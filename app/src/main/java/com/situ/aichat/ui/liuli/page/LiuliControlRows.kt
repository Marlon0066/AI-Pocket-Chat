package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliRadio
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliSlider
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * 分组内的「拨 / 选」行族（契约 §6.5「开关行」/「滑杆行」/「单选行」/「分段行」）。
 *
 * 共同规矩：**整行可点**（点击面在行上、控件本身不再各挂一份，否则双触发且读屏念出两个可操作节点）；
 * 触觉只在开关 / 单选 / 分段的 `selection`（滑杆的「嗒」由 `LiuliSlider` 自己在跨格时打）。
 */

private val TITLE_SIZE = 16.sp
private val TITLE_LINE = 21.sp
private val SUB_SIZE = 13.sp
private val SUB_TOP = 2.dp
private val VALUE_SIZE = 15.sp
/** 两行行的上下内距（单源 [LiuliPageGeometry.rowTwoLinePad]）。 */
private val TWO_LINE_PAD = LiuliPageGeometry.rowTwoLinePad
/** 滑杆行 / 分段行的内距（契约 §6.5「内距 12/16」）与行内两段的缝（对版稿 `.sldrow{gap:8px}`）。 */
private val STACK_PAD_V = 12.dp
private val STACK_GAP = 8.dp
/** 滑杆行标题右侧 ⓘ 钮的视觉尺寸（卷五 A-3·触达 48 由 [liuliFootprint] 外溢不占版）。 */
private val INFO_ICON = 18.dp
/** ⓘ 与标题之间的缝。 */
private val INFO_GAP = 4.dp
/** 手填弹窗里说明句 ↔ 输入框的缝。 */
private val MANUAL_FIELD_GAP = 10.dp

/**
 * 行内标题列（标题 + 可选副标）。`internal` 供同包的 [LiuliStepperRow] / [LiuliMenuRow] 复用
 * ——三处行族的标题落值必须同源，各写一份必漂（卷五 A-4 ①②）。
 */
@Composable
internal fun LiuliRowTitleColumn(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            title,
            style = AppTypography.body.copy(fontSize = TITLE_SIZE, lineHeight = TITLE_LINE, fontWeight = FontWeight.W400),
            color = AppTheme.colors.text.primary,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = AppTypography.secondary.copy(fontSize = SUB_SIZE),
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.padding(top = SUB_TOP),
            )
        }
    }
}

/** 开关行：整行 `toggleable(role = Switch)`；右端 [LiuliSwitch] 只当视觉（两层都可点会双触发）。 */
@Composable
fun LiuliToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tileColor: Color? = null,
    /** 砖之外的自定义前导件（如每角色行的 28dp 头像·A-7）。与 [icon] 二选一，同占砖位。 */
    leading: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
) {
    val haptics = LocalAppHaptics.current
    val interaction = remember { MutableInteractionSource() }
    // 读屏念「开 / 关」（同暖陶 `SettingsSwitchRow` 的 stateDescription·卷五复核 R1 A-6 补·两键既有）。
    val stateOn = stringResource(R.string.settings_state_on)
    val stateOff = stringResource(R.string.settings_state_off)
    LiuliRowBase(
        modifier = modifier
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { next ->
                    if (next) haptics.light() else haptics.soft()
                    onCheckedChange(next)
                },
            )
            .semantics { stateDescription = if (checked) stateOn else stateOff },
        interactionSource = interaction,
        minHeight = if (subtitle != null) LiuliPageGeometry.rowTwoLine else LiuliPageGeometry.rowMin,
        verticalPadding = if (subtitle != null) TWO_LINE_PAD else 0.dp,
        divider = divider,
        dividerInset = if (icon != null || leading != null) {
            LiuliPageGeometry.dividerInsetTile
        } else {
            LiuliPageGeometry.dividerInsetPlain
        },
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        } else if (icon != null && tileColor != null) {
            LiuliGroupIconTile(icon, tileColor)
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        }
        LiuliRowTitleColumn(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        // 纯视觉（null）：点击面只在行上；给它 `{}` 会成第二个 toggleable 节点把药丸上的点击吃掉（复核 R1 🔴-2）。
        LiuliSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * 滑杆行（契约 §6.5）：上行 = 标题 + 可选右值 15 tnum；下行 = [LiuliSlider] + 可选「活例子」句。整块不可点，只拖滑杆。
 * [valueLabel] = null 用于「标题本身已经带着当前值」的行（如免打扰起止·A-7）。
 */
@Composable
fun LiuliSliderRow(
    title: String,
    valueLabel: String? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    example: String? = null,
    /**
     * [example] 走警示档（琥珀 `status.onWarning`）而非常态 `text.tertiary`——用于「设出了安全区」这类提示
     * （卷五 A-3 增补·对应暖陶 `SettingsSliderRow.subtitleIsWarning`·**加法零回归**：false = 与增补前同色）。
     */
    exampleIsWarning: Boolean = false,
    enabled: Boolean = true,
    divider: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * 「这一档是什么意思」的说明（卷五 A-3·**加法零回归**：null = 与增补前逐字节同渲染）。非空时标题右侧多一枚
     * 18 ⓘ 钮，点开 [LiuliDialog]（标题 = 本行标题·正文 = 本串）。对应暖陶 `SettingsSliderRow.infoMessage`。
     */
    info: String? = null,
    /**
     * 手填（卷五 A-3·**加法零回归**：null = 右值不可点，与增补前同）。非空时右值可点 → 弹一枚数字 [LiuliField]，
     * 钳位逐字照暖陶 `SettingsSliderRow`：只留数字、`toIntOrNull()` 且 `>= 0` 才回调（**可超过滑杆上限**，
     * 上限钳位在各 setter 里）。
     */
    onManualInput: ((Int) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    // settings-slider-infobutton / settings-slider-manualinput 的琉璃对应态（与暖陶同名同时序）。
    var showInfo by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    LiuliRowBase(
        modifier = modifier,
        minHeight = 0.dp,
        verticalPadding = STACK_PAD_V,
        divider = divider,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 标题 + ⓘ 合成一段占 weight(1f)：ⓘ 必须紧跟标题右侧（暖陶同结构），而长标题仍受可用宽约束
                // ——直接把 weight 给 Text 会把 ⓘ 顶到右值旁边，给 Row 又会让长标题挤掉右值。
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = AppTypography.body.copy(fontSize = TITLE_SIZE, lineHeight = TITLE_LINE),
                        color = colors.text.primary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (info != null) {
                        Spacer(Modifier.width(INFO_GAP))
                        val infoLabel = stringResource(R.string.settings_info_dialog_title)
                        // 版位恰 18（外层盒），48 触达框由 liuliFootprint 居中外溢——直接给内层 18 会把标题挤窄。
                        Box(Modifier.size(INFO_ICON), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .liuliFootprint(INFO_ICON)
                                    .clickable(role = Role.Button, onClickLabel = infoLabel) { showInfo = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = infoLabel,
                                    tint = colors.text.tertiary,
                                    modifier = Modifier.size(INFO_ICON),
                                )
                            }
                        }
                    }
                }
                if (valueLabel != null) {
                    Spacer(Modifier.width(LiuliPageGeometry.tileGap))
                    val valueText = @Composable {
                        Text(
                            valueLabel,
                            style = AppTypography.captionNumeric.copy(fontSize = VALUE_SIZE),
                            color = colors.text.secondary,
                            maxLines = 1,
                        )
                    }
                    if (onManualInput != null) {
                        // 48 触达外溢要配**内层居中盒**：`liuliTouchHeight` 只把节点撑到 48、不居中内容，直接挂在
                        // Text 上字会被顶到 48 框上沿——右值比标题高出一截（卷五复核 R1 🔴·记忆 / 成长 / 线下见面页全中）。
                        Box(
                            modifier = Modifier
                                .liuliTouchHeight()
                                .clickable(role = Role.Button) {
                                    editText = value.roundToInt().toString()
                                    showManual = true
                                },
                            contentAlignment = Alignment.Center,
                        ) { valueText() }
                    } else {
                        valueText()
                    }
                }
            }
            LiuliSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
            )
            if (example != null) {
                Text(
                    example,
                    style = AppTypography.secondary.copy(fontSize = SUB_SIZE),
                    color = if (exampleIsWarning) colors.status.onWarning else colors.text.tertiary,
                )
            }
        }
    }
    if (showInfo && info != null) {
        LiuliDialog(
            onDismissRequest = { showInfo = false },
            title = title,
            body = info,
            confirmText = stringResource(R.string.settings_info_dialog_ok),
            onConfirm = { showInfo = false },
        )
    }
    if (showManual && onManualInput != null) {
        LiuliDialog(
            onDismissRequest = { showManual = false },
            title = stringResource(R.string.settings_manual_input_title),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                // 逐字照暖陶：解析为整数且 >= 0 才写入（可超过滑杆上限·setter 已放宽上限钳位）。
                editText.toIntOrNull()?.let { if (it >= 0) onManualInput(it) }
                showManual = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showManual = false },
            content = {
                Text(
                    stringResource(R.string.settings_manual_input_message, title),
                    style = AppTypography.dialogBody,
                    color = colors.text.secondary,
                )
                Spacer(Modifier.height(MANUAL_FIELD_GAP))
                LiuliField(
                    value = editText,
                    onValueChange = { next -> editText = next.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
        )
    }
}

/**
 * 单选行（契约 §6.5「单选行」）：整行 `selectable(role = RadioButton)`，右端 [LiuliRadio] 只当视觉。
 * [notifyWhenSelected] = true 时点已选中的那行也回调（时区面板「点当前项即关面板」·复核 R1 A-2）。
 */
@Composable
fun LiuliRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
    notifyWhenSelected: Boolean = false,
) {
    val haptics = LocalAppHaptics.current
    val interaction = remember { MutableInteractionSource() }
    LiuliRowBase(
        modifier = modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = {
                if (!selected || notifyWhenSelected) {
                    haptics.selection()
                    onSelect()
                }
            },
        ),
        interactionSource = interaction,
        minHeight = if (subtitle != null) LiuliPageGeometry.rowTwoLine else LiuliPageGeometry.rowMin,
        verticalPadding = if (subtitle != null) TWO_LINE_PAD else 0.dp,
        divider = divider,
    ) {
        LiuliRowTitleColumn(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        LiuliRadio(selected = selected, enabled = enabled)
    }
}

/**
 * 分段行（契约 §6.5「分段行」）：标题在上、[LiuliSegmented] 满宽在下；内距 12/16。
 * [title] = null 时只有分段控件——组标题已经点了名（外观页「深浅模式」/「透明度」）就别在行里再念一遍（复核 R1 🟡-2）。
 */
@Composable
fun <T> LiuliSegmentRow(
    title: String?,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    LiuliRowBase(
        modifier = modifier,
        minHeight = 0.dp,
        verticalPadding = STACK_PAD_V,
        divider = divider,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            if (title != null) {
                Text(
                    title,
                    style = AppTypography.body.copy(fontSize = TITLE_SIZE, lineHeight = TITLE_LINE),
                    color = AppTheme.colors.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LiuliSegmented(
                options = options,
                selected = selected,
                label = label,
                onSelect = onSelect,
                enabled = enabled,
            )
        }
    }
}
