package com.inknote.pad.domain

import kotlin.math.pow

/**
 * 笔刷参数。四支滑块。
 *
 * ============================ 四个 0.5 就是现在的手感 ============================
 * 每个映射的**端点都是为了"0.5 恰好复现原来手调出来的值"而选的**：
 *
 * | 滑块       | 0.0            | 0.5（默认）      | 1.0            |
 * |------------|----------------|------------------|----------------|
 * | 基础粗细   | 0.0027         | **0.0076**       | 0.0215         |
 * | 压感灵敏度 | γ=2.0 要用力压 | **γ=1.0 线性**   | γ=0.5 轻触即粗 |
 * | 压感强度   | 等宽           | **minFactor=.15**| 极强对比       |
 * | 平滑度     | α=.90 / 1.2px  | **α=.45 / 2.5px**| α=.225 / 5.2px |
 *
 * 这不是巧合，是刻意设计的：改版之前调了很久的那套参数就是基线，
 * 用户一进来看到的滑块全在中间，手感和他已经习惯的完全一致。
 * 如果默认值偏离中心，用户第一件事就是"这跟我昨天写的不一样"。
 *
 * 顺带的好处是测试好写：只要断言"默认参数的派生值等于那组常量"，
 * 就能防止以后有人调端点时把默认手感悄悄改掉。
 *
 * ============================ 为什么粗细和平滑度用几何映射 ============================
 * 线宽从 3px 变到 4px 是 +33%，从 12px 变到 13px 只有 +8% ——
 * 同样的"加 1px"，前者看得出来后者看不出来。
 * 所以**等比变化才是等步的**，滑块必须走几何映射而不是线性。
 * 灵敏度（gamma）和平滑度（α）本身就是倍率关系，同理。
 */
data class BrushParams(
    /** 基础粗细：满压力时的线宽，占文档短边的比例（经几何映射） */
    val baseWidth: Float = 0.5f,
    /** 压感灵敏度：达到最粗需要多大的压力。越大越容易写粗 */
    val sensitivity: Float = 0.5f,
    /** 压感强度：最粗与最细的差距幅度。0 = 等宽 */
    val strength: Float = 0.5f,
    /** 平滑度：同时作用于压感去噪和曲线采样密度 */
    val smoothness: Float = 0.5f,
) {

    /** 基础线宽占文档短边的比例 */
    val baseWidthRatio: Float
        get() = geometric(
            from = MIN_WIDTH_RATIO,
            to = MAX_WIDTH_RATIO,
            t = baseWidth,
        )

    /**
     * 压力曲线的下限系数 —— 也就是"最细能细到满宽的几成"。
     *
     * 用幂映射而不是线性：线性的话要让 0.5 落在 0.15 就得把端点压到负数，
     * 而幂映射天然满足 (0→1) 与 (1→0) 两个端点都必须成立的约束。
     * 指数 2.74 是解 `0.5^p = 0.15` 得来的。
     */
    val minFactor: Float
        get() {
            val s = strength.coerceIn(0f, 1f)
            return (1f - s).pow(STRENGTH_EXPONENT).coerceAtLeast(MIN_FACTOR_FLOOR)
        }

    /** 压力曲线的形状。>1 要用力压才粗，<1 轻触就粗 */
    val gamma: Float
        get() = geometric(from = MAX_GAMMA, to = MIN_GAMMA, t = sensitivity)

    /** 压力一阶低通系数。越小越平滑，滞后越大 */
    val pressureSmoothing: Float
        get() = geometric(from = MAX_PRESSURE_ALPHA, to = MIN_PRESSURE_ALPHA, t = smoothness)

    /**
     * 曲线离散的最小间距（文档单位）。
     *
     * 越大曲线越顺（拐角被合并），越小细节越多但线条越"毛"。
     */
    val sampleSpacingPx: Float
        get() = geometric(from = MIN_SAMPLE_SPACING, to = MAX_SAMPLE_SPACING, t = smoothness)

    private fun geometric(from: Float, to: Float, t: Float): Float {
        val s = t.coerceIn(0f, 1f)
        return from * (to / from).pow(s)
    }

    companion object {
        /** 极细档的下限。比原来的"极细"再细一点，给批注留空间 */
        const val MIN_WIDTH_RATIO = 0.0027f

        /** 极粗档的上限 */
        const val MAX_WIDTH_RATIO = 0.0215f

        /** 灵敏度两端：γ=2 需要用力压，γ=0.5 轻触即粗 */
        const val MAX_GAMMA = 2.0f
        const val MIN_GAMMA = 0.5f

        /**
         * 强度映射的指数，解自 `0.5^p = 0.15`。
         * 那个 0.15 是改版前手调出来的 minFactor。
         */
        const val STRENGTH_EXPONENT = 2.74f

        /** 强度拉满时的最细比例。不能到 0，否则笔迹会整段消失 */
        const val MIN_FACTOR_FLOOR = 0.03f

        /** 平滑度两端：α=0.9 几乎不过滤，α=0.225 明显平滑 */
        const val MAX_PRESSURE_ALPHA = 0.9f
        const val MIN_PRESSURE_ALPHA = 0.225f

        /** 采样间距两端（文档单位）。0.5 处恰好是 2.5px */
        const val MIN_SAMPLE_SPACING = 1.2f
        const val MAX_SAMPLE_SPACING = 5.2f

        /** 全部居中 —— 等价于改版前的手感 */
        val DEFAULT = BrushParams()
    }
}
