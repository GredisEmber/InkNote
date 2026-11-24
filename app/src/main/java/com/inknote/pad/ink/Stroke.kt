package com.inknote.pad.ink

/**
 * 一笔。
 *
 * segments 是预计算好的，这是有意的：
 * 撤销之后要把整页重画一遍。如果每次重画都重新跑一遍平滑 + 压感映射，
 * 一页几百笔时会明显卡顿（每笔几百个采样点 × pow() 运算）。
 * 几何只在收笔时算一次，之后重画就只是 drawLine。
 *
 * bounds 同样是预计算的：橡皮每次移动都要做命中测试，
 * 如果每次现算包围盒，等于把"用包围盒快速排除"这个优化本身的开销
 * 变成 O(线段数) —— 优化就白做了。
 *
 * style 是 Step 4 加的：一页里可以混合不同风格的笔画（用户中途切换过），
 * 而几何是风格的产物 —— 不记录风格就没法在载入时把几何重建得一模一样。
 *
 * 注意这里没有 tool 字段：目前只有钢笔一种绘制工具，橡皮不产生笔迹
 * （它是删除操作），所以 Stroke 不需要记录工具。
 */
class Stroke(
    val colorArgb: Int,
    /** 基础线宽（px），实际宽度是它乘以压感系数 */
    val widthPx: Float,
    /** 生成这笔几何时用的风格 */
    val style: StrokeStyle,
    /**
     * 是不是荧光笔。
     *
     * 它决定这笔画在**哪一层**（荧光笔在下、钢笔在上），
     * 而颜色是**自描述**的：荧光笔的颜色在写入时就已经带上了 alpha，
     * 所以渲染时不需要"如果是荧光笔就调透明度"这种额外判断。
     *
     * 为什么不复用 [InkTool]：Stroke 永远不可能是 ERASER，
     * 用那个枚举会引入一个"不可能但能表示"的状态。
     * 以后真出现铅笔、马克笔之类的第三种笔，再升级成枚举也来得及。
     */
    val isHighlighter: Boolean,
    val points: List<InkPoint>,
    val segments: List<InkSegment>,
    /** 轴对齐包围盒，含线宽，橡皮命中测试用 */
    val bounds: InkBounds,
) {
    companion object {
        /**
         * 从采样点重建一笔（重算几何）。持久化载入时用。
         *
         * ============================ 为什么存点不存几何 ============================
         * 几何是采样点的【派生数据】：一笔 100 个采样点会离散成 300~1000 条线段，
         * 存几何会让文件大 5~10 倍。所以文件里只存采样点，
         * 载入时用同一套算法重算一遍。
         *
         * 代价是：**如果以后改了 StrokeBuilder 的算法或 PressureCurve 的默认值，
         * 老笔记的笔迹外观会跟着变。** 对个人笔记来说这通常正是想要的
         * （渲染改进能惠及全部历史笔记），但必须知道这一点：
         * 它不是"存下来的像素"，而是"重新渲染的矢量"。
         *
         * 几何在收笔时就算好并缓存了，所以重建只在载入时发生一次，
         * 不在每次绘制时发生 —— 不影响滚动和绘制性能。
         */
        fun rebuild(
            colorArgb: Int,
            widthPx: Float,
            style: StrokeStyle,
            isHighlighter: Boolean,
            points: List<InkPoint>,
            curve: PressureCurve = PressureCurve(),
        ): Stroke {
            val builder = StrokeBuilder(
                widthPx = widthPx,
                curve = curve,
                style = style,
                uniformWidth = isHighlighter,
            )
            for (i in points.indices) {
                builder.add(points[i])
            }
            builder.finish()
            return builder.build(colorArgb, isHighlighter)
        }
    }
}
