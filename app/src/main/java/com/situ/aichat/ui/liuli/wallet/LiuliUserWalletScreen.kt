package com.situ.aichat.ui.liuli.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import com.situ.aichat.economy.WalletLedger
import com.situ.aichat.ui.components.AnimatedCoinText
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmentPosition
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.designsystem.liuliCardSegment
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.ui.wallet.UserWalletViewModel
import com.situ.aichat.ui.wallet.walletCategoryIconStyle
import com.situ.aichat.ui.wallet.walletFilterLabel

/**
 * 我的钱包页（琉璃 2.0 脸 · 💰只读，零钱写路径）。与 `UserWalletScreen` 共用 [UserWalletViewModel]；
 * 余额卡 + 兑换码入口 + 分段收支账本全部走半透明玻璃材质与自研分段控件。
 */
@Composable
fun LiuliUserWalletScreen(
    onBack: () -> Unit,
    onOpenGiftShop: () -> Unit,
    onOpenRedeemCode: () -> Unit,
    viewModel: UserWalletViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(WalletLedger.Filter.ALL) }
    val nowMillis = remember(s.transactions) { System.currentTimeMillis() }
    val dark = LocalIsDarkTheme.current
    val listState = rememberLazyListState()
    val collapsed = rememberLargeTitleCollapsed(listState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) { viewModel.markAllWalletNewsViewed() }

    val filtered = remember(s.transactions, filter) { WalletLedger.applyFilter(s.transactions, filter) }

    LiuliPage(
        title = stringResource(R.string.wallet_title),
        onBack = onBack,
        collapsed = collapsed,
        actions = {
            LiuliPageCircleAction(
                onClick = onOpenGiftShop,
                contentDescription = stringResource(R.string.wallet_gift_shop),
                icon = AppActionIcons.Gift,
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = LiuliPageGeometry.navRow,
                bottom = navBottom + LiuliPageGeometry.pageBottom,
            ),
        ) {
            item(key = "title") {
                LiuliLargeTitle(stringResource(R.string.wallet_title))
                Spacer(Modifier.height(LiuliPageGeometry.titleGap))
            }
            item(key = "hero") {
                LiuliBalanceCard(
                    balance = s.balance,
                    monthlyEarn = s.monthlyEarn,
                    monthlySpend = s.monthlySpend,
                    balanceLoaded = s.loaded,
                    dark = dark,
                    modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter).padding(bottom = 12.dp),
                )
            }
            item(key = "redeem") {
                LiuliRedeemBanner(
                    dark = dark,
                    onClick = onOpenRedeemCode,
                    modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter).padding(bottom = 12.dp),
                )
            }
            item(key = "ledger_header") {
                LiuliLedgerHeader(
                    totalCount = s.transactions.size,
                    filter = filter,
                    onFilterChange = { filter = it },
                    dark = dark,
                    modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                )
            }
            if (filtered.isEmpty()) {
                item(key = "ledger_empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(LiuliSegmentPosition.Bottom, dark)
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp, top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val colors = AppTheme.colors
                        val typography = AppTheme.typography
                        Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(30.dp), tint = colors.text.tertiary)
                        Text(
                            stringResource(
                                if (filter == WalletLedger.Filter.ALL) R.string.wallet_ledger_empty else R.string.wallet_ledger_empty_filtered,
                                stringResource(walletFilterLabel(filter)),
                            ),
                            style = typography.body,
                            color = colors.text.secondary,
                        )
                        if (filter == WalletLedger.Filter.ALL) {
                            Text(stringResource(R.string.wallet_ledger_empty_hint), style = typography.caption, color = colors.text.tertiary)
                        }
                    }
                }
            } else {
                itemsIndexed(filtered, key = { _, tx -> tx.uuid }) { idx, tx ->
                    val isLast = idx == filtered.lastIndex
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(if (isLast) LiuliSegmentPosition.Bottom else LiuliSegmentPosition.Middle, dark)
                            .padding(start = 20.dp, end = 20.dp, bottom = if (isLast) 16.dp else 0.dp),
                    ) {
                        LiuliLedgerRow(tx, nowMillis)
                        if (!isLast) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 48.dp)
                                    .height(0.5.dp)
                                    .background(LiuliMaterials.divider(dark)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiuliBalanceCard(
    balance: Int,
    monthlyEarn: Int,
    monthlySpend: Int,
    balanceLoaded: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .liuliCardMaterial(LiuliShapes.group, dark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            val balanceDesc = stringResource(R.string.wallet_my_coins) + " " +
                "%,d".format(balance) + " " + stringResource(R.string.wallet_coins_unit)
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = balanceDesc }) {
                Text(stringResource(R.string.wallet_my_coins), style = typography.label, color = colors.text.secondary)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedCoinText(
                        balance,
                        style = LocalTextStyle.current.copy(fontSize = 42.sp, fontWeight = FontWeight.Bold, color = colors.text.primary),
                        animateChanges = balanceLoaded,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.wallet_coins_unit),
                        style = typography.label,
                        color = colors.text.secondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        Brush.linearGradient(listOf(colors.economy.goldGradientStart, colors.economy.goldGradientEnd)),
                        RoundedCornerShape(14.dp),
                    ),
            ) {
                Icon(Icons.Filled.CreditCard, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(LiuliMaterials.divider(dark)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiuliStatCol(stringResource(R.string.wallet_month_income), monthlyEarn, isEarn = true, Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 16.dp).width(0.5.dp).height(30.dp).background(LiuliMaterials.divider(dark)))
            LiuliStatCol(stringResource(R.string.wallet_month_expense), monthlySpend, isEarn = false, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LiuliStatCol(title: String, amount: Int, isEarn: Boolean, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val desc = "$title ${"%,d".format(amount)} ${stringResource(R.string.wallet_coins_unit)}"
    Column(modifier.clearAndSetSemantics { contentDescription = desc }) {
        Text(title, style = typography.caption, color = colors.text.secondary)
        Spacer(Modifier.height(3.dp))
        Text(
            (if (isEarn) "+" else "-") + "%,d".format(amount),
            style = typography.titleSmall,
            color = if (isEarn) colors.status.onSuccess else colors.text.primary,
        )
    }
}

@Composable
private fun LiuliRedeemBanner(dark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .liuliCardMaterial(LiuliShapes.group, dark)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(colors.economy.gold.copy(alpha = 0.16f), CircleShape),
        ) {
            Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.redeem_entry_title), style = typography.label, color = colors.text.primary)
            Text(stringResource(R.string.redeem_entry_subtitle), style = typography.caption, color = colors.text.secondary)
        }
        Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = colors.text.tertiary)
    }
}

@Composable
private fun LiuliLedgerHeader(
    totalCount: Int,
    filter: WalletLedger.Filter,
    onFilterChange: (WalletLedger.Filter) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .liuliCardSegment(LiuliSegmentPosition.Top, dark)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wallet_ledger), style = typography.titleSmall, color = colors.text.primary)
            Spacer(Modifier.weight(1f))
            if (totalCount > 0) {
                Text(stringResource(R.string.wallet_ledger_count, totalCount), style = typography.caption, color = colors.text.secondary)
            }
        }
        LiuliSegmented(
            options = WalletLedger.Filter.entries,
            selected = filter,
            label = { stringResource(walletFilterLabel(it)) },
            onSelect = onFilterChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LiuliLedgerRow(tx: CurrencyTransactionEntity, nowMillis: Long) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val category = CurrencyTransactionCategory.fromRaw(tx.categoryRaw)
    val isEarn = CurrencyTransactionKind.fromRaw(tx.kindRaw) == CurrencyTransactionKind.EARN
    val note = tx.note.ifEmpty { category.displayName }
    val rowDesc = "$note，${if (isEarn) "收入" else "支出"} ${"%,d".format(tx.amount)} " +
        "${stringResource(R.string.wallet_coins_unit)}，${WalletLedger.dateLabel(tx.timestamp, nowMillis)}"
    val (icon, tint) = walletCategoryIconStyle(category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = rowDesc },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(tint.copy(alpha = 0.15f), CircleShape),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(note, style = typography.label, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(WalletLedger.dateLabel(tx.timestamp, nowMillis), style = typography.caption, color = colors.text.secondary)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (isEarn) "+" else "-") + "%,d".format(tx.amount),
            style = typography.label,
            fontWeight = FontWeight.SemiBold,
            color = if (isEarn) colors.status.onSuccess else colors.text.primary,
        )
    }
}
