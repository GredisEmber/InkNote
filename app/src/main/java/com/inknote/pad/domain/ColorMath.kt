package com.inknote.pad.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 颜色换算。
 *
 * ============================ 为什么不用 android.graphics.Color ============================
 * `Color.HSVToColor` / `Color.colorToHSV` 当然更省事，但它们是 Android API，
 * 而调色板属于 domain 层 —— 引入之后这一层就没法在 JVM 上跑测试了。
 *
 * 自己写还有两个好处：
 *  1. **往返换算可以单元测试**（HSV → RGB → HSV 必须回到原值）。
 *     取色器的正确性完全建立在这个换算上，写错了表现为"滑块拖到某处颜色会跳"，
 *     那是靠肉眼很难定位的一类 bug。
 *  2. 纯 Kotlin 函数没有隐式状态，行为可预期。
 */
object ColorMath {

    /**
     * RGB → HSV。
     *
     * @param r/g/b 0..1
     * @return [色相 0..360, 饱和度 0..1, 明度 0..1]
     */
    fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        val value = maxC
        // 黑色（max=0）没有色相也没有饱和度，定义成 0 而不是除零
        val saturation = if (maxC <= 0f) 0f else delta / maxC

        val hue = when {
            delta <= 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        return floatArrayOf(hue, saturation, value)
    }

    /**
     * HSV → RGB。
     *
     * @param h 色相，会对 360 取模（滑块可能给出 360）
     * @return [r, g, b]，各 0..1
     */
    fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val hue = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)

        val c = value * sat
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = value - c

        val rgb = when {
            hue < 60f -> floatArrayOf(c, x, 0f)
            hue < 120f -> floatArrayOf(x, c, 0f)
            hue < 180f -> floatArrayOf(0f, c, x)
            hue < 240f -> floatArrayOf(0f, x, c)
            hue < 300f -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        return floatArrayOf(rgb[0] + m, rgb[1] + m, rgb[2] + m)
    }

    /** 不透明 ARGB */
    fun argbOf(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or
            (channel8(r) shl 16) or
            (channel8(g) shl 8) or
            channel8(b)

    fun argbOfHsv(h: Float, s: Float, v: Float): Int {
        val rgb = hsvToRgb(h, s, v)
        return argbOf(rgb[0], rgb[1], rgb[2])
    }

    fun hsvOfArgb(argb: Int): FloatArray = rgbToHsv(
        redOf(argb),
        greenOf(argb),
        blueOf(argb),
    )

    fun redOf(argb: Int): Float = ((argb shr 16) and 0xFF) / 255f
    fun greenOf(argb: Int): Float = ((argb shr 8) and 0xFF) / 255f
    fun blueOf(argb: Int): Float = (argb and 0xFF) / 255f

    /** 0..1 → 0..255，四舍五入后夹到合法范围 */
    private fun channel8(v: Float): Int =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

    // ======================================================================
    // 十六进制
    // ======================================================================

    /**
     * 解析十六进制颜色。接受这些写法（大小写都行，`#` 可省）：
     *   `#RRGGBB`、`RRGGBB`、`#RGB`、`RGB`
     *
     * 刻意**不接受** 8 位带 alpha 的写法：墨迹永远是不透明的，
     * 让用户以为能调透明度、结果调了没反应，比不支持更糟。
     *
     * @return 不透明 ARGB；格式不合法时返回 null（调用方据此决定"不更新"，
     *         而不是猜一个颜色 —— 边输边解析时必须区分"还没输完"和"输错了"）
     */
    fun parseHex(raw: String): Int? {
        val body = raw.trim().removePrefix("#").removePrefix("＃")
        val expanded = when (body.length) {
            6 -> body
            // #RGB 是 #RRGGBB 的缩写形式
            3 -> buildString { body.forEach { append(it).append(it) } }
            else -> return null
        }
        if (!expanded.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = expanded.toLongOrNull(16) ?: return null
        return (0xFF shl 24) or value.toInt()
    }

    /** 格式化成 `#RRGGBB`（不含 alpha） */
    fun formatHex(argb: Int): String =
        "#%02X%02X%02X".format(
            (argb shr 16) and 0xFF,
            (argb shr 8) and 0xFF,
            argb and 0xFF,
        )
}
