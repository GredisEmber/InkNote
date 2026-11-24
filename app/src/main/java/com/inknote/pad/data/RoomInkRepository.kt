package com.inknote.pad.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.inknote.pad.data.db.InkDatabase
import com.inknote.pad.data.db.NoteEntity
import com.inknote.pad.data.db.NoteListRow
import com.inknote.pad.data.db.PageEntity
import com.inknote.pad.data.file.InkFileCodec
import com.inknote.pad.data.file.InkFormatException
import com.inknote.pad.data.file.InkPage
import com.inknote.pad.data.file.ThumbnailRenderer
import com.inknote.pad.domain.NoteSummary
import com.inknote.pad.domain.PageSummary
import com.inknote.pad.domain.PaperSize
import com.inknote.pad.domain.PaperSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.File
import java.io.IOException

/**
 * 「Room 存元数据 + 每页一个 .ink 文件」的仓库实现。
 *
 * ============================ 目录结构 ============================
 * ```
 * filesDir/ink/<noteId>/page-000.ink
 * filesDir/ink/<noteId>/page-000.ink.corrupt-<时间戳>   （读取失败时隔离出来的）
 * ```
 * 放在 App 私有目录而不是外部存储：不需要任何权限，
 * 也不会被其他 App 或用户误删。导出功能（Step 7）另外走 MediaStore。
 */
class RoomInkRepository(context: Context) : InkRepository {

    private val appContext = context.applicationContext
    private val db = InkDatabase.get(appContext)
    private val dao = db.noteDao()
    private val pageDao = db.pageDao()
    private val inkRoot = File(appContext.filesDir, "ink")

    /**
     * 仓库自己的作用域，刻意不用 viewModelScope：
     * 写盘必须在"发起它的界面已经销毁"之后仍然完成 ——
     * 用户画完立刻返回列表时，ViewModel 已经没了，但数据还没落盘。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _notes = MutableStateFlow<List<NoteSummary>>(emptyList())
    override val notes: StateFlow<List<NoteSummary>> = _notes.asStateFlow()

    // ---- 待写盘的内容 ----
    // key 用 pageId 而不是页序号：序号会因为增删页而变，
    // 拿它当 key 会让"排队中的写盘任务"指向错误的页面。
    private data class PageKey(val noteId: Long, val pageId: Long)

    /** 每个页面最新的快照。只要它还在，就说明"内存里的比磁盘新" */
    private val latest = HashMap<PageKey, InkPage>()

    /** 每个页面正在等待的写盘任务 */
    private val jobs = HashMap<PageKey, Job>()

    /** 同时保护上面两个 map 和下次自增 id 的锁 */
    private val lock = Any()

    init {
        // Room 的 Flow 在表变化时自动重发，所以新建/删除/改名都不需要手动通知 UI
        scope.launch {
            dao.observeAll().collect { rows ->
                _notes.value = rows.map { it.toSummary() }
            }
        }
    }

    // ======================================================================
    // 元数据
    // ======================================================================

    override suspend fun createNote(paper: PaperSpec): NoteSummary {
        val now = System.currentTimeMillis()
        // 底色轮换，让首页不至于一片死白。用时间戳取模而不是"当前笔记数"——
        // 后者在删除笔记后会让新笔记复用同一个颜色。
        val tint = PREVIEW_TINTS[((now / 1000L) % PREVIEW_TINTS.size).toInt()]
        val entity = NoteEntity(
            title = "",
            createdAt = now,
            updatedAt = now,
            pageCount = 1,
            tintArgb = tint,
            paperSizeName = paper.size.name,
            paperLandscape = paper.landscape,
            paperWidthPx = paper.widthPx,
            paperHeightPx = paper.heightPx,
        )
        val id = dao.insert(entity)
        return entity.copy(id = id).toSummary()
    }

    override fun fillPaperPixelsIfUnknown(noteId: Long, widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        scope.launch { dao.fillPaperPixelsIfUnknown(noteId, widthPx, heightPx) }
    }

    override suspend fun deleteNote(id: Long) {
        // 先删文件再删行：反过来的话，如果删文件失败，
        // 数据库里已经没有这条记录，那些 .ink 文件就永远没人回收了。
        withContext(Dispatchers.IO) {
            val dir = File(inkRoot, id.toString())
            if (dir.exists() && !dir.deleteRecursively()) {
                Log.w(TAG, "删除笔记目录失败，可能有残留：${dir.absolutePath}")
            }
        }
        synchronized(lock) {
            // 取消还没落盘的任务：笔记都删了，再写回去就是复活一个幽灵文件
            latest.keys.filter { it.noteId == id }.forEach { key ->
                jobs.remove(key)?.cancel()
                latest.remove(key)
            }
        }
        db.withTransaction {
            pageDao.deleteAllOf(id)
            dao.delete(id)
        }
    }

    override suspend fun renameNote(id: Long, title: String) {
        dao.rename(id, title, System.currentTimeMillis())
    }

    override suspend fun findNote(id: Long): NoteSummary? = dao.find(id)?.toSummary()

    /**
     * 读取一页的缩略图。没有则返回 null —— 界面会退回占位底色。
     *
     * ============================ 缺失时就地补生成 ============================
     * 缩略图是**缓存**，不是只能写一次的产物。它可能在三种情况下缺失：
     *   · 这一页是在本功能上线之前写的
     *   · 生成时出错（磁盘满、进程被杀）
     *   · 文件被外部清掉
     * 与其让用户"重新打开那一页写一笔"来触发，不如在这里直接补 ——
     * 失败一次不会留下任何痕迹，成功一次就永久好了。
     *
     * 不做内存缓存：首页的 LazyVerticalGrid 会自己缓存已解码的位图
     * （见 ui/home/NoteThumbnail.kt）。放在仓库里做缓存的话，
     * 缓存生命周期就和界面脱钩了，笔记删掉之后那块内存还占着。
     */
    override suspend fun loadThumbnail(noteId: Long, pageId: Long): Bitmap? {
        val key = PageKey(noteId, pageId)
        return withContext(Dispatchers.IO) {
            val file = thumbnailFile(key)
            if (!file.exists()) {
                val page = loadPage(noteId, pageId) ?: return@withContext null
                writeThumbnail(key, page)
            }
            if (!file.exists()) return@withContext null
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }
                .onFailure { Log.w(TAG, "缩略图解码失败：${file.absolutePath}", it) }
                .getOrNull()
        }
    }

    // ======================================================================
    // 页面管理
    // ======================================================================

    /**
     * 笔记的页列表，**保证至少有一页**。
     *
     * 新笔记创建时并不会立刻建页记录 —— 那时还不知道用户会不会真的打开它。
     * 第一次打开时在这里补上第一页。做成幂等而不是"创建笔记时建页"，
     * 是因为这样"笔记一定至少有一页"这个不变量由唯一一个地方维护，
     * 而不是散在创建、迁移、删除三条路径里。
     */
    override suspend fun pagesOf(noteId: Long): List<PageSummary> {
        ensureLegacyPageAdopted(noteId)

        val existing = pageDao.pagesOf(noteId)
        if (existing.isNotEmpty()) return existing.map { it.toSummary() }

        // 一页都没有：创建第一页。放进事务，避免两次打开笔记时各建一页
        return db.withTransaction {
            val again = pageDao.pagesOf(noteId)
            if (again.isNotEmpty()) {
                again.map { it.toSummary() }
            } else {
                val now = System.currentTimeMillis()
                val pageId = pageDao.insert(
                    PageEntity(noteId = noteId, orderIndex = 0, updatedAt = now),
                )
                dao.updatePageCount(noteId, 1)
                listOf(PageSummary(pageId, orderIndex = 0, updatedAt = now))
            }
        }
    }

    override suspend fun addPage(noteId: Long): PageSummary? =
        db.withTransaction {
            val order = pageDao.maxOrderIndex(noteId) + 1
            val now = System.currentTimeMillis()
            val pageId = pageDao.insert(
                PageEntity(noteId = noteId, orderIndex = order, updatedAt = now),
            )
            dao.updatePageCount(noteId, pageDao.countOf(noteId))
            dao.touch(noteId, now)
            PageSummary(pageId, order, updatedAt = now)
        }

    override suspend fun duplicatePage(noteId: Long, pageId: Long): PageSummary? {
        val source = pageDao.find(pageId) ?: return null
        if (source.noteId != noteId) return null

        // 内容先读出来（可能在还没落盘的内存快照里）
        val sourcePage = loadPage(noteId, pageId) ?: InkPage(0f, 0f, emptyList())

        val created = db.withTransaction {
            // 插在原页之后：后面的页整体后移一位
            pageDao.shiftOrderAfter(noteId, source.orderIndex)
            val now = System.currentTimeMillis()
            val newId = pageDao.insert(
                PageEntity(noteId = noteId, orderIndex = source.orderIndex + 1, updatedAt = now),
            )
            dao.updatePageCount(noteId, pageDao.countOf(noteId))
            PageSummary(newId, source.orderIndex + 1, updatedAt = now)
        }

        // 复制内容：直接走 savePage，节流和原子写盘都复用同一套逻辑
        savePage(noteId, created.pageId, sourcePage)
        return created
    }

    /**
     * 删除一页。**最后一页不允许删** —— 一本没有任何页的笔记是没法打开的状态，
     * 与其让各处都去处理这个空状态，不如从源头禁止它出现。
     *
     * @return 是否真的删掉了
     */
    override suspend fun deletePage(noteId: Long, pageId: Long): Boolean {
        val page = pageDao.find(pageId) ?: return false
        if (page.noteId != noteId) return false
        if (pageDao.countOf(noteId) <= 1) return false

        val removed = db.withTransaction {
            pageDao.delete(pageId)
            // 后面的页序号往前补一位，保持 0..n-1 连续。
            // 这一步必须在事务里：否则中途失败会留下序号空洞，
            // 而序号是用来算"第几页/共几页"的，有洞就会显示错乱。
            pageDao.compactOrderAfter(noteId, page.orderIndex)
            dao.updatePageCount(noteId, pageDao.countOf(noteId))
            true
        }

        if (removed) {
            synchronized(lock) {
                val key = PageKey(noteId, pageId)
                jobs.remove(key)?.cancel()
                latest.remove(key)
            }
            withContext(Dispatchers.IO) {
                val file = File(pageDir(noteId), pageFileName(pageId))
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "删除页文件失败，可能有残留：${file.absolutePath}")
                }
                // 缩略图跟着页一起删。留着的话下次新建页如果复用了同一个 pageId
                // （自增不会复用，但这是不该依赖的前提）就会显示成别的内容。
                val thumb = File(pageDir(noteId), thumbnailFileName(pageId))
                if (thumb.exists() && !thumb.delete()) {
                    Log.w(TAG, "删除缩略图失败，可能有残留：${thumb.absolutePath}")
                }
            }
        }
        return removed
    }

    /**
     * 交换相邻两页的顺序。
     *
     * @param delta -1 上移一页，+1 下移一页
     * @return 是否真的移动了（已经在边界上时返回 false）
     */
    override suspend fun movePage(noteId: Long, pageId: Long, delta: Int): Boolean {
        val page = pageDao.find(pageId) ?: return false
        if (page.noteId != noteId) return false

        val target = page.orderIndex + delta
        val pages = pageDao.pagesOf(noteId)
        if (target !in pages.indices) return false
        val other = pages[target]

        return db.withTransaction {
            // orderIndex 没有唯一约束，所以直接交换两步就够。
            // 必须在事务里：中途失败会留下两页序号相同的状态，
            // 而序号是用来算"第几页 / 共几页"和排序的，重号会让顺序变得不确定。
            pageDao.updateOrderIndex(other.pageId, page.orderIndex)
            pageDao.updateOrderIndex(pageId, target)
            true
        }
    }

    /**
     * 设置或清除一页的书签。
     *
     * @param label null 表示清除书签；空字符串表示"有书签但没写标签"
     */
    override suspend fun setPageBookmark(noteId: Long, pageId: Long, label: String?): Boolean {
        val page = pageDao.find(pageId) ?: return false
        if (page.noteId != noteId) return false
        pageDao.updateBookmark(pageId, label)
        return true
    }

    /**
     * 认领 v3 之前的页文件。
     *
     * v3 之前是单页，文件名固定为 page-000.ink，数据库里也没有 pages 记录。
     * 这里给它建一条页记录，并把文件改名成 page-<pageId>.ink。
     *
     * 做成"首次打开笔记时懒执行 + 幂等"，而不是启动时批量跑：
     *  - 批量跑要遍历所有笔记目录，笔记多了会拖慢冷启动
     *  - 懒执行天然没有"还没迁移完用户就打开了笔记"的竞态
     * 文件存在与否就是"是否已迁移"的标记 —— pageId 从 1 开始自增，
     * 永远不会是 0，所以 page-000.ink 这个文件名只可能来自 v3 之前。
     */
    private suspend fun ensureLegacyPageAdopted(noteId: Long) {
        val dir = pageDir(noteId)
        val legacy = File(dir, legacyPageFileName)
        if (!legacy.exists()) return
        if (pageDao.countOf(noteId) > 0) return

        val now = System.currentTimeMillis()
        val pageId = db.withTransaction {
            val newId = pageDao.insert(
                PageEntity(noteId = noteId, orderIndex = 0, updatedAt = now),
            )
            dao.updatePageCount(noteId, 1)
            newId
        }

        withContext(Dispatchers.IO) {
            val target = File(dir, pageFileName(pageId))
            if (!legacy.renameTo(target)) {
                // 改名失败也不能丢内容：保底做法是把内容读出来重新写一份
                Log.w(TAG, "旧页文件改名失败，改用复制：${legacy.absolutePath}")
                runCatching {
                    legacy.copyTo(target, overwrite = true)
                    legacy.delete()
                }.onFailure { Log.e(TAG, "旧页文件迁移失败", it) }
            }
        }
    }

    // ======================================================================
    // 页面读写
    // ======================================================================

    override suspend fun loadPage(noteId: Long, pageId: Long): InkPage? {
        val key = PageKey(noteId, pageId)

        // 先看内存快照。用户"画几笔 → 立刻返回 → 马上再进来"时，
        // 节流窗口内磁盘还是旧的，直接读文件会看到上一个版本的内容 ——
        // 表现就是"刚写的东西没了，过一会儿又回来了"。
        synchronized(lock) { latest[key] }?.let { return it }

        return withContext(Dispatchers.IO) {
            val file = pageFile(key)
            if (!file.exists()) return@withContext null
            try {
                file.inputStream().buffered().use { InkFileCodec.readFrom(it) }
            } catch (e: InkFormatException) {
                // 隔离而不是删除：用户的笔记是不可再生的。
                // 保留原文件，将来还有手工抢救的余地。
                quarantine(file)
                Log.e(TAG, "页面文件损坏，已隔离为 ${file.name}.corrupt-*", e)
                null
            } catch (e: IOException) {
                Log.e(TAG, "读取页面失败：${file.absolutePath}", e)
                null
            }
        }
    }

    override fun savePage(noteId: Long, pageId: Long, page: InkPage) {
        val key = PageKey(noteId, pageId)
        synchronized(lock) {
            latest[key] = page
            // 已经在排队的就不重复排：这样写盘频率的上界就是 SAVE_THROTTLE_MS，
            // 而不是"每次落笔都重置计时器"（那会变成停笔才写，反而更危险）
            if (jobs[key]?.isActive != true) {
                jobs[key] = scope.launch {
                    delay(InkRepository.SAVE_THROTTLE_MS)
                    writePage(key)
                }
            }
        }
    }

    override suspend fun flush() {
        val keys = synchronized(lock) { latest.keys.toList() }
        for (key in keys) {
            // 取消排队中的任务，直接写 —— 不然 flush 完它还会再写一遍
            synchronized(lock) { jobs.remove(key)?.cancel() }
            writePage(key)
        }
    }

    override fun flushAsync() {
        scope.launch { flush() }
    }

    /**
     * 把某个页面的最新快照写盘。
     *
     * 关键细节：写完之后要再检查一次快照有没有被换掉。
     * 如果写盘期间用户又画了东西（savePage 更新了 latest），
     * 就不能清掉它，而要再排一轮 —— 否则那次修改会被静默丢弃。
     */
    private suspend fun writePage(key: PageKey) {
        val page = synchronized(lock) { latest[key] } ?: return

        try {
            withContext(Dispatchers.IO) {
                writePageFile(key, page)
                // 缩略图和 .ink 在同一处更新：首页上"这一页长什么样"只有它一个来源。
                // 放在写盘路径里而不是落笔路径里，于是缩略图的生成频率
                // 和写盘频率共用同一个上界（1.5 秒一次），不会拖慢书写。
                writeThumbnail(key, page)
            }
        } catch (e: IOException) {
            Log.e(TAG, "写 .ink 失败，快照保留待下次重试：$key", e)
            synchronized(lock) { jobs.remove(key) }
            return
        }

        synchronized(lock) {
            if (latest[key] === page) {
                // 期间没有新内容，落盘完成
                latest.remove(key)
                jobs.remove(key)
            } else {
                // 期间又画了东西，再排一轮
                jobs[key] = scope.launch {
                    delay(InkRepository.SAVE_THROTTLE_MS)
                    writePage(key)
                }
            }
        }

        // 落笔之后把笔记顶到列表最前面
        val now = System.currentTimeMillis()
        dao.touch(key.noteId, now)
        // 同时更新**页**的 updatedAt：它是页列表里缩略图的版本号。
        // 只更新笔记的话，页列表就无法判断"手上这张缩略图是不是这一页当前的版本"，
        // 只能整本一起失效 —— 那会让"改一页、所有页缩略图全部重解码"。
        pageDao.touch(key.pageId, now)
    }

    /**
     * 原子写入：先写临时文件再改名。
     *
     * 直接覆写原文件的话，写到一半进程被杀就会留下一个半截文件 ——
     * 而半截文件是【不可恢复】的，用户那一页笔记就毁了。
     * renameTo 在同一文件系统上是原子的，最坏情况只是这次修改没生效，
     * 原来那份完好无损。
     */
    private fun writePageFile(key: PageKey, page: InkPage) {
        val target = pageFile(key)
        target.parentFile?.mkdirs()

        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().buffered().use { InkFileCodec.writeTo(it, page) }

        if (!temp.renameTo(target)) {
            // 有些文件系统上已存在的目标会阻止 rename，退化成"删了再改名"。
            // 这一步不是原子的，但至少临时文件已经完整写好了。
            if (target.exists() && !target.delete()) {
                throw IOException("无法替换 ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                throw IOException("无法重命名 ${temp.absolutePath} -> ${target.absolutePath}")
            }
        }
    }

    private fun pageFile(key: PageKey): File =
        File(pageDir(key.noteId), pageFileName(key.pageId))

    /**
     * 生成并写入缩略图。
     *
     * 失败只记日志、**绝不向上抛**：缩略图是锦上添花的东西，
     * 它写不出来不该影响笔迹本身的落盘 —— 那才是用户真正不能丢的数据。
     */
    private fun writeThumbnail(key: PageKey, page: InkPage) {
        val bitmap = ThumbnailRenderer.render(
            pageWidth = page.pageWidth,
            pageHeight = page.pageHeight,
            strokes = page.strokes,
        ) ?: return

        try {
            ThumbnailRenderer.writeTo(thumbnailFile(key), bitmap)
        } catch (e: IOException) {
            Log.w(TAG, "缩略图写入失败：$key", e)
        } finally {
            // 显式回收：缩略图是临时产物，早点把这块内存还回去。
            // 连续翻页时会反复生成，不回收的话很快就会积到几十 MB。
            bitmap.recycle()
        }
    }

    private fun thumbnailFile(key: PageKey): File =
        File(pageDir(key.noteId), thumbnailFileName(key.pageId))

    private fun thumbnailFileName(pageId: Long): String = "thumb-$pageId.png"

    private fun pageDir(noteId: Long): File = File(inkRoot, noteId.toString())

    /**
     * 页文件名由 **pageId** 决定，不是序号。
     * 这样删除中间一页时不需要重命名任何其他文件 ——
     * 详见 PageSummary 的注释。
     */
    private fun pageFileName(pageId: Long): String = "page-$pageId.ink"

    /** v3 之前所有页都叫这个名字（那时只有单页） */
    private val legacyPageFileName = "page-000.ink"

    private fun quarantine(file: File) {
        val stamp = System.currentTimeMillis()
        val target = File(file.parentFile, "${file.name}.corrupt-$stamp")
        if (!file.renameTo(target)) {
            Log.w(TAG, "隔离损坏文件失败：${file.absolutePath}")
        }
    }

    private fun PageEntity.toSummary() = PageSummary(
        pageId = pageId,
        orderIndex = orderIndex,
        updatedAt = updatedAt,
        bookmarkLabel = bookmarkLabel,
    )

    private fun NoteListRow.toSummary(): NoteSummary {
        val e = note
        return NoteSummary(
            id = e.id,
            title = e.title,
            updatedAtEpochMillis = e.updatedAt,
            pageCount = e.pageCount,
            placeholderTintArgb = e.tintArgb,
            paperSize = parsePaperSize(e.paperSizeName),
            paperLandscape = e.paperLandscape ?: false,
            // 0 表示"未知"（v1 老笔记），编辑器会从 .ink 文件头回填
            paperWidthPx = e.paperWidthPx ?: 0f,
            paperHeightPx = e.paperHeightPx ?: 0f,
            coverPageId = coverPageId,
        )
    }

    private fun NoteEntity.toSummary() = NoteSummary(
        id = id,
        title = title,
        updatedAtEpochMillis = updatedAt,
        pageCount = pageCount,
        placeholderTintArgb = tintArgb,
        paperSize = parsePaperSize(paperSizeName),
        paperLandscape = paperLandscape ?: false,
        // 0 表示"未知"（v1 老笔记），编辑器会从 .ink 文件头回填
        paperWidthPx = paperWidthPx ?: 0f,
        paperHeightPx = paperHeightPx ?: 0f,
    )

    /**
     * 按名字解析纸型，解析不出来就退回 SCREEN。
     *
     * 退回而不是抛异常：一个认不出的纸型名不该让用户打不开笔记。
     * SCREEN 配合"尺寸未知"分支会从 .ink 文件头恢复出真实尺寸，
     * 所以笔迹依然落在正确的位置上。
     */
    private fun parsePaperSize(name: String?): PaperSize =
        PaperSize.entries.firstOrNull { it.name == name } ?: PaperSize.SCREEN

    private companion object {
        const val TAG = "InkRepo"

        val PREVIEW_TINTS = intArrayOf(
            0xFFF3F6FB.toInt(),
            0xFFFBF6EC.toInt(),
            0xFFF2F8F3.toInt(),
            0xFFF8F2F7.toInt(),
        )
    }
}

/**
 * 供 Application 在 onStop 之类的时机做"阻塞式尽力落盘"。
 *
 * 用 runBlocking 是刻意的：onStop 之后进程随时可能被杀，
 * 而写一个几 KB 的文件只要几毫秒 —— 用异步的话可能还没执行到就没了。
 */
@Suppress("unused")
internal fun InkRepository.flushBlocking() = runBlocking { flush() }
