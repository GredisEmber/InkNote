package com.inknote.pad.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 删除页之后的当前页计算。
 *
 * 这段逻辑只有三个分支，但每条都有边界，而且错了之后的表现
 * （"删完一页画面跳到别处"）会让人怀疑删除本身有没有删对。
 * 全部钉死在测试里，改的时候不用再重新推理一遍。
 */
class CurrentIndexAfterDeleteTest {

    // ======================================================================
    // 删的是当前页
    // ======================================================================

    @Test
    fun `删中间某一页时停在原位_原位就是原来的后一页`() {
        // 共 5 页，当前在第 3 页（下标 2），删掉它 -> 剩 4 页，停在新的第 3 页
        assertEquals(2, currentIndexAfterDelete(currentIndex = 2, deletedIndex = 2, newCount = 4))
    }

    @Test
    fun `删最后一页时退到新的最后一页`() {
        // 共 3 页，当前在第 3 页（下标 2），删掉它 -> 剩 2 页，只能停在新的最后一页（下标 1）
        assertEquals(1, currentIndexAfterDelete(currentIndex = 2, deletedIndex = 2, newCount = 2))
    }

    @Test
    fun `删第一页且当时在第一页时停在新第一页`() {
        assertEquals(0, currentIndexAfterDelete(currentIndex = 0, deletedIndex = 0, newCount = 3))
    }

    // ======================================================================
    // 删的是当前页之前的页 —— 这是最容易写错的一条
    // ======================================================================

    @Test
    fun `删当前页之前的页时下标要跟着减一`() {
        // 共 5 页，当前在第 4 页（下标 3），删掉第 1 页（下标 0）
        // 内容没变，但它的下标从 3 变成了 2 —— 不减的话会跳到下一页去
        assertEquals(2, currentIndexAfterDelete(currentIndex = 3, deletedIndex = 0, newCount = 4))
    }

    @Test
    fun `删紧邻的前一页时也减一`() {
        assertEquals(1, currentIndexAfterDelete(currentIndex = 2, deletedIndex = 1, newCount = 4))
    }

    // ======================================================================
    // 删的是当前页之后的页 —— 完全不受影响
    // ======================================================================

    @Test
    fun `删当前页之后的页时当前位置不变`() {
        assertEquals(1, currentIndexAfterDelete(currentIndex = 1, deletedIndex = 3, newCount = 4))
    }

    @Test
    fun `删当前页之后的页时即便删的是最后一页也不变`() {
        assertEquals(0, currentIndexAfterDelete(currentIndex = 0, deletedIndex = 4, newCount = 4))
    }

    // ======================================================================
    // 边界
    // ======================================================================

    @Test
    fun `只剩一页时永远返回第零页`() {
        assertEquals(0, currentIndexAfterDelete(currentIndex = 0, deletedIndex = 1, newCount = 1))
        assertEquals(0, currentIndexAfterDelete(currentIndex = 1, deletedIndex = 1, newCount = 1))
    }

    @Test
    fun `结果永远落在合法范围内`() {
        // 穷举一批组合，确认不会跑出 [0, newCount-1]
        for (newCount in 1..6) {
            for (current in 0..6) {
                for (deleted in 0..6) {
                    val r = currentIndexAfterDelete(current, deleted, newCount)
                    val ok = r in 0 until newCount
                    assertEquals(
                        "current=$current deleted=$deleted newCount=$newCount -> $r 越界",
                        true,
                        ok,
                    )
                }
            }
        }
    }
}
