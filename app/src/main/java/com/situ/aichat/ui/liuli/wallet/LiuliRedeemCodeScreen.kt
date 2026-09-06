package com.situ.aichat.ui.liuli.wallet

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.wallet.RedeemCodeViewModel
import com.situ.aichat.ui.wallet.errorMessage
import kotlinx.coroutines.delay

/** 输入上限（**归一化零碰**：`uppercase().take(30)`·逐字照暖陶 `MAX_INPUT`）。 */
private const val MAX_INPUT = 30
/** 进页自动对焦的延时与成功后自动关闭的延时（逐字照暖陶 300 / 1800）。 */
private const val AUTOFOCUS_DELAY_MS = 300L
private const val SUCCESS_CLOSE_DELAY_MS = 1800L
/** 金砖：56 见方 · 圆角 14 · 内图标 26（逐字照暖陶）。 */
private val GOLD_TILE = 56.dp
private val GOLD_TILE_CORNER = 14.dp
private val GOLD_ICON = 26.dp
/** 状态行 / 钮内图标尺寸与块间缝（逐字照暖陶 18 / 8 / 16 / 24 / 4）。 */
private val STATUS_ICON = 18.dp
private val INLINE_GAP = 8.dp
private val BLOCK_GAP = 16.dp
private val EDITING_PLACEHOLDER_HEIGHT = 24.dp
private val SUCCESS_LINE_GAP = 4.dp
/** 输入框的占位串（逐字照暖陶 `:161`）。 */
private const val CODE_PLACEHOLDER = "AIC-XXXXX-XXXXX-XXXXX"

/**
 * 兑换码页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 28）。💰 **只换壳**：
 * `RedeemCodeViewModel → RedeemCodeService`（验签 / 过期 / 去重 / 入账）全链零碰。
 *
 * 逐字搬：`uppercase().take(30)` 归一化 · 300ms 自动对焦（单独一次性 `LaunchedEffect(Unit)`·**勿并进
 * phase-keyed 那个**，否则每次 phase 变都重对焦）· 成功 1.8s 自动关闭 + 成功 / 失败触觉 ·
 * 四类错误映射借暖陶 [errorMessage] · 成功 / 失败两处 `liveRegion = Polite`（涉钱结果必须自动播报）·
 * `inputEnabled` / `buttonEnabled` 两条判据。
 *
 * 换脸顺手补上了暖陶留的两处 TODO：等宽居中输入现在走 [LiuliField] 的 `textStyle` 槽，
 * 钮内白转圈走 [LiuliSpinner] 的 `color` 槽（§9 ⑤ F7：金 / 绿 / 红三色改走 `economy.gold` /
 * `status.onSuccess` / `status.onError`，不再是文件私有字面量）。
 */
@Composable
fun LiuliRedeemCodeScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RedeemCodeViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val haptics = LocalAppHaptics.current
    val focusRequester = remember { FocusRequester() }

    // P0-24：进页 300ms 后自动对焦输入框弹键盘。一次性，勿并入下面 phase-keyed 那个。
    LaunchedEffect(Unit) {
        delay(AUTOFOCUS_DELAY_MS)
        runCatching { focusRequester.requestFocus() }
    }

    // 成功 → 1.8s 自动关闭；成功 / 失败触觉反馈。
    LaunchedEffect(phase) {
        when (phase) {
            is RedeemCodeViewModel.Phase.Success -> {
                haptics.success()
                delay(SUCCESS_CLOSE_DELAY_MS)
                onClose()
            }
            is RedeemCodeViewModel.Phase.Error -> haptics.error()
            else -> Unit
        }
    }

    val inputEnabled = phase is RedeemCodeViewModel.Phase.Editing || phase is RedeemCodeViewModel.Phase.Error
    val buttonEnabled = input.isNotBlank() && inputEnabled
    val isSuccess = phase is RedeemCodeViewModel.Phase.Success
    val title = stringResource(R.string.redeem_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onClose,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "body") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(GOLD_TILE)
                            .background(
                                Brush.linearGradient(
                                    listOf(colors.economy.goldGradientStart, colors.economy.goldGradientEnd),
                                ),
                                RoundedCornerShape(GOLD_TILE_CORNER),
                            ),
                    ) {
                        Icon(
                            Icons.Filled.ConfirmationNumber,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(GOLD_ICON),
                        )
                    }
                    Text(
                        stringResource(R.string.redeem_header),
                        style = AppTypography.titleSmall,
                        color = colors.text.primary,
                    )
                    Text(
                        stringResource(R.string.redeem_format),
                        style = AppTypography.secondary.copy(fontFamily = FontFamily.Monospace),
                        color = colors.text.secondary,
                    )
                    LiuliField(
                        value = input,
                        onValueChange = {
                            // 归一化零碰：大写 + 截 30。
                            input = it.uppercase().take(MAX_INPUT)
                            viewModel.onInputChanged()
                        },
                        placeholder = CODE_PLACEHOLDER,
                        // 等宽 + 居中（暖陶留的 TODO 在这里落地：kit 现在有 textStyle 槽）。
                        textStyle = AppTypography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                        ),
                        // P0-25：键盘直出 ASCII 大写 + 回车（Go）即兑换。
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { viewModel.redeem(input) }),
                        // 兑换中 / 成功后不许再改（暖陶 `enabled = inputEnabled`·复核 R1 🔴 A-1）。
                        enabled = inputEnabled,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                    LiuliRedeemStatusRow(phase)
                    LiuliButton(
                        onClick = { viewModel.redeem(input) },
                        style = LiuliButtonStyle.Prominent,
                        enabled = buttonEnabled || isSuccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when (phase) {
                            is RedeemCodeViewModel.Phase.Redeeming ->
                                // 实心钮里的白转圈（暖陶留的 TODO 在这里落地：LiuliSpinner 有 color 槽）。
                                LiuliSpinner(size = STATUS_ICON, color = colors.accent.onPrimary)
                            is RedeemCodeViewModel.Phase.Success -> {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(STATUS_ICON),
                                )
                                Text(stringResource(R.string.redeem_done))
                            }
                            else -> {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(STATUS_ICON),
                                )
                                Text(stringResource(R.string.redeem_action))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 状态行四态（逐字照暖陶 `StatusRow`）。**两处 `liveRegion = Polite` 必留**：兑换结果涉钱，
 * TalkBack 要在兑换完自动念出来，且 `mergeDescendants` 合成一句（图标 cd = null 是装饰）。
 */
@Composable
private fun LiuliRedeemStatusRow(phase: RedeemCodeViewModel.Phase) {
    val colors = AppTheme.colors
    when (phase) {
        is RedeemCodeViewModel.Phase.Editing -> Spacer(Modifier.height(EDITING_PLACEHOLDER_HEIGHT))
        is RedeemCodeViewModel.Phase.Redeeming -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(INLINE_GAP),
        ) {
            LiuliSpinner()
            Text(
                stringResource(R.string.redeem_redeeming),
                style = AppTypography.listPreview,
                color = colors.text.secondary,
            )
        }
        is RedeemCodeViewModel.Phase.Error -> Row(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(INLINE_GAP),
        ) {
            Icon(
                Icons.Filled.Error,
                contentDescription = null,
                tint = colors.status.onError,
                modifier = Modifier.size(STATUS_ICON),
            )
            Text(
                stringResource(errorMessage(phase.error)),
                style = AppTypography.listPreview.copy(fontWeight = FontWeight.Medium),
                color = colors.status.onError,
            )
        }
        is RedeemCodeViewModel.Phase.Success -> Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SUCCESS_LINE_GAP),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(INLINE_GAP)) {
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = colors.status.onSuccess,
                    modifier = Modifier.size(STATUS_ICON),
                )
                Text(
                    stringResource(R.string.redeem_success, phase.coinsAdded),
                    style = AppTypography.bodyEmphasis,
                    color = colors.text.primary,
                )
            }
            Text(
                stringResource(R.string.redeem_balance, phase.newBalance),
                style = AppTypography.secondary,
                color = colors.text.secondary,
            )
        }
    }
}
