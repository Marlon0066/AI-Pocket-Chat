package com.situ.aichat.ui.moments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-1（图纸 2026-09-03-朋友圈信息流窗口分页 §7）：扩窗判据纯函数 [shouldLoadOlderPosts] 的边界。
 * 断言从 §3.2 规格（「还有更早的 ∧ 最后一个可见项进入末尾 4 项之内」）独立反推，数值一律写字面量。
 */
class MomentsWindowPredicateTest {

    @Test
    fun `没有更早的了 哪怕已滑到最末一项也不续`() {
        assertFalse(shouldLoadOlderPosts(lastVisibleIndex = 29, totalItemsCount = 30, hasMoreOlder = false))
    }

    @Test
    fun `一项都没渲染 不续`() {
        assertFalse(shouldLoadOlderPosts(lastVisibleIndex = null, totalItemsCount = 30, hasMoreOlder = true))
    }

    @Test
    fun `末尾第4项是闭边界 命中即续`() {
        // totalItemsCount - 4 = 26
        assertTrue(shouldLoadOlderPosts(lastVisibleIndex = 26, totalItemsCount = 30, hasMoreOlder = true))
    }

    @Test
    fun `末尾第5项还差一步 不续`() {
        // totalItemsCount - 5 = 25
        assertFalse(shouldLoadOlderPosts(lastVisibleIndex = 25, totalItemsCount = 30, hasMoreOlder = true))
    }

    @Test
    fun `短列表判据恒成立 由 hasMore 兜底而非判据`() {
        // 3 项的列表：2 >= 3 - 4 = -1 恒真 ⇒ 判据不设防，短列表靠 hasMoreOlder 为 false 拦住（E12 同源）。
        assertTrue(shouldLoadOlderPosts(lastVisibleIndex = 2, totalItemsCount = 3, hasMoreOlder = true))
        assertFalse(shouldLoadOlderPosts(lastVisibleIndex = 2, totalItemsCount = 3, hasMoreOlder = false))
    }
}
