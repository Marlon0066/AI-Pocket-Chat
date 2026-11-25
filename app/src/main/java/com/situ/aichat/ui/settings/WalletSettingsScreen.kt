package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.SettingsMasterCard
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 暖陶「钱包与礼物」设置页（设置全面重构卷三）：
 * 顶卡总控货币系统 + 从属「角色主动送礼」开关 + 前往「通知」管理经济/红包提醒的快捷链接。
 * 纯设置读写 UI，不触碰任何余额/账本/退款逻辑。
 */
@Composable
fun WalletSettingsScreen(
    onBack: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    viewModel: SystemTogglesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    WalletSettingsContent(
        settings = s,
        onSetCurrencyEnabled = viewModel::setCurrencySystemEnabled,
        onSetProactiveGift = viewModel::setCharacterProactiveGiftEnabled,
        onOpenNotifications = onOpenNotifications,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletSettingsContent(
    settings: AppSettings,
    onSetCurrencyEnabled: (Boolean) -> Unit,
    onSetProactiveGift: (Boolean) -> Unit,
    onOpenNotifications: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    val enabled = settings.currencySystemEnabled
    val disabledReason = if (!enabled) stringResource(R.string.settings_wallet_disabled_reason) else null

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_hub_wallet),
                onBack = onBack,
                lifted = lifted,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth(),
        ) {
            SettingsMasterCard(
                title = stringResource(R.string.settings_wallet_master_title),
                subtitle = stringResource(R.string.settings_wallet_master_sub),
                icon = Icons.Filled.AccountBalanceWallet,
                checked = enabled,
                onCheckedChange = onSetCurrencyEnabled,
            )

            SettingsSection(title = stringResource(R.string.sys_proactive_gift_title)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.sys_proactive_gift_title),
                    subtitle = stringResource(R.string.sys_proactive_gift_subtitle),
                    checked = enabled && settings.characterProactiveGiftEnabled,
                    enabled = enabled,
                    disabledReason = disabledReason,
                    onCheckedChange = onSetProactiveGift,
                )
            }

            SettingsSection(title = stringResource(R.string.notif_settings_title)) {
                AppSettingsRow(
                    title = stringResource(R.string.settings_wallet_notif_link),
                    subtitle = stringResource(R.string.settings_wallet_notif_link_sub),
                    icon = Icons.Filled.Notifications,
                    showChevron = true,
                    onClick = onOpenNotifications,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
