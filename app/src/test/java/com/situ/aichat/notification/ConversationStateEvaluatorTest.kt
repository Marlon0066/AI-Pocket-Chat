package com.situ.aichat.notification

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.LatestMessageMeta
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.entity.ConversationEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ConversationStateEvaluator 单测（主动通知真实感改造 T1-1）。
 *
 * 断言从图纸 §3.5 规格**独立反推**（相位阈值 30/120min、自然日差分档 0/1/2-3/4-7/8-14/≥15、
 * 计数口径 = 最后一条用户消息之后的已投递条数），非照抄实现输出。固定 UTC 保证确定性。
 */
class ConversationStateEvaluatorTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val conversationDao: ConversationDao = mockk()
    private val messageDao: MessageDao = mockk()
    private val deliveryDao: NotificationDeliveryDao = mockk()
    private val evaluator = ConversationStateEvaluator(conversationDao, messageDao, deliveryDao)

    private val charId = "char-1"

    /** 基准「现在」= 2026-01-15 12:00 UTC。 */
    private val now: Long = at(2026, 1, 15, 12, 0)

    private fun at(y: Int, m: Int, d: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(y, m, d, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun minutesAgo(minutes: Long): Long = now - minutes * 60_000L

    private fun phase(lastMessageTs: Long?): ConversationPhase =
        evaluator.resolvePhase(lastMessageTs, now, zone)

    // MARK: - resolvePhase 全相位矩阵（±1 边界精度）

    @Test fun phase_neverAnyMessage_isLongAbsence() {
        assertEquals(ConversationPhase.LONG_ABSENCE, phase(null))
    }

    @Test fun phase_hotWindow_upTo30MinutesInclusive() {
        assertEquals(ConversationPhase.HOT, phase(minutesAgo(0)))
        assertEquals(ConversationPhase.HOT, phase(minutesAgo(29)))
        assertEquals(ConversationPhase.HOT, phase(minutesAgo(30))) // 恰 30min 仍热聊
    }

    @Test fun phase_afterglowWindow_31To120MinutesInclusive() {
        assertEquals(ConversationPhase.AFTERGLOW, phase(minutesAgo(31))) // 31min 出热聊
        assertEquals(ConversationPhase.AFTERGLOW, phase(minutesAgo(119)))
        assertEquals(ConversationPhase.AFTERGLOW, phase(minutesAgo(120))) // 恰 120min 仍余温
    }

    @Test fun phase_past121Minutes_fallsToDayBasedPhase() {
        // 121min 前 = 今天 09:59 → 自然日差 0 → SAME_DAY（不再是余温）
        assertEquals(ConversationPhase.SAME_DAY, phase(minutesAgo(121)))
    }

    @Test fun phase_sameDay_dayDiffZero() {
        assertEquals(ConversationPhase.SAME_DAY, phase(at(2026, 1, 15, 0, 30)))
    }

    @Test fun phase_overnight_dayDiffOne() {
        assertEquals(ConversationPhase.OVERNIGHT, phase(at(2026, 1, 14, 23, 50)))
        assertEquals(ConversationPhase.OVERNIGHT, phase(at(2026, 1, 14, 0, 5)))
    }

    @Test fun phase_normal_dayDiffTwoToThree() {
        assertEquals(ConversationPhase.NORMAL, phase(at(2026, 1, 13, 12, 0)))
        assertEquals(ConversationPhase.NORMAL, phase(at(2026, 1, 12, 12, 0)))
    }

    @Test fun phase_distantEarly_dayDiffFourToSeven() {
        assertEquals(ConversationPhase.DISTANT_EARLY, phase(at(2026, 1, 11, 12, 0))) // d=4 档首
        assertEquals(ConversationPhase.DISTANT_EARLY, phase(at(2026, 1, 8, 12, 0))) // d=7 档尾
    }

    @Test fun phase_distantLate_dayDiffEightToFourteen() {
        assertEquals(ConversationPhase.DISTANT_LATE, phase(at(2026, 1, 7, 12, 0))) // d=8 档首
        assertEquals(ConversationPhase.DISTANT_LATE, phase(at(2026, 1, 1, 12, 0))) // d=14 档尾
    }

    @Test fun phase_longAbsence_dayDiffFifteenOrMore() {
        assertEquals(ConversationPhase.LONG_ABSENCE, phase(at(2025, 12, 31, 12, 0))) // d=15 档首
        assertEquals(ConversationPhase.LONG_ABSENCE, phase(at(2025, 10, 1, 12, 0)))
    }

    /** 自然日差按 LocalDate 差（非 24h 整除）：昨晚 23:50 → 今 12:00 只隔 12h10m 但算 1 天。 */
    @Test fun phase_dayDiff_isCalendarDayNotElapsed24h() {
        assertEquals(ConversationPhase.OVERNIGHT, phase(at(2026, 1, 14, 23, 50)))
        // 反向：31h 前但只跨 1 个自然日 → 仍 OVERNIGHT（若按 24h 整除会误判 NORMAL）
        assertEquals(ConversationPhase.OVERNIGHT, phase(at(2026, 1, 14, 5, 0)))
    }

    // MARK: - evaluate（E10 空态 / E7 计数口径 / 字段派生）

    private fun stubConversations(vararg uuids: String) {
        coEvery { conversationDao.getByCharacter(charId) } returns uuids.map {
            ConversationEntity(uuid = it, characterUuid = charId, title = "t-$it", creationDate = 0L)
        }
    }

    /** E10：角色无任何会话 → LONG_ABSENCE + count=0，且**不**发起 IN 空列表查询。 */
    @Test fun evaluate_noConversations_returnsEmptyLongAbsenceAndSkipsQueries() = runTest {
        stubConversations()

        val state = evaluator.evaluate(charId, now, zone)

        assertEquals(ConversationPhase.LONG_ABSENCE, state.phase)
        assertEquals(0, state.unansweredProactiveCount)
        assertNull(state.minutesSinceLastMessage)
        assertNull(state.lastMessageFromUser)
        assertNull(state.daysSinceLastUserMessage)
        assertNull(state.latestMessageUuid)
        coVerify(exactly = 0) { messageDao.latestNonSystemAcross(any()) }
        coVerify(exactly = 0) { messageDao.latestUserTimestampAcross(any()) }
        coVerify(exactly = 0) { deliveryDao.countDeliveredSince(any(), any()) }
    }

    /** 有会话但无消息（预留会话）→ LONG_ABSENCE 空态；计数基准退化为 0（全表已投递条）。 */
    @Test fun evaluate_conversationsButNoMessages_isLongAbsence() = runTest {
        stubConversations("c1")
        coEvery { messageDao.latestNonSystemAcross(listOf("c1")) } returns null
        coEvery { messageDao.latestUserTimestampAcross(listOf("c1")) } returns null
        coEvery { deliveryDao.countDeliveredSince(charId, 0L) } returns 0

        val state = evaluator.evaluate(charId, now, zone)

        assertEquals(ConversationPhase.LONG_ABSENCE, state.phase)
        assertNull(state.minutesSinceLastMessage)
        assertNull(state.daysSinceLastUserMessage)
        assertEquals(0, state.unansweredProactiveCount)
    }

    /** E7 计数口径：以「最后一条用户消息时间戳」为 since 基准查已投递条数。 */
    @Test fun evaluate_unansweredCount_usesLastUserMessageAsSince() = runTest {
        val lastUserTs = at(2026, 1, 13, 9, 0)
        stubConversations("c1", "c2")
        coEvery { messageDao.latestNonSystemAcross(listOf("c1", "c2")) } returns
            LatestMessageMeta(timestamp = at(2026, 1, 14, 10, 0), roleRaw = "assistant", messageUUID = "m-9")
        coEvery { messageDao.latestUserTimestampAcross(listOf("c1", "c2")) } returns lastUserTs
        coEvery { deliveryDao.countDeliveredSince(charId, lastUserTs) } returns 2

        val state = evaluator.evaluate(charId, now, zone)

        assertEquals(2, state.unansweredProactiveCount)
        coVerify(exactly = 1) { deliveryDao.countDeliveredSince(charId, lastUserTs) }
        // 相位用「最后一条消息(任意方)」= 1/14 10:00 → 日差 1 → OVERNIGHT
        assertEquals(ConversationPhase.OVERNIGHT, state.phase)
        // 降频天数用「最后一条用户消息」= 1/13 → 日差 2（与相位口径**不同**是有意的）
        assertEquals(2, state.daysSinceLastUserMessage)
        assertFalse(state.lastMessageFromUser!!)
        assertEquals("m-9", state.latestMessageUuid)
    }

    /** 用户刚说完话 → 计数基准即该时刻，闸自然归零（「瞬间解冻」免费获得）。 */
    @Test fun evaluate_userJustReplied_countResetsToZero() = runTest {
        val lastUserTs = minutesAgo(5)
        stubConversations("c1")
        coEvery { messageDao.latestNonSystemAcross(listOf("c1")) } returns
            LatestMessageMeta(timestamp = lastUserTs, roleRaw = "user", messageUUID = "m-1")
        coEvery { messageDao.latestUserTimestampAcross(listOf("c1")) } returns lastUserTs
        coEvery { deliveryDao.countDeliveredSince(charId, lastUserTs) } returns 0

        val state = evaluator.evaluate(charId, now, zone)

        assertEquals(0, state.unansweredProactiveCount)
        assertEquals(ConversationPhase.HOT, state.phase)
        assertTrue(state.lastMessageFromUser!!)
        assertEquals(5L, state.minutesSinceLastMessage)
        assertEquals(0, state.daysSinceLastUserMessage)
    }

    /** 用户从未说过话但角色有消息 → daysSinceLastUserMessage=null（Pipeline 视同 ≥15 天档）。 */
    @Test fun evaluate_userNeverSpoke_daysIsNull() = runTest {
        stubConversations("c1")
        coEvery { messageDao.latestNonSystemAcross(listOf("c1")) } returns
            LatestMessageMeta(timestamp = at(2026, 1, 14, 10, 0), roleRaw = "assistant", messageUUID = "m-2")
        coEvery { messageDao.latestUserTimestampAcross(listOf("c1")) } returns null
        coEvery { deliveryDao.countDeliveredSince(charId, 0L) } returns 1

        val state = evaluator.evaluate(charId, now, zone)

        assertNull(state.daysSinceLastUserMessage)
        assertEquals(1, state.unansweredProactiveCount)
        assertEquals(ConversationPhase.OVERNIGHT, state.phase)
    }

}
