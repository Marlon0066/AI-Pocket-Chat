package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliMasterCard
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.SystemTogglesViewModel

/** 琉璃 2.0「钱包与礼物」设置页（设置全面重构卷三）。 */
@Composable
fun LiuliWalletSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenNotifications: () -> Unit = {},
    viewModel: SystemTogglesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LiuliWalletSettingsContent(
        settings = s,
        onSetCurrencyEnabled = viewModel::setCurrencySystemEnabled,
        onSetProactiveGift = viewModel::setCharacterProactiveGiftEnabled,
        onOpenNotifications = onOpenNotifications,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LiuliWalletSettingsContent(
    settings: AppSettings,
    onSetCurrencyEnabled: (Boolean) -> Unit,
    onSetProactiveGift: (Boolean) -> Unit,
    onOpenNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.settings_hub_wallet)
    val enabled = settings.currencySystemEnabled
    val disabledReason = if (!enabled) stringResource(R.string.settings_wallet_disabled_reason) else null
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliMasterCard(
                        title = stringResource(R.string.settings_wallet_master_title),
                        subtitle = stringResource(R.string.settings_wallet_master_sub),
                        icon = Icons.Filled.AccountBalanceWallet,
                        tileTone = LiuliTileTone.Gold,
                        checked = enabled,
                        onCheckedChange = onSetCurrencyEnabled,
                    )

                    LiuliGroup(header = stringResource(R.string.sys_proactive_gift_title)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.sys_proactive_gift_title),
                            subtitle = stringResource(R.string.sys_proactive_gift_subtitle),
                            checked = enabled && settings.characterProactiveGiftEnabled,
                            enabled = enabled,
                            disabledReason = disabledReason,
                            divider = false,
                            onCheckedChange = onSetProactiveGift,
                        )
                    }

                    LiuliGroup(header = stringResource(R.string.notif_settings_title)) {
                        LiuliNavRow(
                            title = stringResource(R.string.settings_wallet_notif_link),
                            subtitle = stringResource(R.string.settings_wallet_notif_link_sub),
                            icon = Icons.Filled.Notifications,
                            tileColor = LiuliTileTone.Rose,
                            divider = false,
                            onClick = onOpenNotifications,
                        )
                    }
                }
            }
        }
    }
}
