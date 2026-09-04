package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重新生成」范围判据（[RegenerableTurn]）——**菜单给不给** 与 **引擎删哪些** 的共用单源
 * （2026-09-04 用户拍板根治·独立复核 R2 🔴-1）。
 *
 * 断言从规格独立反推，不照搬实现：一轮 = 可见流末尾连续的 AI **文本类**消息（普通文字 / 日程卡）；
 * 事件与结构化卡（通话记录 / 见面结束 / 红包 / 礼物 / 邀约 / 未来约定）遇到即停——它们是事件凭证与
 * 回看入口，不是「AI 说的一句话」，重来无意义、删掉是净损失（红包礼物更直连钱路）。
 */
class RegenerableTurnTest {

    private fun msg(uuid: String, role: String, kind: MessageKind = MessageKind.PLAIN_TEXT) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c1",
        roleRaw = role,
        content = "x",
        timestamp = 1_000L,
        messageKindRaw = kind.raw,
    )

    // ---- 成段判定 ----

    @Test
    fun `last turn multi bubble reply is regenerable as a whole`() {
        // 一轮分条递送=多条 assistant：整轮每一句都可长按重来（引擎删的就是这一整段）。
        val set = RegenerableTurn.trailingUuids(
            listOf(
                msg("u1", "user"), msg("a1", "assistant"),
                msg("u2", "user"), msg("a2", "assistant"), msg("a3", "assistant"),
            ),
        )
        assertEquals(setOf("a2", "a3"), set)
    }

    @Test
    fun `empty when last visible message is from user`() {
        // 你刚发完、AI 还没回：没有可重来的一轮，整屏不给这一项（引擎同样什么都不做）。
        assertTrue(RegenerableTurn.trailingUuids(listOf(msg("a1", "assistant"), msg("u1", "user"))).isEmpty())
    }

    @Test
    fun `consecutive proactive messages all included`() {
        // 角色连着主动来消息（中间没有你说话）：整段都会被删掉重跑，故整段都给。
        assertEquals(
            setOf("a1", "a2"),
            RegenerableTurn.trailingUuids(listOf(msg("a1", "assistant"), msg("a2", "assistant"))),
        )
    }

    @Test
    fun `empty list yields empty set`() {
        assertTrue(RegenerableTurn.trailingUuids(emptyList()).isEmpty())
    }

    // ---- 事件卡遇到即停（根治的核心收益） ----

    @Test
    fun `call record card ends the turn`() {
        // 通话刚结束：通话记录卡是回看通话内容的唯一入口。此前按 DB 全量算会把它连同几轮转写删掉，
        // 而用户长按的那条纹丝不动 —— 现在整屏都不给「重新生成」。
        val set = RegenerableTurn.trailingUuids(
            listOf(
                msg("u1", "user"), msg("a1", "assistant"),
                msg("call", "assistant", MessageKind.CALL_RECORD_CARD),
            ),
        )
        assertTrue(set.isEmpty())
    }

    @Test
    fun `offline end marker ends the turn`() {
        // 见面刚结束：「线下见面结束」分隔条是见面回顾的唯一入口，同样不可被重新生成删掉。
        val set = RegenerableTurn.trailingUuids(
            listOf(msg("a1", "assistant"), msg("end", "assistant", MessageKind.OFFLINE_MARKER_END)),
        )
        assertTrue(set.isEmpty())
    }

    @Test
    fun `money cards end the turn`() {
        // 钱路：红包 / 礼物卡绝不能被「重新生成」删掉。
        for (kind in listOf(MessageKind.RED_PACKET, MessageKind.GIFT_CARD)) {
            val set = RegenerableTurn.trailingUuids(
                listOf(msg("a1", "assistant"), msg("card", "assistant", kind)),
            )
            assertTrue("$kind 应终止一轮", set.isEmpty())
        }
    }

    @Test
    fun `turn resumes after an event card`() {
        // 事件卡之后又聊了两句：可重来的只是卡之后那两句，卡本身与更早的都不动。
        val set = RegenerableTurn.trailingUuids(
            listOf(
                msg("a0", "assistant"),
                msg("card", "assistant", MessageKind.OFFLINE_INVITE_CARD),
                msg("a1", "assistant"), msg("a2", "assistant"),
            ),
        )
        assertEquals(setOf("a1", "a2"), set)
    }

    @Test
    fun `schedule card counts as part of the turn`() {
        // 日程卡是同一次生成的文字产物（isStructuredCard=false），与普通文字同档可重来。
        val set = RegenerableTurn.trailingUuids(
            listOf(msg("u1", "user"), msg("a1", "assistant"), msg("s1", "assistant", MessageKind.SCHEDULE_CARD)),
        )
        assertEquals(setOf("a1", "s1"), set)
    }

    // ---- 行级组合（复核 R2 🔵-2：此前只是列表里的一句表达式，零覆盖） ----

    @Test
    fun `row gets the action only when in last turn and idle`() {
        val trailing = setOf("a1", "a2")
        assertTrue(RegenerableTurn.canRegenerate("a2", trailing, isBusy = false))
        assertFalse("不在最后一轮=不给", RegenerableTurn.canRegenerate("old", trailing, isBusy = false))
        // 回合在跑时引擎的并发门会挡下 regenerate()，此刻给了就是又一个「点了没反应」。
        assertFalse("生成中=全列不给", RegenerableTurn.canRegenerate("a2", trailing, isBusy = true))
        assertFalse(RegenerableTurn.canRegenerate("a2", emptySet(), isBusy = false))
    }

    // ---- 单条判定 ----

    @Test
    fun `user messages are never part of a turn`() {
        assertFalse(RegenerableTurn.isPartOfTurn(msg("u1", "user")))
        // 系统耳语的 roleRaw 也是 "user"（可见流里本就被 SQL 滤掉，这里是第二道保险）。
        assertFalse(RegenerableTurn.isPartOfTurn(msg("h1", "user", MessageKind.SYSTEM_HINT)))
    }

    @Test
    fun `trailing returns entities in chronological order`() {
        // 引擎按这个列表逐条删，顺序须与输入一致（删除幂等，但顺序影响日志可读性与快照重算时机）。
        val list = RegenerableTurn.trailing(
            listOf(msg("u1", "user"), msg("a1", "assistant"), msg("a2", "assistant")),
        )
        assertEquals(listOf("a1", "a2"), list.map { it.messageUUID })
    }
}
