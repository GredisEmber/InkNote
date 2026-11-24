package com.inknote.pad.data

import android.graphics.Bitmap
import com.inknote.pad.data.file.InkPage
import com.inknote.pad.domain.NoteSummary
import com.inknote.pad.domain.PageSummary
import com.inknote.pad.domain.PaperSpec
import kotlinx.coroutines.flow.StateFlow

/**
 * 笔记仓库。
 *
 * ============================ Step 4 改了什么 ============================
 * Step 1~3 它是纯内存实现，接口形状当时就是照着"最终会换成持久化"定的。
 * 现在换成 Room + .ink 文件，**UI 层只改了两处**：
 *   · 创建/删除/改名变成 suspend（Room 不能在主线程查库）
 *   · 载入笔迹变成 suspend（文件 IO 同理）
 * 其余形状没变 —— notes 仍然是 StateFlow，保存仍然是 fire-and-forget，
 * 因为当初就是按这个方向设计的。
 */
interface InkRepository {

    /** 按「最近修改」倒序的笔记列表 */
    val notes: StateFlow<List<NoteSummary>>

    /**
     * 新建笔记。
     *
     * 纸型在这里就定死并存下来 —— 之后不再修改。
     * 页面尺寸一旦确定就是笔迹坐标的基准，中途改会让已有笔迹全部错位。
     */
    suspend fun createNote(paper: PaperSpec): NoteSummary

    suspend fun deleteNote(id: Long)

    suspend fun renameNote(id: Long, title: String)

    suspend fun findNote(id: Long): NoteSummary?

    /**
     * 回填笔记的页面尺寸，只对"尺寸未知"的行生效。
     *
     * 用在哪：选「与屏幕相同」的笔记在创建时**不知道**尺寸 ——
     * 绘图区大小要等编辑器的画布布局完才知道。所以创建时留 0，
     * 编辑器首次布局后回填，之后这个值就固定了。
     *
     * fire-and-forget 而不是 suspend：调用点在 View 的布局回调里，
     * 那里不能挂起，而且这个写入不着急。
     */
    fun fillPaperPixelsIfUnknown(noteId: Long, widthPx: Float, heightPx: Float)

    // ======================================================================
    // 页面
    // ======================================================================

    /**
     * 笔记的页列表，按显示顺序。**保证至少有一页** ——
     * 新笔记的第一页在这里创建（创建笔记时还不知道用户会不会打开它）。
     */
    suspend fun pagesOf(noteId: Long): List<PageSummary>

    /** 在末尾追加一页 */
    suspend fun addPage(noteId: Long): PageSummary?

    /** 复制一页（笔迹一起复制），插在原页之后 */
    suspend fun duplicatePage(noteId: Long, pageId: Long): PageSummary?

    /**
     * 交换相邻两页的顺序。
     *
     * @param delta -1 上移一页，+1 下移一页
     * @return 是否真的移动了（已经在边界上时返回 false）
     */
    suspend fun movePage(noteId: Long, pageId: Long, delta: Int): Boolean

    /**
     * 设置或清除一页的书签。
     *
     * @param label null 清除书签；空字符串表示"有书签但没写标签"
     */
    suspend fun setPageBookmark(noteId: Long, pageId: Long, label: String?): Boolean

    /**
     * 删除一页。
     * **最后一页不允许删** —— 一本没有任何页的笔记没法打开，
     * 与其让各处都处理这个空状态，不如从源头禁止它出现。
     *
     * @return 是否真的删掉了
     */
    suspend fun deletePage(noteId: Long, pageId: Long): Boolean

    /**
     * 载入一页。
     *
     * @return null 表示这一页还没存过（空白页），不是"读取失败"。
     *         读取失败会记录日志并隔离损坏文件，同样返回 null。
     */
    suspend fun loadPage(noteId: Long, pageId: Long): InkPage?

    /**
     * 保存一页。
     *
     * **不阻塞调用方**：内部做节流，最多每 [SAVE_THROTTLE_MS] 写一次盘。
     *
     * 为什么不每落一笔就写：一次书写可能几百笔，每笔都写等于每秒几十次
     * 几 KB 的 IO，费电且磨损闪存。
     * 为什么不"等停笔再写"：抬手到停笔之间如果进程被杀，那一笔就没了。
     *
     * 所以取中间：既给写盘频率一个上界，也保证不存在长时间未落盘的窗口。
     * 再配合 onStop / 离开编辑页时的 [flush]，最坏只会丢不到一秒的书写量。
     */
    fun savePage(noteId: Long, pageId: Long, page: InkPage)

    /** 把所有待写内容立刻落盘并等待完成 */
    suspend fun flush()

    /** 立刻落盘但不等待（用于"不能阻塞、但必须尽快写完"的时机） */
    fun flushAsync()

    /**
     * 读取一页的缩略图。没有则返回 null，界面会退回占位底色。
     *
     * 缩略图和 .ink 在写盘时一起生成（见 RoomInkRepository.writePage），
     * 所以"有页文件但没缩略图"只会出现在第一次写盘之前。
     */
    suspend fun loadThumbnail(noteId: Long, pageId: Long): Bitmap?

    companion object {
        /**
         * 写盘节流间隔。
         * 1.5 秒是个权衡：写盘频率上界约 40 次/分钟（对闪存完全无压力），
         * 而最坏情况下未落盘的内容也只有 1.5 秒的书写量。
         */
        const val SAVE_THROTTLE_MS = 1_500L
    }
}

/**
 * 仓库的全局入口。
 *
 * 改成显式 init 而不是 by lazy：实现需要 Context 来拿 filesDir、开数据库，
 * 而 Context 只有 Application 有。显式初始化让"忘了在 Application 里注册"
 * 变成一句清楚的报错，而不是某处莫名其妙的空指针。
 */
object InkRepositoryProvider {

    @Volatile
    private var instance: InkRepository? = null

    fun init(repository: InkRepository) {
        instance = repository
    }

    val repository: InkRepository
        get() = instance ?: error(
            "InkRepositoryProvider.init() 未被调用 —— " +
                "检查 AndroidManifest 里的 android:name=\".InkApp\" 是否还在",
        )
}
