package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.liuli.glass.LiuliGatedBackdropHost

/**
 * 主页壳的选脸点（图纸 2026-09-06 卷三 A-2）：`AIChatApp` 里那一个 `Box(fillMaxSize)`（NavHost + 悬浮底栏
 * 叠加层）换成本件。
 *
 * - **暖陶** = `Box(modifier) { content(); bottomBar() }`——与换之前的结构逐字等价（NavHost 在前、底栏叠加层
 *   在后、底栏自己的 `align(BottomCenter)` 仍生效），暖陶四屏与 `AppBottomNav` 因此像素零差。
 * - **琉璃** = 门控背景宿主（[LiuliGatedBackdropHost]·底栏这片玻璃靠它拿身后内容）+ [chrome] 的
 *   nested-scroll 连接（缩丸信号·**只挂在琉璃分支**，暖陶不多任何一个节点）。
 */
@Composable
fun LiuliHomeHost(
    chrome: LiuliHomeChrome,
    active: Boolean,
    bottomBar: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliGatedBackdropHost(
            modifier = modifier.nestedScroll(chrome.connection),
            active = active,
            content = content,
            overlay = bottomBar,
        )
    } else {
        Box(modifier) {
            content()
            bottomBar()
        }
    }
}
