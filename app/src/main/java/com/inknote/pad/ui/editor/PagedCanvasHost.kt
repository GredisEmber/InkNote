package com.inknote.pad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import com.inknote.pad.data.file.InkPage
import com.inknote.pad.domain.BrushParams
import com.inknote.pad.domain.NoteSummary
import com.inknote.pad.ink.InkTool
import com.inknote.pad.ink.NoteCanvasView
import com.inknote.pad.ink.StrokeStats

/**
 * 无缝连续滚动：多页纵向排布在一个可滚动列表里。
 *
 * ============================ 为什么要用 LazyColumn ============================
 * 一页的位图在"与屏幕相同"纸型下约 12MB，A3 约 17MB。
 * 把所有页的 `NoteCanvasView` 都摆出来，十页的笔记就是 170MB —— 必然 OOM。
 *
 * LazyColumn 只组合可见项、划走就回收，**内存上限自然被限制在屏幕附近的那几页**。
 * 它顺带还提供了 `rememberSnapFlingBehavior`，松手自动吸附到整页 ——
 * 自己写吸附要处理速度衰减、越界回弹、多指打断，那是几百行而且很难调对。
 *
 * ============================ 每项高度 = 一整屏 + 一个间隙 ============================
 * 画布本身用 `CanvasViewport.fit` 把整页塞进**它自己的尺寸**。
 * 所以只要让每一项的可视高度等于容器高度，页面看起来就和"单页模式"完全一样。
 *
 * 间隙因此不能从画布区域里扣，只能加在它**下面**：
 *   项高 = 容器高 + gap，画布占上面 容器高，gap 那一条露出下一页的开头。
 * 从画布区域里扣的话，页面会整体缩水一点，就不叫"行为不变"了。
 */
@Composable
fun PagedCanvasHost(
    note: NoteSummary,
    pageIds: List<Long>,
    currentIndex: Int,
    /** 外部请求跳页（新建页/页列表跳页）。处理完必须回调 [onJumpHandled]，否则会反复触发 */
    pendingJump: Int?,
    onJumpHandled: () -> Unit,
    /** 松手是否吸附到整页。关掉时可以停在两页中间，同时看到上一页和下一页 */
    snapToPage: Boolean,
    onIndexChange: (Int) -> Unit,
    onPageEdited: (Long, NoteCanvasView) -> Unit,
    onActiveCanvasChanged: (NoteCanvasView?) -> Unit,
    onPaperResolved: (Float, Float) -> Unit,
    onInputProbe: (String) -> Unit,
    onStrokeStats: (StrokeStats?) -> Unit,
    onErased: () -> Unit,
    isLoaded: (Long) -> Boolean,
    contentOf: (Long) -> InkPage?,
    /** 当前工具等显示配置。每页都推同一份 */
    tool: InkTool,
    stylusOnly: Boolean,
    paperArgb: Int,
    canvasBackgroundArgb: Int,
    penArgb: Int,
    highlighterArgb: Int,
    brushParams: BrushParams,
    highlighterWidthRatio: Float,
    highlighterOpacity: Float,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val defaultFling = ScrollableDefaults.flingBehavior()
    val gapPx = with(LocalDensity.current) { PAGE_GAP.toPx() }

    // 容器高度。它决定"一整屏"有多大，也决定吸附的步长
    var containerHeightPx by remember { mutableStateOf(0) }
    val stride = containerHeightPx + gapPx

    // ---- 滚动位置 → 当前页 ----
    // 取"顶边离容器顶最近的项"而不是 firstVisibleItemIndex：
    // 拖到一半时相邻两项的顶边都在容器内，只按 index 会让页码提前跳。
    LaunchedEffect(listState, stride) {
        if (stride <= 0) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val pos = index * stride + offset
            val nearest = ((pos + stride / 2f) / stride).toInt()
            onIndexChange(nearest.coerceIn(0, (pageIds.size - 1).coerceAtLeast(0)))
        }
    }

    // ---- 外部跳页 → 滚过去 ----
    // 只在 pendingJump 非空时执行。不能用 currentIndex 当触发条件：
    // 拖动过程中 currentIndex 一直在变，那样会把用户的手势硬拽回整页位置。
    LaunchedEffect(pendingJump) {
        val target = pendingJump ?: return@LaunchedEffect
        if (containerHeightPx > 0) {
            listState.scrollToItem(target.coerceIn(0, (pageIds.size - 1).coerceAtLeast(0)))
        }
        onJumpHandled()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerHeightPx = it.height },
    ) {
        if (containerHeightPx <= 0) return@Box

        LazyColumn(
            state = listState,
            // 吸附开着才用 snap 行为；关着用 LazyColumn 的默认惯性 ——
            // 默认惯性松手停在哪儿就是哪儿，可以停在两页中间。
            //
            // 两个都无条件求值（rememberSnapFlingBehavior 和默认惯性都是
            // Composable 调用，条件调用会破坏调用顺序约定），只是选择用哪个。
            flingBehavior = if (snapToPage) snapBehavior else defaultFling,
            modifier = Modifier.fillMaxSize(),
            // 间隙已经算进每一项的高度里，这里不能再加 spacing，
            // 否则吸附的落点会和页顶错开
            contentPadding = PaddingValues(0.dp),
        ) {
            items(
                count = pageIds.size,
                key = { index -> pageIds[index] },
            ) { index ->
                val pageId = pageIds[index]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { (containerHeightPx + gapPx).toDp() }),
                ) {
                    if (!isLoaded(pageId)) {
                        // 内容还没读完。铺一块底色避免闪出上一页
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(LocalDensity.current) { containerHeightPx.toDp() })
                                .background(MaterialTheme.colorScheme.background),
                        )
                    } else {
                        PageCanvas(
                            note = note,
                            pageId = pageId,
                            isActive = index == currentIndex,
                            page = contentOf(pageId),
                            tool = tool,
                            stylusOnly = stylusOnly,
                            paperArgb = paperArgb,
                            canvasBackgroundArgb = canvasBackgroundArgb,
                            penArgb = penArgb,
                            highlighterArgb = highlighterArgb,
                            brushParams = brushParams,
                            highlighterWidthRatio = highlighterWidthRatio,
                            highlighterOpacity = highlighterOpacity,
                            onEdited = { canvas -> onPageEdited(pageId, canvas) },
                            onActiveCanvasChanged = onActiveCanvasChanged,
                            onPaperResolved = onPaperResolved,
                            onInputProbe = onInputProbe,
                            onStrokeStats = onStrokeStats,
                            onErased = onErased,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(LocalDensity.current) { containerHeightPx.toDp() }),
                        )
                    }
                }
            }
        }
    }
}

/** 页与页之间的空隙。它同时也是"这里换页了"的视觉提示 */
private val PAGE_GAP = 16.dp

/**
 * 一页的画布。
 *
 * 从 EditorScreen 里抽出来是因为无缝滚动之后**每一页都要一份**，
 * 而那套配置有十几行。留在原地的话会变成"复制 N 份、改一处漏 N-1 处"。
 */
@Composable
private fun PageCanvas(
    note: NoteSummary,
    pageId: Long,
    isActive: Boolean,
    page: InkPage?,
    tool: InkTool,
    stylusOnly: Boolean,
    paperArgb: Int,
    canvasBackgroundArgb: Int,
    penArgb: Int,
    highlighterArgb: Int,
    brushParams: BrushParams,
    highlighterWidthRatio: Float,
    highlighterOpacity: Float,
    onEdited: (NoteCanvasView) -> Unit,
    onActiveCanvasChanged: (NoteCanvasView?) -> Unit,
    onPaperResolved: (Float, Float) -> Unit,
    onInputProbe: (String) -> Unit,
    onStrokeStats: (StrokeStats?) -> Unit,
    onErased: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // key(pageId) 保证换页/换笔记时重建。
    // 不重建的话位图里还留着上一页的像素，而 setStrokes 只改数据不管旧像素。
    AndroidView(
        factory = { context ->
            val canvas = NoteCanvasView(context)
            canvas.paperStyle = NoteCanvasView.PaperStyle.GRID

            // 顺序很重要：先预设文档尺寸、再 setStrokes，最后挂回调。
            // setStrokes 不触发 onHistoryChanged（见它的注释），
            // 顺序反了就会在组合期间写 Compose state。
            //
            // 两个来源的优先级：**页文件里存的尺寸优先**。
            // 它是和笔迹一起写下去的，最可靠；笔记的纸型是它的声明式副本。
            // setDocumentSize 是幂等的（已设定就不再改），所以先调先赢。
            //
            // ⚠️ 新建的页没有文件，这里可能一个尺寸都设不上 ——
            // 那种情况由 update 里的补设路径兜底（见那里的注释）。
            page?.let {
                if (it.pageWidth > 0f && it.pageHeight > 0f) {
                    canvas.setDocumentSize(it.pageWidth, it.pageHeight)
                }
            }
            if (note.hasResolvedPaper) {
                canvas.setDocumentSize(note.paperWidthPx, note.paperHeightPx)
            }
            canvas.setStrokes(page?.strokes.orEmpty())

            // 「与屏幕相同」的笔记在创建时还不知道尺寸（要等画布布局完），
            // 老笔记（v1 迁移过来的）也没有。两种情况都在这里补上。
            if (!note.hasResolvedPaper) {
                canvas.addOnLayoutChangeListener(
                    object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                            oldLeft: Int,
                            oldTop: Int,
                            oldRight: Int,
                            oldBottom: Int,
                        ) {
                            val w = canvas.documentWidth
                            val h = canvas.documentHeight
                            if (w <= 0f || h <= 0f) return
                            canvas.removeOnLayoutChangeListener(this)
                            onPaperResolved(w, h)
                        }
                    },
                )
            }

            canvas
        },
        update = { canvas ->
            // ============================ 尺寸可能在画布建好之后才到位 ============================
            // 新建的页**没有 .ink 文件**，它的尺寸只能来自笔记的纸型；
            // 而笔记的纸型在老笔记上可能是 0（v1 时代没有纸型，或者还没落库）。
            // 那种情况下画布建出来时没有尺寸 —— docWidth 为 0 → 不分配位图 →
            // onDraw 直接 return → **一整片空白**。
            //
            // 所以补一次：尺寸晚到也能用上（setDocumentSize 是幂等的，已设定就不再改）。
            if (canvas.documentWidth <= 0f) {
                page?.let {
                    if (it.pageWidth > 0f && it.pageHeight > 0f) {
                        canvas.setDocumentSize(it.pageWidth, it.pageHeight)
                    }
                }
                if (note.hasResolvedPaper) {
                    canvas.setDocumentSize(note.paperWidthPx, note.paperHeightPx)
                }
            }

            canvas.paperColor = paperArgb
            canvas.canvasBackgroundColor = canvasBackgroundArgb
            canvas.penColorArgb = penArgb
            canvas.highlighterColorArgb = highlighterArgb
            canvas.brushParams = brushParams
            canvas.highlighterWidthRatio = highlighterWidthRatio
            canvas.highlighterOpacity = highlighterOpacity
            canvas.tool = tool
            canvas.stylusOnly = stylusOnly
            // 滚动容器自己处理翻页手势，画布不再管手指滑动 ——
            // 两边都认的话，横向划一下会既滚动又翻页
            canvas.onPageSwipe = null

            // ⚠️ 回调放在 update 里而不是 factory 里。
            // factory 只在创建时跑一次，那时捕获的 isActive 会被**永久固化** ——
            // 页面滑走之后它还以为自己是当前页，工具栏的计数和撤销就指向错的页。
            // update 每次重组都跑，拿到的才是当前值。
            canvas.onHistoryChanged = { _, _, _ -> onEdited(canvas) }
            canvas.onInputProbe = { toolType, source, buttonState ->
                onInputProbe("tool=$toolType src=0x${source.toString(16)} btn=0x${buttonState.toString(16)}")
            }
            canvas.onStrokeCommitted = { stats -> onStrokeStats(stats) }
            canvas.onErased = { onErased() }

            // 只有当前页把活动画布交上去（工具栏的撤销/重做/清空作用在它上面）
            if (isActive) onActiveCanvasChanged(canvas)
        },
        modifier = modifier,
    )
}
