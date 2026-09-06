package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.profile.CharacterWalletActivity
import com.situ.aichat.ui.components.AnimatedCoinText
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase

// 落值 1:1 暖陶：余额大字 / 「新变动」徽标底透明度 / 流水 >3 条的定高滚动窗 / 小图标尺寸。
// 进项绿：暖陶那边是文件内 private 字面量 #34C759；琉璃走语义 token `status.onSuccess`（§9 ⑤ 禁裸色）。
private val BALANCE_SIZE = 28.sp
private const val BADGE_BG_ALPHA = 0.12f
private val ACTIVITY_SCROLL_MAX = 108.dp
private val SMALL_ICON = 14.dp
private val EDIT_BUTTON = 36.dp

/**
 * 角色钱包卡「TA 的钱包」（琉璃·搬暖陶 `CharacterWalletCard`）。
 *
 * **💰只读**：余额 / 月薪 · 发薪日 / 近 7 天流水 + 本月汇总；永不隐藏。编辑圆钮只回调
 * （首次警告与真正的保存都在屏一层·钱路零碰）。
 */
@Composable
internal fun LiuliProfileWalletCard(
    characterName: String,
    wallet: CharacterWalletEntity?,
    activity: CharacterWalletActivity.Summary,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    showNewBadge: Boolean = false,
    onEdit: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_wallet_title, characterName)) {
        LiuliRowBase(
            divider = false,
            minHeight = 0.dp,
            verticalPadding = LiuliPageGeometry.groupPadH,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.profile_wallet_balance),
                            style = AppTypography.listPreview,
                            color = colors.text.secondary,
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            // 数字滚动：wallet 未加载（null→0 占位）不滚（同暖陶门控）。
                            AnimatedCoinText(
                                wallet?.coinBalance ?: 0,
                                style = AppTypography.titleLarge.copy(fontSize = BALANCE_SIZE, fontWeight = FontWeight.W700),
                                animateChanges = wallet != null,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.profile_wallet_coins),
                                style = AppTypography.listPreview,
                                color = colors.text.secondary,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    if (showNewBadge) {
                        // 「新变动」徽标：自上次浏览以来有新经济流水；本次浏览全程可见、下次进页消化。
                        Box(
                            Modifier
                                .clip(LiuliShapes.pill)
                                .background(colors.accent.primary.copy(alpha = BADGE_BG_ALPHA))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                stringResource(R.string.profile_wallet_new_badge),
                                style = AppTypography.caption.copy(fontWeight = FontWeight.W600),
                                color = colors.accent.text,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (onEdit != null) {
                        LiuliCircleButton(
                            onClick = onEdit,
                            contentDescription = stringResource(R.string.wallet_edit_title),
                            size = EDIT_BUTTON,
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Hairline()
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
                    Hairline()
                    Text(
                        stringResource(R.string.profile_wallet_recent),
                        style = AppTypography.secondary.copy(fontWeight = FontWeight.W600),
                        color = colors.text.secondary,
                    )
                    val rows: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activity.recent.forEach { ActivityRow(it, nowMillis) }
                        }
                    }
                    if (activity.recent.size > 3) {
                        Column(Modifier.heightIn(max = ACTIVITY_SCROLL_MAX).verticalScroll(rememberScrollState())) { rows() }
                    } else {
                        rows()
                    }
                    MonthlyStatsRow(activity.monthlyEarn, activity.monthlySpend)
                }
            }
        }
    }
}

@Composable
private fun WalletInfoColumn(title: String, value: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(modifier) {
        Text(title, style = AppTypography.secondary, color = colors.text.secondary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = AppTypography.bodyEmphasis, color = colors.text.primary)
    }
}

/** 月薪文案：仅「已推断 + >0」显示数字，否则一律「—」（1:1 暖陶 `walletSalaryText`）。 */
@Composable
private fun walletSalaryText(wallet: CharacterWalletEntity?): String =
    if (wallet != null && wallet.salaryInferred && wallet.monthlySalary > 0) {
        "%,d".format(wallet.monthlySalary)
    } else {
        stringResource(R.string.profile_wallet_salary_unset)
    }

/** 一条流水：备注 + 三态金额（earn 绿 + / spend 主色 − / 0 灰）+ 相对时间。 */
@Composable
private fun ActivityRow(tx: CurrencyTransactionEntity, nowMillis: Long) {
    val colors = AppTheme.colors
    val note = tx.note.ifEmpty { CurrencyTransactionCategory.fromRaw(tx.categoryRaw).displayName }
    val isEarn = CurrencyTransactionKind.fromRaw(tx.kindRaw) == CurrencyTransactionKind.EARN
    val (amountText, amountColor) = when {
        tx.amount == 0 -> "0" to colors.text.secondary
        isEarn -> "+${tx.amount}" to colors.status.onSuccess
        else -> "-${tx.amount}" to colors.text.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            note,
            style = AppTypography.listPreview,
            color = colors.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(amountText, style = AppTypography.listPreview.copy(fontWeight = FontWeight.W500), color = amountColor)
        Spacer(Modifier.width(8.dp))
        Text(
            GiftHistoryPromptService.relativeGiftTime(tx.timestamp, nowMillis),
            style = AppTypography.secondary,
            color = colors.text.secondary,
        )
    }
}

/** 本月收支汇总行。 */
@Composable
private fun MonthlyStatsRow(earn: Int, spend: Int) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint = colors.text.secondary,
            modifier = Modifier.size(SMALL_ICON),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.profile_wallet_month), style = AppTypography.secondary, color = colors.text.secondary)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.profile_wallet_month_income, earn),
            style = AppTypography.secondary.copy(fontWeight = FontWeight.W500),
            color = colors.status.onSuccess,
        )
        Spacer(Modifier.width(6.dp))
        Text("·", style = AppTypography.secondary, color = colors.text.secondary)
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.profile_wallet_month_expense, spend),
            style = AppTypography.secondary.copy(fontWeight = FontWeight.W500),
            color = colors.text.primary,
        )
    }
}
