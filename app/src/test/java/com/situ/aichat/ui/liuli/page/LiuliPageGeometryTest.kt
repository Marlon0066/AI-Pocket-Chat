package com.situ.aichat.ui.liuli.page

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.liuli.chat.LiuliChatGeometry
import com.situ.aichat.ui.liuli.home.LiuliHomeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：二级屏几何金标（图纸 2026-09-06 卷四 §4.1 · §8 C1）。
 *
 * 期望值全部从图纸 §4.1 那一行**重新打字**，不回读实现；派生量按图纸给的算式独立算一遍。
 * 另钉「与主页 / 聊天屏同值」——[LiuliPageGeometry] 是有意的同值复制（两张表各自可改），
 * 哪天一边漂了这里当场红，不至于静默走样（卷四 A-2）。
 */
class LiuliPageGeometryTest {

    @Test fun 页壳落值() {
        assertEquals(44.dp, LiuliPageGeometry.navRow)
        assertEquals(2.dp, LiuliPageGeometry.titleTop)
        assertEquals(40.dp, LiuliPageGeometry.titleHeight)
        assertEquals(12.dp, LiuliPageGeometry.titleGap)
        assertEquals(44.dp, LiuliPageGeometry.compactBar)
        assertEquals(40.dp, LiuliPageGeometry.backButton)
        assertEquals(12.dp, LiuliPageGeometry.actionButtonGap)
        assertEquals(20.dp, LiuliPageGeometry.gutter)
        assertEquals(48.dp, LiuliPageGeometry.touchTarget)
    }

    @Test fun 分组行族落值() {
        assertEquals(16.dp, LiuliPageGeometry.groupCorner)
        assertEquals(16.dp, LiuliPageGeometry.groupPadH)
        assertEquals(52.dp, LiuliPageGeometry.rowMin)
        assertEquals(64.dp, LiuliPageGeometry.rowTwoLine)
        assertEquals(28.dp, LiuliPageGeometry.tile)
        assertEquals(7.dp, LiuliPageGeometry.tileCorner)
        assertEquals(12.dp, LiuliPageGeometry.tileGap)
        assertEquals(8.dp, LiuliPageGeometry.groupHeaderBottom)
        assertEquals(6.dp, LiuliPageGeometry.groupFooterTop)
        assertEquals(24.dp, LiuliPageGeometry.groupGap)
    }

    @Test fun 详情页落值() {
        assertEquals(280.dp, LiuliPageGeometry.hero)
        assertEquals(130.dp, LiuliPageGeometry.heroScrim)
        assertEquals(88.dp, LiuliPageGeometry.heroCollapseTail)
        assertEquals(56.dp, LiuliPageGeometry.action)
        assertEquals(68.dp, LiuliPageGeometry.actionSlot)
        assertEquals(16.dp, LiuliPageGeometry.actionGap)
        assertEquals(36.dp, LiuliPageGeometry.stripPaper)
        assertEquals(40.dp, LiuliPageGeometry.stripGlass)
        assertEquals(56.dp, LiuliPageGeometry.subBar)
        assertEquals(18.sp, LiuliPageGeometry.statValue)
        assertEquals(12.sp, LiuliPageGeometry.statLabel)
    }

    @Test fun 派生量按图纸算式独立复算() {
        // 发丝起点：有砖 = 16 + 28 + 12 = 56；无砖 = 16。
        assertEquals(56.dp, LiuliPageGeometry.dividerInsetTile)
        assertEquals(16.dp, LiuliPageGeometry.dividerInsetPlain)
        // 大标题右侧留 = 钮 40 + 缝 12 = 52。
        assertEquals(52.dp, LiuliPageGeometry.titleEndReserve)
        // 内容顶内距 = 状态栏 + 导航行。
        assertEquals(44.dp, LiuliPageGeometry.contentTopInset(0.dp))
        assertEquals(68.dp, LiuliPageGeometry.contentTopInset(24.dp))
        // 覆盖区 = 状态栏 + 收起顶栏（+ subBar）。
        assertEquals(68.dp, LiuliPageGeometry.cover(24.dp, hasSubBar = false))
        assertEquals(124.dp, LiuliPageGeometry.cover(24.dp, hasSubBar = true))
        // subBar 槽 = 8 + 40 玻璃 pill + 8。
        assertEquals(8.dp, (LiuliPageGeometry.subBar - LiuliPageGeometry.stripGlass) / 2)
    }

    /** E19：360dp 窄屏上四个动作版位 + 三条缝恰好等于可用宽（4×68 + 3×16 = 320 = 360 − 2×20）。 */
    @Test fun 动作排在360窄屏不换行() {
        val used = LiuliPageGeometry.actionSlot * 4 + LiuliPageGeometry.actionGap * 3
        assertEquals(320.dp, used)
        assertEquals(360.dp - LiuliPageGeometry.gutter * 2, used)
    }

    @Test fun 与主页几何同值() {
        assertEquals(LiuliHomeGeometry.titleTop, LiuliPageGeometry.titleTop)
        assertEquals(LiuliHomeGeometry.titleHeight, LiuliPageGeometry.titleHeight)
        assertEquals(LiuliHomeGeometry.titleGap, LiuliPageGeometry.titleGap)
        assertEquals(LiuliHomeGeometry.compactBar, LiuliPageGeometry.compactBar)
        assertEquals(LiuliHomeGeometry.gutter, LiuliPageGeometry.gutter)
        assertEquals(LiuliHomeGeometry.plusButton, LiuliPageGeometry.backButton)
        assertEquals(LiuliHomeGeometry.titleEndReserve, LiuliPageGeometry.titleEndReserve)
    }

    @Test fun 与聊天屏触达外框同值() {
        assertEquals(LiuliChatGeometry.touchTarget, LiuliPageGeometry.touchTarget)
    }

    /**
     * 卷五新增件的落值（图纸 2026-09-06 卷五 A-4·§8 C0「加表并钉」）——期望值从图纸那几行重新打字。
     */
    @Test fun 卷五新增落值() {
        assertEquals(28.dp, LiuliPageGeometry.stepperButton)
        assertEquals(16.dp, LiuliPageGeometry.stepperIcon)
        assertEquals(44.dp, LiuliPageGeometry.stepperValueMin)
        assertEquals(56.dp, LiuliPageGeometry.fab)
        assertEquals(24.dp, LiuliPageGeometry.fabIcon)
        assertEquals(24.dp, LiuliPageGeometry.fabBottom)
        assertEquals(12.dp, LiuliPageGeometry.snackbarBottom)
        assertEquals(44.dp, LiuliPageGeometry.snackbarMinHeight)
        assertEquals(14.dp, LiuliPageGeometry.snackbarPadV)
        assertEquals(16.dp, LiuliPageGeometry.snackbarPadH)
        assertEquals(4.dp, LiuliPageGeometry.progressTrack)
        assertEquals(2.dp, LiuliPageGeometry.progressCorner)
        assertEquals(10.dp, LiuliPageGeometry.rowTwoLinePad)
    }

    /**
     * 两枚 28 步进钮之间隔着 ≥ 44 的值槽 → 中心距 ≥ 72 > 48，两枚 48 触达框互不重叠
     * （菜单行那条「紧贴的兄弟各自外溢会互压」的教训在这里被几何排除）。
     */
    @Test fun 步进两钮触达框不重叠() {
        val centerGap = LiuliPageGeometry.stepperButton / 2 +
            LiuliPageGeometry.stepperValueMin +
            LiuliPageGeometry.stepperButton / 2
        assertEquals(72.dp, centerGap)
        assertTrue(centerGap > LiuliPageGeometry.touchTarget)
    }
}
