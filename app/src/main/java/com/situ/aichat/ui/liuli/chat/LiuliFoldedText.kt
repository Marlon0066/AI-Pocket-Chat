package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.liuliTouchHeight

/**
 * 长文折叠（图纸 2026-09-05 卷二C §4.10 · A-1 · 契约 §5.3）：**只对 AI 泡 × 已显形**——排版行数超
 * [FOLD_LINE_LIMIT] 才折，折后露 [FOLD_VISIBLE_LINES] 行 + 底部渐隐带 + 下一行「展开全文」（与时间戳同行）。
 *
 * 展开**只在本会话内记住**（[LiuliFoldState] 是普通 `remember`·重建即忘 = 全部折回·§3.4），展开后不再提供
 * 收起（对版稿只画了展开）。折叠**只裁显示高度**：长按 / 右滑引用 / 双击回应 / 飞入上报 / 递送变身全部照旧。
 *
 * 行数在**测量期**用 `rememberTextMeasurer` 先量后布（§3.1）——`onTextLayout` 回填会先出一帧全高再折，
 * 长文泡上是肉眼可见的一跳。
 */
@Composable
internal fun LiuliFoldableText(
    text: String,
    style: TextStyle,
    color: Color,
    /** 已显形（占位三点期不折·A-1）。 */
    revealed: Boolean,
    /** 用户自己写的不折（A-1）。 */
    isUser: Boolean,
    /** 本会话内已展开过。 */
    expanded: Boolean,
    onExpand: () -> Unit,
    /** 渐隐带的落色 = 泡底色（透明 → 泡色）。 */
    fadeColor: Color,
    stamp: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier.animateContentSize(if (reduceMotion) snap() else AppMotion.gentleSpring()),
    ) {
        val widthPx = constraints.maxWidth
        val eligible = revealed && !isUser && !expanded
        val lineCount = remember(text, style, widthPx, eligible) {
            if (!eligible || widthPx <= 0) {
                0
            } else {
                measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = widthPx)).lineCount
            }
        }
        if (!liuliShouldFold(lineCount, revealed, isUser)) {
            LiuliInlineStampLayout(
                textString = text,
                textStyle = style,
                stamp = stamp,
                text = { Text(text, style = style, color = color) },
            )
            return@BoxWithConstraints
        }
        Column {
            Box {
                Text(
                    text,
                    style = style,
                    color = color,
                    maxLines = FOLD_VISIBLE_LINES,
                    overflow = TextOverflow.Clip,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(LiuliChatGeometry.foldFade)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor))),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                // 复核 R1 🔴-1（REDLINES「a11y 48dp」）：文字链触达 48 居中外溢、版位仍一行字高（戳同行不变）；
                // 外溢的上半截落在渐隐带、下半截落在泡内边距，都不是别人的触达面。48 框隐形，故不给 ripple。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = EXPAND_TOP)
                        .liuliTouchHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onExpand,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(R.string.liuli_expand_full_text),
                        style = ExpandLabelStyle,
                        color = AppTheme.colors.accent.text,
                    )
                }
                stamp()
            }
        }
    }
}

/**
 * 本会话内的展开记账（图纸 §3.4）：**纯瞬态**——只增不减，重建（转屏 / 进程回来）即忘、全部折回。
 * 不入库、不进上下文、不影响任何递送机制。
 */
@Stable
internal class LiuliFoldState {
    private val expanded = mutableStateListOf<String>()

    /** 幂等：同一条重复展开不重复记账（T1-2）。 */
    fun expand(uuid: String) {
        if (uuid !in expanded) expanded.add(uuid)
    }

    fun isExpanded(uuid: String): Boolean = uuid in expanded

    /** 图纸 §3.1 的判据式：行数超阈且本会话未展开过。 */
    fun isFolded(uuid: String, lineCount: Int): Boolean = lineCount > FOLD_LINE_LIMIT && !isExpanded(uuid)

    /** 只供测试观测（生产码一律走 [isExpanded]）。 */
    internal val expandedCount: Int get() = expanded.size
}

/**
 * 「这条该折吗」纯函数（A-1 · T1-2）：**排版行数** > [FOLD_LINE_LIMIT] × 已显形 × 非用户泡。
 * 阈值改这一个常量即可（用户看真机后可调）。
 */
internal fun liuliShouldFold(lineCount: Int, revealed: Boolean, isUser: Boolean): Boolean =
    revealed && !isUser && lineCount > FOLD_LINE_LIMIT

/** 折叠阈值与折后露出的行数（A-1·孤值即打回）。 */
internal const val FOLD_LINE_LIMIT = 12
internal const val FOLD_VISIBLE_LINES = 10

/** 「展开全文」落值（A-1：12.5sp W500 归梯 520·色走 `accent.text`·左对齐；对版稿 `.more{margin-top:2}`）。 */
private val ExpandLabelStyle = AppTypography.snackbarBody.copy(fontWeight = FontWeight(520))
private val EXPAND_TOP = 2.dp
