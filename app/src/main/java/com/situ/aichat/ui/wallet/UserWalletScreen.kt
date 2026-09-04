package com.situ.aichat.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.CardSegment
import com.situ.aichat.ui.designsystem.appCardSegmentSurface
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import com.situ.aichat.economy.WalletLedger
import com.situ.aichat.ui.components.AnimatedCoinText

private val LedgerEarn = Color(0xFF34C759) // iOS ledgerEarn #34C759

/**
 * 我的钱包屏（14.6a·1:1 iOS `WalletView`·💰只读）：Hero 余额卡（余额大数字 + 本月收/支）+ 账本（筛选全部/收入/
 * 支出 + 分类图标 + 备注 + 日期 + 带号金额）。礼物店从顶栏进入（对齐 iOS）。零钱写路径。
 * 兑换码入口 banner 由 14.6c 接入（用户拍板补做兑换码）。
 *
 * 整屏 LazyColumn（P1-31 同族 P1-15）：iOS 账本卡内本就是 LazyVStack 按需构建行（WalletView.swift:293-304
 * 注释明示几千条防卡顿），Compose 懒列不能嵌 verticalScroll → 账本行拆顶层 item（key=uuid，divider 与行同捆
 * =iOS 同构），连续圆角卡用顶/中/底三形同色分段拼回，视觉不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserWalletScreen(
    onBack: () -> Unit,
    onOpenGiftShop: () -> Unit,
    onOpenRedeemCode: () -> Unit,
    viewModel: UserWalletViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(WalletLedger.Filter.ALL) }
    val nowMillis = remember(s.transactions) { System.currentTimeMillis() }

    // P1-40 浏览即清：进「我的钱包」清全部角色钱包卡的「新变动」高亮。
    LaunchedEffect(Unit) { viewModel.markAllWalletNewsViewed() }

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.wallet_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    AppButton(onClick = onOpenGiftShop, style = AppButtonStyle.Text) {
                        Icon(Icons.Filled.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.wallet_gift_shop))
                    }
                },
            )
        },
    ) { padding ->
        val filtered = remember(s.transactions, filter) { WalletLedger.applyFilter(s.transactions, filter) }
        // contentPadding 顶 16/底 36 = 原 Spacer(4/24) 与 spacedBy(12) 的精确合成。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 36.dp),
        ) {
            item(key = "hero", contentType = "hero") {
                BalanceCard(
                    balance = s.balance,
                    monthlyEarn = s.monthlyEarn,
                    monthlySpend = s.monthlySpend,
                    balanceLoaded = s.loaded,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            item(key = "redeem", contentType = "banner") {
                RedeemCodeBanner(onClick = onOpenRedeemCode, modifier = Modifier.padding(bottom = 12.dp))
            }
            item(key = "ledger_header", contentType = "ledger_header") {
                LedgerHeaderSegment(
                    totalCount = s.transactions.size,
                    filter = filter,
                    onFilterChange = { filter = it },
                )
            }
            if (filtered.isEmpty()) {
                item(key = "ledger_empty", contentType = "ledger_empty") {
                    LedgerSegment(CardSegment.Bottom) {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                            EmptyLedger(filter)
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filtered,
                    key = { _, tx -> tx.uuid },
                    contentType = { _, _ -> "tx_row" },
                ) { idx, tx ->
                    val isLast = idx == filtered.lastIndex
                    LedgerSegment(if (isLast) CardSegment.Bottom else CardSegment.Middle) {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = if (isLast) 20.dp else 0.dp)) {
                            LedgerRow(tx, nowMillis)
                            if (!isLast) {
                                // start 52 相对卡内 20 缩进 = 原卡内 divider 同位（屏侧 16+20+52）。
                                AppListDivider(modifier = Modifier.padding(start = 52.dp), startInset = 0.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 账本卡分段承托：appCardSegmentSurface 拼回单张连续圆角 16 卡（J1 纯 raised 底·发丝线/软影同族·段接缝无缝）。 */
@Composable
private fun LedgerSegment(
    segment: CardSegment,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .appCardSegmentSurface(segment),
        content = content,
    )
}

/** 账本头段：标题 + 全量笔数（对齐 iOS 计数用未筛选全量）+ 筛选分段钮；底 12dp = 原 spacedBy 到列表的间距。 */
@Composable
private fun LedgerHeaderSegment(
    totalCount: Int,
    filter: WalletLedger.Filter,
    onFilterChange: (WalletLedger.Filter) -> Unit,
) {
    LedgerSegment(CardSegment.Top) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.wallet_ledger), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (totalCount > 0) {
                    Text(
                        stringResource(R.string.wallet_ledger_count, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AppSegmentedControl(
                options = WalletLedger.Filter.entries,
                selected = filter,
                onSelect = { onFilterChange(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { stringResource(filterLabel(it)) },
            )
        }
    }
}

@Composable
private fun BalanceCard(balance: Int, monthlyEarn: Int, monthlySpend: Int, balanceLoaded: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .appCardSurface(raised = true)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 无障碍（14.7e）：余额标签/数字/单位散成 3 个停。clearAndSetSemantics 合成一句「我的金币 N 金币」。
            val balanceDesc = stringResource(R.string.wallet_my_coins) + " " +
                "%,d".format(balance) + " " + stringResource(R.string.wallet_coins_unit)
            Column(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = balanceDesc },
            ) {
                Text(
                    stringResource(R.string.wallet_my_coins),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    // 数字滚动（P1-12）：iOS hero 故意静态，安卓全动=已登记超越。
                    // animateChanges 门控（复核修 LOW#2）：占位 100→真值不滚，只滚已加载后的真实变更。
                    AnimatedCoinText(
                        balance,
                        style = LocalTextStyle.current.copy(fontSize = 42.sp, fontWeight = FontWeight.Bold),
                        animateChanges = balanceLoaded,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.wallet_coins_unit),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            WalletIcon()
        }
        AppListDivider(startInset = 0.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthlyStatColumn(stringResource(R.string.wallet_month_income), monthlyEarn, isEarn = true, Modifier.weight(1f))
            // 竖分隔：AppListDivider 只做横线，竖线按 §4.12 就地画（0.5dp × 原高 30dp × surface.stroke）。
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .width(0.5.dp)
                    .height(30.dp)
                    .background(AppTheme.colors.surface.stroke),
            )
            MonthlyStatColumn(stringResource(R.string.wallet_month_expense), monthlySpend, isEarn = false, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RedeemCodeBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(Color(0xFFC8A878).copy(alpha = 0.18f), CircleShape),
        ) {
            Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, tint = Color(0xFFC8A878), modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.redeem_entry_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.redeem_entry_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WalletIcon() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(
                brush = Brush.linearGradient(listOf(Color(0xFFF5D38F), Color(0xFFC9892F))),
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        Icon(Icons.Filled.CreditCard, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MonthlyStatColumn(title: String, amount: Int, isEarn: Boolean, modifier: Modifier = Modifier) {
    val color = if (isEarn) LedgerEarn else MaterialTheme.colorScheme.onSurface
    // 无障碍（14.7e）：标题(本月收入/支出)已含方向，+/- 号对 TalkBack 读成「加/减」噪音；合成一句「本月收入 N 金币」。
    val statDesc = "$title ${"%,d".format(amount)} ${stringResource(R.string.wallet_coins_unit)}"
    Column(modifier.clearAndSetSemantics { contentDescription = statDesc }) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(
            (if (isEarn) "+" else "-") + "%,d".format(amount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun EmptyLedger(filter: WalletLedger.Filter) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Text(
            stringResource(if (filter == WalletLedger.Filter.ALL) R.string.wallet_ledger_empty else R.string.wallet_ledger_empty_filtered, stringResource(filterLabel(filter))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filter == WalletLedger.Filter.ALL) {
            Text(
                stringResource(R.string.wallet_ledger_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun LedgerRow(tx: CurrencyTransactionEntity, nowMillis: Long) {
    val category = CurrencyTransactionCategory.fromRaw(tx.categoryRaw)
    val isEarn = CurrencyTransactionKind.fromRaw(tx.kindRaw) == CurrencyTransactionKind.EARN
    val note = tx.note.ifEmpty { category.displayName }
    // 无障碍（14.7e）：图标装饰 + 备注/日期/带 +/- 金额散读、符号读成「加/减」。合成一句「{备注}，收入/支出 N 金币，{日期}」。
    val rowDesc = "$note，${if (isEarn) "收入" else "支出"} ${"%,d".format(tx.amount)} " +
        "${stringResource(R.string.wallet_coins_unit)}，${WalletLedger.dateLabel(tx.timestamp, nowMillis)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = rowDesc },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(note, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                WalletLedger.dateLabel(tx.timestamp, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (isEarn) "+" else "-") + "%,d".format(tx.amount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isEarn) LedgerEarn else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CategoryIcon(category: CurrencyTransactionCategory) {
    val (icon, color) = categoryIconStyle(category)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.15f), CircleShape),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

/** 分类图标 + 柔和色（1:1 iOS categoryIconStyle 的 12 类映射，SF Symbol → 最接近的 Material 图标）。 */
private fun categoryIconStyle(category: CurrencyTransactionCategory): Pair<ImageVector, Color> = when (category) {
    CurrencyTransactionCategory.PET_WALK -> Icons.Filled.Pets to Color(0xFF7AB18A)
    CurrencyTransactionCategory.PET_SOUVENIR_SALE -> Icons.Filled.Inventory2 to Color(0xFFB59F7F)
    CurrencyTransactionCategory.PET_SHOP -> Icons.Filled.ShoppingCart to Color(0xFFE8A979)
    CurrencyTransactionCategory.MILESTONE -> Icons.Filled.EmojiEvents to Color(0xFFD6A84A)
    CurrencyTransactionCategory.RED_PACKET -> Icons.Filled.Mail to Color(0xFFD4605D)
    CurrencyTransactionCategory.SALARY -> Icons.Filled.Payments to Color(0xFF6EA7C7)
    CurrencyTransactionCategory.GIFT -> Icons.Filled.CardGiftcard to Color(0xFFC05A3A)
    CurrencyTransactionCategory.INITIAL -> Icons.Filled.AutoAwesome to Color(0xFF9B7ABF)
    CurrencyTransactionCategory.UNEXPECTED_INCOME -> Icons.Filled.ArrowCircleDown to Color(0xFF7AB18A)
    CurrencyTransactionCategory.UNEXPECTED_EXPENSE -> Icons.Filled.ArrowCircleUp to Color(0xFFD4605D)
    CurrencyTransactionCategory.REDEEM_CODE -> Icons.Filled.ConfirmationNumber to Color(0xFFC8A878)
    // W7 旅行购票（图纸未定图标/配色·此为占位默认待 UI 过审·§11 已登记）：罗盘=世界/远行。
    CurrencyTransactionCategory.WORLD_TRAVEL -> Icons.Filled.Explore to Color(0xFF5AA6A0)
    CurrencyTransactionCategory.OTHER -> Icons.Filled.MoreHoriz to Color(0xFF9E9E9E)
}

private fun filterLabel(filter: WalletLedger.Filter): Int = when (filter) {
    WalletLedger.Filter.ALL -> R.string.wallet_filter_all
    WalletLedger.Filter.EARN -> R.string.wallet_filter_earn
    WalletLedger.Filter.SPEND -> R.string.wallet_filter_spend
}
