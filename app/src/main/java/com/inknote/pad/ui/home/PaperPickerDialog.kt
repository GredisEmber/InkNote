package com.inknote.pad.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.inknote.pad.R
import com.inknote.pad.domain.PaperMetrics
import com.inknote.pad.domain.PaperSize
import com.inknote.pad.domain.PaperSpec

/**
 * 纸型选择器。
 *
 * 只在新建笔记时出现一次 —— 选定之后纸型就写进笔记里，不再修改。
 * 所以这里不给"稍后可以改"的暗示，提示语直接把这件事说清楚。
 *
 * 每个选项都画一个**真实比例**的小预览。文字标签（"A4 竖"）对多数人
 * 不能立刻唤起尺寸感，而一个矩形一眼就能看出是宽的还是高的、大概多长。
 */
@Composable
fun PaperPickerDialog(
    onDismiss: () -> Unit,
    onPick: (PaperSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.paper_picker_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.paper_picker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))

                // 每行 4 个。7 个选项排成 4 + 3，最后一行留空不补位
                OPTIONS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { option ->
                            PaperOptionTile(
                                option = option,
                                onClick = {
                                    onPick(
                                        PaperSpec.resolve(
                                            size = option.size,
                                            landscape = option.landscape,
                                            // 「与屏幕相同」在这里还不知道尺寸 ——
                                            // 绘图区大小要等编辑器的画布布局完才知道。
                                            // 先留 0，编辑器首次布局后回填并持久化。
                                            screenWidthPx = 0f,
                                            screenHeightPx = 0f,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PaperOptionTile(
    option: PaperOption,
    onClick: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    // SCREEN 没有固定比例，用当前屏幕自己的比例来预览
    val screenAspect = configuration.screenWidthDp.toFloat() /
        configuration.screenHeightDp.toFloat().coerceAtLeast(1f)

    val aspect = PaperMetrics.aspectRatio(option.size, option.landscape)
        .let { if (it > 0f) it else screenAspect }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        PaperPreview(
            aspect = aspect,
            size = option.size,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 按真实比例画一个小纸片。
 *
 * ============================ 为什么要按 A3 归一化 ============================
 * A5 / A4 / A3 的宽高比是**完全相同**的（都是 1:√2，这是 ISO 216 的定义）。
 * 如果只把每张纸按自己的比例塞进同一个盒子，三张竖版预览会渲染成
 * 一模一样的矩形 —— 预览就白画了，用户只能靠标签区分。
 *
 * 所以以 A3 的长边为基准：A4 是它的 1/√2，A5 是它的 1/2。
 * 这样"大小"这个维度才看得见。"与屏幕相同"没有可比的物理尺寸，
 * 直接按屏幕自身比例顶满盒子。
 */
@Composable
private fun PaperPreview(
    aspect: Float,
    size: PaperSize,
) {
    val boxWidth = 56.dp
    val boxHeight = 68.dp

    val relative = if (size.isScreen) {
        1f
    } else {
        size.longSideMm / PaperSize.A3.longSideMm
    }
    val boxLongSide = if (aspect >= 1f) boxWidth else boxHeight
    val longSide = boxLongSide * relative

    val (previewWidth, previewHeight) = if (aspect >= 1f) {
        longSide to (longSide / aspect)
    } else {
        (longSide * aspect) to longSide
    }

    // 外框始终占满盒子，内层纸片居中 —— 这样无论纸多小，标签都是对齐的
    Box(
        modifier = Modifier.size(boxWidth, boxHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(previewWidth, previewHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
                .border(
                    width = if (size.isScreen) 1.5.dp else 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(3.dp),
                ),
        )
    }
}

private data class PaperOption(
    val size: PaperSize,
    val landscape: Boolean,
    // 显式写 param: 目标：Kotlin 2.x 起不加会在编译期报
    // "annotation is currently applied to the value parameter only" 警告
    @param:StringRes val labelRes: Int,
)

private val OPTIONS = listOf(
    PaperOption(PaperSize.SCREEN, false, R.string.paper_screen),
    PaperOption(PaperSize.A5, false, R.string.paper_a5_portrait),
    PaperOption(PaperSize.A4, false, R.string.paper_a4_portrait),
    PaperOption(PaperSize.A3, false, R.string.paper_a3_portrait),
    PaperOption(PaperSize.A5, true, R.string.paper_a5_landscape),
    PaperOption(PaperSize.A4, true, R.string.paper_a4_landscape),
    PaperOption(PaperSize.A3, true, R.string.paper_a3_landscape),
)
