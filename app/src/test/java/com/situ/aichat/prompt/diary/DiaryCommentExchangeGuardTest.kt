package com.situ.aichat.prompt.diary

import android.content.Context
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MomentTriggerType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.util.LocaleManager
import com.situ.aichat.work.BackgroundScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * T2（MockK 全假）：「大家来评论」批的交换日记守卫（图纸 2026-09-05 日记编辑丢作者根治·J-4）。
 * TA 写的信不安排别的角色来评论——它的互动只走 R6-1（用户留言 → 作者本人回应）。正常流程本就不给信
 * 调度评论；守卫兜住「把信存成草稿 → 再一键发布」这条（编辑放开后）新可达路径。
 *
 * 判别力：守卫下在 [DiaryCommentService.generateCommentsForEntry] 开头、`resolveConfigValues` 之前 →
 * 「信 = 一次都不查 API 配置」与「用户日记 = 照查」构成正反对照，守卫误伤/失效任一侧都会翻红。
 */
class DiaryCommentExchangeGuardTest {

    private val context = mockk<Context>(relaxed = true)
    private val contextLog = mockk<ContextLogService>(relaxed = true)
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val diaryRepository = mockk<DiaryRepository>(relaxed = true)
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val userProfileDao = mockk<UserProfileDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>()
    private val service = DiaryCommentService(
        context = context,
        contextLog = contextLog,
        apiConfigRepo = apiConfigRepo,
        diaryRepository = diaryRepository,
        characterDao = characterDao,
        userProfileDao = userProfileDao,
        settingsRepo = settingsRepo,
        backgroundScheduler = mockk<BackgroundScheduler>(relaxed = true),
    )

    /** TA 的信：作者非空 + triggerTypeRaw='exchange'（openToAI，好让「守卫不生效」时能一路走下去）。 */
    private val letter = DiaryEntryEntity(
        uuid = "d1",
        content = "TA 写给你的信",
        visibilityRaw = "openToAI",
        triggerTypeRaw = MomentTriggerType.EXCHANGE.raw,
        authorCharacterUuid = "c1",
        authorNameSnapshot = "小柚",
    )

    /** 用户自己的日记：作者为空，其余条件与上面完全相同（唯一变量 = 作者位）。 */
    private val mine = DiaryEntryEntity(uuid = "d1", content = "我自己的日记", visibilityRaw = "openToAI")

    @Before fun setUp() {
        mockkObject(LocaleManager)
        every { LocaleManager.wrap(any()) } returns context
        every { context.getString(any()) } returns "s"
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>()
        // 互动开关关掉：正向对照走到「配置已查、开关不通过」即停，全程零 LLM 调用。
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(diaryCharacterInteractionEnabled = false)
    }

    @After fun tearDown() = unmockkObject(LocaleManager)

    @Test fun `TA 的信不安排大家来评论`(): Unit = runBlocking {
        coEvery { diaryRepository.getEntry("d1") } returns letter

        service.generateCommentsForEntry("d1")

        coVerify(exactly = 0) { apiConfigRepo.resolveConfigValues(any()) }
        coVerify(exactly = 0) { diaryRepository.addComment(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { diaryRepository.addReaction(any(), any(), any()) }
        coVerify(exactly = 0) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `用户自己的日记照旧走既有路径`(): Unit = runBlocking {
        coEvery { diaryRepository.getEntry("d1") } returns mine

        service.generateCommentsForEntry("d1")

        // 守卫不误伤：走过守卫 → 查 API 配置 → 读设置（在此被互动开关早退，非被守卫拦下）。
        coVerify(exactly = 1) { apiConfigRepo.resolveConfigValues(any()) }
        coVerify(exactly = 1) { settingsRepo.getAppSettings() }
    }
}
