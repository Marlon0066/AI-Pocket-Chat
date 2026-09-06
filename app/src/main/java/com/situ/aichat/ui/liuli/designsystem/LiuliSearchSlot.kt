package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.liuliFootprint

/** 搜索槽几何（图纸 2026-09-06 卷三 A-14 / §3.2·对版稿 `.srch`）。 */
private val SLOT_HEIGHT = 38.dp
private val SLOT_PAD_H = 14.dp
private val SLOT_GAP = 8.dp
private val LEADING_ICON = 16.dp
private val CLEAR_CIRCLE = 20.dp
private val CLEAR_ICON = 12.dp
private const val CLEAR_CIRCLE_ALPHA = 0.18f

/**
 * 琉璃搜索槽（图纸 2026-09-06 卷三 A-14 · 契约 §7.2 点名的 `LiuliSearchField`）。
 *
 * **内容层的件，不是玻璃**（契约 §3.1 #2：玻璃只在导航层）——38 高 pill、`surface.sunken` 纸面沉底、
 * 无发丝无玻璃。底座是 foundation [BasicTextField]（IME / 光标 / 选择由它保证），M3 `TextField` 不碰（§9 ⑤）。
 *
 * 清除钮视觉 20、触达 48（[liuliFootprint] 外溢·不撑高 38 的槽）。
 */
@Composable
fun LiuliSearchSlot(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val textStyle = AppTypography.listPreview.copy(color = colors.text.primary)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.accent.text),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .height(SLOT_HEIGHT)
                    .clip(LiuliShapes.pill)
                    .background(colors.surface.sunken)
                    .padding(horizontal = SLOT_PAD_H),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = colors.text.tertiary, modifier = Modifier.size(LEADING_ICON))
                Spacer(Modifier.width(SLOT_GAP))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = colors.text.tertiary, maxLines = 1)
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Spacer(Modifier.width(SLOT_GAP))
                    // 触达 48 外溢、版位仍是视觉 20（同 `LiuliDraftBar` 的圆钮打法·PITFALLS §1d）。
                    Box(
                        modifier = Modifier
                            .liuliFootprint(CLEAR_CIRCLE)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = clearContentDescription,
                                onClick = { onValueChange("") },
                            )
                            .semantics { contentDescription = clearContentDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(CLEAR_CIRCLE)
                                .clip(CircleShape)
                                .background(colors.text.tertiary.copy(alpha = CLEAR_CIRCLE_ALPHA)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = colors.text.secondary, modifier = Modifier.size(CLEAR_ICON))
                        }
                    }
                }
            }
        },
    )
}
