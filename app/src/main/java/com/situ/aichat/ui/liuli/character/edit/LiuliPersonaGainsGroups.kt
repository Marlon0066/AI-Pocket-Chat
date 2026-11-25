package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.ui.character.PERSONA_GAIN_LEVELS
import com.situ.aichat.ui.character.acceptsCustomGainDraft
import com.situ.aichat.ui.character.customGainDuplicateOf
import com.situ.aichat.ui.character.customGainSubmittable
import com.situ.aichat.ui.character.differingCount
import com.situ.aichat.ui.character.isCustomFull
import com.situ.aichat.ui.character.normalSystemCount
import com.situ.aichat.ui.character.visibleSystemKeys
import com.situ.aichat.ui.character.withCustomLevel
import com.situ.aichat.ui.character.withNewCustom
import com.situ.aichat.ui.character.withSystemLevel
import com.situ.aichat.ui.character.withoutCustom
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliTextActionRow
import com.situ.aichat.ui.liuli.page.liuliFootprint

/** 行尾删除钮视觉 20（§4.8 锁定值·触达由 `liuliFootprint` 外溢到 48）。 */
private val DELETE_ICON = 20.dp

/**
 * 「她吃哪套」组（§4.8）：摘要 → 专属项 → 手写新增（三护栏·共用 [acceptsCustomGainDraft] 等纯函数）→ 系统 27 项分组
 * （默认只显非「正常」）→ 展开 / 收起。任何改动经 [onChange] 交回 VM，本组件不持久化。
 */
@Composable
internal fun LiuliPersonaGainsGroup(gains: PersonaGains, onChange: (PersonaGains) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val full = gains.isCustomFull()
    val systemLabels = PersonaVocab.GAIN_KEYS.map { stringResource(PersonaVocab.GAINS.getValue(it)) }
    val duplicateOf = customGainDuplicateOf(draft, systemLabels, gains.custom)

    LiuliGroup(
        modifier = modifier,
        header = stringResource(R.string.persona_gains_title),
        footer = stringResource(R.string.persona_gains_footer),
    ) {
        LiuliRowBase(divider = false, minHeight = 0.dp, verticalPadding = 10.dp) {
            Text(
                if (gains.custom.isEmpty()) {
                    stringResource(R.string.persona_gains_summary_short, gains.differingCount())
                } else {
                    stringResource(R.string.persona_gains_summary, gains.differingCount(), gains.custom.size)
                },
                style = AppTypography.caption,
                color = colors.text.secondary,
            )
        }
        if (gains.custom.isNotEmpty()) {
            // 卷五 §11 D-4（复核 R1 核准）：§4.8 只写了小标题行的字样，没给行参数；取与摘要行同档（minHeight 0 · 上下 10）（§11 D-4）。
            SubTitleRow(stringResource(R.string.persona_gains_custom_title))
            gains.custom.forEach { item ->
                LiuliGainRow(
                    label = item.label,
                    level = item.level,
                    badge = if (item.origin == CustomGain.ORIGIN_COMPILED) stringResource(R.string.persona_gains_custom_badge) else null,
                    onLevelChange = { onChange(gains.withCustomLevel(item.id, it)) },
                    onDelete = { onChange(gains.withoutCustom(item.id)) },
                )
            }
        }
        LiuliTextActionRow(stringResource(R.string.persona_gains_add_trigger), onClick = { expanded = !expanded }, enabled = !full)
        if (expanded && !full) {
            LiuliRowBase(minHeight = 0.dp, verticalPadding = 10.dp, verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiuliField(
                        value = draft,
                        onValueChange = { if (acceptsCustomGainDraft(it)) draft = it },
                        label = stringResource(R.string.persona_gains_add_label),
                        placeholder = stringResource(R.string.persona_gains_add_placeholder),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        LiuliButton(
                            onClick = {
                                onChange(gains.withNewCustom(draft))
                                draft = ""
                                expanded = false
                            },
                            style = LiuliButtonStyle.Glass,
                            enabled = customGainSubmittable(draft, duplicateOf, full),
                        ) { Text(stringResource(R.string.persona_gains_add_confirm)) }
                    }
                }
            }
        }
        LiuliRowBase(minHeight = 0.dp, verticalPadding = 8.dp, verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 护栏 1：可观察性提示恒显 + 余量（同暖陶拼法）。
                Text(
                    buildString {
                        append(stringResource(R.string.persona_gains_observable_hint))
                        if (!full) append(stringResource(R.string.persona_gains_remaining_hint, PersonaGains.MAX_CUSTOM - gains.custom.size))
                    },
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                )
                // 护栏 2：查重命中即提示（「添加」已被 customGainSubmittable 挡住）。
                if (duplicateOf != null) {
                    Text(stringResource(R.string.persona_gains_duplicate_hint, duplicateOf), style = AppTypography.caption, color = colors.status.onWarning)
                }
                // 护栏 3 的说明句。
                if (full) Text(stringResource(R.string.persona_gains_full_hint), style = AppTypography.caption, color = colors.text.secondary)
            }
        }
        PersonaVocab.GAIN_GROUPS.forEach { group ->
            val keys = gains.visibleSystemKeys(group, showAll)
            if (keys.isNotEmpty()) {
                SubTitleRow(stringResource(group.labelRes))
                keys.forEach { key ->
                    LiuliGainRow(
                        label = stringResource(PersonaVocab.GAINS.getValue(key)),
                        level = gains.system[key] ?: PersonaVocab.LEVEL_NORMAL,
                        badge = null,
                        // 无删除键（D-4：设「不吃这套」即等于关闭）；回到「正常」= 摘键。
                        onLevelChange = { onChange(gains.withSystemLevel(key, it)) },
                        onDelete = null,
                    )
                }
            }
        }
        val normalCount = gains.normalSystemCount()
        if (normalCount > 0) {
            LiuliTextActionRow(
                if (showAll) stringResource(R.string.persona_gains_collapse) else stringResource(R.string.persona_gains_expand, normalCount),
                onClick = { showAll = !showAll },
            )
        }
    }
}

@Composable
private fun SubTitleRow(text: String) {
    LiuliRowBase(minHeight = 0.dp, verticalPadding = 10.dp) {
        Text(text, style = AppTypography.caption.copy(fontWeight = FontWeight.W600), color = AppTheme.colors.text.secondary)
    }
}

/** 一条增益（§4.8）：标签（+ 徽标 / 删除钮）+ 三档分段。 */
@Composable
internal fun LiuliGainRow(
    label: String,
    level: Int,
    badge: String?,
    onLevelChange: (Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = AppTheme.colors
    LiuliRowBase(minHeight = 0.dp, verticalPadding = 10.dp, verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = AppTypography.body, color = colors.text.primary)
                badge?.let { Text(it, style = AppTypography.caption, color = colors.text.secondary, modifier = Modifier.padding(start = 8.dp)) }
                Spacer(Modifier.weight(1f))
                onDelete?.let { DeleteButton(stringResource(R.string.persona_gains_custom_delete), it) }
            }
            LiuliSegmented(
                options = PERSONA_GAIN_LEVELS,
                selected = level.coerceIn(PersonaVocab.LEVEL_NUMB, PersonaVocab.LEVEL_SENSITIVE),
                label = { stringResource(PersonaVocab.levelLabelRes(it)) },
                onSelect = onLevelChange,
            )
        }
    }
}

@Composable
private fun DeleteButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.liuliFootprint(DELETE_ICON).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppActionIcons.Close, contentDescription = label, tint = AppTheme.colors.text.tertiary, modifier = Modifier.size(DELETE_ICON))
    }
}

/**
 * 「她的固定反应」组（§4.8）：列表空整组不画；每条 = 条件 → 动作 + 开关 + 删除（不做左滑删除·同暖陶）。
 * 词表外的 key 跳过不渲染（同暖陶）。
 */
@Composable
internal fun LiuliPersonaOperatorsGroup(
    operators: List<PersonaOperator>,
    onChange: (List<PersonaOperator>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (operators.isEmpty()) return
    val colors = AppTheme.colors
    LiuliGroup(
        modifier = modifier,
        header = stringResource(R.string.persona_operators_title),
        footer = stringResource(R.string.persona_operators_footer),
    ) {
        var first = true
        operators.forEach { op ->
            val conditionRes = PersonaVocab.CONDITIONS[op.condition]
            val actionRes = PersonaVocab.ACTIONS[op.action]
            if (conditionRes == null || actionRes == null) return@forEach
            LiuliRowBase(divider = !first, minHeight = 0.dp, verticalPadding = 10.dp) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(conditionRes), style = AppTypography.body, color = colors.text.primary)
                    Text(stringResource(actionRes), style = AppTypography.caption, color = colors.text.secondary)
                }
                LiuliSwitch(
                    checked = op.enabled,
                    onCheckedChange = { on -> onChange(operators.map { if (it.id == op.id) it.copy(enabled = on) else it }) },
                )
                Spacer(Modifier.width(8.dp))
                DeleteButton(stringResource(R.string.persona_operators_delete)) { onChange(operators.filterNot { it.id == op.id }) }
            }
            first = false
        }
    }
}
