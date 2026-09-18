package com.situ.aichat.prompt.notification

import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NotificationTemplateGenerator 单测（对齐 iOS parseResponse 解析逻辑）：多候选 JSON 解析 + 按分类上限裁剪。
 * 活人感二期 M3 · T2-2：buildUserPrompt 预烘焙喂记忆（结构化记忆非空追加行 / 全空逐字节不变·E7）。
 * 盲区补扫 B4 · T2-B4：预烘焙通知第三人称指名——buildUserPrompt「用户」→真名（§9⑤）；「你们」代词保留不动（J-6）；
 * 空昵称回退「用户」在 generateAndSave 的 ifBlank 解析处（§3·buildUserPrompt 用 ${userName} 逐字），故用行为测在真实解析点验。
 * Robolectric：generateAndSave 成功路径打 android.util.Log（纯 JUnit 会「Method not mocked」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTemplateGeneratorTest {

    /** buildUserPrompt 只读 character/relationship/userName → 依赖全 relaxed mock 构造即可。 */
    private fun generator() = NotificationTemplateGenerator(
        context = mockk(relaxed = true),
        llmClient = mockk(relaxed = true),
        contextLog = mockk(relaxed = true),
        templateDao = mockk(relaxed = true),
        userProfileDao = mockk(relaxed = true),
    )

    private fun character(structured: StructuredMemory) = CharacterEntity(
        uuid = "c1", name = "小雨", creationDate = 0L, structuredMemoryJSON = structured.encode(),
    )

    @Test fun parsesPlainJsonObject() {
        val raw = """{"streak_remind":["a","b"],"morning":["m1"]}"""
        val map = NotificationTemplateGenerator.parseTemplatesMap(raw)
        assertEquals(listOf("a", "b"), map?.get("streak_remind"))
        assertEquals(listOf("m1"), map?.get("morning"))
    }

    @Test fun parsesFencedJson() {
        val raw = "```json\n{\"random\":[\"hi\"]}\n```"
        val map = NotificationTemplateGenerator.parseTemplatesMap(raw)
        assertEquals(listOf("hi"), map?.get("random"))
    }

    @Test fun returnsNullOnGarbage() {
        assertNull(NotificationTemplateGenerator.parseTemplatesMap("not json at all"))
    }

    @Test fun capsEachCategoryToRequiredCount() {
        // streak_remind 上限 5：给 7 条只保留前 5
        val map = mapOf("streak_remind" to (1..7).map { "t$it" })
        val built = NotificationTemplateGenerator.buildTemplatesFromMap(map, "c1", now = 0L)
        assertEquals(5, built.size)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), built.map { it.content })
        assertTrue(built.all { it.characterId == "c1" && it.category == "streak_remind" })
    }

    @Test fun skipsBlankAndUnknownCategories() {
        val map = mapOf(
            "evening" to listOf("e1", "  ", "e2"),   // 空白条跳过
            "not_a_category" to listOf("x"),          // 未知分类忽略
        )
        val built = NotificationTemplateGenerator.buildTemplatesFromMap(map, "c1", now = 0L)
        assertEquals(listOf("e1", "e2"), built.map { it.content })
    }

    @Test fun totalRequirementsMatchIos() {
        // iOS categoryRequirements 合计 27（5+3+3+3+3+3+3+2+2）
        assertEquals(27, NotificationTemplateGenerator.CATEGORY_REQUIREMENTS.sumOf { it.second })
    }

    // ---- 活人感二期 M3 · T2-2：buildUserPrompt 预烘焙喂记忆（§3.3·E7）+ 盲区补扫 B4 真名指名（§9⑤）----

    @Test fun 预烘焙_结构化记忆非空_称呼关系用真名_你们代词保留() {
        val char = character(
            StructuredMemory(
                nicknameFromChar = "小笨蛋",
                insideJoke = "总把咖啡叫可乐",
                sharedLikes = "深夜的爵士乐",
            ),
        )
        val prompt = generator().buildUserPrompt(char, relationship = "恋人", userName = "小明")
        // B4：称呼 / 关系两处「用户」→真名。
        assertTrue("称呼行用真名", prompt.contains("TA 对小明的称呼：小笨蛋"))
        assertFalse("无通用码称呼", prompt.contains("TA 对用户的称呼"))
        assertTrue("关系行用真名", prompt.contains("和小明的关系：恋人"))
        assertFalse("无通用码关系", prompt.contains("和用户的关系"))
        // J-6：「你们」是第二人称复数代词，逐字保留不动。
        assertTrue("内部梗「你们」保留", prompt.contains("你们之间的内部梗：总把咖啡叫可乐"))
        assertTrue("共同喜欢「你们」保留", prompt.contains("你们共同喜欢：深夜的爵士乐"))
        assertTrue("含引导行", prompt.contains("上面这些相处痕迹可以自然融进部分文案"))
    }

    @Test fun 预烘焙_部分字段空_只出非空行且引导行仍在() {
        // 只有称呼非空：另两行不出，但至少一行在 → 引导行仍追加。
        val char = character(StructuredMemory(nicknameFromChar = "阿柚"))
        val prompt = generator().buildUserPrompt(char, relationship = null, userName = "小明")
        assertTrue(prompt.contains("TA 对小明的称呼：阿柚"))
        assertFalse("内部梗空 → 不出行", prompt.contains("你们之间的内部梗"))
        assertFalse("共同喜欢空 → 不出行", prompt.contains("你们共同喜欢"))
        assertTrue("有一行 → 引导行追加", prompt.contains("上面这些相处痕迹可以自然融进部分文案"))
    }

    @Test fun 预烘焙_结构化记忆全空_prompt不含任何预烘焙行_E7() {
        // 全空 → 整段不追加，除真名指名外与现状逐字节一致（无四个标记串中的任何一个）。
        val char = character(StructuredMemory.EMPTY)
        val prompt = generator().buildUserPrompt(char, relationship = "朋友", userName = "小明")
        assertFalse(prompt.contains("TA 对小明的称呼"))
        assertFalse(prompt.contains("你们之间的内部梗"))
        assertFalse(prompt.contains("你们共同喜欢"))
        assertFalse(prompt.contains("上面这些相处痕迹"))
        // 现状锚点仍在（证明只是没追加、其余不变），关系行用真名。
        assertTrue(prompt.contains("角色名字：小雨"))
        assertTrue(prompt.contains("和小明的关系：朋友"))
    }

    // ---- 盲区补扫 B4 · T2-B4：generateAndSave 解析真实用户名并注入 LLM prompt（真名 + 空名兜底 E1）----

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    // 20 条（≥ 27/2 门槛）确保 parseResponse 不回落默认，走 replaceForCharacter 成功路径。
    private val validTemplatesJson =
        """{"streak_remind":["a","b","c","d","e"],"streak_urgent":["f","g","h"],""" +
            """"streak_broken":["i","j","k"],"morning":["l","m","n"],""" +
            """"evening":["o","p","q"],"random":["r","s","t"]}"""

    /** 驱动 generateAndSave 成功路径，捕获喂 LLM 的 user 消息；[nickname] null → 触发 ifBlank 兜底。 */
    private fun capturedUserPrompt(nickname: String?): String = runBlocking {
        val contextLog = mockk<ContextLogService>()
        val userProfileDao = mockk<UserProfileDao>()
        val templateDao = mockk<NotificationTemplateDao>(relaxed = true)
        coEvery { userProfileDao.get() } returns nickname?.let { UserProfileEntity(nickname = it) }
        val captured = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(captured), any(), any(), any(), any(), any())
        } returns validTemplatesJson
        val gen = NotificationTemplateGenerator(
            context = mockk(relaxed = true),
            llmClient = mockk(relaxed = true),
            contextLog = contextLog,
            templateDao = templateDao,
            userProfileDao = userProfileDao,
        )
        gen.generateAndSave(character(StructuredMemory.EMPTY), relationship = "恋人", config = config)
        captured.captured.first { it.role == "user" }.content.orEmpty()
    }

    @Test fun generateAndSave_realNickname_injectsNameIntoPrompt() {
        val userMsg = capturedUserPrompt("小明")
        assertTrue("真名进 prompt", userMsg.contains("和小明的关系：恋人"))
        assertFalse("无通用码", userMsg.contains("和用户的关系"))
    }

    @Test fun generateAndSave_blankNickname_fallsBackToUser_E1() {
        val userMsg = capturedUserPrompt(null)
        assertTrue("空昵称 → 兜底「用户」", userMsg.contains("和用户的关系：恋人"))
        assertFalse("未兜底会渲染空名", userMsg.contains("和的关系"))
    }

    // ---- 2026-09-18 五处小修 #2：JSON 修复提示词必须列全 9 个分类键（原写死 6 键、漏宠物 3 类） ----

    /**
     * 首次回复解析失败 → 走 LLM 修复；截获修复那一次的 system 提示词。键名在此**重新打字**（不引用
     * CATEGORY_REQUIREMENTS），漏任何一个 = 修复后的 JSON 可能丢掉该分类（宠物提醒随之无文案）。
     */
    @Test fun repairPrompt_listsAllNineCategoryKeys() = runBlocking {
        val contextLog = mockk<ContextLogService>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "文案如下：streak_remind 是……（不是 JSON）"
        val llmClient = mockk<LlmClient>()
        val repairMessages = slot<List<ChatMessageDto>>()
        coEvery {
            llmClient.completion(capture(repairMessages), any(), any(), any(), any(), any(), any())
        } returns validTemplatesJson
        val userProfileDao = mockk<UserProfileDao>()
        coEvery { userProfileDao.get() } returns null
        val gen = NotificationTemplateGenerator(
            context = mockk(relaxed = true),
            llmClient = llmClient,
            contextLog = contextLog,
            templateDao = mockk(relaxed = true),
            userProfileDao = userProfileDao,
        )

        gen.generateAndSave(character(StructuredMemory.EMPTY), relationship = null, config = config)

        val repairSystem = repairMessages.captured.first { it.role == "system" }.content.orEmpty()
        val keys = listOf(
            "streak_remind", "streak_urgent", "streak_broken", "morning", "evening", "random",
            "pet_hungry", "pet_sick", "pet_milestone",
        )
        for (key in keys) assertTrue("修复提示词缺键 $key", repairSystem.contains("\"$key\":[...]"))
        assertTrue("键数说明与 9 键一致", repairSystem.contains("包含 9 个键"))
        assertFalse(repairSystem.contains("包含 6 个键"))
    }

    /** #2 全文钉：除键数与骨架补齐外，修复规则四条与修前逐字一致（第 3 条里的 \" 是字面反斜杠+引号）。 */
    @Test fun buildRepairPrompt_fullText() {
        val expected = """
            你是 JSON 修复器。以下文本应该是一个合法的 JSON 对象，包含 9 个键，每个键对应一个字符串数组：
            {"streak_remind":[...],"streak_urgent":[...],"streak_broken":[...],"morning":[...],"evening":[...],"random":[...],"pet_hungry":[...],"pet_sick":[...],"pet_milestone":[...]}

            修复规则：
            1. 只输出修复后的合法 JSON，不要任何解释或 markdown 代码块
            2. 保留原始文案内容，只修复 JSON 格式问题
            3. 字符串内的双引号用 \" 转义
            4. 确保所有括号和引号正确匹配
        """.trimIndent()
        assertEquals(expected, NotificationTemplateGenerator.buildRepairPrompt())
    }
}
