package com.situ.aichat.pet

import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.NotificationTemplateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotificationPayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 2026-09-18 五处小修 #4（T2·Robolectric 真资源 + MockK）：角色文案池里缺宠物分类时，饿/病提醒不再整条跳过，
 * 回落宠物专用的保底短句（与默认文案池同一份 `notif_default_pet_*`）。
 *
 * 缺宠物分类的真实来路：修复 JSON 时模型照「6 键」骨架丢了宠物三类（同批 #2）、生成途中被杀留空池（同批 #3）。
 * 保底文案在此**重新打字**为中文字面量（zh-rCN 资源），并断言它不是连胜类兜底——通用兜底原先对未知分类落
 * 「今天还没聊天」一类的 streak_remind 文案，宠物饿了发这句是答非所问。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PetReminderSchedulerFallbackTest {

    private val now = 1_700_000_000_000L
    private val settings = AppSettings() // petSystemEnabled = true，饥饿衰减 2/h

    private val petHungryFallbacks = listOf("它一直盯着食盆看", "我觉得该喂它了", "咱家小家伙好像饿了")
    private val petSickFallbacks = listOf("它看起来不太舒服", "来看看它好不好吧")

    private val templateDao = mockk<NotificationTemplateDao>()
    private val alarmScheduler = mockk<NotificationAlarmScheduler>(relaxed = true)
    private val characterRepository = mockk<CharacterRepository>().also {
        coEvery { it.get("c") } returns CharacterEntity(uuid = "c", name = "小雨", creationDate = 0L)
    }

    private fun scheduler() = PetReminderScheduler(
        context = RuntimeEnvironment.getApplication(),
        settingsRepo = mockk(relaxed = true),
        petRepository = mockk(relaxed = true),
        characterRepository = characterRepository,
        templateDao = templateDao,
        alarmScheduler = alarmScheduler,
    )

    private fun pet(hunger: Int, phase: String = "none") = CharacterPetEntity(
        uuid = "p", name = "球球", characterUuid = "c",
        hunger = hunger,
        lastInteractionDate = now,
        neglectPhaseRaw = phase,
        petMetadataJson = PetJson.encodeMetadata(PetMetadata(lastDecayDate = now)),
    )

    private fun scheduledBody(pet: CharacterPetEntity): String {
        val payload = slot<NotificationPayload>()
        every { alarmScheduler.scheduleExact(any(), any(), capture(payload)) } returns Unit
        runBlocking { scheduler().reschedule(pet, settings, now) }
        verify(exactly = 1) { alarmScheduler.scheduleExact(any(), now + PetReminderPredictor.WARMUP_MILLIS, any()) }
        return payload.captured.body
    }

    @Test fun hungry_poolMissingPetCategory_fallsBackToPetText() {
        coEvery { templateDao.forCategory("c", "pet_hungry") } returns emptyList()

        val body = scheduledBody(pet(hunger = 70))

        assertTrue("饿了提醒应落宠物饿保底，实际：$body", body in petHungryFallbacks)
    }

    @Test fun sick_poolMissingPetCategory_fallsBackToPetText() {
        coEvery { templateDao.forCategory("c", "pet_sick") } returns emptyList()

        val body = scheduledBody(pet(hunger = 90, phase = "sick"))

        assertTrue("生病提醒应落宠物病保底，实际：$body", body in petSickFallbacks)
    }

    @Test fun poolHasPetText_usesCharacterVoiceTemplate() {
        coEvery { templateDao.forCategory("c", "pet_hungry") } returns listOf(
            NotificationTemplateEntity(characterId = "c", category = "pet_hungry", content = "球球又在扒拉碗了，你快回来"),
        )

        assertEquals("球球又在扒拉碗了，你快回来", scheduledBody(pet(hunger = 70)))
    }
}
