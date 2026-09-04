package com.situ.aichat.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import com.situ.aichat.data.model.GiftImpressionTag
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.profile.CharacterWalletActivity
import com.situ.aichat.ui.components.AnimatedCoinText
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.gift.GiftSymbolMapping
import com.situ.aichat.ui.offline.MeetingSkyHeroCard
import com.situ.aichat.ui.offline.MeetingSkyMiniThumb

// 1:1 iOS 色值：礼物图标金色 / 收入绿（钱包流水+本月汇总）。
private val GiftGold = Color(0xFFC9892F)
private val IncomeGreen = Color(0xFF34C759)

/**
 * 资料页卡片统一容器（appCardSurface 承托·设计语言 v2；原 18dp surfaceContainer 卡 2026-07-12 并轨 16）。
 * [onClick] 非 null 时整卡可点：clickable 落在卡壳之后（ripple 由收尾 clip 裁圆角·J4），[onClickLabel] 供 a11y。
 */
@Composable
internal fun ProfileCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .appCardSurface()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}

@Composable
internal fun CardSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    }
}

// ── 亲友账卡「TA 眼里的你」（印象标签 + 收到的礼物 + 件数；空则整卡不渲染）──────────────────────

@Composable
internal fun RelationshipAccountCard(
    characterName: String,
    tags: List<GiftImpressionTag>,
    gifts: List<GiftRecordEntity>,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty() && gifts.isEmpty()) return
    ProfileCard(modifier) {
        CardSectionHeader(Icons.Filled.Favorite, MaterialTheme.colorScheme.primary, stringResource(R.string.profile_account_title, characterName))

        if (tags.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag -> ThemePill(tag.label) }
            }
        }

        if (gifts.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            AppListDivider(startInset = 0.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.profile_account_gifts_subtitle, characterName),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            // >3 件套定高滚动（3 行可视，1:1 iOS 108pt），≤3 直接展开。
            val giftList: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gifts.forEach { GiftRow(it, nowMillis) }
                }
            }
            if (gifts.size > 3) {
                Column(Modifier.heightIn(max = 108.dp).verticalScroll(rememberScrollState())) { giftList() }
            } else {
                giftList()
            }

            Spacer(Modifier.size(12.dp))
            AppListDivider(startInset = 0.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.profile_account_gift_count, characterName, gifts.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GiftRow(record: GiftRecordEntity, nowMillis: Long) {
    val item = GiftCatalog.find(record.giftItemId)
    val name = item?.name ?: if (record.isDIY) {
        record.diyTitle.ifEmpty { stringResource(R.string.profile_gift_handmade_default) }
    } else {
        stringResource(R.string.profile_gift_default)
    }
    val handmade = record.isDIY || item?.isHandmade == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            GiftSymbolMapping.materialIcon(item?.fallbackSymbol ?: "gift.fill"),
            contentDescription = null,
            tint = GiftGold,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (handmade) {
            Spacer(Modifier.width(6.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.error) {
                Text(
                    stringResource(R.string.profile_gift_handmade_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            GiftHistoryPromptService.relativeGiftTime(record.timestamp, nowMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 角色钱包卡「TA 的钱包」（💰只读：余额/月薪·发薪日/近 7 天流水+本月汇总；永不隐藏）──────────────

@Composable
internal fun CharacterWalletCard(
    characterName: String,
    wallet: CharacterWalletEntity?,
    activity: CharacterWalletActivity.Summary,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    showNewBadge: Boolean = false,
    onEdit: (() -> Unit)? = null,
) {
    ProfileCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                CardSectionHeader(Icons.Filled.CreditCard, GiftGold, stringResource(R.string.profile_wallet_title, characterName))
                if (showNewBadge) {
                    Spacer(Modifier.width(6.dp))
                    // 「新变动」徽标（P1-40）：自上次浏览以来有新经济流水；本次浏览全程可见、下次进页消化。
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(
                            stringResource(R.string.profile_wallet_new_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.wallet_edit_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 余额 hero
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.profile_wallet_balance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            // 数字滚动（P1-12）：iOS 此处仅声明 numericText 无驱动，安卓显式驱动=已登记超越。
            // wallet 未加载（null→0 占位）不滚（复核修 LOW#2 同构门控）。
            AnimatedCoinText(
                wallet?.coinBalance ?: 0,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                animateChanges = wallet != null,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.profile_wallet_coins),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Spacer(Modifier.size(12.dp))
        AppListDivider(startInset = 0.dp)
        Spacer(Modifier.size(12.dp))

        // 月薪 / 发薪日
        Row {
            WalletInfoColumn(
                title = stringResource(R.string.profile_wallet_salary),
                value = walletSalaryText(wallet),
                modifier = Modifier.weight(1f),
            )
            WalletInfoColumn(
                title = stringResource(R.string.profile_wallet_payday),
                value = stringResource(R.string.profile_wallet_payday_value, wallet?.salaryDay ?: 15),
                modifier = Modifier.weight(1f),
            )
        }

        if (activity.recent.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            AppListDivider(startInset = 0.dp)
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.profile_wallet_recent), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            val rows: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activity.recent.forEach { ActivityRow(it, nowMillis) }
                }
            }
            if (activity.recent.size > 3) {
                Column(Modifier.heightIn(max = 108.dp).verticalScroll(rememberScrollState())) { rows() }
            } else {
                rows()
            }
            Spacer(Modifier.size(8.dp))
            MonthlyStatsRow(activity.monthlyEarn, activity.monthlySpend)
        }
    }
}

@Composable
private fun WalletInfoColumn(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(2.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/** 月薪文案：仅「已推断 + >0」显示数字，否则一律「—」（1:1 iOS monthlySalaryText）。 */
@Composable
private fun walletSalaryText(wallet: CharacterWalletEntity?): String =
    if (wallet != null && wallet.salaryInferred && wallet.monthlySalary > 0) {
        "%,d".format(wallet.monthlySalary)
    } else {
        stringResource(R.string.profile_wallet_salary_unset)
    }

@Composable
private fun ActivityRow(tx: CurrencyTransactionEntity, nowMillis: Long) {
    val note = tx.note.ifEmpty { CurrencyTransactionCategory.fromRaw(tx.categoryRaw).displayName }
    val isEarn = CurrencyTransactionKind.fromRaw(tx.kindRaw) == CurrencyTransactionKind.EARN
    // 三态金额：earn 绿(+) / spend 主色(-) / 0 灰（欠租 0 元流水）。
    val (amountText, amountColor) = when {
        tx.amount == 0 -> "0" to MaterialTheme.colorScheme.onSurfaceVariant
        isEarn -> "+${tx.amount}" to IncomeGreen
        else -> "-${tx.amount}" to MaterialTheme.colorScheme.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(amountText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = amountColor)
        Spacer(Modifier.width(8.dp))
        Text(
            GiftHistoryPromptService.relativeGiftTime(tx.timestamp, nowMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonthlyStatsRow(earn: Int, spend: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.profile_wallet_month), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.profile_wallet_month_income, earn), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = IncomeGreen)
        Spacer(Modifier.width(6.dp))
        Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.profile_wallet_month_expense, spend), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ── 见面回忆卡「那晚的天色」（SKY-3·契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL §1）─────────────────
// 最新一场 = 全宽窗景卡；更早 = 横向小天窗胶卷（右缘露半张）；HorizontalPager/圆点/页脚按钮已退役，
// 「查看全部」唯一入口 = 卡头「全部 N 次 ›」（≥1 场即显）。始终渲染，空态文案不变。

@Composable
internal fun OfflineMeetingMemorySection(
    sessions: List<OfflineMeetingSession>,
    onOpenAll: () -> Unit,
    onRetryFallback: (String) -> Unit,
    modifier: Modifier = Modifier,
    retryingSessionIds: Set<String> = emptySet(),
) {
    ProfileCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.profile_meeting_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (sessions.isNotEmpty()) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenAll).padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.profile_meeting_all_count, sessions.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (sessions.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.profile_meeting_empty_1), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.profile_meeting_empty_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        } else {
            Spacer(Modifier.height(12.dp))
            // extractor 已按日期倒序输出；此处显式钉死 newest-first（防上游变化静默倒挂）。
            val sorted = remember(sessions) { sessions.sortedByDescending { it.startMillis } }
            val hero = sorted.first()
            MeetingSkyHeroCard(
                session = hero,
                sequenceNumber = sorted.size,
                onRetryFallback = onRetryFallback,
                onClick = onOpenAll,
                isRetrying = hero.id in retryingSessionIds,
            )
            if (sorted.size > 1) {
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 24.dp),
                ) {
                    items(sorted.drop(1), key = { it.id }) { s ->
                        MeetingSkyMiniThumb(session = s, onClick = onOpenAll)
                    }
                }
            }
        }
    }
}

/** 印象标签胶囊（对齐 iOS ThemePill .tint 样式）。 */
@Composable
private fun ThemePill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
