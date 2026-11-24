package com.inknote.pad.ink

/**
 * 画布视口：负责**文档坐标 ↔ 视图坐标**的换算。
 *
 * ============================ 为什么把它从 View 里抽出来 ============================
 * 这套换算原本内联在 NoteCanvasView 的四个字段里（viewScale / viewOffsetX / viewOffsetY）。
 * 对"把整页塞进视图"这一种用法，内联就够了。
 *
 * 但接下来要做的事需要它变成一个**可以自由移动和缩放的窗口**：
 *  · 无缝连续滚动 —— 视口要在多页之间连续平移
 *  · 无限画布 —— 视口可以停在任意位置、任意缩放，还要能算出"哪些内容需要画"
 *
 * 抽出来的另一个好处是**它变成纯数学、可以单元测试**。
 * 这类代码写错了表现为"缩放时画面往一边跑"或者"笔尖和墨迹差了几个像素"，
 * 靠肉眼调试非常痛苦；而它本身没有任何 Android 依赖，没有理由不测。
 *
 * ============================ 为什么不直接存一个 Matrix ============================
 * 一个 Matrix 能表达同样的变换，但**读不出语义**：
 * "现在缩放多少倍"要从矩阵里反解，而"平移多少"会被缩放污染。
 * 分开存 scale + offset 之后，缩放比例的读取是 O(1) 的，
 * 而且缩放锚点、可见范围这些计算都能用小学几何写清楚。
 * 需要 Matrix 的地方（画布变换、MotionEvent.transform）在调用点现拼，成本可忽略。
 *
 * 本类**刻意不引用任何 Android 类型** —— 包括 PointF 和 Matrix。
 * 一旦引用，单元测试就跑不起来了（JVM 上的 Android 类是空壳）。
 */
class CanvasViewport {

    /** 文档 → 视图的缩放倍数 */
    var scale: Float = 1f
        private set

    /** 文档原点在视图坐标里的位置（px） */
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    /**
     * "塞满一屏"时的缩放倍数。
     *
     * 缩放上下限是相对它定义的，而不是绝对值 ——
     * 因为 A3 的"塞满"和"与屏幕相同"的"塞满"差了十几倍，
     * 用绝对倍率的话，同一次捏合在两种纸型上得到的手感完全不同。
     */
    var fitScale: Float = 1f
        private set

    /**
     * 把整份文档等比居中放进视图。
     *
     * ⚠️ 这是"显示老笔记"的唯一路径，行为必须和重构之前**逐像素一致**：
     *   scale = min(viewW/docW, viewH/docH)
     *   offsetX = (viewW - docW*scale) / 2
     *   offsetY = (viewH - docH*scale) / 2
     * 也就是等比缩放 + 居中留边。不做非等比拉伸，否则笔迹会被压扁。
     */
    fun fit(docWidth: Float, docHeight: Float, viewWidth: Int, viewHeight: Int) {
        if (docWidth <= 0f || docHeight <= 0f || viewWidth <= 0 || viewHeight <= 0) return
        val s = minOf(viewWidth / docWidth, viewHeight / docHeight)
        scale = s
        fitScale = s
        offsetX = (viewWidth - docWidth * s) * 0.5f
        offsetY = (viewHeight - docHeight * s) * 0.5f
    }

    // ======================================================================
    // 换算
    // ======================================================================

    fun docToViewX(docX: Float): Float = offsetX + docX * scale
    fun docToViewY(docY: Float): Float = offsetY + docY * scale

    fun viewToDocX(viewX: Float): Float = (viewX - offsetX) / scale
    fun viewToDocY(viewY: Float): Float = (viewY - offsetY) / scale

    // ======================================================================
    // 平移与缩放（6c 无缝滚动 / 6d 无限画布用；6b 只保证它们是对的）
    // ======================================================================

    /** 按视图像素平移。[dxView] 为正表示内容向右移动 */
    fun panBy(dxView: Float, dyView: Float) {
        offsetX += dxView
        offsetY += dyView
    }

    /**
     * 以视图上的某一点为锚缩放。
     *
     * ============================ 锚点不动是唯一的要求 ============================
     * 捏合缩放时，两指中点下的那个文档坐标必须**停在原地**，
     * 否则画面会从手指底下滑走 —— 这是所有地图/看图应用最基本的手感要求。
     *
     * 推导：设锚点视图坐标 (px, py)，它对应的文档坐标
     *   dx = (px - offsetX) / scale
     * 缩放后要求同一个 dx 仍映射回 px：
     *   px = offsetX' + dx * scale'
     * 解出 offsetX' = px - dx * scale'
     *
     * 上下限相对 [fitScale] 而不是绝对值，理由见 fitScale 的注释。
     */
    fun zoomBy(factor: Float, pivotViewX: Float, pivotViewY: Float) {
        if (factor <= 0f || !factor.isFinite()) return

        val docX = viewToDocX(pivotViewX)
        val docY = viewToDocY(pivotViewY)

        val target = (scale * factor).coerceIn(
            fitScale * MIN_SCALE_FACTOR,
            fitScale * MAX_SCALE_FACTOR,
        )
        // 已经顶到上下限时就不要再动 offset 了，否则画面会持续被"推"向锚点
        if (target == scale) return

        scale = target
        offsetX = pivotViewX - docX * target
        offsetY = pivotViewY - docY * target
    }

    // ======================================================================
    // 可见范围（6d 的视口裁剪要用）
    // ======================================================================

    fun visibleDocLeft(): Float = viewToDocX(0f)
    fun visibleDocTop(): Float = viewToDocY(0f)
    fun visibleDocRight(viewWidth: Int): Float = viewToDocX(viewWidth.toFloat())
    fun visibleDocBottom(viewHeight: Int): Float = viewToDocY(viewHeight.toFloat())

    /**
     * 把视口限制在文档范围内，不留多余的空白。
     *
     * @param marginDoc 允许露出文档外的余量（文档单位）。给一点余量是为了
     *        手感上"能稍微拉出去一点再弹回来"，不留的话边缘会像撞墙。
     */
    fun clampToDocument(
        docWidth: Float,
        docHeight: Float,
        viewWidth: Int,
        viewHeight: Int,
        marginDoc: Float = 0f,
    ) {
        if (docWidth <= 0f || docHeight <= 0f) return

        val scaledW = docWidth * scale
        val scaledH = docHeight * scale
        val marginPx = marginDoc * scale

        // 内容比视图小时：居中，不给拖
        // 内容比视图大时：限制在 [viewSize - scaledSize - margin, margin]
        offsetX = if (scaledW <= viewWidth) {
            (viewWidth - scaledW) * 0.5f
        } else {
            offsetX.coerceIn(
                minimumValue = viewWidth - scaledW - marginPx,
                maximumValue = marginPx,
            )
        }
        offsetY = if (scaledH <= viewHeight) {
            (viewHeight - scaledH) * 0.5f
        } else {
            offsetY.coerceIn(
                minimumValue = viewHeight - scaledH - marginPx,
                maximumValue = marginPx,
            )
        }
    }

    fun copyFrom(other: CanvasViewport) {
        scale = other.scale
        offsetX = other.offsetX
        offsetY = other.offsetY
        fitScale = other.fitScale
    }

    companion object {
        /** 最小缩到"塞满屏幕"的多少倍 */
        const val MIN_SCALE_FACTOR = 0.4f

        /** 最大放大到"塞满屏幕"的多少倍 */
        const val MAX_SCALE_FACTOR = 10f
    }
}
