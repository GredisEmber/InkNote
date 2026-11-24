package com.inknote.pad.ui.editor

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inknote.pad.R
import com.inknote.pad.data.AppPreferencesProvider
import com.inknote.pad.data.InkRepositoryProvider
import com.inknote.pad.data.file.InkPage
import com.inknote.pad.domain.BrushParams
import com.inknote.pad.domain.InkPalette
import com.inknote.pad.domain.InkWidthSteps
import com.inknote.pad.domain.NoteSummary
import com.inknote.pad.domain.PageSummary
import com.inknote.pad.domain.PageTurnMode
import com.inknote.pad.domain.currentIndexAfterDelete
import com.inknote.pad.ink.InkTool
import com.inknote.pad.ink.NoteCanvasView
import com.inknote.pad.ink.StrokeStats
import com.inknote.pad.ink.StrokeStyle
import com.inknote.pad.ink.StylusButtonState
import com.inknote.pad.ui.icons.AppIcons
import com.inknote.pad.ui.theme.InkOnDarkPaper
import com.inknote.pad.ui.theme.InkOnLightPaper
import com.inknote.pad.ui.theme.PaperDark
import com.inknote.pad.ui.theme.PaperLight
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 持有 AndroidView 创建出来的那个 View。
 *
 * 刻意【不】用 mutableStateOf 装它：
 * factory 是在组合过程中执行的，在那里写 Compose state 属于
 * "组合期间写状态"，轻则多一次重组、重则抛异常。
 * 用一个普通容器，只在按钮回调（非组合期）里读它，最干净。
 */
private class CanvasRef {
    var view: NoteCanvasView? = null
}

/**
 * 编辑页。
 *
 * 这一页的职责是"接线"：把仓库里的笔迹交给画布、把画布的历史回调
 * 转成 Compose 状态、把按钮点击转回画布操作。所有真正的算法在 ink/ 包里，
 * 这一层不该出现任何几何或绘制代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    noteId: Long,
    noteSnapshot: NoteSummary?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = InkRepositoryProvider.repository
    val prefs = AppPreferencesProvider.prefs
    val scope = rememberCoroutineScope()

    // ---- 先确定"这篇笔记是什么" ----
    //
    // ============================ 为什么不能只用传进来的快照 ============================
    // 调用方是从笔记【列表】里按 id 找的，而那个列表来自 Room 的 Flow ——
    // 它异步发射。刚创建的笔记可能还没发射过来，此时快照是 null。
    // 如果据此判定"笔记不存在"并退回列表，用户看到的就是
    // "点了新建、闪一下又回到首页"。
    //
    // 所以这里按 id 直接查一次库：createNote 是 suspend 的，
    // 返回时数据库已经写完，查得到。快照只作为查库失败时的兜底。
    var note by remember(noteId) { mutableStateOf(noteSnapshot) }
    LaunchedEffect(noteId) {
        val fromDb = repository.findNote(noteId) ?: noteSnapshot
        note = fromDb
        if (fromDb == null) {
            // 笔记确实不存在（在别处被删掉了）。退回列表，
            // 不要让用户停留在指向不存在数据的空壳页面上。
            onBack()
        }
    }

    val currentNote = note
    if (currentNote == null) {
        // 还在查库，或者已经决定退回。给一块空白，别闪内容。
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        return
    }

    // ---- 页列表与当前页 ----
    var pages by remember(noteId) { mutableStateOf<List<PageSummary>>(emptyList()) }
    var currentIndex by remember(noteId) { mutableStateOf(0) }
    var showPageList by remember { mutableStateOf(false) }

    // pagesOf 保证至少有一页 —— 新笔记的第一页由它创建。
    // 放在这里而不是创建笔记时：创建笔记时还不知道用户会不会真的打开它。
    LaunchedEffect(noteId) {
        pages = repository.pagesOf(noteId)
        currentIndex = 0
    }

    val currentPageId = pages.getOrNull(currentIndex)?.pageId

    // ---- 各页内容 ----
    // ============================ 为什么从"一页"改成"一页映射" ============================
    // 无缝滚动时屏幕上同时有三页，前后两页的内容必须**已经就绪** ——
    // 滑过去才去读文件的话，滚动会一顿一顿的。
    //
    // 键是 pageId，值是 InkPage?（null = 读过了、是空页）。
    // 用 containsKey 而不是"值是否为空"来区分"还没读"和"读了是空的"。
    val pageContents = remember(noteId) { mutableStateMapOf<Long, InkPage?>() }

    LaunchedEffect(noteId, pages, currentIndex) {
        if (pages.isEmpty()) return@LaunchedEffect
        // 前后各两页。为什么不是一页：LazyColumn 滚动中会多组合一屏之外的一项，
        // 只备一页的话那一项会掉进"没加载"的占位，滑回去还得重建画布。
        val wanted = ((currentIndex - 2)..(currentIndex + 2))
            .mapNotNull { pages.getOrNull(it)?.pageId }
        for (pageId in wanted) {
            if (!pageContents.containsKey(pageId)) {
                pageContents[pageId] = repository.loadPage(noteId, pageId)
            }
        }
        // 划远了的放掉，否则滑完整篇笔记、所有页的笔迹都留在内存里
        pageContents.keys.retainAll(wanted.toSet())
    }

    // ---- 笔记纸型尺寸未知时，从已有的页文件回填 ----
    //
    // ============================ 为什么必须回填 ============================
    // **新建的页没有 .ink 文件**，它的文档尺寸只能来自笔记的纸型。
    // 而老笔记（v1 时代没有纸型概念）或还没落库的笔记，纸型尺寸是 0 ——
    // 于是新页的画布拿不到尺寸，渲染成一片空白。
    //
    // 回填的来源选**页文件**而不是重新猜：页文件里的尺寸是和笔迹一起写下去的，
    // 比笔记的声明式副本更可靠（这也是它读取时优先级更高的原因）。
    LaunchedEffect(noteId, currentNote.hasResolvedPaper, pageContents.size) {
        if (currentNote.hasResolvedPaper) return@LaunchedEffect
        val from = pageContents.values.firstOrNull {
            it != null && it.pageWidth > 0f && it.pageHeight > 0f
        } ?: return@LaunchedEffect
        repository.fillPaperPixelsIfUnknown(noteId, from.pageWidth, from.pageHeight)
    }

    // 当前页的内容。单页模式（左右滑动）直接用它建画布。
    val loadedPage = currentPageId?.let { pageContents[it] }
    val initialStrokes = loadedPage?.strokes.orEmpty()

    val canvasRef = remember { CanvasRef() }
    var lastStats by remember { mutableStateOf<StrokeStats?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // ⚠️ 这三个的初值必须是"空"，不能用 initialStrokes.size 之类的派生值。
    // 载入是异步的，首次组合时数据还没到；用派生值当 remember 的初值会被
    // 永久固化成 0，之后再也不会更新 —— 症状就是"屏幕上有三笔、计数却写 0 笔"。
    var strokeCount by remember(noteId) { mutableStateOf(0) }
    var canUndo by remember(noteId) { mutableStateOf(false) }
    var canRedo by remember(noteId) { mutableStateOf(false) }

    // key 里带上 pageId：换页时重新载入，不会拿上一页的笔迹去渲染这一页
    // 离开编辑页时尽力把还没落盘的内容写掉。
    // 用 flushAsync 而不是 flush：onDispose 不能挂起，而且此时 ViewModel
    // 可能已经被销毁，但仓库自己的作用域还活着，写盘一定能完成。
    DisposableEffect(noteId) {
        onDispose { repository.flushAsync() }
    }

    /**
     * 换页。
     *
     * 换之前先把当前页的快照交给仓库落盘 —— 用户画完最后一笔立刻滑走时，
     * 节流窗口可能还没到，不显式落盘就有丢掉那一笔的风险。
     */
    /**
     * 程序化跳页请求，供无缝滚动模式使用。
     *
     * 不能用 currentIndex 当触发条件：**拖动过程中它一直在变**，
     * 那样每次滚动都会把用户的手势硬拽回整页位置。
     * 单独一个"请求"状态，宿主处理完就清掉，语义才清楚。
     */
    var pendingJump by remember(noteId) { mutableStateOf<Int?>(null) }

    fun goToPage(index: Int) {
        if (index !in pages.indices || index == currentIndex) return
        repository.flushAsync()
        // 两个都设：单页模式认 currentIndex，滚动模式认 pendingJump。
        // 各自忽略用不上的那一个，比在这里判断模式更不容易错。
        currentIndex = index
        pendingJump = index
    }

    fun addPage() {
        scope.launch {
            repository.flushAsync()
            val created = repository.addPage(noteId) ?: return@launch
            pages = repository.pagesOf(noteId)
            currentIndex = pages.indexOfFirst { it.pageId == created.pageId }
                .coerceAtLeast(0)
        }
    }

    /**
     * 复制指定页。**必须显式传 index**，不能用"当前页"。
     *
     * 原因：currentPageId 是从 pages[currentIndex] 派生出来的组合期值。
     * 如果在回调里先 goToPage(index) 再调一个"复制当前页"的函数，
     * 那次 currentIndex 的修改还没走完重组，函数读到的仍是旧值 —— 会复制错页。
     */
    fun duplicatePageAt(index: Int) {
        val pageId = pages.getOrNull(index)?.pageId ?: return
        scope.launch {
            repository.flushAsync()
            val created = repository.duplicatePage(noteId, pageId) ?: return@launch
            pages = repository.pagesOf(noteId)
            currentIndex = pages.indexOfFirst { it.pageId == created.pageId }.coerceAtLeast(0)
            pendingJump = currentIndex
        }
    }

    /** 删除指定页。同样必须显式传 index，理由见 duplicatePageAt */
    fun deletePageAt(index: Int) {
        val pageId = pages.getOrNull(index)?.pageId ?: return
        scope.launch {
            if (!repository.deletePage(noteId, pageId)) return@launch
            pages = repository.pagesOf(noteId)
            // 下标怎么变是纯逻辑，抽到 domain 里并做了单元测试 ——
            // 这段分支判断容易错，错了的表现是"删完一页画面跳到别处"。
            currentIndex = currentIndexAfterDelete(
                currentIndex = currentIndex,
                deletedIndex = index,
                newCount = pages.size,
            )
            pendingJump = currentIndex
        }
    }

    // 当前工具。
    var tool by remember { mutableStateOf(InkTool.PEN) }

    // 笔侧键单击 → 切换钢笔/橡皮。
    //
    // 为什么是"单击切换"而不是"按住临时切"：实测系统只把 ACTION_UP 交给应用，
    // ACTION_DOWN 被联想的 BluetoothPenInputPolicy 在系统层消费掉了
    // （详见 StylusButtonState 的注释）。没有 DOWN 就没有"按住"，只能做切换。
    //
    // lastHandledPenClick 的初值取当前计数：否则进入编辑页时 LaunchedEffect 首跑
    // 会把进入之前的点击当成一次新点击，一进来就跳到橡皮。
    val penClicks by StylusButtonState.clickCount.collectAsState()
    var lastHandledPenClick by remember { mutableStateOf(penClicks) }
    LaunchedEffect(penClicks) {
        if (penClicks != lastHandledPenClick) {
            lastHandledPenClick = penClicks
            tool = if (tool == InkTool.ERASER) InkTool.PEN else InkTool.ERASER
        }
    }

    // 上次落笔的输入诊断（toolType / source / buttonState）。
    // 侧键在不同 ROM 上上报方式差异很大，没有它就只能靠猜。
    var inputProbe by remember { mutableStateOf<String?>(null) }

    val hostView = LocalView.current
    DisposableEffect(hostView) {
        // 写字的时候别让屏幕灭掉。只在这个页面生效，回列表就恢复系统默认。
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

    // 关键点：主题相关的东西必须在 composable 作用域里读出来再捕获进 lambda。
    // AndroidView 的 factory / update 都是普通 lambda，不是 @Composable，
    // 在里面直接调 isSystemInDarkTheme() 会编译失败。
    //
    // ============================ 纸张刻意不跟随深色主题 ============================
    // 笔迹里存的是写入时的颜色（Stroke.colorArgb）。如果纸色跟着主题走，
    // "浅色下写的黑字 → 切到深色 → 黑字在黑纸上"，历史笔记会直接看不见。
    // 纸就是纸：它永远是浅色的，主题只影响界面外壳（工具栏、首页、页面外那圈底色）。
    // GoodNotes / Notability 也是这个做法。
    val darkTheme = isSystemInDarkTheme()
    val paperArgb = PaperLight.toArgb()
    // ---- 两支笔各自的颜色 ----
    val penColorSlot by prefs.penColor.slot.collectAsState()
    val penCustomColor by prefs.penColor.custom.collectAsState()
    // 从已收集的 StateFlow 派生，而不是读 prefs.penColor.argb ——
    // 后者是普通 getter，读它不会建立订阅，颜色变了界面不重组
    val penArgb = InkPalette.colorForSlot(penColorSlot, penCustomColor)

    val hlColorSlot by prefs.highlighterColor.slot.collectAsState()
    val hlCustomColor by prefs.highlighterColor.custom.collectAsState()
    val hlArgb = InkPalette.colorForSlot(hlColorSlot, hlCustomColor)

    // ---- 笔刷参数 ----
    val brushParams by prefs.brushParams.collectAsState()
    val hlWidthIndex by prefs.highlighterWidthIndex.collectAsState()
    val hlOpacity by prefs.highlighterOpacity.collectAsState()
    val hlWidthRatio = InkWidthSteps.ratioAt(hlWidthIndex) * InkWidthSteps.HIGHLIGHTER_MULTIPLIER

    // 二级菜单：哪个工具的小菜单开着（null = 关闭）
    var menuTool by remember { mutableStateOf<InkTool?>(null) }
    // 三级窗口：null = 关闭
    var detail by remember { mutableStateOf<PanelState?>(null) }

    // 取色器正在给哪支笔挑颜色
    var colorPickerTarget by remember { mutableStateOf<InkTool?>(null) }

    // 返回键不用在这里接管：二级菜单的 Popup 是 focusable 的、
    // 三级窗口是 Dialog，两者都会自己响应返回键并回调 onDismissRequest。

    val canvasBackgroundArgb = remember(darkTheme) {
        if (darkTheme) 0xFF2A2A2E.toInt() else 0xFFE3E1E8.toInt()
    }

    // "只认手写笔"和翻页方式来自全局设置。用 collectAsState 而不是直接读
    // prefs 的属性：设置界面改了之后这里要跟着重组。
    val stylusOnly by prefs.stylusOnly.collectAsState()
    val seamlessSnap by prefs.seamlessSnap.collectAsState()
    val pageTurnMode by prefs.pageTurnMode.collectAsState()
    val showStrokeInfo by prefs.showStrokeInfo.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentNote.title.ifBlank { stringResource(R.string.note_untitled) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = AppIcons.Back,
                            contentDescription = stringResource(R.string.editor_back),
                        )
                    }
                },
                actions = {
                    // 页码指示。点击打开页列表（跳页 / 新建 / 复制 / 删除）。
                    // 即便只有一页也显示 —— 否则用户没地方发现"可以加页"。
                    TextButton(onClick = { showPageList = true }) {
                        Text(
                            text = stringResource(
                                R.string.editor_page_indicator,
                                currentIndex + 1,
                                pages.size.coerceAtLeast(1),
                            ),
                        )
                    }
                    IconButton(onClick = { addPage() }) {
                        Icon(
                            imageVector = AppIcons.Add,
                            contentDescription = stringResource(R.string.editor_add_page),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 工具栏里已经不再挂任何面板。
            // 二级菜单锚在工具按钮上方浮着（不挤压画布），
            // 三级窗口是独立的居中 Dialog（见本函数末尾）。
            EditorToolbar(
                strokeCount = strokeCount,
                canUndo = canUndo,
                canRedo = canRedo,
                lastStats = lastStats,
                tool = tool,
                openMenuTool = menuTool,
                onToolTap = { tapped ->
                    // 点一下选中；**再点一次已选中的工具**才开关它的菜单。
                    // 如果第一次点就弹出来，"我只是想切回钢笔写字"也会白白多一层遮罩。
                    val hasMenu = tapped != InkTool.ERASER
                    if (tool == tapped && hasMenu) {
                        menuTool = if (menuTool == tapped) null else tapped
                    } else {
                        tool = tapped
                        menuTool = null
                    }
                },
                onDismissMenu = { menuTool = null },
                onOpenPage = { tool2, page ->
                    menuTool = null
                    detail = PanelState(tool = tool2, page = page)
                },
                penColorSlot = penColorSlot,
                penCustomColor = penCustomColor,
                hlColorSlot = hlColorSlot,
                hlCustomColor = hlCustomColor,
                brushParams = brushParams,
                hlWidthIndex = hlWidthIndex,
                hlOpacity = hlOpacity,
                showStrokeInfo = showStrokeInfo,
                inputProbe = inputProbe,
                onUndo = { canvasRef.view?.undo() },
                onRedo = { canvasRef.view?.redo() },
                onClear = { showClearConfirm = true },
            )
        },
    ) { innerPadding ->
        // ============================ 两种翻页模式 ============================
        // 上下滑动 → 无缝连续滚动（多页纵向排布 + 松手吸附，见 PagedCanvasHost）
        // 左右滑动 → 整页切换（一次一页，这是用户自己选的交互，保持原样）
        if (pageTurnMode == PageTurnMode.VERTICAL && pages.isNotEmpty()) {
            PagedCanvasHost(
                note = currentNote,
                pageIds = pages.map { it.pageId },
                currentIndex = currentIndex,
                pendingJump = pendingJump,
                onJumpHandled = { pendingJump = null },
                snapToPage = seamlessSnap,
                onIndexChange = { index -> if (index != currentIndex) currentIndex = index },
                onPageEdited = { pageId, canvas ->
                    repository.savePage(
                        noteId,
                        pageId,
                        InkPage(
                            pageWidth = canvas.documentWidth,
                            pageHeight = canvas.documentHeight,
                            strokes = canvas.snapshotStrokes(),
                        ),
                    )
                    // 工具栏（笔数 / 撤销 / 重做）反映的是**当前页**。
                    // 别的页被写的时候不要去动它 —— 否则在第 3 页写字、
                    // 工具栏却显示第 1 页的数字。
                    //
                    // 先比较再赋值：不比较的话每次重组都会写一次 state，
                    // 值相同虽然不会引起重组，但读起来像是"每次都在改"。
                    if (pageId == currentPageId) {
                        if (strokeCount != canvas.strokeCount) strokeCount = canvas.strokeCount
                        if (canUndo != canvas.canUndo) canUndo = canvas.canUndo
                        if (canRedo != canvas.canRedo) canRedo = canvas.canRedo
                    }
                },
                onActiveCanvasChanged = { canvas ->
                    // 工具栏的撤销/重做/清空作用在"当前页那块画布"上
                    canvasRef.view = canvas
                    canvas?.let {
                        if (strokeCount != it.strokeCount) strokeCount = it.strokeCount
                        if (canUndo != it.canUndo) canUndo = it.canUndo
                        if (canRedo != it.canRedo) canRedo = it.canRedo
                    }
                },
                onPaperResolved = { w, h -> repository.fillPaperPixelsIfUnknown(noteId, w, h) },
                onInputProbe = { probe -> inputProbe = probe },
                onStrokeStats = { stats -> lastStats = stats },
                onErased = { lastStats = null },
                // containsKey 而不是值判空：区分"还没读"和"读了是空页"
                isLoaded = { pageContents.containsKey(it) },
                contentOf = { pageContents[it] },
                tool = tool,
                stylusOnly = stylusOnly,
                paperArgb = paperArgb,
                canvasBackgroundArgb = canvasBackgroundArgb,
                penArgb = penArgb,
                highlighterArgb = hlArgb,
                brushParams = brushParams,
                highlighterWidthRatio = hlWidthRatio,
                highlighterOpacity = hlOpacity,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            // ready 要求"页列表读到了" + **"这一页的内容读完了"**。
            // 少了后半条，换页后会先拿空内容建一次画布，
            // 而正确内容到位时 key 没变、factory 不会再执行，画布就停在空页上。
            val ready = currentPageId != null && pageContents.containsKey(currentPageId)
            if (!ready) {
                // 文件还没读完、或者页列表还没读出来。给一块空白避免闪一下别的页面。
                // 正常只有一两毫秒，肉眼基本看不到。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.background),
                )
            } else {
                // key(currentPageId)：换页时**强制重建**画布。
                // 不重建的话，画布位图里还留着上一页的笔迹，而 setStrokes
                // 只改数据不管旧像素 —— 会看到两页内容叠在一起。
                key(currentPageId) {
                    val pageId = currentPageId
                    AndroidView(
                        factory = { context ->
                            val canvas = NoteCanvasView(context)
                            canvas.paperStyle = NoteCanvasView.PaperStyle.GRID
                            canvas.paperColor = paperArgb
                            canvas.canvasBackgroundColor = canvasBackgroundArgb
                            canvas.penColorArgb = penArgb
                            canvas.highlighterColorArgb = hlArgb
                            canvas.brushParams = brushParams
                            canvas.highlighterWidthRatio = hlWidthRatio
                            canvas.highlighterOpacity = hlOpacity
                            canvas.stylusOnly = stylusOnly
                            canvas.tool = tool

                            // 顺序很重要：先预设文档尺寸、再 setStrokes，最后挂回调。
                            // setStrokes 不触发 onHistoryChanged（见它的注释），
                            // 顺序反了就会在组合期间写 Compose state。
                            //
                            // 两个来源的优先级：**页文件里存的尺寸优先**。
                            // 它是和笔迹一起写下去的，最可靠；笔记的纸型是它的声明式副本。
                            // setDocumentSize 是幂等的（已设定就不再改），所以先调先赢。
                            loadedPage?.let {
                                if (it.pageWidth > 0f && it.pageHeight > 0f) {
                                    canvas.setDocumentSize(it.pageWidth, it.pageHeight)
                                }
                            }
                            if (currentNote.hasResolvedPaper) {
                                canvas.setDocumentSize(currentNote.paperWidthPx, currentNote.paperHeightPx)
                            }
                            canvas.setStrokes(initialStrokes)

                            // 「与屏幕相同」的笔记在创建时还不知道尺寸（要等画布布局完），
                            // 老笔记（v1 迁移过来的）也没有。两种情况都在这里补上：
                            // 首次布局后文档尺寸已经锁定，把它写进数据库就不再变。
                            if (!currentNote.hasResolvedPaper) {
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
                                            repository.fillPaperPixelsIfUnknown(noteId, w, h)
                                        }
                                    },
                                )
                            }

                            canvas.onHistoryChanged = { count, undo, redo ->
                                strokeCount = count
                                canUndo = undo
                                canRedo = redo
                                // 每次历史变化都把最新快照交给仓库。
                                // 节流和原子写盘都在仓库内部，这里只管给数据。
                                repository.savePage(
                                    noteId,
                                    pageId,
                                    InkPage(
                                        pageWidth = canvas.documentWidth,
                                        pageHeight = canvas.documentHeight,
                                        strokes = canvas.snapshotStrokes(),
                                    ),
                                )
                            }
                            canvas.onInputProbe = { toolType, source, buttonState ->
                                inputProbe = "tool=$toolType src=0x${source.toString(16)} btn=0x${buttonState.toString(16)}"
                            }
                            canvas.onStrokeCommitted = { stats -> lastStats = stats }
                            canvas.onErased = { lastStats = null }

                            canvasRef.view = canvas
                            canvas
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        update = { canvas ->
                            // 新建的页没有 .ink 文件，尺寸可能晚于画布创建才到位。
                            // 见 PagedCanvasHost 里同一处补设的注释。
                            if (canvas.documentWidth <= 0f) {
                                loadedPage?.let {
                                    if (it.pageWidth > 0f && it.pageHeight > 0f) {
                                        canvas.setDocumentSize(it.pageWidth, it.pageHeight)
                                    }
                                }
                                if (currentNote.hasResolvedPaper) {
                                    canvas.setDocumentSize(currentNote.paperWidthPx, currentNote.paperHeightPx)
                                }
                            }
                            canvas.paperColor = paperArgb
                            canvas.canvasBackgroundColor = canvasBackgroundArgb
                            canvas.penColorArgb = penArgb
                            canvas.highlighterColorArgb = hlArgb
                            canvas.brushParams = brushParams
                            canvas.highlighterWidthRatio = hlWidthRatio
                            canvas.highlighterOpacity = hlOpacity
                            canvas.tool = tool
                            canvas.stylusOnly = stylusOnly
                            // 翻页方向来自全局设置
                            canvas.pageTurnMode = pageTurnMode
                            // ⚠️ onPageSwipe 每次重组都要重挂：它捕获了 currentIndex，
                            // 而 currentIndex 会变。挂在 factory 里的话只有换页
                            // （也就是重建画布）时才会更新，翻页边界判断就会用旧值。
                            canvas.onPageSwipe = { direction ->
                                goToPage(currentIndex + direction)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * 交换相邻两页的顺序。
     *
     * 移动之后当前页要跟着走：如果移动的就是当前页，或者当前页被"挤"到了另一侧，
     * 当前页的下标都会变。用同一个 delta 平移即可 —— 但只在确实移动成功时。
     */
    fun movePageAt(index: Int, delta: Int) {
        val pageId = pages.getOrNull(index)?.pageId ?: return
        scope.launch {
            if (!repository.movePage(noteId, pageId, delta)) return@launch
            pages = repository.pagesOf(noteId)
            currentIndex = when {
                index == currentIndex -> index + delta
                // 被换位的那一页正好是当前页所在的位置：当前页被挤到了相反方向
                index + delta == currentIndex -> currentIndex - delta
                else -> currentIndex
            }.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        }
    }

    fun setBookmarkAt(index: Int, label: String?) {
        val pageId = pages.getOrNull(index)?.pageId ?: return
        scope.launch {
            if (!repository.setPageBookmark(noteId, pageId, label)) return@launch
            pages = repository.pagesOf(noteId)
        }
    }

    if (showPageList) {
        PageListDialog(
            noteId = noteId,
            pages = pages,
            currentIndex = currentIndex,
            tint = Color(currentNote.placeholderTintArgb),
            onDismiss = { showPageList = false },
            onJump = { index ->
                showPageList = false
                goToPage(index)
            },
            onAddPage = {
                showPageList = false
                addPage()
            },
            onDuplicateCurrent = { duplicatePageAt(currentIndex) },
            onDelete = { index -> deletePageAt(index) },
            onMove = { index, delta -> movePageAt(index, delta) },
            onBookmark = { index, label -> setBookmarkAt(index, label) },
        )
    }

    // 取色器按目标分派到对应的那支笔。
    // 用一个"目标是哪支笔"的状态而不是布尔值：加第三支笔的时候
    // 布尔值就得改成别的，而这里只要多一个分支。
    // 三级窗口：屏幕正中的独立窗口。
    // 放在这里而不是工具栏里 —— 它内容多、停留久，居中之后
    // 不用关心锚在哪，横竖屏都不会跑偏。
    detail?.let { state ->
        BrushDetailDialog(
            state = state,
            onDismiss = { detail = null },
            penColorSlot = penColorSlot,
            penCustomColor = penCustomColor,
            onPenColorSlot = { prefs.penColor.setSlot(it) },
            onEditPenCustom = { colorPickerTarget = InkTool.PEN },
            hlColorSlot = hlColorSlot,
            hlCustomColor = hlCustomColor,
            onHlColorSlot = { prefs.highlighterColor.setSlot(it) },
            onEditHlCustom = { colorPickerTarget = InkTool.HIGHLIGHTER },
            params = brushParams,
            onParamsChange = { prefs.setBrushParams(it) },
            hlWidthIndex = hlWidthIndex,
            onHlWidthChange = { prefs.setHighlighterWidthIndex(it) },
            hlOpacity = hlOpacity,
            onHlOpacityChange = { prefs.setHighlighterOpacity(it) },
        )
    }

    colorPickerTarget?.let { target ->
        val initial = when (target) {
            InkTool.HIGHLIGHTER -> hlCustomColor ?: InkPalette.DEFAULT_CUSTOM
            else -> penCustomColor ?: InkPalette.DEFAULT_CUSTOM
        }
        ColorPickerDialog(
            // 已经挑过就从那个颜色开始，没挑过就从默认色开始 ——
            // 每次打开都从头调一遍是没必要的
            initialArgb = initial,
            onDismiss = { colorPickerTarget = null },
            onConfirm = { argb ->
                when (target) {
                    InkTool.HIGHLIGHTER -> prefs.highlighterColor.setCustomColor(argb)
                    else -> prefs.penColor.setCustomColor(argb)
                }
                colorPickerTarget = null
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_canvas_title)) },
            text = { Text(stringResource(R.string.clear_canvas_message, strokeCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        canvasRef.view?.clearAll()
                        showClearConfirm = false
                    },
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EditorToolbar(
    strokeCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    lastStats: StrokeStats?,
    tool: InkTool,
    openMenuTool: InkTool?,
    onToolTap: (InkTool) -> Unit,
    onDismissMenu: () -> Unit,
    onOpenPage: (InkTool, PanelPage) -> Unit,
    penColorSlot: Int,
    penCustomColor: Int?,
    hlColorSlot: Int,
    hlCustomColor: Int?,
    brushParams: BrushParams,
    hlWidthIndex: Int,
    hlOpacity: Float,
    showStrokeInfo: Boolean,
    inputProbe: String?,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 工具栏只剩"工具 + 操作"两类东西。
                // 颜色、笔宽、笔刷参数全部搬进各支笔的二级菜单 ——
                // 那些是"配置"，不是"写字时反复切的东西"。
                ToolSelector(
                    current = tool,
                    openMenuTool = openMenuTool,
                    onTap = onToolTap,
                    menuContent = { menuForTool ->
                        BrushMenuPopup(
                            tool = menuForTool,
                            onDismiss = onDismissMenu,
                            onOpenPage = { page -> onOpenPage(menuForTool, page) },
                            penColorSlot = penColorSlot,
                            penCustomColor = penCustomColor,
                            hlColorSlot = hlColorSlot,
                            hlCustomColor = hlCustomColor,
                            params = brushParams,
                            hlWidthIndex = hlWidthIndex,
                            hlOpacity = hlOpacity,
                        )
                    },
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        imageVector = AppIcons.Undo,
                        contentDescription = stringResource(R.string.editor_undo),
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        imageVector = AppIcons.Redo,
                        contentDescription = stringResource(R.string.editor_redo),
                    )
                }
                Text(
                    text = stringResource(R.string.editor_stroke_count, strokeCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onClear, enabled = canUndo) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = stringResource(R.string.editor_clear),
                    )
                }
            }

            // 笔迹信息是**诊断信息**（采样点数、压感区间、输入来源），
            // 日常写字时它只是一行噪声。默认关，需要时在设置里打开 ——
            // 之前它一直显示着，那是开发期为了排查侧键和压感才加的。
            if (showStrokeInfo) {
                Text(
                    text = statusLine(lastStats, tool, inputProbe),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

/**
 * 笔色选择：三个固定预设 + 一个自定义槽位。
 *
 * ============================ 槽位为什么拆成两项 ============================
 * 「自定义」和「调整颜色…」是两个不同的意图：
 *  · 已经有自定义色了，只是想再用一次 → 选「自定义」，一步到位
 *  · 想改一个颜色 → 选「调整颜色…」，打开取色器
 * 合成一项的话，复用已有颜色也要走一遍取色器再点确定（三步），
 * 而"复用"恰恰是更常见的动作。
 *
 * 还没挑过自定义色时，「自定义」项显示成空槽位，点它会直接打开取色器 ——
 * 没有东西可选的时候，"选中"这个动作本身就没有意义。
 */
/**
 * 工具选择器（钢笔 / 荧光笔 / 橡皮）。
 *
 * ============================ 二级面板怎么进 ============================
 * **点一下选中，再点一下已选中的工具才打开面板。**
 *
 * 不做成"一点就弹面板"：面板会占掉半屏，而用户点钢笔的绝大多数情况
 * 只是"我要写钢笔字"（比如刚从橡皮切回来）—— 那时弹一个面板出来挤走画布
 * 是纯粹的干扰。
 *
 * 也不做成"长按打开"：长按完全没有可见性，用户根本不会去试。
 * 选中的按钮下方多一个小三角，就是"这里还能再点一层"的提示。
 *
 * 选中的用圆形 primaryContainer 底衬，比只改图标颜色更容易一眼看出当前工具 ——
 * 手写场景下用户经常在"以为在写字、其实在擦"的状态里丢掉内容。
 */
@Composable
private fun ToolSelector(
    current: InkTool,
    openMenuTool: InkTool?,
    onTap: (InkTool) -> Unit,
    menuContent: @Composable (InkTool) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ToolButton(
            icon = AppIcons.Pen,
            labelRes = R.string.editor_tool_pen,
            selected = current == InkTool.PEN,
            menuOpen = openMenuTool == InkTool.PEN,
            hasPanel = true,
            onClick = { onTap(InkTool.PEN) },
            menu = { menuContent(InkTool.PEN) },
        )
        ToolButton(
            icon = AppIcons.Highlighter,
            labelRes = R.string.editor_tool_highlighter,
            selected = current == InkTool.HIGHLIGHTER,
            menuOpen = openMenuTool == InkTool.HIGHLIGHTER,
            hasPanel = true,
            onClick = { onTap(InkTool.HIGHLIGHTER) },
            menu = { menuContent(InkTool.HIGHLIGHTER) },
        )
        ToolButton(
            icon = AppIcons.Eraser,
            labelRes = R.string.editor_tool_eraser,
            selected = current == InkTool.ERASER,
            menuOpen = false,
            hasPanel = false,
            onClick = { onTap(InkTool.ERASER) },
            menu = {},
        )
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    labelRes: Int,
    selected: Boolean,
    menuOpen: Boolean,
    hasPanel: Boolean,
    onClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val container = when {
        menuOpen -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val content = when {
        menuOpen -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 这个 Box 就是弹层的锚点：Popup 在它的坐标系里定位，
    // 所以菜单会正好浮在这一个按钮的上方，而不是整条工具栏的上方。
    Box {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.background(container, CircleShape),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(labelRes),
                    tint = content,
                )
            }
            // 只在选中的工具下方显示展开提示。
            // 三个按钮都常显小三角的话，反而看不出哪个真的能展开。
            Box(
                modifier = Modifier.height(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selected && hasPanel) {
                    Icon(
                        imageVector = AppIcons.ChevronUp,
                        contentDescription = stringResource(R.string.editor_open_panel),
                        tint = content,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (menuOpen) menu()
    }
}

/**
 * 状态行。
 *
 * 这一行现在看起来像调试信息，但它有明确用途：Step 3 要标定压感曲线，
 * 而标定必须看着真实数据调（这支笔的 ABS_PRESSURE 是 0~4095，
 * 实际书写大概落在什么区间、系统给的采样率够不够，都要看得见）。
 * Step 6 做正式工具栏时会把它收进一个"笔迹信息"开关里。
 */
@Composable
private fun statusLine(stats: StrokeStats?, tool: InkTool, probe: String?): String {
    val mode = stringResource(R.string.editor_stylus_only)
    val hint = if (tool == InkTool.ERASER) {
        stringResource(R.string.editor_eraser_status)
    } else {
        stringResource(R.string.editor_side_button_hint)
    }
    // 诊断信息拼在末尾：它是排查侧键这类设备差异的唯一手段
    val suffix = if (probe == null) "" else " · $probe"

    if (tool == InkTool.ERASER || stats == null) return "$mode · $hint$suffix"

    val pressureRange = String.format(Locale.US, "%.2f-%.2f", stats.pressureMin, stats.pressureMax)
    val widthRange = String.format(Locale.US, "%.1f-%.1f", stats.widthMinPx, stats.widthMaxPx)
    val tool = stringResource(toolLabelRes(stats.toolType))
    val ratio = String.format(Locale.US, "%.1f", stats.widthRatio)
    return mode + " · " + stringResource(
        R.string.editor_stroke_stats,
        stats.pointCount,
        pressureRange,
        widthRange,
    ) + " · $tool ×$ratio"
}

private fun toolLabelRes(toolType: Int): Int = when (toolType) {
    MotionEvent.TOOL_TYPE_STYLUS -> R.string.tool_stylus
    MotionEvent.TOOL_TYPE_ERASER -> R.string.tool_eraser
    MotionEvent.TOOL_TYPE_FINGER -> R.string.tool_finger
    MotionEvent.TOOL_TYPE_MOUSE -> R.string.tool_mouse
    else -> R.string.tool_unknown
}
