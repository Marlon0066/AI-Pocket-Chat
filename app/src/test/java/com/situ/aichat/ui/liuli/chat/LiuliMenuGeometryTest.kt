package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.geometry.Rect
import com.situ.aichat.ui.chat.immersiveMenuOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-3 菜单定位（图纸 2026-09-05 卷二B §7 · §3.2 菜单一节）：琉璃把屏边距从暖陶的 6 放宽到 12、
 * 并把 `screenH` 扣掉键盘高度（E18）。这里钉的就是这两条在 `immersiveMenuOffset` 上的后果。
 */
class LiuliMenuGeometryTest {

    private val menuW = 200   // LiuliChatGeometry.menuWidth（px == dp·本测按 1x 密度算）
    private val menuH = 260
    private val screenW = 411
    private val windowH = 891
    private val margin = 12   // LiuliChatGeometry.menuMargin
    private val gap = 8       // LiuliChatGeometry.menuBubbleGap

    private fun offsetFor(bubble: Rect, alignEnd: Boolean, imeBottomPx: Int) = immersiveMenuOffset(
        bubble = bubble,
        menuW = menuW,
        menuH = menuH,
        screenW = screenW,
        screenH = windowH - imeBottomPx,
        alignEnd = alignEnd,
        marginPx = margin,
        gapPx = gap,
    )

    @Test fun keyboardOpen_menuStaysAboveTheKeyboard() {
        val ime = 300
        // 泡在屏幕中段，键盘占下方 300px：菜单不许压进键盘区。
        val offset = offsetFor(Rect(40f, 380f, 300f, 440f), alignEnd = false, imeBottomPx = ime)
        assertTrue(
            "菜单底（${offset.y + menuH}）应 ≤ 键盘顶 − 8（${windowH - ime - gap}）",
            offset.y + menuH <= windowH - ime - gap,
        )
    }

    @Test fun noKeyboard_sameBubbleCanUseTheLowerHalf() {
        // 对照组：没键盘时同一个泡的菜单能摆到泡下方（证明上一条的钳位真的是键盘造成的）。
        val withKeyboard = offsetFor(Rect(40f, 380f, 300f, 440f), alignEnd = false, imeBottomPx = 300)
        val without = offsetFor(Rect(40f, 380f, 300f, 440f), alignEnd = false, imeBottomPx = 0)
        assertEquals("无键盘：菜单挂在泡下方 8dp", 440 + gap, without.y)
        assertTrue("有键盘时必须往上让（$withKeyboard vs $without）", withKeyboard.y < without.y)
    }

    @Test fun screenMargin_isTwelve_onBothEdges() {
        // 贴左缘的 AI 泡：左对齐后不许越过 12dp 屏边距。
        val left = offsetFor(Rect(0f, 200f, 260f, 260f), alignEnd = false, imeBottomPx = 0)
        assertEquals("左缘钳 12dp", margin, left.x)
        // 贴右缘的用户泡：右对齐后右缘也留 12dp。
        val right = offsetFor(Rect(151f, 200f, 411f, 260f), alignEnd = true, imeBottomPx = 0)
        assertEquals("右缘钳 12dp", screenW - margin - menuW, right.x)
    }

    @Test fun geometryTokens_matchTheContract() {
        // 金标：契约 §5.7 的三个数（改一个就该有人来改这条）。
        assertEquals(200f, LiuliChatGeometry.menuWidth.value, 0f)
        assertEquals(12f, LiuliChatGeometry.menuMargin.value, 0f)
        assertEquals(8f, LiuliChatGeometry.menuBubbleGap.value, 0f)
    }
}
