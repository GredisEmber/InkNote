package com.inknote.pad.ink

/**
 * 一段已经算好宽度的直线。
 *
 * 为什么不做成一条变宽的 Path：
 * android.graphics.Paint 的 strokeWidth 对整条 Path 是统一的，没法让一笔
 * 从细到粗。要做变宽只有两条路：
 *   A. 拆成很多条小线段，每段自己的宽度，靠圆头笔帽把接缝盖掉（当前方案）
 *   B. 沿法线算出左右边界拟合成一个填充多边形（fillPath）
 * A 的代码量和风险都远低于 B，而圆头笔帽 + 足量采样密度下，肉眼分不出接缝。
 * 等 Step 8 想追求极限笔锋时再换 B，那时只需要重写 StrokeBuilder，
 * 渲染层和存储层都不用动 —— 这也是把 InkSegment 单独抽出来的原因。
 *
 * 零长度线段（x0==x1 && y0==y1）配合圆头笔帽 = 一个圆点，
 * 所以"轻点一下"不需要任何特殊分支。
 */
data class InkSegment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    /** 这一段的线宽（px），已经乘过压感系数 */
    val width: Float,
)
