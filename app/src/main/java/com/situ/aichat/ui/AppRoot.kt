package com.situ.aichat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberAppHaptics
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.onboarding.EulaScreen
import com.situ.aichat.ui.onboarding.OnboardingScreen
import com.situ.aichat.ui.theme.AIPocketChatTheme

/** Root gate: shows the user agreement on first launch / version bump, otherwise the main UI. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val showAgreement by viewModel.showAgreement.collectAsStateWithLifecycle()
    val showOnboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()

    // P12.6 D2：回前台/进后台观察已移至 AppViewModel（进程级 ProcessLifecycleOwner + survive 转屏），不再用此处界面级
    // LocalLifecycleOwner 的 DisposableEffect——避免转屏/切深浅色重建界面时把整套「回前台维护」又跑一遍（误触发回前台重入）。

    // 主题在此应用（11.4a）：深浅模式 + Material You 动态取色由 DataStore 偏好驱动，全屏（含协议页/加载态）统一覆盖。
    AIPocketChatTheme(
        darkTheme = appearance.mode.resolveDarkTheme(isSystemInDarkTheme()),
        dynamicColor = appearance.useDynamicColor,
        skin = appearance.skin,
        glassTier = appearance.glassTier,
    ) {
        // P15.2a：全局分级触觉底座注入，下游各模块经 LocalAppHaptics.current 取用。
        CompositionLocalProvider(LocalAppHaptics provides rememberAppHaptics()) {
            // 门控顺序 1:1 iOS：用户协议 → 首启欢迎引导 → 主界面。
            when (showAgreement) {
                null -> LoadingGate()
                true -> EulaScreen(onAccept = viewModel::acceptAgreement)
                false -> when (showOnboarding) {
                    null -> LoadingGate()
                    true -> OnboardingScreen(onComplete = viewModel::completeOnboarding)
                    false -> AIChatApp(
                        pendingNavConversation = viewModel.pendingNavConversation,
                        onNavConsumed = viewModel::consumeNavConversation,
                        pendingNavMoment = viewModel.pendingNavMoment,
                        onMomentNavConsumed = viewModel::consumeNavMoment,
                        pendingNavMomentsFeed = viewModel.pendingNavMomentsFeed,
                        onMomentsFeedNavConsumed = viewModel::consumeNavMomentsFeed,
                        pendingNavPet = viewModel.pendingNavPet,
                        onPetNavConsumed = viewModel::consumeNavPet,
                        pendingNavContacts = viewModel.pendingNavContacts,
                        onContactsNavConsumed = viewModel::consumeNavContacts,
                        pendingNavCharacterProfile = viewModel.pendingNavCharacterProfile,
                        onCharacterProfileNavConsumed = viewModel::consumeNavCharacterProfile,
                        pendingNavBackup = viewModel.pendingNavBackup,
                        onBackupNavConsumed = viewModel::consumeNavBackup,
                        pendingNavStory = viewModel.pendingNavStory,
                        onStoryNavConsumed = viewModel::consumeNavStory,
                        pendingNavWorld = viewModel.pendingNavWorld,
                        onWorldNavConsumed = viewModel::consumeNavWorld,
                        showReliabilityPrompt = viewModel.showReliabilityPrompt,
                        onDismissReliabilityPrompt = viewModel::dismissReliabilityPrompt,
                    )
                }
            }
        }
    }
}

/** DataStore 偏好加载中的占位（协议/引导门控未就绪时）。 */
@Composable
private fun LoadingGate() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppLoadingRing(size = AppLoadingRingSize.Large)
    }
}
