package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppProfileIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.profile.ProfileDashboardViewModel

/** 钱包图标块底：`economy.goldContainer` 这个 token 不存在 → 走 `economy.gold@14%`（A-12 明写的兜底）。 */
private const val WALLET_TILE_ALPHA = 0.14f

/**
 * 琉璃「我」页（图纸 2026-09-06 卷三 §4.6 · 契约 §6 D 甲）。
 *
 * 顺序 / 分支 / 文案 = 暖陶 F7 逐字（身份卡 → `currencyEnabled` ? 两资产格 + 礼物条 : 动态全宽卡 →
 * 组间 24 → 设置条）；钱包余额与礼物款数只**读** [ProfileDashboardViewModel] 现成的 StateFlow
 * （本卷零钱路）。
 */
@Composable
fun LiuliProfileScreen(
    onEditProfile: () -> Unit,
    onOpenUserMoments: () -> Unit,
    onOpenUserWallet: () -> Unit,
    onOpenGiftShop: () -> Unit,
    onOpenGiftBox: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProfileDashboardViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val currencyEnabled by viewModel.currencyEnabled.collectAsStateWithLifecycle()
    val momentsCount by viewModel.momentsCount.collectAsStateWithLifecycle()
    val coinBalance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val receivedGiftsCount by viewModel.receivedGiftsCount.collectAsStateWithLifecycle()
    val charactersCount by viewModel.charactersCount.collectAsStateWithLifecycle()
    val companionDays by viewModel.companionDays.collectAsStateWithLifecycle()
    val memoriesCount by viewModel.memoriesCount.collectAsStateWithLifecycle()

    LiuliProfileContent(
        profile = profile,
        currencyEnabled = currencyEnabled,
        momentsCount = momentsCount,
        coinBalance = coinBalance,
        receivedGiftsCount = receivedGiftsCount,
        charactersCount = charactersCount,
        companionDays = companionDays,
        memoriesCount = memoriesCount,
        giftCatalogCount = viewModel.giftCatalogCount,
        onEditProfile = onEditProfile,
        onOpenUserMoments = onOpenUserMoments,
        onOpenUserWallet = onOpenUserWallet,
        onOpenGiftShop = onOpenGiftShop,
        onOpenGiftBox = onOpenGiftBox,
        onOpenSettings = onOpenSettings,
    )
}

/** 「我」页的长相（无 VM·T2 可直接驱动）。 */
@Composable
internal fun LiuliProfileContent(
    profile: UserProfileEntity?,
    currencyEnabled: Boolean,
    momentsCount: Int,
    coinBalance: Int,
    receivedGiftsCount: Int,
    charactersCount: Int,
    companionDays: Int?,
    memoriesCount: Int,
    giftCatalogCount: Int,
    onEditProfile: () -> Unit,
    onOpenUserMoments: () -> Unit,
    onOpenUserWallet: () -> Unit,
    onOpenGiftShop: () -> Unit,
    onOpenGiftBox: () -> Unit,
    onOpenSettings: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = AppTheme.colors
    LiuliHomeScaffold(
        title = stringResource(R.string.tab_profile),
        collapsed = rememberScrollCollapsed(scrollState),
        plus = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(
                    bottom = LiuliHomeGeometry.listBottomInset +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            LiuliLargeTitle(stringResource(R.string.tab_profile))
            Column(
                modifier = Modifier
                    .padding(horizontal = LiuliHomeGeometry.gutter)
                    .padding(top = LiuliHomeGeometry.titleGap),
                verticalArrangement = Arrangement.spacedBy(LiuliHomeGeometry.cardGap),
            ) {
                LiuliHeroCard(
                    name = profile?.nickname?.takeIf { it.isNotBlank() },
                    avatarPath = profile?.avatarPath,
                    bio = profile?.bio?.takeIf { it.isNotBlank() },
                    charactersCount = charactersCount,
                    companionDays = companionDays,
                    memoriesCount = memoriesCount,
                    onClick = onEditProfile,
                )
                if (currencyEnabled) {
                    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(LiuliHomeGeometry.cardGap)) {
                        LiuliStatTile(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            icon = AppProfileIcons.Moments,
                            tileTint = colors.accent.container,
                            tileInk = colors.accent.onContainer,
                            title = stringResource(R.string.moment_user_moments_title),
                            value = momentsCount,
                            unit = stringResource(R.string.profile_box_moments_unit),
                            hint = stringResource(R.string.profile_box_moments_hint),
                            emptyText = stringResource(R.string.profile_box_moments_empty).takeIf { momentsCount == 0 },
                            onClick = onOpenUserMoments,
                        )
                        LiuliStatTile(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            icon = AppProfileIcons.Wallet,
                            tileTint = colors.economy.gold.copy(alpha = WALLET_TILE_ALPHA),
                            tileInk = colors.economy.gold,
                            title = stringResource(R.string.wallet_title),
                            value = coinBalance,
                            unit = stringResource(R.string.profile_box_wallet_unit),
                            hint = stringResource(R.string.profile_box_wallet_hint),
                            valueColor = colors.economy.gold,
                            onClick = onOpenUserWallet,
                        )
                    }
                    LiuliGiftRow(
                        shopSub = stringResource(R.string.profile_box_shop_count, giftCatalogCount),
                        giftBoxSub = if (receivedGiftsCount > 0) {
                            stringResource(R.string.profile_box_giftbox_received, receivedGiftsCount)
                        } else {
                            stringResource(R.string.profile_box_giftbox_empty)
                        },
                        onOpenShop = onOpenGiftShop,
                        onOpenBox = onOpenGiftBox,
                    )
                } else {
                    // 货币关：只剩「我的动态」→ 全宽卡，不留半宽空格（暖陶 F7 拍板 2026-06-18）。
                    LiuliMomentsWideCard(count = momentsCount, onClick = onOpenUserMoments)
                }
                Spacer(Modifier.height(LiuliHomeGeometry.cardGap)) // 与 spacedBy 12 合计组间 24
                LiuliSettingsEntryBar(onClick = onOpenSettings)
            }
        }
    }
}
