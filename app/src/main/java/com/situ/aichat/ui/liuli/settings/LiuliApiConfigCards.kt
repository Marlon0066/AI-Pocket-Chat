package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.remote.llm.ApiBalanceResult
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.ToolSupportLevel
import com.situ.aichat.data.local.entity.detectedToolSupportLevel
import com.situ.aichat.data.local.entity.effectiveAudioInputEnabled
import com.situ.aichat.data.local.entity.effectiveIsThinkingModel
import com.situ.aichat.data.local.entity.effectiveVisionEnabled
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.util.QrCodec

/** 卡内距 / 块间缝 / 徽章排的两轴缝（逐字照暖陶 16 / 8 / 6 / 4）。 */
private val CARD_PAD = 16.dp
private val CARD_GAP = 8.dp
private val BADGE_GAP_H = 6.dp
private val BADGE_GAP_V = 4.dp
/** 卡内动作圆钮（同步进钮 28 / 16）与它们之间的缝。 */
private val ACTION_BUTTON = LiuliPageGeometry.stepperButton
private val ACTION_ICON = LiuliPageGeometry.stepperIcon
private val ACTION_GAP = 4.dp
/** 能力芯片：圆角 6 · 内距 8/2（逐字照暖陶 `CapabilityChip`）。 */
private val CHIP_SHAPE = RoundedCornerShape(6.dp)
private val CHIP_PAD_H = 8.dp
private val CHIP_PAD_V = 2.dp
/** 二维码弹窗里图与警示句的缝（逐字照暖陶的 12）。 */
private val QR_GAP = 12.dp
/** 溢出菜单锚点（贴卡右缘往左展开·落在钮下方）。 */
private val MENU_OFFSET = DpOffset((-8).dp, 36.dp)
/** 「用于：…」那行的字色透明度（≈ iOS .tertiary·逐字照暖陶 0.6）。 */
private const val ASSIGNMENT_ALPHA = 0.6f

/**
 * API 配置卡（琉璃·图纸 2026-09-06 卷五 §4.1 屏 7）。壳 = [liuliCardSurface]（独立卡·不是分组），
 * 卡内行 / 徽章 / 菜单 / 按钮的**条件与文案逐字继承**暖陶 `ConfigCard`。
 *
 * 💰 **只读**：余额只显示不动钱（`supportsBalance` 仅 DeepSeek / OpenRouter·判红阈值 10 逐字照抄）。
 */
@Composable
internal fun LiuliApiConfigCard(
    cfg: ApiConfigEntity,
    isDetecting: Boolean,
    isUndetermined: Boolean,
    balance: ApiBalanceResult?,
    functionNames: List<String>,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onClone: () -> Unit,
    onExportQr: () -> Unit,
    onRedetect: () -> Unit,
    onRefreshBalance: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val supportsBalance = ApiProviderType.fromRaw(cfg.providerTypeRaw).let {
        it == ApiProviderType.DEEPSEEK || it == ApiProviderType.OPENROUTER
    }
    Column(
        modifier
            .fillMaxWidth()
            .liuliCardSurface()
            .padding(CARD_PAD),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cfg.providerName, style = AppTypography.bodyEmphasis, color = colors.text.primary)
                Text(cfg.modelName, style = AppTypography.secondary, color = colors.text.secondary)
                LiuliBalanceLabel(balance)
            }
            if (supportsBalance) {
                CardActionButton(Icons.Filled.Refresh, stringResource(R.string.api_balance_refresh), onRefreshBalance)
                Box(Modifier.size(ACTION_GAP))
            }
            if (cfg.isActive) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.a11y_already_enabled),
                    tint = colors.status.onSuccess,
                    modifier = Modifier.size(ACTION_ICON),
                )
            } else {
                LiuliButton(onClick = onActivate, style = LiuliButtonStyle.Text) { Text(ACTIVATE_LABEL) }
            }
            Box(Modifier.size(ACTION_GAP))
            CardActionButton(Icons.Filled.Edit, stringResource(R.string.api_edit), onEdit)
            Box(Modifier.size(ACTION_GAP))
            CardActionButton(
                Icons.Filled.Delete,
                stringResource(R.string.action_delete),
                onDelete,
                tint = colors.status.onError,
            )
            Box(Modifier.size(ACTION_GAP))
            Box {
                CardActionButton(Icons.Filled.MoreVert, stringResource(R.string.api_more), onClick = { menuOpen = true })
                LiuliPopupMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    items = listOf(
                        LiuliMenuEntry(text = stringResource(R.string.api_duplicate), onClick = onClone),
                        // 13.10b 扫码导出：生成含本配置的二维码供另一台设备扫码导入。
                        LiuliMenuEntry(text = stringResource(R.string.api_export_qr), onClick = onExportQr),
                    ),
                    offset = MENU_OFFSET,
                )
            }
        }

        LiuliCapabilityBadges(cfg = cfg, isDetecting = isDetecting)

        // settings-api-6：最近检测返回「不确定」时的原因提示（对齐 iOS detectionHint）。
        if (isUndetermined) {
            Text(
                stringResource(R.string.api_detection_hint),
                style = AppTypography.secondary,
                color = colors.status.onError,
            )
        }

        // settings-api-3：该配置承接的功能「用于：…」（对齐 iOS functionAssignmentText）。
        if (functionNames.isNotEmpty()) {
            Text(
                stringResource(R.string.api_function_assignment_prefix) +
                    functionNames.joinToString(stringResource(R.string.api_capability_separator)),
                style = AppTypography.secondary,
                color = colors.text.secondary.copy(alpha = ASSIGNMENT_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LiuliButton(onClick = onRedetect, style = LiuliButtonStyle.Text, enabled = !isDetecting) {
            Text(stringResource(R.string.api_capability_redetect))
        }
    }
}

/** 「启用」钮文案（暖陶 `ApiConfigScreen.kt:384` 的硬编码同值·A-6）。 */
private const val ACTIVATE_LABEL = "启用"

/** 卡内一枚 28 圆钮（版位恰 28·48 触达居中外溢）。 */
@Composable
private fun CardActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    Box(Modifier.size(ACTION_BUTTON), contentAlignment = Alignment.Center) {
        LiuliCircleButton(onClick = onClick, contentDescription = contentDescription, size = ACTION_BUTTON) {
            if (tint != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(ACTION_ICON))
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ACTION_ICON))
            }
        }
    }
}

/**
 * 余额行（💰 只读·镜像 iOS `balanceLabel`）：DeepSeek 用 ¥、OpenRouter 用 $；**低于 10 判红**。
 * 三个格式串与阈值逐字照暖陶 `BalanceLabel`（`:449–486`）——这里一个数都不许改。
 */
@Composable
private fun LiuliBalanceLabel(result: ApiBalanceResult?) {
    val colors = AppTheme.colors
    val style = AppTypography.caption
    when (result) {
        is ApiBalanceResult.DeepSeek -> {
            val low = result.totalBalance < 10
            Text(
                "¥%.2f".format(result.totalBalance),
                style = style,
                color = if (low) colors.status.onError else colors.text.secondary,
            )
        }
        is ApiBalanceResult.OpenRouter -> {
            val limit = result.limit
            if (limit != null) {
                Text("\$%.2f/\$%.2f".format(result.usage, limit), style = style, color = colors.text.secondary)
                result.limitRemaining?.let { rem ->
                    val low = rem < 10
                    Text(
                        stringResource(R.string.api_balance_remaining, "\$%.2f".format(rem)),
                        style = style,
                        color = if (low) colors.status.onError else colors.text.secondary,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.api_balance_used, "\$%.2f".format(result.usage)),
                    style = style,
                    color = colors.text.secondary,
                )
            }
        }
        ApiBalanceResult.Unsupported, ApiBalanceResult.Failed, null -> Unit
    }
}

/** 能力徽章排（检测中 = 转圈 + 一句；无能力 = 一句；否则 FlowRow 芯片）。条件逐字照暖陶。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiuliCapabilityBadges(cfg: ApiConfigEntity, isDetecting: Boolean) {
    val colors = AppTheme.colors
    if (isDetecting) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BADGE_GAP_H)) {
            LiuliSpinner()
            Text(
                stringResource(R.string.api_capability_detecting),
                style = AppTypography.secondary,
                color = colors.text.secondary,
            )
        }
        return
    }

    val labels = buildList {
        if (cfg.effectiveIsThinkingModel()) add(stringResource(R.string.api_capability_thinking))
        if (cfg.effectiveVisionEnabled()) add(stringResource(R.string.api_capability_vision))
        if (cfg.effectiveAudioInputEnabled()) add(stringResource(R.string.api_capability_audio))
        when (cfg.detectedToolSupportLevel) {
            ToolSupportLevel.FULL -> add(stringResource(R.string.api_capability_tool_full))
            ToolSupportLevel.BASIC -> add(stringResource(R.string.api_capability_tool_basic))
            else -> Unit
        }
    }

    if (labels.isEmpty()) {
        Text(
            stringResource(R.string.api_capability_none_detected),
            style = AppTypography.secondary,
            color = colors.text.secondary,
        )
        return
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(BADGE_GAP_H),
        verticalArrangement = Arrangement.spacedBy(BADGE_GAP_V),
    ) {
        labels.forEach { label ->
            Text(
                label,
                style = AppTypography.caption,
                color = colors.accent.onContainer,
                modifier = Modifier
                    .clip(CHIP_SHAPE)
                    .background(colors.accent.container)
                    .padding(horizontal = CHIP_PAD_H, vertical = CHIP_PAD_V),
            )
        }
    }
}

/**
 * 配置二维码导出弹窗（13.10b · C7）。**警示句必留**：payload 里是**明文 API Key**，
 * 谁扫到谁就拿到密钥（勘察表零碰清单里点名的那一条）。
 */
@Composable
internal fun LiuliExportQrDialog(payload: String, onDismiss: () -> Unit) {
    val qr = remember(payload) { QrCodec.encode(payload, QR_SIZE).asImageBitmap() }
    LiuliDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.api_export_qr_title),
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.api_export_qr_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(LiuliShapes.small),
                )
                Box(Modifier.size(QR_GAP))
                Text(
                    stringResource(R.string.api_export_qr_warning),
                    style = AppTypography.secondary,
                    color = AppTheme.colors.status.onError,
                )
            }
        },
    )
}

/** 二维码位图边长（逐字照暖陶 `ExportQrDialog` 的 640）。 */
private const val QR_SIZE = 640
