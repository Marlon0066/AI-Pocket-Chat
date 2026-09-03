package com.situ.aichat.ui.character

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.memory.ManualEditResult
import com.situ.aichat.prompt.memory.MemorySummaryCoordinator
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 记忆编辑页渲染与交互（图纸 2026-09-01 件③ UI·§4.2/§4.3）。
 *
 * 断言从规格独立反推：分区态出两个只读节头 + 「标题固定」胶囊 + 提示行；无改动时保存钮禁用、改一行即可用；
 * 点保存真的把重组后的正文交给 coordinator；冲突弹窗两个按钮文案落定；未保存返回先弹确认。
 * @Config 屏尺寸配真机档——Robolectric 默认 320×470 会把按钮推出可视区，performClick 静默不命中（假绿教训）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MemoryEditScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private fun str(res: Int) = app.getString(res)

    private val uuid = "char-1"
    private val longHeader = "【长期事实】"
    private val recentHeader = "【近期经历】"
    private val storedMemory = "$longHeader\n- 喜欢猫\n\n$recentHeader\n- [2026-06-10] 去了公园"

    private val characterRepo = mockk<CharacterRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val coordinator = mockk<MemorySummaryCoordinator>(relaxed = true)

    private fun vm(memory: String = storedMemory): MemoryEditViewModel {
        coEvery { characterRepo.get(uuid) } returns
            CharacterEntity(uuid = uuid, name = "角色", creationDate = 0L, memorySummary = memory)
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(memorySummaryMaxLength = 5_000)
        return MemoryEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(MemoryEditViewModel.ARG_CHARACTER_UUID to uuid)),
            characterRepo = characterRepo,
            settingsRepo = settingsRepo,
            coordinator = coordinator,
        )
    }

    /** 钮族吃 [LocalAppHaptics]（无默认值，不注入即抛）——统一在此供给假触觉。 */
    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun show(viewModel: MemoryEditViewModel, onClose: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                MemoryEditScreen(onClose = onClose, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun sectionsMode_rendersHeadersHintAndCounter() {
        show(vm())
        compose.onNodeWithText(str(R.string.memory_edit_title), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.memory_edit_hint), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(longHeader, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(recentHeader, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.memory_edit_recent_tip), useUnmergedTree = true).assertIsDisplayed()
        // 计数行（%1$d / %2$d）——字数非零、上限 5000。
        compose.onNodeWithText("/ 5000", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun saveButton_disabledUntilEdited_thenPersistsComposedText() {
        val viewModel = vm()
        coEvery { coordinator.applyManualEdit(any(), any(), any(), any()) } returns ManualEditResult.Saved
        show(viewModel)

        compose.onNodeWithText(str(R.string.memory_edit_save)).assertIsNotEnabled()

        // 第一个可输入框 = 长期事实节。
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("- 喜欢猫\n- 在学吉他")
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.memory_edit_save)).assertIsEnabled()

        compose.onNodeWithText(str(R.string.memory_edit_save)).performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) {
            coordinator.applyManualEdit(uuid, storedMemory, match { it.contains("在学吉他") && it.contains(recentHeader) }, false)
        }
    }

    @Test
    fun wholeMode_showsFallbackNotice() {
        // E15：老记忆无标准分节 → 整段编辑 + 琥珀提示条，且不出节头胶囊。
        show(vm(memory = "她喜欢猫，最近在学吉他。"))
        compose.onNodeWithText(str(R.string.memory_edit_fallback_notice), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().let {
            assertTrue("整段态只有一个输入框", it.size == 1)
        }
    }

    @Test
    fun conflict_showsDialogWithBothChoices() {
        val viewModel = vm()
        coEvery { coordinator.applyManualEdit(any(), any(), any(), false) } returns
            ManualEditResult.Conflict("$longHeader\n- 后台写的新版")
        show(viewModel)

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("- 我编辑的内容")
        compose.onNodeWithText(str(R.string.memory_edit_save)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.memory_edit_conflict_title), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.memory_edit_conflict_save), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.memory_edit_conflict_reload), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unsavedBack_asksBeforeLeaving_andKeepsPageOpen() {
        var closed = false
        val viewModel = vm()
        show(viewModel) { closed = true }

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("- 改了一行")
        compose.waitForIdle()
        viewModel.requestClose()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.memory_edit_discard_title), useUnmergedTree = true).assertIsDisplayed()
        assertTrue("确认前绝不关页面", !closed)

        compose.onNodeWithText(str(R.string.memory_edit_discard_confirm), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue("确认放弃后关页面", closed)
    }
}
