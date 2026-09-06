package com.situ.aichat.ui.liuli.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiFunctionCategory
import com.situ.aichat.ui.settings.FunctionVisionHint
import com.situ.aichat.ui.settings.VoiceCallSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.hasText

/**
 * T2：语音通话设置页（屏 12）。E10：**拖动中不落盘、松手才落一次**——用读屏的 `SetProgress`
 * （`LiuliSlider` 里它等价「拖一下再松手」）驱动，断言落盘恰一次且值是松手时的本地态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliVoiceCallContentTest : LiuliScreenTestBase() {

    private val commits = mutableListOf<Float>()

    private fun show(stored: Float) = host {
        LiuliVoiceCallSettingsContent(
            storedSlider = stored,
            onCommitSlider = { commits += it },
            onBack = {},
        )
    }

    @Test fun 两端字与说明都在屏上() {
        show(VoiceCallSensitivity.SLIDER_MIN)
        assertEquals(1, countText("打断灵敏度"))
        assertEquals(1, countText("不易打断"))
        assertEquals(1, countText("容易打断"))
    }

    @Test fun 进屏零落盘() {
        show(0.20f)
        assertEquals(emptyList<Float>(), commits)
    }

    @Test fun 读屏设值等价拖一下再松手且恰落一次() {
        show(0.20f)
        val node = compose.onNode(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsActions.SetProgress,
            ),
        )
        node.performScrollTo()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.30f) }
        compose.waitForIdle()
        assertEquals("松手恰落一次", 1, commits.size)
        // 落的是吸附到 0.05 格点后的值（`LiuliSlider.snap`·steps = 6 在 0.05..0.40 上）。
        assertTrue("落盘值应在值域内：${commits.first()}", commits.first() in 0.05f..0.40f)
    }
}

/**
 * T2：API 功能分配页（屏 9）。断言从勘察表条件反推：三个类别各一组 · 每行右值在「未指派 = 跟随当前启用」
 * 与「已指派 = 服务商 + 模型」之间切 · 点菜单项恰写一次 · 视觉提示只在该给的那一档出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliApiFunctionAssignmentContentTest : LiuliScreenTestBase() {

    private val picks = mutableListOf<Pair<ApiFunction, String?>>()

    private fun config(uuid: String, provider: String, model: String) = ApiConfigEntity(
        uuid = uuid,
        providerName = provider,
        apiKeyId = "key-$uuid",
        baseURL = "https://api.example.com",
        modelName = model,
        isActive = false,
        creationDate = 0L,
    )

    private fun show(
        assignments: Map<ApiFunction, String?> = emptyMap(),
        chatHint: FunctionVisionHint = FunctionVisionHint.NO_CONFIG,
        imageHint: FunctionVisionHint = FunctionVisionHint.NO_CONFIG,
    ) = host {
        LiuliApiFunctionAssignmentContent(
            configs = listOf(config("u1", "DeepSeek", "deepseek-chat")),
            activeName = "DeepSeek deepseek-chat",
            assignments = assignments,
            chatVisionHint = chatHint,
            imageVisionHint = imageHint,
            onSelect = { fn, uuid -> picks += fn to uuid },
            onBack = {},
        )
    }

    @Test fun 每个功能各出一行() {
        show()
        // 三个类别下的每一个功能都要有自己那一行——漏一行 = 那个功能永远指派不了。
        ApiFunctionCategory.entries.flatMap { it.functions }.forEach { fn ->
            assertEquals("功能「${'$'}{fn.displayName}」应恰出现一行", 1, countText(fn.displayName))
        }
    }

    @Test fun 未指派时右值是跟随当前启用() {
        show(assignments = emptyMap())
        assertTrue(countText("默认（DeepSeek deepseek-chat）") >= 1)
    }

    @Test fun 已指派时右值换成服务商加模型() {
        show(assignments = mapOf(ApiFunction.CHAT to "u1"))
        assertTrue(countText("DeepSeek deepseek-chat") >= 1)
    }

    @Test fun 点菜单项恰写一次() {
        show(assignments = emptyMap())
        compose.onNodeWithText(ApiFunction.CHAT.displayName).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("DeepSeek deepseek-chat").assertIsDisplayed()
        compose.onNodeWithText("DeepSeek deepseek-chat").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ApiFunction.CHAT to "u1"), picks)
    }

    @Test fun 无配置时不出视觉脚注而有配置时出() {
        show(chatHint = FunctionVisionHint.NO_CONFIG, imageHint = FunctionVisionHint.NO_CONFIG)
        // NO_CONFIG 档两枚脚注都返回 null（逐字照暖陶 `chatVisionFootnote` 的第一行）——按子串找，别拿一句资源里
        // 不存在的话做恒零断言（复核 R1）。
        assertEquals(0, compose.onAllNodes(hasText("看不懂图", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun 聊天配置看不懂图时出脚注() {
        show(chatHint = FunctionVisionHint.NO_VISION, imageHint = FunctionVisionHint.NO_CONFIG)
        assertTrue(compose.onAllNodes(hasText("看不懂图", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}

/**
 * T2：新建配置表单（屏 7 的第一张卡）。E6：baseUrl 非 https 即红字 ·
 * 「保存并启用」的**四条件守卫**（key / baseUrl / model 非空 + https）逐条反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliApiConfigFormTest : LiuliScreenTestBase() {

    private var saves = 0

    private fun form(baseUrl: String, model: String, key: String) = host {
        LiuliApiConfigForm(
            provider = com.situ.aichat.data.model.ApiProviderType.DEEPSEEK,
            baseUrl = baseUrl,
            model = model,
            apiKey = key,
            catalogState = com.situ.aichat.ui.settings.ModelCatalogUiState.Idle,
            providerMenuOpen = false,
            onProviderMenuOpenChange = {},
            onProviderChange = {},
            onBaseUrlChange = {},
            onModelChange = {},
            onApiKeyChange = {},
            onFetchModels = {},
            onSave = { saves++ },
        )
    }

    @Test fun 四条件齐时保存可点且恰一次() {
        form("https://api.deepseek.com", "deepseek-chat", "sk-x")
        compose.onNodeWithText("保存并启用").performClick()
        compose.waitForIdle()
        assertEquals(1, saves)
    }

    @Test fun 明文http时红字在场且保存禁用() {
        form("http://api.deepseek.com", "deepseek-chat", "sk-x")
        assertEquals(1, countText("出于密钥安全，仅支持 https:// 地址（不接受 http://）"))
        compose.onNodeWithText("保存并启用").assertIsNotEnabled()
    }

    @Test fun 密钥为空时保存禁用() {
        form("https://api.deepseek.com", "deepseek-chat", "")
        compose.onNodeWithText("保存并启用").assertIsNotEnabled()
    }

    @Test fun 模型为空时保存禁用() {
        form("https://api.deepseek.com", "", "sk-x")
        compose.onNodeWithText("保存并启用").assertIsNotEnabled()
    }

    @Test fun baseUrl为空时不显红但保存仍禁用() {
        form("", "deepseek-chat", "sk-x")
        // 空 baseUrl 不是「错」（不显红），但四条件不齐 ⇒ 仍不可保存（逐字照暖陶的 urlInsecure 判据）。
        assertEquals(0, countText("出于密钥安全，仅支持 https:// 地址（不接受 http://）"))
        compose.onNodeWithText("保存并启用").assertIsNotEnabled()
    }
}

/**
 * T1：MiniMax 成本提示句（💰 只读）。两条分支逐字照暖陶 `CostHint`——有单价拼月费、没单价拼那句说明。
 */
internal class LiuliTtsCostTextTest {

    @Test fun 有单价时拼出预估月费() {
        val text = liuliTtsCostText(
            com.situ.aichat.tts.pricing.TtsCostEstimate(
                actualCharactersLast7Days = 12_000,
                projectedMonthlyCharacters = 51_000,
                projectedMonthlyUSD = 3.456,
                unitPriceUSDPerMillion = 68.0,
                daysWithData = 7,
                modelID = "speech-02-hd",
            ),
        )
        assertEquals("近 7 天 12000 字符 · 预估月费 ~\$3.46", text)
    }

    @Test fun 没单价时拼出未公开那句() {
        val text = liuliTtsCostText(
            com.situ.aichat.tts.pricing.TtsCostEstimate(
                actualCharactersLast7Days = 0,
                projectedMonthlyCharacters = 0,
                projectedMonthlyUSD = null,
                unitPriceUSDPerMillion = null,
                daysWithData = 0,
                modelID = "unknown-model",
            ),
        )
        assertEquals("近 7 天 0 字符 · 该模型未公开按量单价", text)
    }
}

/**
 * T2：API 编辑页内容层（屏 8）。E7：**保存守卫四条件**（config 非空 / baseUrl 非空 / model 非空 / https）·
 * 思考强度那一行只在「实际是思考模型 + 该服务商真有这档控制」时出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliApiConfigEditContentTest : LiuliScreenTestBase() {

    private val saves = mutableListOf<String?>()

    private fun config(
        baseUrl: String = "https://api.deepseek.com",
        model: String = "deepseek-chat",
        thinkingDetected: Int = 0,
    ) = ApiConfigEntity(
        uuid = "u1",
        providerName = "DeepSeek",
        apiKeyId = "key-u1",
        baseURL = baseUrl,
        modelName = model,
        isActive = true,
        creationDate = 0L,
        detectedThinkingModelType = thinkingDetected,
    )

    private fun show(cfg: ApiConfigEntity?) = host {
        LiuliApiConfigEditContent(
            uuid = "u1",
            config = cfg,
            storedKey = "sk-old",
            catalogState = com.situ.aichat.ui.settings.ModelCatalogUiState.Idle,
            isDetecting = false,
            feedback = kotlinx.coroutines.flow.emptyFlow(),
            onClearModels = {},
            onFetchModels = { _, _, _ -> },
            onRedetect = {},
            onSave = { _, _, _, key, _, _, _, _, _ -> saves += key },
            onBack = {},
        )
    }

    @Test fun 四条件齐时保存可点且未改密钥时传null() {
        show(config())
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        // 预填的 key 与 storedKey 相同 ⇒ resolveNewApiKey 归一成 null（不重写密钥库、不白跑检测）。
        assertEquals(listOf<String?>(null), saves)
    }

    @Test fun 明文http时保存禁用() {
        show(config(baseUrl = "http://api.deepseek.com"))
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String?>(), saves)
    }

    @Test fun 模型为空时保存禁用() {
        show(config(model = ""))
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }

    @Test fun 配置读不到时保存禁用() {
        show(null)
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }

    @Test fun 非思考模型时不出思考强度那一行() {
        show(config(thinkingDetected = 0))
        assertEquals(0, countText("思考强度"))
    }
}
