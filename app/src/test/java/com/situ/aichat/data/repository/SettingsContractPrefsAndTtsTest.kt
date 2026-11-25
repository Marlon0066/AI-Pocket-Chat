package com.situ.aichat.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.security.ApiKeyStore
import com.situ.aichat.tts.TtsConfiguration
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsProviderType
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * D-11 设置值合约测试（卷一护栏）：锁定 `SettingsPreferences`、`TtsConfigurationRepository` 与 `ApiFunctionRouter`
 * 的键名、默认值与写入读回。
 */
class SettingsContractPrefsAndTtsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "contract_prefs.preferences_pb") })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test fun settingsPreferencesRoundTrip() = runBlocking {
        val prefs = SettingsPreferences(store)
        assertEquals(AppearanceMode.SYSTEM, prefs.appearanceMode.first())
        assertEquals(AppSkin.DEFAULT, prefs.appSkin.first())
        assertEquals(GlassTier.SHEER, prefs.glassTier.first())
        assertEquals(0.88f, prefs.bottomNavOpacity.first(), 1e-4f)
        assertFalse(prefs.useDynamicColor.first())
        assertFalse(prefs.advancedModeEnabled.first())

        prefs.setAppearanceMode(AppearanceMode.DARK)
        prefs.setAppSkin(AppSkin.CLAY)
        prefs.setGlassTier(GlassTier.STANDARD)
        prefs.setBottomNavOpacity(0.75f)
        prefs.setAdvancedModeEnabled(true)

        assertEquals(AppearanceMode.DARK, prefs.appearanceMode.first())
        assertEquals(AppSkin.CLAY, prefs.appSkin.first())
        assertEquals(GlassTier.STANDARD, prefs.glassTier.first())
        assertEquals(0.75f, prefs.bottomNavOpacity.first(), 1e-4f)
        assertTrue(prefs.advancedModeEnabled.first())
    }

    @Test fun ttsConfigurationRoundTrip() = runBlocking {
        val keyStore = mockk<ApiKeyStore>(relaxed = true)
        val ttsRepo = TtsConfigurationRepository(store, keyStore)
        assertEquals(TtsProviderType.SYSTEM, ttsRepo.configuration.first().providerType)

        val updated = TtsConfiguration(
            providerType = TtsProviderType.OPENAI,
            providerName = "OpenAI",
            baseURL = "https://api.openai.com/v1",
            modelName = "tts-1",
            defaultSystemVoiceIdentifier = "zh-CN-voice",
            defaultRemoteVoiceID = "alloy",
        )
        ttsRepo.update(updated)
        val loaded = ttsRepo.configuration.first()
        assertEquals(TtsProviderType.OPENAI, loaded.providerType)
        assertEquals("alloy", loaded.defaultRemoteVoiceID)
        assertEquals("zh-CN-voice", loaded.defaultSystemVoiceIdentifier)
    }

    @Test fun apiFunctionRouterAssignmentsRoundTrip() = runBlocking {
        val router = ApiFunctionRouter(store)
        val target = ApiFunction.entries.first()
        router.setAssignment(target, "cfg-uuid-1")
        assertEquals("cfg-uuid-1", router.assignments.first()[target])
        router.clearAssignmentsForConfig("cfg-uuid-1")
        org.junit.Assert.assertNull(router.assignments.first()[target])
    }
}
