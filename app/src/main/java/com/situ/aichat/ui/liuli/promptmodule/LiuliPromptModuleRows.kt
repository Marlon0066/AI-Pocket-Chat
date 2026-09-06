package com.situ.aichat.ui.liuli.promptmodule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.prompt.PromptModule
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.SystemModuleType
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.promptmodule.SceneBadgeState
import com.situ.aichat.ui.promptmodule.sceneBadgeState
import com.situ.aichat.ui.promptmodule.sceneName

/** 灰置态透明度（对齐 iOS `.opacity(0.4)`·逐字照暖陶）。 */
private const val GATED_ALPHA = 0.4f
/** 勾选圈 / 上下移圆钮的尺寸（勾选圈图标 18·排序钮 28 同步进钮）。 */
private val CHECK_ICON = 18.dp
private val MOVE_BUTTON = LiuliPageGeometry.stepperButton
private val MOVE_ICON = LiuliPageGeometry.stepperIcon
/** 两枚排序钮的缝：28 + 20 = 48 → 两枚 48 触达框恰好不重叠（步进钮同理·复核 R1 A-4：原 6 会让 ↑ 右缘触发 ↓）。 */
private val MOVE_GAP = 20.dp
/** 徽章排的缝与徽章内距（逐字照暖陶 6 / 6-1）。 */
private val BADGE_GAP = 6.dp
private val BADGE_PAD_H = 6.dp
private val BADGE_PAD_V = 1.dp
/** 徽章底色透明度（逐字照暖陶 `TinyBadge` 的 0.12）。 */
private const val BADGE_BG_ALPHA = 0.12f

/**
 * 一条提示词模块行（琉璃·图纸 2026-09-06 卷五 §4.1 屏 21）。左勾选圈 + 名 + 双徽章（系统 / 自定义 ·
 * 场景四态）+ 右上下移两枚 28 圆钮；整行点开编辑。
 *
 * **灰置**：表情包模块在「角色发送表情包」总开关关掉时整行 alpha 0.4 且不可交互（保留勾选偏好·
 * 逐字照暖陶 `:270 / :295`）。徽章四态判定借暖陶 [sceneBadgeState]（纯函数·已 T1）。
 */
@Composable
internal fun LiuliPromptModuleRow(
    module: PromptModule,
    isFirst: Boolean,
    isLast: Boolean,
    sceneFilter: PromptScene?,
    isDisabledByParentToggle: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    divider: Boolean,
) {
    val colors = AppTheme.colors
    LiuliRowBase(
        modifier = Modifier.alpha(if (isDisabledByParentToggle) GATED_ALPHA else 1f),
        onClick = onEdit,
        enabled = !isDisabledByParentToggle,
        minHeight = LiuliPageGeometry.rowTwoLine,
        verticalPadding = LiuliPageGeometry.rowTwoLinePad,
        divider = divider,
    ) {
        Box(Modifier.size(MOVE_BUTTON), contentAlignment = Alignment.Center) {
            LiuliCircleButton(
                onClick = onToggle,
                contentDescription = module.name,
                size = MOVE_BUTTON,
                enabled = !isDisabledByParentToggle,
            ) {
                Icon(
                    if (module.isEnabled) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (module.isEnabled) colors.accent.text else colors.text.tertiary,
                    modifier = Modifier.size(CHECK_ICON),
                )
            }
        }
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        Column(Modifier.weight(1f)) {
            Text(
                module.name,
                style = AppTypography.body,
                color = if (module.isEnabled) colors.text.primary else colors.text.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BADGE_GAP)) {
                LiuliTinyBadge(
                    text = stringResource(
                        if (module.isSystemGenerated) R.string.pm_badge_system else R.string.pm_badge_custom,
                    ),
                    color = if (module.isSystemGenerated) colors.accent.text else colors.accent.primary,
                )
                LiuliSceneBadge(module, sceneFilter)
            }
            if (isDisabledByParentToggle) {
                Text(
                    stringResource(R.string.pm_sticker_gated_hint),
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                )
            }
            // 现在卡语义（2026-07-11）：时间感知 / 此刻状态默认排后置区末尾（紧贴生成点·时间把握最准）。
            if (module.systemModuleType == SystemModuleType.TIME_AWARENESS ||
                module.systemModuleType == SystemModuleType.CURRENT_MOMENT
            ) {
                Text(
                    stringResource(R.string.pm_now_card_hint),
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                )
            }
        }
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        MoveButton(Icons.Filled.ArrowUpward, stringResource(R.string.pm_move_up), enabled = !isFirst, onClick = onMoveUp)
        Spacer(Modifier.width(MOVE_GAP))
        MoveButton(Icons.Filled.ArrowDownward, stringResource(R.string.pm_move_down), enabled = !isLast, onClick = onMoveDown)
    }
}

/** 一枚 28 排序圆钮（到界即禁用·版位恰 28）。 */
@Composable
private fun MoveButton(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(MOVE_BUTTON), contentAlignment = Alignment.Center) {
        LiuliCircleButton(onClick = onClick, contentDescription = contentDescription, size = MOVE_BUTTON, enabled = enabled) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(MOVE_ICON))
        }
    }
}

/** 小徽章（暖陶 `TinyBadge` 的琉璃对应件·底色 12% 同值）。 */
@Composable
internal fun LiuliTinyBadge(text: String, color: Color) {
    Text(
        text,
        style = AppTypography.caption,
        color = color,
        modifier = Modifier
            .clip(LiuliShapes.pill)
            .background(color.copy(alpha = BADGE_BG_ALPHA))
            .padding(horizontal = BADGE_PAD_H, vertical = BADGE_PAD_V),
    )
}

/** 场景徽章（四态 + 线下视角下核心规则的「专版」特例·条件逐字照暖陶 `SceneBadge`）。 */
@Composable
private fun LiuliSceneBadge(module: PromptModule, sceneFilter: PromptScene?) {
    val colors = AppTheme.colors
    if (module.systemModuleType == SystemModuleType.CORE_RULES && sceneFilter == PromptScene.OFFLINE_MEETING) {
        LiuliTinyBadge(text = stringResource(R.string.pm_badge_offline_variant), color = colors.accent.primary)
        return
    }
    val (textRes, color) = when (sceneBadgeState(module.enabledScenes)) {
        SceneBadgeState.CHAT_AND_MEET -> R.string.pm_scene_badge_chat_meet to colors.text.secondary
        SceneBadgeState.CHAT_ONLY -> R.string.pm_scene_badge_chat_only to colors.accent.primary
        SceneBadgeState.MEET_ONLY -> R.string.pm_scene_badge_meet_only to colors.accent.primary
        SceneBadgeState.NONE -> R.string.pm_scene_badge_none to colors.status.onError
    }
    LiuliTinyBadge(text = stringResource(textRes), color = color)
}

/** 线下 tab 底部的叙事预设跳转卡（§4-U5·只读·不排序）。 */
@Composable
internal fun LiuliNarrativePresetRow(levelName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiuliNavRow(
        title = stringResource(R.string.pm_narrative_card_title),
        subtitle = stringResource(R.string.pm_narrative_card_desc, levelName),
        onClick = onClick,
        modifier = modifier,
        divider = false,
    )
}

/** 让 `sceneName` 的 import 在本文件有实处——芯片排在屏文件里用它，这里只做 re-export 式引用。 */
@Composable
internal fun liuliSceneName(scene: PromptScene): String = sceneName(scene)
