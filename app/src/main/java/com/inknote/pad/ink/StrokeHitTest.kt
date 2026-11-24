package com.inknote.pad.ink

/**
 * 笔迹命中测试（橡皮用）。
 *
 * 纯 Kotlin，不依赖 android.graphics —— 这样它能在 JVM 上被单元测试覆盖。
 *
 * 为什么橡皮做成"笔迹级"而不是"像素级"：
 *   1. 像素级（PorterDuff.CLEAR）会把笔迹层抠出洞，撤销就得存整张位图快照，
 *      内存和复杂度都不可接受；
 *   2. 笔迹级删除只需从列表里移掉整笔，撤销就是把它们按原下标插回去 —— 干净、可逆；
 *   3. 用户实际想要的也基本是"这一笔不要了"，而不是"把这个字擦掉一半"。
 * 代价是擦不细。真需要细擦时再补一个像素级橡皮，作为另一种工具。
 */
object StrokeHitTest {

    /**
     * 圆（橡皮笔尖）是否碰到这一笔的任意一段。
     *
     * 先用包围盒快速排除，再做点到线段的精确距离。顺序很重要：
     * 一页几百笔时包围盒能排除掉绝大部分，否则每次移动都要遍历所有笔的所有线段。
     */
    fun hits(stroke: Stroke, cx: Float, cy: Float, radius: Float): Boolean {
        if (!stroke.bounds.intersectsCircle(cx, cy, radius)) return false

        for (i in stroke.segments.indices) {
            val s = stroke.segments[i]
            // 线段本身有宽度，圆头笔帽让它的实际覆盖范围是"胶囊形"，
            // 所以有效半径要加上半线宽 —— 否则贴着粗笔画边缘会擦不掉
            val effective = radius + s.width * 0.5f
            if (distanceSquaredToSegment(cx, cy, s.x0, s.y0, s.x1, s.y1) <= effective * effective) {
                return true
            }
        }
        return false
    }

    /**
     * 点到线段的最短距离的平方。
     * 用平方是因为调用方只需要比较大小，省掉一次开方 —— 这是热路径。
     */
    fun distanceSquaredToSegment(
        px: Float,
        py: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared <= 0f) {
            // 退化成点：轻点一下产生的零长度线段就是这种情况
            val ax = px - x0
            val ay = py - y0
            return ax * ax + ay * ay
        }

        // 把点投影到线段所在直线上，参数 t 夹到 [0,1] 就得到线段上的最近点
        val t = (((px - x0) * dx + (py - y0) * dy) / lengthSquared).coerceIn(0f, 1f)
        val nearestX = x0 + t * dx
        val nearestY = y0 + t * dy
        val ex = px - nearestX
        val ey = py - nearestY
        return ex * ex + ey * ey
    }
}
