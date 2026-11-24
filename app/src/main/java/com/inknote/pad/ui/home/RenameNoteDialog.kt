package com.inknote.pad.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.inknote.pad.R
import com.inknote.pad.ui.icons.AppIcons

/**
 * 改名对话框。
 *
 * ============================ 两个细节 ============================
 * 1. **进入时自动聚焦并全选**。用户点"改名"就是想改，不该还要再点一下输入框；
 *    全选是因为多数情况是"整条重写"而不是"在中间插字"。
 *    全选用 [TextFieldValue] 的 selection 而不是先设文本再 setSelection ——
 *    后者会和 Compose 的文本状态打架，光标位置经常不对。
 *
 * 2. **回车直接确认**。输入法是 ImeAction.Done，按完成键就提交，
 *    不用再去点一下"确定"。
 *
 * 标题允许为空：空标题在界面上显示为「未命名笔记」，
 * 这是刻意的 —— 不把"未命名"这三个字写死进数据，以后要改文案不用迁移数据。
 */
@Composable
fun RenameNoteDialog(
    initialTitle: String,
    fallbackTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var field by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialTitle,
                // 空标题时全选没有意义（光标在 0 就行）
                selection = TextRange(0, initialTitle.length),
            ),
        )
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_note_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text(fallbackTitle) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { onConfirm(field.text.trim()) },
                    ),
                    // ============================ 为什么确认键要放在输入框里 ============================
                    // 软键盘会盖住 AlertDialog 底部的按钮 —— 用户在平板上输入完，
                    // 必须先收起键盘才能点到「确定」。这是实测发现的：
                    // 平板上键盘占了下半屏，而对话框的按钮正好在那一带。
                    //
                    // 输入框本身在键盘上方，把确认放在它的尾部就永远点得到。
                    // 底部的「确定」保留不动：物理键盘 / 外接键盘场景下它仍然是对的，
                    // 而且两个入口做同一件事不会让人困惑。
                    trailingIcon = {
                        IconButton(onClick = { onConfirm(field.text.trim()) }) {
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
                    text = stringResource(R.string.rename_note_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(field.text.trim()) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
