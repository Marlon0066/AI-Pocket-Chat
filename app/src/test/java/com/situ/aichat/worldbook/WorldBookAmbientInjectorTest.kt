package com.situ.aichat.worldbook

import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.moments.MomentCommentPromptBuilder
import com.situ.aichat.moments.MomentCommentPromptStrings
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.diary.DiaryCommentPromptBuilder
import com.situ.aichat.prompt.diary.DiaryCommentPromptStrings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2：外围 5 大场景世界书注入协调器（Q2·2A）与入世隔离（Q3·3A）及提示词零回归验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookAmbientInjectorTest {

    private val dao = mockk<WorldBookDao>()
    private val vectorService = mockk<WorldBookVectorService> {
        coEvery { matchedEntryUuids(any(), any(), any()) } returns emptySet()
    }
    private val settingsRepo = mockk<SettingsRepository> {
        coEvery { getAppSettings() } returns AppSettings()
    }
    private val injector = WorldBookAmbientInjector(dao, vectorService, settingsRepo)

    @Test
    fun `命中常驻与上下文关键词并完成宏替换`() = runBlocking {
        val character = CharacterEntity(uuid = "c1", name = "谢清晏", creationDate = 0L)
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns listOf(
            wbEntry("e-const", constant = true, content = "{{char}}居于听雪楼。"),
            wbEntry("e-kw", keys = listOf("桂花酿"), content = "{{user}}曾赠予一坛桂花酿。"),
        )

        val text = injector.buildAmbientSection(
            character = character,
            userName = "小满",
            contextText = "今晚想喝桂花酿",
            nowMillis = 1_750_000_000_000L,
        )
        assertTrue(text.orEmpty().contains("谢清晏居于听雪楼。"))
        assertTrue(text.orEmpty().contains("小满曾赠予一坛桂花酿。"))
    }

    @Test
    fun `入世角色或无命中时返回null`() = runBlocking {
        val inWorld = CharacterEntity(uuid = "c-world", name = "苏晚", creationDate = 0L, joinedWorld = true)
        assertNull(injector.buildAmbientSection(inWorld, userName = "小满", contextText = "桂花酿"))

        val plain = CharacterEntity(uuid = "c2", name = "陆沉", creationDate = 0L, joinedWorld = false)
        coEvery { dao.activeBooksForCharacter("c2") } returns emptyList()
        assertNull(injector.buildAmbientSection(plain, userName = "小满", contextText = "随便聊聊"))
    }

    @Test
    fun `外围PromptBuilder_有世界书注入设定标题_null时字节级零回归`() {
        val promptStrings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "谢清晏", personalityDescription = "清冷剑修", creationDate = 0L)

        // 1. 朋友圈评论 PromptBuilder
        val commentStrings = MomentCommentPromptStrings.from(promptStrings)
        val baseMomentComment = MomentCommentPromptBuilder.build(
            strings = commentStrings,
            character = character,
            postAuthorName = "小满",
            postTimeDescription = "刚刚",
            postContent = "下雪了",
            isPostByCharacter = false,
            nowContext = "深夜",
            scheduleContext = null,
            photoCount = 0,
            visionEnabled = false,
            existingComments = emptyList(),
            replyTarget = null,
        )
        val withWorldMomentComment = MomentCommentPromptBuilder.build(
            strings = commentStrings,
            character = character,
            postAuthorName = "小满",
            postTimeDescription = "刚刚",
            postContent = "下雪了",
            isPostByCharacter = false,
            nowContext = "深夜",
            scheduleContext = null,
            photoCount = 0,
            visionEnabled = false,
            existingComments = emptyList(),
            replyTarget = null,
            worldInfoSection = "听雪楼终年积雪。",
        )
        assertEquals(
            baseMomentComment,
            MomentCommentPromptBuilder.build(
                strings = commentStrings,
                character = character,
                postAuthorName = "小满",
                postTimeDescription = "刚刚",
                postContent = "下雪了",
                isPostByCharacter = false,
                nowContext = "深夜",
                scheduleContext = null,
                photoCount = 0,
                visionEnabled = false,
                existingComments = emptyList(),
                replyTarget = null,
                worldInfoSection = null,
            ),
        )
        assertTrue(withWorldMomentComment.contains("## 世界观设定\n听雪楼终年积雪。"))

        // 2. 日记评论回复 PromptBuilder
        val diaryStrings = DiaryCommentPromptStrings.from(promptStrings)
        val baseDiary = DiaryCommentPromptBuilder.build(
            strings = diaryStrings,
            characterName = "谢清晏",
            personality = "清冷剑修",
            systemPrompt = "",
            userName = "小满",
            entryContent = "今天练剑三千次。",
        )
        val withWorldDiary = DiaryCommentPromptBuilder.build(
            strings = diaryStrings,
            characterName = "谢清晏",
            personality = "清冷剑修",
            systemPrompt = "",
            userName = "小满",
            entryContent = "今天练剑三千次。",
            worldInfoSection = "听雪楼剑谱共九重。",
        )
        assertEquals(
            baseDiary,
            DiaryCommentPromptBuilder.build(
                strings = diaryStrings,
                characterName = "谢清晏",
                personality = "清冷剑修",
                systemPrompt = "",
                userName = "小满",
                entryContent = "今天练剑三千次。",
                worldInfoSection = null,
            ),
        )
        assertTrue(withWorldDiary.contains("## 世界观设定\n听雪楼剑谱共九重。"))
    }
}
