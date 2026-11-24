package com.inknote.pad.domain

/**
 * 笔记里的一页。
 *
 * 只用 [pageId] 标识页面，**文件名也用 pageId 而不是序号**。
 *
 * 为什么不按序号命名（page-000.ink / page-001.ink …）：
 * 删除中间某一页时，后面所有页的序号都要往前挪，于是要重命名一堆文件。
 * 那个过程一旦被强杀（用户划掉 App、系统回收内存），就会留下
 * "数据库说 5 页、磁盘上文件编号乱掉"的不一致状态，而且极难修复。
 * 用不可复用的 pageId 之后，删页就只是删一个文件加一行记录，
 * 没有任何多步操作需要保持原子性。
 *
 * 序号只存在于数据库里（[orderIndex]），随时可以重排，且重排是纯粹的
 * 数据库事务 —— 要么全成要么全不成。
 */
data class PageSummary(
    val pageId: Long,
    /** 显示顺序，从 0 开始连续 */
    val orderIndex: Int,
    /**
     * 这一页最后一次被写入的时间。
     *
     * 它同时是**缩略图的版本号**：页列表里每行都要显示缩略图，
     * 而缓存必须能判断"手上这张图是不是这一页当前的版本"。
     * 用时间戳做版本比自己维护一个计数器可靠 —— 计数器在进程重启后会归零，
     * 而时间戳不会。
     */
    val updatedAt: Long,
    /**
     * 书签。null 表示没有；空字符串表示有书签但没写标签。
     * 见 PageEntity.bookmarkLabel 的注释。
     */
    val bookmarkLabel: String? = null,
) {
    val isBookmarked: Boolean get() = bookmarkLabel != null
}

/**
 * 删除一页之后，当前应该停在第几页。
 *
 * ============================ 为什么单独抽出来 ============================
 * 这段判断三条分支、还要考虑边界（删的是最后一页、删的是当前页之后的页），
 * 单靠推理写很容易错，而错了的表现是"删完一页以后画面突然跳到别的页" ——
 * 不难发现但很难定位，而且用户会怀疑"我删的是不是不对"。
 *
 * 它是纯函数，可以在 JVM 上把每种情况都钉死，所以没有理由留在 composable 里。
 *
 * @param currentIndex 删除前的当前页下标
 * @param deletedIndex 被删掉的那一页的下标
 * @param newCount     删除后的总页数（至少为 1，仓库层保证不会删到 0）
 */
fun currentIndexAfterDelete(
    currentIndex: Int,
    deletedIndex: Int,
    newCount: Int,
): Int {
    if (newCount <= 0) return 0
    val target = when {
        // 删的就是当前页：停在原位。原位已经变成"后一页"了，
        // 如果是最后一页则退到新的最后一页。
        deletedIndex == currentIndex -> currentIndex
        // 删的是当前页之前的页：所有页整体前移一位，当前内容不变，
        // 所以下标也要跟着减一，否则会跳到下一页去。
        deletedIndex < currentIndex -> currentIndex - 1
        // 删的是当前页之后的页：当前页完全不受影响
        else -> currentIndex
    }
    return target.coerceIn(0, newCount - 1)
}
