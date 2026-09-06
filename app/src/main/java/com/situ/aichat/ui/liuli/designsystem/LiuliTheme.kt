package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃设计系统 token（第二张脸·契约 FABLE5_THEME_LIULI_PROPOSAL.md §3.3/§4.1）。
 *
 * 定位：琉璃**不是换皮**，是与暖陶永久并行的第二套界面树（`ui/liuli` 包下整棵树）。本文件是它的形状 / 玻璃上文字色
 * 与两个选脸 CompositionLocal 的单源；色相仍走共用的 [com.situ.aichat.ui.designsystem.AppTheme].colors
 * （琉璃色板由 [com.situ.aichat.ui.theme.AIPocketChatTheme] 按 [AppSkin] provide）。
 *
 * 边界（图纸 §2.3）：`ui/liuli` 下的文件绝不 import 暖陶 `App*` **组件**；只许读 `AppTheme`（色）、`AppTypography`、
 * `Palette`、`AppMotion`、`LocalAppHaptics`、`rememberReduceMotion`、[LocalIsDarkTheme]。
 */

/** 当前界面「脸」（由 [com.situ.aichat.ui.theme.AIPocketChatTheme] provide·默认暖陶）。 */
val LocalAppSkin = staticCompositionLocalOf { AppSkin.CLAY }

/** 当前玻璃「透明度」档（同上 provide·默认清透）。玻璃片 [com.situ.aichat.ui.liuli.glass.liuliGlass] 不传档时读它。 */
val LocalGlassTier = staticCompositionLocalOf { GlassTier.CLEAR }

/** 琉璃形状阶（契约 §3.3 表「琉璃」列·Telegram iOS 器型口径）。 */
object LiuliShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(20.dp)
    /** 二级屏的内嵌圆角分组（卷四 §4.1 `groupCorner`）。 */
    val group = RoundedCornerShape(16.dp)
    val bubble = RoundedCornerShape(18.dp)
    val bubbleTailCorner = 5.dp          // 末条尾巴侧角（卷二气泡用）
    val overlay = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/**
 * 玻璃片**之上**的文字 / 图标色（契约 §4.1）。玻璃自身已把身后内容压到近乎中性底，故这两色是字面量而非
 * 语义 token——它们要对「玻璃合成后的底」达标，而不是对某个 surface 达标。
 */
@Immutable
data class LiuliOnGlassColors(val primary: Color, val secondary: Color)

val LiuliOnGlassLight = LiuliOnGlassColors(primary = Color(0xFF111318), secondary = Color(0xFF5F6470))
val LiuliOnGlassDark = LiuliOnGlassColors(primary = Color(0xFFF2F4F8), secondary = Color(0xFFA3A9B5))

/** 琉璃 token 访问器（用法同暖陶的 `AppTheme`）。 */
object LiuliTheme {
    val skin: AppSkin
        @Composable @ReadOnlyComposable get() = LocalAppSkin.current

    val glassTier: GlassTier
        @Composable @ReadOnlyComposable get() = LocalGlassTier.current

    val shapes: LiuliShapes get() = LiuliShapes

    val onGlass: LiuliOnGlassColors
        @Composable @ReadOnlyComposable get() =
            if (LocalIsDarkTheme.current) LiuliOnGlassDark else LiuliOnGlassLight
}
