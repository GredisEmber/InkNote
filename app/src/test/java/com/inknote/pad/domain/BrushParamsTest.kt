package com.inknote.pad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 笔刷参数的映射。
 *
 * 这里测的核心只有一条，但它很值钱：
 * **默认值必须精确复现改版前手调出来的那组常量。**
 *
 * 用户升级之后第一眼看到的是四个居中的滑块，手感必须和他已经习惯的
 * 一模一样。以后有人调端点时如果把默认手感改掉了，这几条断言会立刻红。
 */
class BrushParamsTest {

    // ======================================================================
    // 默认值 == 改版前的调校值
    // ======================================================================

    @Test
    fun `默认基础粗细等于原来的默认笔宽`() {
        // 改版前 DEFAULT_INK_WIDTH_RATIO = 0.0076
        assertEquals(0.0076f, BrushParams.DEFAULT.baseWidthRatio, 1e-4f)
    }

    @Test
    fun `默认灵敏度等于线性曲线`() {
        // 改版前 gamma = 1.0
        assertEquals(1.0f, BrushParams.DEFAULT.gamma, 1e-3f)
    }

    @Test
    fun `默认强度等于原来的压感曲线下限`() {
        // 改版前 PressureCurve.minFactor = 0.15
        assertEquals(0.15f, BrushParams.DEFAULT.minFactor, 1e-3f)
    }

    @Test
    fun `默认平滑度等于原来的压感系数和采样间距`() {
        // 改版前 PRESSURE_SMOOTHING_ALPHA = 0.45
        assertEquals(0.45f, BrushParams.DEFAULT.pressureSmoothing, 1e-3f)
        // 改版前 StrokeBuilder.minSampleSpacingPx = 2.5
        assertEquals(2.5f, BrushParams.DEFAULT.sampleSpacingPx, 1e-2f)
    }

    // ======================================================================
    // 端点
    // ======================================================================

    @Test
    fun `粗细两端落在设计范围上`() {
        assertEquals(BrushParams.MIN_WIDTH_RATIO, BrushParams(baseWidth = 0f).baseWidthRatio, 1e-6f)
        assertEquals(BrushParams.MAX_WIDTH_RATIO, BrushParams(baseWidth = 1f).baseWidthRatio, 1e-6f)
    }

    @Test
    fun `粗细随滑块单调递增`() {
        var prev = 0f
        for (i in 0..20) {
            val ratio = BrushParams(baseWidth = i / 20f).baseWidthRatio
            assertTrue("粗细必须单调递增，在 $i/20 处退化", ratio > prev)
            prev = ratio
        }
    }

    @Test
    fun `强度为零时是等宽`() {
        assertEquals(1f, BrushParams(strength = 0f).minFactor, 1e-4f)
    }

    @Test
    fun `强度随滑块递减并在下限处被夹住`() {
        var prev = 1.1f
        for (i in 0..20) {
            val mf = BrushParams(strength = i / 20f).minFactor
            // ⚠️ 是"不增"而不是"严格递减"：minFactor 有下限夹取（见 MIN_FACTOR_FLOOR），
            // 到下限之后就不再下降。第一版断言写成严格递减，在 s=0.8 处失败了 ——
            // 失败的是断言，夹取本身是刻意的（minFactor 归零会让笔迹消失）。
            assertTrue("强度越大最细处不能反变粗，在 $i/20 处退化", mf <= prev)
            prev = mf
        }
        // 夹取只应发生在最末端，中段必须还有可用范围
        assertTrue(
            "强度从 0 到 0.7 必须有明显变化，否则滑块大半段是死的",
            BrushParams(strength = 0f).minFactor - BrushParams(strength = 0.7f).minFactor > 0.3f,
        )
    }

    @Test
    fun `强度拉满也不会让笔迹消失`() {
        // minFactor 归零的话整段笔迹会变透明看不见
        val mf = BrushParams(strength = 1f).minFactor
        assertTrue("最细比例必须大于零，实际 $mf", mf > 0f)
        assertEquals(BrushParams.MIN_FACTOR_FLOOR, mf, 1e-4f)
    }

    @Test
    fun `灵敏度越大gamma越小也就是越容易写粗`() {
        // gamma 越小，同样的轻压力映射出的宽度系数越大
        val low = BrushParams(sensitivity = 0f).gamma
        val high = BrushParams(sensitivity = 1f).gamma
        assertTrue("gamma 必须随灵敏度递减", high < low)

        val lightPressure = 0.3f
        // ⚠️ 宽度系数是 `pressure^gamma` —— 底数是**压力**，指数才是 gamma。
        //
        // 第一版这里写成了 `gamma^pressure`，直觉上"把 gamma 当基数"似乎也说得通，
        // 但那是错的。而 PressureCurve 的注释里恰好就写着"这条容易记反"——
        // 结果我还是在测试里记反了一次。测试失败反而把这条钉住了：
        // 轻压力下 gamma=0.5 给出 0.55 的宽度系数，gamma=2.0 只给出 0.09。
        val lowWidth = lightPressure.toDouble().pow(low.toDouble()).toFloat()
        val highWidth = lightPressure.toDouble().pow(high.toDouble()).toFloat()
        assertTrue("高灵敏度在轻压力下必须更粗", highWidth > lowWidth)
    }

    @Test
    fun `平滑度越大压感系数越小采样越疏`() {
        val crisp = BrushParams(smoothness = 0f)
        val smooth = BrushParams(smoothness = 1f)
        assertTrue("平滑度越大 EMA 系数必须越小", smooth.pressureSmoothing < crisp.pressureSmoothing)
        assertTrue("平滑度越大采样间距必须越大", smooth.sampleSpacingPx > crisp.sampleSpacingPx)
    }

    // ======================================================================
    // 越界输入
    // ======================================================================

    @Test
    fun `越界的滑块值会被夹住而不是产生非法参数`() {
        val weird = BrushParams(
            baseWidth = -5f,
            sensitivity = 99f,
            strength = -1f,
            smoothness = 3f,
        )
        // 不该抛异常，也不该产生 NaN / 越界值
        assertTrue(weird.baseWidthRatio > 0f)
        assertTrue(weird.gamma in BrushParams.MIN_GAMMA..BrushParams.MAX_GAMMA)
        assertTrue(weird.minFactor in 0f..1f)
        assertTrue(weird.pressureSmoothing in BrushParams.MIN_PRESSURE_ALPHA..BrushParams.MAX_PRESSURE_ALPHA)
        assertTrue(weird.sampleSpacingPx in BrushParams.MIN_SAMPLE_SPACING..BrushParams.MAX_SAMPLE_SPACING)
    }
}
