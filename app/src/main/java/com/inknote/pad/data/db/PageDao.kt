package com.inknote.pad.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE noteId = :noteId ORDER BY orderIndex ASC")
    suspend fun pagesOf(noteId: Long): List<PageEntity>

    @Query("SELECT COUNT(*) FROM pages WHERE noteId = :noteId")
    suspend fun countOf(noteId: Long): Int

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM pages WHERE noteId = :noteId")
    suspend fun maxOrderIndex(noteId: Long): Int

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Query("DELETE FROM pages WHERE pageId = :pageId")
    suspend fun delete(pageId: Long)

    /**
     * 删掉一本笔记的所有页记录。
     *
     * 刻意不用外键 + CASCADE：外键需要 pages 表的 schema 里写 FOREIGN KEY 子句，
     * 而那个子句一旦和 Room 生成的不一致就会在迁移时炸掉。
     * 笔记删除本来就要连带删磁盘目录，顺手删几行记录成本可以忽略。
     */
    @Query("DELETE FROM pages WHERE noteId = :noteId")
    suspend fun deleteAllOf(noteId: Long)

    @Query("UPDATE pages SET orderIndex = :orderIndex WHERE pageId = :pageId")
    suspend fun updateOrderIndex(pageId: Long, orderIndex: Int)

    @Query("UPDATE pages SET orderIndex = orderIndex + 1 WHERE noteId = :noteId AND orderIndex > :afterIndex")
    suspend fun shiftOrderAfter(noteId: Long, afterIndex: Int)

    @Query("UPDATE pages SET orderIndex = orderIndex - 1 WHERE noteId = :noteId AND orderIndex > :afterIndex")
    suspend fun compactOrderAfter(noteId: Long, afterIndex: Int)

    @Query("UPDATE pages SET updatedAt = :updatedAt WHERE pageId = :pageId")
    suspend fun touch(pageId: Long, updatedAt: Long)

    /**
     * 设置书签。传 null 清除、传空串表示"有书签但没标签"。
     * 三种状态编码在一列里，见 PageEntity.bookmarkLabel 的注释。
     */
    @Query("UPDATE pages SET bookmarkLabel = :label WHERE pageId = :pageId")
    suspend fun updateBookmark(pageId: Long, label: String?)

    @Query("SELECT * FROM pages WHERE pageId = :pageId")
    suspend fun find(pageId: Long): PageEntity?
}
