package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.page.LiuliRadioRow

/**
 * 语言弹窗（琉璃版·逐字继承暖陶 `LanguageDialog`·F2 :431）：只两项「简体中文 / English」
 * （i18n Phase 0 已移除「跟随系统」），点一项即回调并关窗；底部只有取消。
 */
@Composable
internal fun LiuliLanguageDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val options = listOf(
        "zh-CN" to R.string.lang_option_zh,
        "en" to R.string.lang_option_en,
    )
    LiuliDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_language),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            options.forEachIndexed { index, (tag, labelRes) ->
                LiuliRadioRow(
                    title = stringResource(labelRes),
                    selected = tag == current,
                    onSelect = { onSelect(tag) },
                    divider = index > 0,
                )
            }
        },
    )
}
