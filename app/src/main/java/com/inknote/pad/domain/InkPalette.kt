package com.inknote.pad.domain

/**
 * 调色板。
 *
 * ============================ 只有三个预设 + 一个自定义槽位 ============================
 * 预设刻意少：写成套的颜色越多，用户越难形成"哪个颜色是干什么用的"的肌肉记忆，
 * 而且预设一多就得靠标签区分，工具栏会变得需要"读"而不是"看"。
 *
 * 黑/红/蓝覆盖了笔记里 95% 的用法（正文、重点、批注）。
 * 其他颜色走自定义槽位：点开是色相/饱和度/明度三个滑块加一个十六进制输入，
 * 想用什么颜色就用什么颜色，不需要我们预先猜。
 *
 * ============================ 颜色存 ARGB，不存槽位号 ============================
 * 用户选了哪个颜色，写进笔迹的就是那个 ARGB 本身（见 Stroke.colorArgb）。
 * 不存"槽位第 3 号"，是因为槽位含义以后可能调整 —— 那样会让所有历史笔记的
 * 颜色**悄悄改变**，而用户根本没动过它们。
 */
object InkPalette {

    /** 墨黑。默认色，也是绝大多数人写笔记用的颜色 */
    const val BLACK = 0xFF1A1C1E.toInt()

    /** 红。重点、批改 */
    const val RED = 0xFFB3261E.toInt()

    /** 蓝。批注、补充 */
    const val BLUE = 0xFF1B5E9B.toInt()

    /** 固定预设，顺序就是工具栏里的顺序 */
    val PRESETS: List<Int> = listOf(BLACK, RED, BLUE)

    /**
     * 自定义槽位在工具栏里的下标，紧跟在预设之后。
     *
     * 用 val 而不是 const：`PRESETS.size` 不是编译期常量。
     * 这样写的好处是以后改预设数量时，槽位下标**自动跟着变**，
     * 不会留下"加了预设但忘了改槽位号"这种错位。
     */
    val CUSTOM_SLOT: Int = PRESETS.size

    /** 工具栏上一共几个槽位 */
    val SLOT_COUNT: Int = PRESETS.size + 1

    /**
     * 还没挑过自定义颜色时，槽位显示什么、取色器从什么颜色开始。
     *
     * 选绿色是因为它和三个预设（黑/红/蓝）都不撞色 ——
     * 用户第一次点开就能看到一个明显"这不是预设"的颜色。
     */
    const val DEFAULT_CUSTOM = 0xFF1E6B3A.toInt()

    /**
     * 某个槽位当前对应的颜色。
     *
     * @param customArgb 用户挑过的自定义颜色；null 表示还没挑过
     */
    fun colorForSlot(slot: Int, customArgb: Int?): Int = when (slot) {
        CUSTOM_SLOT -> customArgb ?: DEFAULT_CUSTOM
        else -> PRESETS.getOrElse(slot) { BLACK }
    }

    fun isCustomSlot(slot: Int): Boolean = slot == CUSTOM_SLOT

    /** 槽位下标是否合法 */
    fun isValidSlot(slot: Int): Boolean = slot in 0 until SLOT_COUNT
}

/**
 * 笔宽档位。
 *
 * ============================ 为什么是"文档短边的比例"而不是毫米 ============================
 * 文档坐标系是页面位图的像素，而不同纸型下 1 个单位对应的屏幕像素不一样：
 * A4 竖版会缩到 0.8 倍显示，"与屏幕相同"是 1.0 倍。
 *
 * 如果按绝对毫米定义，A4 上的一支"细笔"在屏幕上只有 3px —— 完全不能用。
 * 按短边比例定义，**线宽占纸面的比例恒定**，而这正是眼睛判断粗细的依据。
 *
 * 实测：中号（0.0076）在两种纸型下都占纸面短边的 0.76%，
 * 只是屏幕像素数分别是 11.7px 和 7.5px —— 视觉上"一样粗"。
 */
object InkWidthSteps {

    /** 当前默认值 0.0076 —— 与 Step 5 的默认笔宽一致，观感不变 */
    const val DEFAULT_INDEX = 2

    val RATIOS: List<Float> = listOf(
        0.0035f, // 极细：批注、公式
        0.0055f, // 细
        0.0076f, // 中（默认）
        0.0115f, // 粗
        0.0170f, // 极粗：标题、强调
    )

    fun ratioAt(index: Int): Float = RATIOS.getOrElse(index) { RATIOS[DEFAULT_INDEX] }

    /**
     * 荧光笔的宽度倍率。
     *
     * 荧光笔不单独给一套档位：它和钢笔共用宽度选择，只是整体乘一个倍率。
     * 这样"粗笔 + 荧光笔"和"细笔 + 荧光笔"的差别依然存在，用户不用学两套控件。
     */
    const val HIGHLIGHTER_MULTIPLIER = 4.5f

    /** 荧光笔不透明度。要能看清下面的字，所以压得比较低 */
    const val HIGHLIGHTER_ALPHA = 0.35f
}

/**
 * 翻页方式。
 *
 * [SEAMLESS] 需要把编辑器改成"可滚动的多页容器"才能实现，
 * 属于 Step 6b 的工作，所以先不放进设置界面 —— 一个选了没反应的选项
 * 比没有这个选项更糟。
 */
enum class PageTurnMode {
    /** 左右滑动，一次一页 */
    HORIZONTAL,

    /** 上下滑动，一次一页 */
    VERTICAL,
}
