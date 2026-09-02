package com.situ.aichat.ui.character

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.economy.CharacterEconomyMaintenanceService
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsPreviewer
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CharacterEditViewModel] T2-3（W13 图纸 §7）：新建模式 save(joinWorld=true) 角色插入后委托
 * [WorldMembershipService.join]；joinWorld=false 零调用。MockK 假全部协作者·mockkObject 拦截
 * [NotificationTemplateWorker] 的 WorkManager 静态入队·Robolectric 主循环驱动 save 的 viewModelScope。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterEditViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private lateinit var membershipService: WorldMembershipService

    @Before
    fun setUp() {
        // 建角色会静态入队通知文案 worker（WorkManager 未初始化会抛）→ 拦掉。
        mockkObject(NotificationTemplateWorker.Companion)
        every { NotificationTemplateWorker.enqueueForCharacter(any(), any()) } returns Unit
    }

    @After
    fun tearDown() = unmockkObject(NotificationTemplateWorker.Companion)

    /** 新建模式 VM（无 characterUuid → create 分支·全协作者 relaxed 假）。 */
    private fun buildCreateVm(): CharacterEditViewModel {
        membershipService = mockk(relaxed = true)
        return CharacterEditViewModel(
            characterRepo = mockk(relaxed = true),
            conversationRepo = mockk(relaxed = true),
            settingsRepo = mockk(relaxed = true),
            currencyService = mockk(relaxed = true),
            economyMaintenance = mockk(relaxed = true),
            ttsConfigRepo = mockk(relaxed = true),
            previewer = mockk(relaxed = true),
            membershipService = membershipService,
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
    }

    @Test
    fun `save_joinWorld为false_零调用join`() {
        val vm = buildCreateVm()
        vm.update { it.copy(name = "苏晚", joinWorld = false) }
        vm.save {}
        idle()
        coVerify(exactly = 0) { membershipService.join(any(), any()) }
    }

    // ---- 活人感一期 P1b · T2-1（E2）：新建预填默认示例对话 / 编辑既有绝不预填 ----

    /**
     * 新建模式 VM，appContext 用**真实** Robolectric application（getString 解析真资源），其余协作者 relaxed 假。
     * create 预填分支在 init 内**同步**执行（非协程），构造完即可读 state。
     */
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
        // 既有角色示例对话为空 → 编辑模式绝不预填（否则用户清空的示例会被默认模板复活）。
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
            personaCompiler = mockk(relaxed = true),
            characterWriteLock = CharacterWriteLock(),
            appContext = RuntimeEnvironment.getApplication(),
            savedStateHandle = SavedStateHandle(mapOf("characterUuid" to "c1")),
        )
        idle() // 驱动 edit 分支的 init 协程加载
        assertEquals("编辑既有角色不应预填示例对话", "", vm.state.value.exampleDialogues)
    }
}
