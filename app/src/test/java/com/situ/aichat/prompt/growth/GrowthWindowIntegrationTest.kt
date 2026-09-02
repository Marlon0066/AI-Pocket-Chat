package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-2（活人感内核卷零图纸 §7.2）：协调器接窗口后的**省钱闸**与前置轮标注行。
 *
 * 换窗口最实在的收益之一：上次分析之后一句话都没聊时，旧口径照样把「最近 200 条」老对话再喂一遍
 * LLM（花钱且会把同一段对话重复计分），新口径 `fresh` 为空即早退。本例用 `coVerify(exactly = 0)`
 * 钉死「一次 LLM 都不调」。Robolectric：协调器内有 android.util.Log。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GrowthWindowIntegrationTest {

    private val service = mockk<GrowthAnalysisService>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val milestoneDao = mockk<MilestoneDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val throttle = mockk<MaintenanceThrottleStore>(relaxed = true)
    private val coordinator = GrowthAnalysisCoordinator(
        service, characterDao, milestoneDao, CharacterWriteLock(), settingsRepo, throttle, AffectKernel(characterDao), IntentKernel(characterDao),
        Clock.systemDefaultZone(),
    )

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k", baseUrl = "https://x", modelName = "m",
    )

    private fun character(lastAnalysisDate: Long?) = CharacterEntity(
        uuid = "u", name = "夏晴子", creationDate = 0L,
        personalitySpectrumJSON = GrowthJson.encode(PersonalitySpectrum.NEUTRAL),
        relationshipQualityJSON = GrowthJson.encode(RelationshipQuality()),
        growthMetadataJSON = GrowthJson.encode(GrowthAnalysisMetadata(lastAnalysisDate = lastAnalysisDate)),
    )

    private fun msg(ts: Long, role: String) = MessageEntity(
        messageUUID = "m$ts", conversationUuid = "c", roleRaw = role, content = "hi", timestamp = ts,
    )

    @Test fun `上次分析后零新消息 - 不调 LLM 且不写库`() = runTest {
        coEvery { characterDao.getByUuid("u") } returns character(lastAnalysisDate = 5_000L)
        coEvery { service.collectAnalysisWindow("u", 5_000L) } returns
            AnalysisWindow(leadIn = listOf(msg(1_000L, "user")), fresh = emptyList())

        val error = runCatching {
            coordinator.analyzeAndPersist("u", config, "小明", AppSettings())
        }.exceptionOrNull()

        assertTrue("应抛 NoMessages 早退", error is GrowthAnalysisError.NoMessages)
        coVerify(exactly = 0) { service.analyzeGrowth(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { characterDao.updateGrowthAnalysis(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `协调器把窗口的 leadIn 条数原样传给分析服务`() = runTest {
        val leadIn = listOf(msg(1_000L, "user"), msg(1_100L, "assistant"))
        val fresh = listOf(msg(6_000L, "user"), msg(6_100L, "assistant"))
        coEvery { characterDao.getByUuid("u") } returns character(lastAnalysisDate = 5_000L)
        coEvery { service.collectAnalysisWindow("u", 5_000L) } returns AnalysisWindow(leadIn, fresh)
        coEvery {
            service.analyzeGrowth(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns GrowthAnalysisResult(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList(), "n")

        coordinator.analyzeAndPersist("u", config, "小明", AppSettings())

        // 喂给 LLM 的是 leadIn + fresh 全窗口，且 leadIn 条数如实透传（提示词据此插标注行）
        coVerify(exactly = 1) {
            service.analyzeGrowth(
                messages = leadIn + fresh, characterName = any(), spectrum = any(), quality = any(),
                interests = any(), config = any(), userName = any(), scheduleSystemEnabled = any(),
                characterUuid = any(), nowMillis = any(), leadInMessageCount = 2,
            )
        }
    }

    // MARK: - 前置轮标注行（锁定文本·图纸 §9.1）

    private val realService = GrowthAnalysisService(
        contextLog = mockk(relaxed = true), conversationDao = mockk(relaxed = true),
        messageDao = mockk(relaxed = true), scheduleDao = mockk(relaxed = true),
    )

    private fun promptWith(messages: List<MessageEntity>, leadInCount: Int): String =
        realService.buildAnalysisPrompt(
            messages = messages, characterName = "夏晴子", spectrum = PersonalitySpectrum(),
            quality = RelationshipQuality(), interests = emptyList(), userName = "小明",
            scheduleAnalysis = "", leadInMessageCount = leadInCount,
        ).second

    @Test fun `标注行逐字锁定且写真实轮数`() {
        // 前置 2 轮（2 条 user + 2 条 assistant），其后是新内容
        val messages = listOf(
            msg(1_000L, "user"), msg(1_100L, "assistant"),
            msg(1_200L, "user"), msg(1_300L, "assistant"),
            msg(6_000L, "user"), msg(6_100L, "assistant"),
        )
        val prompt = promptWith(messages, leadInCount = 4)
        assertTrue(
            "标注行必须逐字出现且轮数写真实的 2（不足 4 轮不写 4）",
            prompt.contains("以上 2 轮已在上次分析中计过分，只供你理解语境，不要重复计分。"),
        )
        // 标注行必须夹在前置段与新内容段之间
        val annotationIdx = prompt.indexOf("以上 2 轮已在上次分析中")
        assertTrue(prompt.indexOf("[") in 0 until annotationIdx)   // 前置段在前
        assertTrue(prompt.lastIndexOf("]") > annotationIdx)        // 新内容段在后
    }

    @Test fun `无前置时整行不输出且与旧行为逐字节相同`() {
        val messages = listOf(msg(6_000L, "user"), msg(6_100L, "assistant"))
        val withZero = promptWith(messages, leadInCount = 0)
        val legacy = realService.buildAnalysisPrompt(
            messages = messages, characterName = "夏晴子", spectrum = PersonalitySpectrum(),
            quality = RelationshipQuality(), interests = emptyList(), userName = "小明",
            scheduleAnalysis = "",
        ).second
        assertEquals("默认参 0 必须逐字节回退旧行为", legacy, withZero)
        assertTrue(!withZero.contains("已在上次分析中计过分"))
    }

    @Test fun `前置段里一条 user 都没有时不输出标注行`() {
        val messages = listOf(
            msg(1_000L, "assistant"), msg(1_100L, "assistant"),
            msg(6_000L, "user"), msg(6_100L, "assistant"),
        )
        val prompt = promptWith(messages, leadInCount = 2)
        assertTrue(!prompt.contains("已在上次分析中计过分"))
    }
}
