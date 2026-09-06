package com.situ.aichat.ui.liuli.chat

import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-1 红包主 / 副文案（图纸 2026-09-05 卷二C §7）：琉璃这边是**重打**（暖陶 `RedPacketCardBubble` 的
 * `primaryText` / `secondaryText` 是 private），故必须逐格钉住「与暖陶同值」。
 *
 * 期望值**从规格反推**（图纸 F9 的文案规格 + 暖陶 KDoc「祝福 → 节日名+红包 → 恭喜发财」三级），
 * 一律在测试里重新打字为字面量，不引用实现常量。
 */
class LiuliRedPacketTextTest {

    private fun packet(blessing: String, festival: String? = null) =
        RedPacketData(type = "red_packet", recordUUID = "u1", amount = 88, blessingText = blessing, festivalId = festival)

    // ── 主文案三级 ────────────────────────────────────────────────────────────

    @Test fun primary_prefersBlessing() {
        assertEquals("请你吃糖", liuliRedPacketPrimaryText(packet("请你吃糖"), "春节"))
    }

    @Test fun primary_trimsBlessingAndFallsBackWhenBlank() {
        assertEquals("请你吃糖", liuliRedPacketPrimaryText(packet("  请你吃糖  "), null))
        assertEquals("春节红包", liuliRedPacketPrimaryText(packet("   "), "春节"))
    }

    @Test fun primary_usesFestivalName_thenDefault() {
        assertEquals("中秋红包", liuliRedPacketPrimaryText(packet(""), "中秋"))
        assertEquals("恭喜发财", liuliRedPacketPrimaryText(packet(""), null))
        assertEquals("恭喜发财", liuliRedPacketPrimaryText(packet(""), ""))
    }

    // ── 副文案四态 × 方向 ──────────────────────────────────────────────────────

    @Test fun secondary_pending() {
        assertEquals("等待对方查收", liuliRedPacketSecondaryText(RedPacketStatus.PENDING, isFromUser = true))
        assertEquals("点击拆开 🧧", liuliRedPacketSecondaryText(RedPacketStatus.PENDING, isFromUser = false))
    }

    @Test fun secondary_accepted() {
        assertEquals("对方已领取", liuliRedPacketSecondaryText(RedPacketStatus.ACCEPTED, isFromUser = true))
        assertEquals("已领取", liuliRedPacketSecondaryText(RedPacketStatus.ACCEPTED, isFromUser = false))
    }

    @Test fun secondary_rejected() {
        assertEquals("对方拒收了", liuliRedPacketSecondaryText(RedPacketStatus.REJECTED, isFromUser = true))
        assertEquals("已退回", liuliRedPacketSecondaryText(RedPacketStatus.REJECTED, isFromUser = false))
    }

    @Test fun secondary_expired_isDirectionless() {
        assertEquals("24 小时未拆,已退回", liuliRedPacketSecondaryText(RedPacketStatus.EXPIRED, isFromUser = true))
        assertEquals("24 小时未拆,已退回", liuliRedPacketSecondaryText(RedPacketStatus.EXPIRED, isFromUser = false))
    }

    /** 无障碍句拼法（F9）：「红包，{主}，{副}」——卡面重排不许改这一句。 */
    @Test fun accessibilitySentence_composition() {
        val data = packet("", "春节")
        val cd = "红包，${liuliRedPacketPrimaryText(data, "春节")}，" +
            liuliRedPacketSecondaryText(RedPacketStatus.PENDING, isFromUser = false)
        assertEquals("红包，春节红包，点击拆开 🧧", cd)
    }
}
