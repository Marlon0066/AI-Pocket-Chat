package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.pet.PetJson
import com.situ.aichat.pet.PetMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 通话回合不教 `[PET:…]`（2026-09-18 提示词册核对 A 条）：通话侧（TTS 逐句清理 / 通话落库）只走 ReplyParser，
 * 不提取宠物发言；`[PET:喵~]` 里的「~」还是 TTS 断句符，逐句正则剥不干净 → 教了就会被念出来并原样落库。
 * 所以真通话（scene=VOICE_CALL）只去掉那一行指令，宠物状态其余照注；文字回合与聊天语音消息回合逐字不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderVoiceCallPetLineTest {

    /** 宠物指令行原文（重新打字的字面量·与 PromptBuilderPet 逐字一致）。 */
    private val petSpeechLine =
        "- 可以用 [PET:内容] 让宠物简短说话。例：[PET:喵~] 或 [PET:汪汪！]。节制使用（最多每 4-5 次回复一次），15 字以内，符合物种的声音特点。"

    private fun systemText(
        deliveryMode: PromptBuilder.AssistantDeliveryMode,
        scene: PromptScene,
    ): String {
        val pet = CharacterPetEntity(
            uuid = "p", name = "球球", speciesRaw = "cat", personalityTypeRaw = "lively",
            growthStageRaw = "baby", hunger = 0, cleanliness = 100, happiness = 80, health = 100,
            petMetadataJson = PetJson.encodeMetadata(PetMetadata.EMPTY), characterUuid = "c1",
        )
        val messages = PromptBuilder.buildMessages(
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
            sortedMessages = listOf(
                MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L),
            ),
            userProfile = UserProfileEntity(nickname = "阿哲"),
            appSettings = AppSettings(petSystemEnabled = true),
            strings = PromptStrings(RuntimeEnvironment.getApplication()),
            pet = pet,
            now = Instant.ofEpochMilli(1_700_000_000_000L),
            assistantDeliveryMode = deliveryMode,
            scene = scene,
        )
        return messages.filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
    }

    @Test
    fun 文字回合_宠物状态与PET指令行都在() {
        val s = systemText(PromptBuilder.AssistantDeliveryMode.TEXT, PromptScene.ONLINE_CHAT)
        assertTrue("宠物状态段应在：\n$s", s.contains("[宠物状态]"))
        assertEquals("PET 指令行应恰好一次", 1, s.split(petSpeechLine).size - 1)
    }

    @Test
    fun 真通话_只去掉PET指令行_宠物状态照注() {
        val s = systemText(PromptBuilder.AssistantDeliveryMode.VOICE, PromptScene.VOICE_CALL)
        // 正向锚：宠物状态段仍在（防「宠物模块整段没装配」的假绿）。
        assertTrue("通话回合宠物状态段仍应在：\n$s", s.contains("[宠物状态]"))
        assertTrue("通话回合宠物名仍应在", s.contains("球球"))
        assertFalse("通话回合不该教 [PET:…]", s.contains("[PET:"))
    }

    @Test
    fun 聊天语音消息回合_不是通话_PET指令行照注() {
        // deliveryMode=VOICE 但 scene=在线聊天：这条路经 ChatReplyDeliverer 先 extractPetSpeech 再合成语音，不会念出来。
        val s = systemText(PromptBuilder.AssistantDeliveryMode.VOICE, PromptScene.ONLINE_CHAT)
        assertEquals("语音消息回合 PET 指令行应恰好一次", 1, s.split(petSpeechLine).size - 1)
    }
}
