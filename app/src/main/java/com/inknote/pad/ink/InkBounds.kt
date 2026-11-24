package com.inknote.pad.ink

/**
 * 轴对齐包围盒（AABB）。
 *
 * 为什么不用 android.graphics.RectF：
 * Stroke 必须保持纯 Kotlin，否则它和依赖它的命中测试就没法在 JVM 上跑单元测试。
 * 而命中测试恰恰是橡皮功能里最容易写错、又最不容易被肉眼发现问题的部分 ——
 * "擦不掉"和"擦多了"都只有实际用起来才察觉。
 */
data class InkBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left

    val height: Float get() = bottom - top

    /**
     * 圆是否与包围盒相交。
     * 算法：把圆心夹到盒子范围内得到盒上最近点，再比距离。
     * 圆心在盒内时最近点就是圆心自己，距离为 0，必然相交。
     */
    fun intersectsCircle(cx: Float, cy: Float, radius: Float): Boolean {
        val nearestX = cx.coerceIn(left, right)
        val nearestY = cy.coerceIn(top, bottom)
        val dx = cx - nearestX
        val dy = cy - nearestY
        return dx * dx + dy * dy <= radius * radius
    }

    companion object {
        val EMPTY = InkBounds(0f, 0f, 0f, 0f)
    }
}
