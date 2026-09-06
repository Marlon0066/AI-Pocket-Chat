package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C1 六屏共用的宿主（图纸 2026-09-06 卷五 §8 C1 · F10 测试基建）。
 *
 * 每屏都测**内容层**（`Liuli<X>Content(state, callbacks)`）而不是整屏：整屏的 `hiltViewModel()` 默认形参
 * 会把 Robolectric 里的合成整棵掐死（记忆 `reference-robolectric-hiltviewmodel-blocks-fullscreen`）。
 */
internal abstract class LiuliScreenTestBase {

    @get:Rule
    val compose = createComposeRule()

    protected fun host(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    content()
                }
            }
        }
        compose.waitForIdle()
    }

    protected fun countText(text: String): Int =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    protected fun countDescription(text: String): Int =
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().size
}

/**
 * T2：记忆 hub（屏 1）。断言从勘察表条件反推：
 * 渐进压缩开关**只在自定义提取 prompt 为空时**可用（否则置灰 + 换副标）· 触发滑杆上限跟着短期窗口走 ·
 * 越界时那一行走琥珀（2026-09-05 已 SHIP 的 D-3 三件，一件都不许在换脸时掉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliMemoryHubContentTest : LiuliScreenTestBase() {

    private val setLength = mutableListOf<Int>()
    private val progressive = mutableListOf<Boolean>()
    private val resets = mutableListOf<String>()

    private fun show(settings: AppSettings) = host {
        LiuliMemoryHubContent(
            settings = settings,
            extraction = "提取模板",
            injection = "注入模板",
            memoryCallbacks = LiuliMemoryCallbacks(
                onSetShortTermLength = { setLength += it },
                onSetAutoSummarizeInterval = {},
                onSetCooldownMinutes = {},
                onSetSummaryMaxLength = {},
                onSetProgressive = { progressive += it },
                onSetStructuredInterval = {},
                onSetVectorThreshold = {},
            ),
            promptCallbacks = LiuliMemoryPromptCallbacks(
                onExtractionChange = {},
                onInjectionChange = {},
                onResetExtraction = { resets += "extraction" },
                onResetInjection = { resets += "injection" },
            ),
            onBack = {},
        )
    }

    @org.junit.Test fun 自定义提取prompt为空时渐进压缩可点() {
        show(AppSettings(memoryExtractionPrompt = "", progressiveCompressionEnabled = false))
        compose.onNodeWithText("智能渐进压缩").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(true), progressive)
    }

    @org.junit.Test fun 有自定义提取prompt时渐进压缩置灰且点不动() {
        show(AppSettings(memoryExtractionPrompt = "我自己的提取模板", progressiveCompressionEnabled = true))
        compose.onNodeWithText("智能渐进压缩").assertIsNotEnabled()
        compose.onNodeWithText("智能渐进压缩").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(emptyList<Boolean>(), progressive)
        // 正向证据：副标换成了「让位」那一句（否则「点不动」可能只是没点到）。
        org.junit.Assert.assertEquals(1, countText("你自定义了提取提示词，压缩方式改由你的模板决定，此开关暂不适用；恢复默认提示词后即可正常使用。"))
    }

    @org.junit.Test fun 九个宏字面量一个不少() {
        show(AppSettings())
        listOf(
            "{{聊天记录}}", "{{已有记忆}}", "{{当前时间}}", "{{最大字数}}", "{{当前字数}}",
            "{{压缩策略}}", "{{记忆内容}}", "{{char}}", "{{user}}",
        ).forEach { macro ->
            org.junit.Assert.assertEquals("$macro 应在屏上恰出现一次", 1, countText(macro))
        }
    }

    @org.junit.Test fun 两枚恢复默认各自回调() {
        show(AppSettings())
        val buttons = compose.onAllNodesWithText("恢复默认")
        org.junit.Assert.assertEquals(2, buttons.fetchSemanticsNodes().size)
        // 两枚钮都在首屏之外——不先滚过去，performClick 会静默不命中（PITFALLS §1e 假绿）。
        buttons[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("恢复默认")[1].performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("extraction", "injection"), resets)
    }
}

/**
 * T2：成长设置页（屏 2）。E1：`growthSystemEnabled` 关 → **整个参数区不在场**，只出一句引导；
 * 开 → 三枚滑杆都在。开发者组另受 `debugBuild` 门控。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliGrowthContentTest : LiuliScreenTestBase() {

    private var observatoryTaps = 0

    private fun show(enabled: Boolean, debug: Boolean) = host {
        LiuliGrowthSettingsContent(
            settings = AppSettings(growthSystemEnabled = enabled),
            debugBuild = debug,
            onSetAnalysisInterval = {},
            onSetMoodHistoryMax = {},
            onSetLogMax = {},
            onSetInterestCooldown = {},
            onOpenObservatory = { observatoryTaps++ },
            onBack = {},
        )
    }

    @org.junit.Test fun 成长关时整个参数区不在场() {
        show(enabled = false, debug = false)
        org.junit.Assert.assertEquals(0, countText("性格分析频率"))
        org.junit.Assert.assertEquals(0, countText("情绪记录数量"))
        org.junit.Assert.assertEquals(0, countText("成长记录数量"))
        // 正向证据：引导句在（否则「都不在」也可能是整屏没渲染出来）。
        org.junit.Assert.assertEquals(1, countText("开启「角色成长」后可调整成长分析参数（在「系统功能」里开启）。"))
    }

    @org.junit.Test fun 成长开时三枚滑杆都在() {
        show(enabled = true, debug = false)
        org.junit.Assert.assertEquals(1, countText("性格分析频率"))
        org.junit.Assert.assertEquals(1, countText("情绪记录数量"))
        org.junit.Assert.assertEquals(1, countText("成长记录数量"))
        org.junit.Assert.assertEquals(0, countText("开发者"))
    }

    @org.junit.Test fun debug构建才出开发者组且点得动() {
        show(enabled = true, debug = true)
        org.junit.Assert.assertEquals(1, countText("开发者"))
        compose.onNodeWithText("内核观测台（仅 debug）").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(1, observatoryTaps)
    }
}

/**
 * T2：线下见面设置页（屏 3）。E2：`custom` 档才出三个编辑器；背景三分支各出各的（粒子三选一 /
 * 纯色输入格 / 自定义图片说明），一次只出一支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliImmersiveContentTest : LiuliScreenTestBase() {

    private val narrativePicks = mutableListOf<String>()

    private fun show(narrativeRaw: String, backgroundRaw: String) = host {
        LiuliImmersiveSettingsContent(
            settings = AppSettings(
                offlineNarrativeDetailRaw = narrativeRaw,
                offlineBackgroundStyleRaw = backgroundRaw,
            ),
            callbacks = LiuliImmersiveCallbacks(
                onSetCharacterCanInitiate = {},
                onSetImmersiveInput = {},
                onSetNarrativeDetail = { narrativePicks += it },
                onSetCustomStyle = {},
                onSetCustomDirective = {},
                onSetCustomEmotion = {},
                onSetMeetingMemoryInjectCount = {},
                onSetMeetingMemoryMaxLength = {},
                onSetAfterglowEnabled = {},
                onSetBackgroundStyle = {},
                onSetParticleStyle = {},
                onSetBackgroundColor = {},
            ),
            onBack = {},
        )
    }

    @org.junit.Test fun 非custom档时三个编辑器都不在场() {
        show(narrativeRaw = "normal", backgroundRaw = "particle")
        org.junit.Assert.assertEquals(0, countText("写作风格指导"))
        org.junit.Assert.assertEquals(0, countText("每轮叙事指令"))
        org.junit.Assert.assertEquals(0, countText("情绪底色"))
        // 脚注跟着档位走（借的是暖陶 narrativeDetailFooter·换脸后仍是同一句）。
        org.junit.Assert.assertEquals(1, countText("像真人约会的自然对话风格，偶尔有环境描写和心理活动。"))
    }

    @org.junit.Test fun custom档时三个编辑器都出() {
        show(narrativeRaw = "custom", backgroundRaw = "particle")
        org.junit.Assert.assertEquals(1, countText("写作风格指导"))
        org.junit.Assert.assertEquals(1, countText("每轮叙事指令"))
        org.junit.Assert.assertEquals(1, countText("情绪底色"))
    }

    @org.junit.Test fun 点叙事档位回填raw值() {
        show(narrativeRaw = "plain", backgroundRaw = "particle")
        compose.onNodeWithText("细腻").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("detailed"), narrativePicks)
    }

    @org.junit.Test fun 背景粒子档只出三枚粒子选项() {
        show(narrativeRaw = "plain", backgroundRaw = "particle")
        org.junit.Assert.assertEquals(1, countText("✦ 星光"))
        org.junit.Assert.assertEquals(0, countText("背景色"))
        org.junit.Assert.assertEquals(0, countText("自定义背景图请在对应角色的档案中设置（每个角色独立）；未设置时回退到柔和粒子。"))
    }

    @org.junit.Test fun 背景纯色档只出输入格() {
        show(narrativeRaw = "plain", backgroundRaw = "solidColor")
        org.junit.Assert.assertEquals(1, countText("背景色"))
        org.junit.Assert.assertEquals(0, countText("✦ 星光"))
    }

    @org.junit.Test fun 背景自定义图片档只出说明() {
        show(narrativeRaw = "plain", backgroundRaw = "customImage")
        org.junit.Assert.assertEquals(1, countText("自定义背景图请在对应角色的档案中设置（每个角色独立）；未设置时回退到柔和粒子。"))
        org.junit.Assert.assertEquals(0, countText("✦ 星光"))
        org.junit.Assert.assertEquals(0, countText("背景色"))
    }
}

/**
 * T2：回复规则页（屏 4）。E3：四枚步进器的互钳（最少值到 `max−1` 封顶 / 最多值到 `min+1` 触底）·
 * 思考模型时多一行琥珀警示且滑杆**不禁用**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliReplyRuleContentTest : LiuliScreenTestBase() {

    private val segmentRanges = mutableListOf<Pair<Int, Int>>()

    private fun show(min: Int, max: Int, thinking: Boolean) = host {
        LiuliReplyRuleContent(
            settings = AppSettings(replySegmentMin = min, replySegmentMax = max),
            waitSeconds = 1.5f,
            chatModelIsThinking = thinking,
            onSetWaitSeconds = {},
            onSetSegmentRange = { a, b -> segmentRanges += a to b },
            onSetVoiceRoundRange = { _, _ -> },
            onSetTemperature = {},
            onBack = {},
        )
    }

    @org.junit.Test fun 当前范围行按真值回显() {
        show(min = 2, max = 5, thinking = false)
        // 「当前范围」在两组各出一次；条数那一组的值是「2-5 条」。
        org.junit.Assert.assertEquals(2, countText("当前范围"))
        org.junit.Assert.assertEquals(1, countText("2-5 条"))
    }

    @org.junit.Test fun 最少条数加到max减一即封顶() {
        // min = 4、max = 5 → 「最少条数」的允许上界 = max − 1 = 4，已到界 ⇒ 加号禁用。
        show(min = 4, max = 5, thinking = false)
        val plus = compose.onAllNodesWithContentDescription("增加")
        // 四枚步进器共八枚钮，第 0/1 枚属「最少条数」（+ 在后）。
        plus[0].performScrollTo().assertIsNotEnabled()
    }

    @org.junit.Test fun 最少条数没到界时点加号写回新范围() {
        show(min = 2, max = 5, thinking = false)
        compose.onAllNodesWithContentDescription("增加")[0].performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(3 to 5), segmentRanges)
    }

    @org.junit.Test fun 思考模型时多一行琥珀警示() {
        show(min = 2, max = 5, thinking = true)
        org.junit.Assert.assertEquals(1, countText("当前聊天模型为思考模型，创造力对其不生效。"))
    }

    @org.junit.Test fun 非思考模型时没有那行警示() {
        show(min = 2, max = 5, thinking = false)
        org.junit.Assert.assertEquals(0, countText("当前聊天模型为思考模型，创造力对其不生效。"))
    }
}

/**
 * T2：日历页（屏 6）。E5：集成关 → 「操作确认」与整个权限区都不在场；集成开 + 未授权 → 出两枚钮；
 * 集成开 + 已授权 → 状态词换「已授权」且两枚钮收起。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliCalendarContentTest : LiuliScreenTestBase() {

    private val integrationWrites = mutableListOf<Boolean>()
    private var grants = 0

    private fun show(enabled: Boolean, granted: Boolean, confirm: Boolean = true) = host {
        LiuliCalendarAwarenessContent(
            integrationEnabled = enabled,
            actionConfirmation = confirm,
            granted = granted,
            onSetIntegrationEnabled = { integrationWrites += it },
            onSetActionConfirmation = {},
            onRequestPermission = { grants++ },
            onOpenSystemSettings = {},
            onBack = {},
        )
    }

    @org.junit.Test fun 集成关时操作确认与权限区都不在场() {
        show(enabled = false, granted = false)
        org.junit.Assert.assertEquals(0, countText("操作确认"))
        org.junit.Assert.assertEquals(0, countText("未授权"))
        org.junit.Assert.assertEquals(
            0,
            countText("⚠️ 读到的日历事件会随提示词一起发送给你配置的大模型服务商（如 DeepSeek / MiniMax）。不想上传就不要授权。"),
        )
    }

    @org.junit.Test fun 集成开未授权时出两枚钮() {
        show(enabled = true, granted = false)
        org.junit.Assert.assertEquals(1, countText("操作确认"))
        org.junit.Assert.assertEquals(1, countText("未授权"))
        compose.onNodeWithText("授予日历权限").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(1, grants)
    }

    @org.junit.Test fun 集成开已授权时两枚钮收起() {
        show(enabled = true, granted = true)
        org.junit.Assert.assertEquals(1, countText("已授权"))
        org.junit.Assert.assertEquals(0, countText("授予日历权限"))
    }

    @org.junit.Test fun 点集成开关写回() {
        show(enabled = false, granted = false)
        compose.onNodeWithText("日历集成").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf(true), integrationWrites)
    }
}

/**
 * T2：内容过滤页（屏 5）。E4：预设 / 自定义两组各自的空态 · 自定义行三件（开关 / 编辑 / 删除）各自回调 ·
 * 编辑态正则非法 → 红字 + 保存禁用 · 正则合法 → 保存可点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliContentFilterContentTest : LiuliScreenTestBase() {

    private val toggles = mutableListOf<Pair<String, Boolean>>()
    private val edits = mutableListOf<String>()
    private val deletes = mutableListOf<String>()
    private var adds = 0

    private fun rule(id: String, name: String, preset: Boolean, enabled: Boolean = true) =
        com.situ.aichat.content.ContentFilterRule(
            id = id,
            name = name,
            pattern = "\\d+",
            isEnabled = enabled,
            isPreset = preset,
            mode = com.situ.aichat.content.FilterMode.REMOVE,
            replacement = "",
        )

    private fun showList(rules: List<com.situ.aichat.content.ContentFilterRule>) = host {
        LiuliContentFilterListContent(
            rules = rules,
            onToggle = { id, on -> toggles += id to on },
            onAdd = { adds++ },
            onEdit = { edits += it.id },
            onDelete = { deletes += it },
            onBack = {},
        )
    }

    @org.junit.Test fun 两组都空时各出各的空态() {
        showList(emptyList())
        org.junit.Assert.assertEquals(1, countText("还没有预设规则"))
        org.junit.Assert.assertEquals(1, countText("还没有自定义规则"))
        // 「添加自定义规则」恒在（空态也要给入口）。
        compose.onNodeWithText("添加自定义规则").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(1, adds)
    }

    @org.junit.Test fun 自定义行的编辑与删除各自回调() {
        showList(listOf(rule("c1", "我的规则", preset = false)))
        org.junit.Assert.assertEquals(0, countText("还没有自定义规则"))
        compose.onNodeWithContentDescription("编辑规则").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("删除规则").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("c1"), edits)
        org.junit.Assert.assertEquals(listOf("c1"), deletes)
    }

    @org.junit.Test fun 预设行整行可点即翻开关() {
        showList(listOf(rule("p1", "去掉旁白", preset = true, enabled = false)))
        compose.onNodeWithText("去掉旁白").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("p1" to true), toggles)
    }

    @org.junit.Test fun 编辑态正则非法时红字在场且保存禁用() {
        host {
            LiuliContentFilterEditScreen(
                editing = LiuliEditingRule.new().copy(pattern = "["),
                onCancel = {},
                onSave = {},
            )
        }
        org.junit.Assert.assertEquals(1, countText("正则表达式格式无效"))
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }

    @org.junit.Test fun 编辑态正则合法时无红字且保存可点() {
        val saved = mutableListOf<String>()
        host {
            LiuliContentFilterEditScreen(
                editing = LiuliEditingRule.new().copy(name = "去数字", pattern = "\\d+"),
                onCancel = {},
                onSave = { saved += it.pattern },
            )
        }
        org.junit.Assert.assertEquals(0, countText("正则表达式格式无效"))
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(listOf("\\d+"), saved)
    }

    @org.junit.Test fun 编辑态正则为空时保存也禁用() {
        host {
            LiuliContentFilterEditScreen(editing = LiuliEditingRule.new(), onCancel = {}, onSave = {})
        }
        // pattern 空 = 不显红（不是「错」），但 canSave 仍为 false（逐字照暖陶 :302–303）。
        org.junit.Assert.assertEquals(0, countText("正则表达式格式无效"))
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }
}
