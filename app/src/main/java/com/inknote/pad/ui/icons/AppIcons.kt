package com.inknote.pad.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * App 自带的图标集。
 *
 * 为什么不用 androidx.compose.material.icons.Icons：
 *  1. 那套图标来自 material-icons-core，它是 material3 的传递依赖 ——
 *     一个我们并不直接声明的依赖项，哪天上游把它从 material3 里摘掉，
 *     编译就会莫名其妙地失败，而且报错信息不会告诉你是依赖的问题。
 *  2. 这个 App 到 Step 6 需要十几个工具图标（钢笔 / 高亮 / 橡皮 / 撤销 /
 *     重做 / 套索 / 翻页 / 导出…），Material 的现成图标本来就对不上，
 *     与其混用两套，不如一开始就只留一套自己掌控的。
 *  3. 用 ImageVector 声明的是纯矢量路径，零资源文件、零依赖、可 tint。
 *
 * 约定：viewport 统一 24x24，线宽 1.8~2.0，描边一律 round cap/join，
 * 这样在一支笔的粗细变化旁边看起来不会显得突兀。
 */
object AppIcons {

    /** 返回：左向 V 形 */
    val Back: ImageVector = strokedIcon(name = "InkBack", strokeWidth = 2f) {
        moveTo(15f, 4.5f)
        lineTo(7.5f, 12f)
        lineTo(15f, 19.5f)
    }

    /** 新建：加号 */
    val Add: ImageVector = filledIcon(name = "InkAdd") {
        moveTo(11f, 5f)
        lineTo(13f, 5f)
        lineTo(13f, 11f)
        lineTo(19f, 11f)
        lineTo(19f, 13f)
        lineTo(13f, 13f)
        lineTo(13f, 19f)
        lineTo(11f, 19f)
        lineTo(11f, 13f)
        lineTo(5f, 13f)
        lineTo(5f, 11f)
        lineTo(11f, 11f)
        close()
    }

    /** 书签（未标记）：绶带轮廓 */
    val Bookmark: ImageVector = strokedIcon(name = "InkBookmark", strokeWidth = 1.7f) {
        moveTo(7f, 4f)
        lineTo(17f, 4f)
        lineTo(17f, 20f)
        lineTo(12f, 15.5f)
        lineTo(7f, 20f)
        close()
    }

    /** 书签（已标记）：同一形状但填实，一眼能看出状态 */
    val BookmarkFilled: ImageVector = filledIcon(name = "InkBookmarkFilled") {
        moveTo(7f, 4f)
        lineTo(17f, 4f)
        lineTo(17f, 20f)
        lineTo(12f, 15.5f)
        lineTo(7f, 20f)
        close()
    }

    /** 上移一页 */
    val ArrowUp: ImageVector = strokedIcon(name = "InkArrowUp", strokeWidth = 1.8f) {
        moveTo(12f, 19.5f)
        lineTo(12f, 5.5f)
        moveTo(6.5f, 11f)
        lineTo(12f, 5.5f)
        lineTo(17.5f, 11f)
    }

    /** 下移一页 */
    val ArrowDown: ImageVector = strokedIcon(name = "InkArrowDown", strokeWidth = 1.8f) {
        moveTo(12f, 4.5f)
        lineTo(12f, 18.5f)
        moveTo(6.5f, 13f)
        lineTo(12f, 18.5f)
        lineTo(17.5f, 13f)
    }

    /** 荧光笔：一支斜着的粗头马克笔，下面一道涂痕点明它是涂色用的 */
    val Highlighter: ImageVector = strokedIcon(name = "InkHighlighter", strokeWidth = 1.7f) {
        // 笔身
        moveTo(9.5f, 4.5f)
        lineTo(19.5f, 14.5f)
        lineTo(15.5f, 18.5f)
        lineTo(5.5f, 8.5f)
        close()
        // 笔尖那条边（和钢笔的尖头形成形状上的区别）
        moveTo(5.5f, 8.5f)
        lineTo(9.5f, 4.5f)
        // 下方的一道涂痕
        moveTo(4.5f, 20.5f)
        lineTo(13.5f, 20.5f)
    }

    /**
     * 设置：三条带滑块的横线。
     *
     * 用这个形状而不是齿轮：齿轮的路径要画内外两圈加一堆齿，代码长且容易画歪；
     * 而"滑杆"这个形状在任何密度下都清楚，语义也一样明确。
     */
    val Settings: ImageVector = strokedIcon(name = "InkSettings", strokeWidth = 1.8f) {
        moveTo(4f, 7f)
        lineTo(20f, 7f)
        moveTo(9f, 5.2f)
        lineTo(9f, 8.8f)

        moveTo(4f, 12f)
        lineTo(20f, 12f)
        moveTo(15f, 10.2f)
        lineTo(15f, 13.8f)

        moveTo(4f, 17f)
        lineTo(20f, 17f)
        moveTo(7f, 15.2f)
        lineTo(7f, 18.8f)
    }

    /**
     * 向上的小三角：提示"这里还能再点一层展开"。
     *
     * 用朝上的而不是朝下的：菜单是从按钮**上方**弹出来的，
     * 箭头指向上方才和"东西会从那边出现"对得上。
     */
    val ChevronUp: ImageVector = strokedIcon(name = "InkChevronUp", strokeWidth = 2f) {
        moveTo(6f, 14.5f)
        lineTo(12f, 9f)
        lineTo(18f, 14.5f)
    }

    /** 向右的小三角：提示"这一行可以点进去" */
    val ChevronRight: ImageVector = strokedIcon(name = "InkChevronRight", strokeWidth = 2f) {
        moveTo(9.5f, 6f)
        lineTo(15f, 12f)
        lineTo(9.5f, 18f)
    }

    /** 确认：对勾 */
    val Check: ImageVector = strokedIcon(name = "InkCheck", strokeWidth = 2f) {
        moveTo(5f, 12.5f)
        lineTo(10f, 17.5f)
        lineTo(19f, 7f)
    }

    /** 改名：一支铅笔（笔身是平行四边形，笔尖处有一道分隔线） */
    val Edit: ImageVector = strokedIcon(name = "InkEdit", strokeWidth = 1.7f) {
        moveTo(5.2f, 18.8f)
        lineTo(5.9f, 14.9f)
        lineTo(15.4f, 5.4f)
        lineTo(18.6f, 8.6f)
        lineTo(9.1f, 18.1f)
        close()
        // 笔尖与笔身的分隔
        moveTo(5.9f, 14.9f)
        lineTo(9.1f, 18.1f)
    }

    /** 删除：垃圾桶轮廓 */
    val Delete: ImageVector = strokedIcon(name = "InkDelete", strokeWidth = 1.7f) {
        // 桶盖横条
        moveTo(4.5f, 7f)
        lineTo(19.5f, 7f)
        // 提手
        moveTo(9.5f, 7f)
        lineTo(9.5f, 4.5f)
        lineTo(14.5f, 4.5f)
        lineTo(14.5f, 7f)
        // 桶身
        moveTo(6.8f, 7f)
        lineTo(7.7f, 19.6f)
        lineTo(16.3f, 19.6f)
        lineTo(17.2f, 7f)
        // 两道竖纹
        moveTo(10.4f, 10.4f)
        lineTo(10.4f, 16.4f)
        moveTo(13.6f, 10.4f)
        lineTo(13.6f, 16.4f)
    }

    /** 撤销：左向箭头 + 向右下回卷的弧线 */
    val Undo: ImageVector = strokedIcon(name = "InkUndo", strokeWidth = 1.9f) {
        // 箭头
        moveTo(8f, 4.5f)
        lineTo(3.5f, 9f)
        lineTo(8f, 13.5f)
        // 横杆
        moveTo(3.5f, 9f)
        lineTo(13.5f, 9f)
        // 回卷的弧
        curveTo(18.5f, 9f, 19.5f, 15f, 15f, 18.5f)
    }

    /** 重做：撤销的水平镜像 */
    val Redo: ImageVector = strokedIcon(name = "InkRedo", strokeWidth = 1.9f) {
        moveTo(16f, 4.5f)
        lineTo(20.5f, 9f)
        lineTo(16f, 13.5f)
        moveTo(20.5f, 9f)
        lineTo(10.5f, 9f)
        curveTo(5.5f, 9f, 4.5f, 15f, 9f, 18.5f)
    }

    /** 钢笔：笔尖三角形 + 笔杆两侧 + 尾端 */
    val Pen: ImageVector = strokedIcon(name = "InkPen", strokeWidth = 1.8f) {
        // 笔尖
        moveTo(6.5f, 14f)
        lineTo(3.5f, 20.5f)
        lineTo(10f, 17.5f)
        close()
        // 笔杆两侧
        moveTo(6.5f, 14f)
        lineTo(16.5f, 4f)
        moveTo(10f, 17.5f)
        lineTo(20f, 7.5f)
        // 尾端
        moveTo(16.5f, 4f)
        lineTo(20f, 7.5f)
    }

    /**
     * 橡皮：斜置四边形 + 分界带。
     * 分界带必须垂直于长轴，横跨两条长边 —— 它表示"橡胶"和"握把"的分界，
     * 画成对角线会变成一个看不懂的菱形。
     */
    val Eraser: ImageVector = strokedIcon(name = "InkEraser", strokeWidth = 1.8f) {
        moveTo(8.5f, 3.5f)
        lineTo(20.5f, 15.5f)
        lineTo(15.5f, 20.5f)
        lineTo(3.5f, 8.5f)
        close()
        moveTo(15.5f, 10.5f)
        lineTo(10.5f, 15.5f)
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun filledIcon(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = SolidColor(Color.Black),
            pathBuilder = pathBuilder,
        ).build()

    private fun strokedIcon(
        name: String,
        strokeWidth: Float,
        pathBuilder: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            // 用黑色声明路径，实际颜色由 Icon 的 tint 覆盖 ——
            // 这样图标能自动跟随主题，不需要为深浅色各准备一套。
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        ).build()
}
