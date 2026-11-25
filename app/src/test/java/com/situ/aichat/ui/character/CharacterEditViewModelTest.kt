package com.situ.aichat.ui.character

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.work.NotificationTemplateWorker
import com.situ.aichat.world.member.WorldMembershipService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CharacterEditViewModel] T2-3（W13 图纸 §7 + 4A 新建选世界书）：
 * 新建模式 joinWorld / initialWorldBookUuids 实时二选一联动与保存落库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterEditViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private lateinit var membershipService: WorldMembershipService
    private lateinit var worldBookRepo: WorldBookRepository

    @Before
    fun setUp() {
        mockkObject(NotificationTemplateWorker.Companion)
        every { NotificationTemplateWorker.enqueueForCharacter(any(), any()) } returns Unit
    }

    @After
    fun tearDown() = unmockkObject(NotificationTemplateWorker.Companion)

    private fun buildCreateVm(): CharacterEditViewModel {
        membershipService = mockk(relaxed = true)
        worldBookRepo = mockk(relaxed = true)
        return CharacterEditViewModel(
            characterRepo = mockk(relaxed = true),
            conversationRepo = mockk(relaxed = true),
            settingsRepo = mockk(relaxed = true),
            currencyService = mockk(relaxed = true),
            economyMaintenance = mockk(relaxed = true),
            ttsConfigRepo = mockk(relaxed = true),
            previewer = mockk(relaxed = true),
            membershipService = membershipService,
            worldBookRepo = worldBookRepo,
            personaCompiler = mockk(relaxed = true),
            characterWriteLock = CharacterWriteLock(),
            appContext = mockk<Context>(relaxed = true),
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun `save_joinWorld为true_插入后委托join`() {
        val vm = buildCreateVm()
        vm.update { it.copy(name = "苏晚", joinWorld = true) }
        vm.save {}
        idle()
        coVerify(exactly = 1) { membershipService.join(any(), any()) }
        coVerify(exactly = 0) { worldBookRepo.bind(any(), any()) }
    }

    @Test
    fun `save_joinWorld为false_零调用join`() {
        val vm = buildCreateVm()
        vm.update { it.copy(name = "苏晚", joinWorld = false) }
        vm.save {}
        idle()
        coVerify(exactly = 0) { membershipService.join(any(), any()) }
    }

    @Test
    fun `新建选世界书_自动关joinWorld_save落库绑定且不join`() {
        val vm = buildCreateVm()
        vm.setCreateJoinWorld(true)
        assertTrue(vm.state.value.joinWorld)

        vm.selectInitialWorldBookSole("book-1")
        assertFalse("选中世界书必须自动关加入世界", vm.state.value.joinWorld)
        assertEquals(listOf("book-1"), vm.state.value.initialWorldBookUuids)

        vm.update { it.copy(name = "谢清晏") }
        vm.save {}
        idle()
        coVerify(exactly = 1) { worldBookRepo.bind(any(), "book-1") }
        coVerify(exactly = 0) { membershipService.join(any(), any()) }
    }

    @Test
    fun `新建开joinWorld_自动清空预选世界书`() {
        val vm = buildCreateVm()
        vm.toggleInitialWorldBook("book-1", true)
        vm.toggleInitialWorldBook("book-2", true)
        assertEquals(listOf("book-1", "book-2"), vm.state.value.initialWorldBookUuids)

        vm.setCreateJoinWorld(true)
        assertTrue(vm.state.value.joinWorld)
        assertTrue("开启加入世界必须清空预选世界书", vm.state.value.initialWorldBookUuids.isEmpty())
    }

    private fun buildCreateVmRealContext(): CharacterEditViewModel =
        CharacterEditViewModel(
            characterRepo = mockk(relaxed = true),
            conversationRepo = mockk(relaxed = true),
            settingsRepo = mockk(relaxed = true),
            currencyService = mockk(relaxed = true),
            economyMaintenance = mockk(relaxed = true),
            ttsConfigRepo = mockk(relaxed = true),
            previewer = mockk(relaxed = true),
            membershipService = mockk(relaxed = true),
            worldBookRepo = mockk(relaxed = true),
            personaCompiler = mockk(relaxed = true),
            characterWriteLock = CharacterWriteLock(),
            appContext = RuntimeEnvironment.getApplication(),
            savedStateHandle = SavedStateHandle(),
        )

    @Test
    fun `新建模式_预填默认示例对话模板`() {
        val app = RuntimeEnvironment.getApplication()
        val expected = app.getString(R.string.character_example_dialogues_default)
        val vm = buildCreateVmRealContext()
        assertTrue("默认模板资源不应为空", expected.isNotBlank())
        assertEquals("新建模式应预填默认示例对话", expected, vm.state.value.exampleDialogues)
    }

    @Test
    fun `编辑模式_不预填示例对话保持既有值`() {
        val existing = CharacterEntity(uuid = "c1", name = "旧角色", creationDate = 0L, exampleDialogues = "")
        val characterRepo = mockk<CharacterRepository>(relaxed = true)
        coEvery { characterRepo.get("c1") } returns existing
        coEvery { characterRepo.currentRelationship("c1") } returns null
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()

        val vm = CharacterEditViewModel(
            characterRepo = characterRepo,
            conversationRepo = mockk(relaxed = true),
            settingsRepo = settingsRepo,
            currencyService = mockk(relaxed = true),
            economyMaintenance = mockk(relaxed = true),
            ttsConfigRepo = mockk(relaxed = true),
            previewer = mockk(relaxed = true),
            membershipService = mockk(relaxed = true),
            worldBookRepo = mockk(relaxed = true),
            personaCompiler = mockk(relaxed = true),
            characterWriteLock = CharacterWriteLock(),
            appContext = RuntimeEnvironment.getApplication(),
            savedStateHandle = SavedStateHandle(mapOf("characterUuid" to "c1")),
        )
        idle()
        assertEquals("编辑既有角色不应预填示例对话", "", vm.state.value.exampleDialogues)
    }
}
