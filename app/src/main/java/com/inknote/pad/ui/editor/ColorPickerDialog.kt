package com.inknote.pad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inknote.pad.R
import com.inknote.pad.domain.ColorMath
import kotlin.math.roundToInt

/** 状态条高度。够粗才点得住，也是它同时承担"预览"和"拖动"两个职责的前提 */
private val BAR_HEIGHT = 38.dp

/** 滑块半径。双色描边的圆环，压在任何颜色上都看得见 */
private val THUMB_RADIUS = 11.dp

/**
 * 自定义颜色。
 *
 * ============================ 三条合一 ============================
 * 色相 / 饱和度 / 明度各是一条**既能预览又能拖动**的渐变条，
 * 而不是"上面一条渐变、下面一个滑块"。这样：
 *  · 滑块就压在那个颜色上，看着手指底下是什么色就是什么色
 *  · 少掉一整套滑块的高度，对话框矮了近 60dp
 *  · 横向分辨率完全不变（精度只取决于条有多长，和滑块在哪无关）
 *
 * ============================ 数值栏可以就地输入 ============================
 * "色相 142°" 那栏点一下变成输入框，直接打数字。给它们加了与对话框背景
 * 分离的底色 —— 底色本身就是"这里可以点"的提示，不需要额外的按钮或说明文字。
 *
 * 输入范围按各条限制：色相 0~360，饱和度和明度 0~100，超出直接夹取。
 *
 * 十六进制输入保留在下面：数值栏适合"微调两三度"，十六进制适合"我就要这个值"。
 */
@Composable
fun ColorPickerDialog(
    initialArgb: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(initialArgb) { ColorMath.hsvOfArgb(initialArgb) }

    var hue by remember(initialArgb) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initialArgb) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(initialArgb) { mutableFloatStateOf(initialHsv[2]) }

    var hexText by remember(initialArgb) { mutableStateOf(ColorMath.formatHex(initialArgb)) }

    val currentArgb = ColorMath.argbOfHsv(hue, saturation, brightness)

    // 拖动状态条 → 同步十六进制文本。
    // 反过来（输入十六进制）**不重写文本** —— 用户还在打字，
    // 任何"帮你格式化"都会让光标乱跳、输入被打断。
    fun syncHexFromBars() {
        hexText = ColorMath.formatHex(ColorMath.argbOfHsv(hue, saturation, brightness))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_custom_title)) },
        text = {
            Column(modifier = Modifier.width(360.dp)) {
                // ---- 预览 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 72.dp, height = 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(currentArgb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = ColorMath.formatHex(currentArgb),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))

                // ---- 色相 ----
                HsvBar(
                    label = stringResource(R.string.color_hue),
                    suffix = "°",
                    value = hue,
                    valueRange = 0f..360f,
                    displayMultiplier = 1f,
                    displayMax = 360,
                    gradientAt = { t -> ColorMath.argbOfHsv(t * 360f, saturation, brightness) },
                    onValueChange = {
                        hue = it
                        syncHexFromBars()
                    },
                )

                // ---- 饱和度 ----
                HsvBar(
                    label = stringResource(R.string.color_saturation),
                    suffix = "%",
                    value = saturation,
                    valueRange = 0f..1f,
                    displayMultiplier = 100f,
                    displayMax = 100,
                    gradientAt = { t -> ColorMath.argbOfHsv(hue, t, brightness) },
                    onValueChange = {
                        saturation = it
                        syncHexFromBars()
                    },
                )

                // ---- 明度 ----
                HsvBar(
                    label = stringResource(R.string.color_brightness),
                    suffix = "%",
                    value = brightness,
                    valueRange = 0f..1f,
                    displayMultiplier = 100f,
                    displayMax = 100,
                    gradientAt = { t -> ColorMath.argbOfHsv(hue, saturation, t) },
                    onValueChange = {
                        brightness = it
                        syncHexFromBars()
                    },
                )

                Spacer(Modifier.height(12.dp))

                // ---- 十六进制 ----
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        // 只在合法时同步。解析不了就什么都不做 ——
                        // 用户可能只是还没打完，这时把状态条重置成黑色是最恼人的行为。
                        ColorMath.parseHex(text)?.let { argb ->
                            val hsv = ColorMath.hsvOfArgb(argb)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.color_hex)) },
                    placeholder = { Text("#RRGGBB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentArgb) }) {
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

/**
 * 一条"标签 + 可输入数值 + 可拖动渐变条"。
 */
@Composable
private fun HsvBar(
    label: String,
    suffix: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    /** 内部值 → 显示值的倍数（饱和度/明度内部是 0~1，显示成 0~100） */
    displayMultiplier: Float,
    /** 输入上限，用于夹取 */
    displayMax: Int,
    gradientAt: (Float) -> Int,
    onValueChange: (Float) -> Unit,
) {
    val displayValue = (value * displayMultiplier).roundToInt()

    // 数值栏是否处于编辑态。**刻意提升到这里**而不是留在 ValueChip 内部：
    // 拖动状态条时要能把它关掉，见下面 GradientBar 的注释。
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            ValueChip(
                displayValue = displayValue,
                suffix = suffix,
                displayMax = displayMax,
                editing = editing,
                onStartEdit = { editing = true },
                onFinishEdit = {
                    editing = false
                    focusManager.clearFocus()
                },
                onCommit = { typed ->
                    onValueChange(typed / displayMultiplier)
                },
            )
        }
        Spacer(Modifier.height(6.dp))

        GradientBar(
            value = value,
            valueRange = valueRange,
            gradientAt = gradientAt,
            onValueChange = { dragged ->
                // ============================ 拖动必须退出数值编辑态 ============================
                // 编辑态下数值栏显示的是**用户打的字**（field），不是当前值。
                // 不退出来的话，拖完状态条数值栏还停在刚才那个数字上 ——
                // 看起来就是"拖动滑块数值不变化"。
                //
                // 用户不可能同时在打字和拖动，而手指已经在条上了，意图很明确：
                // 拖动应该赢。退出之后数值栏重新显示真实值，拖动过程中就会实时跟着走。
                if (editing) {
                    editing = false
                    // 顺手收起键盘：编辑态已经结束，键盘留着只会挡住下面的十六进制输入框
                    focusManager.clearFocus()
                }
                onValueChange(dragged)
            },
        )
    }
}

/**
 * 数值栏：平时显示数字，点一下就地变成输入框。
 *
 * 底色用 [MaterialTheme.colorScheme.surfaceVariant] —— 和对话框背景分离，
 * **底色本身就是"这里可以点"的提示**，不需要额外的按钮或说明文字。
 */
@Composable
private fun ValueChip(
    displayValue: Int,
    suffix: String,
    displayMax: Int,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onFinishEdit: () -> Unit,
    onCommit: (Int) -> Unit,
) {
    // 用 TextFieldValue 而不是裸 String：需要控制**选区**。
    // 裸 String 版的光标默认停在开头，用户输入的数字会被插到前面 ——
    // 想把 264 改成 30，会得到 "30264"。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    val shape = RoundedCornerShape(8.dp)
    val chipColor = MaterialTheme.colorScheme.surfaceVariant

    // 进入编辑态：填入当前值、**全选**、并聚焦。
    // 全选是因为把 264 改成 30 时，直接打 "30" 就完事；
    // 只放光标的话要先退三格，而三位数的栏位里"整条重写"是绝对主流的意图。
    //
    // 退出编辑态时清空 field：下次进来必须从当前值开始，
    // 而不是接着上次没打完的半截数字。
    LaunchedEffect(editing) {
        if (editing) {
            val initial = displayValue.toString()
            field = TextFieldValue(initial, TextRange(0, initial.length))
            focusRequester.requestFocus()
        } else {
            field = TextFieldValue("")
        }
    }

    Row(
        modifier = Modifier
            .clip(shape)
            .background(chipColor)
            .clickable(enabled = !editing) { onStartEdit() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            BasicTextField(
                value = field,
                onValueChange = { newValue ->
                    // 只收数字：这一栏的语义就是数值，混进别的字符没有意义
                    val digits = newValue.text.filter { it.isDigit() }.take(3)
                    field = if (digits == newValue.text) {
                        newValue
                    } else {
                        // 过滤之后文本变了，光标必须跟着挪到末尾，
                        // 否则选区会落到越界位置
                        TextFieldValue(digits, TextRange(digits.length))
                    }
                    // ============================ 边打边应用 ============================
                    // 不能只在「Done」时提交：Compose 里点别处**不会**让输入框失焦，
                    // 所以用户打完数字直接点「确定」时，那个值会被丢掉 ——
                    // 而"打完就点确定"恰恰是最自然的操作顺序。
                    //
                    // 解析不出数字时什么都不做（删光了就保持原值），
                    // 否则用户删到一半颜色会突然变成黑。
                    digits.toIntOrNull()?.let { onCommit(it.coerceIn(0, displayMax)) }
                },
                modifier = Modifier
                    .width(34.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                    textAlign = TextAlign.End,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                // Done 只负责退出编辑态 —— 值已经在打字过程中应用过了
                keyboardActions = KeyboardActions(onDone = { onFinishEdit() }),
            )
        } else {
            Text(
                text = displayValue.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(34.dp),
            )
        }
        Text(
            text = suffix,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 可拖动的渐变条。
 *
 * ============================ 为什么不用 Material 的 Slider ============================
 * `Slider` 的 `track` 参数在 Material3 的几个版本之间改过签名
 * （`SliderPositions` → `SliderState`），用它就要绑死版本，升级时会突然编译不过。
 * 自己画只有几十行，没有版本风险，滑块的形状和描边也完全可控。
 *
 * ============================ 为什么渐变条要左右内缩 ============================
 * 如果渐变铺满整条、滑块也走到两端，滑块在 0 和 100% 处会被画布边界**裁掉一半**。
 * 所以渐变在左右各内缩一个滑块半径，滑块的行进范围正好是那条渐变 ——
 * 这样滑块永远完整，而且它压着的颜色和它的位置**严格对应**。
 *
 * ============================ 手势为什么用 awaitEachGesture ============================
 * 用 `detectDragGestures` + `detectTapGestures` 两个 pointerInput 会有互相干扰，
 * 而只用 `detectDragGestures` 的话"轻点一下"不触发（它要等触摸阈值）。
 * 但滑块必须支持"点哪跳哪"。所以直接写按下即取值、拖动持续取值的手势循环，
 * 一个处理器覆盖两种操作。
 */
@Composable
private fun GradientBar(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    gradientAt: (Float) -> Int,
    onValueChange: (Float) -> Unit,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    val thumbRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        THUMB_RADIUS.toPx()
    }

    // 手势回调每次重组都是新的 lambda。用 rememberUpdatedState 拿最新的，
    // 同时又**不把手势监听器重建** —— 否则拖动到一半监听器被换掉，手指还在屏幕上就断了。
    val currentOnChange by rememberUpdatedState(onValueChange)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .semantics {
                // 自己画的条失去了 Material Slider 自带的无障碍支持。
                // 补上进度语义，至少让 TalkBack 能读出当前值 ——
                // "用无障碍手势逐格调节"拿不回来，但读得出值比完全无声好得多。
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = valueRange,
                    steps = 0,
                )
            }
            .pointerInput(valueRange) {
                val width = size.width.toFloat()
                val usable = width - thumbRadiusPx * 2f
                if (usable <= 0f) return@pointerInput

                fun report(x: Float) {
                    val t = ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
                    currentOnChange(valueRange.start + t * span)
                }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    report(down.position.x)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        report(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        val h = size.height
        val w = size.width
        val radius = thumbRadiusPx

        // 渐变：左右各内缩一个滑块半径
        drawRoundRect(
            brush = Brush.horizontalGradient(
                List(25) { i -> Color(gradientAt(i / 24f)) },
                startX = radius,
                endX = w - radius,
            ),
            topLeft = Offset(radius, 0f),
            size = Size(w - radius * 2f, h),
            cornerRadius = CornerRadius(h / 2f),
        )
        // 外轮廓：浅色末端（明度条的纯黑端、饱和度条的灰端）需要一条边才看得出范围
        drawRoundRect(
            color = Color(0x33000000),
            topLeft = Offset(radius, 0f),
            size = Size(w - radius * 2f, h),
            cornerRadius = CornerRadius(h / 2f),
            style = Stroke(width = 1.dp.toPx()),
        )

        // 滑块：白心 + 黑边。无论压在什么颜色上，总有一圈是可见的
        val cx = radius + fraction * (w - radius * 2f)
        val cy = h / 2f
        drawCircle(color = Color.White, radius = radius, center = Offset(cx, cy))
        drawCircle(
            color = Color(0xCC000000),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}
