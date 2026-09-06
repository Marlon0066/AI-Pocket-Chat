package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.RedPacketAmountCatalog
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.redpacket.RedPacketSendOutcome
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import kotlinx.coroutines.launch

/**
 * 琉璃版发红包底片（图纸 2026-09-05 卷二C C6b · A-16 · 照抄源 F25
 * `ui/redpacket/RedPacketComposerSheet.kt:60-192`）。
 *
 * **只换渲染皮·钱路零碰**（§6「C6 钱路」）：`rememberSaveable` 三字段、今日节日一次预填、金额过滤
 * （只留数字取 5 位、超上限钳 [RedPacketAmountCatalog].MAX_AMOUNT）、`canSend` 三条件门、
 * `submit()` 的三条错误文案、主钮两版文案，全部逐字照抄。扣款 / 插消息仍全在 [onSend]（VM）里。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LiuliRedPacketComposerSheet(
    characterName: String,
    balance: Int,
    onSend: suspend (amount: Int, blessing: String, festivalId: String?) -> RedPacketSendOutcome,
    onDismiss: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val todayFestival = remember(now) { FestivalCalendar.festivalsMatching(now).firstOrNull() }
    var amountText by rememberSaveable { mutableStateOf("") }
    var blessing by rememberSaveable { mutableStateOf("") }
    var selectedFestivalId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

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

    LiuliSheetShell(
        onDismissRequest = { if (!isSending) onDismiss() },
        sheetState = sheetState,
        title = "发给 $characterName",
        subtitle = "红包是心意,金额不强求",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            todayFestival?.let { fest ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🎉 今天是", style = AppTypography.snackbarBody, color = onGlass.secondary)
                    LiuliChip(
                        selected = selectedFestivalId == fest.id,
                        onClick = { selectedFestivalId = if (selectedFestivalId == fest.id) null else fest.id },
                        label = fest.name,
                        role = Role.Checkbox,
                    )
                }
            }

            Text("金额", style = AppTypography.label, color = onGlass.primary)
            LiuliField(
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
                big = true,
            )
            LiuliAmountTierGroup("小心意", RedPacketAmountCatalog.SMALL_AMOUNTS, amount) { selectAmount(it) }
            LiuliAmountTierGroup("用心的选择", RedPacketAmountCatalog.MEDIUM_AMOUNTS, amount) { selectAmount(it) }
            LiuliAmountTierGroup("珍贵的心意", RedPacketAmountCatalog.PRECIOUS_AMOUNTS, amount) { selectAmount(it) }

            Text("祝福语(选填)", style = AppTypography.label, color = onGlass.primary)
            LiuliField(
                value = blessing,
                onValueChange = { if (it.length <= BLESSING_MAX) blessing = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "写一句心里话",
            )

            errorText?.let { Text(it, style = AppTypography.snackbarBody, color = colors.status.onError) }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(16.dp))
                Text("钱包余额 $balance 金币", style = AppTypography.snackbarBody, color = onGlass.secondary)
            }
            LiuliButton(
                onClick = { submit() },
                style = LiuliButtonStyle.Prominent,
                enabled = canSend,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(amount?.let { "🧧 塞 $it 金币进红包" } ?: "🧧 先选金额") }
        }
    }
}

/** 祝福语上限（照抄暖陶 `if (it.length <= 80)`）。 */
private const val BLESSING_MAX = 80

/** 一档吉利数 chip 组（标题 + 自动换行 chip 行·照抄暖陶 `AmountTierGroup`）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiuliAmountTierGroup(title: String, amounts: List<Int>, selected: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = AppTypography.caption, color = LiuliTheme.onGlass.secondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            amounts.forEach { value ->
                LiuliChip(selected = selected == value, onClick = { onSelect(value) }, label = "$value")
            }
        }
    }
}
