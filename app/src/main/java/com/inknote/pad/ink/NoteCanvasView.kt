package com.inknote.pad.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.inknote.pad.domain.BrushParams
import com.inknote.pad.domain.InkWidthSteps
import com.inknote.pad.domain.PageTurnMode
import kotlin.math.abs

/**
 * 手写画布。
 *
 * ============================ 渲染架构 ============================
 * 只有【一块】Bitmap（pageBitmap），纸张和所有笔迹都画在它上面。
 * onDraw 每帧只做一件事：把这块 Bitmap 贴上去（外加一个橡皮光标圈）。
 *
 * 为什么把纸张也烘焙进位图：
 *   1. 每帧现画意味着 180+ 条网格线要走一遍 display list；
 *   2. 纸张和笔迹在同一层时 onDraw 只有一次位图绘制，填充率减半。
 * 代价是换纸张颜色/样式时要整页重画一次 —— 用户主动切换才会发生，可接受。
 *
 * 为什么不需要"当前笔单独一层"：
 * 几何是【只追加】的，一个采样点处理完就不会再变，所以正在写的这一笔
 * 可以直接落在 pageBitmap 上。只有撤销/重做/擦除/换纸才需要整页重画，
 * 而重画只是 drawLine（几何在收笔时已缓存进 Stroke.segments），不重跑平滑。
 *
 * ============================ 为什么不自己算脏矩形 ============================
 * View.invalidate(Rect) 和 invalidate(int,int,int,int) 在 API 28 就都废弃了 ——
 * 现代硬件加速渲染本来就是重录并重绘整个视图，局部失效是软件渲染时代的优化。
 * 真正需要降低填充率时，正确做法是 SurfaceView + 独立渲染线程（Step 8）。
 *
 * ============================ 为什么要坐标变换 ============================
 * 文档坐标系（doc）在【第一次布局】时锁定，之后再不变；
 * 视图坐标系（view）= doc 等比缩放 + 居中。
 * 如果直接拿 view 像素当坐标，一旦视图尺寸变了（横竖屏、分屏、折叠），
 * 已经画好的笔迹就会错位。锁定 doc 之后，尺寸变化只改变缩放和偏移。
 *
 * ============================ 编辑历史 ============================
 * 历史里存的是【操作】而不是笔迹：
 *   AddStroke    —— 画了一笔
 *   EraseStrokes —— 擦掉了一批笔迹（带原始下标，撤销时按原位置插回去）
 * 只存笔迹是表达不了"擦除"的：擦掉三笔再撤销，必须一次把三笔都放回原位，
 * 而不是只恢复最后一笔。
 *
 * ============================ 低延迟 ============================
 * ACTION_DOWN 时调 requestUnbufferedDispatch()：告诉系统"别攒批处理，
 * 有事件立刻给我"。这是手写跟手感的命门。
 *
 * ============================ 输入过滤 ============================
 * 默认只接受手写笔（TOOL_TYPE_STYLUS / ERASER，或 SOURCE_STYLUS）。
 * 手指一律不画 —— 这是防掌拒的第一道闸，比任何"手掌面积判定"都可靠。
 * 判定同时看 toolType 和 source：有些设备和 adb 注入的事件只设置其中一个。
 */
class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class PaperStyle {
        BLANK,

        /** 方格纸（默认，适合写公式和画图） */
        GRID,

        /** 横线纸 */
        RULED,
    }

    /** 编辑历史里的一次操作 */
    private sealed interface InkEdit {
        class AddStroke(val stroke: Stroke) : InkEdit

        /**
         * 一次擦除手势删掉的全部笔迹。
         * 带【原始下标】是关键：撤销时必须按原位置插回去，
         * 否则本该压在下面的笔画会跑到上面来，图层顺序就乱了。
         */
        class EraseStrokes(val removed: List<Pair<Int, Stroke>>) : InkEdit
    }

    // ======================================================================
    // 对外配置
    // ======================================================================

    var paperColor: Int = DEFAULT_PAPER_COLOR
        set(value) {
            if (field != value) {
                field = value
                // 纸张已经烘焙进位图，换色必须整页重画
                requestFullRender()
            }
        }

    var paperStyle: PaperStyle = PaperStyle.GRID
        set(value) {
            if (field != value) {
                field = value
                requestFullRender()
            }
        }

    /**
     * 两支笔各自的颜色。
     *
     * 刻意存两个字段而不是"一个 inkColorArgb，宿主在切工具时改它"：
     * 那样在"落笔的瞬间正好工具也变了"时会出现竞态 ——
     * 一笔荧光笔可能用上钢笔的颜色。存两份就没有这个窗口，
     * 而且换钢笔颜色也不会顺手把荧光笔改掉。
     */
    var penColorArgb: Int = DEFAULT_INK_COLOR
    var highlighterColorArgb: Int = DEFAULT_INK_COLOR

    /**
     * 当前这一笔是不是荧光笔。**落笔时定下来，中途不变**。
     *
     * 中途不变是有意的：如果在写一笔的过程中切到荧光笔，
     * 那半截笔迹就会从上层跳到下层，视觉上像穿模。
     * 想换工具就抬笔再落笔 —— 中途切工具时 [syncToolWithActiveGesture]
     * 会结束当前这一笔，所以这个字段总是和当前这一笔一致。
     */
    private var activeIsHighlighter = false

    /**
     * 当前这一笔该画进哪一层。
     *
     * 荧光笔永远在下层。钢笔优先上层；**上层不存在时用下层** ——
     * 页面上没有荧光笔时不分层，钢笔直接画在纸上，结果完全一样。
     */
    private fun activeLayerCanvas(): Canvas? =
        if (activeIsHighlighter) underCanvas else (overCanvas ?: underCanvas)

    /**
     * 当前这一笔实际写进笔迹的颜色。
     * 荧光笔要带上透明度 —— 颜色是**自描述**的，
     * 存进文件的就是带 alpha 的值，渲染时不需要额外判断。
     */
    private fun activeStrokeColor(): Int =
        if (activeIsHighlighter) withHighlighterAlpha(highlighterColorArgb) else penColorArgb

    /**
     * 笔迹基础线宽，**按文档短边的比例**而不是固定 dp。
     *
     * 为什么不能用 dp：文档坐标系是页面位图的像素，而不同纸型下
     * 1 个单位对应的物理尺寸差了 3 倍 ——
     * A4 是 1240px / 210mm = 5.9 px/mm，"与屏幕相同"是 1534px 铺满 190mm
     * 左右的屏幕 ≈ 8 px/mm，但屏幕本身是 440dpi 的高密度屏，
     * 用户在屏幕上看到的 1 个单位其实很小。
     *
     * 用固定 dp 的后果：同一支笔在 A4 上画出约 2mm 的线，
     * 在"与屏幕相同"的纸上只有约 0.7mm。取短边比例能让"线与纸的关系"保持一致，
     * 而这正是眼睛判断粗细的依据。
     *
     * 比例值 0.0076 是**从原来的默认值反推出来的**：原来是 11.6px / 1534px 的屏幕纸，
     * 所以"与屏幕相同"的观感和 Step 4 之前完全一样，只有 A4/A3 变成物理上合理的粗细。
     */
    /**
     * 笔宽占文档短边的比例。由设置界面调整（见 AppPreferences.inkWidthRatio）。
     *
     * 只在**新写的笔画**上生效：每一笔的宽度在收笔时就烘焙进几何并存进 .ink 了，
     * 所以改这个值不会动到已经写好的内容 —— 这是当初"逐笔存宽度"的直接收益。
     */
    /**
     * 钢笔的笔刷参数（四支滑块）。
     *
     * 只在**新写的笔画**上生效 —— 已有笔迹的几何早就烘好了，
     * 改参数不会让它们重算。这既是性能考虑，也是正确性考虑：
     * 用户不该因为调了一下灵敏度就发现以前的笔记变了样。
     */
    var brushParams: BrushParams = BrushParams.DEFAULT

    /**
     * 荧光笔的宽度比例（已含荧光笔倍率）和不透明度。
     * 和钢笔完全独立 —— 荧光笔不共用那四支滑块。
     */
    var highlighterWidthRatio: Float = InkWidthSteps.ratioAt(InkWidthSteps.DEFAULT_INDEX) *
        InkWidthSteps.HIGHLIGHTER_MULTIPLIER
    var highlighterOpacity: Float = InkWidthSteps.HIGHLIGHTER_ALPHA

    private val eraserRadiusPx: Float get() = documentShortSide * ERASER_RADIUS_RATIO

    /**
     * 把颜色变成荧光笔用的半透明色。
     *
     * 只替换 alpha，保留 RGB —— 所以荧光笔的颜色就是当前笔色，
     * 换色后涂出来的荧光笔也跟着变，不需要单独一套"荧光笔颜色"。
     */
    private fun withHighlighterAlpha(argb: Int): Int =
        (argb and 0x00FFFFFF) or ((highlighterOpacity * 255f).toInt().coerceIn(0, 255) shl 24)

    /**
     * 页面之外那圈区域的底色。
     *
     * 之前这里是"什么都不画"，露出 Scaffold 的背景（#FDFCFF）——
     * 和白纸（#FFFFFF）几乎同色，于是**转屏留白之后完全看不出页边界在哪**。
     * 页边界是"我在纸上还是在纸外"的唯一提示，必须有明确对比。
     */
    var canvasBackgroundColor: Int = DEFAULT_CANVAS_BACKGROUND

    private val documentShortSide: Float
        get() = if (docWidth <= 0f || docHeight <= 0f) 0f else minOf(docWidth, docHeight)

    var pressureCurve: PressureCurve = PressureCurve()

    /**
     * 笔迹风格。
     *
     * 切换只影响【之后新写的笔画】—— 已有笔画的几何在收笔时就烘焙进
     * Stroke.segments 了。所以可以在同一页上先用 A 写一行、切到 B 再写一行，
     * 直接并排对比。
     */
    var strokeStyle: StrokeStyle = StrokeStyle.PRESSURE

    /** 当前工具。侧键按下时只是【临时】切到橡皮，不改这个字段 */
    var tool: InkTool = InkTool.PEN
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 翻页方向。由设置界面决定（见 domain/PageTurnMode）。
     *
     * 只影响手指滑动翻页的方向判定；笔的书写和它无关。
     */
    var pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL

    /**
     * 手指左右/上下滑动翻页的回调。
     *
     * 参数是 [PAGE_SWIPE_NEXT] 或 [PAGE_SWIPE_PREVIOUS]。
     * 由编辑器决定"还有没有下一页/上一页"，View 只负责识别手势。
     */
    var onPageSwipe: ((direction: Int) -> Unit)? = null

    // ---- 滑动手势状态 ----
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false
    private val swipeTriggerPx: Float get() = SWIPE_TRIGGER_DP * resources.displayMetrics.density

    /**
     * 笔侧键按住时临时切橡皮。
     *
     * 这支 Lenovo Tab Pen Plus 的 digitizer 上报 BTN_STYLUS / BTN_STYLUS2，
     * Android 会映射成 MotionEvent.BUTTON_STYLUS_PRIMARY / SECONDARY，
     * 所以是纯标准 MotionEvent 实现，不需要任何厂商 SDK。
     */
    var sideButtonErases: Boolean = true

    /** 只接受手写笔。Step 6 会把它做成用户可切换的设置 */
    var stylusOnly: Boolean = true

    /**
     * 历史变化回调：(总笔数, 可撤销, 可重做)
     *
     * 只在用户操作（落笔 / 擦除 / 撤销 / 重做 / 清空）时触发，不在 [setStrokes] 里触发 ——
     * setStrokes 是初始化，通常在 Compose 的组合过程中被调用，
     * 在回调里改 Compose state 会撞上"组合期间写 state"。
     */
    var onHistoryChanged: ((strokeCount: Int, canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

    /** 每笔写完的实测统计，用于压感曲线标定 */
    var onStrokeCommitted: ((stats: StrokeStats) -> Unit)? = null

    /** 一次擦除手势结束，报告擦掉了几笔 */
    var onErased: ((erasedCount: Int) -> Unit)? = null

    /**
     * 每次 ACTION_DOWN 的输入诊断：(toolType, source, buttonState)。
     *
     * 侧键在不同 ROM 上的上报方式差异很大 —— 有的塞进 MotionEvent.buttonState，
     * 有的只发 KeyEvent，有的干脆被系统截走。没有这个探针就只能靠猜。
     * 它是 Step 6 那个"笔迹信息"开关的前身。
     */
    var onInputProbe: ((toolType: Int, source: Int, buttonState: Int) -> Unit)? = null

    // ======================================================================
    // 文档坐标系
    // ======================================================================

    /** 文档尺寸（px）。第一次布局时锁定，之后不再变 */
    private var docWidth = 0f
    private var docHeight = 0f

    /**
     * 视图变换。
     *
     * ============================ 为什么从三个字段抽成一个对象 ============================
     * 这套换算原先就是下面那三个内联字段。对"把整页塞进视图"这一种用法，内联够了。
     *
     * 但无缝滚动和无限画布需要它变成一个**能自由平移缩放的窗口**，
     * 还要能算出"当前可见的是文档的哪一块"（视口裁剪）。
     * 抽出来之后这些能力都在 [CanvasViewport] 里，而且那里的数学是纯的、
     * 可以单元测试 —— 见 CanvasViewportTest，其中一条专门把重构前的公式逐位钉死。
     *
     * 当前**只有 fit 一种用法**，所以行为和重构之前完全一致。
     */
    private val viewport = CanvasViewport()

    // 这三个名字保留着，让下面几百行渲染、命中测试、悬停光标的代码一行都不用改。
    // 它们现在是视口的只读投影。
    private val viewScale: Float get() = viewport.scale
    private val viewOffsetX: Float get() = viewport.offsetX
    private val viewOffsetY: Float get() = viewport.offsetY

    /** 网格间距（px），按密度换算一次 */
    private var stepPx = 0f

    /**
     * ============================ 为什么要两块位图 ============================
     * 荧光笔必须画在钢笔**下面**：涂在已有的字上时不该把字盖住，
     * 而应该像真的荧光笔一样透出下面的字。
     *
     * 单个位图做不到这件事 —— 位图一旦画上去就没有"层次"了。
     * 而"荧光笔来时整页重画一遍"也不可行：一页几百笔时，
     * 每落一个采样点就重放全部笔迹，书写会直接卡死。
     *
     * 所以分两层：下层是纸 + 荧光笔，上层是钢笔。绘制时先贴下层再贴上层。
     * 正在写的笔画按工具画进对应的那一层 —— 由于下层总是先贴，
     * 后画的荧光笔天然就落在已有的字下面，不需要任何额外处理。
     *
     * 代价是位图内存翻倍（A4 8.7MB → 17.4MB，A3 → 34.8MB）。可以接受。
     */
    private var underBitmap: Bitmap? = null
    private var underCanvas: Canvas? = null

    /** 上层：只有钢笔笔迹，背景透明 */
    private var overBitmap: Bitmap? = null
    private var overCanvas: Canvas? = null

    // ======================================================================
    // 笔迹与历史
    // ======================================================================

    private val strokes = ArrayList<Stroke>()
    private val history = ArrayList<InkEdit>()
    private val redoHistory = ArrayList<InkEdit>()

    // ---- 画线状态 ----
    private var builder: StrokeBuilder? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN
    private var activePressureMin = Float.MAX_VALUE
    private var activePressureMax = -Float.MAX_VALUE
    private var activeStartUptime = 0L

    /**
     * 平滑后的压力。数字笔的原始压力是有噪声的（12 位量化 + 手掌压力干扰），
     * 直接映射成宽度会让笔画边缘出现肉眼可见的抖动。
     * 一阶低通（EMA）用几乎没有成本的代价把这个抖动压掉。
     */
    private var smoothedPressure = 0f
    private var hasSmoothedPressure = false

    /**
     * 是否有待处理的整页重画。
     *
     * 见 [requestFullRender] —— 这个标志位是"数据与屏幕一致"的结构性保证。
     */
    private var pendingFullRender = false

    // ---- 橡皮状态 ----
    private var erasing = false
    /** 本次擦除手势开始时笔迹的排列顺序，用来记录每笔的原始下标 */
    private var eraseOrder: List<Stroke>? = null
    private val eraseRemoved = ArrayList<Pair<Int, Stroke>>()

    // ---- 悬停光标 ----
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverVisible = false
    private var hoverSideButton = false

    /** 上次上报过的按键状态，用来发现"书写过程中按键状态变化" */
    private var lastProbeButtonState = -1

    // ---- 手势中最后已知的位置 ----
    // 用途是"书写中途换工具"：那个时刻没有现成的 MotionEvent 可用，
    // 只能拿最后处理过的采样点来接续，否则新工具会从 (0,0) 或上一次的位置开始。
    private var gestureX = 0f
    private var gestureY = 0f
    private var gesturePressure = 0.6f
    private var gestureTime = 0L

    /** 本次手势开始时用的工具，用来检测"手势进行中工具被切换了" */
    private var gestureStartTool = InkTool.PEN

    // ======================================================================
    // 画笔
    // ======================================================================

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    /** 贴位图用的画笔。必须开双线性过滤，否则缩放留边时笔迹会有锯齿 */
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * 视图坐标 → 文档坐标的逆变换矩阵。复用一个实例，避免每次触摸都分配。
     * @see toDocumentSpace
     */
    private val inverseMatrix = Matrix()

    // 光标画两圈：外圈浅色、内圈深色。这样在白纸和深色纸上都能看清
    private val cursorHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = CURSOR_HALO_COLOR
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = CURSOR_LINE_COLOR
    }

    // ======================================================================
    // 对外查询
    // ======================================================================

    val strokeCount: Int get() = strokes.size

    val canUndo: Boolean get() = history.isNotEmpty()

    val canRedo: Boolean get() = redoHistory.isNotEmpty()

    // ======================================================================
    // 对外操作
    // ======================================================================

    /**
     * 载入已有笔迹。进入笔记时调用一次。
     *
     * 刻意不触发 [onHistoryChanged]：调用方（Compose 的 AndroidView factory）
     * 是在组合过程中调它，回调里改 state 会出问题。
     * 初始的可撤销状态由调用方根据传入的列表自行计算。
     */
    fun setStrokes(newStrokes: List<Stroke>) {
        strokes.clear()
        strokes.addAll(newStrokes)
        history.clear()
        redoHistory.clear()
        requestFullRender()
    }

    fun undo() {
        if (history.isEmpty()) return
        val edit = history.removeAt(history.size - 1)
        revert(edit)
        redoHistory.add(edit)
        requestFullRender()
        notifyHistory()
    }

    fun redo() {
        if (redoHistory.isEmpty()) return
        val edit = redoHistory.removeAt(redoHistory.size - 1)
        apply(edit)
        history.add(edit)
        requestFullRender()
        notifyHistory()
    }

    private fun revert(edit: InkEdit) {
        when (edit) {
            is InkEdit.AddStroke -> strokes.remove(edit.stroke)
            is InkEdit.EraseStrokes -> {
                // 升序插回原位：先插小下标，大下标才不会被挤偏
                for ((index, stroke) in edit.removed.sortedBy { it.first }) {
                    strokes.add(index.coerceIn(0, strokes.size), stroke)
                }
            }
        }
    }

    private fun apply(edit: InkEdit) {
        when (edit) {
            is InkEdit.AddStroke -> strokes.add(edit.stroke)
            is InkEdit.EraseStrokes -> edit.removed.forEach { (_, stroke) -> strokes.remove(stroke) }
        }
    }

    /**
     * 清空全部笔迹。
     *
     * 刻意做成【不可撤销】的：清空是破坏性操作，调用方必须先弹确认框。
     * 如果让它可撤销，就得把整页笔迹和此前的全部编辑历史一起管理，
     * 复杂度远大于收益。
     */
    fun clearAll() {
        if (strokes.isEmpty()) return
        strokes.clear()
        history.clear()
        redoHistory.clear()
        requestFullRender()
        notifyHistory()
    }

    /**
     * 当前笔迹的快照（副本）。
     * 调用方拿到后可以安全持有，不会被后续落笔影响 ——
     * 保存到仓库时必须是副本，否则仓库里存的会是同一个可变列表。
     */
    fun snapshotStrokes(): List<Stroke> = ArrayList(strokes)

    /** 文档坐标系宽度（px）。持久化时要存下来，载入时才能对齐。 */
    val documentWidth: Float get() = docWidth

    /** 文档坐标系高度（px） */
    val documentHeight: Float get() = docHeight

    /**
     * 预设文档尺寸（从已保存的页面读出来的）。
     *
     * **必须在第一次布局之前调用** —— 布局之后文档坐标系就锁定了。
     * 不预设的话，同一个页面在横屏存、竖屏开就会错位：
     * 笔迹坐标是相对文档的，而文档尺寸会被新视图的尺寸重新定义。
     */
    fun setDocumentSize(width: Float, height: Float) {
        if (docWidth > 0f) return
        if (width <= 0f || height <= 0f) return
        docWidth = width
        docHeight = height
    }

    // ======================================================================
    // 尺寸 / 生命周期
    // ======================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // stepPx 必须在这一帧重画之前算好，因为它要拿去画网格
        stepPx = GRID_STEP_DP * resources.displayMetrics.density

        // pageBitmap 为 null 才分配。用 docWidth 判断是不行的：
        // 载入已保存的页面时 docWidth 已经被 setDocumentSize 预设过了，
        // 那样位图就永远不会被创建。
        if (underBitmap == null) {
            if (docWidth <= 0f) {
                // 第一次拿到真实尺寸：锁定文档坐标系。此时缩放为 1、无留边，
                // 所以首屏看起来就是"铺满整屏的一张纸"
                docWidth = w.toFloat()
                docHeight = h.toFloat()
            }
            allocatePageBitmap()
        }

        updateTransform()
        // 尺寸变了要按新的缩放重画（笔迹坐标不变，只是贴图缩放变了）
        requestFullRender()
    }

    /**
     * 分配位图。**只分配下层**。
     *
     * ============================ 上层为什么按需分配 ============================
     * 上层只放钢笔。但**页面上没有荧光笔时根本不需要分层** ——
     * 钢笔直接画在下层（纸的上面）结果完全一样，因为它不会和任何东西抢层次。
     *
     * 一张整页位图在"与屏幕相同"纸型下约 12MB，A3 约 17MB。
     * 原来固定分两块，等于每页白扔一块；无缝滚动同时要活两三页，
     * 那一块就是 25~35MB 的纯浪费。
     *
     * 代价是"第一笔荧光笔落笔时"要补一次分层：见 [ensureOverLayer]。
     */
    private fun allocatePageBitmap() {
        val w = docWidth.toInt().coerceAtLeast(1)
        val h = docHeight.toInt().coerceAtLeast(1)

        val oldUnder = underBitmap
        val oldOver = overBitmap

        val under = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        underBitmap = under
        underCanvas = Canvas(under)

        // 上层此刻不分配，等真的出现荧光笔再说（见 allocateOverLayer）
        overBitmap = null
        overCanvas = null

        // 旧图没有别处引用，立刻回收，不必等 GC
        oldUnder?.recycle()
        oldOver?.recycle()
    }

    /** 上层是否存在 */
    private val hasOverLayer: Boolean get() = overCanvas != null

    /**
     * 确保上层存在。
     *
     * 由 [renderPage] 在"页面上确实有荧光笔"时调用，以及第一笔荧光笔落笔时调用。
     * 分配之后必须**整页重画**（把已有的钢笔搬上去），调用方负责这一点。
     */
    private fun allocateOverLayer(): Boolean {
        if (overCanvas != null) return false
        val w = docWidth.toInt().coerceAtLeast(1)
        val h = docHeight.toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        overBitmap = bmp
        overCanvas = Canvas(bmp)
        return true
    }

    /**
     * 没有荧光笔了就释放上层，把那块内存还给系统。
     *
     * 触发场景：撤销掉最后几笔荧光笔、或者擦掉它们。
     * 不做这一步的话，内存只增不减 —— 用户写一笔荧光笔再撤销，
     * 那 12MB 就永远占着。
     */
    private fun releaseOverLayerIfUnused() {
        if (overCanvas == null) return
        if (strokes.any { it.isHighlighter }) return
        overBitmap?.recycle()
        overBitmap = null
        overCanvas = null
    }

    /**
     * 把整页等比居中放进视图。
     *
     * 具体算式搬到了 [CanvasViewport.fit]（并且在那里被单元测试逐位钉住）。
     * 这里只负责把当前的文档尺寸和视图尺寸交给它。
     */
    private fun updateTransform() {
        viewport.fit(docWidth, docHeight, width, height)
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 笔迹列表被改过就先重画位图，再贴上来。
        // 放在这里而不是散在各个改动点，理由见 requestFullRender 的注释。
        if (pendingFullRender) {
            pendingFullRender = false
            renderPage()
        }

        val under = underBitmap ?: return

        // 先把页面之外的区域铺一层对比色，页边界才看得见
        canvas.drawColor(canvasBackgroundColor)

        canvas.save()
        canvas.translate(viewOffsetX, viewOffsetY)
        canvas.scale(viewScale, viewScale)
        // 贴图顺序就是层次：纸 + 荧光笔在下，钢笔在上。
        // "荧光笔不盖住字"完全由这个顺序保证，不需要混合模式或透明度魔法。
        canvas.drawBitmap(under, 0f, 0f, bitmapPaint)
        // 上层按需分配：页面上没有荧光笔时它是 null，钢笔已经在下层了
        overBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        canvas.restore()

        // 光标画在变换【之外】（它属于界面反馈而不是文档内容），
        // 所以要把文档坐标换回视图坐标再画 —— 半径同理，
        // 橡皮半径是文档空间的量，乘上缩放才是屏幕上的像素数。
        if (hoverVisible && cursorShouldShow()) {
            val viewX = hoverX * viewScale + viewOffsetX
            val viewY = hoverY * viewScale + viewOffsetY
            val r = eraserRadiusPx * viewScale
            canvas.drawCircle(viewX, viewY, r, cursorHaloPaint)
            canvas.drawCircle(viewX, viewY, r, cursorPaint)
        }
    }

    private fun cursorShouldShow(): Boolean =
        tool == InkTool.ERASER || (sideButtonErases && hoverSideButton)

    private fun drawPaper(canvas: Canvas) {
        paperPaint.color = paperColor
        canvas.drawRect(0f, 0f, docWidth, docHeight, paperPaint)

        if (stepPx <= 0f) return
        when (paperStyle) {
            PaperStyle.BLANK -> Unit

            PaperStyle.GRID -> {
                gridPaint.color = GRID_LINE_COLOR
                var x = stepPx
                while (x < docWidth) {
                    canvas.drawLine(x, 0f, x, docHeight, gridPaint)
                    x += stepPx
                }
                var y = stepPx
                while (y < docHeight) {
                    canvas.drawLine(0f, y, docWidth, y, gridPaint)
                    y += stepPx
                }
            }

            PaperStyle.RULED -> {
                gridPaint.color = RULED_LINE_COLOR
                val rowHeight = stepPx * 2f
                var y = rowHeight
                while (y < docHeight) {
                    canvas.drawLine(0f, y, docWidth, y, gridPaint)
                    y += rowHeight
                }
            }
        }
    }

    /**
     * 请求一次整页重画。
     *
     * ============================ 为什么用标志位而不是直接重画 ============================
     * 之前是"每个改动笔迹列表的地方自己调一次重画函数"。这个约定已经漏了三次：
     *   · endErase 漏了抬手那一个采样点 → 擦掉的笔要到下次擦除才消失
     *   · 手势中途切工具时漏了起擦那一下 → 同样的症状
     *   · beginErase 起擦即命中时也漏了
     * 症状完全一样：**数据变了、屏幕还是旧的**，而且极难定位 ——
     * 因为出问题的地方和写错的地方隔了好几个调用层级。
     *
     * 改成"标志位 + onDraw 里统一处理"之后，只要列表变了就一定会重画 ——
     * 这是结构性保证，不再依赖每个调用点记得。
     * 顺带还有个好处：一次事件里擦掉十笔也只在下一帧重画一次。
     */
    private fun requestFullRender() {
        pendingFullRender = true
        invalidate()
    }

    /**
     * 真正执行整页重画：清空 → 铺纸 → 重放全部笔迹。
     * 重放用的是 Stroke.segments（收笔时算好的缓存），
     * 不重跑平滑和压感映射 —— 必须是纯 drawLine，撤销才不会卡。
     *
     * 刻意不在这里 invalidate：它由 onDraw 自己调用，
     * 在绘制过程中再请求一次重绘只会白白多画一帧。
     */
    private fun renderPage() {
        val under = underCanvas ?: return

        // 页面上确实有荧光笔才需要分层。
        // 没有的话把上层放掉 —— 用户撤掉最后几笔荧光笔之后，
        // 那 12~17MB 应该还给系统，而不是一直占着（只增不减的内存最要命）。
        val needsOver = strokes.any { it.isHighlighter }
        if (needsOver) {
            allocateOverLayer()
        } else {
            releaseOverLayerIfUnused()
        }

        under.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        overCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawPaper(under)

        for (i in strokes.indices) {
            val stroke = strokes[i]
            InkRenderer.drawStroke(
                canvas = if (stroke.isHighlighter) under else (overCanvas ?: under),
                stroke = stroke,
                paint = inkPaint,
            )
        }

        // 正在写的那一笔也要重画。
        // 少了这一步，重画会把位图清空却只重建已提交的笔画 ——
        // 屏幕上那一笔会闪一下然后缺一截。
        builder?.let { b ->
            val live = b.accumulatedSegments()
            if (live.isNotEmpty()) {
                val target = activeLayerCanvas() ?: return@let
                InkRenderer.applyStyle(inkPaint, activeStrokeColor())
                InkRenderer.drawSegments(target, live, inkPaint)
            }
        }
    }

    /**
     * 把整个 MotionEvent 从【视图坐标】变换到【文档坐标】。
     *
     * ============================ 这个函数补的是一个长期潜伏的 bug ============================
     * Step 2~4 里所有触摸处理都直接用 event.getX()/getY() 当文档坐标，
     * 从来没有做过视图→文档的变换。之所以一直没暴露，是因为那时的纸型是
     * "与屏幕相同" —— docWidth 恰好等于 viewWidth，于是
     * viewScale 恒为 1、viewOffset 恒为 0，**两个坐标系完全重合**。
     *
     * Step 5 引入 A4/A3 之后，一页 1240×1754 的纸要塞进 3040×1534 的绘图区：
     * 缩放变成 0.875、横向偏移 978px。这时不换算就会：落笔点整体右移、笔迹被压扁。
     *
     * 用 MotionEvent.transform（而不是在每个取样点手动减偏移除缩放）的好处是
     * **一次处理干净**：所有指针、所有历史采样点、悬停事件都被同一矩阵变换过，
     * 下面几百行代码不用改一行，也不会漏掉某个分支。
     */
    private fun toDocumentSpace(event: MotionEvent) {
        if (viewScale == 1f && viewOffsetX == 0f && viewOffsetY == 0f) return
        inverseMatrix.reset()
        // 视图 → 文档：(v - offset) / scale
        inverseMatrix.postTranslate(-viewOffsetX, -viewOffsetY)
        inverseMatrix.postScale(1f / viewScale, 1f / viewScale)
        event.transform(inverseMatrix)
    }

    // ======================================================================
    // 触摸
    // ======================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex

        // ============================ 分流必须在坐标变换之前 ============================
        // 手指的事件不是绘制输入，走左右滑动翻页那条路径。
        //
        // ⚠️ 顺序很关键：toDocumentSpace 会【就地】把 event 的坐标改成文档坐标。
        // 如果先变换再判断不属于自己，翻页手势看到的位移就变成了"文档单位"，
        // 在缩放不为 1 的纸型上阈值会跟着变，父层拿到的坐标也已经被改坏了。
        // 判定这一串事件归谁，必须在动它之前完成。
        //
        // 另外：已经在画一笔时（builder != null）即便来的是手指也不分流 ——
        // 那是手掌侧面按上来的 ACTION_POINTER_DOWN，下面会忽略它。
        if (!isAcceptedTool(event, index) && builder == null && !erasing) {
            return handleFingerSwipe(event)
        }

        toDocumentSpace(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                if (!isAcceptedTool(event, index)) return false
                // 关掉输入批处理：手写跟手感的命门
                requestUnbufferedDispatch(event)

                // 落笔时光标必须消失。原因有两个：
                //   1. 此时笔尖就在屏幕上，再画一个圈是多余的；
                //   2. 落笔期间系统不再发 hover 事件，光标会【冻在】上一次悬停的位置，
                //      看起来像卡住了。
                hoverVisible = false
                lastProbeButtonState = event.buttonState
                onInputProbe?.invoke(event.getToolType(index), event.source, event.buttonState)

                // 侧键在 DOWN 时读一次。绝大多数设备（包括这支笔）
                // 只在按下瞬间上报按键状态，中途变化不会补发，
                // 所以"先按住侧键再落笔"才是可靠用法。
                val sideButton = sideButtonErases && hasStylusButton(event)
                erasing = tool == InkTool.ERASER || sideButton

                if (erasing) beginErase(event, index) else beginStroke(event, index)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true

                // 书写过程中按键状态变了也上报一次。
                // 这能区分"ROM 根本不报侧键"和"只在落笔瞬间报一次" ——
                // 两者的修法完全不同，不测出来就只能瞎猜。
                if (event.buttonState != lastProbeButtonState) {
                    lastProbeButtonState = event.buttonState
                    onInputProbe?.invoke(event.getToolType(index), event.source, event.buttonState)
                }

                // 手势进行中工具可能被换掉了（按了笔侧键、或误触工具栏）。
                // 换掉就当场接管，否则会出现"工具栏显示橡皮、画布还在画线"。
                if (syncToolWithActiveGesture(event, index)) {
                    // 切换时已经用当前事件的位置起过一笔/擦了一次，
                    // 不要再走下面的正常分支处理同一个事件
                    return true
                }

                if (erasing) {
                    // 不用在这里重画：eraseAt 擦掉东西时会 requestFullRender，
                    // 标志位保证一次事件里擦掉多笔也只在下一帧重画一次
                    eraseAlong(event, index)
                    return true
                }

                val b = builder ?: return false
                appendHistorical(event, index, b)
                appendPoint(
                    x = event.getX(index),
                    y = event.getY(index),
                    pressure = pressureOf(event, index),
                    timeMillis = event.eventTime,
                    b = b,
                )
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(activePointerId)
                if (erasing) {
                    if (index >= 0) eraseAlong(event, index)
                    endErase()
                    return true
                }
                val b = builder ?: return false
                if (index >= 0) {
                    appendHistorical(event, index, b)
                    appendPoint(
                        x = event.getX(index),
                        y = event.getY(index),
                        pressure = pressureOf(event, index),
                        timeMillis = event.eventTime,
                        b = b,
                    )
                }
                endStroke(b)
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (erasing) {
                    endErase()
                    return true
                }
                // 被系统打断（来电、手势接管）。已经画出来的部分保留下来，
                // 总比整笔突然消失更符合直觉。
                val b = builder ?: return false
                endStroke(b)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                // 已经有一笔在画时来了第二根手指 / 第二个指针：
                // 刻意忽略，不切换指针也不打断笔迹。手掌侧边按上来时走的就是这个路径。
                return builder != null || erasing
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isAcceptedTool(event: MotionEvent, index: Int): Boolean {
        if (index < 0 || index >= event.pointerCount) return false
        val toolType = event.getToolType(index)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return true
        }
        // 兜底：部分设备和 adb 注入的事件只设 source、不设 toolType
        if (event.isFromSource(InputDevice.SOURCE_STYLUS)) return true
        if (stylusOnly) return false
        return true
    }

    /**
     * 手指左右滑动翻页。
     *
     * ============================ 为什么放在 View 里而不是用 Compose ============================
     * 本来更"干净"的做法是让画布返回 false、把事件让给 Compose 的 pointerInput。
     * 但那条路依赖 Compose 与 AndroidView 的事件分发细节（谁先拿到、返回值怎么传导），
     * 一旦不成立就是"滑动没反应"这种没有报错的故障，很难查。
     * 画布本来就收到了全部事件，自己判断更可靠。
     *
     * ============================ 阈值为什么这么严 ============================
     * 手指在画布上滑动和"手掌蹭过屏幕"在事件层面是同一件事。
     * 好消息是系统在笔接触屏幕时会抑制触摸输入（ZUI 的 stylus gesture mode），
     * 写字时的掌侧根本到不了这里；剩下要防的是"没在写字时手掌平移"。
     * 所以要求横向位移超过 [SWIPE_TRIGGER_DP]，且横向明显大于纵向 ——
     * 上下蹭不会误翻页，斜着划也很难凑够比例。
     */
    private fun handleFingerSwipe(event: MotionEvent): Boolean {
        val trigger = onPageSwipe ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_FINGER) {
                    return false
                }
                swipeStartX = event.x
                swipeStartY = event.y
                swipeTracking = true
                // 必须返回 true，否则后续的 MOVE/UP 不会送到这个 View
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!swipeTracking) return true
                val dx = event.x - swipeStartX
                val dy = event.y - swipeStartY

                // 只认设置里选的那个方向：
                //  · 左右翻页 → 看横向位移
                //  · 上下翻页 → 看纵向位移
                // 另一个方向的位移只用来做"必须明显更大"的判定 ——
                // 斜着划不能触发翻页。
                val horizontal = pageTurnMode == PageTurnMode.HORIZONTAL
                val main = if (horizontal) dx else dy
                val cross = if (horizontal) dy else dx

                if (abs(main) >= swipeTriggerPx && abs(main) > abs(cross) * SWIPE_ASPECT_RATIO) {
                    // 一次手势只翻一页：翻完就停止跟踪，否则手指继续移动会连翻好几页
                    swipeTracking = false
                    // 位移为负（向左滑 / 向上滑）都是"往后翻"，
                    // 这和阅读器的直觉一致：把内容往上一推就是下一页。
                    trigger(if (main < 0f) PAGE_SWIPE_NEXT else PAGE_SWIPE_PREVIOUS)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeTracking = false
                return true
            }
        }
        return false
    }

    /**
     * 侧键是否按下（标准通道）。
     *
     * 这是 AOSP 的标准行为：笔尖 digitizer 把侧键折进 MotionEvent.buttonState。
     * 但这支笔的 digitizer 声明了 BTN_STYLUS 却从不上报，所以在这台设备上
     * 这条永远为 false —— 侧键改由 MainActivity 从蓝牙按键设备接收，
     * 以"单击切换工具"的形式实现（见 StylusButtonState 的注释）。
     *
     * 保留这条判断是为了通用性：换一支通过标准通道上报侧键的笔时，
     * "按住临时切橡皮"会自动生效，不需要改代码。
     */
    private fun hasStylusButton(event: MotionEvent): Boolean {
        val buttons = event.buttonState
        return (buttons and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
    }

    // ======================================================================
    // 画线
    // ======================================================================

    private fun beginStroke(event: MotionEvent, index: Int) {
        activePointerId = event.getPointerId(index)
        activeToolType = event.getToolType(index)
        startStrokeAt(
            x = event.getX(index),
            y = event.getY(index),
            pressure = pressureOf(event, index),
            timeMillis = event.eventTime,
        )
    }

    /**
     * 从给定点起笔。
     * 和 [beginStroke] 分开是为了"书写中途换工具"能复用同一套起笔逻辑 ——
     * 否则那段逻辑会被复制成两份，早晚有一份忘了改。
     */
    private fun startStrokeAt(x: Float, y: Float, pressure: Float, timeMillis: Long) {
        activePressureMin = Float.MAX_VALUE
        activePressureMax = -Float.MAX_VALUE
        activeStartUptime = SystemClock.uptimeMillis()
        hasSmoothedPressure = false
        gestureStartTool = tool
        rememberGesturePoint(x, y, pressure, timeMillis)

        // 工具在这一刻定下来，整笔不变
        activeIsHighlighter = tool == InkTool.HIGHLIGHTER

        // 页面上第一笔荧光笔：这时才需要分层。
        // 已有的钢笔此刻还和纸挤在下层，必须整页重画把它们搬到上层，
        // 否则这一笔荧光笔会盖在它们**上面** —— 正好是荧光笔要避免的事。
        //
        // 在落第一个点**之前**做：此时这一笔还没有任何线段，
        // 重画不会丢掉进行中的内容（renderPage 也会补画 builder 的线段，双保险）。
        if (activeIsHighlighter && allocateOverLayer()) {
            requestFullRender()
        }

        val b = if (activeIsHighlighter) {
            newHighlighterBuilder(
                widthPx = documentShortSide * highlighterWidthRatio,
                // 荧光笔也跟平滑度走：采样过粗会让宽色带出现可见的多边形棱角
                sampleSpacingPx = brushParams.sampleSpacingPx,
            )
        } else {
            brushParams.newPenBuilder(widthPx = documentShortSide * brushParams.baseWidthRatio)
        }
        builder = b
        // 第一个点还构不成曲线，不产生线段
        appendPoint(x, y, pressure, timeMillis, b)
    }

    /**
     * 回放历史采样点。
     *
     * 系统为了省电会把一次触摸里的多个采样攒进 MotionEvent 的 historical 数组。
     * 高刷屏上 ACTION_MOVE 每帧往往只带一两个"当前点"，不回放历史点的话
     * 曲线会退化成折线 —— 这是手写平滑最容易被忽略的一环。
     */
    private fun appendHistorical(event: MotionEvent, index: Int, b: StrokeBuilder) {
        val historySize = event.historySize
        if (historySize <= 0) return
        for (h in 0 until historySize) {
            appendPoint(
                x = event.getHistoricalX(index, h),
                y = event.getHistoricalY(index, h),
                pressure = clampPressure(event.getHistoricalPressure(index, h)),
                // 历史点也有自己的时间戳，速度调制必须用它而不是当前事件时间，
                // 否则同一批历史点会被算成 dt=0，速度直接失真
                timeMillis = event.getHistoricalEventTime(h),
                b = b,
            )
        }
    }

    private fun appendPoint(x: Float, y: Float, pressure: Float, timeMillis: Long, b: StrokeBuilder) {
        rememberGesturePoint(x, y, pressure, timeMillis)

        // 统计用【原始】压力：标定曲线时要看的是硬件的真实输出，
        // 如果这里记平滑后的值，就永远调不准 —— 平滑会把峰值削掉。
        if (pressure < activePressureMin) activePressureMin = pressure
        if (pressure > activePressureMax) activePressureMax = pressure

        // 渲染用【平滑后】压力：去掉噪声，笔画边缘才干净
        val renderPressure = smoothPressure(pressure)

        val newSegments = b.add(InkPoint(x, y, renderPressure, timeMillis))
        if (newSegments.isEmpty()) return

        // 增量落图：只画这一批新线段，不碰已有内容。
        // 画进哪一层由 activeIsHighlighter 决定 —— 荧光笔进下层，
        // 因为下层总是先被贴上去，所以后画的荧光笔天然落在已有的字下面。
        val canvas = activeLayerCanvas() ?: return
        InkRenderer.applyStyle(inkPaint, activeStrokeColor())
        InkRenderer.drawSegments(canvas, newSegments, inkPaint)
    }

    /**
     * 一阶低通滤波。
     *
     * alpha 越小越平滑，但滞后越大。0.45 大约是 1~2 个采样点的滞后
     * （240Hz 下不到 10ms），手感上察觉不到，而噪声能压掉大半。
     * 刻意不用滑动平均窗口：窗口要保存历史、有额外内存和遍历成本，
     * 而一阶低通是 O(1)、零分配。
     */
    private fun smoothPressure(raw: Float): Float {
        if (!hasSmoothedPressure) {
            smoothedPressure = raw
            hasSmoothedPressure = true
            return raw
        }
        smoothedPressure += brushParams.pressureSmoothing * (raw - smoothedPressure)
        return smoothedPressure
    }

    private fun endStroke(b: StrokeBuilder) {
        builder = null
        activePointerId = MotionEvent.INVALID_POINTER_ID

        // 这一笔的颜色和层次在收笔前就要取出来：下面会把 activeIsHighlighter 之外
        // 的状态清掉，取晚了就可能和这一笔对不上
        val color = activeStrokeColor()
        val highlighter = activeIsHighlighter

        // 补上收笔的那一小截，否则笔锋会"断头"
        val tail = b.finish()
        if (tail.isNotEmpty()) {
            activeLayerCanvas()?.let { canvas ->
                InkRenderer.applyStyle(inkPaint, color)
                InkRenderer.drawSegments(canvas, tail, inkPaint)
            }
        }

        val stroke = b.build(color, highlighter)
        strokes.add(stroke)
        history.add(InkEdit.AddStroke(stroke))
        // 新操作让原来的"重做"失效，这是所有编辑器的标准语义
        redoHistory.clear()
        notifyHistory()
        onStrokeCommitted?.invoke(buildStats(stroke))
    }

    /**
     * 从已经算好的几何里统计这一段笔迹的实测数据。
     * 宽度的最值直接取线段宽度，而不是拿压力再算一遍 ——
     * 这样统计的就是"屏幕上是多少像素"，标定时不会有两套数字对不上。
     */
    private fun buildStats(stroke: Stroke): StrokeStats {
        var widthMin = Float.MAX_VALUE
        var widthMax = 0f
        for (i in stroke.segments.indices) {
            val w = stroke.segments[i].width
            if (w < widthMin) widthMin = w
            if (w > widthMax) widthMax = w
        }
        if (widthMin == Float.MAX_VALUE) widthMin = 0f

        return StrokeStats(
            pointCount = stroke.points.size,
            segmentCount = stroke.segments.size,
            pressureMin = if (activePressureMin == Float.MAX_VALUE) 0f else activePressureMin,
            pressureMax = if (activePressureMax == -Float.MAX_VALUE) 0f else activePressureMax,
            widthMinPx = widthMin,
            widthMaxPx = widthMax,
            toolType = activeToolType,
            durationMillis = SystemClock.uptimeMillis() - activeStartUptime,
        )
    }

    private fun pressureOf(event: MotionEvent, index: Int): Float =
        clampPressure(event.getPressure(index))

    private fun clampPressure(raw: Float): Float =
        if (raw.isFinite()) raw.coerceIn(0f, 1f) else 1f

    // ======================================================================
    // 橡皮
    // ======================================================================

    private fun beginErase(event: MotionEvent, index: Int) {
        activePointerId = event.getPointerId(index)
        startEraseAt(event.getX(index), event.getY(index))
    }

    /** 从给定点开始擦除。同样是为了"中途换工具"能复用 */
    private fun startEraseAt(x: Float, y: Float) {
        erasing = true
        gestureStartTool = tool
        eraseOrder = ArrayList(strokes)
        eraseRemoved.clear()
        // 橡皮不测压力；这里给个中间值，万一中途切回钢笔，起笔宽度不至于极端
        rememberGesturePoint(x, y, MID_GESTURE_PRESSURE, SystemClock.uptimeMillis())
        eraseAt(x, y)
    }

    private fun rememberGesturePoint(x: Float, y: Float, pressure: Float, timeMillis: Long) {
        gestureX = x
        gestureY = y
        gesturePressure = pressure
        gestureTime = timeMillis
    }

    /**
     * 手势进行中工具被切换时立刻接管。
     *
     * 放在 ACTION_MOVE 里而不是 [tool] 的 setter 里，是有原因的：
     * setter 会被 Compose 的 AndroidView update 回调调用，那是在组合阶段；
     * 而接管过程要调 endStroke / endErase，它们会触发 onHistoryChanged
     * 去写 Compose state —— 那就是"组合期间写 state"。
     * 触摸分发里做这件事则完全安全。
     *
     * @return 是否发生了切换。true 表示当前事件已经被切换逻辑消费掉，
     *         调用方不要再走正常分支处理同一个事件。
     */
    private fun syncToolWithActiveGesture(event: MotionEvent, index: Int): Boolean {
        if (tool == gestureStartTool) return false

        // 留一行日志：这条路径用 adb 完全没法注入验证
        // （笔接触屏幕时触摸被 ZUI 的 stylus gesture mode 抑制、
        //   未知键码的注入事件被 Android 丢弃、sendevent 需要 root），
        // 只能靠真机按键 + logcat 确认。见 MainActivity 的 TAG InkPenKey。
        Log.d(TAG, "mid-gesture tool switch: $gestureStartTool -> $tool (erasing=$erasing)")

        if (erasing) {
            endErase()
            if (tool == InkTool.PEN) {
                startStrokeAt(
                    x = event.getX(index),
                    y = event.getY(index),
                    pressure = pressureOf(event, index),
                    timeMillis = event.eventTime,
                )
            }
        } else {
            val b = builder ?: return false
            // 已经画出来的部分保留并提交（可撤销），不是丢掉 ——
            // 悄悄抹掉用户已经写下的东西是更糟的选择
            endStroke(b)
            if (tool == InkTool.ERASER) {
                startEraseAt(event.getX(index), event.getY(index))
            }
        }

        // ⚠️ 必须把指针 id 恢复回来。
        // endStroke / endErase 都会把它置成 INVALID_POINTER_ID，
        // 而 ACTION_MOVE 的第一件事就是 findPointerIndex(activePointerId)——
        // 不恢复的话之后的每一个事件都返回 -1 被丢弃，
        // 表现就是"切换后原工具失效，新工具要抬笔才生效"。
        activePointerId = event.getPointerId(index)
        invalidate()
        return true
    }

    /** 处理一次 MotionEvent 里的全部采样点（含历史点） */
    private fun eraseAlong(event: MotionEvent, index: Int): Boolean {
        var changed = false
        for (h in 0 until event.historySize) {
            if (eraseAt(event.getHistoricalX(index, h), event.getHistoricalY(index, h))) {
                changed = true
            }
        }
        if (eraseAt(event.getX(index), event.getY(index))) changed = true
        return changed
    }

    /**
     * 擦掉笔尖覆盖到的笔迹。
     *
     * 倒序遍历：删除元素会让后面的下标前移，从后往前就不会漏检。
     * @return 是否真的擦掉了东西（调用方据此决定要不要整页重画）
     */
    private fun eraseAt(x: Float, y: Float): Boolean {
        val order = eraseOrder ?: return false
        gestureX = x
        gestureY = y
        var removedAny = false

        for (i in strokes.indices.reversed()) {
            val stroke = strokes[i]
            if (!StrokeHitTest.hits(stroke, x, y, eraserRadiusPx)) continue

            // 记原始下标：撤销时按原位置插回去，图层顺序才不会乱
            val originalIndex = order.indexOf(stroke)
            if (originalIndex >= 0) {
                eraseRemoved.add(originalIndex to stroke)
            }
            strokes.removeAt(i)
            removedAny = true
        }
        // 擦了东西就一定要重画。放在这里而不是让调用方负责 ——
        // 调用方一共有三处（起擦、滑动擦、抬手擦），之前正是漏掉了其中两处。
        if (removedAny) requestFullRender()
        return removedAny
    }

    private fun endErase() {
        erasing = false
        activePointerId = MotionEvent.INVALID_POINTER_ID

        if (eraseRemoved.isNotEmpty()) {
            // 整个手势合并成【一次】编辑记录：
            // 擦掉十笔之后一次撤销应该全部回来，而不是撤十次
            history.add(InkEdit.EraseStrokes(ArrayList(eraseRemoved)))
            redoHistory.clear()
            notifyHistory()
            onErased?.invoke(eraseRemoved.size)
        }
        eraseRemoved.clear()
        eraseOrder = null
        // 不需要显式重画：抬手那一个采样点如果擦掉了笔迹，
        // eraseAt 已经置上了重画标志（见 requestFullRender 的注释）
    }

    // ======================================================================
    // 悬停光标
    // ======================================================================

    /**
     * 需要同时实现 onHoverEvent 和 onGenericMotionEvent：
     *   · ACTION_HOVER_ENTER / EXIT 走 onHoverEvent
     *   · ACTION_HOVER_MOVE 走 onGenericMotionEvent
     * onHoverEvent 必须返回 true，否则框架不认为这个 View 是 hover 目标，
     * 后面的 hover 事件根本不会送过来。
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val index = event.actionIndex
                // 只认手写笔的悬停。手指划过屏幕不该出现橡皮光标。
                if (!isAcceptedTool(event, index)) return true
                toDocumentSpace(event)
                updateHover(event)
            }

            MotionEvent.ACTION_HOVER_EXIT -> clearHover()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_STYLUS)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    toDocumentSpace(event)
                    updateHover(event)
                    return true
                }

                MotionEvent.ACTION_HOVER_EXIT -> {
                    clearHover()
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * 保存悬停位置。
     *
     * 存的是**文档坐标**（调用方已经 [toDocumentSpace] 过），
     * 和落笔点用的是同一个坐标系 —— 这样光标才会正好落在笔尖下方。
     * 绘制时再换回视图坐标，见 onDraw。
     */
    private fun updateHover(event: MotionEvent) {
        hoverX = event.x
        hoverY = event.y
        hoverVisible = true
        // 悬停时按住侧键也应该显示橡皮光标 —— 这是"我按对了键"的唯一反馈
        hoverSideButton = sideButtonErases && hasStylusButton(event)
        invalidate()
    }

    private fun clearHover() {
        if (!hoverVisible) return
        hoverVisible = false
        hoverSideButton = false
        invalidate()
    }

    private fun notifyHistory() {
        onHistoryChanged?.invoke(strokes.size, canUndo, canRedo)
    }

    private companion object {
        const val TAG = "InkGesture"

        /** 网格间距。24dp 在平板上接近 5mm 格 */
        const val GRID_STEP_DP = 24f

        /**
         * 笔迹基础线宽占文档短边的比例（默认值）。
         *
         * 由原来的 dp 写法反推而来。历史值：4.2dp 在 440dpi 的 Y700 上是 11.6px，
         * 除以当时"与屏幕相同"纸型的短边 1534px 得到 0.00756，取 0.0076。
         *
         * 那个 4.2dp 也不是随手调的：实测字高约 220px，笔画落在 2.7~6.9px 时
         * 只占字高的 1.2%~3.1%，而真实 0.5mm 笔写 10mm 字是 5%。
         * 笔画偏细会直接削弱"粗细变化"的可感知度。
         *
         * 实际值由设置界面决定，见 domain/InkWidthSteps。
         */
        const val DEFAULT_INK_WIDTH_RATIO = 0.0076f

        /** 页面外的底色。中灰：和浅色纸、和深浅两种界面外壳都有明确对比 */
        const val DEFAULT_CANVAS_BACKGROUND = 0xFFE3E1E8.toInt()

        /** 橡皮半径占文档短边的比例。14dp = 38.5px，除以 1534px ≈ 0.0251 */
        const val ERASER_RADIUS_RATIO = 0.025f

        /**
         * 压感低通滤波系数。越小越平滑、滞后越大。
         * 0.45 对应约 1~2 个采样点的滞后，240Hz 下不到 10ms，手感察觉不到。
         */
        const val PRESSURE_SMOOTHING_ALPHA = 0.45f

        /** 手势中途从橡皮切回钢笔时的起笔压力（此时拿不到真实压力） */
        const val MID_GESTURE_PRESSURE = 0.6f

        /** 荧光笔宽度是钢笔的多少倍 */
        const val HIGHLIGHTER_WIDTH_MULTIPLIER = 4.5f

        /**
         * 荧光笔的不透明度。
         * 要能看清下面的字，所以压得比较低；太高就变成"涂满了"，
         * 也就失去了"像真荧光笔一样透出下面的字"这个意义。
         */
        const val HIGHLIGHTER_ALPHA = 0.35f

        /** 翻页手势的最小横向位移。够大才不会和"手掌蹭过"混淆 */
        const val SWIPE_TRIGGER_DP = 110f

        /** 横向位移必须大于纵向的这个倍数，避免上下滑动误触发翻页 */
        const val SWIPE_ASPECT_RATIO = 1.8f

        const val PAGE_SWIPE_NEXT = 1
        const val PAGE_SWIPE_PREVIOUS = -1

        /** 不透明白纸。0xFFFFFFFF 在 Kotlin 里是 Long，必须 .toInt() */
        val DEFAULT_PAPER_COLOR = 0xFFFFFFFF.toInt()

        /** 默认墨色：近黑但不纯黑，纯黑在屏幕上偏硬 */
        val DEFAULT_INK_COLOR = 0xFF1A1C1E.toInt()

        /** 方格线：约 8% 黑，够看见又不抢笔迹 */
        const val GRID_LINE_COLOR = 0x14000000

        /** 横线：约 12% 黑，比方格稍微明显一点 */
        const val RULED_LINE_COLOR = 0x1F000000

        /** 光标外圈：半透明白，在深色纸上看清 */
        val CURSOR_HALO_COLOR = 0xCCFFFFFF.toInt()

        /** 光标内圈：半透明黑，在白纸上看清 */
        val CURSOR_LINE_COLOR = 0x99000000.toInt()
    }
}
