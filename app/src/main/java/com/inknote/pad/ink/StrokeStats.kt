package com.inknote.pad.ink

/**
 * 一笔写完之后的实测统计。
 *
 * 存在的理由是 Step 3 要标定压感曲线：
 * 标定必须基于真实数据（这支 Lenovo Tab Pen Plus 的 ABS_PRESSURE 是 0~4095），
 * 而不是靠"感觉好像细了"。有了这个统计，画几笔就能知道
 * 实际压力落在什么区间、采样率够不够、用的是不是手写笔通道。
 */
data class StrokeStats(
    val pointCount: Int,
    val segmentCount: Int,
    val pressureMin: Float,
    val pressureMax: Float,
    /**
     * 这一笔实际渲染出来的最细/最粗线宽（px）。
     *
     * 这是标定压感曲线时真正要看的数据 —— 只看压力区间（0.13~0.70）
     * 判断不出"笔锋够不够明显"，必须看到它映射成了多少像素：
     * 如果只是 3.7px→6.2px，肉眼当然看不出来；
     * 要 2.5px→7.2px 才有笔锋。
     */
    val widthMinPx: Float,
    val widthMaxPx: Float,
    /** MotionEvent.getToolType() 的原始值：2=STYLUS 3=ERASER 1=FINGER 0=UNKNOWN */
    val toolType: Int,
    val durationMillis: Long,
) {
    /** 采样率（点/秒），用来判断系统给的点够不够密 */
    val sampleRateHz: Float
        get() = if (durationMillis <= 0L) 0f else pointCount * 1000f / durationMillis

    /** 粗细对比度。这就是"笔锋明不明显"的量化指标，1.0 = 完全没有笔锋 */
    val widthRatio: Float
        get() = if (widthMinPx <= 0f) 1f else widthMaxPx / widthMinPx
}
