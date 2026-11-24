package com.inknote.pad.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.inknote.pad.domain.InkWidthSteps

/**
 * 按比例画出笔宽示意点。
 *
 * 直径按"最大档位"归一化：如果直接按 ratio 画像素，各档之间的差别只有 1~2px，
 * 看不太出来。归一化之后极细到极粗有明显的大小梯度。
 *
 * 放在 ui/common 而不是留在编辑器里：笔宽控件现在同时出现在工具栏和
 * 荧光笔面板里，两处必须是同一个形状 —— 复制一份的话，改了一处
 * 另一处就会悄悄变成不一样的大小。
 */
@Composable
fun WidthDot(
    ratio: Float,
    containerSize: Dp,
    modifier: Modifier = Modifier,
) {
    val maxRatio = InkWidthSteps.RATIOS.last().coerceAtLeast(1e-6f)
    // 0.28~1.0 之间插值：最小档也留一点可见面积，否则像个灰尘
    val fraction = 0.28f + 0.72f * (ratio / maxRatio).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(containerSize * fraction)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface),
    )
}
