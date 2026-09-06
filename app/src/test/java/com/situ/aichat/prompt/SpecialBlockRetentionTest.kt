package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1-1 / T1-2（图纸 2026-09-06 见面窗口与节拍卡七件 §7）：特殊块保留数单源 [retainedSpecialBlockCount]。
 *
 * 断言从 §3.B/F 规格独立反推：见面 = 由新到旧累加 CJK 字数，前 [SpecialBlockPolicy.MEETING_MIN_KEEP] 条无条件
 * 保留、之后某条会使累计**超过**预算即停（该条不保留）；通话 = min(size, callLimit)；空块 = 0。
 */
class SpecialBlockRetentionTest {

    private fun msg(id: String, content: String, call: Boolean = false, offline: Boolean = false) = MessageEntity(
        messageUUID = id,
        conversationUuid = "c1",
        roleRaw = "user",
        content = content,
        timestamp = id.hashCode().toLong(),
        isPartOfVoiceCall = call,
        isOfflineMode = offline,
    )

    /** 升序块：第 1 条最旧，最后一条最新。 */
    private fun meetingBlock(count: Int, lengths: (Int) -> Int) =
        (1..count).map { msg("m$it", "字".repeat(lengths(it)), offline = true) }

    private fun policy(budget: Int = SpecialBlockPolicy.MEETING_BUDGET_CJK, callLimit: Int = 120) =
        SpecialBlockPolicy(meetingBudgetCjk = budget, callLimit = callLimit)

    /** E1：30 条每条 1,000 字、预算 20,000 → 恰 20 条（第 21 条会使累计到 21,000 越预算）。 */
    @Test
    fun `见面_按字符预算由新到旧截断`() {
        val block = meetingBlock(30) { 1_000 }
        assertEquals(20, retainedSpecialBlockCount(block, SpecialBlockKind.OFFLINE_MEETING, policy(budget = 20_000)))
    }

    /** E2：5 条、最新一条 30,000 字（远超预算）→ 块长 < MIN_KEEP → 全留，不许饿死窗口。 */
    @Test
    fun `见面_单条超长但块短于MIN_KEEP时全留`() {
        val block = meetingBlock(5) { if (it == 5) 30_000 else 100 }
        assertEquals(5, retainedSpecialBlockCount(block, SpecialBlockKind.OFFLINE_MEETING, policy(budget = 20_000)))
    }

    /** E3：12 条、最新 8 条各 5,000 字（合计 40,000 > 预算）→ 恰 MIN_KEEP 8 条。 */
    @Test
    fun `见面_最新八条已超预算时保留恰MIN_KEEP条`() {
        val block = meetingBlock(12) { if (it > 4) 5_000 else 100 }
        assertEquals(
            SpecialBlockPolicy.MEETING_MIN_KEEP,
            retainedSpecialBlockCount(block, SpecialBlockKind.OFFLINE_MEETING, policy(budget = 20_000)),
        )
    }

    /** 边界：累计恰好等于预算的那条要留（判据是「超过」才停）。 */
    @Test
    fun `见面_累计恰好等于预算时该条仍保留`() {
        val block = meetingBlock(12) { 1_000 }
        assertEquals(10, retainedSpecialBlockCount(block, SpecialBlockKind.OFFLINE_MEETING, policy(budget = 10_000)))
    }

    /** E4：通话 200 条 → 短期 30 → 120；短期 20 → 80；短期 0 → callLimit 恒 ≥ 1。 */
    @Test
    fun `通话_按条数上限截断且下限为一`() {
        val block = (1..200).map { msg("c$it", "喂", call = true) }
        val fromSettings = { n: Int -> SpecialBlockPolicy.from(AppSettings(shortTermMemoryLength = n)) }
        assertEquals(120, retainedSpecialBlockCount(block, SpecialBlockKind.VOICE_CALL, fromSettings(30)))
        assertEquals(80, retainedSpecialBlockCount(block, SpecialBlockKind.VOICE_CALL, fromSettings(20)))
        assertEquals(1, retainedSpecialBlockCount(block, SpecialBlockKind.VOICE_CALL, fromSettings(0)))
        // 块比上限短 → 全留。
        assertEquals(3, retainedSpecialBlockCount(block.take(3), SpecialBlockKind.VOICE_CALL, fromSettings(30)))
    }

    @Test
    fun `空块两种类型都返回零`() {
        assertEquals(0, retainedSpecialBlockCount(emptyList(), SpecialBlockKind.OFFLINE_MEETING, policy()))
        assertEquals(0, retainedSpecialBlockCount(emptyList(), SpecialBlockKind.VOICE_CALL, policy()))
    }

    /** classify：通话优先于线下（见面中打来的通话消息两个标志都为真）。 */
    @Test
    fun `classify_通话优先于线下且普通消息为null`() {
        assertEquals(SpecialBlockKind.VOICE_CALL, SpecialBlockKind.classify(msg("x", "a", call = true, offline = true)))
        assertEquals(SpecialBlockKind.VOICE_CALL, SpecialBlockKind.classify(msg("x", "a", call = true)))
        assertEquals(SpecialBlockKind.OFFLINE_MEETING, SpecialBlockKind.classify(msg("x", "a", offline = true)))
        assertNull(SpecialBlockKind.classify(msg("x", "a")))
    }
}
