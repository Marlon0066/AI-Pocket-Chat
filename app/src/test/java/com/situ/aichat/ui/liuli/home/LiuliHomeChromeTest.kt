package com.situ.aichat.ui.liuli.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-1：缩丸状态机（图纸 2026-09-06 卷三 §7 T1-1 · §4.1 · E2 / E3）。
 *
 * 期望**从规格反推**：同向累计到 24dp 翻一次态、翻转后累计归零（同向再滚不重复触发）、方向一反转就清零
 * 累计（overscroll 回弹与阈内小幅来回都不许闪切）、0 位移什么都不动。
 * 阈值在本测里直接写 24f「像素」——纯函数不认单位，密度换算是 `rememberLiuliHomeChrome` 的活。
 */
class LiuliHomeChromeTest {

    private val threshold = 24f

    @Test fun 同向累计到阈值才翻成缩起() {
        val (acc1, collapsed1) = liuliCollapseStep(0f, -10f, threshold, collapsed = false)
        assertEquals(-10f, acc1, 0f)
        assertFalse("只滚了 10 < 24，不该缩", collapsed1)

        val (acc2, collapsed2) = liuliCollapseStep(acc1, -14f, threshold, collapsed1)
        assertTrue("累计 −24 到阈值，缩起", collapsed2)
        assertEquals("翻转后累计归零", 0f, acc2, 0f)
    }

    @Test fun 方向反转即清零累计() {
        val (acc, collapsed) = liuliCollapseStep(-20f, 5f, threshold, collapsed = false)
        assertEquals("反转只从这一下重新攒", 5f, acc, 0f)
        assertFalse(collapsed)
    }

    @Test fun 阈内来回不翻态() {
        val (acc1, c1) = liuliCollapseStep(0f, -20f, threshold, collapsed = false)
        val (acc2, c2) = liuliCollapseStep(acc1, 20f, threshold, c1)
        assertEquals(20f, acc2, 0f)
        assertFalse("−20 再 +20 都没到 24，两态都不该翻", c2)
    }

    @Test fun 已缩起时继续下滚不重复触发() {
        val (acc, collapsed) = liuliCollapseStep(0f, -30f, threshold, collapsed = true)
        assertTrue("仍是缩起", collapsed)
        assertEquals("累计归零，之后上滚只需再攒一个阈值就能展开", 0f, acc, 0f)
    }

    @Test fun 已展开时继续上滚不重复触发() {
        val (acc, collapsed) = liuliCollapseStep(0f, 30f, threshold, collapsed = false)
        assertFalse(collapsed)
        assertEquals(0f, acc, 0f)
    }

    @Test fun 零位移什么都不动() {
        val (acc, collapsed) = liuliCollapseStep(-7f, 0f, threshold, collapsed = true)
        assertEquals(-7f, acc, 0f)
        assertTrue(collapsed)
    }
}
