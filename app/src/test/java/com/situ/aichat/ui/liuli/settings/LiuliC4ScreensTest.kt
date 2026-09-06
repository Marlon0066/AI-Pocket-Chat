package com.situ.aichat.ui.liuli.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.liuli.backup.LiuliBackupImportPreviewScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：功能开关页（屏 24）。E20：**三层门控**——成长关 → 藏「自动关系进化」；日程关 → 藏「角色之间互相来往」
 * 整块；跨角色开（level > 0）→ 才露三档分段。💰 相邻：货币关 → 藏「角色主动送礼」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliSystemTogglesContentTest : LiuliScreenTestBase() {

    private val crossLevels = mutableListOf<Int>()

    private fun show(settings: AppSettings) = host {
        LiuliSystemTogglesContent(
            settings = settings,
            onSetGrowth = {},
            onSetRelationshipAutoAdvance = {},
            onSetSchedule = {},
            onSetCrossCharacterLevel = { crossLevels += it },
            onSetPet = {},
            onSetCurrency = {},
            onSetProactiveGift = {},
            onBack = {},
        )
    }

    @Test fun 成长关时藏掉自动关系进化() {
        show(AppSettings(growthSystemEnabled = false, scheduleSystemEnabled = false, currencySystemEnabled = false))
        assertEquals(0, countText("自动关系进化"))
        // 正向证据：成长那一枚本身还在。
        assertEquals(1, countText("角色成长"))
    }

    @Test fun 日程关时藏掉跨角色整块() {
        show(AppSettings(scheduleSystemEnabled = false))
        assertEquals(0, countText("角色之间互相来往"))
    }

    @Test fun 跨角色为零时不露三档分段() {
        show(AppSettings(scheduleSystemEnabled = true, crossCharacterLevel = 0))
        assertEquals(1, countText("角色之间互相来往"))
        assertEquals(0, countText("偶尔"))
    }

    @Test fun 跨角色大于零时露三档且点得动() {
        show(AppSettings(scheduleSystemEnabled = true, crossCharacterLevel = 1))
        assertEquals(1, countText("偶尔"))
        compose.onNodeWithText("频繁").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(3), crossLevels)
    }

    @Test fun 开跨角色开关时写回1() {
        show(AppSettings(scheduleSystemEnabled = true, crossCharacterLevel = 0))
        compose.onNodeWithText("角色之间互相来往").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(1), crossLevels)
    }

    @Test fun 货币关时藏掉主动送礼() {
        show(AppSettings(currencySystemEnabled = false))
        assertEquals(0, countText("角色主动送礼"))
    }
}

/**
 * T2：后台保障页（屏 25）。已豁免电池优化 → 状态词换「已放行」且那枚钮灰掉；
 * 自启动那张卡**没有状态词**故恒可点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliBackgroundReliabilityContentTest : LiuliScreenTestBase() {

    private var batteryTaps = 0
    private var autoStartTaps = 0

    private fun show(exempt: Boolean) = host {
        LiuliBackgroundReliabilityContent(
            batteryExempt = exempt,
            onRequestBatteryExempt = { batteryTaps++ },
            onOpenAutoStart = { autoStartTaps++ },
            onBack = {},
        )
    }

    @Test fun 未豁免时电池钮可点() {
        show(exempt = false)
        val buttons = compose.onAllNodesWithText("去设置")
        assertEquals("两张卡各一枚钮", 2, buttons.fetchSemanticsNodes().size)
        buttons[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, batteryTaps)
    }

    @Test fun 已豁免时电池钮禁用而自启动钮仍可点() {
        show(exempt = true)
        val buttons = compose.onAllNodesWithText("去设置")
        buttons[0].assertIsNotEnabled()
        buttons[1].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, batteryTaps)
        assertEquals(1, autoStartTaps)
    }
}

/**
 * T2：内核观测台（屏 26）。三态 + 每张卡 11–12 行；行文本走纯函数 `observatoryLines`，逐句可核。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliKernelObservatoryContentTest : LiuliScreenTestBase() {

    @Test fun 读取中出提示句() {
        host { LiuliKernelObservatoryContent(loading = true, rows = emptyList(), onBack = {}) }
        assertEquals(1, countText("读取中…"))
    }

    @Test fun 没有角色出空态句() {
        host { LiuliKernelObservatoryContent(loading = false, rows = emptyList(), onBack = {}) }
        assertEquals(1, countText("还没有角色。"))
    }
}

/**
 * T2：备份导入预览屏（屏 30）。E25：全跳过 → 确认钮禁用 + 那句解释在场；完成态 → 换成结果区 + 「完成」钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
internal class LiuliBackupImportPreviewTest : LiuliScreenTestBase() {

    private var confirms = 0

    private fun row(uuid: String, conflict: Boolean) = com.situ.aichat.data.backup.CharacterPreviewRow(
        uuid = uuid,
        name = "小云",
        messageCount = 12,
        hasConflict = conflict,
        existingName = if (conflict) "小云（旧）" else null,
        avatarBytes = null,
    )

    private fun show(
        rows: List<com.situ.aichat.data.backup.CharacterPreviewRow>,
        strategies: Map<String, com.situ.aichat.data.backup.ImportStrategy> = emptyMap(),
        result: com.situ.aichat.data.backup.ImportResult? = null,
    ) = host {
        LiuliBackupImportPreviewScreen(
            preview = com.situ.aichat.data.backup.BackupPreview(characters = rows, mediaCount = 0, hasGlobalData = false),
            strategies = strategies,
            importResult = result,
            busy = false,
            onSetStrategy = { _, _ -> },
            onConfirm = { confirms++ },
            onDismiss = {},
        )
    }

    @Test fun 有可导角色时确认钮可点() {
        show(listOf(row("u1", conflict = false)))
        // 确认钮住 bottomBar（不在滚动容器里）——直接点，别 performScrollTo。
        compose.onNodeWithText("确认导入").performClick()
        compose.waitForIdle()
        assertEquals(1, confirms)
    }

    @Test fun 全跳过时确认钮禁用且解释句在场() {
        show(
            rows = listOf(row("u1", conflict = true)),
            strategies = mapOf("u1" to com.situ.aichat.data.backup.ImportStrategy.SKIP),
        )
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText("确认导入").performClick()
        compose.waitForIdle()
        assertEquals(0, confirms)
        // 「解释句在场」得真断（复核 R1：原例名字有、断言无）。
        compose.onNodeWithText("所有角色都已选择跳过", substring = true).assertExists()
    }
}
