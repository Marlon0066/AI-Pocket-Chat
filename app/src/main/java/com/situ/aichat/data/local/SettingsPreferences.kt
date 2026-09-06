package com.situ.aichat.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.GlassTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide lightweight settings (DataStore). The iOS app keeps these on the AppSettings @Model;
 * on Android, app-wide flags live in DataStore.
 *
 * Agreement gate mirrors iOS `UserAgreementView.currentVersion`: we store the accepted version
 * string and re-prompt whenever [CURRENT_AGREEMENT_VERSION] is bumped.
 */
@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val acceptedAgreementVersion: Flow<String> =
        dataStore.data.map { it[KEY_AGREEMENT_VERSION] ?: "" }

    /** True until the current agreement version has been accepted. */
    val needsAgreement: Flow<Boolean> =
        acceptedAgreementVersion.map { it != CURRENT_AGREEMENT_VERSION }

    suspend fun acceptCurrentAgreement() {
        dataStore.edit { it[KEY_AGREEMENT_VERSION] = CURRENT_AGREEMENT_VERSION }
    }

    // MARK: - 首启欢迎引导（11.4b；对齐 iOS hasCompletedOnboarding，协议同意后、首启一次）

    /** 是否已看过首启 4 页欢迎引导（默认 false → 首启展示一次）。 */
    val hasCompletedOnboarding: Flow<Boolean> =
        dataStore.data.map { it[KEY_COMPLETED_ONBOARDING] ?: false }

    suspend fun completeOnboarding() {
        dataStore.edit { it[KEY_COMPLETED_ONBOARDING] = true }
    }

    // MARK: - 后台可靠性引导（13.7a；首次开启依赖后台的功能时主动弹一次，关了不再弹）

    /** 是否已弹过 HyperOS 后台可靠性引导（默认 false → 首次开启后台功能时弹一次；设备本地，不进备份）。 */
    val hasPromptedReliability: Flow<Boolean> =
        dataStore.data.map { it[KEY_PROMPTED_RELIABILITY] ?: false }

    suspend fun markReliabilityPrompted() {
        dataStore.edit { it[KEY_PROMPTED_RELIABILITY] = true }
    }

    // MARK: - 高级模式（15.2-P1 批0 / P1-24；对齐 iOS SettingsView.swift:22 @AppStorage("advancedModeEnabled")）

    /**
     * 高级模式开关（默认 false=设置首屏隐藏高级分组，新用户不被高级项淹没）。纯可见性 gate，
     * 不影响既有设置的运行。设备本地（=iOS UserDefaults 语义），不进备份。
     */
    val advancedModeEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_ADVANCED_MODE] ?: false }

    suspend fun setAdvancedModeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ADVANCED_MODE] = enabled }
    }

    // MARK: - 外观（11.4a；对齐 iOS appearanceMode + 安卓特有 Material You 动态取色开关）

    /** 深浅模式（默认跟随系统，对齐 iOS `AppearanceMode` 默认 .system）。 */
    val appearanceMode: Flow<AppearanceMode> =
        dataStore.data.map { AppearanceMode.fromRaw(it[KEY_APPEARANCE_MODE]) }

    /**
     * Material You 动态取色开关（安卓特有）。**Fable-5 Phase 0 起默认关**=品牌陶土玫调色板，
     * Monet 降为 opt-in「跟随壁纸」（设计语言 §1.5）。老用户曾显式开过的仍保留其选择（DataStore 有存值）。
     */
    val useDynamicColor: Flow<Boolean> =
        dataStore.data.map { it[KEY_USE_DYNAMIC_COLOR] ?: false }

    suspend fun setAppearanceMode(mode: AppearanceMode) {
        dataStore.edit { it[KEY_APPEARANCE_MODE] = mode.raw }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_DYNAMIC_COLOR] = enabled }
    }

    // MARK: - 界面「脸」（琉璃第二张脸·2026-09-04·见 FABLE5_THEME_LIULI_PROPOSAL.md §7.1）

    /**
     * 界面「脸」（默认暖陶 [AppSkin.CLAY]）。与深浅模式正交；用户在外观设置切换。设备本地（=iOS UserDefaults 语义）。
     * DataStore key 沿用历史串 `"theme_palette"`（不迁移·老值 `"qinghua"` 由 [AppSkin.fromRaw] 回退暖陶）。
     */
    val appSkin: Flow<AppSkin> =
        dataStore.data.map { AppSkin.fromRaw(it[KEY_THEME_PALETTE]) }

    suspend fun setAppSkin(skin: AppSkin) {
        dataStore.edit { it[KEY_THEME_PALETTE] = skin.raw }
    }

    /** 琉璃玻璃「透明度」档（默认清透 [GlassTier.CLEAR]）。只影响琉璃的玻璃片；暖陶下无消费者。 */
    val glassTier: Flow<GlassTier> =
        dataStore.data.map { GlassTier.fromRaw(it[KEY_GLASS_TIER]) }

    suspend fun setGlassTier(tier: GlassTier) {
        dataStore.edit { it[KEY_GLASS_TIER] = tier.raw }
    }

    /**
     * 悬浮底栏背景不透明度（过渡丝滑化·A1）。默认 0.88=内容隐隐透到胶囊栏后；1.0=实色（旧观感）。
     * 用户在外观设置拖动调节（范围 0.5–1.0）；底栏观察此偏好即时反映。
     */
    val bottomNavOpacity: Flow<Float> =
        dataStore.data.map { it[KEY_BOTTOM_NAV_OPACITY] ?: 0.88f }

    suspend fun setBottomNavOpacity(opacity: Float) {
        dataStore.edit { it[KEY_BOTTOM_NAV_OPACITY] = opacity }
    }

    companion object {
        /** Mirrors iOS `UserAgreementView.currentVersion`. Bump to re-prompt all users. */
        const val CURRENT_AGREEMENT_VERSION = "1.1"
        private val KEY_AGREEMENT_VERSION = stringPreferencesKey("accepted_agreement_version")
        private val KEY_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        private val KEY_PROMPTED_RELIABILITY = booleanPreferencesKey("has_prompted_reliability")
        private val KEY_ADVANCED_MODE = booleanPreferencesKey("advanced_mode_enabled")
        private val KEY_APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        private val KEY_USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        private val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        private val KEY_GLASS_TIER = stringPreferencesKey("glass_tier")
        private val KEY_BOTTOM_NAV_OPACITY = floatPreferencesKey("bottom_nav_opacity")
    }
}
