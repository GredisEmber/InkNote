package com.inknote.pad.ink

/**
 * 当前使用的工具。
 *
 * 侧键按下时会【临时】切到 ERASER，松开恢复 —— 但它不改这个字段，
 * 所以工具栏的选中态不会因为按了一下侧键就跳走。
 */
enum class InkTool {
    /** 钢笔：不透明，画在最上层 */
    PEN,

    /**
     * 荧光笔：半透明、宽，画在**钢笔下面**。
     *
     * "画在下面"是荧光笔的关键语义 —— 涂在已有的字上时不应该把字盖住，
     * 而应该像真的荧光笔一样透出下面的字。
     * 这需要画布分两层渲染，见 NoteCanvasView 的注释。
     */
    HIGHLIGHTER,

    /** 橡皮：笔迹级擦除，整笔删掉 */
    ERASER,
    ;

    /** 这个工具会不会产生笔迹。橡皮是删除操作，不产生 */
    val drawsInk: Boolean get() = this != ERASER
}
