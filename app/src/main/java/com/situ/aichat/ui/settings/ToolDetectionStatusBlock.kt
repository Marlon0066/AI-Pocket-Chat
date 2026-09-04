package com.situ.aichat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.detectedToolSupportLevel
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.ui.chat.rememberRelativeTimeStrings
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.util.DateFormatters

/**
 * 「工具调用」检测状态块 —— 放在「编辑 API 配置」页工具调用 [ModePickerRow] 下方（取代原来的小角标
 * `检测：…`）。用陪伴口吻把「这模型能不能在聊天里直接帮你办事」讲清楚，**不支持也温和不报警**（marker
 * 降级照常可用，是产品事实而非错误）。设计已过审，见 TOOL_CALLING_HARDENING_PLAN §9。
 *
 * 判定语义抽进纯函数 [resolveToolDetectionKind]（已 T1）；本 composable 只负责按种类映射文案 / 莫兰迪柔色 /
 * 相对时间 / 覆盖提示 / 重测按钮，**不碰任何运行时工具调用逻辑、不碰别的页**。
 *
 * @param detecting 该配置当前是否正在检测（来自 `ApiConfigViewModel.detecting`，实时态优先于持久等级）。
 * @param onRedetect 「重新检测 / 立即检测」点击 —— 复用 `viewModel.redetect(uuid)`。
 */
@Composable
fun ToolDetectionStatusBlock(
    config: ApiConfigEntity,
    toolMode: ToolCallingMode,
    detecting: Boolean,
    onRedetect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val kind = resolveToolDetectionKind(config.detectedToolSupportLevel, detecting)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 状态主行：莫兰迪柔色圆点（或转圈）+ 友好文案
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolStatusDot(kind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = toolStatusText(kind),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text.secondary,
            )
        }

        // 「上次检测 · X 前」——有检测时间戳、且当前不在检测中时显示
        val checkedAt = config.toolDetectionCheckedAt
        if (checkedAt != null && kind != ToolDetectionStatusKind.Detecting) {
            val relative = DateFormatters.relativeTimeString(
                millis = checkedAt,
                nowMillis = System.currentTimeMillis(),
                strings = rememberRelativeTimeStrings(),
            )
            Text(
                text = stringResource(R.string.api_tool_status_last_checked, relative),
                style = MaterialTheme.typography.labelSmall,
                color = colors.text.tertiary,
            )
        }

        // 手动覆盖提示（toolMode = 开启/关闭 时）：检测结果仅供参考
        if (toolMode == ToolCallingMode.ENABLED || toolMode == ToolCallingMode.DISABLED) {
            Text(
                text = stringResource(
                    if (toolMode == ToolCallingMode.ENABLED) R.string.api_tool_override_enabled
                    else R.string.api_tool_override_disabled,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = colors.text.tertiary,
            )
        }

        // 重新检测 / 立即检测（检测中不显示——转圈已表态）
        if (kind != ToolDetectionStatusKind.Detecting) {
            AppButton(
                onClick = onRedetect,
                style = AppButtonStyle.Text,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(
                        if (kind == ToolDetectionStatusKind.NotDetected) R.string.api_tool_detect_now
                        else R.string.api_tool_redetect,
                    ),
                )
            }
        }
    }
}

/** 种类 → 友好文案（陪伴口吻·五态终版文案见 §9）。 */
@Composable
private fun toolStatusText(kind: ToolDetectionStatusKind): String = stringResource(
    when (kind) {
        ToolDetectionStatusKind.Detecting -> R.string.api_tool_status_detecting
        ToolDetectionStatusKind.FullSupport -> R.string.api_tool_status_full
        ToolDetectionStatusKind.BasicSupport -> R.string.api_tool_status_basic
        ToolDetectionStatusKind.UnsupportedFallback -> R.string.api_tool_status_fallback
        ToolDetectionStatusKind.NotDetected -> R.string.api_tool_status_undetected
    },
)

/**
 * 状态圆点：莫兰迪柔色（绿/琥珀/灰实心、未检测空心、检测中转圈）。圆点是纯装饰指示，文字由上方
 * [toolStatusText] 承载语义，故颜色只取「能一眼分清」即可，不进 ColorContrastTest 文字网。
 */
@Composable
private fun ToolStatusDot(kind: ToolDetectionStatusKind) {
    val colors = AppTheme.colors
    if (kind == ToolDetectionStatusKind.Detecting) {
        AppLoadingRing(size = AppLoadingRingSize.Small)
        return
    }
    val hollow = kind == ToolDetectionStatusKind.NotDetected
    val dotColor = when (kind) {
        ToolDetectionStatusKind.FullSupport -> colors.status.onSuccess // 柔绿
        ToolDetectionStatusKind.BasicSupport -> colors.status.onWarning // 琥珀
        else -> colors.text.tertiary // 不支持→兼容 灰点 / 未检测 空心边框色
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .then(
                if (hollow) Modifier.border(1.5.dp, colors.text.tertiary, CircleShape)
                else Modifier.background(dotColor, CircleShape),
            ),
    )
}
