package com.situ.aichat.ui.settings

import android.os.Looper
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.tts.TtsConfiguration
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsProviderType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 设置主页回显 VM 的 T2（SETTINGS_REORG D6）：MockK 假掉四路数据源，断言五路 StateFlow
 * 按规格映射（外观两路直通、TTS 只取 providerType、通知取对应布尔、深层记忆加载状态直通）。
 * WhileSubscribed 需有订阅者才拉上游——用 Robolectric 主循环驱动（同 WorldBookEntryEditViewModelTest 惯例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsOverviewViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun buildVm(
        skin: AppSkin,
        mode: AppearanceMode,
        provider: TtsProviderType,
        notifications: Boolean,
        embedderState: TextEmbedder.LoadState = TextEmbedder.LoadState.LOADED,
    ): SettingsOverviewViewModel {
        val prefs = mockk<SettingsPreferences> {
            every { appSkin } returns flowOf(skin)
            every { appearanceMode } returns flowOf(mode)
        }
        val appSettings = mockk<AppSettings> {
            every { notificationsEnabled } returns notifications
        }
        val settingsRepo = mockk<SettingsRepository> {
            every { this@mockk.appSettings } returns flowOf(appSettings)
        }
        val ttsConfig = mockk<TtsConfiguration> {
            every { providerType } returns provider
        }
        val ttsRepo = mockk<TtsConfigurationRepository> {
            every { configuration } returns flowOf(ttsConfig)
        }
        val embedder = mockk<TextEmbedder> {
            every { loadState } returns MutableStateFlow(embedderState)
        }
        return SettingsOverviewViewModel(prefs, settingsRepo, ttsRepo, embedder)
    }

    /** 订阅全部四路（WhileSubscribed 才开闸）并驱动主循环，读完 value 再退订。 */
    private fun <T> withSubscriptions(vm: SettingsOverviewViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val flows: List<StateFlow<*>> = listOf(
            vm.appSkin, vm.appearanceMode, vm.ttsProvider, vm.notificationsEnabled,
        )
        flows.forEach { flow -> scope.launch { flow.collect {} } }
        idle()
        return try {
            block()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun `五路回显按规格映射·组合一`() {
        val vm = buildVm(
            skin = AppSkin.LIULI,
            mode = AppearanceMode.DARK,
            provider = TtsProviderType.MINIMAX,
            notifications = false,
        )
        withSubscriptions(vm) {
            assertEquals(AppSkin.LIULI, vm.appSkin.value)
            assertEquals(AppearanceMode.DARK, vm.appearanceMode.value)
            assertEquals(TtsProviderType.MINIMAX, vm.ttsProvider.value)
            assertFalse(vm.notificationsEnabled.value)
            assertEquals(TextEmbedder.LoadState.LOADED, vm.embedderLoadState.value)
        }
    }

    @Test
    fun `五路回显按规格映射·组合二（反向值防对称写错）`() {
        val vm = buildVm(
            skin = AppSkin.CLAY,
            mode = AppearanceMode.LIGHT,
            provider = TtsProviderType.VOLINK,
            notifications = true,
            embedderState = TextEmbedder.LoadState.FAILED,
        )
        withSubscriptions(vm) {
            assertEquals(AppSkin.CLAY, vm.appSkin.value)
            assertEquals(AppearanceMode.LIGHT, vm.appearanceMode.value)
            assertEquals(TtsProviderType.VOLINK, vm.ttsProvider.value)
            assertTrue(vm.notificationsEnabled.value)
            assertEquals(TextEmbedder.LoadState.FAILED, vm.embedderLoadState.value)
        }
    }
}
