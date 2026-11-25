package com.situ.aichat.ui.settings

import android.os.Looper
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.AutoBackupConfig
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.worldbook.WorldBookRepository
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
 * 设置主页聚合快照 VM 的 T2（设置全面重构卷一）：
 * MockK 假掉五路数据源，验证 [SettingsOverviewViewModel.snapshot] 与 [embedderLoadState] 映射精确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsOverviewViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun buildVm(
        activeConfig: ApiConfigEntity? = null,
        skin: AppSkin = AppSkin.CLAY,
        mode: AppearanceMode = AppearanceMode.SYSTEM,
        provider: TtsProviderType = TtsProviderType.SYSTEM,
        appSettings: AppSettings = AppSettings(),
        waitSeconds: Float? = null,
        backupConfig: AutoBackupConfig = AutoBackupConfig(),
        worldBooks: List<WorldBookEntity> = emptyList(),
        embedderState: TextEmbedder.LoadState = TextEmbedder.LoadState.LOADED,
    ): SettingsOverviewViewModel {
        val apiRepo = mockk<ApiConfigRepository> {
            every { observeActive() } returns flowOf(activeConfig)
        }
        val prefs = mockk<SettingsPreferences> {
            every { appSkin } returns flowOf(skin)
            every { appearanceMode } returns flowOf(mode)
        }
        val settingsRepo = mockk<SettingsRepository> {
            every { this@mockk.appSettings } returns flowOf(appSettings)
            every { chatSendWaitSecondsFlow } returns flowOf(waitSeconds)
            every { autoBackupConfig } returns flowOf(backupConfig)
        }
        val ttsRepo = mockk<TtsConfigurationRepository> {
            every { configuration } returns flowOf(TtsConfiguration(providerType = provider))
        }
        val wbRepo = mockk<WorldBookRepository> {
            every { observeAllBooks() } returns flowOf(worldBooks)
        }
        val embedder = mockk<TextEmbedder> {
            every { loadState } returns MutableStateFlow(embedderState)
        }
        return SettingsOverviewViewModel(apiRepo, prefs, settingsRepo, ttsRepo, wbRepo, embedder)
    }

    private fun <T> withSubscription(vm: SettingsOverviewViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.snapshot.collect {} }
        idle()
        return try {
            block()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun `聚合快照按规格映射·组合一`() {
        val cfg = mockk<ApiConfigEntity> {
            every { providerName } returns "DeepSeek"
            every { modelName } returns "deepseek-chat"
        }
        val b1 = mockk<WorldBookEntity> { every { enabled } returns true }
        val b2 = mockk<WorldBookEntity> { every { enabled } returns false }
        val b3 = mockk<WorldBookEntity> { every { enabled } returns true }
        val vm = buildVm(
            activeConfig = cfg,
            skin = AppSkin.LIULI,
            mode = AppearanceMode.DARK,
            provider = TtsProviderType.MINIMAX,
            appSettings = AppSettings(
                replySegmentMin = 3,
                replySegmentMax = 7,
                offlineNarrativeDetailRaw = "detailed",
                characterCanSendStickersEnabled = true,
                shortTermMemoryLength = 40,
                scheduleSystemEnabled = true,
                crossCharacterLevel = 2,
                growthSystemEnabled = false,
                petSystemEnabled = true,
                currencySystemEnabled = false,
                worldVividnessTier = AppSettings.WORLD_VIVIDNESS_RICH,
                diaryAutoGenerateEnabled = true,
                diaryAutoGenerateTime = "22:30",
                momentAutoPostFrequency = 3,
                storyCreationTemperature = 1.2,
                notificationsEnabled = false,
                calendarIntegrationEnabled = false,
            ),
            waitSeconds = 2.5f,
            backupConfig = AutoBackupConfig(enabled = true, lastBackupAt = 1_700_000_000_000L),
            worldBooks = listOf(b1, b2, b3),
            embedderState = TextEmbedder.LoadState.LOADED,
        )
        withSubscription(vm) {
            val s = vm.snapshot.value
            assertEquals("DeepSeek · deepseek-chat", s.activeConfigLabel)
            assertEquals(AppSkin.LIULI, s.appSkin)
            assertEquals(AppearanceMode.DARK, s.appearanceMode)
            assertEquals(2.5f, s.chatSendWaitSeconds, 0.001f)
            assertEquals(3..7, s.replySegmentRange)
            assertEquals(TtsProviderType.MINIMAX.displayName, s.ttsProviderName)
            assertEquals("detailed", s.offlineNarrativeDetailRaw)
            assertTrue(s.stickersEnabled)
            assertEquals(40, s.shortTermMemoryLength)
            assertEquals(2, s.enabledWorldBookCount)
            assertTrue(s.scheduleEnabled)
            assertEquals(2, s.crossCharacterLevel)
            assertFalse(s.growthEnabled)
            assertTrue(s.petEnabled)
            assertFalse(s.currencyEnabled)
            assertEquals(AppSettings.WORLD_VIVIDNESS_RICH, s.worldVividnessTier)
            assertTrue(s.diaryAutoEnabled)
            assertEquals("22:30", s.diaryAutoTime)
            assertEquals(3, s.momentAutoPostFrequency)
            assertEquals(1.2, s.storyTemperature, 0.001)
            assertFalse(s.notificationsEnabled)
            assertFalse(s.calendarEnabled)
            assertTrue(s.autoBackupEnabled)
            assertEquals(1_700_000_000_000L, s.lastBackupAt)
            assertEquals(TextEmbedder.LoadState.LOADED, vm.embedderLoadState.value)
        }
    }

    @Test
    fun `聚合快照默认与未配置状态映射正确`() {
        val vm = buildVm(
            activeConfig = null,
            skin = AppSkin.CLAY,
            mode = AppearanceMode.LIGHT,
            provider = TtsProviderType.VOLINK,
            embedderState = TextEmbedder.LoadState.FAILED,
        )
        withSubscription(vm) {
            val s = vm.snapshot.value
            assertNull(s.activeConfigLabel)
            assertEquals(AppSkin.CLAY, s.appSkin)
            assertEquals(AppearanceMode.LIGHT, s.appearanceMode)
            assertEquals(1.5f, s.chatSendWaitSeconds, 0.001f)
            assertEquals(TtsProviderType.VOLINK.displayName, s.ttsProviderName)
            assertEquals(0, s.enabledWorldBookCount)
            assertTrue(s.notificationsEnabled)
            assertEquals(9, s.activeNotifCategoryCount)
            assertEquals(TextEmbedder.LoadState.FAILED, vm.embedderLoadState.value)
        }
    }

    @Test
    fun `全部通知类别关闭时活跃计数为空以回显已关闭`() {
        val vm = buildVm(
            appSettings = AppSettings(
                notificationsEnabled = false,
                worldNotificationTier = AppSettings.WORLD_NOTIFICATION_SILENT,
                economyNotificationTier = com.situ.aichat.notification.EconomyNotificationTier.OFF.raw,
                milestoneNotificationEnabled = false,
                momentNewPostNotificationEnabled = false,
                notifMomentInteractionEnabled = false,
                notifStoryFinishedEnabled = false,
                notifMeetingReminderEnabled = false,
                notifRedPacketExpiringEnabled = false,
            ),
        )
        withSubscription(vm) {
            val s = vm.snapshot.value
            assertFalse(s.notificationsEnabled)
            assertNull(s.activeNotifCategoryCount)
        }
    }
}
