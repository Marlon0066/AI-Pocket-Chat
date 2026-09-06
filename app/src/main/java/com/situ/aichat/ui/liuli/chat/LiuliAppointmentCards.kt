package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.FutureMeetingChangeData
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 琉璃「未来约定见面」两张卡（图纸 2026-09-05 卷二C §4.7 · A-3 宽卡 280）：确认卡与变更确认卡。
 *
 * 状态机、文案、回调**逐字照抄**暖陶 `AppointmentProposalBubble` / `AppointmentChangeBubble`（F11）：
 * 确认卡的态由真理源 `MeetingStatus` 驱动（`status`=null 退消息快照兜底并收起按钮）；变更卡的态由消息快照
 * `responded` 驱动，取消分支把「保留约定」做主钮（安全默认·误触不毁已定约定）。`ui/meeting` 整目录零改。
 */
@Composable
internal fun LiuliAppointmentProposalCard(
    data: FutureMeetingProposalData,
    status: MeetingStatus?,
    characterName: String,
    onAccept: () -> Unit,
    onReschedule: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val cardState = remember(status, data.responded) { liuliResolveProposalState(status, data.responded) }
    val name = characterName.ifBlank { FALLBACK_NAME }

    LiuliCard(width = LiuliChatGeometry.cardWideWidth, modifier = modifier) {
        LiuliCardHeader(
            icon = Icons.Outlined.CalendarMonth,
            title = "${name}想和你约个时间",
            subtitle = data.whenDisplay?.takeIf { it.isNotBlank() }?.let { whenText ->
                { Text(whenText, style = AppTypography.secondary, color = colors.accent.text) }
            },
        )
        LiuliCardBody {
            data.activity?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = AppTypography.secondary, color = colors.text.primary)
            }
            data.location?.takeIf { it.isNotBlank() }?.let {
                Text("📍 $it", style = AppTypography.secondary, color = colors.text.secondary)
            }
            data.invitation?.takeIf { it.isNotBlank() }?.let {
                Text("「$it」", style = AppTypography.kaiQuote, color = colors.text.secondary)
            }
            data.tensionHint?.takeIf { it.isNotBlank() }?.let {
                Text("✨ $it", style = AppTypography.kaiQuote, color = colors.text.secondary)
            }
            when (cardState) {
                LiuliProposalCardState.PENDING -> Unit
                LiuliProposalCardState.AGREED ->
                    LiuliReceiptLine(Icons.Outlined.CheckCircle, "已约定", colors.accent.text)
                LiuliProposalCardState.DECLINED ->
                    LiuliReceiptLine(Icons.Outlined.HighlightOff, "先不约了", colors.text.secondary)
            }
        }
        if (cardState == LiuliProposalCardState.PENDING) {
            LiuliCardButtonRow {
                LiuliCardButton(text = "好呀", prominent = true, onClick = onAccept)
                LiuliCardButton(text = "换个时间", prominent = false, onClick = onReschedule)
                // 「先不约」是三级行动：不占等分槽、只给一枚文字钮（F11 的 `AppButtonStyle.Text` 同档·触达 48 不占版）。
                LiuliCardTextButton(text = "先不约", onClick = onDecline)
            }
        }
    }
}

/** 变更确认卡（改期 / 取消）。 */
@Composable
internal fun LiuliAppointmentChangeCard(
    data: FutureMeetingChangeData,
    characterName: String,
    onApply: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val name = characterName.ifBlank { FALLBACK_NAME }

    LiuliCard(width = LiuliChatGeometry.cardWideWidth, modifier = modifier) {
        LiuliCardHeader(
            icon = if (data.isCancel) Icons.Outlined.EventBusy else Icons.Outlined.EditCalendar,
            title = if (data.isCancel) "${name}想取消这次约定" else "${name}想把约定改个时间",
            subtitle = null,
        )
        LiuliCardBody {
            if (data.isReschedule) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ARROW_GAP),
                ) {
                    data.oldWhenDisplay?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = AppTypography.secondary,
                            color = colors.text.tertiary,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        Text("→", style = AppTypography.secondary, color = colors.text.tertiary)
                    }
                    data.newWhenDisplay?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = AppTypography.label, color = colors.accent.text)
                    }
                }
            } else {
                data.oldWhenDisplay?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = AppTypography.secondary, color = colors.text.secondary)
                }
            }
            listOfNotNull(
                data.activity?.takeIf { it.isNotBlank() },
                data.location?.takeIf { it.isNotBlank() },
            ).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = AppTypography.secondary, color = colors.text.secondary)
            }
            data.reason?.takeIf { it.isNotBlank() }?.let {
                Text("「$it」", style = AppTypography.kaiQuote, color = colors.text.secondary)
            }
            when (data.responded) {
                null -> Unit
                FutureMeetingChangeData.RESPONDED_APPLIED ->
                    if (data.isCancel) {
                        LiuliReceiptLine(Icons.Outlined.EventBusy, "约定已取消", colors.text.secondary)
                    } else {
                        val suffix = data.newWhenDisplay?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                        LiuliReceiptLine(Icons.Outlined.CheckCircle, "已改期$suffix", colors.accent.text)
                    }
                else -> LiuliReceiptLine(Icons.Outlined.CheckCircle, "仍按原约定", colors.text.secondary)
            }
        }
        if (data.responded == null) {
            LiuliCardButtonRow {
                if (data.isCancel) {
                    // 安全默认（F11）：保留 = 主钮，取消约定 = 弱化文字钮——误触不毁已定约定。
                    LiuliCardButton(text = "保留约定", prominent = true, onClick = onKeep)
                    LiuliCardTextButton(text = "取消约定", onClick = onApply)
                } else {
                    LiuliCardButton(text = "好，改", prominent = true, onClick = onApply)
                    LiuliCardButton(text = "还是原来的", prominent = false, onClick = onKeep)
                }
            }
        }
    }
}

/** 已响应回执行（**重打**暖陶 `ReceiptLine`·那侧是 private·两侧注释互指）：图标 14 + `secondary`（§4.7）。 */
@Composable
private fun LiuliReceiptLine(icon: ImageVector, text: String, color: Color) {
    Row(
        modifier = Modifier.padding(top = RECEIPT_TOP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RECEIPT_GAP),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(RECEIPT_ICON))
        Text(text, style = AppTypography.secondary, color = color)
    }
}

/** 确认卡视觉态（**重打**暖陶 `ProposalCardState`·那侧是 private·两侧注释互指）。 */
internal enum class LiuliProposalCardState { PENDING, AGREED, DECLINED }

/**
 * 态解析（**重打**暖陶 `resolveCardState` 同值）：proposed → 待确认；confirmed / honored / missed → 已约定；
 * cancelled → 婉拒；约定已不存在（null）→ 退消息快照兜底并收起按钮。纯函数 · T2-7 对表。
 */
internal fun liuliResolveProposalState(status: MeetingStatus?, responded: String?): LiuliProposalCardState =
    when (status) {
        MeetingStatus.PROPOSED -> LiuliProposalCardState.PENDING
        MeetingStatus.CONFIRMED, MeetingStatus.HONORED, MeetingStatus.MISSED -> LiuliProposalCardState.AGREED
        MeetingStatus.CANCELLED -> LiuliProposalCardState.DECLINED
        null -> when (responded) {
            FutureMeetingProposalData.RESPONDED_DECLINED -> LiuliProposalCardState.DECLINED
            else -> LiuliProposalCardState.AGREED
        }
    }

/** 角色名为空时的兜底称呼（照抄暖陶 F11 的 `"TA"`）。 */
private const val FALLBACK_NAME = "TA"

/** 落值（§4.7·孤值即打回）。 */
private val ARROW_GAP = 6.dp
private val RECEIPT_TOP = 4.dp
private val RECEIPT_GAP = 5.dp
private val RECEIPT_ICON = 14.dp
