package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Fable-5 自研通用媒体/详情列表行（去谷歌化批一过审）——取代 M3 `ListItem`。
 *
 * 专补 [AppSettingsRow] 未覆盖的结构：44dp 角色头像 / 表情缩略图等自定义前导区、等宽正则副标题、
 * 以及右侧多控件槽（开关 + 图标钮 / GIF 徽标 + 文字钮）。水平间距遵循设计语言 20dp gutter 与 12dp 缝。
 */
@Composable
fun AppMediaRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleFontFamily: FontFamily? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = AppSpacing.screenGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = AppTypography.label,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = if (subtitleFontFamily != null) {
                        AppTypography.caption.copy(fontFamily = subtitleFontFamily)
                    } else {
                        AppTypography.caption
                    },
                    color = colors.text.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}
