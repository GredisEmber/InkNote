package com.inknote.pad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.inknote.pad.R
import com.inknote.pad.data.AppPreferences
import com.inknote.pad.domain.BrushParams
import com.inknote.pad.domain.InkPalette
import com.inknote.pad.domain.InkWidthSteps
import com.inknote.pad.ink.BrushTestPadView
import com.inknote.pad.ink.InkTool
import com.inknote.pad.ui.common.WidthDot
import com.inknote.pad.ui.icons.AppIcons
import kotlin.math.roundToInt

/**
 * 笔的设置：二级菜单 + 三级窗口。
 *
 * ============================ 两级为什么长得不一样 ============================
 * **二级是"选择去哪"，三级是"在这里改"。**
 *
 * 二级做成锚定在工具按钮上方的小菜单：它只有两三个词条，功能是导航，
 * 所以不该占掉画布 —— 浮在画布上、点一下就走，是最轻的形态。
 *
 * 三级做成屏幕正中的窗口：它要放四支滑块加试写区，内容多、停留久，
 * 而且用户此刻的注意力完全在"调参数"上，遮住画布没有关系。
 * 居中还有一个好处 —— 不用管它锚在哪里，横竖屏都不会跑偏。
 */

/** 三级窗口显示哪一页 */
enum class PanelPage {
    /** 颜色 */
    COLOR,

    /** 钢笔的四支笔刷滑块 */
    BRUSH,

    /** 荧光笔的粗细档位 */
    HL_WIDTH,

    /** 荧光笔的透明度 */
    HL_OPACITY,
}

/** 三级窗口的状态。[tool] 只会是钢笔或荧光笔 */
data class PanelState(val tool: InkTool, val page: PanelPage)

private fun toolTitleRes(tool: InkTool): Int = when (tool) {
    InkTool.HIGHLIGHTER -> R.string.panel_highlighter_title
    else -> R.string.panel_pen_title
}

// ======================================================================
// 二级：锚定在工具按钮上方的小菜单
// ======================================================================

/**
 * 把弹层放在锚点**正上方**，并且不让它跑出屏幕。
 *
 * 为什么自己写 [PopupPositionProvider] 而不是用 `DropdownMenu`：
 * DropdownMenu 只会向下弹（它假设锚点上方空间不足）。工具按钮在屏幕最底部，
 * 向下弹会直接出界。自己算位置是最直接的解法。
 */
private class AboveAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // 水平向左对齐锚点，但不越出窗口左右边
        val x = anchorBounds.left.coerceIn(
            minimumValue = 0,
            maximumValue = (windowSize.width - popupContentSize.width).coerceAtLeast(0),
        )
        // 垂直放在锚点上方
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
fun BrushMenuPopup(
    tool: InkTool,
    onDismiss: () -> Unit,
    onOpenPage: (PanelPage) -> Unit,
    penColorSlot: Int,
    penCustomColor: Int?,
    hlColorSlot: Int,
    hlCustomColor: Int?,
    params: BrushParams,
    hlWidthIndex: Int,
    hlOpacity: Float,
) {
    val isHighlighter = tool == InkTool.HIGHLIGHTER
    val colorSlot = if (isHighlighter) hlColorSlot else penColorSlot
    val customColor = if (isHighlighter) hlCustomColor else penCustomColor
    val gapPx = with(LocalDensity.current) { 10.dp.roundToPx() }

    Popup(
        popupPositionProvider = AboveAnchorPositionProvider(gapPx),
        onDismissRequest = onDismiss,
        // focusable 才能让系统返回键关掉它，也才能点外面关闭
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier
                    // 窄：它只有两三个词条，宽了就不像菜单而像面板
                    .width(210.dp)
                    .padding(vertical = 6.dp),
            ) {
                MenuRow(
                    title = stringResource(R.string.panel_color),
                    // 行上带当前值：多数时候"看一眼"就够了，不用点进去
                    valueText = colorName(colorSlot),
                    leading = { ColorDot(InkPalette.colorForSlot(colorSlot, customColor)) },
                    onClick = { onOpenPage(PanelPage.COLOR) },
                )
                MenuRow(
                    title = stringResource(R.string.panel_width),
                    valueText = if (isHighlighter) {
                        widthStepName(hlWidthIndex)
                    } else {
                        percent(params.baseWidth)
                    },
                    leading = {
                        WidthDot(
                            ratio = if (isHighlighter) {
                                InkWidthSteps.ratioAt(hlWidthIndex)
                            } else {
                                params.baseWidthRatio
                            },
                            containerSize = 20.dp,
                        )
                    },
                    onClick = {
                        onOpenPage(if (isHighlighter) PanelPage.HL_WIDTH else PanelPage.BRUSH)
                    },
                )
                if (isHighlighter) {
                    MenuRow(
                        title = stringResource(R.string.panel_opacity),
                        valueText = percent(normalizeOpacity(hlOpacity)),
                        onClick = { onOpenPage(PanelPage.HL_OPACITY) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    valueText: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            leading?.invoke()
        }
        Spacer(Modifier.width(12.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ======================================================================
// 三级：屏幕正中的窗口
// ======================================================================

@Composable
fun BrushDetailDialog(
    state: PanelState,
    onDismiss: () -> Unit,
    penColorSlot: Int,
    penCustomColor: Int?,
    onPenColorSlot: (Int) -> Unit,
    onEditPenCustom: () -> Unit,
    hlColorSlot: Int,
    hlCustomColor: Int?,
    onHlColorSlot: (Int) -> Unit,
    onEditHlCustom: () -> Unit,
    params: BrushParams,
    onParamsChange: (BrushParams) -> Unit,
    hlWidthIndex: Int,
    onHlWidthChange: (Int) -> Unit,
    hlOpacity: Float,
    onHlOpacityChange: (Float) -> Unit,
) {
    val isHighlighter = state.tool == InkTool.HIGHLIGHTER
    val colorSlot = if (isHighlighter) hlColorSlot else penColorSlot
    val customColor = if (isHighlighter) hlCustomColor else penCustomColor

    Dialog(
        onDismissRequest = onDismiss,
        // usePlatformDefaultWidth = false 才能自己定宽度 ——
        // 默认宽度在平板上会窄得没法放四支滑块
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.width(if (state.page == PanelPage.BRUSH) 620.dp else 400.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // ---- 标题栏 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            when (state.page) {
                                PanelPage.COLOR -> R.string.panel_color
                                PanelPage.BRUSH -> R.string.panel_width
                                PanelPage.HL_WIDTH -> R.string.panel_width
                                PanelPage.HL_OPACITY -> R.string.panel_opacity
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(toolTitleRes(state.tool)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.panel_done))
                    }
                }
                Spacer(Modifier.height(12.dp))

                when (state.page) {
                    // ---- 颜色 ----
                    PanelPage.COLOR -> ColorRow(
                        currentSlot = colorSlot,
                        customColor = customColor,
                        onSelectSlot = if (isHighlighter) onHlColorSlot else onPenColorSlot,
                        onEditCustom = if (isHighlighter) onEditHlCustom else onEditPenCustom,
                    )

                    // ---- 钢笔笔刷：滑块 + 试写区 ----
                    PanelPage.BRUSH -> Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            ParamSlider(
                                label = stringResource(R.string.brush_base_width),
                                hint = stringResource(R.string.brush_base_width_hint),
                                value = params.baseWidth,
                                onChange = { onParamsChange(params.copy(baseWidth = it)) },
                            )
                            ParamSlider(
                                label = stringResource(R.string.brush_sensitivity),
                                hint = stringResource(R.string.brush_sensitivity_hint),
                                value = params.sensitivity,
                                onChange = { onParamsChange(params.copy(sensitivity = it)) },
                            )
                            ParamSlider(
                                label = stringResource(R.string.brush_strength),
                                hint = stringResource(R.string.brush_strength_hint),
                                value = params.strength,
                                onChange = { onParamsChange(params.copy(strength = it)) },
                            )
                            ParamSlider(
                                label = stringResource(R.string.brush_smoothness),
                                hint = stringResource(R.string.brush_smoothness_hint),
                                value = params.smoothness,
                                onChange = { onParamsChange(params.copy(smoothness = it)) },
                            )
                        }
                        Spacer(Modifier.width(20.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            // 试写区**只给钢笔**。荧光笔是等宽的、也没有笔锋，
                            // 试写区能传达的信息几乎为零，留着反而让窗口变胖
                            TestPad(
                                params = params,
                                colorArgb = InkPalette.colorForSlot(colorSlot, customColor),
                                isHighlighter = false,
                            )
                        }
                    }

                    // ---- 荧光笔粗细 ----
                    PanelPage.HL_WIDTH -> {
                        SectionLabel(stringResource(R.string.panel_width))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InkWidthSteps.RATIOS.forEachIndexed { index, ratio ->
                                WidthChoice(
                                    ratio = ratio,
                                    selected = index == hlWidthIndex,
                                    onClick = { onHlWidthChange(index) },
                                )
                            }
                        }
                    }

                    // ---- 荧光笔透明度 ----
                    PanelPage.HL_OPACITY -> ParamSlider(
                        label = stringResource(R.string.panel_opacity),
                        hint = stringResource(R.string.panel_opacity_hint),
                        value = normalizeOpacity(hlOpacity),
                        onChange = { onHlOpacityChange(denormalizeOpacity(it)) },
                    )
                }
            }
        }
    }
}

// ======================================================================
// 控件
// ======================================================================

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun ColorRow(
    currentSlot: Int,
    customColor: Int?,
    onSelectSlot: (Int) -> Unit,
    onEditCustom: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkPalette.PRESETS.forEachIndexed { index, argb ->
            ColorChoice(
                argb = argb,
                selected = index == currentSlot,
                onClick = { onSelectSlot(index) },
            )
        }
        // 自定义槽位：点它有颜色就选用、没颜色就打开取色器
        ColorChoice(
            argb = customColor,
            selected = currentSlot == InkPalette.CUSTOM_SLOT,
            onClick = {
                if (customColor != null) onSelectSlot(InkPalette.CUSTOM_SLOT) else onEditCustom()
            },
        )
        TextButton(onClick = onEditCustom) {
            Text(stringResource(R.string.color_adjust))
        }
    }
}

@Composable
private fun ColorChoice(argb: Int?, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (argb != null) Color(argb) else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = ring,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (argb == null) {
            Icon(
                imageVector = AppIcons.Add,
                contentDescription = stringResource(R.string.color_custom),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ColorDot(argb: Int) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun WidthChoice(ratio: Float, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        WidthDot(ratio = ratio, containerSize = 26.dp)
    }
}

/**
 * 带说明文字的滑块。
 *
 * 说明文字是必要的：这四个参数名字相近，"灵敏度"和"强度"光看名字
 * 分不出谁是谁。一句话说明比让人试出来快得多。
 */
@Composable
private fun ParamSlider(
    label: String,
    hint: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = percent(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            modifier = Modifier.height(32.dp),
        )
    }
}

/**
 * 试写区（只给钢笔）。
 *
 * 用 AndroidView 而不是 Compose 的 Canvas + pointerInput：
 * 压感要拿到原始 MotionEvent 的 pressure 和 historical 采样，
 * 这条路在自定义 View 里是现成的，走 Compose 的指针事件反而要额外做适配。
 */
@Composable
private fun TestPad(
    params: BrushParams,
    colorArgb: Int,
    isHighlighter: Boolean,
    modifier: Modifier = Modifier,
) {
    var padView by remember { mutableStateOf<BrushTestPadView?>(null) }
    var empty by remember { mutableStateOf(true) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(stringResource(R.string.panel_test_pad))
            TextButton(
                onClick = {
                    padView?.clear()
                    empty = true
                },
                enabled = !empty,
            ) {
                Text(stringResource(R.string.panel_clear_test))
            }
        }
        AndroidView(
            factory = { context ->
                BrushTestPadView(context).also { padView = it }
            },
            update = { view ->
                // 每次重组都把当前参数推下去：拖动滑块时下一笔就是新参数
                view.brushParams = params
                view.colorArgb = colorArgb
                view.isHighlighter = isHighlighter
                if (!view.isEmpty) empty = false
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
        )
    }
}

// ======================================================================
// 显示用的换算
// ======================================================================

private fun percent(v: Float): String = "${(v.coerceIn(0f, 1f) * 100).roundToInt()}%"

private fun colorName(slot: Int): String = when (slot) {
    0 -> "墨黑"
    1 -> "红"
    2 -> "蓝"
    else -> "自定义"
}

private fun widthStepName(index: Int): String = when (index) {
    0 -> "极细"
    1 -> "细"
    2 -> "中"
    3 -> "粗"
    else -> "极粗"
}

// 透明度归一化。上下限从 AppPreferences 取而不是在这里再写一遍：
// 写两遍的话，以后改了限制范围，归一化就会和真实范围错位 ——
// 表现为"滑块拖到头却到不了极限值"，而且很难查。
private fun normalizeOpacity(alpha: Float): Float {
    val lo = AppPreferences.MIN_HIGHLIGHTER_ALPHA
    val hi = AppPreferences.MAX_HIGHLIGHTER_ALPHA
    return ((alpha - lo) / (hi - lo)).coerceIn(0f, 1f)
}

private fun denormalizeOpacity(t: Float): Float {
    val lo = AppPreferences.MIN_HIGHLIGHTER_ALPHA
    val hi = AppPreferences.MAX_HIGHLIGHTER_ALPHA
    return lo + (hi - lo) * t.coerceIn(0f, 1f)
}
