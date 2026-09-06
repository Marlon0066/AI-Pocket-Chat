package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.GlassTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 外观设置（11.4a）：脸（暖陶 / 琉璃）+ 玻璃透明度档 + 深浅模式 + Material You 动态取色。读写都走 [SettingsPreferences]（DataStore）。
 * 根部主题由 [com.situ.aichat.ui.AppViewModel.appearance] 直接观察同一份偏好，这里只服务设置屏。
 */
@HiltViewModel
class AppearanceSettingsViewModel @Inject constructor(
    private val settings: SettingsPreferences,
) : ViewModel() {

    val mode: StateFlow<AppearanceMode> = settings.appearanceMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceMode.SYSTEM)

    /** 界面「脸」（暖陶 / 琉璃·与深浅正交）。默认暖陶，用户切换即时落 DataStore、根部主题即时换。 */
    val skin: StateFlow<AppSkin> = settings.appSkin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSkin.CLAY)

    /** 琉璃玻璃「透明度」档（清透 / 着色）。默认清透；只在琉璃 + 有实时模糊能力时给选。 */
    val glassTier: StateFlow<GlassTier> = settings.glassTier
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlassTier.CLEAR)

    val useDynamicColor: StateFlow<Boolean> = settings.useDynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 悬浮底栏背景不透明度（过渡丝滑化·A1）；默认 0.88，用户拖动滑块调节（0.5–1.0）。 */
    val bottomNavOpacity: StateFlow<Float> = settings.bottomNavOpacity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.88f)

    fun setMode(mode: AppearanceMode) {
        viewModelScope.launch { settings.setAppearanceMode(mode) }
    }

    fun setSkin(skin: AppSkin) {
        viewModelScope.launch { settings.setAppSkin(skin) }
    }

    fun setGlassTier(tier: GlassTier) {
        viewModelScope.launch { settings.setGlassTier(tier) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setUseDynamicColor(enabled) }
    }

    fun setBottomNavOpacity(opacity: Float) {
        viewModelScope.launch { settings.setBottomNavOpacity(opacity) }
    }
}
