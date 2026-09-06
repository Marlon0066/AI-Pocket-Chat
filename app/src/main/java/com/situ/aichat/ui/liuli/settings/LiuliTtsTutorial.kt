package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.ui.settings.tutorialContent

/** 教程卡内距 / 行间缝 / 雪佛龙尺寸（逐字照暖陶 `TtsProviderTutorial` 的 12 / 6 / 默认 24）。 */
private val CARD_PAD = 12.dp
private val CARD_GAP = 6.dp
private val CHEVRON = 20.dp

/**
 * 语音引擎教程卡（琉璃·图纸 2026-09-06 卷五 §4.1 屏 11「教程折叠组」）。
 *
 * 正文借暖陶 [tutorialContent]（**纯函数返回一对字符串**·§2.2-2 已提 internal·实现零改），
 * 五段长中文教程因此只有一份。折叠行为逐字照暖陶：整卡可点、`remember(provider)` 换引擎即收起。
 */
@Composable
internal fun LiuliTtsTutorialCard(provider: TtsProviderType, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    var expanded by remember(provider) { mutableStateOf(false) }
    val (header, body) = tutorialContent(provider)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liuliCardSurface()
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(CARD_PAD),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                header,
                style = AppTypography.bodyEmphasis,
                color = colors.text.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.text.tertiary,
                modifier = Modifier.size(CHEVRON),
            )
        }
        // `if` 而非 AnimatedVisibility：spacedBy 栈里隐着的 0 高节点会留一条幽灵缝（§9 ⑤·暖陶也是 if）。
        if (expanded) {
            Text(body, style = AppTypography.secondary, color = colors.text.secondary)
        }
    }
}
