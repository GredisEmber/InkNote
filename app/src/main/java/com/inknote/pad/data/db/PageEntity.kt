package com.inknote.pad.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 笔记里的一页。
 *
 * 这个表刻意**不存任何笔迹数据** —— 正文在磁盘上的 .ink 文件里，
 * 文件名由 [pageId] 决定（page-<pageId>.ink）。
 * 表里只有"有哪些页、什么顺序"。
 */
@Entity(
    tableName = "pages",
    indices = [Index("noteId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true)
    val pageId: Long = 0L,
    val noteId: Long,
    /** 显示顺序，从 0 开始连续。增删、排序之后由仓库重排 */
    val orderIndex: Int,
    val updatedAt: Long,

    /**
     * 书签。**一列编码三种状态**：
     *   · `null`  → 没有书签
     *   · `""`    → 有书签，但没写标签
     *   · `"重点"` → 有书签，标签是「重点」
     *
     * 为什么不拆成 `isBookmarked` + `bookmarkLabel` 两列：
     * 两列允许一个**不一致状态**（标记为否、却有标签），
     * 那就得在每一处写入时维护"两者必须同时改"这个不变量 ——
     * 而漏掉一处的表现是"书签莫名其妙消失/复活"，很难查。
     * 一列编码让不一致状态**根本无法表示**。
     */
    val bookmarkLabel: String? = null,
)
