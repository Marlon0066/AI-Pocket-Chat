package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R

/**
 * Fable-5 编辑页门楣 = **一条表单条**（B 案·用户过审 2026-09-05·对版稿
 * `fable5_artifacts/mockups/form_bar_mockup.html`）。
 *
 * 与 [AppTopBar] **并列而非继承**：两者共用几何（56dp / 居中标题 / 静止-升起，实现单源在
 * [BarScaffold]），但**左槽语义相反**——门楣的左槽是「离开这一屏」（白瓷返回圆钮），表单条的左槽是
 * 「放弃这次改动」（文字钮「取消」）。硬做成一个组件就得加 `leadingIsText` 之类的形态开关，那是
 * 「加参数硬套」；两个薄组件 + 一个共用骨架更干净。
 *
 * 三槽：
 * - **左**：[onCancel] 非空时渲染 [AppButtonStyle.Text] 文字钮。文案取 [cancelText]，null 则走
 *   `R.string.action_cancel`（站点侧要「返回」这类不同措辞时才传）。距屏缘 8dp——文字钮自带横 12dp
 *   内边距，文字左缘正好落在 20dp 的屏 gutter 线上（设计语言 §2.5 军规 + 换算表）。
 * - **中**：标题绝对居中、单行省略、带 `heading()` 语义。双侧内边距 **88dp**（≠门楣的 64）——右槽实心钮距屏缘 20dp
 *   且钮体本身更宽（横内边距 20×2 + 两字 ≈ 68），占位比门楣的圆钮宽得多。
 * - **右**：[trailing] 任意内容。惯例放釉烧主行动钮（`AppButton` 默认 Primary 档），也可以是**纯文字
 *   状态槽**（如「已自动保存」）——状态槽就该是低调灰字、不可点，不做成钮（用户 2026-09-05 拍板⑤）。
 *
 * 无投影（「纸浮起」不是「悬空卡」）；深浅双档全走 token。
 *
 * @param title 标题文案（随态变的站点侧照旧 `stringResource(if (...) a else b)`）。
 * @param lifted 内容是否已滚离顶部——接线三式见 M3 清零收官图纸 §4.5。
 * @param onCancel null = 这一屏没有左槽（标题仍绝对居中）。
 * @param cancelText 左钮文案覆写；null 取 `action_cancel`。
 * @param trailing 右槽内容；null = 无右槽节点。
 */
@Composable
fun AppFormBar(
    title: String,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
    onCancel: (() -> Unit)? = null,
    cancelText: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    BarScaffold(lifted = lifted, modifier = modifier) {
        if (onCancel != null) {
            AppButton(
                onClick = onCancel,
                style = AppButtonStyle.Text,
                // 屏 gutter 恒 20（设计语言 §2.5 军规）；Text 档钮自带横 12dp 内边距，故 padding = 20 − 12 = 8。
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = AppSpacing.gutterForTextButton),
            ) {
                Text(cancelText ?: stringResource(R.string.action_cancel))
            }
        }
        Text(
            text = title,
            style = AppTypography.titleSmall,
            color = colors.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 双侧 88dp = 右槽最宽占位。装机像素实测（gutter/02·density 3）：「保存」钮体 66dp，
            // 故右槽占 20 + 66 = 86dp，**标题框只剩 2dp 余量**（改前是 76 − 4 − 66 = 6dp）。
            // ⚠️ 右槽内容宽超 68dp 就会与标题框重叠（Box 同层兄弟）——换更长标签 / 英文档前须重估这个数。
            // 门楣是 64，那边两端是 40dp 圆钮。
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 88.dp)
                .semantics { heading() },
        )
        if (trailing != null) {
            Row(
                // 屏 gutter 恒 20（§2.5 军规）；实心钮无内部补偿，故 padding 直接给 20。
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = AppSpacing.gutterForSolid),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
    }
}
