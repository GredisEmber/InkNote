package com.inknote.pad.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.inknote.pad.R
import com.inknote.pad.data.AppPreferencesProvider
import com.inknote.pad.domain.PageTurnMode
import com.inknote.pad.ui.icons.AppIcons
import androidx.compose.runtime.collectAsState

/**
 * 设置。
 *
 * ============================ 为什么这些放设置、那些放工具栏 ============================
 * 判据是**"改它的时候你在不在写字"**：
 *
 *  · 笔色、笔宽、工具、笔迹风格 —— 写字时会反复改 → **工具栏**
 *  · 翻页方向、掌拒、诊断开关 —— 定下来之后几个月不动 → **设置**
 *
 * 翻页方向尤其典型：它是阅读习惯，不是每篇笔记都要换的东西。
 * 按笔记存会让"新建笔记"多一个每次都要面对的选择，而那个选择 99% 的情况是一样的。
 *
 * 写成"跟着 StateFlow 走"而不是直接读 prefs 的属性：
 * 设置项改完要立刻反映在界面上，而 prefs 的 getter 不会触发重组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppPreferencesProvider.prefs

    val pageTurnMode by prefs.pageTurnMode.collectAsState()
    val stylusOnly by prefs.stylusOnly.collectAsState()
    val seamlessSnap by prefs.seamlessSnap.collectAsState()
    val showStrokeInfo by prefs.showStrokeInfo.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = AppIcons.Back,
                            contentDescription = stringResource(R.string.editor_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_section_turning))

            // 整组用 selectableGroup：无障碍服务会把它们读成一组单选项，
            // 而不是三个互不相干的按钮
            Column(modifier = Modifier.selectableGroup()) {
                RadioRow(
                    title = stringResource(R.string.settings_turn_horizontal),
                    description = stringResource(R.string.settings_turn_horizontal_desc),
                    selected = pageTurnMode == PageTurnMode.HORIZONTAL,
                    onSelect = { prefs.setPageTurnMode(PageTurnMode.HORIZONTAL) },
                )
                RadioRow(
                    title = stringResource(R.string.settings_turn_vertical),
                    description = stringResource(R.string.settings_turn_vertical_desc),
                    selected = pageTurnMode == PageTurnMode.VERTICAL,
                    onSelect = { prefs.setPageTurnMode(PageTurnMode.VERTICAL) },
                )
            }

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_section_writing))
            SwitchRow(
                title = stringResource(R.string.settings_snap_to_page),
                description = stringResource(R.string.settings_snap_to_page_desc),
                checked = seamlessSnap,
                onCheckedChange = { prefs.setSeamlessSnap(it) },
            )
            SwitchRow(
                title = stringResource(R.string.settings_stylus_only),
                description = stringResource(R.string.settings_stylus_only_desc),
                checked = stylusOnly,
                onCheckedChange = { prefs.setStylusOnly(it) },
            )

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_section_diagnostics))
            SwitchRow(
                title = stringResource(R.string.settings_show_stroke_info),
                description = stringResource(R.string.settings_show_stroke_info_desc),
                checked = showStrokeInfo,
                onCheckedChange = { prefs.setShowStrokeInfo(it) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun RadioRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        SettingTexts(title = title, description = description)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingTexts(title = title, description = description, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        // onClick = null：整行已经可点。Switch 自己再处理一次的话，
        // 一次点击会触发两遍（开关一次、行一次），值原地翻回来。
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingTexts(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
