package com.inknote.pad.ui.common

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import com.inknote.pad.data.InkRepositoryProvider

/**
 * 某一页的缩略图。
 *
 * 首页卡片和编辑器的页列表都用它 —— 两处需要的是同一样东西：
 * "把这一页的样子画出来，并且内容变了要跟着变"。
 *
 * @param pageId 要显示的页。null 表示还没有页（新建但没打开过的笔记）
 * @param contentVersion 这一页的内容版本，传 [com.inknote.pad.domain.PageSummary.updatedAt]。
 *        缓存靠它判断"手上这张图是不是当前版本"。详见 [ThumbnailCache]。
 * @param tint 缩略图缺失时的底色
 */
@Composable
fun PageThumbnail(
    noteId: Long,
    pageId: Long?,
    contentVersion: Long,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val repository = InkRepositoryProvider.repository

    // 缓存命中就直接用，不闪一下占位图。
    // key 带上 contentVersion：内容变了要重新取，否则会一直显示旧图。
    var image by remember(pageId, contentVersion) {
        mutableStateOf(pageId?.let { ThumbnailCache.get(it, contentVersion) })
    }

    LaunchedEffect(pageId, contentVersion) {
        if (pageId == null || image != null) return@LaunchedEffect
        val decoded = repository.loadThumbnail(noteId, pageId) ?: return@LaunchedEffect
        val bitmap = decoded.asImageBitmap()
        ThumbnailCache.put(pageId, contentVersion, bitmap)
        image = bitmap
    }

    Box(
        modifier = modifier.background(tint),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Fit：纸的宽高比已经烘进缩略图里了，这里只负责把它放进容器
                contentScale = ContentScale.Fit,
            )
        } else {
            // 必须显式给尺寸：下面是 Canvas（Spacer + drawBehind），
            // 在 Box 里不给修饰符的话会按最小约束塌成 0×0，什么都不画。
            // 底色已经由外层 Box 的 background(tint) 铺好了，这里传透明即可。
            ThumbnailPlaceholder(
                seed = pageId ?: noteId,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 缩略图缺失时的占位：几条长短不一的假笔迹。
 *
 * 只在"还没有真实缩略图"时出现（新建但没打开过的笔记、以及缩略图正在生成的那一帧）。
 * 用种子让每张卡片看起来不同，比一片纯色方块更像"这里以后会有内容"。
 */
@Composable
fun ThumbnailPlaceholder(
    seed: Long,
    modifier: Modifier = Modifier,
) {
    val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    Canvas(modifier = modifier) {
        drawPlaceholderLines(seed, strokeColor)
    }
}

private fun DrawScope.drawPlaceholderLines(seed: Long, color: Color) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val base = (seed % 3).toInt()
    var y = h * 0.22f
    var line = 0
    while (y < h * 0.86f) {
        val lengthFactor = 0.92f - ((base + line) % 3) * 0.18f
        drawLine(
            color = color,
            start = Offset(w * 0.16f, y),
            end = Offset(w * 0.16f + w * 0.68f * lengthFactor, y),
            strokeWidth = h * 0.035f,
            cap = StrokeCap.Round,
        )
        y += h * 0.16f
        line++
    }
}

/**
 * 缩略图的内存缓存。
 *
 * ============================ 缓存键为什么必须带版本 ============================
 * 最初这里只按 pageId 缓存，结果是**缩略图永远不更新**：
 * 用户写完返回首页时，磁盘上的 PNG 已经重新生成了，但缓存里还是旧位图，
 * 而 pageId 没变，于是 remember 和 LaunchedEffect 都不会重跑。
 *
 * 版本用页面的 `updatedAt`，它和缩略图在同一个写盘调用里更新
 * （见 RoomInkRepository.writePage）。刻意不用文件时间戳：
 * `File.lastModified()` 的粒度取决于文件系统，同一秒内写两次可能拿到相同的值，
 * 那会变成"偶尔不更新"这种最难查的故障。
 *
 * ============================ 为什么缓存在界面层而不是仓库层 ============================
 * `LazyVerticalGrid` / `LazyColumn` 滚动时条目会反复进入/离开组合，
 * 每次重新解 PNG 会明显卡顿，所以必须缓存。
 * 但缓存放仓库层是错的：仓库的生命周期是整个进程，
 * 笔记删掉之后那些位图还会一直占着内存，而且没有任何时机可以释放。
 */
private object ThumbnailCache {

    /** 一屏可见条目加上滚动缓冲，24 张足够；一张 A4 缩略图约 416KB，合计约 10MB */
    private const val MAX_ENTRIES = 24

    /** 缓存值带上写入时的版本，取的时候要核对 —— 只按 pageId 会返回过期内容 */
    private class Entry(val version: Long, val bitmap: ImageBitmap)

    private val cache = object : LruCache<Long, Entry>(MAX_ENTRIES) {}

    /** 只有版本一致才算命中；版本不同当作没有缓存 */
    fun get(pageId: Long, version: Long): ImageBitmap? =
        cache.get(pageId)?.takeIf { it.version == version }?.bitmap

    fun put(pageId: Long, version: Long, bitmap: ImageBitmap) {
        cache.put(pageId, Entry(version, bitmap))
    }
}
