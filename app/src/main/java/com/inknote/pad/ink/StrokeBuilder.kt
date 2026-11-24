package com.inknote.pad.ink

import kotlin.math.ceil
import kotlin.math.hypot

/**
 * 增量式笔迹平滑器 + 压感变宽 + 可选风格修饰。
 *
 * ============================ 平滑算法 ============================
 * 中点二次贝塞尔（midpoint quadratic）：
 * 对连续三个采样点 p[i-1]、p[i]、p[i+1]，
 * 以 p[i] 为控制点、以 mid(p[i-1],p[i]) 和 mid(p[i],p[i+1]) 为端点，
 * 画一段二次贝塞尔曲线。
 *
 * 相邻两段天然共用同一个中点，所以曲线是 C1 连续的，不会出现折角 ——
 * 比"直接把采样点连成折线"平滑得多，又比 Catmull-Rom 少一半计算量。
 *
 * ============================ 为什么必须增量 ============================
 * 每来一个新采样点，只产出"这一小段"对应的线段，绝不回头重算历史。
 * 否则一笔写 2 秒（240Hz 笔 = 480 个点），每帧都要重算上千个点，
 * 144Hz 屏上必掉帧。代价是有一个采样点的延迟，240Hz 下 4ms，不可感知。
 *
 * ============================ 起笔收笔渐细为什么要"扣住尾巴" ============================
 * 收笔渐细需要知道"离笔画终点还有多远"，可笔画还没写完，终点在哪是未知的。
 * 所以 PRESSURE_TAPER 模式下，最后 [taperLengthPx] 那一段几何【先不交出去】，
 * 等它离当前笔尖超过 taperLengthPx 之后再发；到 finish() 时剩下的自然就是
 * 真正的笔画末尾，这时才按到终点的距离施加渐细。
 *
 * 代价是笔尖有一小段（taperLengthPx ≈ 0.9mm）视觉滞后。
 * 这个滞后不可感知，而且换来的是"收笔不收尖"这个手感上明显更好的结果。
 *
 * 其他两种风格不需要扣尾巴（holdback = 0），几何立即交出去，零延迟。
 */
class StrokeBuilder(
    /** 基础线宽（px），乘以各种系数后才是实际宽度 */
    private val widthPx: Float,
    private val curve: PressureCurve,
    private val style: StrokeStyle = StrokeStyle.PRESSURE,
    /** 曲线离散的最小间距（px）。越小越平滑，越小也越费算力 */
    private val minSampleSpacingPx: Float = 2.5f,
    /** 单段曲线最多离散成多少段，防止快速甩笔时一次产生几千个线段 */
    private val maxSamplesPerQuad: Int = 24,
    /**
     * 起笔/收笔渐细的长度（px）。
     * 9px 在 440dpi 上约 0.55mm，是"看得出收尖但不显得刻意"的量级。
     * 太短则看不出笔锋，太长则短笔画（点、挑）会被收成沙漏。
     */
    private val taperLengthPx: Float = 9f,
    /** 笔画最末端收细到基础宽度的多少倍 */
    private val taperMinFactor: Float = 0.22f,
    /** 写得最快时宽度降到基础宽度的多少倍 */
    private val velocityMinFactor: Float = 0.5f,
    /** 达到这个速度（px/ms）就按 velocityMinFactor 收细 */
    private val velocityFullSpeed: Float = 2.0f,
    /**
     * 宽度是否恒定（荧光笔用）。
     *
     * 真实的荧光笔是**等宽**的 —— 它不会因为手轻手重而变粗变细，
     * 走的是压感曲线的话会显得像在写粗体字而不是在涂色。
     *
     * 这件事放在这里而不是在调用方各写一遍：几何的重建路径
     * （载入笔记时的 Stroke.rebuild）也必须用同一套规则，
     * 否则"存下去时等宽、读回来时带压感"，同一笔在磁盘和屏幕上会长得不一样。
     */
    private val uniformWidth: Boolean = false,
) {
    private val points = ArrayList<InkPoint>(256)
    private val allSegments = ArrayList<InkSegment>(512)

    /** 上一个采样点，也就是下一段曲线的控制点 p[i] */
    private var prevPoint: InkPoint? = null

    /** 上一个中点，也就是下一段曲线的起点 */
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var prevMidPressure = 0f
    private var hasPrevMid = false

    private var finished = false

    // ---- 速度调制（风格 C）的状态 ----
    private var prevTimeMillis = 0L
    /** 平滑后的速度（px/ms）。原始速度抖动很大，直接映射会让宽度忽粗忽细 */
    private var smoothedSpeed = 0f
    private var hasSpeed = false

    // ---- 起收笔渐细（风格 B）的状态 ----
    /** 已生成几何的总长度 */
    private var traveledLength = 0f
    /** 已交给调用方的几何长度，也等于下一段待发几何"离笔画起点"的距离 */
    private var emittedLength = 0f
    /** allSegments 中下一个待发出的下标 */
    private var emitCursor = 0

    val pointCount: Int get() = points.size

    /**
     * 送入一个采样点。
     * @return 这次可以画出去的线段（风格 B 下会少于新生成的段数，因为要扣住尾巴）
     */
    fun add(point: InkPoint): List<InkSegment> {
        if (finished) return emptyList()
        points.add(point)

        updateSpeed(point)

        val p = prevPoint
        if (p == null) {
            prevPoint = point
            return emptyList()
        }

        val midX = (p.x + point.x) * 0.5f
        val midY = (p.y + point.y) * 0.5f
        val midPressure = (p.pressure + point.pressure) * 0.5f

        if (!hasPrevMid) {
            prevMidX = midX
            prevMidY = midY
            prevMidPressure = midPressure
            hasPrevMid = true
            // 补上 p0 → 第一个中点 这一段。
            // 经典的中点二次贝塞尔是 moveTo(p0) 然后一路 quadTo，
            // 起点 p0 本身是画出来的。如果这里不发这一小段，笔画开头会缺掉
            // 【半个采样间距】—— 144Hz 批处理下这个间距可能到 7px，
            // 起笔会明显短一截，而且橡皮的包围盒也会跟着漏掉开头。
            val w = widthPx * widthFactorFor(p.pressure) * velocityFactor()
            appendSegments(listOf(InkSegment(p.x, p.y, midX, midY, w)))
            prevPoint = point
            return drain(force = false)
        }

        val newSegments = quadSegments(
            ax = prevMidX, ay = prevMidY, ap = prevMidPressure,
            cx = p.x, cy = p.y, cp = p.pressure,
            bx = midX, by = midY, bp = midPressure,
        )
        prevMidX = midX
        prevMidY = midY
        prevMidPressure = midPressure
        prevPoint = point

        appendSegments(newSegments)
        return drain(force = false)
    }

    /**
     * 收笔：把最后一个中点到最后一个真实点补上，并把扣住的尾巴全部发出。
     * 不补尾段的话收笔处会缺一小截。
     */
    fun finish(): List<InkSegment> {
        if (finished) return emptyList()
        finished = true

        val p = prevPoint ?: return emptyList()

        if (!hasPrevMid) {
            // 只按下没移动（轻点一下）：零长度线段 + 圆头笔帽 = 圆点
            val w = widthPx * widthFactorFor(p.pressure) * velocityFactor()
            appendSegments(listOf(InkSegment(p.x, p.y, p.x, p.y, w)))
        } else {
            val w = widthPx * widthFactorFor(p.pressure) * velocityFactor()
            appendSegments(listOf(InkSegment(prevMidX, prevMidY, p.x, p.y, w)))
        }

        return drain(force = true)
    }

    /** 组装成最终的 Stroke（几何已经全部算好，渲染时不再计算） */
    fun build(colorArgb: Int, isHighlighter: Boolean = false): Stroke = Stroke(
        colorArgb = colorArgb,
        widthPx = widthPx,
        style = style,
        isHighlighter = isHighlighter,
        points = ArrayList(points),
        segments = ArrayList(allSegments),
        bounds = computeBounds(allSegments),
    )

    /**
     * 预计算包围盒。
     * 必须把半线宽算进去 —— 否则橡皮贴着笔画边缘时圆的包围盒不相交，
     * 会被快速排除掉，表现就是"描边擦不掉"。
     */
    private fun computeBounds(segments: List<InkSegment>): InkBounds {
        if (segments.isEmpty()) return InkBounds.EMPTY

        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE

        for (i in segments.indices) {
            val s = segments[i]
            val half = s.width * 0.5f
            left = minOf(left, s.x0 - half, s.x1 - half)
            top = minOf(top, s.y0 - half, s.y1 - half)
            right = maxOf(right, s.x0 + half, s.x1 + half)
            bottom = maxOf(bottom, s.y0 + half, s.y1 + half)
        }
        return InkBounds(left, top, right, bottom)
    }

    // ======================================================================
    // 速度
    // ======================================================================

    private fun updateSpeed(point: InkPoint) {
        if (style != StrokeStyle.PRESSURE_VELOCITY) return
        val p = prevPoint
        if (p == null) {
            prevTimeMillis = point.timeMillis
            return
        }
        val dt = point.timeMillis - prevTimeMillis
        prevTimeMillis = point.timeMillis
        // dt <= 0 说明时间戳没填或重复，保持上一步的速度，不要算出无穷大
        if (dt <= 0L) return

        val dist = hypot(point.x - p.x, point.y - p.y)
        val speed = dist / dt

        if (!hasSpeed) {
            smoothedSpeed = speed
            hasSpeed = true
        } else {
            // 用较重的平滑：速度本身噪声大，不平滑会让笔画宽窄乱跳
            smoothedSpeed += VELOCITY_SMOOTHING_ALPHA * (speed - smoothedSpeed)
        }
    }

    /**
     * 这一笔在某个压力下的宽度系数。
     *
     * 荧光笔（[uniformWidth]）恒为 1：真实的荧光笔是等宽的，
     * 会因为手轻手重而变粗变细的话，看起来像在写粗体字而不是在涂色。
     *
     * 放在这里统一判断而不是在五个调用点各写一遍 ——
     * 实时书写和载入重建（Stroke.rebuild）走的是同一套规则，
     * 否则会出现"存下去时等宽、读回来时带压感"。
     */
    private fun widthFactorFor(pressure: Float): Float =
        if (uniformWidth) 1f else curve.widthFactor(pressure)

    /** 速度调制系数。荧光笔恒为 1 —— 速度调制是"笔锋"的一部分，而荧光笔没有笔锋 */
    private fun velocityFactor(): Float {
        if (uniformWidth) return 1f
        if (style != StrokeStyle.PRESSURE_VELOCITY) return 1f
        val t = (smoothedSpeed / velocityFullSpeed).coerceIn(0f, 1f)
        return 1f - (1f - velocityMinFactor) * t
    }

    // ======================================================================
    // 几何生成与发出
    // ======================================================================

    private fun appendSegments(segments: List<InkSegment>) {
        if (segments.isEmpty()) return
        allSegments.addAll(segments)
        for (i in segments.indices) {
            val s = segments[i]
            traveledLength += hypot(s.x1 - s.x0, s.y1 - s.y0)
        }
    }

    /**
     * 把"已经可以定型"的几何交给调用方。
     *
     * PRESSURE_TAPER 之外的风格 holdback = 0，等于全部立即发出，没有延迟。
     * PRESSURE_TAPER 会扣住最后 taperLengthPx 那一段，等它离笔尖足够远——
     * 那时它就不可能再是"收笔"了，可以安全地按起点距离施加起笔渐细。
     */
    private fun drain(force: Boolean): List<InkSegment> {
        if (emitCursor >= allSegments.size) return emptyList()

        val holdback = if (!uniformWidth && style == StrokeStyle.PRESSURE_TAPER) taperLengthPx else 0f
        val out = ArrayList<InkSegment>()

        while (emitCursor < allSegments.size) {
            val seg = allSegments[emitCursor]
            val len = hypot(seg.x1 - seg.x0, seg.y1 - seg.y0)
            val endDistance = emittedLength + len

            if (!force && endDistance > traveledLength - holdback) break

            val factor = taperFactor(fromStart = emittedLength, endDistance = endDistance)
            // 就地替换成最终几何：build() 拿到的就是已经施加过修饰的版本，
            // 撤销重绘时不必再算一遍
            allSegments[emitCursor] = if (factor >= 0.999f) seg else seg.copy(width = seg.width * factor)

            out.add(allSegments[emitCursor])
            emittedLength = endDistance
            emitCursor++
        }
        return out
    }

    /**
     * 起笔/收笔的渐细系数。
     *
     * 用【固定的】taperLengthPx，不按笔画长度缩放 —— 这是刻意的：
     * 缩放会让"早期发出的段"和"收笔时才发的段"用上不同的参考长度，
     * 结果就是笔画中间出现一个宽度台阶。
     * 固定长度的代价只是很短的笔画（点、挑）中段略微收腰，那反而更像笔锋。
     */
    private fun taperFactor(fromStart: Float, endDistance: Float): Float {
        if (style != StrokeStyle.PRESSURE_TAPER) return 1f
        val fromEnd = traveledLength - endDistance
        return minOf(rampUp(fromStart), rampUp(fromEnd))
    }

    private fun rampUp(distanceFromEnd: Float): Float {
        if (distanceFromEnd >= taperLengthPx) return 1f
        val t = (distanceFromEnd / taperLengthPx).coerceIn(0f, 1f)
        return taperMinFactor + (1f - taperMinFactor) * t
    }

    /**
     * 把一段二次贝塞尔离散成线段。
     * 位置和压力都用同一个二次贝塞尔参数化，保证"最粗的地方"正好在曲线弯得最狠的地方。
     */
    private fun quadSegments(
        ax: Float, ay: Float, ap: Float,
        cx: Float, cy: Float, cp: Float,
        bx: Float, by: Float, bp: Float,
    ): List<InkSegment> {
        val chord = hypot(bx - ax, by - ay)
        val count = ceil(chord / minSampleSpacingPx).toInt()
            .coerceIn(MIN_SAMPLES_PER_QUAD, maxSamplesPerQuad)

        val vFactor = velocityFactor()
        val out = ArrayList<InkSegment>(count)
        var px = ax
        var py = ay
        var pw = widthPx * widthFactorFor(ap) * vFactor

        for (i in 1..count) {
            val t = i.toFloat() / count
            val u = 1f - t

            val x = u * u * ax + 2f * u * t * cx + t * t * bx
            val y = u * u * ay + 2f * u * t * cy + t * t * by
            val pressure = u * u * ap + 2f * u * t * cp + t * t * bp
            val w = widthPx * widthFactorFor(pressure) * vFactor

            // 用两端宽度的平均值：比取起点更平滑，比取最大值更不容易把细笔锋糊掉
            out.add(InkSegment(px, py, x, y, (pw + w) * 0.5f))

            px = x
            py = y
            pw = w
        }
        return out
    }

    private companion object {
        /**
         * 每段曲线至少离散成 2 段。
         * 就算弦长为 0 也要 2 段，否则一段曲线会被压成单个线段，
         * 在拐弯处会退化成折角。
         */
        const val MIN_SAMPLES_PER_QUAD = 2

        /** 速度平滑系数。比压力更需要平滑，因为速度的原始抖动更大 */
        const val VELOCITY_SMOOTHING_ALPHA = 0.25f
    }

    /**
     * 已经产生的全部线段。
     *
     * 用于"整页重画时把正在写的那一笔也画上"。
     *
     * 没有这个方法的话，重画会清空位图却只重建已提交的笔画 ——
     * 表现为"重画的那一帧笔迹闪一下、然后缺一截"。
     * （整页重画原本只在撤销/擦除时发生，那些时刻不会有笔在写；
     * 但画布改成位图分层之后，页面上第一笔荧光笔落笔时会触发一次重画，正好撞上。）
     */
    fun accumulatedSegments(): List<InkSegment> = allSegments
}
