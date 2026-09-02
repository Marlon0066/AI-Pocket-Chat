package com.situ.aichat.prompt.persona

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷一《人设编译器》T1-1–T1-5（图纸 §7.2）：编译器「宽进严出」解析的逐字段校验。
 *
 * 断言从图纸 §3.4 的校验表与 §5 的 Y-E1–Y-E10 **独立反推**，不照抄实现：
 * - 三者全空 / JSON 坏 / 响应空 ⇒ 判失败，**返回对象不含任何数值**（Y-E2 / Y-E10）
 * - `anchors` 非法键丢弃计数、越界钳 [0,100]、**缺席维度不出现**（不强填·Y-E3 / Y-E4）
 * - `gains` 非法键丢弃、值钳 [0,2]、**值 1 不入库**（Y-E5）
 * - `operators` 词表外整条丢、同 condition 保留第一条、超 8 条截断（Y-E6 / Y-E7）
 * - `custom_gains` 与 27 项重名丢、超 10 截断、label 超 12 字截断、空 label 丢（Y-E8 / Y-E9）
 *
 * 编译器是纯逻辑：解析走 [PersonaCompileService.parseCompileResponse] 直测，
 * LLM 那一层只在「空响应重试」与「人设为空零调用」两例里用 MockK 假掉。
 */
class PersonaCompileParseTest {

    private val contextLog = mockk<ContextLogService>(relaxed = true)
    private val service = PersonaCompileService(contextLog)

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.invalid/v1",
        modelName = "m",
    )

    private fun input(persona: String = "高冷毒舌、嘴硬心软、怕黑") =
        PersonaCompileInput(name = "林晚", personalityDescription = persona)

    // MARK: - T1-1 失败判定

    @Test
    fun allThreeEmpty_isFailure_notFakeSuccess() {
        val json = """{"anchors":{},"gains":{},"custom_gains":[],"operators":[],"notes":"没读出什么"}"""
        val e = assertThrows(PersonaCompileError.InvalidResponse::class.java) {
            service.parseCompileResponse(json)
        }
        assertTrue("失败原因要说清是三者全空", e.detail.contains("全空"))
    }

    @Test
    fun onlyNotes_isAlsoFailure() {
        // 模型只回一句总结 = 什么都没生成，绝不能记成「编译成功」。
        assertThrows(PersonaCompileError.InvalidResponse::class.java) {
            service.parseCompileResponse("""{"notes":"她是个复杂的人"}""")
        }
    }

    @Test
    fun brokenJson_isFailure() {
        assertThrows(PersonaCompileError.InvalidResponse::class.java) {
            service.parseCompileResponse("这不是 JSON，是模型在跟你聊天")
        }
    }

    @Test
    fun emptyResponseAfterRetry_isFailure_andRetriedExactlyOnce() = runTest {
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ""

        assertThrows(PersonaCompileError.InvalidResponse::class.java) {
            kotlinx.coroutines.runBlocking { service.compile(input(), config) }
        }
        // 空响应等 200ms 重试 1 次 ⇒ 恰两次调用，不是一次、也不是无限重试。
        coVerify(exactly = 2) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun blankPersona_makesZeroLlmCalls() = runTest {
        assertThrows(PersonaCompileError.EmptyPersona::class.java) {
            kotlinx.coroutines.runBlocking { service.compile(input(persona = "   \n  "), config) }
        }
        coVerify(exactly = 0) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun llmCall_usesLockedTemperatureAndJsonFormat() = runTest {
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns """{"anchors":{"warmth":25}}"""

        kotlinx.coroutines.runBlocking { service.compile(input(), config) }

        coVerify(exactly = 1) {
            contextLog.completion(
                source = "人设编译",
                characterName = "林晚",
                config = config,
                messages = match<List<ChatMessageDto>> { it.size == 2 && it[0].role == "system" && it[1].role == "user" },
                temperature = 0.3,
                maxTokens = null,
                responseFormat = ResponseFormatDto(type = "json_object"),
                segments = any(),
                onFinishReason = any(),
            )
        }
    }

    // MARK: - 卷三 T2-6（K-14）：编译提示词的增益清单必须「key 中文标签」并列

    @Test
    fun compilePrompt_listsGainKeysWithChineseLabels() {
        val (systemPrompt, _) = service.buildCompilePrompt(input())
        assertTrue("g13 必须带标签，模型不能靠编号猜", systemPrompt.contains("g13 吵架 · 被凶"))
        assertTrue(systemPrompt.contains("g01 被关心问候"))
        assertTrue(systemPrompt.contains("g27 被抛弃的信号"))
        // 27 项一个不少（每个 key 都以「gNN 标签」形态出现一次）。
        for (key in PersonaVocab.GAIN_KEYS) {
            assertTrue("$key 未带标签出现", systemPrompt.contains(PersonaVocab.gainPromptLine(key)))
        }
    }

    // MARK: - T1-2 anchors

    @Test
    fun anchors_dropIllegalKeys_andCount() {
        val json = """{"anchors":{"warmth":25,"kindness":80,"evilness":10}}"""
        val r = service.parseCompileResponse(json)
        assertEquals(mapOf("warmth" to 25), r.anchors)
        assertEquals("两个非法键各计一次丢弃", 2, r.droppedCount)
    }

    @Test
    fun anchors_clampOutOfRangeValues() {
        val json = """{"anchors":{"warmth":-5,"humor":150,"extroversion":0,"openness":100}}"""
        val r = service.parseCompileResponse(json)
        assertEquals(0, r.anchors["warmth"])
        assertEquals(100, r.anchors["humor"])
        assertEquals(0, r.anchors["extroversion"])
        assertEquals(100, r.anchors["openness"])
        assertEquals("钳位不算丢弃", 0, r.droppedCount)
    }

    @Test
    fun anchors_absentDimensionsAreNotFilled() {
        val r = service.parseCompileResponse("""{"anchors":{"warmth":25,"humor":70}}""")
        assertEquals("只有 LLM 真给出的两维，其余不强填", setOf("warmth", "humor"), r.anchors.keys)
        assertNull(r.anchors["extroversion"])
    }

    @Test
    fun anchorBasis_isTrimmedTo24Chars_andKeepsOnlyLegalKeys() {
        val long = "一".repeat(40)
        val json = """{"anchors":{"warmth":25},"anchor_basis":{"warmth":"$long","kindness":"无关维度","humor":"  "}}"""
        val r = service.parseCompileResponse(json)
        assertEquals(24, r.basis.getValue("warmth").length)
        assertEquals("非法键与空白值都不进 basis", setOf("warmth"), r.basis.keys)
    }

    // MARK: - T1-3 gains

    @Test
    fun gains_dropIllegalKeys_clampValues_andSkipNormal() {
        val json = """{"gains":{"g02":2,"g04":0,"g07":1,"g99":2,"gXX":0,"g25":7,"g26":-3}}"""
        val r = service.parseCompileResponse(json)
        assertEquals(
            "值 1（正常）不入库；越界钳到 [0,2]",
            mapOf("g02" to 2, "g04" to 0, "g25" to 2, "g26" to 0),
            r.gains.system,
        )
        assertEquals("两个词表外的键各计一次", 2, r.droppedCount)
    }

    @Test
    fun gains_allNormal_countsAsNothingGenerated() {
        // 全是「正常」= 一项都不入库 ⇒ 三者全空 ⇒ 判失败（不是「成功但空」）。
        assertThrows(PersonaCompileError.InvalidResponse::class.java) {
            service.parseCompileResponse("""{"gains":{"g01":1,"g02":1}}""")
        }
    }

    // MARK: - T1-4 operators

    @Test
    fun operators_dropWholeEntryOnUnknownVocab() {
        val json = """{"operators":[
            {"condition":"c01","action":"a01"},
            {"condition":"c99","action":"a01"},
            {"condition":"c02","action":"a99"},
            {"condition":"","action":""}
        ]}"""
        val r = service.parseCompileResponse(json)
        assertEquals(1, r.operators.size)
        assertEquals("c01", r.operators.single().condition)
        assertEquals("三条越界整条丢弃并计数", 3, r.droppedCount)
    }

    @Test
    fun operators_duplicateCondition_keepsFirst() {
        val json = """{"operators":[
            {"condition":"c01","action":"a01"},
            {"condition":"c01","action":"a07"}
        ]}"""
        val r = service.parseCompileResponse(json)
        assertEquals(1, r.operators.size)
        assertEquals("保留第一条（a01），不是后来的 a07", "a01", r.operators.single().action)
        assertEquals(1, r.droppedCount)
    }

    @Test
    fun operators_truncateAtEight() {
        // 12 条各不重复条件（词表恰 12 条）⇒ 留前 8、丢 4。
        val entries = (1..12).joinToString(",") { """{"condition":"c%02d","action":"a01"}""".format(it) }
        val r = service.parseCompileResponse("""{"operators":[$entries]}""")
        assertEquals(PersonaVocab.MAX_OPERATORS, r.operators.size)
        assertEquals(listOf("c01", "c02", "c03", "c04", "c05", "c06", "c07", "c08"), r.operators.map { it.condition })
        assertEquals(4, r.droppedCount)
    }

    @Test
    fun operators_defaultToEnabled_withGeneratedIds() {
        val r = service.parseCompileResponse("""{"operators":[{"condition":"c03","action":"a05"}]}""")
        val op = r.operators.single()
        assertTrue("新算子默认开着", op.enabled)
        assertTrue("id 不能是空串（否则开关/删除认不出行）", op.id.isNotEmpty())
    }

    // MARK: - T1-5 custom_gains

    @Test
    fun customGains_dropDuplicatesOfSystemLabels() {
        val json = """{"custom_gains":[{"label":"被夸奖肯定","level":2},{"label":"被叫全名","level":2}]}"""
        val r = service.parseCompileResponse(json, systemGainLabels = setOf("被夸奖肯定", "被冷落 · 已读不回"))
        assertEquals(listOf("被叫全名"), r.gains.custom.map { it.label })
        assertEquals(1, r.droppedCount)
    }

    @Test
    fun customGains_dropDuplicatesWithinItself() {
        val json = """{"custom_gains":[{"label":"被叫全名"},{"label":"被叫全名"},{"label":"  被叫全名  "}]}"""
        val r = service.parseCompileResponse(json)
        assertEquals(1, r.gains.custom.size)
        assertEquals("去空白后同名的两条被丢", 2, r.droppedCount)
    }

    @Test
    fun customGains_truncateLabelTo12Chars_andDropBlank() {
        val json = """{"custom_gains":[{"label":"${"长".repeat(30)}"},{"label":"   "},{"label":""}]}"""
        val r = service.parseCompileResponse(json)
        assertEquals(1, r.gains.custom.size)
        assertEquals(CustomGain.MAX_LABEL_LENGTH, r.gains.custom.single().label.length)
        assertEquals("两条空 label 丢弃", 2, r.droppedCount)
    }

    @Test
    fun customGains_truncateAtTen() {
        val entries = (1..14).joinToString(",") { """{"label":"专属$it","level":2}""" }
        val r = service.parseCompileResponse("""{"custom_gains":[$entries]}""")
        assertEquals(10, r.gains.custom.size)
        assertEquals(listOf("专属1", "专属10"), listOf(r.gains.custom.first().label, r.gains.custom.last().label))
        assertEquals(4, r.droppedCount)
    }

    @Test
    fun customGains_originIsCompiled_andLevelClamped() {
        val json = """{"custom_gains":[{"label":"被叫全名","level":9},{"label":"被打断","level":-2}]}"""
        val r = service.parseCompileResponse(json)
        assertEquals(listOf(CustomGain.ORIGIN_COMPILED, CustomGain.ORIGIN_COMPILED), r.gains.custom.map { it.origin })
        assertEquals(listOf(PersonaVocab.LEVEL_SENSITIVE, PersonaVocab.LEVEL_NUMB), r.gains.custom.map { it.level })
        assertTrue(r.gains.custom.all { it.id.isNotEmpty() })
    }

    // MARK: - 其它

    @Test
    fun notes_areTrimmedTo60Chars_andNeverPersisted() {
        val json = """{"anchors":{"warmth":25},"custom_gains":[{"label":"怕黑","level":2}],"notes":"${"总".repeat(90)}"}"""
        val r = service.parseCompileResponse(json)
        assertEquals(60, r.notes.length)
        // notes 只存在于返回对象里（供 Logcat），四个落库字段一个都不含它——由协调器落库路径保证，
        // 这里钉「结果对象把它与数值分开放」这一半（修缮卷 B🔵-10：custom 里只有真给的那条，notes 没漏进去）。
        assertEquals(listOf("怕黑"), r.gains.custom.map { it.label })
    }

    // MARK: - 修缮卷 T1-12（E26 / D-12）：数值字段宽松取整，坏一项丢一项、不判废

    @Test
    fun lenientNumbers_dropUnparseable_countThem_neverFail_E26() {
        val json = """{"anchors":{"warmth":null,"humor":72.4},"gains":{"g02":"2"},"custom_gains":[{"label":"怕黑","level":"很敏感"}]}"""
        val r = service.parseCompileResponse(json)
        assertEquals(mapOf("humor" to 72), r.anchors)
        assertEquals("warmth 取不出整数 ⇒ 丢并计 1", 1, r.droppedCount)
        assertEquals(mapOf("g02" to 2), r.gains.system)
        assertEquals(listOf("怕黑"), r.gains.custom.map { it.label })
        assertEquals("level 认不出 ⇒ 回默认「很敏感」", 2, r.gains.custom.single().level)
    }

    @Test
    fun lenientNumbers_decimalsRoundAndClamp_levelNullDefaults() {
        val json = """{"anchors":{"warmth":"130","humor":-3.2},"gains":{"g05":0.4,"g06":2.6,"g07":1.2},"custom_gains":[{"label":"被叫全名","level":null},{"label":"迟到","level":"0"}]}"""
        val r = service.parseCompileResponse(json)
        assertEquals(mapOf("warmth" to 100, "humor" to 0), r.anchors)
        assertEquals("0.4 → 0、2.6 → 2、1.2 → 1（正常档不入库）", mapOf("g05" to 0, "g06" to 2), r.gains.system)
        assertEquals(listOf(2, 0), r.gains.custom.map { it.level })
        assertEquals(0, r.droppedCount)
    }

    @Test
    fun fencedJson_isStillParsed() {
        val fenced = "```json\n{\"anchors\":{\"warmth\":25}}\n```"
        assertEquals(mapOf("warmth" to 25), service.parseCompileResponse(fenced).anchors)
    }

    @Test
    fun prompt_carriesLockedVocabKeys_andPersonaOnly() {
        val (system, user) = service.buildCompilePrompt(
            PersonaCompileInput(
                name = "林晚",
                personalityDescription = "高冷毒舌",
                occupation = "调酒师",
                backstory = "从小在海边长大",
            ),
        )
        // 封闭词表必须原样出现在提示词里，否则模型只能瞎猜 key。
        assertTrue(system.contains("g01") && system.contains("g27"))
        assertTrue(system.contains("c01") && system.contains("c12"))
        assertTrue(system.contains("a01") && system.contains("a10"))
        assertTrue("算子上限要写进提示词", system.contains("最多 8 条"))
        // 输入只有人设面，绝不含对话记录。
        assertTrue(user.contains("高冷毒舌") && user.contains("调酒师") && user.contains("从小在海边长大"))
        assertTrue("空字段不生成空标题行", !user.contains("说话风格"))
    }

    @Test
    fun personaHash_is16LowerHexChars_andOnlyTracksPersonaText() {
        val a = service.personaHash("高冷毒舌、嘴硬心软")
        val b = service.personaHash("高冷毒舌、嘴硬心软")
        val c = service.personaHash("高冷毒舌、嘴硬心软。")
        assertEquals(16, a.length)
        assertTrue(a.all { it.isDigit() || it in 'a'..'f' })
        assertEquals("同一段人设 hash 稳定", a, b)
        assertTrue("改一个标点就应该变（D-2 提醒条据此触发）", a != c)
    }
}
