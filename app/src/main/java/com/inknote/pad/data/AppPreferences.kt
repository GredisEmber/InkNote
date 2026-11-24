package com.inknote.pad.data

import android.content.Context
import com.inknote.pad.domain.BrushParams
import com.inknote.pad.domain.InkPalette
import com.inknote.pad.domain.InkWidthSteps
import com.inknote.pad.domain.PageTurnMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局设置。
 *
 * ============================ 为什么用 SharedPreferences 而不是 Room ============================
 * 这些是"整个 App 一套"的偏好，没有查询、没有关联、没有迁移需求。
 * 为它单开一张表、写一版 schema、维护一次迁移，成本远大于收益 ——
 * 而且 Room 的迁移是"写错就打不开 App"的东西，能不加就不加。
 *
 * 用 StateFlow 暴露而不是让界面直接读 prefs：
 * 界面需要"设置变了要重组"，而 SharedPreferences 的监听器是回调式的，
 * 包一层 StateFlow 之后 Compose 侧就是一句 collectAsState()。
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init {
        purgeLegacyKeys()
    }

    /**
     * 清理改版前的残留键。
     *
     * 这些键在"颜色和笔宽搬进各支笔的二级菜单"时就被取代了
     * （现在是 `pen_color_*` / `hl_color_*` / `brush_*`），没有任何代码再读它们。
     *
     * 留着只占几十字节，但**"一个键存在却没人读"本身就是噪音** ——
     * 以后翻到它的人会先花时间确认"是不是还有别的地方在用"。
     * 删除的成本只有一次几毫秒的 apply，所以顺手做掉。
     *
     * 只删这些确定的旧键，**不碰任何现用设置**。
     */
    private fun purgeLegacyKeys() {
        var dirty = false
        val editor = prefs.edit()
        for (key in LEGACY_KEYS) {
            if (prefs.contains(key)) {
                editor.remove(key)
                dirty = true
            }
        }
        // 没有旧键时不要提交空事务：apply 虽然便宜，但每次冷启动都写一次盘是没必要的
        if (dirty) editor.apply()
    }

    /**
     * 一支笔的颜色设置。
     *
     * ============================ 为什么抽成一个内部类 ============================
     * 钢笔和荧光笔的颜色逻辑**一模一样**：一个槽位（0..2 预设 / 3 自定义）
     * 加一个自定义色，解析成 ARGB。写两遍的话，以后调色板一改就要改两处，
     * 而"改了一处忘了另一处"正是这类复制粘贴最典型的坏法。
     *
     * 抽出来之后，"每支笔的颜色单独分开"这件事变成了**两个实例**而不是两套代码。
     */
    inner class ColorSetting(private val prefix: String) {

        private val _slot = MutableStateFlow(
            prefs.getInt("${prefix}_slot", 0).takeIf { InkPalette.isValidSlot(it) } ?: 0,
        )
        val slot: StateFlow<Int> = _slot.asStateFlow()

        /**
         * 用户挑过的自定义颜色。null 表示还没挑过 ——
         * 用 0 当"未设置"的哨兵值：ARGB 0 是全透明，永远不可能是合法的墨色。
         */
        private val _custom: MutableStateFlow<Int?> = MutableStateFlow(
            prefs.getInt("${prefix}_custom", 0).takeIf { it != 0 },
        )
        val custom: StateFlow<Int?> = _custom.asStateFlow()

        /** 当前颜色（ARGB），从槽位解析出来，界面和画布直接用 */
        val argb: Int
            get() = InkPalette.colorForSlot(_slot.value, _custom.value)

        fun setSlot(index: Int) {
            if (!InkPalette.isValidSlot(index)) return
            _slot.value = index
            prefs.edit().putInt("${prefix}_slot", index).apply()
        }

        /**
         * 保存自定义颜色，并**立刻切到自定义槽位**。
         *
         * 用户挑了一个颜色，意思显然是"我现在要用它" ——
         * 让他挑完还要再点一下槽位才能用，是多余的一步。
         */
        fun setCustomColor(argb: Int) {
            _custom.value = argb
            prefs.edit().putInt("${prefix}_custom", argb).apply()
            setSlot(InkPalette.CUSTOM_SLOT)
        }
    }

    /** 钢笔的颜色 */
    val penColor = ColorSetting("pen_color")

    /** 荧光笔的颜色。和钢笔完全独立 —— 换钢笔的颜色不会把荧光笔也改掉 */
    val highlighterColor = ColorSetting("hl_color")

    // ---- 钢笔的笔刷参数 ----
    private val _brushParams = MutableStateFlow(
        BrushParams(
            baseWidth = prefs.getFloat("brush_base_width", BrushParams.DEFAULT.baseWidth),
            sensitivity = prefs.getFloat("brush_sensitivity", BrushParams.DEFAULT.sensitivity),
            strength = prefs.getFloat("brush_strength", BrushParams.DEFAULT.strength),
            smoothness = prefs.getFloat("brush_smoothness", BrushParams.DEFAULT.smoothness),
        ),
    )
    val brushParams: StateFlow<BrushParams> = _brushParams.asStateFlow()

    fun setBrushParams(params: BrushParams) {
        _brushParams.value = params
        prefs.edit()
            .putFloat("brush_base_width", params.baseWidth)
            .putFloat("brush_sensitivity", params.sensitivity)
            .putFloat("brush_strength", params.strength)
            .putFloat("brush_smoothness", params.smoothness)
            .apply()
    }

    // ---- 荧光笔的简易参数 ----
    // 荧光笔刻意只有三样：颜色、粗细档位、透明度。
    // 它不需要压感/灵敏度/平滑度那一套 —— 荧光笔是"涂"不是"写"，
    // 真实荧光笔也没有笔锋。给四支滑块只会让人以为要调。
    private val _highlighterWidthIndex = MutableStateFlow(
        prefs.getInt("hl_width", InkWidthSteps.DEFAULT_INDEX),
    )
    val highlighterWidthIndex: StateFlow<Int> = _highlighterWidthIndex.asStateFlow()

    /** 荧光笔宽度占文档短边的比例（已乘上荧光笔倍率） */
    val highlighterWidthRatio: Float
        get() = InkWidthSteps.ratioAt(_highlighterWidthIndex.value) *
            InkWidthSteps.HIGHLIGHTER_MULTIPLIER

    private val _highlighterOpacity = MutableStateFlow(
        prefs.getFloat("hl_opacity", InkWidthSteps.HIGHLIGHTER_ALPHA),
    )
    val highlighterOpacity: StateFlow<Float> = _highlighterOpacity.asStateFlow()

    fun setHighlighterWidthIndex(index: Int) {
        _highlighterWidthIndex.value = index
        prefs.edit().putInt("hl_width", index).apply()
    }

    fun setHighlighterOpacity(alpha: Float) {
        val v = alpha.coerceIn(MIN_HIGHLIGHTER_ALPHA, MAX_HIGHLIGHTER_ALPHA)
        _highlighterOpacity.value = v
        prefs.edit().putFloat("hl_opacity", v).apply()
    }

    // ---- 翻页方式 ----
    private val _pageTurnMode = MutableStateFlow(
        PageTurnMode.entries.getOrElse(prefs.getInt(KEY_PAGE_TURN, 0)) {
            PageTurnMode.HORIZONTAL
        },
    )
    val pageTurnMode: StateFlow<PageTurnMode> = _pageTurnMode.asStateFlow()

    // ---- 只认手写笔 ----
    private val _stylusOnly = MutableStateFlow(prefs.getBoolean(KEY_STYLUS_ONLY, true))
    val stylusOnly: StateFlow<Boolean> = _stylusOnly.asStateFlow()

    /**
     * 无缝滚动时是否吸附到整页。
     *
     * ============================ 为什么默认是关 ============================
     * 关掉时松手停在哪儿就是哪儿，**可以停在两页中间同时看到上一页的末尾和
     * 下一页的开头** —— 这正是"无缝"的意义：页与页之间没有硬边界，
     * 你是在一条连续的纸上写，而不是在一叠卡片上翻。
     *
     * 开着则每次自动对齐整页，接近传统的翻页手感。
     *
     * 两种都合理，取决于怎么用：连续书写倾向自由滚动，
     * 跳读/整理倾向整页对齐。所以做成开关，默认给更"连续"的那一种。
     */
    private val _seamlessSnap = MutableStateFlow(prefs.getBoolean(KEY_SEAMLESS_SNAP, false))
    val seamlessSnap: StateFlow<Boolean> = _seamlessSnap.asStateFlow()

    // ---- 显示笔迹信息 ----
    private val _showStrokeInfo = MutableStateFlow(prefs.getBoolean(KEY_SHOW_STROKE_INFO, false))
    val showStrokeInfo: StateFlow<Boolean> = _showStrokeInfo.asStateFlow()

    fun setPageTurnMode(mode: PageTurnMode) {
        _pageTurnMode.value = mode
        prefs.edit().putInt(KEY_PAGE_TURN, mode.ordinal).apply()
    }

    fun setStylusOnly(enabled: Boolean) {
        _stylusOnly.value = enabled
        prefs.edit().putBoolean(KEY_STYLUS_ONLY, enabled).apply()
    }

    fun setSeamlessSnap(enabled: Boolean) {
        _seamlessSnap.value = enabled
        prefs.edit().putBoolean(KEY_SEAMLESS_SNAP, enabled).apply()
    }

    fun setShowStrokeInfo(enabled: Boolean) {
        _showStrokeInfo.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_STROKE_INFO, enabled).apply()
    }

    companion object {
        /**
         * 透明度下限。低于这个值荧光笔涂了等于没涂，
         * 用户会以为功能坏了 —— 让他调不到那个区间比让他调坏更好。
         */
        const val MIN_HIGHLIGHTER_ALPHA = 0.10f

        /**
         * 透明度上限。不做到 1.0（完全实心）：
         * 实心的荧光笔就不是荧光笔了，它会把下面的字彻底盖住，
         * 而那正是这个工具存在的意义的反面。
         */
        const val MAX_HIGHLIGHTER_ALPHA = 0.75f

        private const val FILE_NAME = "inknote_settings"

        /**
         * 改版前用过、现在没有任何代码读取的键。
         * 见 [purgeLegacyKeys]。
         */
        private val LEGACY_KEYS = listOf(
            "ink_color_index",
            "ink_color_slot",
            "custom_ink_color",
            "ink_width_index",
        )

        private const val KEY_PAGE_TURN = "page_turn_mode"
        private const val KEY_STYLUS_ONLY = "stylus_only"
        private const val KEY_SEAMLESS_SNAP = "seamless_snap"
        private const val KEY_SHOW_STROKE_INFO = "show_stroke_info"
    }
}

/**
 * 设置的单例入口。
 *
 * 和 InkRepositoryProvider 一样的模式：显式 init 让"忘了在 Application 里注册"
 * 变成一句清楚的报错，而不是某处莫名其妙的空指针。
 */
object AppPreferencesProvider {

    @Volatile
    private var instance: AppPreferences? = null

    fun init(preferences: AppPreferences) {
        instance = preferences
    }

    val prefs: AppPreferences
        get() = instance ?: error(
            "AppPreferencesProvider.init() 未被调用 —— " +
                "检查 AndroidManifest 里的 android:name=\".InkApp\" 是否还在",
        )
}
