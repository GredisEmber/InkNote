package com.inknote.pad.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.inknote.pad.R
import com.inknote.pad.domain.NoteSummary
import com.inknote.pad.domain.PaperSize
import com.inknote.pad.domain.PaperSpec
import com.inknote.pad.ui.common.PageThumbnail
import com.inknote.pad.ui.icons.AppIcons
import com.inknote.pad.ui.theme.InkNoteTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 笔记列表页。
 *
 * 刻意做成「无状态」的：notes 和所有动作都从外面传进来。
 * 这样它可以在 @Preview 里直接渲染，也不需要为了测试去构造 ViewModel。
 * 状态的持有者是 [HomeViewModel]，接线在 InkNavHost 里完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    notes: List<NoteSummary>,
    onCreateNote: (PaperSpec) -> Unit,
    onOpenNote: (Long) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onRenameNote: (Long, String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待确认删除的笔记。非 null 时弹确认框 —— 笔记删了没法从回收站找回，
    // 所以这里宁可多一次点击。
    var pendingDelete by remember { mutableStateOf<NoteSummary?>(null) }

    // 待改名的笔记
    var pendingRename by remember { mutableStateOf<NoteSummary?>(null) }

    // 纸型选择器。点「新建笔记」不直接建，而是先让用户选纸 ——
    // 纸型创建后不能改，所以这一步必须发生在创建之前。
    var showPaperPicker by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = AppIcons.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showPaperPicker = true },
                icon = { Icon(imageVector = AppIcons.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new_note)) },
            )
        },
    ) { innerPadding ->
        if (showPaperPicker) {
            PaperPickerDialog(
                onDismiss = { showPaperPicker = false },
                onPick = { paper ->
                    showPaperPicker = false
                    onCreateNote(paper)
                },
            )
        }
        if (notes.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyVerticalGrid(
                // Adaptive：Y700 竖屏 1~2 列、横屏 3~4 列，自动适配，不用手写断点
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 104.dp, // 给 FAB 留出空间，避免最后一行被挡住
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items = notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onOpenNote(note.id) },
                        onRename = { pendingRename = note },
                        onDelete = { pendingDelete = note },
                    )
                }
            }
        }
    }

    pendingRename?.let { target ->
        RenameNoteDialog(
            initialTitle = target.title,
            fallbackTitle = stringResource(R.string.note_untitled),
            onDismiss = { pendingRename = null },
            onConfirm = { newTitle ->
                onRenameNote(target.id, newTitle)
                pendingRename = null
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_note_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_note_message,
                        target.title.ifBlank { stringResource(R.string.note_untitled) },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteNote(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.home_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoteCard(
    note: NoteSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f) // 接近 A4 的比例，在平板上看着像一页纸
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageThumbnail(
                noteId = note.id,
                pageId = note.coverPageId,
                // updatedAt 在每次写盘时和缩略图一起更新，用它当缓存版本号，
                // 否则写完返回首页会一直显示旧缩略图
                contentVersion = note.updatedAtEpochMillis,
                tint = Color(note.placeholderTintArgb),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = note.title.ifBlank { stringResource(R.string.note_untitled) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatTimestamp(note.updatedAtEpochMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = AppIcons.Edit,
                        contentDescription = stringResource(R.string.action_rename),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// java.time 而不是 SimpleDateFormat：minSdk 29 已经原生支持 java.time，
// 而且 DateTimeFormatter 是线程安全的，SimpleDateFormat 不是。
private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIMESTAMP_FORMATTER)

/** @Preview 用的样例笔记。把新增字段的默认值集中在一处，加字段时只改这里 */
private fun sampleNote(
    id: Long,
    title: String,
    ageMillis: Long,
    pageCount: Int,
    tint: Int,
) = NoteSummary(
    id = id,
    title = title,
    updatedAtEpochMillis = System.currentTimeMillis() - ageMillis,
    pageCount = pageCount,
    placeholderTintArgb = tint,
    paperSize = PaperSize.A4,
    paperLandscape = false,
    paperWidthPx = 1240f,
    paperHeightPx = 1754f,
)

@Preview(showBackground = true, widthDp = 820, heightDp = 520, name = "首页-有笔记")
@Composable
private fun HomeScreenPreview() {
    InkNoteTheme {
        HomeScreen(
            notes = listOf(
                sampleNote(1L, "会议记录", 0L, 3, 0xFFF3F6FB.toInt()),
                sampleNote(2L, "", 86_400_000L, 1, 0xFFFBF6EC.toInt()),
                sampleNote(3L, "读书笔记", 172_800_000L, 12, 0xFFF2F8F3.toInt()),
            ),
            onCreateNote = {},
            onOpenNote = {},
            onDeleteNote = {},
            onRenameNote = { _, _ -> },
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 820, heightDp = 520, name = "首页-空")
@Composable
private fun HomeScreenEmptyPreview() {
    InkNoteTheme {
        HomeScreen(
            notes = emptyList(),
            onCreateNote = {},
            onOpenNote = {},
            onDeleteNote = {},
            onRenameNote = { _, _ -> },
            onOpenSettings = {},
        )
    }
}
