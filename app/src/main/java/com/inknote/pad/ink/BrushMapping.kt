package com.inknote.pad.ink

import com.inknote.pad.domain.BrushParams

/**
 * domain 的笔刷参数 → 引擎参数。
 *
 * ============================ 为什么映射放在 ink 层而不是 domain 层 ============================
 * domain 层是纯逻辑，它不该知道 [PressureCurve]、[StrokeBuilder] 这些东西存在 ——
 * 否则那一层就被渲染细节污染了，也没法在 JVM 上单独测（BrushParamsTest 现在跑得飞快就是因为它只碰 domain）。
 *
 * 映射集中在这一个文件里，好处是**画布和试写区用的是同一份翻译**。
 * 各写一遍的话，试写区试出来的手感和真实书写不一致 ——
 * 那比没有试写区更糟，因为它会让人相信一个假的结论。
 */

/** 笔刷参数对应的压感曲线 */
fun BrushParams.toPressureCurve(): PressureCurve =
    PressureCurve(minFactor = minFactor, maxFactor = 1f, gamma = gamma)

/**
 * 按当前参数造一支钢笔。
 *
 * style 固定为 [StrokeStyle.PRESSURE]（纯压感变宽，无起笔收笔渐细）。
 *
 * 改版前"压感 / 笔锋 / 速度"是三选一，现在被四支滑块取代，
 * 所以这里必须定死一个。[PRESSURE] 是改版前的**默认值** ——
 * 选它意味着"升级之后笔迹和昨天长得一样"，而选 TAPER 会让所有新笔迹
 * 两端出现渐细，用户第一眼就会觉得"我的笔变了"。
 *
 * 笔锋（TAPER）和速度（VELOCITY）的实现和存储格式都还在，
 * 只是暂时没有入口。以后想加回来，加一支滑块或一个开关即可。
 */
fun BrushParams.newPenBuilder(widthPx: Float): StrokeBuilder = StrokeBuilder(
    widthPx = widthPx,
    curve = toPressureCurve(),
    style = StrokeStyle.PRESSURE,
    minSampleSpacingPx = sampleSpacingPx,
)

/**
 * 按当前参数造一支荧光笔。
 *
 * 荧光笔无视这四支滑块：它是"涂"不是"写"，等宽、无压感、无笔锋。
 * 宽度和透明度由它自己那套设置决定。
 */
fun newHighlighterBuilder(
    widthPx: Float,
    sampleSpacingPx: Float,
): StrokeBuilder = StrokeBuilder(
    widthPx = widthPx,
    curve = PressureCurve(),
    style = StrokeStyle.PRESSURE,
    minSampleSpacingPx = sampleSpacingPx,
    uniformWidth = true,
)
