package com.inknote.pad.data.file

import com.inknote.pad.ink.InkPoint
import com.inknote.pad.ink.Stroke
import com.inknote.pad.ink.StrokeStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import kotlin.math.roundToInt

/** 一页的完整内容 */
class InkPage(
    val pageWidth: Float,
    val pageHeight: Float,
    val strokes: List<Stroke>,
)

/**
 * `.ink` 文件编解码。
 *
 * ============================ 文件布局 ============================
 * ```
 * magic          int32    0x494E4B31  ("INK1")
 * formatVersion  uint8    当前 1
 * reserved       uint8[3] 保留位，必须为 0
 * crc32          int32    正文的 CRC32
 * bodyLength     int32    正文字节数
 * body           bytes    见下
 * ```
 * 正文：
 * ```
 * pageWidth      float32
 * pageHeight     float32
 * strokeCount    varint
 * 每笔：
 *   colorArgb    int32
 *   widthPx      float32
 *   style        uint8    StrokeStyle.ordinal
 *   tool         uint8    **v2 新增**：0=钢笔，1=荧光笔
 *   pointCount   varint
 *   第一个点： x varint, y varint, pressure varint      (绝对坐标)
 *   之后的点： dx zigzagVarint, dy zigzagVarint,
 *              dPressure zigzagVarint                    (增量)
 * ```
 *
 * ============================ 为什么这么设计 ============================
 * 1. **增量 + varint**：相邻采样点通常只差几像素。绝对坐标要 3 字节，
 *    增量只要 1 字节。一页 5 万个点从 600KB（三个 float32）压到约 150KB。
 * 2. **定点坐标**：坐标按 1/16 px 量化后存整数。1/16 px 远低于屏幕精度，
 *    但换来的是"能被 varint 压缩"。
 * 3. **CRC32**：手写笔记是不可再生的数据。没有校验的话，
 *    一个被截断的文件只会表现为"解析到一半抛异常"，甚至更糟 ——
 *    解析出一个看似正常但内容错乱的结果。有 CRC 才能明确判定"这个文件坏了"。
 * 4. **保留位**：以后要加"页面背景样式"之类的小字段时，
 *    可以直接用掉保留位而不必升版本号。
 *
 * 这个类是纯 JVM 的（只用 java.io 和 java.util.zip），
 * 所以格式正确性可以在单元测试里完整验证，不需要真机。
 */
object InkFileCodec {

    private const val MAGIC = 0x494E4B31
    private const val FORMAT_VERSION = 2

    /** v2 起每笔多一个工具字节。老文件（v1）没有它，按钢笔读 */
    private const val TOOL_PEN = 0
    private const val TOOL_HIGHLIGHTER = 1

    /**
     * 坐标量化精度：1/4 px。
     *
     * 为什么是 2 次幂的位数而不是 1/16：这个精度直接决定压缩率。
     * 相邻采样点在正常书写速度下相距 3~5px，按 1/4 px 量化后增量是 12~20，
     * zigzag 之后 24~40 —— **1 字节装得下**。
     * 若用 1/16 px，同样 4px 的增量变成 64，zigzag 后 128 就溢出 7 位了，
     * 每点白白多花一个字节（3 字节变 5 字节，文件大 60%）。
     * 而 1/4 px 在任何屏幕密度下都远低于可见精度。
     */
    private const val COORD_SHIFT = 2
    private const val COORD_SCALE = 1 shl COORD_SHIFT

    /**
     * 压感量化精度：0..1024。
     *
     * 为什么不是 4096：这支笔的 ABS_PRESSURE 确实是 12 位，但那是【硬件分辨率】，
     * 不是这里需要的精度。压感最终只影响线宽系数（0.15~1.0 这个区间），
     * 1024 级对应 0.0008 的宽度步进 —— 肉眼和手感都绝无可能分辨。
     *
     * 而 4096 是有代价的：相邻采样点的压力增量约 68，zigzag 之后 136 就溢出
     * 7 位了，每个点白白多花一个字节（占总量四分之一）。
     * 这个是实测出来的 —— 单元测试的压缩率断言逼着算了一遍真实数字，
     * 才发现注释里"约 150KB"是拍脑袋写的。
     */
    private const val PRESSURE_SCALE = 1024

    /**
     * 单页笔迹数的上限，用来防止坏文件把内存吃光。
     * 正常一页不会超过几千笔，100 万是极宽松的上界。
     */
    private const val MAX_STROKES = 1_000_000

    /** 单笔采样点数上限，同理 */
    private const val MAX_POINTS_PER_STROKE = 1_000_000

    fun writeTo(out: OutputStream, page: InkPage) {
        val body = ByteArrayOutputStream(estimateBodySize(page))
        val bodyOut = DataOutputStream(body)

        bodyOut.writeFloat(page.pageWidth)
        bodyOut.writeFloat(page.pageHeight)
        bodyOut.writeVarInt(page.strokes.size)

        for (stroke in page.strokes) {
            bodyOut.writeInt(stroke.colorArgb)
            bodyOut.writeFloat(stroke.widthPx)
            bodyOut.writeByte(stroke.style.ordinal)
            bodyOut.writeByte(if (stroke.isHighlighter) TOOL_HIGHLIGHTER else TOOL_PEN)
            bodyOut.writeVarInt(stroke.points.size)

            var prevX = 0
            var prevY = 0
            var prevPressure = 0
            for (i in stroke.points.indices) {
                val p = stroke.points[i]
                val x = quantizeCoord(p.x)
                val y = quantizeCoord(p.y)
                val pressure = quantizePressure(p.pressure)

                if (i == 0) {
                    bodyOut.writeVarInt(x)
                    bodyOut.writeVarInt(y)
                    bodyOut.writeVarInt(pressure)
                } else {
                    // 增量用 zigzag：正负都要能压成 1 字节
                    bodyOut.writeVarInt(zigzag(x - prevX))
                    bodyOut.writeVarInt(zigzag(y - prevY))
                    bodyOut.writeVarInt(zigzag(pressure - prevPressure))
                }
                prevX = x
                prevY = y
                prevPressure = pressure
            }
        }
        bodyOut.flush()

        val payload = body.toByteArray()
        val crc = CRC32().apply { update(payload) }.value.toInt()

        val header = DataOutputStream(out)
        header.writeInt(MAGIC)
        header.writeByte(FORMAT_VERSION)
        header.writeByte(0)
        header.writeByte(0)
        header.writeByte(0)
        header.writeInt(crc)
        header.writeInt(payload.size)
        header.write(payload)
        header.flush()
    }

    /**
     * @throws InkFormatException 文件不是 .ink、版本不支持、CRC 不匹配或内容损坏。
     *         调用方应该把这个文件隔离而不是当成空页面 —— 那会让用户以为笔记丢了。
     */
    @Throws(InkFormatException::class)
    fun readFrom(input: InputStream): InkPage {
        try {
            val header = DataInputStream(input)
            val magic = header.readInt()
            if (magic != MAGIC) {
                throw InkFormatException("不是 .ink 文件（magic=0x${magic.toString(16)}）")
            }
            val version = header.readUnsignedByte()
            if (version > FORMAT_VERSION) {
                throw InkFormatException(
                    "文件版本 $version 比本程序支持的 $FORMAT_VERSION 更新，请升级 App",
                )
            }
            header.readUnsignedByte()
            header.readUnsignedByte()
            header.readUnsignedByte()

            val expectedCrc = header.readInt()
            val bodyLength = header.readInt()
            if (bodyLength < 0) throw InkFormatException("正文长度非法：$bodyLength")

            val payload = ByteArray(bodyLength)
            header.readFully(payload)

            val actualCrc = CRC32().apply { update(payload) }.value.toInt()
            if (actualCrc != expectedCrc) {
                throw InkFormatException("CRC 校验失败，文件已损坏")
            }

            return parseBody(payload, version)
        } catch (e: InkFormatException) {
            throw e
        } catch (e: EOFException) {
            throw InkFormatException("文件被截断", e)
        } catch (e: IOException) {
            throw InkFormatException("读取失败：${e.message}", e)
        }
    }

    private fun parseBody(payload: ByteArray, version: Int): InkPage {
        val body = DataInputStream(ByteArrayInputStream(payload))

        val pageWidth = body.readFloat()
        val pageHeight = body.readFloat()
        if (!pageWidth.isFinite() || !pageHeight.isFinite() || pageWidth <= 0f || pageHeight <= 0f) {
            throw InkFormatException("页面尺寸非法：$pageWidth x $pageHeight")
        }

        val strokeCount = body.readVarInt()
        if (strokeCount < 0 || strokeCount > MAX_STROKES) {
            throw InkFormatException("笔迹数非法：$strokeCount")
        }

        val styles = StrokeStyle.entries
        val strokes = ArrayList<Stroke>(strokeCount)

        repeat(strokeCount) {
            val colorArgb = body.readInt()
            val widthPx = body.readFloat()
            val styleOrdinal = body.readUnsignedByte()
            if (styleOrdinal >= styles.size) {
                throw InkFormatException("未知的笔迹风格：$styleOrdinal")
            }
            // v2 起才有工具字节。老文件按钢笔读 ——
            // 荧光笔是 v2 才有的功能，v1 文件里不可能有荧光笔。
            val isHighlighter = if (version >= 2) {
                when (val tool = body.readUnsignedByte()) {
                    TOOL_PEN -> false
                    TOOL_HIGHLIGHTER -> true
                    else -> throw InkFormatException("未知的笔迹工具：$tool")
                }
            } else {
                false
            }
            val pointCount = body.readVarInt()
            if (pointCount < 0 || pointCount > MAX_POINTS_PER_STROKE) {
                throw InkFormatException("采样点数非法：$pointCount")
            }

            val points = ArrayList<InkPoint>(pointCount)
            var x = 0
            var y = 0
            var pressure = 0
            for (i in 0 until pointCount) {
                if (i == 0) {
                    x = body.readVarInt()
                    y = body.readVarInt()
                    pressure = body.readVarInt()
                } else {
                    x += unzigzag(body.readVarInt())
                    y += unzigzag(body.readVarInt())
                    pressure += unzigzag(body.readVarInt())
                }
                points.add(
                    InkPoint(
                        x = x / COORD_SCALE.toFloat(),
                        y = y / COORD_SCALE.toFloat(),
                        pressure = pressure.coerceIn(0, PRESSURE_SCALE) / PRESSURE_SCALE.toFloat(),
                    ),
                )
            }

            strokes.add(
                Stroke.rebuild(
                    colorArgb = colorArgb,
                    widthPx = widthPx,
                    style = styles[styleOrdinal],
                    isHighlighter = isHighlighter,
                    points = points,
                ),
            )
        }
        return InkPage(pageWidth, pageHeight, strokes)
    }

    private fun estimateBodySize(page: InkPage): Int {
        // 每点约 3 字节是实测值，这里给宽松一点避免反复扩容
        var points = 0
        for (s in page.strokes) points += s.points.size
        return 64 + points * 4 + page.strokes.size * 16
    }

    private fun quantizeCoord(value: Float): Int =
        (value * COORD_SCALE).roundToInt()

    private fun quantizePressure(value: Float): Int =
        (value.coerceIn(0f, 1f) * PRESSURE_SCALE).roundToInt()

    private fun zigzag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun unzigzag(value: Int): Int = (value ushr 1) xor -(value and 1)
}

/** 文件损坏或格式不支持。调用方据此把文件隔离而不是当成空页面。 */
class InkFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

// ---------------------------------------------------------------------------
// varint / zigzag
//
// 放在文件末尾而不是独立工具类里：它们只服务于这个格式，
// 单独抽出去会让人以为它们是通用工具（其实 zigzag 只对"差值为小整数"有意义）。
// ---------------------------------------------------------------------------

private fun DataOutputStream.writeVarInt(value: Int) {
    var v = value
    // 每字节 7 位有效数据，最高位表示"还有后续"
    while (true) {
        if (v and 0x7F.inv() == 0) {
            writeByte(v)
            return
        }
        writeByte((v and 0x7F) or 0x80)
        v = v ushr 7
    }
}

private fun DataInputStream.readVarInt(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val b = readUnsignedByte()
        result = result or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
        // int 最多 5 个字节；再多就是文件坏了，早点报错好过无限循环
        if (shift > 28) throw InkFormatException("varint 过长，文件已损坏")
    }
}
