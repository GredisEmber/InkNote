package com.inknote.pad.ink

import kotlin.math.pow

/**
 * 压感 → 笔迹宽度系数。
 *
 * ============================ 参数怎么影响"笔锋" ============================
 * 公式：factor = minFactor + (maxFactor - minFactor) * pressure^gamma
 *
 * 粗细对比度（轻写 vs 重写）由两个参数决定，方向必须记对：
 *
 *   · minFactor 越小 → 轻写越细 → 对比越强。
 *     这是最有效的杠杆。minFactor=0.35 意味着"再轻也有 35% 宽"，
 *     变化空间被压掉三分之一以上。
 *
 *   · gamma > 1 → 轻写更细 → 对比更强。
 *     gamma < 1 → 轻写变粗 → 对比被削弱。
 *     ⚠️ 这条容易记反。直觉上"把轻压力段拉开"听起来像是在增强笔锋，
 *     实际上 gamma<1 是把轻写抬粗，正好抹平了粗细差异。
 *     实测数据：Y700 + Lenovo Tab Pen Plus，正常书写压力落在 0.13~0.70。
 *     用 minFactor=0.35 / gamma=0.65，这段压力只映射出 1.66 倍的宽度差
 *     （3.7px vs 6.2px），在 440dpi 屏上就是"看不出笔锋"。
 *
 * ============================ 为什么现在默认线性 ============================
 * gamma=1.0（线性）不是"没调"，而是刻意选的：
 * 线性映射可预测、可解释，出问题时能一眼算出期望宽度。
 * 只有在实测发现某段压力变化不够细腻时，才值得引入 gamma 去非线性拉伸。
 * 先正确，再精致。
 */
class PressureCurve(
    /**
     * 压力为 0 时的宽度系数。
     * 不能是 0（笔迹会消失），也不宜太大（笔锋会被抹平）。
     * 0.12~0.20 是"轻写能看清、又不吃掉对比度"的区间。
     */
    val minFactor: Float = 0.15f,
    /** 压力为 1 时的宽度系数 */
    val maxFactor: Float = 1.0f,
    /** 曲线形状。>1 增强笔锋，<1 削弱笔锋，=1 线性 */
    val gamma: Float = 1.0f,
) {
    init {
        require(minFactor > 0f) { "minFactor 必须大于 0，否则笔迹会消失：$minFactor" }
        require(minFactor <= maxFactor) { "minFactor($minFactor) 不能大于 maxFactor($maxFactor)" }
        require(gamma > 0f) { "gamma 必须大于 0：$gamma" }
    }

    /**
     * @param pressure 原始归一化压力，可以是任意值（内部会 clamp）
     * @return 宽度系数，恒在 [minFactor, maxFactor] 区间内
     */
    fun widthFactor(pressure: Float): Float {
        val p = if (pressure.isNaN()) 0f else pressure.coerceIn(0f, 1f)
        // gamma == 1 时跳过 pow：pow 比乘法贵得多，而这是每个采样点都要走的热路径
        val shaped = if (gamma == 1f) p else p.pow(gamma)
        return minFactor + (maxFactor - minFactor) * shaped
    }
}
