package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-3 卡壳几何（图纸 2026-09-05 卷二C §7）：卡宽收口 = min(恒宽, 气泡最大宽)，以及卷二C 新增的
 * 金标几何值。
 *
 * 断言**从规格独立反推**：期望值直接重打图纸 §2.2 几何表 / §3.2 的数字，不读实现常量——
 * 落值被悄悄改一位，这里必须红。
 */
class LiuliCardShellTest {

    @Test fun cardWidth_takesTheSmallerOfPreferredAndBubbleMax() {
        // 411dp 屏：bubbleMax = 411 × 0.74 = 304.14 → 236 / 280 都放得下。
        assertEquals(236.dp, liuliCardWidth(236.dp, 304.14f.dp))
        assertEquals(280.dp, liuliCardWidth(280.dp, 304.14f.dp))
    }

    @Test fun cardWidth_clampsOnNarrowScreen() {
        // 360dp 窄屏：bubbleMax = 360 × 0.74 = 266.4 → 280 的宽卡必须让位（A-3 / E17）。
        val narrowMax = 266.4f.dp
        assertEquals(narrowMax, liuliCardWidth(280.dp, narrowMax))
        assertEquals(236.dp, liuliCardWidth(236.dp, narrowMax))
    }

    @Test fun cardWidth_isIdempotentAtEquality() {
        assertEquals(280.dp, liuliCardWidth(280.dp, 280.dp))
    }

    @Test fun geometry_goldValues_matchBlueprintTable() {
        assertEquals(236.dp, LiuliChatGeometry.cardWidth)
        assertEquals(280.dp, LiuliChatGeometry.cardWideWidth)
        assertEquals(20.dp, LiuliChatGeometry.cardCorner)
        assertEquals(34.dp, LiuliChatGeometry.cardIconBlock)
        assertEquals(11.dp, LiuliChatGeometry.cardIconCorner)
        assertEquals(34.dp, LiuliChatGeometry.cardButtonHeight)
        assertEquals(110.dp, LiuliChatGeometry.stickerSize)
        assertEquals(24.dp, LiuliChatGeometry.stickerCorner)
        assertEquals(200.dp, LiuliChatGeometry.imageMaxWidth)
        assertEquals(36.dp, LiuliChatGeometry.foldFade)
        assertEquals(30.dp, LiuliChatGeometry.voicePlay)
        assertEquals(3.dp, LiuliChatGeometry.voiceBarWidth)
        assertEquals(2.dp, LiuliChatGeometry.voiceBarGap)
        assertEquals(22.dp, LiuliChatGeometry.voiceBarHeight)
    }
}
