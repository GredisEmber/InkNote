package com.inknote.pad.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = InkBlue,
    onPrimary = Color.White,
    secondary = InkTeal,
    onSecondary = Color.White,
    tertiary = InkAmber,
    onTertiary = Color(0xFF3A2400),
    background = InkSurfaceLight,
    onBackground = InkOnSurfaceLight,
    surface = InkSurfaceLight,
    onSurface = InkOnSurfaceLight,
    surfaceVariant = InkSurfaceVariantLight,
    onSurfaceVariant = InkOnSurfaceVariantLight,
)

private val DarkColors = darkColorScheme(
    primary = InkBlueDark,
    onPrimary = Color(0xFF00315F),
    secondary = InkTealDark,
    onSecondary = Color(0xFF00382F),
    tertiary = InkAmberDark,
    onTertiary = Color(0xFF412D00),
    background = InkSurfaceDark,
    onBackground = InkOnSurfaceDark,
    surface = InkSurfaceDark,
    onSurface = InkOnSurfaceDark,
    surfaceVariant = InkSurfaceVariantDark,
    onSurfaceVariant = InkOnSurfaceVariantDark,
)

/**
 * App 主题。
 *
 * 刻意不接 Material You 动态取色（dynamicColor）：
 * 手写笔记的墨色和纸张颜色应该稳定、可预期，不该随壁纸变化 ——
 * 换个壁纸笔记的配色就变，对笔记类 App 是负分。
 * 个人自用、单设备，也不需要靠动态取色去「融入系统」。
 */
@Composable
fun InkNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = InkTypography,
        content = content,
    )
}
