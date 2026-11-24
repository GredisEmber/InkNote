package com.inknote.pad.ink

import android.graphics.Canvas
import android.graphics.Paint

/**
 * 把几何画到 Canvas 上。
 *
 * 单独抽出来而不是写在 View 里，是因为 Step 7 导出 PNG / PDF 必须
 * 复用同一套绘制逻辑。如果画法散在 View 的 onDraw 里，
 * 导出就会出现"屏幕上一套、导出文件另一套"的偏差 —— 这是手写 App 的经典坑。
 */
internal object InkRenderer {

    /** 配置一支"按段变宽"的笔 */
    fun applyStyle(paint: Paint, colorArgb: Int) {
        paint.color = colorArgb
        paint.style = Paint.Style.STROKE
        // 圆头 + 圆角接合：这是"分段变宽"方案能看不出接缝的关键
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.isAntiAlias = true
    }

    /** 逐段绘制。每段自己设一次 strokeWidth */
    fun drawSegments(canvas: Canvas, segments: List<InkSegment>, paint: Paint) {
        var lastWidth = Float.NaN
        for (i in segments.indices) {
            val s = segments[i]
            // 相邻段宽度经常相同，避免重复写 Paint 触发无谓的 native 调用
            if (s.width != lastWidth) {
                paint.strokeWidth = s.width
                lastWidth = s.width
            }
            canvas.drawLine(s.x0, s.y0, s.x1, s.y1, paint)
        }
    }

    fun drawStroke(canvas: Canvas, stroke: Stroke, paint: Paint) {
        applyStyle(paint, stroke.colorArgb)
        drawSegments(canvas, stroke.segments, paint)
    }
}
