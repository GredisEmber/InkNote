package com.inknote.pad.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压感映射的单元测试。
 *
 * 这部分是纯数学，能在 JVM 上跑，所以不靠肉眼验证 ——
 * 压感曲线一旦写错，症状是"笔锋怪怪的"，肉眼根本判断不出是曲线错了还是硬件飘了。
 * 有测试钉死边界，Step 3 标定时才能确定改的是曲线而不是别的地方。
 */
class PressureCurveTest {

    private val curve = PressureCurve()

    @Test
    fun `零压力映射到 minFactor`() {
        assertEquals(curve.minFactor, curve.widthFactor(0f), 1e-6f)
    }

    @Test
    fun `满压力映射到 maxFactor`() {
        assertEquals(curve.maxFactor, curve.widthFactor(1f), 1e-6f)
    }

    @Test
    fun `宽度系数随压力单调不减`() {
        var prev = curve.widthFactor(0f)
        var p = 0f
        while (p <= 1f) {
            val w = curve.widthFactor(p)
            assertTrue("压力 $p 处宽度 $w 反而小于上一点的 $prev", w >= prev - 1e-6f)
            prev = w
            p += 0.01f
        }
    }

    @Test
    fun `超出零到一区间的压力被截断`() {
        assertEquals(curve.minFactor, curve.widthFactor(-5f), 1e-6f)
        assertEquals(curve.maxFactor, curve.widthFactor(9f), 1e-6f)
    }

    @Test
    fun `NaN 压力按 0 处理而不是扩散成 NaN`() {
        val w = curve.widthFactor(Float.NaN)
        assertTrue("NaN 压力产生了非有限宽度", w.isFinite())
        assertEquals(curve.minFactor, w, 1e-6f)
    }

    @Test
    fun `gamma 为 1 时是线性插值`() {
        val linear = PressureCurve(minFactor = 0.5f, maxFactor = 1.5f, gamma = 1f)
        assertEquals(1.0f, linear.widthFactor(0.5f), 1e-6f)
        assertEquals(0.75f, linear.widthFactor(0.25f), 1e-6f)
    }

    @Test
    fun `gamma 小于 1 会抬高轻压力段的宽度`() {
        val soft = PressureCurve(minFactor = 0.01f, maxFactor = 1f, gamma = 0.5f)
        val linear = PressureCurve(minFactor = 0.01f, maxFactor = 1f, gamma = 1f)
        assertTrue(
            "gamma=0.5 在轻压力处应比线性更粗",
            soft.widthFactor(0.2f) > linear.widthFactor(0.2f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `minFactor 为 0 被拒绝`() {
        // 为 0 意味着"压力为 0 时笔迹完全消失"，属于配置错误而非合法用法
        PressureCurve(minFactor = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `minFactor 大于 maxFactor 被拒绝`() {
        PressureCurve(minFactor = 2f, maxFactor = 1f)
    }
}
