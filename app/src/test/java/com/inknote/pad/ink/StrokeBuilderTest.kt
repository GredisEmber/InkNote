package com.inknote.pad.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 笔迹平滑 / 变宽几何的单元测试。
 *
 * 最关键的一条是 segments are contiguous：分段变宽方案唯一的失败模式
 * 就是"段与段之间没接上"，肉眼看是一串断续的小毛毛虫。
 * 相邻段必须严格首尾相接，这条测试把这个不变量钉死。
 */
class StrokeBuilderTest {

    private val curve = PressureCurve()
    private val baseWidth = 6f

    private fun newBuilder() = StrokeBuilder(widthPx = baseWidth, curve = curve)

    private fun buildStroke(vararg points: InkPoint): Stroke {
        val b = newBuilder()
        points.forEach { b.add(it) }
        b.finish()
        return b.build(0xFF000000.toInt())
    }

    @Test
    fun `轻点一下产生一个零长度线段`() {
        val stroke = buildStroke(InkPoint(10f, 20f, 0.5f))
        assertEquals(1, stroke.segments.size)
        val seg = stroke.segments[0]
        assertEquals("零长度线段才能配合圆头笔帽画出圆点", seg.x0, seg.x1, 1e-6f)
        assertEquals(seg.y0, seg.y1, 1e-6f)
        assertTrue(seg.width > 0f)
    }

    @Test
    fun `相邻线段严格首尾相接`() {
        val b = newBuilder()
        val points = (0 until 40).map { i ->
            InkPoint(
                x = i * 4f,
                y = 50f + 20f * sin(i * 0.3f).toFloat(),
                pressure = 0.2f + 0.015f * i,
            )
        }
        points.forEach { b.add(it) }
        b.finish()
        val stroke = b.build(0)

        assertTrue("采样点太少，测试没有意义", stroke.segments.size > 10)
        for (i in 1 until stroke.segments.size) {
            val prev = stroke.segments[i - 1]
            val cur = stroke.segments[i]
            assertEquals("第 $i 段起点 X 没接上上一段终点", prev.x1, cur.x0, 1e-4f)
            assertEquals("第 $i 段起点 Y 没接上上一段终点", prev.y1, cur.y0, 1e-4f)
        }
    }

    @Test
    fun `所有宽度都落在曲线区间内`() {
        val b = newBuilder()
        (0 until 30).forEach { i ->
            b.add(InkPoint(i * 5f, i * 2f, i / 30f))
        }
        b.finish()
        val stroke = b.build(0)

        val minW = baseWidth * curve.minFactor
        val maxW = baseWidth * curve.maxFactor
        stroke.segments.forEach {
            assertTrue("宽度 ${it.width} 越界", it.width >= minW - 1e-4f && it.width <= maxW + 1e-4f)
        }
    }

    @Test
    fun `坐标和宽度都是有限值`() {
        val b = newBuilder()
        b.add(InkPoint(0f, 0f, Float.NaN))
        b.add(InkPoint(10f, 10f, 0.5f))
        b.add(InkPoint(20f, 0f, 1f))
        b.finish()
        val stroke = b.build(0)

        stroke.segments.forEach {
            assertTrue("x0 非有限", it.x0.isFinite())
            assertTrue("y0 非有限", it.y0.isFinite())
            assertTrue("x1 非有限", it.x1.isFinite())
            assertTrue("y1 非有限", it.y1.isFinite())
            assertTrue("width 非有限或非正", it.width.isFinite() && it.width > 0f)
        }
    }

    @Test
    fun `压力越大线段越粗`() {
        val thin = buildStroke(
            InkPoint(0f, 0f, 0.1f),
            InkPoint(50f, 0f, 0.1f),
            InkPoint(100f, 0f, 0.1f),
        )
        val thick = buildStroke(
            InkPoint(0f, 0f, 0.9f),
            InkPoint(50f, 0f, 0.9f),
            InkPoint(100f, 0f, 0.9f),
        )
        assertTrue(
            "重压的平均宽度应大于轻压",
            thick.segments.map { it.width }.average() > thin.segments.map { it.width }.average(),
        )
    }

    @Test
    fun `add 只返回新增线段且总数与 build 一致`() {
        val b = newBuilder()
        val collected = mutableListOf<InkSegment>()

        // 第 1 个点：构不成曲线
        assertTrue(b.add(InkPoint(0f, 0f, 0.5f)).isEmpty())
        // 第 2 个点：已经能发出 p0 → 第一个中点 这一小段了
        collected += b.add(InkPoint(10f, 0f, 0.5f)).also { assertTrue(it.isNotEmpty()) }
        // 第 3 个点开始进入正常的二次贝塞尔
        collected += b.add(InkPoint(20f, 0f, 0.5f)).also { assertTrue(it.isNotEmpty()) }
        collected += b.add(InkPoint(30f, 0f, 0.5f)).also { assertTrue(it.isNotEmpty()) }
        collected += b.finish().also { assertTrue(it.isNotEmpty()) }

        val stroke = b.build(0)
        assertEquals("增量产出的线段总数必须等于最终几何", collected.size, stroke.segments.size)

        // finish 之后不应再接受新点，否则重复收笔会画出多余线段
        assertTrue(b.add(InkPoint(40f, 0f, 0.5f)).isEmpty())
        assertTrue(b.finish().isEmpty())
    }

    @Test
    fun `直线保持笔直且首尾端点正确`() {
        val stroke = buildStroke(
            InkPoint(0f, 0f, 0.5f),
            InkPoint(30f, 0f, 0.5f),
            InkPoint(60f, 0f, 0.5f),
            InkPoint(90f, 0f, 0.5f),
        )
        // 起点必须精确落在第一个采样点上，否则起笔会短一截
        assertEquals(0f, stroke.segments.first().x0, 1e-3f)
        // 终点必须精确落在最后一个真实点上，否则收笔会短一截
        assertEquals(90f, stroke.segments.last().x1, 1e-3f)
        stroke.segments.forEach { assertEquals("直线不该有 Y 偏移", 0f, it.y0, 1e-3f) }
    }

    @Test
    fun `高密度采样不会产生爆炸数量的线段`() {
        val b = newBuilder()
        // 模拟一次快速甩笔：一次产生一个很长的位移
        b.add(InkPoint(0f, 0f, 0.5f))
        b.add(InkPoint(10f, 0f, 0.5f))
        b.add(InkPoint(3000f, 0f, 0.5f))
        b.add(InkPoint(3010f, 0f, 0.5f))
        b.finish()
        val stroke = b.build(0)
        // maxSamplesPerQuad = 24，两段曲线，加开头一小段和收笔一段，最多 51 段
        assertTrue("线段数 ${stroke.segments.size} 超出预期上限", stroke.segments.size <= 51)
    }

    // ======================================================================
    // 风格修饰
    // ======================================================================

    @Test
    fun `纯压感风格下恒定压力得到恒定宽度`() {
        val b = StrokeBuilder(widthPx = baseWidth, curve = curve, style = StrokeStyle.PRESSURE)
        var x = 0f
        repeat(30) {
            b.add(InkPoint(x, 0f, 0.5f, it * 4L))
            x += 8f
        }
        b.finish()
        val widths = b.build(0).segments.map { it.width }

        val expected = baseWidth * curve.widthFactor(0.5f)
        // 纯压感不做任何端点修饰：首段、中段、末段都必须一样粗。
        // 这条测试是"风格 A 没有被 B/C 的代码污染"的守卫。
        assertEquals("首段不该被削细", expected, widths.first(), 0.02f)
        assertEquals("末段不该被削细", expected, widths.last(), 0.02f)
        assertEquals("中段", expected, widths[widths.size / 2], 0.02f)
    }

    @Test
    fun `笔锋风格让起笔和收笔变细而中段不变`() {
        val b = StrokeBuilder(widthPx = baseWidth, curve = curve, style = StrokeStyle.PRESSURE_TAPER)
        var x = 0f
        repeat(40) {
            b.add(InkPoint(x, 0f, 0.5f, it * 4L))
            x += 10f
        }
        b.finish()
        val widths = b.build(0).segments.map { it.width }

        val mid = widths[widths.size / 2]
        val expected = baseWidth * curve.widthFactor(0.5f)

        assertEquals("中段必须保持原始宽度，渐细不能渗到笔画中段", expected, mid, 0.02f)
        assertTrue("起笔应收细，实际首段=${widths.first()} 中段=$mid", widths.first() < mid * 0.5f)
        assertTrue("收笔应收细，实际末段=${widths.last()} 中段=$mid", widths.last() < mid * 0.5f)
    }

    @Test
    fun `笔锋风格下极短笔画不会被收成沙漏`() {
        // 12px 的短笔画，taperLengthPx=9 时两端渐变会重叠
        val b = StrokeBuilder(widthPx = baseWidth, curve = curve, style = StrokeStyle.PRESSURE_TAPER)
        b.add(InkPoint(0f, 0f, 0.5f, 0L))
        b.add(InkPoint(6f, 0f, 0.5f, 4L))
        b.add(InkPoint(12f, 0f, 0.5f, 8L))
        b.finish()
        val widths = b.build(0).segments.map { it.width }

        assertTrue("短笔画不该出现零宽度", widths.all { it > 0f })
        // 中段至少要有基础宽度的 40%，否则视觉上就是一个沙漏
        val minAcceptable = baseWidth * curve.widthFactor(0.5f) * 0.4f
        assertTrue(
            "短笔画中段收腰过头：中段最大宽度 ${widths.max()} 低于 $minAcceptable",
            widths.max() >= minAcceptable,
        )
    }

    @Test
    fun `速度风格让快速笔画变细`() {
        val slow = buildWithVelocity(stepPx = 4f, stepMs = 8L)
        val fast = buildWithVelocity(stepPx = 20f, stepMs = 4L)

        val slowAvg = slow.segments.map { it.width }.average()
        val fastAvg = fast.segments.map { it.width }.average()
        assertTrue("快速笔画的平均宽度($fastAvg)应明显小于慢速($slowAvg)", fastAvg < slowAvg * 0.75)

        // 中段仍应比纯压感细，但不该细到看不见
        assertTrue("速度调制后宽度不应低于基础宽度的一半", fastAvg > baseWidth * 0.5f * 0.5)
    }

    @Test
    fun `速度风格在时间戳缺失时不会算出无穷大`() {
        // 所有点 timeMillis 都是 0（默认值），dt 恒为 0
        val b = StrokeBuilder(widthPx = baseWidth, curve = curve, style = StrokeStyle.PRESSURE_VELOCITY)
        var x = 0f
        repeat(10) {
            b.add(InkPoint(x, 0f, 0.5f))
            x += 8f
        }
        b.finish()
        val widths = b.build(0).segments.map { it.width }
        assertTrue("宽度出现了非有限值", widths.all { it.isFinite() && it > 0f })
    }

    private fun buildWithVelocity(stepPx: Float, stepMs: Long): Stroke {
        val b = StrokeBuilder(widthPx = baseWidth, curve = curve, style = StrokeStyle.PRESSURE_VELOCITY)
        var x = 0f
        var t = 0L
        repeat(30) {
            b.add(InkPoint(x, 0f, 0.5f, t))
            x += stepPx
            t += stepMs
        }
        b.finish()
        return b.build(0)
    }
}
