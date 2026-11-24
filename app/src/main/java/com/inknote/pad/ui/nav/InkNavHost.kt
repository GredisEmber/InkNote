package com.inknote.pad.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inknote.pad.ui.editor.EditorScreen
import com.inknote.pad.ui.home.HomeScreen
import com.inknote.pad.ui.home.HomeViewModel
import com.inknote.pad.ui.settings.SettingsScreen

/**
 * 没有打开任何笔记时的哨兵值。
 * 笔记 id 从 1 开始分配（见 InMemoryInkRepository），所以 -1 不会冲突。
 */
private const val NO_NOTE_ID = -1L

/**
 * 极简导航。
 *
 * 为什么这一步不用 navigation-compose：
 *  1. 当前只有「列表 / 编辑」两个目的地，一个 Long 状态就能表达完；
 *  2. navigation-compose 的当前稳定版本号我无法在本机验证，
 *     而且 Navigation 3 的 API 与 2.x 差别很大，引入等于多押一个赌注；
 *  3. 手写版行为完全可控（返回键、进程重建后恢复都只有几行）。
 *
 * 代价：没有 Navigation 的深链接、转场动画和嵌套图。
 * 等 Step 5 目的地变多（笔记 / 页面管理 / 设置）时再换 ——
 * 到那时只需要替换这一个文件，UI 层不受影响。
 *
 * rememberSaveable 保证「进程被回收后重建」仍然停在原来那篇笔记上。
 */
@Composable
fun InkNavHost(
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = viewModel(),
) {
    val notes by homeViewModel.notes.collectAsState()
    var openNoteId by rememberSaveable { mutableStateOf(NO_NOTE_ID) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 系统返回键：设置页先回列表，编辑页再回列表，最后才退出 App
    BackHandler(enabled = openNoteId != NO_NOTE_ID || showSettings) {
        if (showSettings) showSettings = false else openNoteId = NO_NOTE_ID
    }

    when {
        showSettings -> SettingsScreen(
            onBack = { showSettings = false },
            modifier = modifier,
        )

        openNoteId == NO_NOTE_ID -> HomeScreen(
            notes = notes,
            onCreateNote = { paper -> homeViewModel.createNote(paper) { id -> openNoteId = id } },
            onOpenNote = { noteId -> openNoteId = noteId },
            onDeleteNote = homeViewModel::deleteNote,
            onRenameNote = homeViewModel::renameNote,
            onOpenSettings = { showSettings = true },
            modifier = modifier,
        )

        else -> EditorScreen(
            noteId = openNoteId,
            // 用 id 现查而不是把整个对象存进状态：
            // 这样标题被改名后，编辑页顶部会立刻跟着变。
            // 只传列表快照作为兜底。编辑器会按 id 再查一次库 ——
            // 列表来自 Flow，刚创建的笔记可能还没发射过来，
            // 只用快照判断"存不存在"会把用户刚建好的笔记弹回去。
            noteSnapshot = notes.firstOrNull { it.id == openNoteId },
            onBack = { openNoteId = NO_NOTE_ID },
            modifier = modifier,
        )
    }
}
