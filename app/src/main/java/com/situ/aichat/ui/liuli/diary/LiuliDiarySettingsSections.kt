package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.ui.diary.DiaryCharacterChoice
import com.situ.aichat.ui.diary.DiarySettingsState
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliMenuRow
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import androidx.compose.material3.Text
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.AppTheme
import androidx.compose.ui.unit.dp

/** 日记设置页的写口（与暖陶 `DiarySettingsViewModel` 一一对应）。 */
@Immutable
data class LiuliDiarySettingsCallbacks(
    val onSetAutoGenerate: (Boolean) -> Unit,
    val onOpenTimePicker: () -> Unit,
    val onSetAutoPublish: (Boolean) -> Unit,
    val onSetPetAutoGenerate: (Boolean) -> Unit,
    val onSelectExchangePartner: (String) -> Unit,
    val onSetCommentEnabled: (Boolean) -> Unit,
    val onSetCommentDelay: (Int) -> Unit,
    val onToggleCharacter: (String) -> Unit,
    val onOpenWritingRules: () -> Unit,
)

/**
 * 日记设置四组（琉璃·图纸 2026-09-06 卷五 §4.1 屏 14）。节序 / 三处门控 / 文案逐字继承暖陶
 * `DiarySettingsScreen`：自动生成开 → 才出「生成时间 / 直接发布」；评论开 → 才出「延迟 + 角色清单」；
 * 宠物日记那一枚**不受门控**（独立开关）。
 */
/** 「可评论的角色」小标题行 / 说明脚注行的上下内距（比整行 52 矮一档）。 */
private val CAPTION_PAD_V = 10.dp

@Composable
internal fun ColumnScope.liuliDiarySettingsGroups(
    state: DiarySettingsState,
    partnerMenuOpen: Boolean,
    onPartnerMenuOpenChange: (Boolean) -> Unit,
    callbacks: LiuliDiarySettingsCallbacks,
) {
    LiuliGroup(
        header = stringResource(R.string.diary_settings_autogen_header),
        footer = stringResource(R.string.diary_settings_autogen_footer),
    ) {
        LiuliToggleRow(
            title = stringResource(R.string.diary_settings_autogen_label),
            checked = state.autoGenerateEnabled,
            onCheckedChange = callbacks.onSetAutoGenerate,
            divider = false,
        )
        if (state.autoGenerateEnabled) {
            LiuliValueRow(
                title = stringResource(R.string.diary_settings_autogen_time),
                value = state.autoGenerateTime,
                onClick = callbacks.onOpenTimePicker,
            )
            // R3 评论区活化（O3 锁定·默认关）：自动日记直接发布 = 跳过草稿、发布即走角色评论。
            LiuliToggleRow(
                title = stringResource(R.string.diary_settings_auto_publish_label),
                subtitle = stringResource(R.string.diary_settings_auto_publish_footer),
                checked = state.autoPublishEnabled,
                onCheckedChange = callbacks.onSetAutoPublish,
            )
        }
        // 宠物日记自动生成（独立开关·不受用户日记开关门控）。
        LiuliToggleRow(
            title = stringResource(R.string.diary_settings_pet_autogen_label),
            checked = state.petAutoGenerateEnabled,
            onCheckedChange = callbacks.onSetPetAutoGenerate,
        )
    }

    // R4 交换日记：笔友选择（空 = 自动「当天聊得最多」·O1 锁定「兼有」）。
    LiuliGroup(
        header = stringResource(R.string.diary_settings_exchange_header),
        footer = stringResource(R.string.diary_settings_exchange_footer),
    ) {
        val autoLabel = stringResource(R.string.diary_settings_exchange_auto)
        val autoSelected = state.exchangePartnerUuid.isEmpty() ||
            state.characters.none { it.uuid == state.exchangePartnerUuid }
        LiuliMenuRow(
            title = stringResource(R.string.diary_settings_exchange_partner),
            // uuid 失配回落「自动」显示（逐字照暖陶 `ExchangePartnerRow`）。
            value = state.characters.firstOrNull { it.uuid == state.exchangePartnerUuid }?.name ?: autoLabel,
            options = buildList {
                add(LiuliMenuEntry(text = autoLabel, selected = autoSelected, onClick = { callbacks.onSelectExchangePartner("") }))
                state.characters.forEach { ch ->
                    add(
                        LiuliMenuEntry(
                            text = ch.name,
                            selected = ch.uuid == state.exchangePartnerUuid,
                            onClick = { callbacks.onSelectExchangePartner(ch.uuid) },
                        ),
                    )
                }
            },
            expanded = partnerMenuOpen,
            onExpandedChange = onPartnerMenuOpenChange,
            divider = false,
        )
    }

    LiuliGroup(
        header = stringResource(R.string.diary_settings_interaction_header),
        footer = stringResource(R.string.diary_settings_comment_footer),
    ) {
        LiuliToggleRow(
            title = stringResource(R.string.diary_settings_comment_label),
            checked = state.commentEnabled,
            onCheckedChange = callbacks.onSetCommentEnabled,
            divider = false,
        )
        if (state.commentEnabled) {
            LiuliSliderRow(
                title = stringResource(R.string.diary_settings_comment_delay),
                valueLabel = stringResource(R.string.diary_settings_comment_delay_value, state.commentDelay),
                value = state.commentDelay.toFloat(),
                valueRange = 1f..15f,
                steps = 13,
                onValueChange = { callbacks.onSetCommentDelay(it.toInt()) },
            )
            if (state.characters.isEmpty()) {
                LiuliValueRow(
                    title = stringResource(R.string.diary_settings_comment_chars),
                    value = stringResource(R.string.diary_settings_comment_chars_all),
                )
            } else {
                // 空选中集 = 全部（对齐 iOS）：小标题一行 + 每角色一行勾选（选中打勾在右值位）+ 说明脚注
                //（暖陶 :148–183·复核 R1：小标题原拿空值行冒充 = 一行空白 52 高；`diary_settings_chars_footer` 曾被丢）。
                LiuliRowBase(verticalPadding = CAPTION_PAD_V) {
                    Text(
                        stringResource(R.string.diary_settings_comment_chars),
                        style = AppTypography.secondary,
                        color = AppTheme.colors.text.secondary,
                    )
                }
                state.characters.forEach { ch ->
                    LiuliCharacterPickRow(ch, callbacks.onToggleCharacter)
                }
                LiuliRowBase(verticalPadding = CAPTION_PAD_V) {
                    Text(
                        stringResource(R.string.diary_settings_chars_footer),
                        style = AppTypography.secondary,
                        color = AppTheme.colors.text.tertiary,
                    )
                }
            }
        }
    }

    // 写作规则（2026-09-05·图纸 §4.1）：篇幅 / 人称 / 文风 / 补充规则两套 + 只读预览，另起一屏。
    LiuliGroup(
        header = stringResource(R.string.diary_settings_rules_header),
        footer = stringResource(R.string.diary_settings_rules_footer),
    ) {
        LiuliNavRow(
            title = stringResource(R.string.diary_settings_rules_entry),
            onClick = callbacks.onOpenWritingRules,
            divider = false,
        )
    }
}

/**
 * 一行角色勾选（整行可点 = 翻这个角色的允许评论位）。用 [LiuliToggleRow] 的语义会念成「开关」，
 * 而这是**多选清单**，故走 [LiuliValueRow] + 选中时右值给一枚「✓」的文字位——保持读屏能听出选没选中。
 */
@Composable
private fun LiuliCharacterPickRow(choice: DiaryCharacterChoice, onToggle: (String) -> Unit) {
    LiuliValueRow(
        title = choice.name,
        value = if (choice.selected) CHECK_MARK else "",
        onClick = { onToggle(choice.uuid) },
        modifier = Modifier,
    )
}

/** 勾选标记（暖陶用 `Icons.Filled.Check` 图标·琉璃用同义的字符让它进右值槽·读屏可读）。 */
private const val CHECK_MARK = "✓"
