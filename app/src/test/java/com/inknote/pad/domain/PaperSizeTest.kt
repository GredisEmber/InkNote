package com.inknote.pad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纸型换算的单元测试。
 *
 * 这里要守住的核心不变量是**尺寸一旦解析出来就必须稳定**：
 * 它会被存进数据库、成为笔迹坐标的基准，之后不能再变。
 * 如果哪天有人调了 RASTER_DPI 而没意识到这一点，
 * 这些测试里的具体数字会失败，从而把问题拦在编译期而不是变成
 * "用户的笔记全部错位"。
 */
class PaperSizeTest {

    private val screenW = 1904f
    private val screenH = 1700f

    // ======================================================================
    // 物理尺寸换算
    // ======================================================================

    @Test
    fun `A4 竖版在 150dpi 下是 1240 乘 1754`() {
        val d = PaperMetrics.resolvePixels(PaperSize.A4, landscape = false, screenW, screenH)
        // 210mm = 8.2677in × 150 = 1240.16 → 1240
        // 297mm = 11.6929in × 150 = 1753.94 → 1754
        assertEquals(1240f, d.widthPx, 0f)
        assertEquals(1754f, d.heightPx, 0f)
    }

    @Test
    fun `A4 横版是竖版的宽高互换`() {
        val p = PaperMetrics.resolvePixels(PaperSize.A4, landscape = false, screenW, screenH)
        val l = PaperMetrics.resolvePixels(PaperSize.A4, landscape = true, screenW, screenH)
        assertEquals(p.heightPx, l.widthPx, 0f)
        assertEquals(p.widthPx, l.heightPx, 0f)
    }

    @Test
    fun `A3 的每边都约等于 A4 的根号二倍`() {
        val a4 = PaperMetrics.resolvePixels(PaperSize.A4, landscape = false, screenW, screenH)
        val a3 = PaperMetrics.resolvePixels(PaperSize.A3, landscape = false, screenW, screenH)
        val ratio = a3.widthPx / a4.widthPx
        assertEquals("A3/A4 边长比应约为 √2", 1.414f, ratio, 0.01f)
    }

    @Test
    fun `A5 比 A4 小`() {
        val a5 = PaperMetrics.resolvePixels(PaperSize.A5, landscape = false, screenW, screenH)
        val a4 = PaperMetrics.resolvePixels(PaperSize.A4, landscape = false, screenW, screenH)
        assertTrue("A5 应该比 A4 小", a5.widthPx < a4.widthPx && a5.heightPx < a4.heightPx)
    }

    @Test
    fun `所有纸张都是竖向时长边大于短边`() {
        for (size in listOf(PaperSize.A5, PaperSize.A4, PaperSize.A3)) {
            val d = PaperMetrics.resolvePixels(size, landscape = false, screenW, screenH)
            assertTrue("${size.name} 竖版应该高大于宽", d.heightPx > d.widthPx)
        }
    }

    // ======================================================================
    // SCREEN 纸型
    // ======================================================================

    @Test
    fun `与屏幕相同时直接用绘图区尺寸`() {
        val d = PaperMetrics.resolvePixels(PaperSize.SCREEN, landscape = false, screenW, screenH)
        assertEquals(1904f, d.widthPx, 0f)
        assertEquals(1700f, d.heightPx, 0f)
    }

    @Test
    fun `与屏幕相同时横竖方向的参数不影响结果`() {
        val a = PaperMetrics.resolvePixels(PaperSize.SCREEN, landscape = false, screenW, screenH)
        val b = PaperMetrics.resolvePixels(PaperSize.SCREEN, landscape = true, screenW, screenH)
        assertEquals(a, b)
    }

    @Test
    fun `与屏幕相同时小数尺寸会被取整`() {
        val d = PaperMetrics.resolvePixels(PaperSize.SCREEN, landscape = false, 1903.6f, 1699.4f)
        assertEquals(1904f, d.widthPx, 0f)
        assertEquals(1699f, d.heightPx, 0f)
    }

    // ======================================================================
    // 缩略图适配
    // ======================================================================

    @Test
    fun `缩略图按比例缩到框内且不超出`() {
        val d = PaperMetrics.fitInside(1240f, 1754f, 300f, 300f)
        assertTrue("高度贴满", d.heightPx <= 300f)
        assertTrue("宽度不超框", d.widthPx <= 300f)
        assertEquals("宽高比要保持", 1240f / 1754f, d.widthPx / d.heightPx, 0.01f)
    }

    @Test
    fun `缩略图遇到非法源尺寸时不崩`() {
        val d = PaperMetrics.fitInside(0f, 0f, 300f, 200f)
        assertEquals(300f, d.widthPx, 0f)
        assertEquals(200f, d.heightPx, 0f)
    }

    // ======================================================================
    // 比例预览
    // ======================================================================

    @Test
    fun `屏幕纸型没有固定比例`() {
        assertEquals(-1f, PaperMetrics.aspectRatio(PaperSize.SCREEN, landscape = false), 0f)
    }

    @Test
    fun `A4 竖版比例约等于 0 点 707`() {
        assertEquals(0.7071f, PaperMetrics.aspectRatio(PaperSize.A4, landscape = false), 0.001f)
    }
}
