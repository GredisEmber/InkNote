package com.inknote.pad.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 笔记的元数据行。
 *
 * 为什么只存元数据、笔迹另存文件：
 * 一笔可能有几万个采样点，把它塞进 SQLite 的 BLOB 会让
 * "查一页笔记的标题"这种操作顺带把几 MB 数据读进内存。
 * 元数据（几十字节）和正文（几百 KB）分开存，各自的访问模式才对得上。
 *
 * ============================ v2 新增的纸型字段为什么可空 ============================
 * 它们是为 v1 时代的老行准备的 —— 那时还没有纸型概念。
 * 刻意用"可空 + 无默认值"而不是"NOT NULL DEFAULT"：
 * Room 会逐列校验迁移后的表结构和实体声明是否一致，而 DEFAULT 的写法
 * （尤其是字符串要不要带内层引号）很容易写歪，写歪的后果是运行期直接
 * 抛 "Migration didn't properly handle" —— 用户直接打不开 App。
 * 可空列配 `ALTER TABLE ... ADD COLUMN x TYPE` 是最不容易写错的形态。
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 空字符串代表未命名，显示时再兜底 */
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    /** 真实缩略图生成之前的卡片底色 */
    val tintArgb: Int,

    /**
     * 纸型枚举名。**存名字而不是 ordinal** ——
     * ordinal 会在枚举顺序调整时静默错位（A4 变成 A3），
     * 而名字错了至少能被解析代码发现并回退。
     */
    val paperSizeName: String? = null,
    val paperLandscape: Boolean? = null,

    /**
     * 解析后的页面像素尺寸，同时是笔迹坐标的基准。
     * 一旦写入就不再重算 —— 重算会让已有笔迹错位。
     */
    val paperWidthPx: Float? = null,
    val paperHeightPx: Float? = null,
)
