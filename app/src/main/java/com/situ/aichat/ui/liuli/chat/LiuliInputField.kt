package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃输入槽（图纸 2026-09-05 卷二A §4.5 · 契约 §5.2「输入区 A · 分体胶囊」）：一片玻璃胶囊裹住
 * `BasicTextField`（IME / 光标 / 选择手柄零重写，只换视觉装饰——同暖陶 `ChatInputField` 的做法）。
 *
 * 单行 44dp 胶囊；多行（上限 5 行）壳变 22dp 圆角矩形、**向上长高**。占位文案取 `chat_input_placeholder`
 * ——**baselineprofile 哨兵，只读不动**（REDLINES §1）。
 */
@Composable
internal fun LiuliInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** M3b ④：握手 / 飞行期抑制占位符视觉（本卷闸恒关，参数保留以免卷二B 再改签名）。 */
    hidePlaceholder: Boolean = false,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val dark = LocalIsDarkTheme.current
    // 折行后壳从胶囊变 22dp 圆角矩形（pill 是 50%，长高后会跟着变成大椭圆）。
    var multiline by remember { mutableStateOf(false) }
    val shape = if (multiline) RoundedCornerShape(MultilineCorner) else LiuliShapes.pill
    // 审计 Y5① 照抄：空态给编辑框挂占位文案语义（读屏聚焦不只听到裸「编辑框」）。
    val placeholderCd = stringResource(R.string.chat_input_placeholder)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { if (value.isEmpty()) contentDescription = placeholderCd },
        textStyle = AppTypography.body.copy(color = onGlass.primary),
        maxLines = MAX_LINES,
        cursorBrush = SolidColor(colors.accent.primary),
        onTextLayout = { multiline = it.lineCount > 1 },
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .liuliGlass(shape, dark = dark)
                    .heightIn(min = LiuliChatGeometry.inputPieceSize)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && !hidePlaceholder) {
                    Text(placeholderCd, style = AppTypography.body, color = onGlass.secondary)
                }
                innerTextField()
            }
        },
    )
}

/** 多行上限（契约 §5.2 锁 5 行）与多行壳圆角（图纸 §4.5 锁 22dp）。 */
private const val MAX_LINES = 5
private val MultilineCorner = 22.dp
