package com.situ.aichat.ui.gift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.ui.designsystem.AppSheet
import kotlinx.coroutines.launch

/**
 * 礼物详情底片（9.2d d-2，1:1 iOS `GiftDetailSheet`，含送礼流程）。
 *
 * 220 大图 + 名/副标题 + 标签行（精选徽章 + 情感标签）+ 价格卡（价 + 我的余额）+ 送出按钮。送出 → [onSpend]
 * （扣币建 record）→ Success 关闭底片并 [onSent]（导航反应页）；余额不足 / 扣款失败显示提示文案。
 *
 * @param character 当前送礼对象（null=未选，按钮禁用并提示先选对象）。
 * @param onSpend 第一步扣币建 record（[GiftShopViewModel.spend]）。
 * @param onSent 送出成功回调（带 recordUuid，跳反应页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftDetailSheet(
    item: GiftItem,
    balance: Int,
    character: CharacterEntity?,
    onDismiss: () -> Unit,
    onSpend: suspend (GiftItem) -> GiftSendService.ShopSpendOutcome,
    onSent: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val canAfford = balance >= item.price
    val canSend = character != null && canAfford && !isSending

    AppSheet(onDismissRequest = { if (!isSending) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GiftImage(item = item, size = 220.dp, cornerRadius = 22.dp)

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            TagsRow(item)
            PriceCard(item = item, balance = balance, canAfford = canAfford)

            // 送出按钮 + 提示
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton(
                    onClick = {
                        if (!canSend) return@AppButton
                        scope.launch {
                            isSending = true
                            errorText = null
                            when (val outcome = onSpend(item)) {
                                is GiftSendService.ShopSpendOutcome.Success -> onSent(outcome.record.uuid) // 保持 isSending，等导航
                                is GiftSendService.ShopSpendOutcome.InsufficientCoins -> {
                                    errorText = "余额不足，还差 ${outcome.need - outcome.have} 金币"
                                    isSending = false
                                }
                                GiftSendService.ShopSpendOutcome.SpendFailed -> {
                                    errorText = "扣款失败，请稍后重试"
                                    isSending = false
                                }
                            }
                        }
                    },
                    style = AppButtonStyle.Primary,
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppActionIcons.Gift, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = sendButtonTitle(item, character, canAfford),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val hint = when {
                    errorText != null -> errorText
                    character == null -> "请先在礼物店首页选择送礼对象" // gift-4：文案 1:1 iOS GiftShopView
                    !canAfford -> "余额不足，还差 ${item.price - balance} 金币"
                    else -> null
                }
                if (hint != null) {
                    // gift-4：无送礼对象提示用中性灰（1:1 iOS .tertiary）；仅余额不足 / 运行时错误才暗红。
                    val hintColor = if (character == null && errorText == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        GiftColors.Unafford
                    }
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = hintColor)
                }
            }
        }
    }
}

/** 标签行：精选徽章（signature）+ 情感标签（1:1 iOS tagsRow）。 */
@Composable
private fun TagsRow(item: GiftItem) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.isSignature) {
            Surface(shape = RoundedCornerShape(50), color = GiftColors.Gold.copy(alpha = 0.18f)) {
                Text(
                    "精选",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = GiftColors.Gold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        item.emotionalTags.forEach { tag ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    tag.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
    }
}

/** 价格卡：价格 + 我的余额（1:1 iOS priceCard，不足暗红）。 */
@Composable
private fun PriceCard(item: GiftItem, balance: Int, canAfford: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("价格", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(20.dp))
                    Text(
                        " ${item.price}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (canAfford) MaterialTheme.colorScheme.onSurface else GiftColors.Unafford,
                    )
                    Text(" 金币", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("我的余额", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "$balance 金币",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (canAfford) MaterialTheme.colorScheme.onSurfaceVariant else GiftColors.Unafford,
                )
            }
        }
    }
}

/** 送出按钮文案（1:1 iOS sendButtonTitle）。 */
private fun sendButtonTitle(item: GiftItem, character: CharacterEntity?, canAfford: Boolean): String = when {
    !canAfford -> "余额不足"
    !character?.name.isNullOrEmpty() -> "送给 ${character.name}"
    else -> "送出这份礼物"
}
