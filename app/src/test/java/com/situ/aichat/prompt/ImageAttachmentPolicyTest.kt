package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.TurnMediaAttachments
import com.situ.aichat.ui.chat.TurnMediaAttachments.MAX_ATTACHED_IMAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：历史图片「只挂最近 N 张」的选取策略（拍板①）。
 *
 * 这条策略是本卷与主流客户端唯一的有意分歧——RikkaHub / Cherry Studio / LobeChat 都是窗口内**全量重发**。
 * 我们不那么做的理由：历史窗口 500 条 + 陪伴场景高频长聊，一张图各家计费约 1k–2.4k token，
 * 攒 10 张旧图就是每轮白烧一两万；而本 App 有别人没有的 `mediaMemorySummary`，旧图退成
 * 「发送了一张图片：{摘要}」后语义并不断链。
 *
 * 这里锁的是选取谓词本身（纯逻辑）：**按时间倒序取最近 N 张 user 图片**。
 */
class ImageAttachmentPolicyTest {

    private fun msg(uuid: String, ts: Long, role: String = "user", image: String? = null) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c1",
        content = "[图片]",
        roleRaw = role,
        timestamp = ts,
        imageRelativePath = image,
    )

    /**
     * **调用产线代码本身**，绝不在测试里复制一份选取逻辑——早先那版就是复制的，结果是把
     * `TurnMediaAttachments.images` 改成 takeLast、去掉 user 过滤、甚至整个返回空表，6 条断言照样全绿。
     */
    private fun pick(history: List<MessageEntity>, inOffline: Boolean = false, sessionId: String? = null): List<String> =
        TurnMediaAttachments.selectImageCandidates(history, inOffline, sessionId)
            .take(MAX_ATTACHED_IMAGES)
            .map { it.messageUUID }

    @Test
    fun `上限是 3 张`() {
        assertEquals(3, MAX_ATTACHED_IMAGES)
    }

    @Test
    fun `少于上限时全挂`() {
        val history = listOf(
            msg("a", 1, image = "/1.jpg"),
            msg("b", 2, image = "/2.jpg"),
        )
        assertEquals(setOf("a", "b"), pick(history).toSet())
    }

    @Test
    fun `超过上限时只取最近三张`() {
        val history = (1..6).map { msg("m$it", it.toLong(), image = "/$it.jpg") }
        // 最近三张 = m6 m5 m4；m1–m3 退语义占位
        assertEquals(listOf("m6", "m5", "m4"), pick(history))
    }

    @Test
    fun `助手侧图片不挂`() {
        val history = listOf(
            msg("u", 1, image = "/1.jpg"),
            msg("a", 2, role = "assistant", image = "/2.jpg"),
        )
        assertEquals(listOf("u"), pick(history))
    }

    @Test
    fun `无图消息不参与计数`() {
        val history = listOf(
            msg("t1", 1),
            msg("i1", 2, image = "/1.jpg"),
            msg("t2", 3),
            msg("i2", 4, image = "/2.jpg"),
        )
        assertEquals(listOf("i2", "i1"), pick(history))
    }

    @Test
    fun `线上模式下见面里发的图不占名额`() {
        // 线上装配会整片剔除见面消息（PromptBuilderWindow），若这里不同口径，见面照片会吃掉全部 3 个名额，
        // 结果那 3 张压根不进提示词、窗口内本可挂的线上照片反倒退成占位。
        val history = listOf(
            msg("online1", 1, image = "/1.jpg"),
            msg("meet1", 2, image = "/2.jpg").copy(isOfflineMode = true, offlineSessionId = "s1"),
            msg("meet2", 3, image = "/3.jpg").copy(isOfflineMode = true, offlineSessionId = "s1"),
            msg("meet3", 4, image = "/4.jpg").copy(isOfflineMode = true, offlineSessionId = "s1"),
        )
        assertEquals(listOf("online1"), pick(history, inOffline = false))
    }

    @Test
    fun `见面中只认本场的图`() {
        val history = listOf(
            msg("online1", 1, image = "/1.jpg"),
            msg("other", 2, image = "/2.jpg").copy(isOfflineMode = true, offlineSessionId = "other"),
            msg("mine", 3, image = "/3.jpg").copy(isOfflineMode = true, offlineSessionId = "s1"),
        )
        assertEquals(listOf("mine", "online1"), pick(history, inOffline = true, sessionId = "s1"))
    }

    @Test
    fun `空历史给空表`() {
        assertTrue(pick(emptyList()).isEmpty())
    }

    // ── 本轮窗口谓词（R4 🔵-2 补锁）──
    // `turnUserMessageUuids` 决定媒体降级重试后那句 toast 说「图」还是说「语音」：
    // 判据必须是「本轮**这一次**发的里有没有图」。旧判据「窗口里有图」近乎恒真（名额恒取最近 3 张），
    // 任何网络抖动都会误报图片；退一步的写法「最后一条 user 消息带图」也不行——合并等待窗会把
    // 「先发一张图、再补一句话」并成一轮，那时最后一条是文字，明明剥掉的是图却弹语音文案。

    private fun turnUuids(vararg history: MessageEntity): Set<String> =
        TurnMediaAttachments.turnUserMessageUuids(history.toList())

    @Test
    fun `本轮窗口 = 上一条 assistant 之后的全部 user 消息`() {
        // 「图 + 一句话」并成一轮：两条都算本轮，缺一条 toast 就会指错对象
        val set = turnUuids(
            msg("old_img", 1, image = "/old.jpg"),
            msg("reply", 2, role = "assistant"),
            msg("img", 3, image = "/1.jpg"),
            msg("text", 4),
        )
        assertEquals(setOf("img", "text"), set)
    }

    @Test
    fun `上一条就是 assistant 时给空集`() {
        // 本轮压根没有用户消息 → 无图可剥 → 该退回语音文案，绝不能拿上一轮的图说事
        assertTrue(turnUuids(msg("img", 1, image = "/1.jpg"), msg("reply", 2, role = "assistant")).isEmpty())
    }

    @Test
    fun `历史为空给空集`() {
        assertTrue(TurnMediaAttachments.turnUserMessageUuids(emptyList()).isEmpty())
    }

    @Test
    fun `一条 assistant 都没有时整段历史都算本轮`() {
        // 新会话首轮：takeWhile 吃掉全部历史是对的（这时本来就只有这一轮）
        assertEquals(setOf("a", "b"), turnUuids(msg("a", 1), msg("b", 2)))
    }

    @Test
    fun `线下见面消息同样按角色算_不因 offline 标记漏掉`() {
        val set = turnUuids(
            msg("reply", 1, role = "assistant"),
            msg("meet", 2, image = "/m.jpg").copy(isOfflineMode = true, offlineSessionId = "s1"),
        )
        assertEquals(setOf("meet"), set)
    }
}
