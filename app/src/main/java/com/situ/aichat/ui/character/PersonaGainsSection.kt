package com.situ.aichat.ui.character

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import java.util.UUID

// 活人感内核·卷一《人设编译器》「她吃哪套」三层（图纸 §4.3 · D-4 / D-10）：
// ①专属项（置顶·可删）②手写新增（三护栏）③系统 27 项按九组分组（无删除键·设「不吃这套」即等于关闭）。
// 卷一只**编译、存、显示**：增益不接任何提示词 / 内核消费端（图纸 §0.0 与 §9.5）。

/** 一行触达最小 48dp（a11y·图纸 §4.6）。增益与算子两区共用同一值。 */
internal val ROW_MIN_HEIGHT = 48.dp

/**
 * 「她吃哪套」整区（性格区内的子标题，不新增分区）。
 *
 * [gains] 是当前值，任何改动经 [onChange] 交回 VM——本组件不持久化、不碰 DB。
 */
@Composable
internal fun PersonaGainsSection(gains: PersonaGains, onChange: (PersonaGains) -> Unit) {
    val colors = AppTheme.colors
    Text(
        text = stringResource(R.string.persona_gains_title),
        style = MaterialTheme.typography.titleSmall,
        color = colors.text.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
    // 摘要行：系统项里「与常人不同」的条数 + 专属项条数（专属为 0 时后半句整句省略）。
    val differing = gains.system.count { it.value != PersonaVocab.LEVEL_NORMAL }
    Text(
        text = if (gains.custom.isEmpty()) {
            stringResource(R.string.persona_gains_summary_short, differing)
        } else {
            stringResource(R.string.persona_gains_summary, differing, gains.custom.size)
        },
        style = MaterialTheme.typography.bodySmall,
        color = colors.text.tertiary,
    )

    CustomGainsBlock(gains = gains, onChange = onChange)
    SystemGainsBlock(gains = gains, onChange = onChange)

    Text(
        text = stringResource(R.string.persona_gains_footer),
        style = MaterialTheme.typography.bodySmall,
        color = colors.text.tertiary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** ①专属项 + ②手写新增（D-10 三护栏）。 */
@Composable
private fun CustomGainsBlock(gains: PersonaGains, onChange: (PersonaGains) -> Unit) {
    val colors = AppTheme.colors
    if (gains.custom.isNotEmpty()) {
        Text(
            text = stringResource(R.string.persona_gains_custom_title),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text.secondary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        gains.custom.forEach { item ->
            GainRow(
                label = item.label,
                level = item.level,
                badge = if (item.origin == CustomGain.ORIGIN_COMPILED) {
                    stringResource(R.string.persona_gains_custom_badge)
                } else {
                    null
                },
                onLevelChange = { lv ->
                    onChange(gains.copy(custom = gains.custom.map { if (it.id == item.id) it.copy(level = lv) else it }))
                },
                onDelete = { onChange(gains.copy(custom = gains.custom.filterNot { it.id == item.id })) },
            )
        }
    }
    CustomGainAdder(gains = gains, onChange = onChange)
}

/** ②手写新增：可观察性提示（恒显）+ 语义查重（禁止提交）+ 上限 10（触发行置灰）。 */
@Composable
private fun CustomGainAdder(gains: PersonaGains, onChange: (PersonaGains) -> Unit) {
    val colors = AppTheme.colors
    val full = gains.custom.size >= PersonaGains.MAX_CUSTOM
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    // 查重口径：27 项标签 ∪ 已有专属标签，去空白 + 全小写比较。
    val systemLabels = PersonaVocab.GAIN_KEYS.map { stringResource(PersonaVocab.GAINS.getValue(it)) }
    val taken = (systemLabels + gains.custom.map { it.label }).associateBy { it.trim().lowercase() }
    val duplicateOf = taken[draft.trim().lowercase()]
    val submittable = draft.isNotBlank() && duplicateOf == null && !full

    Row(
        Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = ROW_MIN_HEIGHT)
            .clickable(enabled = !full) { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.persona_gains_add_trigger),
            style = MaterialTheme.typography.bodyMedium,
            // 护栏 3：满 10 项时触发行置灰（点不动）。
            color = if (full) colors.text.tertiary else colors.accent.text,
        )
    }
    if (expanded && !full) {
        AppTextField(
            value = draft,
            // 护栏：输入上限 12 字——**超出不接收**（而不是接收后截断，免得用户以为写进去了）。
            onValueChange = { if (it.length <= CustomGain.MAX_LABEL_LENGTH) draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.persona_gains_add_label),
            placeholder = stringResource(R.string.persona_gains_add_placeholder),
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AppButton(
                onClick = {
                    onChange(
                        gains.copy(
                            custom = gains.custom + CustomGain(
                                id = UUID.randomUUID().toString(),
                                label = draft.trim(),
                                level = PersonaVocab.LEVEL_SENSITIVE, // D-10：新项默认「很敏感」
                                origin = CustomGain.ORIGIN_MANUAL,
                            ),
                        ),
                    )
                    draft = ""
                    expanded = false
                },
                style = AppButtonStyle.Tonal,
                enabled = submittable,
            ) {
                Text(stringResource(R.string.persona_gains_add_confirm))
            }
        }
    }
    // 护栏 1：可观察性提示恒显（不是错误态，是写法指导）。
    Text(
        text = buildString {
            append(stringResource(R.string.persona_gains_observable_hint))
            if (!full) append(stringResource(R.string.persona_gains_remaining_hint, PersonaGains.MAX_CUSTOM - gains.custom.size))
        },
        style = MaterialTheme.typography.bodySmall,
        color = colors.text.tertiary,
    )
    // 护栏 2：语义查重命中即提示并禁止提交（上面 submittable 已挡住按钮）。
    if (duplicateOf != null) {
        Text(
            text = stringResource(R.string.persona_gains_duplicate_hint, duplicateOf),
            style = MaterialTheme.typography.bodySmall,
            color = colors.status.onWarning,
        )
    }
    // 护栏 3 的说明句。
    if (full) {
        Text(
            text = stringResource(R.string.persona_gains_full_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text.tertiary,
        )
    }
}

/** ③系统 27 项：按九组分组，默认只展开档位 ≠「正常」的项（D-4）。 */
@Composable
private fun SystemGainsBlock(gains: PersonaGains, onChange: (PersonaGains) -> Unit) {
    val colors = AppTheme.colors
    var showAll by remember { mutableStateOf(false) }
    val normalCount = PersonaVocab.GAIN_KEYS.count { gains.system[it] == null || gains.system[it] == PersonaVocab.LEVEL_NORMAL }

    PersonaVocab.GAIN_GROUPS.forEach { group ->
        val visibleKeys = group.keys.filter { showAll || (gains.system[it] ?: PersonaVocab.LEVEL_NORMAL) != PersonaVocab.LEVEL_NORMAL }
        if (visibleKeys.isEmpty()) return@forEach
        Text(
            text = stringResource(group.labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text.secondary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        visibleKeys.forEach { key ->
            GainRow(
                label = stringResource(PersonaVocab.GAINS.getValue(key)),
                level = gains.system[key] ?: PersonaVocab.LEVEL_NORMAL,
                badge = null,
                // 无删除键（D-4：设「不吃这套」即等于关闭）。回到「正常」= 从 map 里摘掉（缺席即 1·Y-7）。
                onLevelChange = { lv ->
                    val next = gains.system.toMutableMap()
                    if (lv == PersonaVocab.LEVEL_NORMAL) next.remove(key) else next[key] = lv
                    onChange(gains.copy(system = next))
                },
                onDelete = null,
            )
        }
    }
    if (normalCount > 0) {
        Row(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = ROW_MIN_HEIGHT)
                .clickable { showAll = !showAll },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (showAll) {
                    stringResource(R.string.persona_gains_collapse)
                } else {
                    stringResource(R.string.persona_gains_expand, normalCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent.text,
            )
        }
    }
}

/** 一条增益：标签（+可选徽标 / 删除键）+ 三档分段控件。 */
@Composable
private fun GainRow(
    label: String,
    level: Int,
    badge: String?,
    onLevelChange: (Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = AppTheme.colors
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().sizeIn(minHeight = ROW_MIN_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.text.primary)
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.tertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (onDelete != null) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(ROW_MIN_HEIGHT)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.persona_gains_custom_delete),
                            tint = colors.text.tertiary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        AppSegmentedControl(
            options = LEVELS,
            selected = level.coerceIn(PersonaVocab.LEVEL_NUMB, PersonaVocab.LEVEL_SENSITIVE),
            onSelect = onLevelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { stringResource(PersonaVocab.levelLabelRes(it)) },
        )
    }
}

/** 三档次序恒为 不吃这套 / 正常 / 很敏感（= 档位整数 0/1/2 的自然序）。 */
private val LEVELS = listOf(PersonaVocab.LEVEL_NUMB, PersonaVocab.LEVEL_NORMAL, PersonaVocab.LEVEL_SENSITIVE)
