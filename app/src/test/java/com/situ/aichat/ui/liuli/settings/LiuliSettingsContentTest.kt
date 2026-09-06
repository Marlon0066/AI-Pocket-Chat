package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：设置主页内容层（图纸 2026-09-06 卷四 §8 C3 · §4.3 · F2 逐行）。
 *
 * 钉：十一组按 FABLE5_SETTINGS_REORG_PROPOSAL 的顺序自上而下；25 个导航出口各点各的、**恰一次**；
 * 三个开关各回一次；高级门（E6）关时门后四件不组合、开时全在；行尾值回显（E9 未配置走警示值）；
 * 搜索（E7）筛的是组不是行、全隐显无结果、清除恢复。
 *
 * 内容层是纯参数的（VM 在 `LiuliSettingsScreen` 一层订阅完再传值）——整屏会被 `hiltViewModel()` 掐死，
 * 这是本库既定打法（图纸 F11）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSettingsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableMapOf<String, Int>()
    private val toggles = mutableMapOf<String, Boolean>()
    private var pickedLang: String? = null
    private val advanced = mutableStateOf(false)
    private val configured = mutableStateOf(false)

    private fun tap(key: String): () -> Unit = { taps[key] = (taps[key] ?: 0) + 1 }

    private val callbacks = LiuliSettingsCallbacks(
        onOpenApiConfig = tap("apiConfig"),
        onOpenApiFunctions = tap("apiFunctions"),
        onOpenMemorySettings = tap("memorySettings"),
        onOpenSystemToggles = tap("systemToggles"),
        onOpenAppearance = tap("appearance"),
        onOpenNotificationSettings = tap("notificationSettings"),
        onOpenImmersiveSettings = tap("immersiveSettings"),
        onOpenStickerManagement = tap("stickerManagement"),
        onOpenGrowthSettings = tap("growthSettings"),
        onOpenReplyRules = tap("replyRules"),
        onOpenContentFilter = tap("contentFilter"),
        onOpenCalendarAwareness = tap("calendarAwareness"),
        onOpenWorldBooks = tap("worldBooks"),
        onOpenPromptModules = tap("promptModules"),
        onOpenTtsConfig = tap("ttsConfig"),
        onOpenVoiceCallSettings = tap("voiceCallSettings"),
        onOpenDiarySettings = tap("diarySettings"),
        onOpenMomentSettings = tap("momentSettings"),
        onOpenStoryGlobalSettings = tap("storyGlobalSettings"),
        onOpenWorldSettings = tap("worldSettings"),
        onOpenBackup = tap("backup"),
        onOpenBackgroundReliability = tap("backgroundReliability"),
        onOpenContextLog = tap("contextLog"),
        onOpenPerfCollect = tap("perfCollect"),
        onOpenAbout = tap("about"),
    )

    private val actions = LiuliSettingsActions(
        onSetEmotionAnimation = { toggles["emotion"] = it },
        onSetTextingTone = { toggles["tone"] = it },
        onSetAdvancedMode = { toggles["advanced"] = it },
        onSelectLanguage = { pickedLang = it },
    )

    private var backTaps = 0

    private fun show() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliSettingsContent(
                        state = LiuliSettingsState(
                            activeConfigLabel = if (configured.value) "深度求索 · deepseek-chat" else null,
                            advancedEnabled = advanced.value,
                            emotionAnimEnabled = false,
                            textingToneEnabled = false,
                            appearanceLabel = "琉璃 · 跟随系统",
                            ttsProviderName = "火山引擎",
                            notifEnabled = true,
                            worldTierLabel = "标准",
                            embedderState = TextEmbedder.LoadState.LOADED,
                            currentLangTag = "zh-CN",
                            version = "1.2.3",
                        ),
                        callbacks = callbacks,
                        actions = actions,
                        onBack = { backTaps++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** 行 = 「有点击动作且文字里有这一条」的那个节点——组标题与行标题会重名（如「关于」/「语音」）。 */
    private fun row(text: String) = compose.onNode(hasText(text) and hasClickAction())

    /** 组标题 = 挂了 heading() 的那个节点。 */
    private fun header(text: String) = compose.onNode(hasText(text) and isHeading())

    private fun clickRow(text: String) {
        row(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** 搜索槽是屏上唯一可输入的节点。 */
    private fun searchField() = compose.onNode(hasSetTextAction())

    @Test fun 十一组按重排口径自上而下() {
        show()
        val headers = listOf(
            "个性化", "API 与模型", "聊天行为", "记忆与设定", "语音",
            "AI 自动创作", "故事", "世界", "系统与通知", "数据与诊断", "关于",
        )
        headers.forEach { text -> header(text).performScrollTo().assertExists() }
        // 顺序：把相邻两组滚到同屏再比顶边（LazyColumn 里 performScrollTo 之后坐标才有意义）。
        headers.zipWithNext().forEach { (a, b) ->
            header(b).performScrollTo()
            val topB = header(b).getUnclippedBoundsInRoot().top.value
            val topA = header(a).getUnclippedBoundsInRoot().top.value
            assert(topA < topB) { "「$a」应排在「$b」之上（实测 $topA ≥ $topB）" }
        }
    }

    @Test fun 二十五个导航出口各点各的且恰一次() {
        advanced.value = true
        show()
        val rows = listOf(
            "外观" to "appearance",
            "表情包管理" to "stickerManagement",
            "API 配置" to "apiConfig",
            "功能 API 分配" to "apiFunctions",
            "回复规则" to "replyRules",
            "线下见面" to "immersiveSettings",
            "内容过滤" to "contentFilter",
            "记忆" to "memorySettings",
            "设定集" to "worldBooks",
            "成长与关系" to "growthSettings",
            "日历感知" to "calendarAwareness",
            "提示词模块" to "promptModules",
            "语音 / TTS" to "ttsConfig",
            "语音通话" to "voiceCallSettings",
            "日记设置" to "diarySettings",
            "朋友圈设置" to "momentSettings",
            "故事创作" to "storyGlobalSettings",
            "世界设置" to "worldSettings",
            "通知设置" to "notificationSettings",
            "后台运行保障" to "backgroundReliability",
            "功能开关" to "systemToggles",
            "备份与恢复" to "backup",
            "上下文日志" to "contextLog",
            "性能采集" to "perfCollect",
            "关于" to "about",
        )
        assertEquals("导航出口共 25 个（暖陶 SettingsScreen 同数）", 25, rows.size)
        rows.forEach { (title, key) -> clickRow(title) }
        rows.forEach { (title, key) ->
            assertEquals("「$title」应恰回调一次", 1, taps[key] ?: 0)
        }
        assertEquals("点行不该误触返回", 0, backTaps)
    }

    @Test fun 三个开关各回一次且带正确的新值() {
        show()
        clickRow("消息情绪动画")
        clickRow("自然短句口吻")
        clickRow("高级功能")
        assertEquals(true, toggles["emotion"])
        assertEquals(true, toggles["tone"])
        assertEquals(true, toggles["advanced"])
    }

    @Test fun 高级门关时门后四件都不在开时全在() {
        show()
        listOf("提示词模块", "上下文日志", "性能采集", "深层记忆").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.runOnIdle { advanced.value = true }
        compose.waitForIdle()
        listOf("提示词模块", "上下文日志", "性能采集").forEach {
            row(it).performScrollTo().assertExists()
        }
        // 深层记忆是只读状态行（无点击动作），单独找。
        compose.onNodeWithText("深层记忆").performScrollTo().assertExists()
    }

    @Test fun 行尾值回显() {
        show()
        listOf(
            "外观" to "琉璃 · 跟随系统",
            "API 配置" to "未配置，点此添加",
            "语音 / TTS" to "火山引擎",
            "通知设置" to "已开启",
            "世界设置" to "标准",
            "关于" to "1.2.3",
            "语言" to "简体中文",
        ).forEach { (title, value) ->
            row(title).performScrollTo().assert(hasText(value))
        }
    }

    @Test fun 配好API后不再显警示值而把服务商模型放副标() {
        configured.value = true
        show()
        compose.onNodeWithText("未配置，点此添加").assertDoesNotExist()
        row("API 配置").performScrollTo().assert(hasText("深度求索 · deepseek-chat"))
    }

    @Test fun 搜索筛的是组不是行且全隐显无结果() {
        show()
        searchField().performTextInput("语音")
        compose.waitForIdle()
        // 命中组整组照原样在（同组的「语音通话」也留着）；没命中的组连组标题都不组合。
        row("语音 / TTS").assertExists()
        row("语音通话").assertExists()
        header("个性化").assertDoesNotExist()
        row("外观").assertDoesNotExist()

        searchField().performTextReplacement("zzz没有这一项")
        compose.waitForIdle()
        row("语音 / TTS").assertDoesNotExist()
        compose.onNodeWithText("没有找到相关设置").assertExists()

        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.waitForIdle()
        header("个性化").performScrollTo().assertExists()
        row("语音 / TTS").performScrollTo().assertExists()
    }

    @Test fun 语言行开弹窗选中即回调() {
        show()
        clickRow("语言")
        compose.onNodeWithText("English").performClick()
        compose.waitForIdle()
        assertEquals("en", pickedLang)
        compose.onNodeWithText("English").assertDoesNotExist()
    }
}
