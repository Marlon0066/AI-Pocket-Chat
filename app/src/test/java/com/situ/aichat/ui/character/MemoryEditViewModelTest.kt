package com.situ.aichat.ui.character

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.memory.ManualEditResult
import com.situ.aichat.prompt.memory.MemoryEditMode
import com.situ.aichat.prompt.memory.MemorySummaryCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 记忆编辑页 VM（图纸 2026-09-01 件③·T2-4·E12/E13/E16）。
 *
 * 断言从规格独立反推：写库只经 [MemorySummaryCoordinator.applyManualEdit]（VM 绝不碰 DAO）；
 * 空文本禁存；冲突弹窗两条出路（force 覆盖 / 重载新版并清 dirty）；角色没了直接关页面。
 * Robolectric 驱动 viewModelScope（init 与 save 都在其中跑）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryEditViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val uuid = "char-1"
    private val longHeader = "【长期事实】"
    private val recentHeader = "【近期经历】"
    private val storedMemory = "$longHeader\n- 喜欢猫\n\n$recentHeader\n- [2026-06-10] 去了公园"

    private val characterRepo = mockk<CharacterRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val coordinator = mockk<MemorySummaryCoordinator>(relaxed = true)

    private fun buildVm(memory: String? = storedMemory, maxLength: Int = 5_000): MemoryEditViewModel {
        coEvery { characterRepo.get(uuid) } returns memory?.let {
            CharacterEntity(uuid = uuid, name = "角色", creationDate = 0L, memorySummary = it)
        }
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(memorySummaryMaxLength = maxLength)
        val vm = MemoryEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(MemoryEditViewModel.ARG_CHARACTER_UUID to uuid)),
            characterRepo = characterRepo,
            settingsRepo = settingsRepo,
            coordinator = coordinator,
        )
        idle()
        return vm
    }

    @Test
    fun load_splitsIntoSections_andCountsCodePoints() {
        val vm = buildVm()
        val state = vm.state.value
        assertTrue("标准两节应进分区态", state.mode is MemoryEditMode.Sections)
        assertTrue(state.loaded)
        assertEquals(5_000, state.maxLength)
        assertFalse("进屏无改动", state.dirty)
        assertFalse("无改动不许保存", state.canSave)
        assertTrue("字数须已算出", state.count > 0)
    }

    @Test
    fun edit_marksDirty_andEmptyingBlocksSave() {
        val vm = buildVm()
        vm.updateLongTerm("- 喜欢猫\n- 在学吉他")
        assertTrue(vm.state.value.dirty)
        assertTrue(vm.state.value.canSave)

        // E13：两节都清空 → 保存钮置灰（清空记忆是「删除」语义，本期不提供）。
        vm.updateLongTerm("")
        vm.updateRecent("")
        assertTrue("清空后仍是 dirty", vm.state.value.dirty)
        assertFalse("空内容绝不许保存", vm.state.value.canSave)
    }

    @Test
    fun save_delegatesToCoordinator_withBaselineAndComposedText() = runBlocking {
        val vm = buildVm()
        coEvery { coordinator.applyManualEdit(any(), any(), any(), any()) } returns ManualEditResult.Saved
        vm.updateRecent("- [2026-06-11] 一起吃了火锅")

        vm.save()
        idle()

        // 写库只经 coordinator（同锁），baseline = 进屏时的库内文本，正文 = 重组后的完整记忆。
        coVerify(exactly = 1) {
            coordinator.applyManualEdit(
                uuid,
                storedMemory,
                match { it.contains("一起吃了火锅") && it.startsWith(longHeader) },
                false,
            )
        }
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun conflict_opensDialog_andForceSaveOverwrites() = runBlocking {
        val vm = buildVm()
        val newest = "$longHeader\n- 后台整理写的新版\n\n$recentHeader\n- 新事件"
        coEvery { coordinator.applyManualEdit(any(), any(), any(), false) } returns ManualEditResult.Conflict(newest)
        coEvery { coordinator.applyManualEdit(any(), any(), any(), true) } returns ManualEditResult.Saved
        vm.updateLongTerm("- 我编辑的内容")

        vm.save()
        idle()
        assertEquals("冲突弹窗须带库内新版文本", newest, vm.state.value.conflict)

        vm.save(force = true)
        idle()
        coVerify(exactly = 1) { coordinator.applyManualEdit(uuid, storedMemory, any(), true) }
        assertNull("保存后弹窗须关", vm.state.value.conflict)
    }

    @Test
    fun conflict_reload_replacesBaselineAndClearsDirty() = runBlocking {
        val vm = buildVm()
        val newest = "$longHeader\n- 后台整理写的新版\n\n$recentHeader\n- 新事件"
        coEvery { coordinator.applyManualEdit(any(), any(), any(), false) } returns ManualEditResult.Conflict(newest)
        vm.updateLongTerm("- 我编辑的内容")
        vm.save()
        idle()

        vm.reloadFromConflict()

        assertNull(vm.state.value.conflict)
        assertFalse("重载后不再是 dirty", vm.state.value.dirty)
        val mode = vm.state.value.mode as MemoryEditMode.Sections
        assertEquals("- 后台整理写的新版", mode.longTermText)

        // 再保存时 baseline 必须已换成新版（否则马上又冲突）。
        coEvery { coordinator.applyManualEdit(any(), any(), any(), any()) } returns ManualEditResult.Saved
        vm.updateRecent("- 新事件\n- 又一件")
        vm.save()
        idle()
        coVerify(exactly = 1) { coordinator.applyManualEdit(uuid, newest, any(), false) }
    }

    @Test
    fun characterGone_closesPage() {
        // E16：进页面时角色已被删 → 直接关，不崩。closed 是 StateFlow：init 期置位不会被后订阅的 UI 漏掉。
        val vm = buildVm(memory = null)
        assertFalse("载入失败不该进编辑态", vm.state.value.loaded)
        assertTrue("角色不存在必须关页面", vm.closed.value)
    }

    @Test
    fun discardConfirmed_closesPage() {
        val vm = buildVm()
        vm.updateLongTerm("- 改了一行")
        vm.requestClose()
        vm.confirmDiscard()
        assertFalse(vm.state.value.showDiscardDialog)
        assertTrue(vm.closed.value)
    }

    @Test
    fun back_withUnsavedEdits_asksBeforeLeaving() {
        val vm = buildVm()
        vm.requestClose()
        assertFalse("无改动直接关，不弹确认", vm.state.value.showDiscardDialog)

        vm.updateLongTerm("- 改了一行")
        vm.requestClose()
        assertTrue("有未保存改动必须先确认", vm.state.value.showDiscardDialog)

        vm.dismissDiscardDialog()
        assertFalse(vm.state.value.showDiscardDialog)
    }
}
