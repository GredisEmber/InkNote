package com.inknote.pad.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * 视口数学。
 *
 * 这类代码写错了表现为"缩放时画面往一边跑"或"笔尖和墨迹差几个像素"，
 * 靠肉眼调试非常痛苦 —— 而它没有任何 Android 依赖，没有理由不测。
 */
class CanvasViewportTest {

    private fun viewport(
        docW: Float = 1000f,
        docH: Float = 1400f,
        viewW: Int = 2000,
        viewH: Int = 1200,
    ) = CanvasViewport().apply { fit(docW, docH, viewW, viewH) }

    // ======================================================================
    // fit —— 这是"老笔记显示不变"的机器化证据
    // ======================================================================

    /**
     * 容差传 **0f**，也就是逐位相同，不是"差不多"。
     *
     * 视口是从 NoteCanvasView 里内联的四个字段抽出来的，抽错了就会让
     * 所有老笔记的显示位置或缩放发生整体偏移 —— 那种变化很细微，
     * 靠肉眼看截图不一定看得出来。所以这里用最严格的方式钉住。
     */
    @Test
    fun `fit 的结果和重构前的公式逐位相同`() {
        val docW = 1240f
        val docH = 1754f

        for ((viewW, viewH) in listOf(
            1904 to 900,   // 宽屏
            900 to 1904,   // 竖屏
            1240 to 1754,  // 和文档等比
            300 to 200,    // 极小
            4000 to 3000,  // 极大
        )) {
            val v = CanvasViewport()
            v.fit(docW, docH, viewW, viewH)

            val legacyScale = min(viewW / docW, viewH / docH)
            assertEquals("scale 在 ${viewW}x$viewH 下不一致", legacyScale, v.scale, 0f)
            assertEquals(
                "offsetX 在 ${viewW}x$viewH 下不一致",
                (viewW - docW * legacyScale) * 0.5f, v.offsetX, 0f,
            )
            assertEquals(
                "offsetY 在 ${viewW}x$viewH 下不一致",
                (viewH - docH * legacyScale) * 0.5f, v.offsetY, 0f,
            )
        }
    }

    @Test
    fun `fit 是等比缩放不做非等比拉伸`() {
        // 文档比视图"瘦"时，横向会留边，但两个方向的缩放必须相同，
        // 否则笔迹会被压扁
        val v = viewport(docW = 1000f, docH = 1000f, viewW = 2000, viewH = 1000)
        assertEquals(1f, v.scale, 1e-6f)
        // 左右各留 500
        assertEquals(500f, v.offsetX, 1e-4f)
        assertEquals(0f, v.offsetY, 1e-4f)
    }

    @Test
    fun `fit 把文档居中`() {
        val v = viewport(docW = 1000f, docH = 1000f, viewW = 1000, viewH = 2000)
        val centerDoc = v.docToViewX(500f)
        assertEquals("文档中心应该落在视图中心", 500f, centerDoc, 1e-3f)
    }

    @Test
    fun `fit 遇到非法输入时保持原状不产生 NaN`() {
        val v = viewport()
        val (s, ox, oy) = Triple(v.scale, v.offsetX, v.offsetY)
        v.fit(0f, 100f, 100, 100)
        v.fit(100f, 0f, 100, 100)
        v.fit(100f, 100f, 0, 100)
        v.fit(100f, 100f, 100, 0)
        assertEquals(s, v.scale, 0f)
        assertEquals(ox, v.offsetX, 0f)
        assertEquals(oy, v.offsetY, 0f)
    }

    // ======================================================================
    // 换算
    // ======================================================================

    @Test
    fun `文档与视图坐标往返一致`() {
        val v = viewport()
        for (x in listOf(0f, 1f, 123.5f, 999f, 1000f)) {
            assertEquals(x, v.viewToDocX(v.docToViewX(x)), 0.01f)
        }
        for (y in listOf(0f, 7.25f, 700f, 1400f)) {
            assertEquals(y, v.viewToDocY(v.docToViewY(y)), 0.01f)
        }
    }

    @Test
    fun `文档原点映射到 offset 处`() {
        val v = viewport()
        assertEquals(v.offsetX, v.docToViewX(0f), 1e-5f)
        assertEquals(v.offsetY, v.docToViewY(0f), 1e-5f)
    }

    // ======================================================================
    // 可见范围
    // ======================================================================

    @Test
    fun `可见范围覆盖整个视图且和换算一致`() {
        val viewW = 2000
        val viewH = 1200
        val v = viewport(viewW = viewW, viewH = viewH)

        assertEquals(v.viewToDocX(0f), v.visibleDocLeft(), 1e-4f)
        assertEquals(v.viewToDocY(0f), v.visibleDocTop(), 1e-4f)
        assertEquals(v.viewToDocX(viewW.toFloat()), v.visibleDocRight(viewW), 1e-4f)
        assertEquals(v.viewToDocY(viewH.toFloat()), v.visibleDocBottom(viewH), 1e-4f)

        assertTrue("右边界必须在左边界右侧", v.visibleDocRight(viewW) > v.visibleDocLeft())
        assertTrue("下边界必须在上边界下方", v.visibleDocBottom(viewH) > v.visibleDocTop())
    }

    @Test
    fun `放大之后可见的文档范围变小`() {
        val v = viewport()
        val before = v.visibleDocRight(2000) - v.visibleDocLeft()
        v.zoomBy(2f, 1000f, 600f)
        val after = v.visibleDocRight(2000) - v.visibleDocLeft()
        assertTrue("放大后应该看到更少的文档", after < before)
        assertEquals("放大 2 倍后可见范围应该减半", before / 2f, after, 0.5f)
    }

    // ======================================================================
    // 平移
    // ======================================================================

    @Test
    fun `平移按视图像素移动`() {
        val v = viewport()
        val ox = v.offsetX
        v.panBy(37f, -21f)
        assertEquals(ox + 37f, v.offsetX, 1e-4f)
        assertEquals(v.offsetY, v.offsetY, 0f)
    }

    @Test
    fun `平移不改变缩放`() {
        val v = viewport()
        val s = v.scale
        v.panBy(500f, 500f)
        assertEquals(s, v.scale, 0f)
    }

    // ======================================================================
    // 缩放锚点 —— 这一组是无限画布能否正确缩放的关键
    // ======================================================================

    /**
     * 捏合缩放时，两指中点下的那个文档坐标必须**停在原地**。
     * 不满足的话画面会从手指底下滑走 —— 这是所有看图应用最基本的手感要求，
     * 也是最容易写错的地方（忘记了 offset 要跟着补偿）。
     */
    @Test
    fun `以某点为锚缩放时那个点在文档里的位置不动`() {
        for (pivot in listOf(0f to 0f, 1000f to 600f, 1999f to 1199f, 250f to 900f)) {
            for (factor in listOf(1.7f, 0.6f, 3.1f)) {
                val v = viewport()
                val (px, py) = pivot
                val docX = v.viewToDocX(px)
                val docY = v.viewToDocY(py)

                v.zoomBy(factor, px, py)

                assertEquals(
                    "锚点 x 在 pivot=($px,$py) factor=$factor 下漂移了",
                    docX, v.viewToDocX(px), 0.05f,
                )
                assertEquals(
                    "锚点 y 在 pivot=($px,$py) factor=$factor 下漂移了",
                    docY, v.viewToDocY(py), 0.05f,
                )
            }
        }
    }

    @Test
    fun `多次连续缩放锚点累计漂移仍然可忽略`() {
        val v = viewport()
        val px = 733f
        val py = 411f
        val docX = v.viewToDocX(px)
        val docY = v.viewToDocY(py)

        // 模拟一次捏合里的几十帧
        repeat(40) { v.zoomBy(1.05f, px, py) }
        repeat(40) { v.zoomBy(1f / 1.05f, px, py) }

        assertEquals("连续缩放后锚点漂移过大", docX, v.viewToDocX(px), 1.5f)
        assertEquals("连续缩放后锚点漂移过大", docY, v.viewToDocY(py), 1.5f)
    }

    @Test
    fun `缩放被限制在相对塞满倍数的上下限内`() {
        val v = viewport()
        val fit = v.fitScale

        repeat(50) { v.zoomBy(1.5f, 1000f, 600f) }
        assertEquals(
            "放大不能超过上限",
            fit * CanvasViewport.MAX_SCALE_FACTOR, v.scale, 1e-3f,
        )

        repeat(80) { v.zoomBy(1f / 1.5f, 1000f, 600f) }
        assertEquals(
            "缩小不能低于下限",
            fit * CanvasViewport.MIN_SCALE_FACTOR, v.scale, 1e-3f,
        )
    }

    @Test
    fun `顶到缩放上限之后继续缩放画面不再移动`() {
        val v = viewport()
        repeat(50) { v.zoomBy(1.5f, 700f, 500f) }
        val ox = v.offsetX
        val oy = v.offsetY
        // 再推几次，offset 必须一动不动 ——
        // 否则画面会持续被"推"向锚点，表现为捏到头之后还在缓慢漂移
        repeat(10) { v.zoomBy(1.5f, 700f, 500f) }
        assertEquals(ox, v.offsetX, 0f)
        assertEquals(oy, v.offsetY, 0f)
    }

    @Test
    fun `非法的缩放倍数被忽略`() {
        val v = viewport()
        val s = v.scale
        v.zoomBy(0f, 100f, 100f)
        v.zoomBy(-2f, 100f, 100f)
        v.zoomBy(Float.NaN, 100f, 100f)
        v.zoomBy(Float.POSITIVE_INFINITY, 100f, 100f)
        assertEquals("非法倍数不该改变缩放", s, v.scale, 0f)
    }

    // ======================================================================
    // 边界约束
    // ======================================================================

    @Test
    fun `内容比视图小时居中且不给拖`() {
        val v = viewport(docW = 1000f, docH = 1000f, viewW = 2000, viewH = 1000)
        v.panBy(800f, 0f)
        v.clampToDocument(docWidth = 1000f, docHeight = 1000f, viewWidth = 2000, viewHeight = 1000)
        assertEquals("内容比视图小时必须回到居中", 500f, v.offsetX, 1e-3f)
    }

    @Test
    fun `内容比视图大时不能拖出边界`() {
        val v = viewport()
        // 放大到内容明显大于视图
        repeat(10) { v.zoomBy(1.4f, 1000f, 600f) }

        // 往右下狂拖
        repeat(20) { v.panBy(900f, 900f) }
        v.clampToDocument(1000f, 1400f, 2000, 1200)
        assertTrue("左边缘不能被拖到视图内（会露出文档外的空白）", v.offsetX <= 0.001f)

        // 往左上狂拖
        repeat(40) { v.panBy(-900f, -900f) }
        v.clampToDocument(1000f, 1400f, 2000, 1200)
        val minX = 2000 - 1000f * v.scale
        assertTrue("右边缘不能被拖过头", v.offsetX >= minX - 0.001f)
    }

    @Test
    fun `边界约束在正负方向都能兜住`() {
        val v = viewport()
        repeat(10) { v.zoomBy(1.4f, 1000f, 600f) }
        val minY = 1200 - 1400f * v.scale

        v.panBy(0f, 99999f)
        v.clampToDocument(1000f, 1400f, 2000, 1200)
        assertTrue(v.offsetY <= 0.001f)

        v.panBy(0f, -99999f)
        v.clampToDocument(1000f, 1400f, 2000, 1200)
        assertTrue(v.offsetY >= minY - 0.001f)
    }

    @Test
    fun `余量允许稍微拉出文档边界`() {
        val v = viewport()
        repeat(10) { v.zoomBy(1.4f, 1000f, 600f) }
        v.panBy(99999f, 0f)
        v.clampToDocument(1000f, 1400f, 2000, 1200, marginDoc = 50f)
        assertEquals(
            "留了余量就应该正好停在余量处",
            50f * v.scale, v.offsetX, 0.01f,
        )
    }

    @Test
    fun `非法文档尺寸下边界约束不产生 NaN`() {
        val v = viewport()
        v.clampToDocument(0f, 100f, 100, 100)
        v.clampToDocument(100f, 0f, 100, 100)
        assertTrue("offset 不该变成 NaN", !v.offsetX.isNaN() && !v.offsetY.isNaN())
        assertTrue(abs(v.offsetX) < 1e6f)
    }
}
