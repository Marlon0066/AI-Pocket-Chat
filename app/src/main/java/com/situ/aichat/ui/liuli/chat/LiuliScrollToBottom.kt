package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.page.liuliFootprint

/**
 * 回底钮（图纸 2026-09-05 卷二A §4.6）：40dp 玻璃圆 + 向下箭头，右 14dp、**底缘 = 输入区顶 + 12dp**。
 * 显隐条件与动作照抄暖陶（`showScrollDown` = 视觉底部第一条不是最新；点击交协调员单飞贴底）。
 *
 * ⚠️ 列表区的底边是**窗口底**（输入区是 overlay 层的浮件，不占列表区高度），所以「输入区顶 + 12dp」
 * 换算成本件的底 padding = 输入区 overlay **实测高** + 间隙 12 + 导航栏 inset
 * = [LiuliChatGeometry.listBottomPadding] + navBar，与列表 `contentPadding.bottom` 同一个数
 * （C4 装机量测抓到：写死 12dp 会让钮沉到输入区背后·图纸 §4.7 零重叠 ④；复核 R1 🔴-1 再改成跟随实测高，
 * 引用条 / 多行输入长高时钮同升）。触达框 48dp 不占版（[liuliFootprint]）→ 视觉底缘与输入区顶恰 12dp。
 */
@Composable
internal fun BoxScope.LiuliScrollToBottom(
    visible: Boolean,
    reduceMotion: Boolean,
    bottomPadding: Dp,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = LiuliChatGeometry.scrollFabEnd, bottom = bottomPadding),
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
        exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
    ) {
        LiuliCircleButton(
            onClick = onClick,
            contentDescription = stringResource(R.string.a11y_scroll_to_bottom),
            modifier = Modifier.liuliFootprint(40.dp),
            size = 40.dp,
        ) {
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = LocalContentColor.current, // 圆钮甲：跟圆钮走（钴蓝）
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
