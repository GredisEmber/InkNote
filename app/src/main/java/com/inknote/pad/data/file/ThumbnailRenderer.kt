package com.inknote.pad.data.file

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.inknote.pad.domain.PaperMetrics
import com.inknote.pad.ink.InkRenderer
import com.inknote.pad.ink.Stroke
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * 把一页笔迹渲染成缩略图。
 *
 * ============================ 为什么和导出 PNG/PDF 共用同一套代码 ============================
 * 缩略图和导出本质是同一件事：**脱离屏幕、把笔迹重放到位图上**。
 * 区别只有目标尺寸和是否画纸张背景。
 * 放到一起之后，Step 7 的导出只需要调用同一个函数并给个大一点的尺寸。
 *
 * 它不依赖任何 View —— 直接 new Bitmap + Canvas 就行。
 * 这一点很重要：缩略图是在仓库写盘之后生成的，那时界面上根本没有这一页。
 */
object ThumbnailRenderer {

    /**
     * 缩略图长边像素。
     *
     * 首页卡片大约 176dp 宽，在 440dpi 上是约 484px。
     * 取 384 是折中：比卡片略小一点（缩放时不会放大模糊），
     * 但一张 A4 缩略图只有约 380KB 内存 —— 20 本笔记同时加载也才 7MB 左右。
     */
    const val MAX_SIDE_PX = 384

    /** 缩略图的纸底色。固定白色：纸就是白的，不该跟随界面深色主题 */
    private const val PAPER_ARGB = 0xFFFFFFFF.toInt()

    /**
     * @return 渲染好的位图；页面尺寸非法时返回 null
     */
    fun render(
        pageWidth: Float,
        pageHeight: Float,
        strokes: List<Stroke>,
        maxSidePx: Int = MAX_SIDE_PX,
    ): Bitmap? {
        if (pageWidth <= 0f || pageHeight <= 0f) return null

        // 按比例缩进正方形框内，保持纸张的宽高比
        val target = PaperMetrics.fitInside(
            srcWidth = pageWidth,
            srcHeight = pageHeight,
            maxWidth = maxSidePx.toFloat(),
            maxHeight = maxSidePx.toFloat(),
        )
        val width = target.widthPx.roundToInt().coerceAtLeast(1)
        val height = target.heightPx.roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER_ARGB)

        // 文档坐标 → 缩略图像素。用 minOf 保证不裁切（fitInside 已经是等比，
        // 这里只是把精确的缩放系数算出来，避免取整带来的偏差）
        val scale = minOf(width / pageWidth, height / pageHeight)
        canvas.save()
        canvas.scale(scale, scale)

        // 刻意不画网格/横线：缩到 380px 宽之后网格会糊成一片灰，
        // 反而看不清笔迹。缩略图的唯一职责是"让你认出这是哪一页"。
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ⚠️ 分两趟画，而不是按插入顺序一趟画完。
        //
        // 编辑器里荧光笔是在**钢笔下面**（靠两块位图的贴合顺序保证）。
        // 按插入顺序画的话，后涂的荧光笔会盖在已有的字上面 ——
        // 缩略图看起来字被糊掉了，而点进去发现字是清楚的。
        // 缩略图和编辑器必须是同一个结果，否则缩略图会让人觉得笔记坏了。
        for (i in strokes.indices) {
            val stroke = strokes[i]
            if (stroke.isHighlighter) InkRenderer.drawStroke(canvas, stroke, paint)
        }
        for (i in strokes.indices) {
            val stroke = strokes[i]
            if (!stroke.isHighlighter) InkRenderer.drawStroke(canvas, stroke, paint)
        }
        canvas.restore()

        return bitmap
    }

    /**
     * 原子写入 PNG：先写临时文件再改名。
     *
     * 和 .ink 的写入同样的理由：直接覆写时进程被杀会留下半截文件，
     * 而半截 PNG 解码会失败 —— 表现为"首页某张缩略图一直空着"，
     * 而且不会自愈（因为文件存在，不会重新生成）。
     */
    @Throws(IOException::class)
    fun writeTo(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")

        temp.outputStream().buffered().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("PNG 编码失败：${file.absolutePath}")
            }
        }

        if (!temp.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                throw IOException("无法替换缩略图 ${file.absolutePath}")
            }
            if (!temp.renameTo(file)) {
                throw IOException("无法重命名缩略图 ${temp.absolutePath} -> ${file.absolutePath}")
            }
        }
    }
}
