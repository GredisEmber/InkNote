package com.inknote.pad.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.inknote.pad.domain.BrushParams

/**
 * 试写区。
 *
 * ============================ 它必须用和画布同一套代码 ============================
 * 试写区只做一件事：让用户立刻看到当前参数写出来是什么样。
 * 所以它**复用 [StrokeBuilder] 和 [InkRenderer]**，走和 NoteCanvasView 完全相同的
 * 几何生成与绘制路径。
 *
 * 如果为了省事在这里另写一套简化的画线逻辑，试出来的手感就会和真实书写不一致 ——
 * 那比没有试写区更糟：它会让人相信一个假的结论，然后去调一个本来没问题的参数。
 *
 * ============================ 为什么不复用 NoteCanvasView ============================
 * NoteCanvasView 是 900 多行的完整编辑器：纸型、网格、多页、橡皮、撤销重做、
 * 缩略图、翻页手势…… 试写区一个都不需要。
 * 把它塞进来既要用一大堆"关掉这些功能"的开关，又要让它的文档坐标系
 * 和指尖坐标系对齐 —— 而试写区根本不需要文档坐标系。
 *
 * 所以这里是一个刻意的**小**实现：没有坐标变换、没有历史、没有分页，
 * 只有"按参数画线"这一件事。
 */
class BrushTestPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 当前笔刷参数。改完立刻生效（下一笔就是新参数） */
    var brushParams: BrushParams = BrushParams.DEFAULT

    /** 当前颜色，由宿主决定用钢笔色还是荧光笔色 */
    var colorArgb: Int = Color.BLACK

    /** 是不是荧光笔。荧光笔等宽、无视压感 */
    var isHighlighter: Boolean = false

    /**
     * 覆盖线宽比例（占短边）。null 表示用默认的 [PAD_WIDTH_RATIO]。
     *
     * 荧光笔要用它自己的宽度设置来试写 —— 否则试写区画出来的宽度
     * 和真实落笔的宽度不一致，试了等于没试。
     */
    var widthRatioOverride: Float? = null

    private val strokes = ArrayList<Stroke>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var builder: StrokeBuilder? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var smoothedPressure = 0f
    private var hasSmoothedPressure = false

    /** 正在写的这一笔已经产生的线段，只用于实时显示 */
    private var liveSegments: List<InkSegment> = emptyList()
    private var liveColor: Int = Color.BLACK

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HINT_COLOR
        // 用 TypedValue 换算 sp→px 而不是 displayMetrics.scaledDensity：
        // 后者已废弃，而且不尊重用户的字体缩放设置
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            14f,
            resources.displayMetrics,
        )
        textAlign = Paint.Align.CENTER
    }

    fun clear() {
        strokes.clear()
        liveSegments = emptyList()
        builder = null
        invalidate()
    }

    val isEmpty: Boolean get() = strokes.isEmpty() && liveSegments.isEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(PAD_BACKGROUND)

        // 试写区不做增量位图：内容一共就几十笔，每帧全画一遍最简单也最不容易出错。
        // 画布那边必须做增量，是因为一页可以有几百笔还要跑 144Hz ——
        // 这里没有那个量级，为了"看起来一致"而引入一套位图管理是自找麻烦。
        for (i in strokes.indices) {
            InkRenderer.drawStroke(canvas, strokes[i], paint)
        }
        if (liveSegments.isNotEmpty()) {
            InkRenderer.applyStyle(paint, liveColor)
            InkRenderer.drawSegments(canvas, liveSegments, paint)
        }

        if (strokes.isEmpty() && liveSegments.isEmpty()) {
            canvas.drawText(
                HINT_TEXT,
                width / 2f,
                height / 2f + hintPaint.textSize / 3f,
                hintPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 试写区里手指划一下就当没发生 —— 用户是想试**笔**，不是在试手指。
                // 这和画布上的"只认手写笔"是同一个理由，只是这里不需要开关。
                if (!isStylusLike(event.getToolType(index))) return false
                activePointerId = event.getPointerId(index)
                hasSmoothedPressure = false
                beginStroke(event, index)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val b = builder ?: return true
                val i = event.findPointerIndex(activePointerId)
                if (i < 0) return true
                // 历史采样点必须回放：高刷屏上一帧里可能攒了好几个采样，
                // 不回放曲线会退化成折线。
                for (h in 0 until event.historySize) {
                    addPoint(
                        x = event.getHistoricalX(i, h),
                        y = event.getHistoricalY(i, h),
                        pressure = event.getHistoricalPressure(i, h),
                        b = b,
                    )
                }
                addPoint(event.getX(i), event.getY(i), event.getPressure(i), b)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val b = builder ?: return true
                // finish() 补上收尾的那一小段，然后把这一笔收进列表。
                // 不需要"用点重建"—— build() 拿到的就是 builder 里已经算好的几何。
                b.finish()
                strokes.add(b.build(liveColor, isHighlighter))
                builder = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
                liveSegments = emptyList()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginStroke(event: MotionEvent, index: Int) {
        liveColor = colorArgb
        val widthPx = (widthRatioOverride ?: PAD_WIDTH_RATIO) * minOf(width, height)
        val b = if (isHighlighter) {
            newHighlighterBuilder(widthPx = widthPx, sampleSpacingPx = brushParams.sampleSpacingPx)
        } else {
            // 试写区的"文档短边"就是它自己的短边 ——
            // 这样试出来的相对粗细和它在真实页面上的比例是一致的
            StrokeBuilder(
                widthPx = widthPx,
                curve = brushParams.toPressureCurve(),
                style = StrokeStyle.PRESSURE,
                minSampleSpacingPx = brushParams.sampleSpacingPx,
            )
        }
        builder = b
        addPoint(event.getX(index), event.getY(index), event.getPressure(index), b)
    }

    private fun addPoint(x: Float, y: Float, pressure: Float, b: StrokeBuilder) {
        val segments = b.add(InkPoint(x, y, smooth(pressure), 0L))
        if (segments.isNotEmpty()) {
            // 拼接会产生新列表，但一笔顶多几百个点、几十次拼接，
            // 在这个尺寸下完全不值得为它做可变列表 + 手动失效
            liveSegments = liveSegments + segments
        }
    }

    private fun smooth(raw: Float): Float {
        val p = raw.coerceIn(0f, 1f)
        if (!hasSmoothedPressure) {
            smoothedPressure = p
            hasSmoothedPressure = true
            return p
        }
        smoothedPressure += brushParams.pressureSmoothing * (p - smoothedPressure)
        return smoothedPressure
    }

    private fun isStylusLike(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            toolType == MotionEvent.TOOL_TYPE_MOUSE

    private companion object {
        /**
         * 试写区里的线宽占短边的比例。
         * 比真实页面略粗一点，方便看清笔锋和粗细变化。
         */
        const val PAD_WIDTH_RATIO = 0.012f

        const val HINT_TEXT = "在这里试笔"

        const val PAD_BACKGROUND = 0xFFFFFFFF.toInt()
        const val HINT_COLOR = 0xFF9AA0A6.toInt()
    }
}
