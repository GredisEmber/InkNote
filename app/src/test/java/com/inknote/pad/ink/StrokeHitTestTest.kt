package com.inknote.pad.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 橡皮命中测试的单元测试。
 *
 * 这是 Step 3 里最值得测的部分：命中测试写错了，症状是"某些地方擦不掉"
 * 或者"擦一下掉了三笔"，两种都很难在真机上快速定位 —— 你只会觉得橡皮"有点怪"。
 */
class StrokeHitTestTest {

    /** 一条从 (0,0) 到 (100,0) 的横线，线宽恒为 10（minFactor=maxFactor=1） */
    private fun horizontalStroke(widthPx: Float = 10f): Stroke {
        val curve = PressureCurve(minFactor = 1f, maxFactor = 1f, gamma = 1f)
        val b = StrokeBuilder(widthPx = widthPx, curve = curve)
        b.add(InkPoint(0f, 0f, 0.5f))
        b.add(InkPoint(50f, 0f, 0.5f))
        b.add(InkPoint(100f, 0f, 0.5f))
        b.finish()
        return b.build(0)
    }

    /** 轻点一下产生的圆点 */
    private fun dotStroke(widthPx: Float = 10f): Stroke {
        val curve = PressureCurve(minFactor = 1f, maxFactor = 1f, gamma = 1f)
        val b = StrokeBuilder(widthPx = widthPx, curve = curve)
        b.add(InkPoint(40f, 40f, 0.5f))
        b.finish()
        return b.build(0)
    }

    // ======================================================================
    // 包围盒
    // ======================================================================

    @Test
    fun `包围盒必须把半线宽算进去`() {
        val stroke = horizontalStroke(widthPx = 10f)
        // 笔画中心线在 y=0，线宽 10 → 上下各到 ±5
        assertEquals(-5f, stroke.bounds.top, 0.6f)
        assertEquals(5f, stroke.bounds.bottom, 0.6f)
        // 起点就是第一个采样点 (0,0)，终点是 (100,0)，两端各外扩半线宽
        assertEquals(-5f, stroke.bounds.left, 0.6f)
        assertEquals(105f, stroke.bounds.right, 0.6f)
    }

    @Test
    fun `圆心在盒内必然相交`() {
        val box = InkBounds(0f, 0f, 10f, 10f)
        assertTrue(box.intersectsCircle(5f, 5f, 1f))
    }

    @Test
    fun `圆心在盒外但圆覆盖到盒边也算相交`() {
        val box = InkBounds(0f, 0f, 10f, 10f)
        assertTrue(box.intersectsCircle(14f, 5f, 5f))
        assertFalse(box.intersectsCircle(14f, 5f, 3.9f))
    }

    // ======================================================================
    // 命中
    // ======================================================================

    @Test
    fun `圆心压在笔画正上方时命中`() {
        val stroke = horizontalStroke()
        assertTrue(StrokeHitTest.hits(stroke, 60f, 0f, radius = 8f))
    }

    @Test
    fun `圆心远离笔画时不命中`() {
        val stroke = horizontalStroke()
        assertFalse(StrokeHitTest.hits(stroke, 60f, 200f, radius = 8f))
    }

    @Test
    fun `圆只碰到线宽边缘时也应命中`() {
        val stroke = horizontalStroke(widthPx = 10f)
        // 笔画中心线 y=0，半线宽 5；圆心在 y=11、半径 8 → 覆盖到 y=3，与笔画相交
        assertTrue(StrokeHitTest.hits(stroke, 60f, 11f, radius = 8f))
        // 圆心在 y=20、半径 8 → 覆盖到 y=12，够不着 y=5 的边缘
        assertFalse(StrokeHitTest.hits(stroke, 60f, 20f, radius = 8f))
    }

    @Test
    fun `圆碰到端点圆头时命中`() {
        val stroke = horizontalStroke()
        // 终点在 x=100，半径 8 的圆放在 x=106 应该够得着
        assertTrue(StrokeHitTest.hits(stroke, 106f, 0f, radius = 8f))
        assertFalse(StrokeHitTest.hits(stroke, 120f, 0f, radius = 8f))
    }

    @Test
    fun `零长度的圆点笔迹也能被命中`() {
        val dot = dotStroke()
        assertTrue(StrokeHitTest.hits(dot, 40f, 40f, radius = 4f))
        assertTrue(StrokeHitTest.hits(dot, 44f, 40f, radius = 4f))
        assertFalse(StrokeHitTest.hits(dot, 90f, 40f, radius = 4f))
    }

    // ======================================================================
    // 距离函数
    // ======================================================================

    @Test
    fun `点到线段的距离在段内取垂足`() {
        // 线段 (0,0)-(10,0)，点 (5,3) → 距离 3
        assertEquals(9f, StrokeHitTest.distanceSquaredToSegment(5f, 3f, 0f, 0f, 10f, 0f), 1e-3f)
    }

    @Test
    fun `点到线段的距离在段外取端点`() {
        // 点 (20,0) 投到线段外，最近点是 (10,0) → 距离 10
        assertEquals(100f, StrokeHitTest.distanceSquaredToSegment(20f, 0f, 0f, 0f, 10f, 0f), 1e-3f)
        // 负方向同理
        assertEquals(100f, StrokeHitTest.distanceSquaredToSegment(-10f, 0f, 0f, 0f, 10f, 0f), 1e-3f)
    }

    @Test
    fun `零长度线段的距离退化成点距`() {
        assertEquals(25f, StrokeHitTest.distanceSquaredToSegment(3f, 4f, 0f, 0f, 0f, 0f), 1e-3f)
    }
}
