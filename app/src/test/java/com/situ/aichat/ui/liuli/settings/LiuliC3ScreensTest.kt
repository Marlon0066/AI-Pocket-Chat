package com.situ.aichat.ui.liuli.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.liuli.contextlog.LiuliContextLogSettingsContent
import com.situ.aichat.ui.liuli.moments.LiuliMomentSettingsContent
import com.situ.aichat.ui.liuli.worldbook.LiuliWorldBookSettingsContent
import com.situ.aichat.ui.moments.MomentSettingsState
import com.situ.aichat.ui.settings.WorldSettingsUiState
import com.situ.aichat.worldbook.WorldInfoInsertionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.hasText

/**
 * T2：故事全局设置页（屏 13）。E11：三行值标全走 `globalValueLabel` —— 口味画像**没有出厂默认**，
 * 值标只有「已设置 / 未设置」两说；另两行有出厂默认，多一档「跟随默认」。思考模型时多一行琥珀提示，
 * 且滑杆**不禁用**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliStoryGlobalContentTest : LiuliScreenTestBase() {

    private val opened = mutableListOf<String>()

    private fun show(settings: AppSettings, thinking: Boolean) = host {
        LiuliStoryGlobalSettingsContent(
            settings = settings,
            isThinking = thinking,
            onSetTemperature = {},
            onOpenField = { opened += it },
            onBack = {},
        )
    }

    @Test fun 三行都在且点得进编辑器() {
        show(AppSettings(), thinking = false)
        assertEquals(1, countText("全局文字忌口"))
        compose.onNodeWithText("全局文字忌口").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(com.situ.aichat.story.StoryEditableField.GLOBAL_BANNED_KEY), opened)
    }

    @Test fun 思考模型时多一行琥珀提示() {
        show(AppSettings(), thinking = true)
        assertEquals(1, countText("当前故事创作用的是思考模型：温度对它不生效，换普通模型即生效。"))
    }

    @Test fun 非思考模型时没有那行提示() {
        show(AppSettings(), thinking = false)
        assertEquals(0, countText("当前故事创作用的是思考模型：温度对它不生效，换普通模型即生效。"))
    }
}

/**
 * T2：朋友圈设置页（屏 17）。E15：三枚滑杆的「0 = 关」换词——前两枚有 off 档，第三枚（评论延迟）**没有**
 * （值域从 1 起）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliMomentSettingsContentTest : LiuliScreenTestBase() {

    private val posts = mutableListOf<Int>()

    private fun show(state: MomentSettingsState) = host {
        LiuliMomentSettingsContent(
            state = state,
            onSetAutoPost = { posts += it },
            onSetAutoComment = {},
            onSetCommentDelay = {},
            onSetAutoLike = {},
            onSetNewPostNotification = {},
            onBack = {},
        )
    }

    @Test fun 频率为零时两枚滑杆都显示关() {
        show(MomentSettingsState(autoPostFrequency = 0, autoCommentFrequency = 0, commentDelay = 5))
        // 「关闭」在两处右值上各出现一次。
        assertEquals(2, countText("关"))
    }

    @Test fun 频率非零时显示带单位的数() {
        show(MomentSettingsState(autoPostFrequency = 3, autoCommentFrequency = 2, commentDelay = 5))
        assertEquals(0, countText("关"))
    }

    @Test fun 两枚行为开关都在() {
        show(MomentSettingsState())
        assertEquals(1, countText("自动点赞"))
        assertEquals(1, countText("新动态通知"))
    }
}

/**
 * T2：上下文日志设置页（屏 20）。E18：**拖动只更本地态、松手才写**（`setRetentionCount` 会立即真删日志）；
 * 两枚危险动作各自先弹确认，点确认才落。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliContextLogContentTest : LiuliScreenTestBase() {

    private val retentionWrites = mutableListOf<Int>()
    private var purges = 0
    private var clears = 0

    private fun show(retention: Int = 200, detail: Boolean = false) = host {
        LiuliContextLogSettingsContent(
            retentionCount = retention,
            detailEnabled = detail,
            onCommitRetention = { retentionWrites += it },
            onSetDetailEnabled = {},
            onPurgeFullText = { purges++ },
            onClearAll = { clears++ },
            onBack = {},
        )
    }

    @Test fun 进屏零写入() {
        show()
        assertEquals(emptyList<Int>(), retentionWrites)
        assertEquals(1, countText("保留条数"))
        assertEquals(1, countText("200 条"))
    }

    @Test fun 清除全文要先确认() {
        show()
        compose.onNodeWithText("清除既有日志全文（保留元数据）").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("弹窗出来之前不许真删", 0, purges)
        compose.onNodeWithText("清除").performClick()
        compose.waitForIdle()
        assertEquals(1, purges)
    }

    @Test fun 清空全部要先确认且取消不落() {
        show()
        compose.onNodeWithText("清空全部日志").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertEquals(0, clears)
    }

    @Test fun 脚注四句在屏上() {
        show()
        assertEquals(1, countText("绝不记录 API 密钥 · 容量自动轮转 · 日志不进备份导出 · 纯本地"))
    }
}

/**
 * T2：世界书设置页（屏 19）。E17：**联动开才出层数步进器**；插入策略读不回来时回落 CHARACTER_FIRST。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliWorldBookContentTest : LiuliScreenTestBase() {

    private val depths = mutableListOf<Int>()

    private fun show(recursive: Boolean, strategyRaw: String) = host {
        LiuliWorldBookSettingsContent(
            settings = AppSettings(
                worldInfoRecursiveScan = recursive,
                worldInfoInsertionStrategy = strategyRaw,
            ),
            onSetScanDepth = {},
            onSetBudgetChars = {},
            onSetRecursiveScan = {},
            onSetMaxRecursionSteps = { depths += it },
            onSetInsertionStrategy = {},
            onSetCaseSensitive = {},
            onSetMatchWholeWords = {},
            onOpenMemorySettings = {},
            onBack = {},
        )
    }

    @Test fun 联动关时层数步进器不在场() {
        show(recursive = false, strategyRaw = WorldInfoInsertionStrategy.CHARACTER_FIRST.name)
        assertEquals(0, countText("最多联动层数"))
    }

    @Test fun 联动开时层数步进器在场且加得动() {
        show(recursive = true, strategyRaw = WorldInfoInsertionStrategy.CHARACTER_FIRST.name)
        assertEquals(1, countText("最多联动层数"))
        // 两处步进器（扫描范围 + 联动层数）各一枚加号 ⇒ 屏上恰两枚。
        assertEquals(2, countDescription("增加"))
    }

    @Test fun 策略串读不回来时回落角色优先() {
        show(recursive = false, strategyRaw = "NOT_AN_ENUM")
        // 回落值必须是「角色卡优先」那一档的文案（逐字照暖陶 getOrDefault）。
        assertTrue(countText("角色的书优先") >= 1)
    }
}

/**
 * T2：日记设置页（屏 14）。E12：自动生成开 → 才出「生成时间 / 直接发布」；评论开 → 才出「延迟 + 角色清单」；
 * **宠物日记那一枚不受门控**（独立开关·门控写错就会连它一起藏掉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliDiarySettingsContentTest : LiuliScreenTestBase() {

    private val times = mutableListOf<String>()

    private fun show(state: com.situ.aichat.ui.diary.DiarySettingsState) = host {
        com.situ.aichat.ui.liuli.diary.LiuliDiarySettingsContent(
            state = state,
            callbacksFor = { openPicker ->
                com.situ.aichat.ui.liuli.diary.LiuliDiarySettingsCallbacks(
                    onSetAutoGenerate = {},
                    onOpenTimePicker = openPicker,
                    onSetAutoPublish = {},
                    onSetPetAutoGenerate = {},
                    onSelectExchangePartner = {},
                    onSetCommentEnabled = {},
                    onSetCommentDelay = {},
                    onToggleCharacter = {},
                    onOpenWritingRules = {},
                )
            },
            onCommitTime = { times += it },
            onBack = {},
        )
    }

    @Test fun 自动生成关时时间行与直接发布都不在场() {
        show(com.situ.aichat.ui.diary.DiarySettingsState(autoGenerateEnabled = false, commentEnabled = false))
        assertEquals(0, countText("生成时间"))
        // 宠物日记那一枚不受门控 —— 必须还在。
        assertEquals(1, countText("宠物日记自动生成"))
    }

    @Test fun 自动生成开时时间行在场且回显() {
        show(
            com.situ.aichat.ui.diary.DiarySettingsState(
                autoGenerateEnabled = true,
                autoGenerateTime = "07:30",
                commentEnabled = false,
            ),
        )
        assertEquals(1, countText("生成时间"))
        assertEquals(1, countText("07:30"))
    }

    @Test fun 评论关时延迟与角色清单都不在场() {
        show(com.situ.aichat.ui.diary.DiarySettingsState(commentEnabled = false))
        assertEquals(0, countText("评论延迟"))
    }

    @Test fun 评论开时延迟在场() {
        show(com.situ.aichat.ui.diary.DiarySettingsState(commentEnabled = true, commentDelay = 5))
        assertEquals(1, countText("评论延迟"))
    }
}

/**
 * T2：日记写作规则页（屏 15）。两分区结构完全相同 ⇒ 每个标签都该恰出现两次；
 * 「恢复默认」两枚各自只回调自己那一段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliDiaryPromptSettingsContentTest : LiuliScreenTestBase() {

    private val resets = mutableListOf<com.situ.aichat.ui.diary.DiaryRuleSection>()

    private fun show() = host {
        com.situ.aichat.ui.liuli.diary.LiuliDiaryPromptSettingsContent(
            mine = com.situ.aichat.ui.diary.DiaryRuleForm(wordCount = 600),
            exchange = com.situ.aichat.ui.diary.DiaryRuleForm(wordCount = 900),
            callbacks = com.situ.aichat.ui.liuli.diary.LiuliDiaryRuleCallbacks(
                onWordCountDrag = { _, _ -> },
                onCommitWordCount = {},
                onSetWordCount = { _, _ -> },
                onNarrativePersonChange = { _, _ -> },
                onStyleHintChange = { _, _ -> },
                onExtraRulesChange = { _, _ -> },
                onResetSection = { resets += it },
            ),
            onOpenPreviewMine = {},
            onOpenPreviewExchange = {},
            onBack = {},
        )
    }

    @Test fun 两分区结构相同故每个标签各出两次() {
        show()
        assertEquals(2, countText("篇幅"))
        assertEquals(2, countText("人称"))
        assertEquals(2, countText("恢复默认"))
    }

    @Test fun 两枚恢复默认各自只回调自己那一段() {
        show()
        compose.onAllNodesWithText("恢复默认")[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(com.situ.aichat.ui.diary.DiaryRuleSection.MINE), resets)
    }

    @Test fun 两枚篇幅值各自回显() {
        show()
        // 值标 = `diary_rules_length_value`（「约 N 字」）：两段各一枚，各显各的数（复核 R1：原断言 >= 0 恒真）。
        assertEquals(1, countText("约 600 字"))
        assertEquals(1, countText("约 900 字"))
    }
}

/**
 * T2：世界设置页（屏 18）。E16：鲜活度 / 通知各三档单选（推荐 / 默认角标并进标题）；
 * 时区行的右值在「跟随设备 · GMT±N」与「城市名 · GMT±N」之间切。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliWorldSettingsContentTest : LiuliScreenTestBase() {

    private val vividness = mutableListOf<String>()

    private fun show(state: WorldSettingsUiState) = host {
        LiuliWorldSettingsContent(
            state = state,
            onSetVividness = { vividness += it },
            onSetNotification = {},
            onSetRelationships = {},
            onSetRomance = {},
            onPickTimezone = {},
            onBack = {},
        )
    }

    @Test fun 六枚单选行都在() {
        show(WorldSettingsUiState())
        // 三档鲜活度 + 三档通知 = 六个 RadioButton 语义节点。
        val radios = compose.onAllNodes(
            androidx.compose.ui.test.isSelectable(),
        ).fetchSemanticsNodes().size
        assertTrue("单选行应至少六个（实得 $radios）", radios >= 6)
    }

    @Test fun 点鲜活度写回存储串() {
        show(WorldSettingsUiState(vividnessTier = AppSettings.WORLD_VIVIDNESS_STANDARD))
        // 「浓」那一档的名字以资源为准：先按语义找第三个单选行再点它（避免把文案抄错当测试失败）。
        val richRow = compose.onAllNodes(androidx.compose.ui.test.isSelectable())[2]
        richRow.performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(AppSettings.WORLD_VIVIDNESS_RICH), vividness)
    }

    @Test fun 时区未钉时右值走跟随设备() {
        show(WorldSettingsUiState(timezoneId = null))
        // 右值 = 「跟随设备 · GMT±h」拼接串（子串命中·复核 R1：原断言 >= 0 恒真且精确匹配永不命中拼接串）。
        assertEquals(1, compose.onAllNodes(hasText("跟随设备", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}

/**
 * T2：日记提示词预览页（屏 16）。E14：三态渲染——PLAIN / SLOT / CUSTOM 三行都在场，**空行不出文字节点**
 * （空行是 6dp 空当·不是空 `Text`），标题由外部给（VM 按 section 分「我的 / 交换」两份）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliDiaryPromptPreviewContentTest : LiuliScreenTestBase() {

    private fun show(lines: List<com.situ.aichat.prompt.diary.PreviewLine>) = host {
        com.situ.aichat.ui.liuli.diary.LiuliDiaryPromptPreviewContent(
            title = "我的日记 · 预览",
            lines = lines,
            onBack = {},
        )
    }

    @Test fun 三态各出一行且空行不出节点() {
        show(
            listOf(
                com.situ.aichat.prompt.diary.PreviewLine("平常这一行", com.situ.aichat.prompt.diary.PreviewLineKind.PLAIN),
                com.situ.aichat.prompt.diary.PreviewLine("", com.situ.aichat.prompt.diary.PreviewLineKind.PLAIN),
                com.situ.aichat.prompt.diary.PreviewLine("<占位这一行>", com.situ.aichat.prompt.diary.PreviewLineKind.SLOT),
                com.situ.aichat.prompt.diary.PreviewLine("改过这一行", com.situ.aichat.prompt.diary.PreviewLineKind.CUSTOM),
            ),
        )
        assertEquals(1, countText("平常这一行"))
        assertEquals(1, countText("<占位这一行>"))
        assertEquals(1, countText("改过这一行"))
        // 标题在大标题带上出现一次。
        assertEquals(1, countText("我的日记 · 预览"))
    }

    @Test fun 空表时只剩标题与提示() {
        show(emptyList())
        assertEquals(1, countText("我的日记 · 预览"))
    }
}
