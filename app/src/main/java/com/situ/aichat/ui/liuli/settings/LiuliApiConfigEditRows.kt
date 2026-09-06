package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.situ.aichat.R
import com.situ.aichat.data.model.KnownModelCapabilityTable
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.page.LiuliInputRow
import com.situ.aichat.ui.liuli.page.LiuliMenuRow
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry

/**
 * API 编辑 / 新建两屏共用的行族（琉璃·图纸 2026-09-06 卷五 §4.1 屏 7 / 屏 8）。
 *
 * 三枚：[LiuliApiKeyRow]（带眼睛的密钥行·暖陶 `ApiKeyField` 的对应件）· [liuliKnownCapabilityHint]
 * （静态能力表提示句·**纯函数自写同值**，暖陶那枚 `KnownCapabilityHint` 是 Composable 长相不出借）·
 * [LiuliModePickerRow]（能力档下拉行·暖陶 `ModePickerRow` 的对应件）。
 */

/** 眼睛钮的视觉直径 / 图标（同步进钮 28 / 16）。 */
private val EYE_BUTTON = LiuliPageGeometry.stepperButton
private val EYE_ICON = LiuliPageGeometry.stepperIcon

/**
 * 带「眼睛」的密钥输入行：默认打码（[PasswordVisualTransformation]），点眼睛切明文。
 * 可见性用 `rememberSaveable` 存（逐字照暖陶 `ApiKeyField.kt:36`——转屏后不该偷偷把明文亮出来，
 * 也不该把用户刚点开的明文又藏回去）。
 */
@Composable
internal fun LiuliApiKeyRow(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = DEFAULT_API_KEY_LABEL,
    supportingText: String? = null,
    divider: Boolean = true,
    /** 占位（TTS「已设置，留空则不修改」拆到这里·整句塞 96 宽标签列会折成三行·复核 R1 C6）。 */
    placeholder: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    LiuliInputRow(
        label = label,
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        supportingText = supportingText,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        divider = divider,
        trailing = {
            // 版位恰 28（外层盒），48 触达由圆钮自带的 minimumInteractiveComponentSize 居中外溢。
            Box(Modifier.size(EYE_BUTTON), contentAlignment = Alignment.Center) {
                LiuliCircleButton(
                    onClick = { visible = !visible },
                    contentDescription = stringResource(if (visible) R.string.api_key_hide else R.string.api_key_show),
                    size = EYE_BUTTON,
                ) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(EYE_ICON),
                    )
                }
            }
        },
    )
}

/** 密钥行的默认标签（暖陶 `ApiKeyField.kt:33` 的硬编码同值·A-6）。 */
private const val DEFAULT_API_KEY_LABEL = "API Key"

/**
 * 静态已知能力表的即时提示句（模型格下方那一行）。**自写同值**：值与文案逐字照暖陶
 * `ApiConfigScreen.KnownCapabilityHint`（`:314–330`·同一批资源键、同一个 [KnownModelCapabilityTable]），
 * 只是这里返回字符串由调用方决定放哪（组脚注），不自带长相。查不到该模型时返回 null（不出这一行）。
 */
@Composable
internal fun liuliKnownCapabilityHint(model: String): String? {
    val known = KnownModelCapabilityTable.lookup(model) ?: return null
    val parts = buildList {
        if (known.isThinking) add(stringResource(R.string.api_capability_thinking))
        if (known.hasVision) add(stringResource(R.string.api_capability_vision))
        if (known.hasToolCalling) add(stringResource(R.string.api_capability_tool))
        if (known.hasAudioInput) add(stringResource(R.string.api_capability_audio))
    }
    return if (parts.isEmpty()) {
        stringResource(R.string.api_capability_known_none)
    } else {
        stringResource(R.string.api_capability_known_prefix) +
            parts.joinToString(stringResource(R.string.api_capability_separator))
    }
}

/**
 * 能力档下拉行（暖陶 `ModePickerRow` 的对应件）：标题 +（可选）检测角标作副标 + 右值 + 菜单。
 *
 * 暖陶把角标放在标题行右端、下拉另起一行；琉璃的行本身就是下拉（右值 + chevron），角标便挪到**副标**位
 * ——同一句话、同一处信息，不多占一行。
 */
@Composable
internal fun <T> LiuliModePickerRow(
    title: String,
    badge: String?,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    val selectedLabel = options.firstOrNull { it.second == selected }?.first.orEmpty()
    LiuliMenuRow(
        title = title,
        subtitle = badge,
        value = selectedLabel,
        options = options.map { (label, value) ->
            LiuliMenuEntry(text = label, selected = value == selected, onClick = { onSelect(value) })
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        divider = divider,
    )
}

/**
 * 通用「自动 / 开 / 关」三档标签（暖陶 `capabilityModeOptions` 的对应件·同三枚资源键）。
 */
@Composable
internal fun <T> liuliCapabilityModeOptions(
    build: (String, String, String) -> List<Pair<String, T>>,
): List<Pair<String, T>> = build(
    stringResource(R.string.api_cmode_auto),
    stringResource(R.string.api_cmode_enabled),
    stringResource(R.string.api_cmode_disabled),
)

/** 思考模型检测结果的措辞（暖陶 `thinkingDetectedText` 同值·同三枚资源键）。 */
@Composable
internal fun liuliThinkingDetectedText(detected: Int): String = when (detected) {
    1 -> stringResource(R.string.api_det_thinking_model)
    0 -> stringResource(R.string.api_det_standard_model)
    else -> stringResource(R.string.api_det_undetermined)
}

/** 视觉 / 语音能力检测结果的措辞（暖陶 `capDetectedText` 同值）。 */
@Composable
internal fun liuliCapDetectedText(detected: Int): String = when (detected) {
    1 -> stringResource(R.string.api_cap_supported)
    0 -> stringResource(R.string.api_cap_unsupported)
    else -> stringResource(R.string.api_cap_undetected)
}

/** 思考强度档位名（暖陶 `levelLabel` 同值）。 */
@Composable
internal fun liuliLevelLabel(level: ThinkingBudgetLevel): String = when (level) {
    ThinkingBudgetLevel.OFF -> stringResource(R.string.api_level_off)
    ThinkingBudgetLevel.AUTO -> stringResource(R.string.api_level_auto)
    ThinkingBudgetLevel.LOW -> stringResource(R.string.api_level_low)
    ThinkingBudgetLevel.MEDIUM -> stringResource(R.string.api_level_medium)
    ThinkingBudgetLevel.HIGH -> stringResource(R.string.api_level_high)
}

/** 思考强度档位的一句说明（暖陶 `levelHint` 同值）。 */
@Composable
internal fun liuliLevelHint(level: ThinkingBudgetLevel): String = when (level) {
    ThinkingBudgetLevel.OFF -> stringResource(R.string.api_levelhint_off)
    ThinkingBudgetLevel.AUTO -> stringResource(R.string.api_levelhint_auto)
    ThinkingBudgetLevel.LOW -> stringResource(R.string.api_levelhint_low)
    ThinkingBudgetLevel.MEDIUM -> stringResource(R.string.api_levelhint_medium)
    ThinkingBudgetLevel.HIGH -> stringResource(R.string.api_levelhint_high)
}

/** 「模型名」标签（暖陶 `ApiConfigScreen.kt:580` 的硬编码同值·A-6·两屏共用）。 */
internal object LiuliApiText {
    const val MODEL_LABEL = "模型名"
}
