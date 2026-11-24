package com.inknote.pad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜色换算的单元测试。
 *
 * 取色器的正确性完全建立在这两个换算上：滑块拖到某处颜色会跳、
 * 输入十六进制之后滑块位置不对 —— 都是靠肉眼很难定位的 bug。
 * 而它们是纯数学，没有理由不测。
 */
class ColorMathTest {

    // ======================================================================
    // 已知值
    // ======================================================================

    @Test
    fun `纯红是色相零度饱和度一`() {
        val hsv = ColorMath.rgbToHsv(1f, 0f, 0f)
        assertEquals(0f, hsv[0], 1e-3f)
        assertEquals(1f, hsv[1], 1e-3f)
        assertEquals(1f, hsv[2], 1e-3f)
    }

    @Test
    fun `纯绿是色相一百二十度`() {
        assertEquals(120f, ColorMath.rgbToHsv(0f, 1f, 0f)[0], 1e-3f)
    }

    @Test
    fun `纯蓝是色相二百四十度`() {
        assertEquals(240f, ColorMath.rgbToHsv(0f, 0f, 1f)[0], 1e-3f)
    }

    @Test
    fun `黑色没有色相也没有饱和度`() {
        val hsv = ColorMath.rgbToHsv(0f, 0f, 0f)
        assertEquals(0f, hsv[0], 1e-3f)
        assertEquals(0f, hsv[1], 1e-3f)
        assertEquals(0f, hsv[2], 1e-3f)
    }

    @Test
    fun `白色明度一饱和度为二零`() {
        val hsv = ColorMath.rgbToHsv(1f, 1f, 1f)
        // 240 是 RGB→HSV 里灰色会被判到的色相，这里只要求饱和度和明度正确
        assertEquals(0f, hsv[1], 1e-3f)
        assertEquals(1f, hsv[2], 1e-3f)
    }

    @Test
    fun `灰色饱和度为零`() {
        assertEquals(0f, ColorMath.rgbToHsv(0.5f, 0.5f, 0.5f)[1], 1e-3f)
    }

    // ======================================================================
    // 往返换算 —— 这一组才是真正保护取色器的
    // ======================================================================

    @Test
    fun `任意 HSV 往返之后不变量`() {
        for (h in 0 until 360 step 7) {
            for (s in listOf(0f, 0.15f, 0.5f, 0.83f, 1f)) {
                for (v in listOf(0f, 0.2f, 0.55f, 0.9f, 1f)) {
                    val rgb = ColorMath.hsvToRgb(h.toFloat(), s, v)
                    val back = ColorMath.rgbToHsv(rgb[0], rgb[1], rgb[2])

                    // ⚠️ 色相和饱和度**只在饱和度和明度都不为零时才有定义**：
                    //   · 明度为 0（纯黑）→ 没有任何颜色信息，色相和饱和度都归零
                    //   · 饱和度为 0（灰阶）→ 没有色相
                    // 这不是实现的缺陷，是颜色模型本身的性质。
                    // 第一版测试在这里失败了，失败的是断言而不是代码 ——
                    // 往返测试的价值正在于此：它逼你写清楚"哪些量在什么条件下才有定义"。
                    if (s > 0.01f && v > 0.01f) {
                        assertEquals(
                            "h=$h s=$s v=$v 色相往返不一致",
                            h.toFloat(), back[0], 0.5f,
                        )
                        assertEquals("h=$h s=$s v=$v 饱和度往返不一致", s, back[1], 0.01f)
                    }
                    // 明度**任何情况下**都必须原样回来
                    assertEquals("h=$h s=$s v=$v 明度往返不一致", v, back[2], 0.01f)
                }
            }
        }
    }

    @Test
    fun `纯黑的色相与饱和度归零是颜色模型的性质而非缺陷`() {
        // 这条测试把上面那个"哪些量才有定义"的结论固化下来，
        // 免得以后有人看到 hsvToRgb(0, 0_15f, 0f) 得到黑色就以为换算写错了
        val rgb = ColorMath.hsvToRgb(0f, 0.15f, 0f)
        assertEquals(0f, rgb[0], 1e-4f)
        assertEquals(0f, rgb[1], 1e-4f)
        assertEquals(0f, rgb[2], 1e-4f)
    }

    @Test
    fun `ARGB 往返之后逐通道相同`() {
        val samples = listOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF1A1C1E.toInt(),
            0xFFB3261E.toInt(), 0xFF1B5E9B.toInt(), 0xFF1E6B3A.toInt(),
            0xFF9A5B00.toInt(), 0xFF6A3FA0.toInt(), 0xFF7F7F7F.toInt(),
        )
        for (argb in samples) {
            val hsv = ColorMath.hsvOfArgb(argb)
            val back = ColorMath.argbOfHsv(hsv[0], hsv[1], hsv[2])
            assertEquals(
                "0x${argb.toUInt().toString(16)} 往返之后颜色变了",
                argb, back,
            )
        }
    }

    // ======================================================================
    // 边界
    // ======================================================================

    @Test
    fun `色相三百六十度等价于零度`() {
        val a = ColorMath.hsvToRgb(0f, 1f, 1f)
        val b = ColorMath.hsvToRgb(360f, 1f, 1f)
        assertEquals(a[0], b[0], 1e-4f)
        assertEquals(a[1], b[1], 1e-4f)
        assertEquals(a[2], b[2], 1e-4f)
    }

    @Test
    fun `色相为负也能正确换算`() {
        val a = ColorMath.hsvToRgb(0f, 1f, 1f)
        val b = ColorMath.hsvToRgb(-360f, 1f, 1f)
        assertEquals(a[0], b[0], 1e-4f)
    }

    @Test
    fun `饱和度和明度会被夹到合法范围`() {
        // 不该抛异常，也不该产生越界通道值
        val rgb = ColorMath.hsvToRgb(120f, 5f, 5f)
        rgb.forEach { assertTrue("通道值越界: $it", it in 0f..1f) }
    }

    @Test
    fun `通道值会四舍五入到八位`() {
        // 0.5 → 128（127.5 四舍五入），而不是 127
        val argb = ColorMath.argbOf(0.5f, 0.5f, 0.5f)
        assertEquals(0xFF808080.toInt(), argb)
    }

    // ======================================================================
    // 十六进制
    // ======================================================================

    @Test
    fun `六位十六进制带井号`() {
        assertEquals(0xFF1B5E9B.toInt(), ColorMath.parseHex("#1B5E9B"))
    }

    @Test
    fun `六位十六进制不带井号`() {
        assertEquals(0xFF1B5E9B.toInt(), ColorMath.parseHex("1B5E9B"))
    }

    @Test
    fun `小写也能解析`() {
        assertEquals(0xFF1B5E9B.toInt(), ColorMath.parseHex("#1b5e9b"))
    }

    @Test
    fun `三位缩写会展开成六位`() {
        assertEquals(0xFFAABBCC.toInt(), ColorMath.parseHex("#ABC"))
    }

    @Test
    fun `首尾空格会被忽略`() {
        assertEquals(0xFF1B5E9B.toInt(), ColorMath.parseHex("  #1B5E9B  "))
    }

    @Test
    fun `输入到一半的十六进制返回 null 而不是猜一个颜色`() {
        // 边输边解析时必须区分"还没输完"和"输错了"：
        // 返回 null 表示"不更新滑块"，用户才能继续往下打
        assertNull(ColorMath.parseHex(""))
        assertNull(ColorMath.parseHex("#"))
        assertNull(ColorMath.parseHex("#1B"))
        assertNull(ColorMath.parseHex("#1B5E"))
        assertNull(ColorMath.parseHex("#1B5E9"))
    }

    @Test
    fun `非法字符返回 null`() {
        assertNull(ColorMath.parseHex("#GGGGGG"))
        assertNull(ColorMath.parseHex("#1B5E9Z"))
    }

    @Test
    fun `八位带透明度的写法被拒绝`() {
        // 墨迹永远不透明。接受它会让用户以为能调透明度，而实际调了没反应。
        assertNull(ColorMath.parseHex("#801B5E9B"))
    }

    @Test
    fun `格式化输出大写六位带井号`() {
        assertEquals("#1B5E9B", ColorMath.formatHex(0xFF1B5E9B.toInt()))
        assertEquals("#000000", ColorMath.formatHex(0xFF000000.toInt()))
        assertEquals("#FFFFFF", ColorMath.formatHex(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `格式化再解析回来是同一个颜色`() {
        val argb = 0xFF6A3FA0.toInt()
        assertEquals(argb, ColorMath.parseHex(ColorMath.formatHex(argb)))
    }
}
