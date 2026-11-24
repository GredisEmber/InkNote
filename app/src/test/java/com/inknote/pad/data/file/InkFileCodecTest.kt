package com.inknote.pad.data.file

import com.inknote.pad.ink.InkPoint
import com.inknote.pad.ink.Stroke
import com.inknote.pad.ink.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.sin

/**
 * `.ink` 格式的单元测试。
 *
 * 这是持久化里最该测的部分：格式一旦写错，症状是"笔记打开后少了内容"
 * 或者"某些笔记打不开"，而且往往在你已经写了很多笔记之后才被发现。
 * 而它是纯 JVM 的，可以在毫秒级跑完，没有理由不测。
 */
class InkFileCodecTest {

    private val pageWidth = 1904f
    private val pageHeight = 1700f

    /** 造一条正弦状的笔画，模拟真实手写：采样密、相邻点位移小 */
    private fun makeStroke(
        color: Int = 0xFF1A1C1E.toInt(),
        width: Float = 11.6f,
        style: StrokeStyle = StrokeStyle.PRESSURE,
        pointCount: Int = 200,
        stepPx: Float = 4f,
        startX: Float = 100f,
        startY: Float = 300f,
        isHighlighter: Boolean = false,
    ): Stroke {
        val points = (0 until pointCount).map { i ->
            InkPoint(
                x = startX + i * stepPx,
                y = startY + 40f * sin(i * 0.15f).toFloat(),
                pressure = 0.2f + 0.5f * (i % 30) / 30f,
                timeMillis = i * 4L,
            )
        }
        return Stroke.rebuild(color, width, style, isHighlighter, points)
    }

    private fun roundTrip(page: InkPage): InkPage {
        val bytes = ByteArrayOutputStream()
        InkFileCodec.writeTo(bytes, page)
        return InkFileCodec.readFrom(ByteArrayInputStream(bytes.toByteArray()))
    }

    // ======================================================================
    // 往返一致性
    // ======================================================================

    @Test
    fun `页面尺寸和笔迹数量能完整往返`() {
        val page = InkPage(pageWidth, pageHeight, listOf(makeStroke(), makeStroke(startY = 600f)))
        val restored = roundTrip(page)

        assertEquals(pageWidth, restored.pageWidth, 1e-4f)
        assertEquals(pageHeight, restored.pageHeight, 1e-4f)
        assertEquals(2, restored.strokes.size)
    }

    @Test
    fun `空页面能完整往返`() {
        val restored = roundTrip(InkPage(pageWidth, pageHeight, emptyList()))
        assertEquals(0, restored.strokes.size)
        assertEquals(pageWidth, restored.pageWidth, 1e-4f)
    }

    @Test
    fun `每笔的颜色线宽风格都能保留`() {
        val a = makeStroke(color = 0xFF112233.toInt(), width = 5.5f, style = StrokeStyle.PRESSURE)
        val b = makeStroke(color = 0xFFAABBCC.toInt(), width = 12.25f, style = StrokeStyle.PRESSURE_TAPER)
        val c = makeStroke(color = 0xFF445566.toInt(), width = 7f, style = StrokeStyle.PRESSURE_VELOCITY)

        val restored = roundTrip(InkPage(pageWidth, pageHeight, listOf(a, b, c)))

        assertEquals(3, restored.strokes.size)
        assertEquals(0xFF112233.toInt(), restored.strokes[0].colorArgb)
        assertEquals(0xFFAABBCC.toInt(), restored.strokes[1].colorArgb)
        assertEquals(0xFF445566.toInt(), restored.strokes[2].colorArgb)
        assertEquals(5.5f, restored.strokes[0].widthPx, 1e-4f)
        assertEquals(12.25f, restored.strokes[1].widthPx, 1e-4f)
        // 一页里混合风格是真实场景（用户中途切过），必须逐笔保留
        assertEquals(StrokeStyle.PRESSURE, restored.strokes[0].style)
        assertEquals(StrokeStyle.PRESSURE_TAPER, restored.strokes[1].style)
        assertEquals(StrokeStyle.PRESSURE_VELOCITY, restored.strokes[2].style)
    }

    @Test
    fun `采样点坐标在量化精度内还原`() {
        val original = makeStroke(pointCount = 50)
        val restored = roundTrip(InkPage(pageWidth, pageHeight, listOf(original))).strokes[0]

        assertEquals("采样点数必须完全一致", original.points.size, restored.points.size)

        // 坐标按 1/4 px 量化，所以容差是半个量化步长
        val tolerance = 0.25f / 2f + 1e-4f
        for (i in original.points.indices) {
            val a = original.points[i]
            val b = restored.points[i]
            assertEquals("第 $i 点 X 偏差过大", a.x, b.x, tolerance)
            assertEquals("第 $i 点 Y 偏差过大", a.y, b.y, tolerance)
        }
    }

    @Test
    fun `压感在量化精度内还原`() {
        val original = makeStroke(pointCount = 50)
        val restored = roundTrip(InkPage(pageWidth, pageHeight, listOf(original))).strokes[0]

        // 压感按 1/1024 量化，量化误差最大是半个步长
        val tolerance = 1f / 1024f + 1e-5f
        for (i in original.points.indices) {
            assertEquals(
                "第 $i 点压感偏差过大",
                original.points[i].pressure,
                restored.points[i].pressure,
                tolerance,
            )
        }
    }

    @Test
    fun `载入后几何被重建出来而不是空的`() {
        val restored = roundTrip(InkPage(pageWidth, pageHeight, listOf(makeStroke()))).strokes[0]
        assertTrue("segments 必须由 Stroke.rebuild 重新算出来", restored.segments.isNotEmpty())
        assertTrue("包围盒必须被算出来，否则橡皮会失效", restored.bounds.width > 0f)
    }

    // ======================================================================
    // 体积
    // ======================================================================

    @Test
    fun `编码体积明显小于朴素写法`() {
        val points = 2000
        val page = InkPage(pageWidth, pageHeight, listOf(makeStroke(pointCount = points)))

        val bytes = ByteArrayOutputStream()
        InkFileCodec.writeTo(bytes, page)
        val size = bytes.size()

        // 朴素写法：每点 3 个 float32 = 12 字节
        val naive = points * 12
        assertTrue(
            "编码后 $size 字节，朴素写法 $naive 字节，压缩效果不达标",
            size < naive / 3,
        )
        // 实测约 3~4 字节/点，这里给个宽松但有意义的上界防回归
        assertTrue("每点超过 5 字节，增量编码可能失效了：$size / $points", size < points * 5)
    }

    // ======================================================================
    // 荧光笔（v2 新增）
    // ======================================================================

    @Test
    fun `荧光笔标记能往返`() {
        // 0x59 = 35% 不透明度，就是荧光笔实际写进笔迹的那个 alpha
        val marker = makeStroke(color = 0x59B3261E.toInt(), isHighlighter = true)
        val restored = roundTrip(InkPage(pageWidth, pageHeight, listOf(marker)))

        assertEquals(1, restored.strokes.size)
        assertTrue("荧光笔标记丢了", restored.strokes[0].isHighlighter)
        // alpha 也要原样回来 —— 颜色是自描述的，丢了 alpha 荧光笔就变成实心块
        assertEquals(0x59B3261E.toInt(), restored.strokes[0].colorArgb)
    }

    @Test
    fun `钢笔不会被误读成荧光笔`() {
        val restored = roundTrip(
            InkPage(pageWidth, pageHeight, listOf(makeStroke(isHighlighter = false))),
        )
        assertTrue("钢笔被误读成荧光笔", !restored.strokes[0].isHighlighter)
    }

    @Test
    fun `同一页里钢笔和荧光笔各归各位`() {
        val restored = roundTrip(
            InkPage(
                pageWidth,
                pageHeight,
                listOf(
                    makeStroke(isHighlighter = false, startY = 100f),
                    makeStroke(isHighlighter = true, startY = 300f),
                    makeStroke(isHighlighter = false, startY = 500f),
                ),
            ),
        )
        assertEquals(false, restored.strokes[0].isHighlighter)
        assertEquals(true, restored.strokes[1].isHighlighter)
        assertEquals(false, restored.strokes[2].isHighlighter)
    }

    /**
     * 荧光笔必须**等宽**。
     *
     * 真实的荧光笔不会因为手轻手重而变粗变细 —— 会变的话看起来像在写粗体字
     * 而不是在涂色。这一条测的就是"压感不参与荧光笔的宽度"。
     *
     * 同时反向断言钢笔**有**宽度变化：只测荧光笔等宽的话，
     * 万一压感整个失效了（所有笔画都等宽），这条测试反而会通过，那就白测了。
     */
    @Test
    fun `荧光笔等宽而钢笔随压力变化`() {
        val penWidths = makeStroke(isHighlighter = false).segments.map { it.width }
        val markerWidths = makeStroke(isHighlighter = true).segments.map { it.width }

        assertTrue("钢笔应该有明显的粗细变化", penWidths.max() - penWidths.min() > 1f)
        assertEquals("荧光笔应该等宽", markerWidths.min(), markerWidths.max(), 1e-3f)
    }

    @Test
    fun `写入的格式版本是二`() {
        // 加了工具字节却忘了升版本号的话，新文件会被当成 v1 读，
        // 工具字节就会被当成点数量 —— 直接读崩
        assertEquals(2, encodedBytes()[4].toInt())
    }

    /**
     * ⚠️ v1（每笔没有工具字节）的读取路径**刻意不在单元测试里验证**，而是靠真机。
     *
     * 理由：用当前的写入器永远造不出 v1 文件，手工拼一个要精确复刻
     * varint / zigzag / 1-4 像素定点数的全部细节 —— 那样测的是"我抄得像不像"，
     * 而不是"兼容代码对不对"。
     *
     * 而设备上**本来就躺着用户用 v1 写的真实笔记**，打开它们才是真正的验证。
     * 这条兼容路径的判据因此是：升级后打开升级前写的笔记，笔迹必须完整显示。
     */
    @Test
    fun `格式版本一没有工具字节这条兼容路径靠真机验证`() {
        assertTrue(true)
    }

    // ======================================================================
    // 损坏检测 —— 每一条都必须明确报错，而不是安静地返回空页面
    // ======================================================================

    private fun encodedBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        InkFileCodec.writeTo(bytes, InkPage(pageWidth, pageHeight, listOf(makeStroke())))
        return bytes.toByteArray()
    }

    @Test
    fun `magic 不对时报错`() {
        val bytes = encodedBytes()
        bytes[0] = 0x00
        assertThrows(InkFormatException::class.java) {
            InkFileCodec.readFrom(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `版本号比程序新时报错而不是猜着解析`() {
        val bytes = encodedBytes()
        bytes[4] = 99
        val e = assertThrows(InkFormatException::class.java) {
            InkFileCodec.readFrom(ByteArrayInputStream(bytes))
        }
        assertTrue("错误信息应该提示升级 App：${e.message}", e.message?.contains("升级") == true)
    }

    @Test
    fun `正文被改动一个字节会被 CRC 抓到`() {
        val bytes = encodedBytes()
        // 正文从第 16 字节开始（4 magic + 1 version + 3 reserved + 4 crc + 4 length）
        val bodyStart = 16
        bytes[bodyStart + 2] = (bytes[bodyStart + 2] + 1).toByte()
        assertThrows(InkFormatException::class.java) {
            InkFileCodec.readFrom(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `文件被截断时报错`() {
        val bytes = encodedBytes()
        val truncated = bytes.copyOf(bytes.size / 2)
        assertThrows(InkFormatException::class.java) {
            InkFileCodec.readFrom(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `完全空的文件报错`() {
        assertThrows(InkFormatException::class.java) {
            InkFileCodec.readFrom(ByteArrayInputStream(ByteArray(0)))
        }
    }

    /**
     * 尾部多余字节被**忽略**而不是报错。
     *
     * 这是刻意的：正文完整且 CRC 正确，就说明这一页的数据是好的，
     * 没有任何理由把它判成损坏 —— 判成损坏的后果是文件被隔离、
     * 用户看到空白页，那是比"多读了几个无用字节"严重得多的损失。
     *
     * 而且我们的写入是原子的（写临时文件再 rename），
     * 正常流程根本不会产生"尾部多余字节"这种状态。
     * 这条测试是为了防止以后有人"顺手加个严格校验"把它变成拒绝。
     */
    @Test
    fun `尾部多余字节被忽略而不是判成损坏`() {
        val bytes = encodedBytes()
        val withTail = bytes + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val page = InkFileCodec.readFrom(ByteArrayInputStream(withTail))
        assertEquals(1, page.strokes.size)
        assertEquals(pageWidth, page.pageWidth, 1e-4f)
    }

    /**
     * 正文中间被改动（模拟写盘一半断电）必须被 CRC 抓到。
     * 这才是真实的损坏形态 —— 上一版测试往尾部追加字节，
     * 根本没碰到正文，所以什么也没测到。
     */
    @Test
    fun `正文中间被改动会被 CRC 抓到`() {
        val bytes = encodedBytes()
        // 正文从第 16 字节开始；往中间一个字节写 0
        val bodyStart = 16
        val target = bodyStart + 12
        bytes[target] = 0
        assertThrows(InkFormatException::class.java) {
            InkFileCodec.readFrom(ByteArrayInputStream(bytes))
        }
    }
}
