package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.effectiveVisionEnabled
import com.situ.aichat.data.local.entity.prefilledFromKnownCapabilities
import com.situ.aichat.data.local.entity.resolvedConfigHasVision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉能力打标签的**看门测试**。
 *
 * 为什么值得这么严：2026-08-29 起聊天「+」面板的「照片」入口按这条链显隐——标错的后果不再是
 * 一个不准的徽章，而是「有视觉的模型却没有发图按钮」或「纯文本模型给了发图按钮」。
 *
 * 链路四层，优先级从高到低：
 * ① 服务商官方元数据（OpenRouter `input_modalities` / Anthropic `capabilities.image_input`）
 * ② 名字表 [KnownModelCapabilityTable]（最长前缀匹配）
 * ③ 运行时探针（真发一张 1×1 图）
 * ④ 用户手动 [VisionMode.ENABLED]
 *
 * 本组锁 ②③④ 的判定语义与②的**已知陷阱**；①的解析在 provider 单测侧。
 */
class VisionCapabilityTaggingTest {

    private fun config(
        model: String,
        visionMode: VisionMode = VisionMode.AUTO,
        detected: Int = -1,
    ) = ApiConfigEntity(
        uuid = "u",
        providerName = "p",
        providerTypeRaw = ApiProviderType.OPENAI_COMPATIBLE.raw,
        apiKeyId = "k",
        baseURL = "https://h.com/v1",
        modelName = model,
        isActive = true,
        creationDate = 0L,
        visionModeRaw = visionMode.raw,
        detectedVisionSupport = detected,
    )

    // ---------- ② 名字表：主流视觉模型必须命中 ----------

    @Test
    fun `主流视觉模型名字表命中`() {
        val visionModels = listOf(
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-5",
            "claude-sonnet-4", "claude-opus-4", "claude-3-5-sonnet",
            "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5", "claude-fable-5",
            "gemini-2.5-pro", "gemini-2.0-flash", "gemini-3",
            "o3", "o4-mini", "grok-4", "llama-4", "pixtral-12b",
            "qwen2.5-vl-72b", "qwen3-vl-30b", "minimax-m3",
            "deepseek-v4-flash-vision-exp",
        )
        visionModels.forEach { name ->
            val known = KnownModelCapabilityTable.lookup(name)
            assertNotNull("『$name』应在名字表里", known)
            assertTrue("『$name』应标为有视觉", known!!.hasVision)
        }
    }

    @Test
    fun `纯文本模型不得被标成有视觉`() {
        val textModels = listOf(
            "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner", "deepseek-r1",
            "minimax-m2.7", "minimax-m2", "qwen3-32b", "qwen2.5-72b",
            "o1-mini", "o3-mini", "gpt-4", "llama-3.3", "mistral-large", "command-r", "grok-2",
        )
        textModels.forEach { name ->
            val known = KnownModelCapabilityTable.lookup(name)
            assertNotNull("『$name』应在名字表里", known)
            assertFalse("『$name』不该被标成有视觉", known!!.hasVision)
        }
    }

    // ---------- ② 已知陷阱：更长的前缀必须先赢，否则表会「主动说错」 ----------

    @Test
    fun `视觉变体不被基础款前缀吞掉`() {
        // 这三对是真实踩过的坑：左边若被右边吞掉，表会**断言「不支持视觉」**——
        // 比查不到更糟（查不到还会退给探针裁决，主动说错则直接关掉发图按钮）。
        val traps = listOf(
            "deepseek-v4-flash-vision-exp" to "deepseek-v4-flash",
            "qwen3-vl-30b" to "qwen3-32b",
            "gpt-4.1-mini" to "gpt-4-0613",
            "grok-2-vision-1212" to "grok-2-1212",
            "llama-3.2-vision-11b" to "llama-3.2-3b",
        )
        traps.forEach { (visionName, textName) ->
            assertTrue("$visionName 应有视觉", KnownModelCapabilityTable.lookup(visionName)!!.hasVision)
            assertFalse("$textName 不该有视觉", KnownModelCapabilityTable.lookup(textName)!!.hasVision)
        }
    }

    @Test
    fun `中转厂商前缀被剥掉后仍能命中`() {
        // 中转站/OpenRouter 的 id 常带厂商前缀，剥不掉就会整片查不到 → 发图按钮集体消失
        listOf("anthropic/claude-sonnet-4", "openai/gpt-4o", "google/gemini-2.5-pro", "qwen/qwen2.5-vl-7b")
            .forEach { assertTrue("『$it』应命中并有视觉", KnownModelCapabilityTable.lookup(it)!!.hasVision) }
    }

    @Test
    fun `查不到的模型返回 null 而不是瞎猜`() {
        // null 才能让探针去裁决；瞎猜成 false 会关掉一个本该有的按钮
        assertNull(KnownModelCapabilityTable.lookup("some-private-finetune-v9"))
        assertNull(KnownModelCapabilityTable.lookup(""))
    }

    // ---------- 预填：名字表 → detectedVisionSupport ----------

    @Test
    fun `名字表有视觉时预填为支持`() {
        assertEquals(1, config("gpt-4o").prefilledFromKnownCapabilities().detectedVisionSupport)
    }

    @Test
    fun `名字表无视觉时预填为不支持`() {
        assertEquals(0, config("deepseek-v4-pro").prefilledFromKnownCapabilities().detectedVisionSupport)
    }

    @Test
    fun `查不到的模型不预填 留给探针`() {
        assertEquals(-1, config("some-private-finetune-v9").prefilledFromKnownCapabilities().detectedVisionSupport)
    }

    @Test
    fun `手动档不被预填覆盖`() {
        val manual = config("deepseek-v4-pro", visionMode = VisionMode.ENABLED)
        assertEquals(-1, manual.prefilledFromKnownCapabilities().detectedVisionSupport)
        assertTrue("手动开启应恒为真，与探测无关", manual.effectiveVisionEnabled())
    }

    // ---------- ④ 三档语义 ----------

    @Test
    fun `自动档只有确定支持才算有视觉`() {
        assertTrue(config("m", detected = 1).effectiveVisionEnabled())
        assertFalse(config("m", detected = 0).effectiveVisionEnabled())
        // 「不确定」按没有处理：宁可少给按钮，也不让人发了图才发现对方看不见
        assertFalse(config("m", detected = -1).effectiveVisionEnabled())
    }

    @Test
    fun `手动关闭压过探测结果`() {
        assertFalse(config("gpt-4o", visionMode = VisionMode.DISABLED, detected = 1).effectiveVisionEnabled())
    }

    // ---------- 发图入口谓词（聊天屏与设置屏共用单源） ----------

    @Test
    fun `未分配时用默认配置判定`() {
        val active = config("gpt-4o", detected = 1)
        assertTrue(resolvedConfigHasVision(null, listOf(active), active))
    }

    @Test
    fun `显式分配时用被分配的那个`() {
        val active = config("gpt-4o", detected = 1).copy(uuid = "active")
        val assigned = config("deepseek-v4-pro", detected = 0).copy(uuid = "assigned")
        assertFalse(
            "分配了纯文本配置就该关掉发图入口，哪怕默认配置有视觉",
            resolvedConfigHasVision("assigned", listOf(active, assigned), active),
        )
    }

    @Test
    fun `分配失效时回落默认配置`() {
        val active = config("gpt-4o", detected = 1).copy(uuid = "active")
        // 被分配的配置已被删除 → 回落 active（与 resolveConfigValues 的回退语义一致）
        assertTrue(resolvedConfigHasVision("deleted-uuid", listOf(active), active))
    }

    @Test
    fun `一个配置都没有时不给发图入口`() {
        assertFalse(resolvedConfigHasVision(null, emptyList(), null))
    }

    @Test
    fun `手动常开压过检测不支持 发图入口照样给`() {
        // 逃生口：检测判「不支持」（名字表说没有 / 探针失败），但用户确知模型能看图 →
        // 在该配置把「图片理解」设成常开，发图按钮必须出现。识别再不准也不能锁死用户。
        val manual = config("some-relay-vision-model", visionMode = VisionMode.ENABLED, detected = 0)
        assertTrue(manual.effectiveVisionEnabled())
        assertTrue(resolvedConfigHasVision(null, listOf(manual), manual))
    }

    @Test
    fun `手动常开在探测不确定时同样给入口`() {
        val manual = config("brand-new-model", visionMode = VisionMode.ENABLED, detected = -1)
        assertTrue(resolvedConfigHasVision(null, listOf(manual), manual))
    }

    // ---------- ② 2026-08-31 补录：Qwen3.5 / Omni 与国产三家视觉款 ----------

    @Test
    fun `补录的多模态款必须命中且有视觉`() {
        // 补录前：`qwen3`(hasVision=false) 吞掉整个 Qwen3.5 世代与 Omni 系 =「主动说错」；
        // GLM / Kimi / 豆包三家视觉款则整族缺席（查不到 → 只能等探针，探针一失败按钮就没了）。
        val visionModels = listOf(
            "qwen3-omni-flash", "qwen3.5-397b", "qwen3.5-omni-flash",
            "glm-4.5v", "glm-4.6v-flash", "glm-4v-plus",
            "kimi-k2.5", "kimi-k3",
            "doubao-seed-1-6-250615", "doubao-1-5-vision-pro-32k-250115",
            "moonshotai/kimi-k2.6",
        )
        visionModels.forEach { name ->
            val known = KnownModelCapabilityTable.lookup(name)
            assertNotNull("『$name』应在名字表里", known)
            assertTrue("『$name』应标为有视觉", known!!.hasVision)
        }
    }

    @Test
    fun `Qwen 新世代不被 qwen3 前缀吞掉`() {
        // 左边若被右边吞掉，表会**断言「不支持视觉」**；Omni 系强制流式、探针必 400 救不回来，
        // 表说错就等于永久没有发图按钮。
        val traps = listOf(
            "qwen3-omni-flash" to "qwen3-32b",
            "qwen3.5-omni" to "qwen3-32b",
        )
        traps.forEach { (visionName, textName) ->
            assertTrue("$visionName 应有视觉", KnownModelCapabilityTable.lookup(visionName)!!.hasVision)
            assertFalse("$textName 不该有视觉", KnownModelCapabilityTable.lookup(textName)!!.hasVision)
        }
        // 豆包思考视觉款比基础视觉款前缀更长，必须命中自己那条
        assertTrue(KnownModelCapabilityTable.lookup("doubao-1-5-thinking-vision-pro")!!.hasVision)
    }

    @Test
    fun `gpt-5 不再被标成能听音频`() {
        // OpenAI 的音频输入是 gpt-4o-audio / gpt-audio 族；gpt-5 的 chat completions 不收 input_audio。
        // 此前表值 true 被音频探针的 400→0 歪打正着压住，探针修了参数方言后必须靠表值本身正确。
        assertFalse(KnownModelCapabilityTable.lookup("gpt-5")!!.hasAudioInput)
        assertTrue("音频款照旧为真", KnownModelCapabilityTable.lookup("gpt-4o-audio-preview")!!.hasAudioInput)
    }

    // ---------- ② 2026-08-31 补录：StepFun 阶跃星辰视觉款（图纸 C·C4）----------

    @Test
    fun `阶跃视觉款必须命中且有视觉`() {
        // 补录前阶跃全族缺席（查不到 → 只能等探针；探针一失败发图按钮就没了）。
        // `step-3.7-flash` 靠 `step-3` 前缀吞并——阶跃旗舰线原生多模态，后继大概率延续（J7）。
        val visionModels = listOf(
            "step-1v-8k", "step-1v-32k",
            "step-1o-turbo-vision", "step-1o-vision-32k",
            "step-3", "step-3.7-flash",
        )
        visionModels.forEach { name ->
            val known = KnownModelCapabilityTable.lookup(name)
            assertNotNull("『$name』应在名字表里", known)
            assertTrue("『$name』应标为有视觉", known!!.hasVision)
        }
    }

    @Test
    fun `阶跃旗舰是思考模型 1v 系不是`() {
        // 防「四条抄同一行」：step-3 = 321B MoE 多模态**推理**旗舰，step-1v / 1o 视觉款不是思考模型。
        assertTrue(KnownModelCapabilityTable.lookup("step-3")!!.isThinking)
        assertFalse(KnownModelCapabilityTable.lookup("step-1v-8k")!!.isThinking)
        assertFalse(KnownModelCapabilityTable.lookup("step-1o-turbo-vision")!!.isThinking)
    }

    // ---------- 探针写回护栏（AUTO 档「表的正断言不被探针 0 推翻」） ----------

    @Test
    fun `探针 0 推不翻名字表的正断言`() {
        // 一次 400 成因太多（参数方言 / 强制流式 / 中转不认参数），不足以推翻人工核过的正条目
        assertEquals(1, guardedProbeWriteback(0, true))
    }

    @Test
    fun `名字表说没有或查不到时护栏不干预`() {
        assertEquals(0, guardedProbeWriteback(0, false))
        assertEquals("查不到就该让探针裁决", 0, guardedProbeWriteback(0, null))
    }

    @Test
    fun `探针 1 是强证据 照旧压过表的否定`() {
        // 真收下图并回了话 = 实证；未收录的中转视觉款靠这条救回来
        assertEquals(1, guardedProbeWriteback(1, false))
        assertEquals(1, guardedProbeWriteback(1, null))
    }

    @Test
    fun `未判定原样透传`() {
        // -1 的「不覆盖已有值」语义在调用方，护栏不许把它变成别的数
        assertEquals(-1, guardedProbeWriteback(-1, true))
        assertEquals(-1, guardedProbeWriteback(-1, false))
        assertEquals(-1, guardedProbeWriteback(-1, null))
    }
}
