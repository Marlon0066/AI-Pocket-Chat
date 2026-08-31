package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.ui.chat.TurnMediaAttachments
import com.situ.aichat.ui.chat.TurnMediaAttachments.MAX_ATTACHED_AUDIO
import com.situ.aichat.util.AudioStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T1+T2：历史语音「只挂最近 N 条」的选取策略（2026-08-31 用户拍板 N=3 对齐图片·图纸 C·C3）。
 *
 * 改前是**全量**：窗口 500 条里的每条 user 语音都读盘 + base64 重发。一条 WAV 几十 KB，长聊下
 * 每轮白烧流量与 token；而退出名额的语音**语义不断链**——语音消息的 content 本就是端侧
 * sherpa-onnx 转写，`PromptBuilderHistory` 音频 gate 不命中时自然落纯文本桶。
 *
 * 锁的是选取谓词本身（与图片侧同构三纪律：窗口口径 / 从新到旧 / 读不到不占名额）。
 * **调用产线 [TurnMediaAttachments.selectAudioCandidates] 与 audio() 本体，绝不在测试里复制一份逻辑**
 * ——`ImageAttachmentPolicyTest` 头注释记着复制版全绿的教训。
 */
class AudioAttachmentPolicyTest {

    private fun msg(
        uuid: String,
        ts: Long,
        role: String = "user",
        voice: Boolean = true,
        audio: String? = "/$uuid.wav",
    ) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c1",
        content = "（语音转写）",
        roleRaw = role,
        timestamp = ts,
        isVoiceMessage = voice,
        audioRelativePath = audio,
    )

    private fun config(audioEnabled: Boolean = true) = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
        audioInputEnabled = audioEnabled,
    )

    private fun pick(history: List<MessageEntity>, inOffline: Boolean = false, sessionId: String? = null): List<String> =
        TurnMediaAttachments.selectAudioCandidates(history, inOffline, sessionId)
            .take(MAX_ATTACHED_AUDIO)
            .map { it.messageUUID }

    // ---------- T1-5 选取谓词 ----------

    @Test
    fun `上限是 3 条 与图片同档`() {
        assertEquals(3, MAX_ATTACHED_AUDIO)
    }

    @Test
    fun `少于上限时全挂`() {
        assertEquals(setOf("a", "b"), pick(listOf(msg("a", 1), msg("b", 2))).toSet())
    }

    @Test
    fun `超过上限时只取最近三条`() {
        val history = (1..6).map { msg("m$it", it.toLong()) }
        assertEquals(listOf("m6", "m5", "m4"), pick(history))
    }

    @Test
    fun `本轮刚发的语音恒在名额内`() {
        // E15：最新一条永远排在倒序首位，任何历史长度下都不会被挤掉。
        val history = (1..20).map { msg("old$it", it.toLong()) } + msg("justNow", 99)
        assertTrue("justNow" in pick(history))
        assertEquals("justNow", pick(history).first())
    }

    @Test
    fun `助手侧语音不挂`() {
        // 助手 TTS 语音不喂回模型（1:1 iOS preEncodedMedia 只 pre-encode role==.user）。
        val history = listOf(msg("u", 1), msg("a", 2, role = "assistant"))
        assertEquals(listOf("u"), pick(history))
    }

    @Test
    fun `非语音消息与无音频路径不参与计数`() {
        val history = listOf(
            msg("t1", 1, voice = false, audio = null),
            msg("v1", 2),
            msg("noPath", 3, audio = null),
            msg("v2", 4),
        )
        assertEquals(listOf("v2", "v1"), pick(history))
    }

    @Test
    fun `线上模式下见面里的语音不占名额`() {
        // E13：线上装配整片剔除见面消息（PromptBuilderWindow）——不同口径的话，见面语音会吃光名额，
        // 结果它们压根不进提示词、窗口内本可挂的线上语音反倒退成转写文本。
        val history = listOf(
            msg("online1", 1),
            msg("meet1", 2).copy(isOfflineMode = true, offlineSessionId = "s1"),
            msg("meet2", 3).copy(isOfflineMode = true, offlineSessionId = "s1"),
            msg("meet3", 4).copy(isOfflineMode = true, offlineSessionId = "s1"),
        )
        assertEquals(listOf("online1"), pick(history, inOffline = false))
    }

    @Test
    fun `见面中只认本场的语音`() {
        val history = listOf(
            msg("online1", 1),
            msg("other", 2).copy(isOfflineMode = true, offlineSessionId = "other"),
            msg("mine", 3).copy(isOfflineMode = true, offlineSessionId = "s1"),
        )
        assertEquals(listOf("mine", "online1"), pick(history, inOffline = true, sessionId = "s1"))
    }

    @Test
    fun `空历史给空表`() {
        assertTrue(pick(emptyList()).isEmpty())
    }

    // ---------- T2-6 audio() 本体（凑满 / 坏文件 / 门控）----------

    @Before
    fun setUp() = mockkObject(AudioStore)

    @After
    fun tearDown() = unmockkObject(AudioStore)

    private fun bytesFor(uuid: String) = uuid.toByteArray()

    @Test
    fun `六条可用时恰取最新三条`() = runBlocking {
        val history = (1..6).map { msg("m$it", it.toLong()) }
        coEvery { AudioStore.load(any()) } answers { bytesFor(firstArg<String?>().orEmpty()) }
        val picked = TurnMediaAttachments.audio(history, config(), inOfflineMode = false, currentOfflineSessionId = null)
        assertEquals(setOf("m6", "m5", "m4"), picked.keys)
        assertEquals(PromptBuilder.encodeWavBase64(bytesFor("/m6.wav")), picked["m6"])
    }

    @Test
    fun `读不到的文件不占名额_顺延更早的凑满三条`() = runBlocking {
        // E12：5 条候选里第 2 新的文件丢了（备份未带音频 / 用户清理）→ 结果必须仍是 3 条，
        // 且第 4 新的那条补位。先 take 后 mapNotNull 的朴素写法在这里只会挂 2 条。
        val history = (1..5).map { msg("m$it", it.toLong()) }
        coEvery { AudioStore.load(any()) } answers { bytesFor(firstArg<String?>().orEmpty()) }
        coEvery { AudioStore.load("/m4.wav") } returns null
        val picked = TurnMediaAttachments.audio(history, config(), inOfflineMode = false, currentOfflineSessionId = null)
        assertEquals(listOf("m5", "m3", "m2"), picked.keys.toList())
    }

    @Test
    fun `音频输入关闭时空表且一次都不读盘`() = runBlocking {
        // E14：既有门控行为回归——关闭时连读盘都不该发生（几十 KB×N 的白读）。
        val history = (1..3).map { msg("m$it", it.toLong()) }
        coEvery { AudioStore.load(any()) } answers { bytesFor(firstArg<String?>().orEmpty()) }
        val picked = TurnMediaAttachments.audio(
            history, config(audioEnabled = false), inOfflineMode = false, currentOfflineSessionId = null,
        )
        assertTrue(picked.isEmpty())
        coVerify(exactly = 0) { AudioStore.load(any()) }
    }

    @Test
    fun `全部读不到时空表_不抛错`() = runBlocking {
        val history = (1..4).map { msg("m$it", it.toLong()) }
        coEvery { AudioStore.load(any()) } returns null
        val picked = TurnMediaAttachments.audio(history, config(), inOfflineMode = false, currentOfflineSessionId = null)
        assertTrue(picked.isEmpty())
    }
}
