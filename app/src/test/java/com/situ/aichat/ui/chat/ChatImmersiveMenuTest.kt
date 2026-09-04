package com.situ.aichat.ui.chat

import androidx.compose.ui.geometry.Rect
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ③ 长按沉浸菜单（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL §3·M2）——断言从「只换壳」冻结清单独立反推：
 * 动作显示条件与旧 DropdownMenu 逐字等价（§3.3 矩阵·除 2026-09-04 三项拍板收紧 + 同日引用一期放开语音/表情）、级联数学、菜单定位钳制、
 * 复制口径三分支（日程剥标签/结构化卡绝不露 JSON/普通原样）。
 */
class ChatImmersiveMenuTest {

    // ---- 动作面（§3.3：复制/删除=恒有；重新生成=AI **且落在最后一轮**；引用=**正文有话可引**） ----

    @Test
    fun `actions - user message never gets regenerate even inside last turn`() {
        // 防调用方传错：末尾段判据只装 assistant 的 uuid，但门控自身也守住「用户消息绝不给重新生成」。
        val actions = immersiveMenuActions(isUser = true, canRegenerate = true, canQuote = true)
        assertEquals(
            listOf(ImmersiveMenuAction.COPY, ImmersiveMenuAction.QUOTE, ImmersiveMenuAction.DELETE),
            actions,
        )
    }

    @Test
    fun `actions - ai message in last turn adds regenerate`() {
        val actions = immersiveMenuActions(isUser = false, canRegenerate = true, canQuote = true)
        assertEquals(
            listOf(
                ImmersiveMenuAction.COPY, ImmersiveMenuAction.QUOTE,
                ImmersiveMenuAction.REGENERATE, ImmersiveMenuAction.DELETE,
            ),
            actions,
        )
    }

    @Test
    fun `actions - older ai message hides regenerate`() {
        // 2026-09-04 收紧：长按历史中间某条时不给这一项——点它删掉重来的其实是最后一轮（假选项）。
        val actions = immersiveMenuActions(isUser = false, canRegenerate = false, canQuote = true)
        assertEquals(
            listOf(ImmersiveMenuAction.COPY, ImmersiveMenuAction.QUOTE, ImmersiveMenuAction.DELETE),
            actions,
        )
    }

    @Test
    fun `actions - image message swaps copy for save and drops quote`() {
        // 真实组合：图片消息恒为用户发出（ChatImageSender 落库写死 roleRaw="user"），故无重新生成；
        // 且图片不可引用（2026-09-04 收紧）——真实调用点传的 canQuote 由 messageCanBeQuoted 算出恒 false。
        val actions = immersiveMenuActions(isUser = true, hasImage = true, canQuote = false)
        assertEquals(
            listOf(ImmersiveMenuAction.SAVE_IMAGE, ImmersiveMenuAction.DELETE),
            actions,
        )
    }

    @Test
    fun `actions - save image also replaces copy for ai side`() {
        // 防御性：角色侧目前发不出图片，但门控对 hasImage 的处置与 isUser 无关——将来开放角色发图不必改这里。
        val actions = immersiveMenuActions(isUser = false, hasImage = true, canRegenerate = true, canQuote = false)
        assertEquals(
            listOf(
                ImmersiveMenuAction.SAVE_IMAGE,
                ImmersiveMenuAction.REGENERATE, ImmersiveMenuAction.DELETE,
            ),
            actions,
        )
    }

    @Test
    fun `actions - non quotable text-like message keeps copy but loses quote`() {
        // 日程卡 / 结构化卡 / 占位转写语音这一类：正文仍是文本（「复制」照给），但引用这一项整条不出现。
        val actions = immersiveMenuActions(isUser = true, canQuote = false)
        assertEquals(listOf(ImmersiveMenuAction.COPY, ImmersiveMenuAction.DELETE), actions)
    }

    // ---- 菜单状态对 canRegenerate 的存取与复位（复核 R2 🔵-2：长按当刻的快照，串味会让菜单撒谎） ----

    @Test
    fun `menu state carries and resets canRegenerate`() {
        val state = ChatImmersiveMenuState()
        assertFalse("初始态不给", state.canRegenerate)
        state.open(msg("m1", "assistant"), Rect.Zero, null, null, canRegenerate = true)
        assertTrue(state.canRegenerate)
        state.dismissNow()
        // 必须复位：否则下次长按一条历史消息、快照还停在上一次的 true = 菜单撒谎。
        assertFalse("关闭后须复位", state.canRegenerate)
        state.open(msg("m2", "assistant"), Rect.Zero, null, null, canRegenerate = false)
        assertFalse(state.canRegenerate)
    }

    // ---- 「可引用」判据（2026-09-04 引用一期：正文有话可引才给；长按菜单 / 右滑 / 读屏三路共用单源） ----
    // 穷举图纸 §3.3：纯文字 ✅ / 语音有转写 ✅ / 语音占位转写 ❌ / 纯贴纸 ✅ / 文字+贴纸 ✅ /
    // 图片 ❌ / 空白正文 ❌ / 9 种卡片 ❌。

    /** 「可引用」判据用的最小消息（范围判据那组随 [RegenerableTurn] 挪去 RegenerableTurnTest）。 */
    private fun msg(uuid: String, role: String) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c1",
        roleRaw = role,
        content = "x",
        timestamp = 1_000L,
        messageKindRaw = MessageKind.PLAIN_TEXT.raw,
    )


    @Test
    fun `quotable - plain text bubble is quotable on both sides`() {
        assertTrue(messageCanBeQuoted(msg("m1", "user")))
        assertTrue(messageCanBeQuoted(msg("m2", "assistant")))
    }

    @Test
    fun `quotable - voice message with a real transcript is quotable`() {
        // 引用一期放开：语音的转写就是正文，引用过去是有话可引的。
        assertTrue(messageCanBeQuoted(msg("m1", "user").copy(isVoiceMessage = true, content = "我大概八点到")))
    }

    @Test
    fun `quotable - voice message still holding a placeholder transcript is not quotable`() {
        // STT 未完成 / 失败时正文是占位串（中英两版），零信息量——引用它等于引用了个寂寞。
        assertFalse(messageCanBeQuoted(msg("m1", "user").copy(isVoiceMessage = true, content = "[语音消息]")))
        assertFalse(messageCanBeQuoted(msg("m2", "user").copy(isVoiceMessage = true, content = "  [语音消息]  ")))
        assertFalse(messageCanBeQuoted(msg("m3", "user").copy(isVoiceMessage = true, content = "[Voice Message]")))
    }

    @Test
    fun `quotable - image message is not quotable`() {
        // 图片正文是内部哨兵 `[图片]`——引用它会把三个没意义的字符送进预览、气泡引用头和提示词。
        assertFalse(messageCanBeQuoted(msg("m1", "user").copy(imageRelativePath = "img/a.jpg")))
    }

    @Test
    fun `quotable - sticker only and mixed sticker bubbles are quotable`() {
        // 引用一期放开：引用行注入排在表情转语义之前，`[sticker:x]` 会被下游转成 `[非语言情绪：…]`
        //（PromptBuilderQuoteLineTest 端到端钉），引用表情不再是喂给模型一个零信息的 `[表情包]`。
        assertTrue(messageCanBeQuoted(msg("m1", "user").copy(content = "[sticker:abc]")))
        assertTrue(messageCanBeQuoted(msg("m2", "assistant").copy(content = "哈哈哈[sticker:abc]")))
    }

    @Test
    fun `quotable - blank body is not quotable`() {
        // 流式占位气泡（ChatMessageGrouping 合成·content=""·未落库）引用过去是一条根本不存在的消息。
        assertFalse(messageCanBeQuoted(msg("m1", "assistant").copy(content = "")))
        assertFalse(messageCanBeQuoted(msg("m2", "assistant").copy(content = "   \n  ")))
    }

    @Test
    fun `quotable - structured cards and schedule card are not quotable`() {
        // 卡片的 content 是 JSON：旧行为下右滑引用会把原始 JSON 显进输入栏预览条。
        val notQuotable = listOf(
            MessageKind.RED_PACKET, MessageKind.GIFT_CARD, MessageKind.OFFLINE_INVITE_CARD,
            MessageKind.OFFLINE_END_CARD, MessageKind.FUTURE_MEETING_PROPOSAL_CARD,
            MessageKind.FUTURE_MEETING_CHANGE_CARD, MessageKind.SYSTEM_EVENT_CARD,
            MessageKind.CALL_RECORD_CARD, MessageKind.SCHEDULE_CARD,
        )
        for (kind in notQuotable) {
            assertFalse(kind.name, messageCanBeQuoted(msg("m1", "user").copy(messageKindRaw = kind.raw)))
        }
    }

    @Test
    fun `action labels frozen verbatim`() {
        assertEquals("复制", immersiveMenuActionLabel(ImmersiveMenuAction.COPY))
        assertEquals("保存到相册", immersiveMenuActionLabel(ImmersiveMenuAction.SAVE_IMAGE))
        assertEquals("引用", immersiveMenuActionLabel(ImmersiveMenuAction.QUOTE))
        assertEquals("重新生成", immersiveMenuActionLabel(ImmersiveMenuAction.REGENERATE))
        assertEquals("删除", immersiveMenuActionLabel(ImmersiveMenuAction.DELETE))
    }

    // ---- 级联数学（Telegram cascade·波长 3：窗口=3/n、起点按序错峰）----
    // 用例一律按**生产可达项数**取值：AI 消息 4 项（复制/引用/重新生成/删除）、用户消息 3 项。
    // 2026-09-04 起 5 项不再存在（「改成邀约」已删），旧用例的 n=5 会恒绿但不描述任何真实配置。

    @Test
    fun `cascade - zero at start and one at end for every item`() {
        for (i in 0 until 4) {
            assertEquals(0f, cascadeProgress(0f, i, 4))
            assertEquals(1f, cascadeProgress(1f, i, 4))
        }
    }

    @Test
    fun `cascade - earlier item leads later item mid-flight`() {
        // 4 项（AI 消息实配）必须仍错峰——这条钉住「波长跟不上项数就整卡同步」的退化事故。
        val p0 = cascadeProgress(0.1f, 0, 4)
        val p3 = cascadeProgress(0.1f, 3, 4)
        assertTrue("首项应先行（p0=$p0 p3=$p3）", p0 > p3)
    }

    @Test
    fun `cascade - few items degrade to synchronized (window clamped to 1)`() {
        // n≤3 时窗口=min(1, 3/n)=1 → 全项同步（Telegram 同款退化）；用户消息 3 项即此档，与删除前一致。
        assertEquals(cascadeProgress(0.3f, 0, 3), cascadeProgress(0.3f, 2, 3))
    }

    @Test
    fun `cascade - clamped to unit range`() {
        assertEquals(0f, cascadeProgress(-0.5f, 2, 4))
        assertEquals(1f, cascadeProgress(1.5f, 2, 4))
    }

    // ---- 菜单定位（§3.2：贴气泡对齐缘·下方优先·放不下翻上方·超长气泡钳屏内） ----

    private val screenW = 1080
    private val screenH = 2400
    private val margin = 16
    private val gap = 24

    @Test
    fun `offset - below bubble aligned to end for user message`() {
        val bubble = Rect(500f, 800f, 1000f, 950f)
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = true, margin, gap)
        assertEquals(1000 - 400, off.x) // 右缘对齐气泡右缘
        assertEquals(950 + gap, off.y) // 气泡下方 gap 处
    }

    @Test
    fun `offset - aligned to start for ai message`() {
        val bubble = Rect(60f, 800f, 700f, 950f)
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = false, margin, gap)
        assertEquals(60, off.x)
    }

    @Test
    fun `offset - flips above when bottom overflows`() {
        val bubble = Rect(500f, 1900f, 1000f, 2200f)
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = true, margin, gap)
        assertEquals(1900 - gap - 500, off.y) // 翻到气泡上方
    }

    @Test
    fun `offset - clamped horizontally to screen margin`() {
        val bubble = Rect(0f, 800f, 300f, 950f) // 贴左缘的窄气泡·右对齐会算出负 x
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = true, margin, gap)
        assertEquals(margin, off.x)
    }

    @Test
    fun `offset - huge bubble spanning screen keeps menu inside`() {
        val bubble = Rect(100f, -500f, 1000f, 3000f) // 高于一屏的超长消息
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = false, margin, gap)
        assertTrue(off.y >= gap)
        assertTrue(off.y + 500 <= screenH - gap)
    }

    // ---- 复制口径三分支（自 ChatMessageRow 原样搬迁·冻结） ----

    private fun message(kind: MessageKind, content: String) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c1",
        roleRaw = "assistant",
        content = content,
        timestamp = 1_000L,
        messageKindRaw = kind.raw,
    )

    @Test
    fun `copy - schedule card strips calendar tags and trims`() {
        val text = messageCopyText(message(MessageKind.SCHEDULE_CARD, " 下午三点去公园散步 [#E1] "))
        assertFalse(text.contains("[#E"))
        assertFalse(text.startsWith(" "))
        assertTrue(text.contains("下午三点去公园散步"))
    }

    @Test
    fun `copy - structured card never leaks raw json`() {
        val raw = """{"amount":520,"note":"藏起来的金额"}"""
        val text = messageCopyText(message(MessageKind.RED_PACKET, raw))
        assertFalse(text.contains("{"))
        assertFalse(text.contains("520"))
    }

    @Test
    fun `copy - plain text verbatim`() {
        assertEquals("今晚一起看晚霞", messageCopyText(message(MessageKind.PLAIN_TEXT, "今晚一起看晚霞")))
    }
}
