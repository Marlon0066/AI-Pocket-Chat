package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.settings.hub.SettingsHubEntry
import com.situ.aichat.ui.settings.hub.SettingsHubSnapshot
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：设置主页内容层（设置全面重构卷一）：
 * 钉：五组自上而下顺序；全部 22 个非语言入口点一次恰回调对应 [SettingsHubEntry] 且不误触返回；
 * 大卡与行尾当前值回显；行级搜索按标题与关键词过滤并支持清除恢复；语言行开弹窗回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSettingsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableMapOf<SettingsHubEntry, Int>()
    private var pickedLang: String? = null
    private var backTaps = 0
    private val configured = mutableStateOf(false)

    private fun show() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliSettingsContent(
                        snapshot = SettingsHubSnapshot(
                            activeConfigLabel = if (configured.value) "深度求索 · deepseek-chat" else null,
                            appSkin = AppSkin.LIULI,
                            appearanceMode = AppearanceMode.SYSTEM,
                            ttsProviderName = "火山引擎",
                            notificationsEnabled = true,
                            shortTermMemoryLength = 30,
                            enabledWorldBookCount = 2,
                        ),
                        isBatteryExempt = true,
                        currentLangTag = "zh-CN",
                        versionName = "1.2.3",
                        onBack = { backTaps++ },
                        onOpenEntry = { e -> opened[e] = (opened[e] ?: 0) + 1 },
                        onSelectLanguage = { pickedLang = it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row(text: String) = compose.onNode(hasText(text) and hasClickAction())
    private fun header(text: String) = compose.onNode(hasText(text) and isHeading())
    private fun searchField() = compose.onNode(hasSetTextAction())

    private fun clickRow(text: String) {
        row(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun 五组按新骨架自上而下排列() {
        show()
        val headers = listOf("聊天", "记忆", "TA 们的生活", "TA 写的东西", "系统")
        headers.forEach { text -> header(text).performScrollTo().assertExists() }
        headers.zipWithNext().forEach { (a, b) ->
            header(b).performScrollTo()
            val topB = header(b).getUnclippedBoundsInRoot().top.value
            val topA = header(a).getUnclippedBoundsInRoot().top.value
            assert(topA < topB) { "「$a」应排在「$b」之上（实测 $topA ≥ $topB）" }
        }
    }

    @Test fun 全部二十二个导航入口各点一次且恰回调对应枚举() {
        show()
        val expected = listOf(
            "AI 服务" to SettingsHubEntry.AI_SERVICE,
            "外观" to SettingsHubEntry.APPEARANCE,
            "回复规则" to SettingsHubEntry.REPLY,
            "语音" to SettingsHubEntry.VOICE,
            "线下见面" to SettingsHubEntry.OFFLINE_MEETING,
            "表情包管理" to SettingsHubEntry.STICKERS,
            "记忆" to SettingsHubEntry.MEMORY,
            "设定集" to SettingsHubEntry.WORLDBOOKS,
            "日程与来往" to SettingsHubEntry.SCHEDULE,
            "成长与关系" to SettingsHubEntry.GROWTH,
            "宠物" to SettingsHubEntry.PET,
            "钱包与礼物" to SettingsHubEntry.WALLET,
            "世界设置" to SettingsHubEntry.WORLD,
            "日记设置" to SettingsHubEntry.DIARY,
            "朋友圈设置" to SettingsHubEntry.MOMENTS,
            "故事创作" to SettingsHubEntry.STORY,
            "通知设置" to SettingsHubEntry.NOTIFICATIONS,
            "日历感知" to SettingsHubEntry.CALENDAR,
            "后台运行保障" to SettingsHubEntry.BACKGROUND_RELIABILITY,
            "备份与恢复" to SettingsHubEntry.BACKUP,
            "高级" to SettingsHubEntry.ADVANCED,
            "关于" to SettingsHubEntry.ABOUT,
        )
        assertEquals(22, expected.size)
        expected.forEach { (title, _) -> clickRow(title) }
        expected.forEach { (title, entry) ->
            assertEquals("「$title」应恰回调一次", 1, opened[entry] ?: 0)
        }
        assertEquals("点行不该误触返回", 0, backTaps)
    }

    @Test fun 大卡与行尾当前值回显() {
        show()
        listOf(
            "AI 服务" to "未配置，点此添加",
            "外观" to "琉璃 · 跟随系统",
            "语音" to "火山引擎",
            "记忆" to "记住近 30 轮",
            "设定集" to "2 本启用",
            "后台运行保障" to "已设好",
            "关于" to "1.2.3",
            "语言" to "简体中文",
        ).forEach { (title, value) ->
            row(title).performScrollTo().assert(hasText(value))
        }
    }

    @Test fun 配好AI服务后大卡显示服务商与模型() {
        configured.value = true
        show()
        compose.onNodeWithText("未配置，点此添加").assertDoesNotExist()
        row("AI 服务").performScrollTo().assert(hasText("深度求索 · deepseek-chat"))
    }

    @Test fun 行级搜索命中关键词并支持清空恢复() {
        show()
        searchField().performTextInput("TTS")
        compose.waitForIdle()
        row("语音").assertExists()
        compose.onNodeWithText("匹配：TTS").assertExists()
        row("外观").assertDoesNotExist()

        searchField().performTextReplacement("zzz没有这一项")
        compose.waitForIdle()
        row("语音").assertDoesNotExist()
        compose.onNodeWithText("没有找到相关设置").assertExists()

        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.waitForIdle()
        header("聊天").performScrollTo().assertExists()
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
