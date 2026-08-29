package com.situ.aichat.offline

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.ConversationEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineMeetingGate` T1 谓词表（图纸 2026-08-26 卷一 §7 T1-1）：断言从规格反推——
 * 「按会话不按全局 + 脏态视同见面（fail-closed）+ 会话缺席视为不在见面」。
 */
class OfflineMeetingGateTest {

    private fun conversation(
        uuid: String = "c1",
        characterUuid: String = "char-1",
        inOffline: Boolean = false,
        sessionId: String? = null,
    ) = ConversationEntity(
        uuid = uuid,
        title = "t",
        characterUuid = characterUuid,
        creationDate = 0L,
        isInOfflineMode = inOffline,
        currentOfflineSessionId = sessionId,
    )

    // ── inMeeting：会话级零 IO 判定 ──

    @Test fun inMeeting_null_conversation_is_false() {
        assertFalse(OfflineMeetingGate.inMeeting(null))
    }

    @Test fun inMeeting_flag_false_is_false() {
        assertFalse(OfflineMeetingGate.inMeeting(conversation(inOffline = false, sessionId = null)))
    }

    @Test fun inMeeting_flag_true_with_session_is_true() {
        assertTrue(OfflineMeetingGate.inMeeting(conversation(inOffline = true, sessionId = "s-1")))
    }

    /** E1 脏态：flag=true 而 sessionId 空 → fail-closed 视同见面中（宁可多闭嘴，不穿帮）。 */
    @Test fun inMeeting_dirty_flag_true_without_session_is_true() {
        assertTrue(OfflineMeetingGate.inMeeting(conversation(inOffline = true, sessionId = null)))
        assertTrue(OfflineMeetingGate.inMeeting(conversation(inOffline = true, sessionId = "")))
        assertTrue(OfflineMeetingGate.inMeeting(conversation(inOffline = true, sessionId = "   ")))
    }

    /** 反向脏态（flag=false 但残留 sessionId）→ 不在见面（旗标是唯一判据）。 */
    @Test fun inMeeting_orphan_session_without_flag_is_false() {
        assertFalse(OfflineMeetingGate.inMeeting(conversation(inOffline = false, sessionId = "s-1")))
    }

    // ── characterInMeeting：角色级一跳查询 ──

    @Test fun characterInMeeting_reads_latest_active_conversation() = runTest {
        val dao = mockk<ConversationDao>()
        coEvery { dao.latestActiveForCharacter("char-1") } returns
            conversation(characterUuid = "char-1", inOffline = true, sessionId = "s-1")
        assertTrue(OfflineMeetingGate.characterInMeeting(dao, "char-1"))
    }

    @Test fun characterInMeeting_online_conversation_is_false() = runTest {
        val dao = mockk<ConversationDao>()
        coEvery { dao.latestActiveForCharacter("char-1") } returns
            conversation(characterUuid = "char-1", inOffline = false)
        assertFalse(OfflineMeetingGate.characterInMeeting(dao, "char-1"))
    }

    /** E3 首装冷启：该角色无活动会话 → 不在见面，一切照常。 */
    @Test fun characterInMeeting_no_conversation_is_false() = runTest {
        val dao = mockk<ConversationDao>()
        coEvery { dao.latestActiveForCharacter("char-x") } returns null
        assertFalse(OfflineMeetingGate.characterInMeeting(dao, "char-x"))
    }

    /** E11 并发见面：A 在见面丝毫不影响 B（按会话不按全局）。 */
    @Test fun characterInMeeting_is_per_character_not_global() = runTest {
        val dao = mockk<ConversationDao>()
        coEvery { dao.latestActiveForCharacter("A") } returns
            conversation(uuid = "cA", characterUuid = "A", inOffline = true, sessionId = "sA")
        coEvery { dao.latestActiveForCharacter("B") } returns
            conversation(uuid = "cB", characterUuid = "B", inOffline = false)
        assertTrue(OfflineMeetingGate.characterInMeeting(dao, "A"))
        assertFalse(OfflineMeetingGate.characterInMeeting(dao, "B"))
    }
}
