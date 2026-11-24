package com.inknote.pad.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 首页列表的一行：笔记元数据 + 它的封面页 id。
 *
 * 用 @Embedded 拼一个投影类，而不是给 notes 表加一个 coverPageId 列：
 * 封面就是"排序后的第一页"，是**派生数据**。存下来就要在每次增删页、
 * 每次排序之后同步它，多一处可能不同步的地方。
 * 用子查询现算，代价是列表查询多一次索引查找 —— 几十本笔记完全无感。
 */
data class NoteListRow(
    @Embedded val note: NoteEntity,
    /** 该笔记的第一页 id；还没有页时为 null */
    val coverPageId: Long?,
)

@Dao
interface NoteDao {

    /**
     * 返回 Flow 而不是 List —— Room 会在表变化时自动重新发射。
     * 这样"新建/删除/改名"之后首页会自动刷新，不需要任何手动通知。
     */
    @Query(
        """
        SELECT notes.*,
               (SELECT pages.pageId FROM pages
                WHERE pages.noteId = notes.id
                ORDER BY pages.orderIndex ASC LIMIT 1) AS coverPageId
        FROM notes
        ORDER BY notes.updatedAt DESC
        """,
    )
    fun observeAll(): Flow<List<NoteListRow>>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)

    /** 只更新时间，用于"落笔之后把笔记顶到列表最前面" */
    @Query("UPDATE notes SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun find(id: Long): NoteEntity?

    /**
     * 同步页数。
     *
     * pageCount 是**冗余字段**，真相在 pages 表里。
     * 保留它是为了让首页列表一次查询就能拿到页数，
     * 不必为每张卡片去 COUNT 一次 pages。代价是每次增删页都要同步，
     * 所以调用点必须包在同一个事务里（见 RoomInkRepository）。
     */
    @Query("UPDATE notes SET pageCount = :pageCount WHERE id = :id")
    suspend fun updatePageCount(id: Long, pageCount: Int)

    /**
     * 回填页面尺寸，只对"尺寸未知"的行生效。
     *
     * WHERE 里那个 `paperWidthPx <= 0` 是保护条件，不是优化：
     * 页面尺寸是笔迹坐标的基准，**一旦确定就绝不能被改写** ——
     * 改写会让这本笔记里已经写好的内容全部错位。
     * 把保护写进 SQL 而不是靠调用方自觉，是因为调用方将来会变多。
     */
    @Query(
        "UPDATE notes SET paperWidthPx = :widthPx, paperHeightPx = :heightPx " +
            "WHERE id = :id AND (paperWidthPx IS NULL OR paperWidthPx <= 0)",
    )
    suspend fun fillPaperPixelsIfUnknown(id: Long, widthPx: Float, heightPx: Float)
}
