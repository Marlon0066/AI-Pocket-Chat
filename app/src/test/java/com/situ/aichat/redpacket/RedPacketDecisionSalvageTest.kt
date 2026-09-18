package com.situ.aichat.redpacket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RedPacketDecisionSalvage] 纯函数（2026-09-18 提示词册核对 D 条）。期望从规格反推：
 * 「明确表态」只认 JSON 布尔；表态拒收才照拒收，且只留合格字段（≤30 / ≤40、空与技术 id 整条丢）；
 * 表态收下 / 无表态 → null（交默认收下）。
 */
class RedPacketDecisionSalvageTest {

    // ── explicitAcceptIntent：只认 JSON 布尔 ──

    @Test fun intent_json_boolean_counts() {
        assertEquals(false, RedPacketDecisionSalvage.explicitAcceptIntent("""{"shouldAccept":false}"""))
        assertEquals(true, RedPacketDecisionSalvage.explicitAcceptIntent("""{"shouldAccept":true,"reason":""}"""))
    }

    @Test fun intent_string_or_missing_or_non_json_is_unknown() {
        assertNull("字符串 \"false\" 不算明确表态", RedPacketDecisionSalvage.explicitAcceptIntent("""{"shouldAccept":"false"}"""))
        assertNull(RedPacketDecisionSalvage.explicitAcceptIntent("""{"reason":"不熟"}"""))
        assertNull(RedPacketDecisionSalvage.explicitAcceptIntent("我不想收这个红包"))
    }

    @Test fun intent_reads_through_think_tags_and_code_fence() {
        val wrapped = "<think>要不要收呢</think>\n```json\n{\"shouldAccept\":false,\"reason\":\"不熟\"}\n```"
        assertEquals(false, RedPacketDecisionSalvage.explicitAcceptIntent(wrapped))
    }

    // ── salvagedRejection ──

    @Test fun no_explicit_response_or_accept_intent_gives_null() {
        assertNull(RedPacketDecisionSalvage.salvagedRejection(null))
        assertNull("表态收下 → 交默认收下", RedPacketDecisionSalvage.salvagedRejection("""{"shouldAccept":true,"reason":"r"}"""))
        assertNull("无表态 → 交默认收下", RedPacketDecisionSalvage.salvagedRejection("""{"reason":"r","chatReply":"嗯"}"""))
    }

    @Test fun reject_missing_rejection_reason_is_honored_with_usable_reply() {
        val d = RedPacketDecisionSalvage.salvagedRejection("""{"shouldAccept":false,"reason":"不熟","chatReply":"先不收哈"}""")
        assertNotNull(d)
        d!!
        assertFalse("明确拒收必须照拒收", d.shouldAccept)
        assertTrue("来自兜底层", d.isFromFallback)
        assertNull("缺的拒收理由保持空", d.rejectionReason)
        assertEquals("先不收哈", d.chatReply)
        assertEquals("内部理由只给日志", RedPacketDecisionSalvage.SALVAGE_REASON, d.reason)
    }

    @Test fun tech_id_fields_are_dropped_not_fatal() {
        val d = RedPacketDecisionSalvage.salvagedRejection(
            """{"shouldAccept":false,"rejectionReason":"太贵重啦","reason":"r","chatReply":"看 red_packet 哦"}""",
        )!!
        assertFalse(d.shouldAccept)
        assertEquals("合格的拒收理由保留", "太贵重啦", d.rejectionReason)
        assertNull("带技术 id 的口头回复整条丢", d.chatReply)

        val d2 = RedPacketDecisionSalvage.salvagedRejection(
            """{"shouldAccept":false,"rejectionReason":"gift_rose 太贵","reason":"r","chatReply":"收不起呀"}""",
        )!!
        assertNull("带技术 id 的拒收理由整条丢", d2.rejectionReason)
        assertEquals("收不起呀", d2.chatReply)
    }

    @Test fun blank_fields_dropped_and_long_fields_capped() {
        val d = RedPacketDecisionSalvage.salvagedRejection(
            """{"shouldAccept":false,"rejectionReason":"   ","chatReply":"${"嗯".repeat(45)}"}""",
        )!!
        assertNull("纯空白理由当没有", d.rejectionReason)
        assertEquals("口头回复截到 40", 40, d.chatReply!!.length)

        val d2 = RedPacketDecisionSalvage.salvagedRejection(
            """{"shouldAccept":false,"rejectionReason":"${"不".repeat(35)}"}""",
        )!!
        assertEquals("拒收理由截到 30", 30, d2.rejectionReason!!.length)
        assertNull("没给口头回复就不插话", d2.chatReply)
    }
}
