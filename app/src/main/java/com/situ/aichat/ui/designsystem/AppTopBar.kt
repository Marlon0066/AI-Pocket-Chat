package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 二级页门楣 = **一道安静的横梁**（六件套草图 2026-07-17 整体过审·M3 清零收官落地）。
 *
 * 取代二级页的 M3 `TopAppBar`。两个状态：
 * - **静止**（`lifted = false`）：**完全无痕**——没有底色、没有分隔线、没有投影，标题像直接印在页面上。
 * - **升起**（`lifted = true`，内容滚起来了）：底色淡入到 [AppColors.surface] raised 的 92%，底缘落一根
 *   0.5dp 发丝。**不做真实背景模糊**（用户已知悉的降格·与已过审的玻璃菜单同口径），也**不加投影**——
 *   「纸浮起」不是「悬空卡」。
 *
 * **背景与发丝画在 [statusBarsPadding] 之前**（修饰链顺序锁定）：升起时这层底色必须一路盖到状态栏，否则
 * 内容会从状态栏区域「漏」上去。
 *
 * **`lifted` 由站点计算传参**，组件不做内部滚动魔法——收编的 75 屏滚动源各异（LazyColumn / verticalScroll /
 * 无滚动三式），显式接线才可测可核。
 *
 * **标题的 heading 语义与返回钮的「返回」文案单源在本组件内**——站点侧不再各写一遍（收编前是 75 份复制）。
 * 返回箭头是白瓷圆钮 [AppTopBarAction] + 自绘雪佛龙 [AppTopBarIcons.Back]（带 `autoMirror`·RTL 自动镜像）。
 *
 * @param title 标题文案（站点侧 `stringResource(...)` 取值，资源 id 逐站不变）。
 * @param onBack null = 这一屏没有返回钮（如底栏 tab 直挂的枢纽页）；标题仍绝对居中。
 * @param backEnabled false = 返回钮**灰掉但仍在原位**（如「创建中禁止退出」）——**绝不实现成 `onBack = null`**，
 *   那会让钮整个消失：「不能退出」与「没有退出」是两回事。禁用口径见 [AppTopBarAction]。
 * @param lifted 内容是否已滚离顶部——接线三式见 M3 清零收官图纸 §4.5。
 * @param actions 右侧动作槽（惯例放 [AppTopBarAction]），null = 无动作节点。
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    lifted: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    BarScaffold(lifted = lifted, modifier = modifier) {
        if (onBack != null) {
            AppTopBarAction(
                icon = AppTopBarIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                // 屏 gutter 恒 20（设计语言 §2.5 军规）；白瓷圆钮触达外溢 +4，故 padding = 20 − 4 = 16。
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = AppSpacing.gutterForRoundButton),
                enabled = backEnabled,
            )
        }
        Text(
            text = title,
            style = AppTypography.titleSmall,
            color = colors.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 双侧 64dp 内边距 = 长标题恒不压到返回钮 / 动作钮。算式（R1 复核订正）：padding 16 作用在
            // **48dp 触达框**上 → 触达框占 16–64、视觉圆占 20–60，标题框正好接在触达框外缘（与视觉圆留 4dp）。
            // ⚠️ 不是「16 + 视觉 40 + 8」——那把 padding 错算在 40dp 视觉盒上了（同 R1 F1 的坑·设计语言 §2.5.3）。
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 64.dp)
                .semantics { heading() },
        )
        if (actions != null) {
            Row(
                // 同返回钮：屏 gutter 恒 20（§2.5 军规），圆钮补偿 +4 → padding 16。
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = AppSpacing.gutterForRoundButton),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/**
 * 顶栏族共用骨架：56dp 横梁 + 静止/升起两态（[AppTopBar] 与 [AppFormBar] 的**唯一**几何实现）。
 *
 * 抽出来的原因：两者共用几何但左槽语义相反（门楣的左槽是「离开」圆钮，表单条的左槽是「放弃改动」
 * 文字钮），硬做成一个组件就得加 `leadingIsText` 之类的形态开关——那是「加参数硬套」。抽骨架
 * 既避免复制第二份几何，又让两个公开组件各自薄。
 *
 * **`internal` 不是 `private`**：Kotlin 的 `private` = 文件私有，两个文件不可能共用同一份；
 * `internal` = 模块私有，设计系统之外看不见，是最接近「包内共用」的可行档（图纸 §11 D-3）。
 *
 * **修饰链顺序锁定**：背景与发丝画在 [statusBarsPadding] **之前**——升起时这层底色必须一路盖到
 * 状态栏，否则内容会从状态栏区域「漏」上去。
 *
 * @param lifted 内容是否已滚离顶部；过渡走 [AppMotion.effectMediumSpring]（[rememberReduceMotion] 时 `snap()`）。
 */
@Composable
internal fun BarScaffold(
    lifted: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val t by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "appBarLift",
    )
    val background = colors.surface.raised.copy(alpha = 0.92f * t)
    val hairline = if (colors.isDark) {
        colors.surface.stroke.copy(alpha = t)
    } else {
        colors.text.primary.copy(alpha = AppElevation.HAIRLINE_ALPHA * t)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind {
                val thickness = AppElevation.hairlineWidth.toPx()
                drawRect(
                    color = hairline,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
                )
            }
            .statusBarsPadding()
            .height(56.dp),
        content = content,
    )
}
