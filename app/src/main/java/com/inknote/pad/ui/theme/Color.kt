package com.inknote.pad.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// 亮色
// ---------------------------------------------------------------------------
/** 主色：钢笔墨蓝 */
val InkBlue = Color(0xFF1B6EF3)

/** 次色：墨绿 */
val InkTeal = Color(0xFF00897B)

/** 强调色：笔尖黄 */
val InkAmber = Color(0xFFF2A33C)

val InkSurfaceLight = Color(0xFFFDFCFF)
val InkOnSurfaceLight = Color(0xFF1A1C1E)
val InkSurfaceVariantLight = Color(0xFFE1E2EC)
val InkOnSurfaceVariantLight = Color(0xFF44474F)

// ---------------------------------------------------------------------------
// 暗色
// ---------------------------------------------------------------------------
val InkBlueDark = Color(0xFF9CC6FF)
val InkTealDark = Color(0xFF6FD8CB)
val InkAmberDark = Color(0xFFF7C878)

val InkSurfaceDark = Color(0xFF121316)
val InkOnSurfaceDark = Color(0xFFE3E2E6)
val InkSurfaceVariantDark = Color(0xFF44474F)
val InkOnSurfaceVariantDark = Color(0xFFC4C6D0)

// ---------------------------------------------------------------------------
// 画布纸张
//
// 注意：这两个颜色不跟随 Material 主题，而是直接交给 NoteCanvasView。
// 理由见 EditorScreen 里的注释 —— 纸张颜色属于「笔记内容」的一部分，
// 将来会存进笔记数据里，不能随 UI 主题漂移。
// ---------------------------------------------------------------------------
val PaperLight = Color(0xFFFFFFFF)
val PaperDark = Color(0xFF1E1F24)

// 墨色必须和纸张成对出现，不能各自跟系统主题走 ——
// 否则黑暗模式下纸变深了、墨还是黑的，字就看不见了。
val InkOnLightPaper = Color(0xFF1A1C1E)
val InkOnDarkPaper = Color(0xFFE8E8EA)

// 转成 View 层用的 ARGB Int 用 Compose 自带的 androidx.compose.ui.graphics.toArgb()，
// 不要自己写 —— 自己实现容易在 alpha 预乘上出偏差。
