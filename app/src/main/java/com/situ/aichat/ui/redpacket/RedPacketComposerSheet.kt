package com.situ.aichat.ui.redpacket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.RedPacketAmountCatalog
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.redpacket.RedPacketSendOutcome
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.gift.GiftColors
import kotlinx.coroutines.launch

/**
 * 聊天内发红包底片（1:1 iOS `RedPacketComposerView` 行为，Material 3 原生 ModalBottomSheet）。
 *
 * Header（发给谁）+ 节日 chip（今日命中节日自动预填，可 toggle 回日常）+ 金额（数字输入 clamp 20000 + 三档吉利数 chip）+
 * 祝福（≤80）+ 底部余额 + 发送按钮。校验金额范围 + 余额；成功 dismiss，余额不足/失败显错。不扣款/不插消息——全由 [onSend]（VM）完成。
 *
 * @param now 用于节日自动检测（默认当前时间）。
 * @param onSend 发送回调（suspend；VM.sendRedPacketInChat → sendFromUser + 异步触发 AI 收/拒决策）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RedPacketComposerSheet(
    characterName: String,
    balance: Int,
    onSend: suspend (amount: Int, blessing: String, festivalId: String?) -> RedPacketSendOutcome,
    onDismiss: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val todayFestival = remember(now) { FestivalCalendar.festivalsMatching(now).firstOrNull() }
    // E1#0：用户输入升 rememberSaveable——转屏/进程死亡后随宿主标志重开时金额/祝福/节日选择不丢；
    // isSending/errorText 是瞬态流程位保持 remember（恢复后回到「未发送」可重试，安全方向）。
    var amountText by rememberSaveable { mutableStateOf("") }
    var blessing by rememberSaveable { mutableStateOf("") }
    var selectedFestivalId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 今日命中节日 → 自动预填（E2，可点 chip 切回 nil）
    LaunchedEffect(todayFestival?.id) {
        if (selectedFestivalId == null) selectedFestivalId = todayFestival?.id
    }

    val amount = amountText.toIntOrNull()
    val isAmountValid = amount != null && RedPacketAmountCatalog.isValidAmount(amount)
    val canAfford = amount != null && balance >= amount
    val canSend = isAmountValid && canAfford && !isSending

    fun selectAmount(value: Int) {
        amountText = value.toString()
        errorText = null
    }

    fun submit() {
        val amt = amount ?: return
        if (isSending) return
        if (!RedPacketAmountCatalog.isValidAmount(amt)) { errorText = "金额需要在 1 - 20000 之间"; return }
        if (balance < amt) { errorText = "钱包余额不足"; return }
        scope.launch {
            isSending = true
            errorText = null
            when (val outcome = onSend(amt, blessing.trim(), selectedFestivalId)) {
                is RedPacketSendOutcome.Success -> onDismiss()
                is RedPacketSendOutcome.InsufficientBalance -> { errorText = "钱包余额不足"; isSending = false }
                is RedPacketSendOutcome.Failed -> { errorText = outcome.message; isSending = false }
            }
        }
    }

    AppSheet(onDismissRequest = { if (!isSending) onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("发给 $characterName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("红包是心意,金额不强求", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 节日 chip
            todayFestival?.let { fest ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🎉 今天是", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppChoiceChip(
                        selected = selectedFestivalId == fest.id,
                        onClick = { selectedFestivalId = if (selectedFestivalId == fest.id) null else fest.id },
                        label = fest.name,
                        role = Role.Checkbox,
                    )
                }
            }

            // 金额
            Text("金额", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            AppTextField(
                value = amountText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(5)
                    val v = digits.toIntOrNull()
                    amountText = when {
                        v == null -> ""
                        v > RedPacketAmountCatalog.MAX_AMOUNT -> RedPacketAmountCatalog.MAX_AMOUNT.toString()
                        else -> digits
                    }
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth(),
                prefix = "¥",
                placeholder = "随手心意",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            AmountTierGroup("小心意", RedPacketAmountCatalog.SMALL_AMOUNTS, amount) { selectAmount(it) }
            AmountTierGroup("用心的选择", RedPacketAmountCatalog.MEDIUM_AMOUNTS, amount) { selectAmount(it) }
            AmountTierGroup("珍贵的心意", RedPacketAmountCatalog.PRECIOUS_AMOUNTS, amount) { selectAmount(it) }

            // 祝福
            Text("祝福语(选填)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            AppTextField(
                value = blessing,
                onValueChange = { if (it.length <= 80) blessing = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "写一句心里话",
            )

            errorText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GiftColors.Unafford) }

            // 余额 + 发送
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
                Text("钱包余额 $balance 金币", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppButton(onClick = { submit() }, style = AppButtonStyle.Primary, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
                Text(amount?.let { "🧧 塞 $it 金币进红包" } ?: "🧧 先选金额", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 一档吉利数 chip 组（标题 + 自动换行 chip 行）。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AmountTierGroup(title: String, amounts: List<Int>, selected: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            amounts.forEach { value ->
                AppChoiceChip(selected = selected == value, onClick = { onSelect(value) }, label = "$value")
            }
        }
    }
}
