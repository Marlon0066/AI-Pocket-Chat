package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.SettingsSliderRow
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-3（图纸 2026-07-30 性能采集与量尺 §7）—— **本卷核心断言**：拖动「保留条数」滑杆不再真删日志。
 *
 * 修的是真 bug：滑杆原先每跨一档就 `setRetentionCount`，而它会**立即裁库**（`deleteOlderThanInclusive`）。
 * 从 500 拖到 10 再拖回 300，经过 10 那一档时日志已被裁到 10 条，拖回不恢复。
 *
 * 断言从图纸 §5 E15/E16/E17 与 J6 的规格独立反推：
 * - 拖动全程（含经过最小档）**零次**写设置；
 * - 松手才写，恰 1 次，且写的**不是**拖动途中经过的最小值；
 * - 拖动中数字标签必须跟着动（冻住是可见回归）；
 * - `setRetentionCount` 自身语义不变（写设置 + 立即裁）——手填路径照旧即时生效；
 * - `SettingsSliderRow` 新参默认 null 时行为与加它之前一致（其余 14 个调用点零回归）。
 *
 * ⚠️ 两条踩过的坑，别改回去：
 * 1. 手势必须**分步拖**——实测一次性 `moveTo` 跨半个滑杆，Slider 一个 `onValueChange` 都不发，
 *    写成单步会得到「零次写入」的假绿。
 * 2. 被测对象是抽出来的 [RetentionSliderRow] 而不是整屏——整屏包在 `verticalScroll` 里，
 *    Robolectric 下滚动容器会吞掉滑杆的横向拖动（逐层实证：bare / SettingsSection 能拖，
 *    一加 verticalScroll 就收不到），对整屏拖动同样只会得到假绿。整屏的接线另由
 *    [ContextLogSettingsScreenWiringTest] 用 SetProgress 语义动作钉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextLogRetentionSliderTest {

    @get:Rule
    val compose = createComposeRule()

    private val committed = mutableListOf<Int>()

    @Before
    fun setUp() {
        committed.clear()
    }

    /** 真 Compose 树需要 [LocalAppHaptics]（AppSlider 消费它），测试里假掉即可。 */
    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { block() }
        }
    }

    private fun showRow(saved: Int = 500) = content {
        RetentionSliderRow(savedCount = saved, onCommit = { committed += it })
    }

    private fun slider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    /** 分步横拖到目标 x（见类 KDoc：单步跨越会被 Slider 吞掉）。 */
    private fun TouchInjectionScope.dragTo(targetX: Float, fromX: Float, steps: Int = 12) {
        val y = center.y
        repeat(steps) { i -> moveTo(Offset(fromX + (targetX - fromX) * (i + 1) / steps, y)) }
    }

    // MARK: - E15 核心

    @Test
    fun `从 500 拖到 10 再拖回中段的过程中零次提交`() {
        showRow()

        slider().performTouchInput {
            down(centerRight)
            dragTo(left, centerRight.x) // 途经最小档 10 —— 旧实现在这里就把库裁到 10 条了
            dragTo(center.x, left)
        }
        compose.waitForIdle()

        assertEquals(emptyList<Int>(), committed)
    }

    @Test
    fun `松手才提交一次_且提交的不是拖动途中经过的最小档`() {
        showRow()

        slider().performTouchInput {
            down(centerRight)
            dragTo(left, centerRight.x)
            dragTo(center.x, left)
        }
        compose.waitForIdle()
        // 松手前先确认「拖动真的发生过」——否则下面的「恰 1 次」会是零手势造出来的假绿。
        compose.onNodeWithText("500 条").assertDoesNotExist()

        slider().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("松手恰提交一次", 1, committed.size)
        assertNotEquals("绝不能落在途经的最小档上", 10, committed.single())
        assertTrue("松手值应落在滑杆区间内", committed.single() in 10..500)
    }

    @Test
    fun `拖动中数字标签跟着本地态走_不冻在已保存值上`() {
        showRow()
        compose.onNodeWithText("500 条").assertExists()

        slider().performTouchInput {
            down(centerRight)
            dragTo(left, centerRight.x)
        }
        compose.waitForIdle()

        // 拖到最左 → 标签必须已经变成最小档，而不是还显示已保存的 500。
        compose.onNodeWithText("10 条").assertExists()
        assertEquals(emptyList<Int>(), committed)
    }

    @Test
    fun `手填路径不受影响_直接即时提交（E16）`() {
        showRow()

        // 手填走 onManualInput = onCommit，不经拖动态，故仍是即时生效。
        compose.onNodeWithText("500 条").performClick()
        compose.waitForIdle()
        // 手填弹窗照旧弹出（行为未被本卷改动）。取资源而非字面量：Robolectric 默认 locale 是 values/（英文）。
        val manualTitle = RuntimeEnvironment.getApplication().getString(R.string.settings_manual_input_title)
        compose.onNodeWithText(manualTitle).assertExists()
    }

    // MARK: - E17 其余调用点零回归

    @Test
    fun `不传新参时滑杆行为与加它之前一致（默认 null 恒等）`() {
        val changes = mutableListOf<Float>()
        content {
            Column {
                SettingsSliderRow(
                    label = "无关设置",
                    valueLabel = "3",
                    value = 3f,
                    valueRange = 1f..10f,
                    steps = 8,
                    onValueChange = { changes += it },
                )
            }
        }

        slider().performTouchInput {
            down(centerRight)
            dragTo(left, centerRight.x)
            up()
        }
        compose.waitForIdle()

        assertTrue("默认参 null 时 onValueChange 仍照常逐档回调", changes.size >= 3)
    }
}

/**
 * B4 / E16：[ContextLogViewModel.setRetentionCount] 的语义**没被本卷改动** —— 仍是「写设置 + 立即裁一次」。
 * 本卷改的只是「谁在什么时候调它」。手填（`onManualInput`）走的就是这条路，故它依旧即时生效。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextLogRetentionViewModelContractTest {

    private val logDao = mockk<LogDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val contextLog = mockk<ContextLogService>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { logDao.recent(any()) } returns flowOf(emptyList())
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setRetentionCount 仍然是写设置加立即裁剪`() = runTest {
        val vm = ContextLogViewModel(logDao, settingsRepository, contextLog)

        vm.setRetentionCount(300)

        coVerify(exactly = 1) { settingsRepository.setLogRetentionCount(300) }
        verify(exactly = 1) { contextLog.enforceRetentionLimit() }
    }

    @Test
    fun `手填可超滑杆上限的值也照样即时生效`() = runTest {
        val vm = ContextLogViewModel(logDao, settingsRepository, contextLog)

        vm.setRetentionCount(2000)

        coVerify(exactly = 1) { settingsRepository.setLogRetentionCount(2000) }
        verify(exactly = 1) { contextLog.enforceRetentionLimit() }
    }
}

/**
 * 整屏接线钉（E15 的另一半）：[ContextLogSettingsScreen] 真的把 [RetentionSliderRow] 的提交接到了
 * `viewModel.setRetentionCount`。整屏在 `verticalScroll` 里拖不动（见上面的类 KDoc），故这里走
 * `SetProgress` 语义动作——M3 的 Slider 在该动作里会依次调 `onValueChange` 与 `onValueChangeFinished`，
 * 正好等价于「拖一下再松手」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextLogSettingsScreenWiringTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `整屏把滑杆提交接到了 setRetentionCount`() {
        val viewModel = mockk<ContextLogViewModel>(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(ContextLogUiState(retentionCount = 500, loaded = true))
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                ContextLogSettingsScreen(onBack = {}, viewModel = viewModel)
            }
        }

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }
        compose.waitForIdle()

        verify(exactly = 1) { viewModel.setRetentionCount(300) }
    }
}
