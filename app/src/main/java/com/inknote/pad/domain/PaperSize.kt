package com.inknote.pad.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纸张规格。
 *
 * ============================ 纸型是「笔记级」属性 ============================
 * 一本笔记在创建时选定纸型，之后**不能修改**；这本笔记里新建的每一页都沿用
 * 同一个纸型。这是刻意的：
 *
 * 笔迹坐标是相对页面位图的像素。如果允许中途换纸型，已写的内容要么被拉伸
 * 变形、要么被裁掉，而这两种结果用户都不会接受。锁死在创建时，
 * "坐标的含义"就永远不会变 —— 这比"随时可换"重要得多。
 */
enum class PaperSize(
    /** 短边毫米数。SCREEN 没有物理尺寸，用 0 表示 */
    val shortSideMm: Float,
    /** 长边毫米数 */
    val longSideMm: Float,
) {
    /** 用创建时绘图区的实际尺寸。之后同样被锁定，只是没有物理含义 */
    SCREEN(0f, 0f),
    A5(148f, 210f),
    A4(210f, 297f),
    A3(297f, 420f),
    ;

    val isScreen: Boolean get() = this == SCREEN

    /** 毫米单位的宽高（考虑方向） */
    fun widthMm(landscape: Boolean): Float = if (landscape) longSideMm else shortSideMm
    fun heightMm(landscape: Boolean): Float = if (landscape) shortSideMm else longSideMm
}

/**
 * 纸型 → 位图像素的换算。
 *
 * ============================ 为什么用 150 dpi ============================
 * 纸上写的东西最终要显示在屏幕上、可能还要导出。光栅分辨率取多少是个权衡：
 *
 *  · 太低 → 屏幕上糊
 *  · 太高 → 位图内存爆掉（内存 = 宽 × 高 × 4 字节，是平方级增长）
 *
 * 150 dpi 是本机实测出来的甜点：这台平板绘图区高约 1700px，
 * 而 A4 竖版在 150 dpi 下是 1754px 高 —— **铺满屏幕时几乎正好 1:1，不会模糊**。
 * A4 位图 8.7 MB、A3 17.4 MB，都在可接受范围。
 *
 * 导出不走这张位图，而是从采样点按任意 dpi 重新渲染（矢量重放），
 * 所以 150 dpi 只是"屏幕用的"，不限制导出质量。
 */
object PaperMetrics {

    const val RASTER_DPI = 150f
    private const val MM_PER_INCH = 25.4f

    /**
     * 解析出页面的像素尺寸。
     *
     * ⚠️ 这个结果**会被存进数据库**，之后永远按存下来的值走，
     * 不再重新计算。原因：笔迹坐标是相对它的像素。
     * 如果哪天改了 [RASTER_DPI]，重新计算会让所有老笔记的笔迹错位
     * （x=620 从"页面正中"变成"页面 1/4 处"）。
     *
     * @param screenWidthPx  当前绘图区宽（仅 SCREEN 用）
     * @param screenHeightPx 当前绘图区高（仅 SCREEN 用）
     */
    fun resolvePixels(
        size: PaperSize,
        landscape: Boolean,
        screenWidthPx: Float,
        screenHeightPx: Float,
    ): PaperDimension {
        if (size.isScreen) {
            // 屏幕尺寸直接取整，避免位图尺寸出现小数
            return PaperDimension(
                widthPx = screenWidthPx.roundToInt().toFloat(),
                heightPx = screenHeightPx.roundToInt().toFloat(),
            )
        }
        return PaperDimension(
            widthPx = mmToPx(size.widthMm(landscape)),
            heightPx = mmToPx(size.heightMm(landscape)),
        )
    }

    /** 给选择器画比例预览用。SCREEN 没有固定比例，返回 -1 表示"用屏幕自己的比例" */
    fun aspectRatio(size: PaperSize, landscape: Boolean): Float {
        if (size.isScreen) return -1f
        val w = size.widthMm(landscape)
        val h = size.heightMm(landscape)
        return w / h
    }

    private fun mmToPx(mm: Float): Float =
        (mm / MM_PER_INCH * RASTER_DPI).roundToInt().toFloat()

    /** 按比例缩放到一个框内，用于生成缩略图时算目标尺寸 */
    fun fitInside(
        srcWidth: Float,
        srcHeight: Float,
        maxWidth: Float,
        maxHeight: Float,
    ): PaperDimension {
        if (srcWidth <= 0f || srcHeight <= 0f) {
            return PaperDimension(maxWidth, maxHeight)
        }
        val scale = min(maxWidth / srcWidth, maxHeight / srcHeight)
        return PaperDimension(
            widthPx = max(1f, (srcWidth * scale).roundToInt().toFloat()),
            heightPx = max(1f, (srcHeight * scale).roundToInt().toFloat()),
        )
    }
}

/** 像素尺寸。用 Float 是因为它直接参与画布的缩放变换 */
data class PaperDimension(
    val widthPx: Float,
    val heightPx: Float,
)

/**
 * 一本笔记的完整纸型描述：选了什么 + 实际解析出来的尺寸。
 *
 * 两者都要，而且都要持久化：
 *  · [size] / [landscape] 是**用户意图**，导出 PDF、显示"这是一本 A4"要用它
 *  · [widthPx] / [heightPx] 是**坐标基准**，笔迹像素就是相对它的
 *
 * 只存前者的话，以后改了 [PaperMetrics.RASTER_DPI] 会让所有老笔记错位；
 * 只存后者的话，导出的 PDF 就不知道该输出多大的纸。
 */
data class PaperSpec(
    val size: PaperSize,
    val landscape: Boolean,
    val widthPx: Float,
    val heightPx: Float,
) {
    val isResolved: Boolean get() = widthPx > 0f && heightPx > 0f

    companion object {
        /** 用当前绘图区尺寸解析出一个纸型 */
        fun resolve(
            size: PaperSize,
            landscape: Boolean,
            screenWidthPx: Float,
            screenHeightPx: Float,
        ): PaperSpec {
            val d = PaperMetrics.resolvePixels(size, landscape, screenWidthPx, screenHeightPx)
            return PaperSpec(size, landscape, d.widthPx, d.heightPx)
        }
    }
}
