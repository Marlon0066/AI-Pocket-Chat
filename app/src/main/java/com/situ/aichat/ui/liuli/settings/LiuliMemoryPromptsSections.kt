package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.MacroHighlightTransformation
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues

/** 两枚编辑器的最小高（逐字照暖陶 `MemoryPromptsSections` 的 260 / 140）。 */
private val EXTRACTION_MIN_HEIGHT = 260.dp
private val INJECTION_MIN_HEIGHT = 140.dp
/** 组内块与块的缝。 */
private val BLOCK_GAP = 12.dp
/** 宏行之间的缝与宏 ↔ 释义的缝（逐字照暖陶的 8）；宏名列定宽（最长「{{当前时间}}」13sp 约 88）。 */
private val MACRO_GAP = 8.dp
private val MACRO_COLUMN = 104.dp

/**
 * 记忆提示词两段 + 宏说明（琉璃·图纸 2026-09-06 卷五 §4.1 屏 1b·A-4 ⑤「多行文本域」首用）。
 *
 * 多行组 = 组标题 + 组内整块 [LiuliField]（`minHeight` 撑高 · 内距 16 · 宏高亮走暖陶
 * [MacroHighlightTransformation]，§2.2-2 已把它提为 internal·实现零改）。逐字即写无保存钮（同暖陶）。
 *
 * **零碰**：九个宏字面量与解析格式强耦合（`{{聊天记录}}` 等），本文件里的每一个都从暖陶
 * `MemoryPromptsSections` 逐字复制，改任一处必须两侧同步。
 */

/** 提示词段的写口（与暖陶 `MemoryPromptsSettingsViewModel` 一一对应）。 */
@Immutable
data class LiuliMemoryPromptCallbacks(
    val onExtractionChange: (String) -> Unit,
    val onInjectionChange: (String) -> Unit,
    val onResetExtraction: () -> Unit,
    val onResetInjection: () -> Unit,
)

@Composable
internal fun ColumnScope.liuliMemoryPromptGroups(
    extraction: String,
    injection: String,
    callbacks: LiuliMemoryPromptCallbacks,
) {
    LiuliGroup(
        header = stringResource(R.string.mem_prompts_extraction_title),
        footer = stringResource(R.string.mem_prompts_extraction_macros),
    ) {
        PromptBlock(
            caption = stringResource(R.string.mem_prompts_extraction_desc),
            value = extraction,
            onValueChange = callbacks.onExtractionChange,
            minHeight = EXTRACTION_MIN_HEIGHT,
            onReset = callbacks.onResetExtraction,
        )
    }

    LiuliGroup(
        header = stringResource(R.string.mem_prompts_injection_title),
        footer = stringResource(R.string.mem_prompts_injection_macros),
    ) {
        PromptBlock(
            caption = stringResource(R.string.mem_prompts_injection_desc),
            value = injection,
            onValueChange = callbacks.onInjectionChange,
            minHeight = INJECTION_MIN_HEIGHT,
            onReset = callbacks.onResetInjection,
        )
    }

    LiuliGroup(header = stringResource(R.string.mem_prompts_macros_title)) {
        LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.groupPadH, verticalAlignment = Alignment.Top) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MACRO_GAP)) {
                // 九个宏字面量与解析格式耦合——逐字照暖陶，一个字都不许改。
                MacroRow("{{聊天记录}}", stringResource(R.string.mem_prompts_macro_chatlog))
                MacroRow("{{已有记忆}}", stringResource(R.string.mem_prompts_macro_existing))
                MacroRow("{{当前时间}}", stringResource(R.string.mem_prompts_macro_now))
                MacroRow("{{最大字数}}", stringResource(R.string.mem_prompts_macro_maxchars))
                MacroRow("{{当前字数}}", stringResource(R.string.mem_prompts_macro_curchars))
                MacroRow("{{压缩策略}}", stringResource(R.string.mem_prompts_macro_compress))
                MacroRow("{{记忆内容}}", stringResource(R.string.mem_prompts_macro_content))
                MacroRow("{{char}}", stringResource(R.string.mem_prompts_macro_char))
                MacroRow("{{user}}", stringResource(R.string.mem_prompts_macro_user))
            }
        }
    }
}

/** 一段提示词：说明句 + 整块文本域 + 「恢复默认」文字钮。 */
@Composable
private fun PromptBlock(
    caption: String,
    value: String,
    onValueChange: (String) -> Unit,
    minHeight: Dp,
    onReset: () -> Unit,
) {
    val colors = AppTheme.colors
    LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.groupPadH, verticalAlignment = Alignment.Top) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
            Text(caption, style = AppTypography.secondary, color = colors.text.secondary)
            LiuliField(
                value = value,
                onValueChange = onValueChange,
                singleLine = false,
                minHeight = minHeight,
                visualTransformation = MacroHighlightTransformation(colors.accent.text),
            )
            // 卡内文字钮去掉自带的 12 左右内距，让字与上面的说明句同一条基线（复核 R1）。
            LiuliButton(onClick = onReset, style = LiuliButtonStyle.Text, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(stringResource(R.string.mem_prompts_reset_default))
            }
        }
    }
}

/** 一条宏说明：宏名（钴蓝）+ 释义（次级色）。 */
@Composable
private fun MacroRow(macro: String, description: String) {
    // 宏名定宽成一列：九条宏长短不一，释义跟着宏名尾巴走就参差不齐（复核 R1·用户点名「文案没对齐」）。
    Row(horizontalArrangement = Arrangement.spacedBy(MACRO_GAP)) {
        Text(macro, style = AppTypography.secondary, color = AppTheme.colors.accent.text, modifier = Modifier.width(MACRO_COLUMN))
        Text(description, style = AppTypography.secondary, color = AppTheme.colors.text.secondary)
    }
}
