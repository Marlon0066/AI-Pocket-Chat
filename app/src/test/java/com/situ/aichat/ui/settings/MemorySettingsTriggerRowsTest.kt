package com.situ.aichat.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.SettingsSliderRow
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记忆设置页「长期记忆」组 T2（图纸 2026-09-05 §7 T2-7 / T2-8）：活例子文案随设置值实时重算、
 * 越界琥珀提示、两根滑杆真接到 Repository、以及 `SettingsSliderRow` 新尾参默认 null 的零回归钉。
 *
 * ⚠️ 三条配置不是装饰，去掉任一条会得到假绿（PITFALLS §1e）：
 * 1. `qualifiers` 带 `w411dp-h891dp`——Robolectric 默认屏只有 320×470，长表单尾部的节点会被推出可视区，
 *    此时断言与点击都静默不命中；带 `zh-rCN` 是因为本屏文案锁定在中文资源上（图纸 §4.4 逐字表）。
 * 2. 滑杆一律用 `SemanticsActions.SetProgress` 驱动，不用手势拖——整屏里横向拖动会被吞（本屏虽未包
 *    verticalScroll，但按行序取节点更稳）。SetProgress 等价「拖一下再松手」。
 * 3. `LocalAppHaptics` 必须提供假件（AppSlider 消费它）。
 *
 * 滑杆行序（`onAllNodes(SetProgress)` 的下标）：0 短期窗口 / 1 攒够多少轮 / 2 两次总结至少间隔 /
 * 3 摘要字数上限 / 4 结构化 / 5 向量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class MemorySettingsTriggerRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repo: SettingsRepository

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = mockk(relaxed = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { block() }
        }
    }

    /** 用给定设置快照渲染整段记忆设置。 */
    private fun showSections(settings: AppSettings) {
        every { repo.appSettings } returns MutableStateFlow(settings)
        content { MemorySettingsSections(viewModel = MemorySettingsViewModel(repo)) }
    }

    private fun slider(index: Int) =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))[index]

    // ---- T2-7：新尾参 subtitle 默认 null = 其余 14 个调用点零回归 ----

    @Test
    fun `T2_7_副标默认null时不放任何节点`() {
        content {
            SettingsSliderRow(
                label = "无副标", valueLabel = "1", value = 1f, valueRange = 0f..10f, steps = 9,
                onValueChange = {},
            )
        }
        compose.onNodeWithText("按现在的设置", substring = true).assertDoesNotExist()
        // 正向锚：行本体确实渲染出来了，上面的否定断言不是「整棵树都没画」造出来的。
        compose.onNodeWithText("无副标").assertIsDisplayed()
    }

    @Test
    fun `T2_7_副标非null时渲染出该行文字`() {
        content {
            SettingsSliderRow(
                label = "有副标", valueLabel = "1", value = 1f, valueRange = 0f..10f, steps = 9,
                subtitle = "按现在的设置：这是一行副标", onValueChange = {},
            )
        }
        compose.onNodeWithText("按现在的设置", substring = true).assertIsDisplayed()
    }

    // ---- T2-8：活例子四态 + 两滑杆接线 ----

    @Test
    fun `T2_8a_默认态活例子_第40轮_攒够10轮_30分钟`() {
        // 窗口 30 + 攒够 10 → 第一次总结在第 40 轮；间隔 30 分钟 → 带「且距上次满 N 分钟」半句。
        showSections(AppSettings(shortTermMemoryLength = 30, autoSummarizeInterval = 10, memorySummaryCooldownMinutes = 30))
        compose.onNodeWithText("第 40 轮", substring = true).assertIsDisplayed()
        compose.onNodeWithText("攒够 10 轮", substring = true).assertIsDisplayed()
        compose.onNodeWithText("满 30 分钟", substring = true).assertIsDisplayed()
    }

    @Test
    fun `T2_8b_攒够30超出窗口20_出琥珀越界提示并带窗口值`() {
        showSections(AppSettings(shortTermMemoryLength = 20, autoSummarizeInterval = 30))
        compose.onNodeWithText("超过了短期窗口（20 轮）", substring = true).assertIsDisplayed()
    }

    @Test
    fun `T2_8c_攒够设0_活例子改说已关闭自动总结`() {
        showSections(AppSettings(autoSummarizeInterval = 0))
        compose.onNodeWithText("已关闭自动总结", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第 40 轮", substring = true).assertDoesNotExist()
    }

    @Test
    fun `T2_8d_间隔设0_活例子去掉等时间那半句`() {
        showSections(AppSettings(shortTermMemoryLength = 30, autoSummarizeInterval = 10, memorySummaryCooldownMinutes = 0))
        compose.onNodeWithText("第 40 轮", substring = true).assertIsDisplayed()
        compose.onNodeWithText("且距上次", substring = true).assertDoesNotExist()
        // 尾值同步显示「不限」而不是「0 分钟」。
        compose.onNodeWithText("不限").assertIsDisplayed()
    }

    @Test
    fun `T2_8e_拖攒够滑杆到15_写进Repository`() {
        showSections(AppSettings(shortTermMemoryLength = 30, autoSummarizeInterval = 10))
        slider(1).performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }
        compose.waitForIdle()
        coVerify(exactly = 1) { repo.setAutoSummarizeInterval(15) }
    }

    @Test
    fun `T2_8f_拖间隔滑杆到45_按5吸附写进Repository`() {
        showSections(AppSettings(shortTermMemoryLength = 30, autoSummarizeInterval = 10, memorySummaryCooldownMinutes = 30))
        slider(2).performSemanticsAction(SemanticsActions.SetProgress) { it(45f) }
        compose.waitForIdle()
        coVerify(exactly = 1) { repo.setMemorySummaryCooldownMinutes(45) }
    }

    @Test
    fun `T2_8g_手填间隔600超滑杆上限_尾值照实显示600分钟`() {
        // E5：setter 不加上限钳，越界值原样展示，绝不静默改写成 180。
        showSections(AppSettings(memorySummaryCooldownMinutes = 600))
        compose.onNodeWithText("600 分钟").assertIsDisplayed()
    }
}
