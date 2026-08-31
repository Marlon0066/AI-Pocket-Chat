package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteData
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.offline.OfflineMarkerEndPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 线下邀约/离场「留痕行」全装配 T2（留痕改造 2026-08-31·图纸 §7 T2-2/T2-3·照
 * [PromptBuilderFutureMeetingLeakTest] 骨架）：邀约卡（[MessageKind.OFFLINE_INVITE_CARD]）与离场标记
 * （[MessageKind.OFFLINE_MARKER_END]）经 **主聊天历史→LLM 的真装配路径** [PromptBuilder.buildMessages]
 * 后，必须以脱敏的 `[系统记录：…]` 留痕行出现在提示词里，且原文（卡 JSON / 台词 / 心事种子 / 标记的
 * 【重要】指令段）零泄漏。
 *
 * 回归方向（本改造的病根）：两者此前在 [prepareFilteredRecentMessages] 被整条剥离且**无任何替身**——
 * 模型看不见自己发过的邀约、也看不见见面已结束，于是「失忆式重复邀约」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderOfflineInviteStandInTest {

    private val invitationLine = "走吧，我知道一家新开的咖啡厅~"
    private val tensionHintText = "她今天有点心事"
    private val hiddenTensionText = "她其实在等一个消息"

    private fun inviteJson(responded: String?): String = OfflineInviteJson.encode(
        OfflineInviteData(
            type = OfflineInviteJson.TYPE_INVITE,
            location = "咖啡馆",
            activity = "喝咖啡",
            invitation = invitationLine,
            tensionHint = tensionHintText,
            hiddenTension = hiddenTensionText,
            responded = responded,
        ),
    )

    /** 组装「用户问 → 邀约卡 →（可选）离场标记」的最小会话，返回整条 prompt 的拼接文本。 */
    private fun promptWith(cardContent: String, withMarkerEnd: Boolean = false): String {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val userMsg = MessageEntity(
            messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "那我们约一个见面?", timestamp = 1L,
        )
        val card = MessageEntity(
            messageUUID = "m1",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = cardContent,
            timestamp = 2L,
            messageKindRaw = MessageKind.OFFLINE_INVITE_CARD.raw,
        )
        val markerEnd = MessageEntity(
            messageUUID = "m2",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = OfflineMarkerEndPayload("约40分钟", "16:00", "你们自然地结束了这次见面").makeContent(),
            timestamp = 3L,
            isOfflineMode = true,
            offlineSessionId = "s1",
            messageKindRaw = MessageKind.OFFLINE_MARKER_END.raw,
        )
        val messages = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = if (withMarkerEnd) listOf(userMsg, card, markerEnd) else listOf(userMsg, card),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            pet = null,
        )
        return messages.joinToString("\n") { it.content.orEmpty() }
    }

    /**
     * 取历史里那条**真的邀约留痕行**。
     *
     * ⚠️ 不能用「整篇 prompt 含『发出了线下见面邀约』」当探针：知情邀约规则
     * [com.situ.aichat.offline.OfflineMeetingAction.INFORMED_INVITE_RULES] 自己就引用了这句措辞
     *（「历史里的 [系统记录：…发出了线下见面邀约…] 这类记录，是…」）→ 恒真。留痕行的独有特征 = **行首**就是
     * `[系统记录：`（规则那行以「历史里的」开头）。
     */
    private fun standInLines(prompt: String): List<String> =
        prompt.lines().filter { it.startsWith("[系统记录：") && it.contains("线下见面邀约") }

    @Test
    fun `婉拒的邀约卡与离场标记双双留痕进 prompt`() {
        val prompt = promptWith(inviteJson("declined"), withMarkerEnd = true)
        // 称呼词随用户名解析（此处 userProfile=null → pb_user_fallback），故断言用**名字无关**的稳定子串。
        assertEquals("应恰有一条邀约留痕行，实际：$prompt", 1, standInLines(prompt).size)
        // ⚠️ 状态断言一律**行级**：2026-08-31 追订后「还没回应」也出现在知情邀约规则 bullet 里，
        // 整篇 prompt 级的 contains 已是恒真坏探针（规则 bullet 2/3 同理含「对方」）。
        val standInLine = standInLines(prompt).single()
        assertTrue("应含实时婉拒状态，实际：$standInLine", standInLine.contains("婉拒了，这次没见成"))
        assertFalse("留痕行不得含通用代号「对方」，实际：$standInLine", standInLine.contains("对方"))
        // 双名第三人称（2026-08-31 终拍板）：角色名侧**可精确钉**——charName 由本测试构造（"小雨"），
        // 不像用户名回退那样随 locale 资源变；「你向」制式一旦回潮这条即红。
        assertTrue("留痕行应以角色真名起头，实际：$standInLine", standInLine.contains("小雨向"))
        assertFalse("留痕行不得回到「你向」制式，实际：$standInLine", standInLine.contains("你向"))
        assertTrue(prompt.contains("地点=咖啡馆"))
        assertTrue(prompt.contains("活动=喝咖啡"))
        assertTrue("应含离场留痕行，实际：$prompt", prompt.contains("线下见面结束（约40分钟）"))
        // 离场留痕行的无视角措辞（该短语只出现在离场留痕行·非恒真探针）。
        assertTrue("离场留痕行应用「两人」，实际：$prompt", prompt.contains("两人回到了线上聊天"))
    }

    @Test
    fun `卡片原文与标记指令段绝不进 prompt`() {
        val prompt = promptWith(inviteJson("declined"), withMarkerEnd = true)
        // ⚠️ 泄漏探针不能用裸 "offline_invite"：**守卫提示词自身**就含 `[offline_invite|…]` 暗号格式与
        // `{"type":"offline_invite",...}` 反面例句 → 恒真的假阳性。改钉卡 JSON 独有的字段值序列。
        assertFalse("不应漏卡原文 JSON，实际：$prompt", prompt.contains("\"location\":\"咖啡馆\""))
        assertFalse(prompt.contains("\"activity\":\"喝咖啡\""))
        assertFalse("不应漏 hiddenTension，实际：$prompt", prompt.contains(hiddenTensionText))
        assertFalse("不应漏 invitation 台词，实际：$prompt", prompt.contains(invitationLine))
        assertFalse("不应漏 tensionHint，实际：$prompt", prompt.contains(tensionHintText))
        // 离场标记原文（含只服务见面刚结束那一轮的【重要】段）不该永驻历史。
        assertFalse("不应漏标记原文，实际：$prompt", prompt.contains("【线下见面结束 |"))
        assertFalse("不应漏【重要】指令段，实际：$prompt", prompt.contains("从现在起不要再使用"))
        // 留痕措辞的负向约束：绝不含「邀约卡片」连写（否则模型复读即被 sysRecordInviteRegex 解析成新卡）。
        // 探针必须**只看留痕行本身**——守卫提示词末句「…显示为邀约卡片」会让整篇 prompt 的同名探针恒真。
        val standInLine = standInLines(prompt).single()
        assertFalse("留痕行不得含「邀约卡片」连写，实际：$standInLine", standInLine.contains("邀约卡片"))
    }

    @Test
    fun `损坏的卡 JSON 整条消失_原文绝不漏`() {
        val corrupted = """{"type":"offline_invite","location":"咖啡馆","activity":"喝咖"""  // 截断的 JSON
        val prompt = promptWith(corrupted)
        // 同上：探针钉损坏 JSON 独有的片段（守卫提示词含 "offline_invite" 字样，不能当探针）。
        assertFalse("解析失败应整条跳过，实际：$prompt", prompt.contains("\"location\":\"咖啡馆\""))
        assertTrue("解析失败不应产出任何留痕行，实际：$prompt", standInLines(prompt).isEmpty())
        assertTrue("同窗口的普通消息仍在", prompt.contains("那我们约一个见面?"))
    }

    @Test
    fun `接受态渲染为过去式已见面`() {
        val prompt = promptWith(inviteJson("accepted"))
        val standInLine = standInLines(prompt).single()
        assertTrue("实际：$standInLine", standInLine.contains("接受了，两人随后见了面"))
        assertFalse("实际：$standInLine", standInLine.contains("还没回应"))
        assertFalse("留痕行不得含通用代号「对方」，实际：$standInLine", standInLine.contains("对方"))
        assertFalse("留痕行不得回到「你向」制式，实际：$standInLine", standInLine.contains("你向"))
    }

    @Test
    fun `未回应态渲染为还没回应`() {
        val prompt = promptWith(inviteJson(null))
        val standInLine = standInLines(prompt).single()
        assertTrue("实际：$standInLine", standInLine.contains("还没回应"))
        assertFalse("实际：$standInLine", standInLine.contains("婉拒"))
        assertFalse("留痕行不得含通用代号「对方」，实际：$standInLine", standInLine.contains("对方"))
        assertFalse("留痕行不得回到「你向」制式，实际：$standInLine", standInLine.contains("你向"))
    }
}
