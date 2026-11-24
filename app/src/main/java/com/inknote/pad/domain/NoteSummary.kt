package com.inknote.pad.domain

/**
 * 笔记列表项模型。
 *
 * 为什么不叫 Note、为什么没有笔迹字段：
 * 笔记的「元数据」和「笔迹正文」存在两个地方 ——
 * 元数据进 Room（标题、时间、页数、纸型），笔迹进每页一个 .ink 二进制文件。
 * 模型上也把它们分开，是为了防止有人顺手把几 MB 的笔迹塞进列表查询结果里，
 * 让首页滚动变卡。
 */
data class NoteSummary(
    val id: Long,
    /** 空字符串表示「未命名」，显示的时候再兜底，不把「未命名」写死进数据 */
    val title: String,
    val updatedAtEpochMillis: Long,
    val pageCount: Int,
    /**
     * 真实缩略图缺失时的占位底色。
     * 缩略图还没生成出来的那一小段时间会用到它。
     */
    val placeholderTintArgb: Int,

    // ---- 纸型（笔记级，创建后不可改）--------------------------------------

    val paperSize: PaperSize,
    val paperLandscape: Boolean,

    /**
     * 解析后的页面像素尺寸，**也是笔迹坐标的基准**。
     *
     * 0 表示"未知" —— 只可能出现在 v1 时代创建的老笔记上
     * （那时还没有纸型概念）。遇到这种情况，编辑器会从 .ink 文件头里
     * 读出的页面尺寸回填，见 RoomInkRepository.updatePaperPixels。
     */
    val paperWidthPx: Float,
    val paperHeightPx: Float,

    /**
     * 封面页（排序后的第一页）的 id，首页用它去取缩略图。
     * null 表示这本笔记还没有页。
     */
    val coverPageId: Long? = null,
) {
    /** 尺寸是否已经确定。false 时编辑器要走"从页文件回填"的兼容路径 */
    val hasResolvedPaper: Boolean get() = paperWidthPx > 0f && paperHeightPx > 0f
}
