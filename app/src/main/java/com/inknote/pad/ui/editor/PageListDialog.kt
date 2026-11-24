package com.inknote.pad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.inknote.pad.R
import com.inknote.pad.domain.PageSummary
import com.inknote.pad.ui.common.PageThumbnail
import com.inknote.pad.ui.icons.AppIcons

/**
 * 页列表：跳页、排序、书签、新建、复制、删除。
 *
 * ============================ 为什么删除/复制/排序都显式传 index ============================
 * 调用方（EditorScreen）的"当前页"是从组合期状态派生的。
 * 如果这里回调一个不带参数的 onDelete，调用方拿到的会是**上一次组合**的当前页 ——
 * 用户点了第 3 页的删除，删掉的可能是第 5 页。这种错误没有任何报错，
 * 只表现为"笔记莫名少了一页"，所以从一开始就把目标页号传出去。
 */
@Composable
fun PageListDialog(
    noteId: Long,
    pages: List<PageSummary>,
    currentIndex: Int,
    tint: Color,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onAddPage: () -> Unit,
    onDuplicateCurrent: () -> Unit,
    onDelete: (Int) -> Unit,
    onMove: (index: Int, delta: Int) -> Unit,
    onBookmark: (index: Int, label: String?) -> Unit,
) {
    // 只看书签。书签数量多了之后，这是"找内容"的主入口。
    var onlyBookmarks by remember { mutableStateOf(false) }

    // 正在编辑标签的页下标。非 null 时弹标签对话框
    var editingLabelAt by remember { mutableStateOf<Int?>(null) }

    val bookmarkCount = pages.count { it.isBookmarked }

    // 过滤后仍然用【原始下标】来回调 —— 排序、删除都作用在真实页序上。
    // 这里如果换成过滤后列表的下标，删掉的就会是另一页。
    val visible: List<Pair<Int, PageSummary>> = remember(pages, onlyBookmarks) {
        pages.withIndex()
            .filter { (_, p) -> !onlyBookmarks || p.isBookmarked }
            .map { it.index to it.value }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.page_list_title, pages.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    // 书签数大于 0 时才给筛选入口 —— 一个永远筛不出东西的开关只会碍事
                    if (bookmarkCount > 0) {
                        FilterChip(
                            selected = onlyBookmarks,
                            onClick = { onlyBookmarks = !onlyBookmarks },
                            label = {
                                Text(stringResource(R.string.page_list_only_bookmarks, bookmarkCount))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.page_list_no_bookmarks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(visible, key = { _, pair -> pair.second.pageId }) { _, pair ->
                            val (index, page) = pair
                            PageRow(
                                noteId = noteId,
                                page = page,
                                displayNumber = index + 1,
                                isCurrent = index == currentIndex,
                                tint = tint,
                                // 过滤状态下不给排序：在"只看书签"的列表里移动，
                                // 用户看不出它会插到哪两页之间，结果不可预期。
                                canReorder = !onlyBookmarks,
                                canDelete = pages.size > 1,
                                onJump = { onJump(index) },
                                onMoveUp = { onMove(index, -1) },
                                onMoveDown = { onMove(index, +1) },
                                onBookmark = { editingLabelAt = index },
                                onDelete = { onDelete(index) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onAddPage) {
                        Text(stringResource(R.string.page_list_add))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDuplicateCurrent) {
                        Text(stringResource(R.string.page_list_duplicate))
                    }
                }
            }
        }
    }

    editingLabelAt?.let { index ->
        val page = pages.getOrNull(index)
        if (page == null) {
            editingLabelAt = null
        } else {
            BookmarkDialog(
                pageNumber = index + 1,
                initialLabel = page.bookmarkLabel.orEmpty(),
                isBookmarked = page.isBookmarked,
                onDismiss = { editingLabelAt = null },
                onSave = { label ->
                    // 空字符串表示"有书签但没标签"，不是"没有书签" ——
                    // 三种状态的编码见 PageEntity.bookmarkLabel 的注释
                    onBookmark(index, label)
                    editingLabelAt = null
                },
                onRemove = {
                    onBookmark(index, null)
                    editingLabelAt = null
                },
            )
        }
    }
}

@Composable
private fun PageRow(
    noteId: Long,
    page: PageSummary,
    displayNumber: Int,
    isCurrent: Boolean,
    tint: Color,
    canReorder: Boolean,
    canDelete: Boolean,
    onJump: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onBookmark: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onJump)
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---- 排序 ----
        CompactIconButton(
            icon = AppIcons.ArrowUp,
            description = stringResource(R.string.page_list_move_up),
            enabled = canReorder,
            onClick = onMoveUp,
        )
        CompactIconButton(
            icon = AppIcons.ArrowDown,
            description = stringResource(R.string.page_list_move_down),
            enabled = canReorder,
            onClick = onMoveDown,
        )

        // ---- 缩略图 ----
        // 有了它，"移动到第几页"才是看得见的操作，而不是盲排。
        // contentVersion 用这一页自己的 updatedAt：只改了一页时，
        // 只有这一页的缩略图会重新解码，其他页继续用缓存。
        PageThumbnail(
            noteId = noteId,
            pageId = page.pageId,
            contentVersion = page.updatedAt,
            tint = tint,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(width = 44.dp, height = 58.dp)
                .clip(RoundedCornerShape(4.dp)),
        )

        // ---- 页号 + 标签 ----
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.page_list_item, displayNumber),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            val label = page.bookmarkLabel
            if (label != null) {
                Text(
                    text = label.ifBlank { stringResource(R.string.bookmark_no_label) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ---- 书签 ----
        CompactIconButton(
            icon = if (page.isBookmarked) AppIcons.BookmarkFilled else AppIcons.Bookmark,
            description = stringResource(R.string.page_list_bookmark),
            enabled = true,
            tint = if (page.isBookmarked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            onClick = onBookmark,
        )

        // ---- 删除 ----
        CompactIconButton(
            icon = AppIcons.Delete,
            description = stringResource(R.string.page_list_delete),
            enabled = canDelete,
            onClick = onDelete,
        )
    }
}

/**
 * 紧凑图标按钮。
 *
 * 标准 IconButton 是 48dp，一行里放四个会把页号挤没。
 * 36dp 仍然满足触控目标下限（Android 建议 48dp，但密集列表里 36dp 是可接受的折中，
 * 而且这是在平板上用手指之外的场景 —— 页列表通常用笔或手指精确点）。
 */
@Composable
private fun CompactIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.32f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 书签标签编辑。
 *
 * 做成一个对话框而不是"点一下切换、长按编辑"：
 * 标签才是这个功能的重点（用户要的是"方便查找内容"），
 * 而一个入口的交互永远比两个入口好猜。
 */
@Composable
private fun BookmarkDialog(
    pageNumber: Int,
    initialLabel: String,
    isBookmarked: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_title, pageNumber)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.bookmark_label_hint)) },
                    trailingIcon = {
                        IconButton(onClick = { onSave(label.trim()) }) {
                            Icon(
                                imageVector = AppIcons.Check,
                                contentDescription = stringResource(R.string.action_confirm),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bookmark_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label.trim()) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            Row {
                if (isBookmarked) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.bookmark_remove))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
