package com.situ.aichat.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlinx.coroutines.delay
import com.situ.aichat.R
import com.situ.aichat.economy.redeem.RedeemCodeService

private val GoldStart = Color(0xFFF5D38F)
private val GoldEnd = Color(0xFFC9892F)
private val OkGreen = Color(0xFF7AB18A)
private val ErrRed = Color(0xFFD4605D)
private const val MAX_INPUT = 30

/**
 * 输入兑换码屏（14.6c-2·💰涉钱写·1:1 iOS RedeemCodeSheet）：自动大写居中等宽输入 + 4 类错误红字 + 成功到账态
 * 1.8s 自动关闭。兑换走 [RedeemCodeViewModel] → [RedeemCodeService]（验签/过期/去重/入账）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedeemCodeScreen(
    onClose: () -> Unit,
    viewModel: RedeemCodeViewModel = hiltViewModel(),
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val haptics = LocalAppHaptics.current
    val focusRequester = remember { FocusRequester() }

    // P0-24：进页 300ms 后自动对焦输入框弹键盘（1:1 iOS RedeemCodeSheet .task sleep 300ms→isInputFocused）。
    // 单独 LaunchedEffect(Unit) 一次性，勿并入下面 phase-keyed 那个（会随 phase 变化反复重对焦）。
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { focusRequester.requestFocus() }
    }

    // 成功 → 1.8s 自动关闭（1:1 iOS）；成功/失败触觉反馈。
    LaunchedEffect(phase) {
        when (phase) {
            is RedeemCodeViewModel.Phase.Success -> {
                haptics.success()
                kotlinx.coroutines.delay(1800)
                onClose()
            }
            is RedeemCodeViewModel.Phase.Error -> haptics.error()
            else -> {}
        }
    }

    val inputEnabled = phase is RedeemCodeViewModel.Phase.Editing || phase is RedeemCodeViewModel.Phase.Error
    val buttonEnabled = input.isNotBlank() && inputEnabled

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.redeem_title),
                onBack = onClose,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 顶部说明
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .background(Brush.linearGradient(listOf(GoldStart, GoldEnd)), RoundedCornerShape(14.dp)),
            ) {
                Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Text(stringResource(R.string.redeem_header), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.redeem_format),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 输入框（自动大写·居中等宽）
            // TODO(图纸未覆盖): 兑换码输入框靠 `textStyle`（等宽 + 居中）把 AIC-XXXXX-XXXXX-XXXXX 排整齐，
            //  placeholder 也是同样式的 composable；AppTextField 的 placeholder 只收 String、**没有 textStyle 槽**，
            //  §9 又禁止给组件加参数硬套 → 停手登记（施工日志 D-16）。钱路屏，K-10 只换外壳不改逻辑。
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it.uppercase().take(MAX_INPUT)
                    viewModel.onInputChanged()
                },
                enabled = inputEnabled,
                placeholder = { Text("AIC-XXXXX-XXXXX-XXXXX", fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                // P0-25：键盘直出 ASCII 大写（对齐 iOS asciiCapable+.characters）+ 回车(Go)即兑换。
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.redeem(input) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )

            // 状态行
            StatusRow(phase)

            Spacer(Modifier.height(8.dp))

            // 兑换按钮
            val isSuccess = phase is RedeemCodeViewModel.Phase.Success
            Button(
                onClick = { viewModel.redeem(input) },
                enabled = buttonEnabled || isSuccess,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSuccess) OkGreen else GoldEnd,
                    disabledContainerColor = (if (isSuccess) OkGreen else GoldEnd).copy(alpha = 0.55f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (phase) {
                    is RedeemCodeViewModel.Phase.Redeeming -> {
                        // TODO(图纸未覆盖): 同 PetShopScreen —— 实心钮里的白色转圈（钱路屏·K-10 只换外壳），
                        //  AppLoadingRing 无 color 槽 → 停手登记（D-13）。
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    is RedeemCodeViewModel.Phase.Success -> {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.redeem_done), color = Color.White)
                    }
                    else -> {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.redeem_action), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(phase: RedeemCodeViewModel.Phase) {
    when (phase) {
        is RedeemCodeViewModel.Phase.Editing -> Spacer(Modifier.height(24.dp))
        is RedeemCodeViewModel.Phase.Redeeming -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLoadingRing(size = AppLoadingRingSize.Small)
            Text(stringResource(R.string.redeem_redeeming), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 无障碍（14.7e）：兑换结果（涉钱）此前不自动播报、图标+文案散读。liveRegion=Polite 让 TalkBack 兑换完即自动念出结果，
        // mergeDescendants 合成一句（图标 cd=null 装饰、文案已含金额/余额，1:1 iOS .accessibilityElement(.combine)）。
        is RedeemCodeViewModel.Phase.Error -> Row(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Error, contentDescription = null, tint = ErrRed, modifier = Modifier.size(18.dp))
            Text(stringResource(errorMessage(phase.error)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ErrRed)
        }
        is RedeemCodeViewModel.Phase.Success -> Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = OkGreen, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.redeem_success, phase.coinsAdded), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.redeem_balance, phase.newBalance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun errorMessage(error: RedeemCodeService.RedeemError): Int = when (error) {
    RedeemCodeService.RedeemError.INVALID_FORMAT -> R.string.redeem_err_format
    RedeemCodeService.RedeemError.INVALID_CODE -> R.string.redeem_err_invalid
    RedeemCodeService.RedeemError.EXPIRED -> R.string.redeem_err_expired
    RedeemCodeService.RedeemError.ALREADY_USED -> R.string.redeem_err_used
}
