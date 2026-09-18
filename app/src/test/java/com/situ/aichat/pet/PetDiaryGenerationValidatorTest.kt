package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 宠物日记脏数据门（2026-09-18 提示词册核对 C 条）：宠物日记过去只判「非空」，服务商偶发返回的
 * 「Token count: 2937」「{"error": …}」会被原样存成当天宠物日记（且当天不会再生成）。现与日记 / 朋友圈 /
 * 评论同过 [com.situ.aichat.prompt.GeneratedContentValidator]。
 *
 * 手法（T2）：真跑 [PetDiaryGenerationService.checkAndAutoGenerate] 整条门控链，MockK 假掉设置 / 宠物 / 日记库 /
 * API 配置 / LLM 出口；断言「是否落库」这一件用户可见结果。正常正文落库一例作对照，证明门控链确实跑到了落库点。
 */
class PetDiaryGenerationValidatorTest {

    private val settingsRepo = mockk<SettingsRepository>()
    private val petRepository = mockk<PetRepository>()
    private val diaryRepository = mockk<DiaryRepository>(relaxed = true)
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val contextLog = mockk<ContextLogService>()
    private lateinit var service: PetDiaryGenerationService

    private val pet = CharacterPetEntity(
        uuid = "p", name = "球球", speciesRaw = "cat", personalityTypeRaw = "lively",
        growthStageRaw = "baby", hunger = 0, cleanliness = 100, happiness = 80, health = 100,
        petMetadataJson = PetJson.encodeMetadata(PetMetadata.EMPTY), characterUuid = "c",
    )

    @Before
    fun setUp() {
        coEvery { settingsRepo.getAppSettings() } returns
            AppSettings(petSystemEnabled = true, petDiaryAutoGenerateEnabled = true)
        coEvery { diaryRepository.hasPetDiaryToday(any()) } returns false
        coEvery { petRepository.getAll() } returns listOf(pet)
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk(relaxed = true)
        service = PetDiaryGenerationService(
            settingsRepo = settingsRepo,
            petRepository = petRepository,
            diaryRepository = diaryRepository,
            apiConfigRepo = apiConfigRepo,
            contextLog = contextLog,
            userProfileDao = mockk(relaxed = true),
        )
    }

    private fun llmReturns(text: String) {
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns text
    }

    @Test
    fun 服务商调试串_TokenCount_不存成宠物日记() = runBlocking {
        llmReturns("Token count: 2937")
        service.checkAndAutoGenerate()
        coVerify(exactly = 0) { diaryRepository.upsert(any()) }
    }

    @Test
    fun 正文里塞了JSON错误体_不存成宠物日记() = runBlocking {
        llmReturns("""{"error": "invalid_api_key", "code": 401}""")
        service.checkAndAutoGenerate()
        coVerify(exactly = 0) { diaryRepository.upsert(any()) }
    }

    @Test
    fun 对照_正常宠物口吻正文_照常落库() = runBlocking {
        val text = "今天主人回来得好晚，我在门口等了很久，最后在拖鞋上睡着了。喵。"
        llmReturns(text)
        service.checkAndAutoGenerate()
        val saved = slot<DiaryEntryEntity>()
        coVerify(exactly = 1) { diaryRepository.upsert(capture(saved)) }
        assertEquals(text, saved.captured.content)
        assertTrue("应标记为宠物日记", saved.captured.isPetDiary)
    }
}
