package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C4 补测（图纸 2026-09-05 卷二A §4.7 零重叠核对 ②）：chrome 让位的三个派生量。
 *
 * 装机那一档量不到「有世界胶囊」的分支（本机测试数据里角色没进世界 → 胶囊不出现），故把算式钉在这里：
 * 顶栏 chrome 底 = 状态栏 inset + 6 + 44（+ 8 + 24 有胶囊）；列表顶留白再 +12；日期胶囊落在 chrome 底 + 8。
 * 落值全部从图纸 §4.7 的表**重新打一遍**，不引用实现常量。
 */
class LiuliChatGeometryTest {

    private val statusBar = 52.dp // 本机实测（480dpi·156px）；算式与具体值无关，换设备只换这一个入参

    @Test fun chromeBottom_withoutWorldPill() {
        assertEquals(statusBar + 6.dp + 44.dp, LiuliChatGeometry.chromeBottom(statusBar, hasWorldPill = false))
    }

    @Test fun chromeBottom_withWorldPill_addsGapAndPillHeight() {
        assertEquals(
            statusBar + 6.dp + 44.dp + 8.dp + 24.dp,
            LiuliChatGeometry.chromeBottom(statusBar, hasWorldPill = true),
        )
    }

    @Test fun listTopPadding_isChromeBottomPlusTwelve() {
        listOf(false, true).forEach { pill ->
            assertEquals(
                LiuliChatGeometry.chromeBottom(statusBar, pill) + 12.dp,
                LiuliChatGeometry.listTopPadding(statusBar, pill),
            )
        }
    }

    @Test fun datePill_sitsEightBelowChrome_andAboveFirstBubble() {
        listOf(false, true).forEach { pill ->
            val datePillTop = LiuliChatGeometry.datePillOffset(statusBar, pill)
            assertEquals(LiuliChatGeometry.chromeBottom(statusBar, pill) + 8.dp, datePillTop)
            // 首条气泡在胶囊之下（列表顶留白 12 > 胶囊上缘偏移 8）——两者绝不叠字。
            assert(LiuliChatGeometry.listTopPadding(statusBar, pill) > datePillTop)
        }
    }

    @Test fun listBottomPadding_isInputHeightPlusMarginPlusBreath() {
        // 默认态：44（输入区高）+ 12（离屏底）+ 12（呼吸）= 68dp；导航栏 inset 由调用方另加。
        assertEquals(44.dp + 12.dp, LiuliChatGeometry.inputOverlayDefaultHeight)
        assertEquals(44.dp + 12.dp + 12.dp, LiuliChatGeometry.listBottomPadding)
        assertEquals(LiuliChatGeometry.listBottomPadding, LiuliChatGeometry.listBottomPadding(LiuliChatGeometry.inputOverlayDefaultHeight))
    }

    @Test fun listBottomPadding_followsMeasuredInputOverlayHeight() {
        // 复核 R1 🔴-1：引用条 / 日历卡 / 多行输入把 overlay 撑高 60dp → 底留白同量长高（最新气泡与回底钮同升）。
        val grown = LiuliChatGeometry.inputOverlayDefaultHeight + 60.dp
        assertEquals(68.dp + 60.dp, LiuliChatGeometry.listBottomPadding(grown))
    }

    @Test fun inputPieces_matchLockedValues() {
        assertEquals(10.dp, LiuliChatGeometry.inputSide)
        assertEquals(12.dp, LiuliChatGeometry.inputBottom)
        assertEquals(6.dp, LiuliChatGeometry.inputPieceGap)
        assertEquals(44.dp, LiuliChatGeometry.inputPieceSize)
        assertEquals(6.dp, LiuliChatGeometry.stackGap)
        assertEquals(12.dp, LiuliChatGeometry.listHorizontal)
        assertEquals(14.dp, LiuliChatGeometry.scrollFabEnd)
    }
}
