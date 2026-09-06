package com.situ.aichat.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Phase 0 全局换装桥：把 Fable-5 暖中性 + 陶土玫 token 灌进 M3 [ColorScheme] 槽位，让现有 110 文件
 * `MaterialTheme.*` 读取点一夜换暖装（未迁移屏也不再是工程师风 Monet），压缩阴阳脸窗口。
 * 已迁移组件直接读 [AppTheme].colors，不经 M3。本桥属设计系统基建（lint 围栏白名单），非 feature 代码。
 */
internal fun brandLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Palette.Clay,
    onPrimary = Palette.OnClayInk, // 深墨字 on 陶土填充（微信式·白字 2.96:1 不达 4.5·WCAG 决议）
    primaryContainer = Palette.ClayWhisper,
    onPrimaryContainer = Palette.ClayInk,
    secondary = Palette.ClayDeep, // 陶土功能深档（白字 5.3:1·中间调 #A8765F 死区不可作文字底）
    onSecondary = Palette.White,
    secondaryContainer = Palette.Linen,
    onSecondaryContainer = Palette.Ink,
    tertiary = Palette.Gold,
    onTertiary = Palette.White,
    tertiaryContainer = Palette.WarnContainer,
    onTertiaryContainer = Palette.OnWarn,
    background = Palette.Porcelain,
    onBackground = Palette.Ink,
    // 沉浸决议（2026-06-13 用户拍板）：surface=background 同色——顶栏/列表等铬面与底无缝（微信式整屏一底），
    // 白色只留给「内容纸张」（AI 气泡/卡片走 AppTheme.colors.surface.raised，不经此槽位）。
    surface = Palette.Porcelain,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Linen,
    onSurfaceVariant = Palette.InkSoft,
    surfaceTint = Palette.Clay,
    outline = Palette.InkFaint,
    outlineVariant = Palette.LinenDeep,
    error = Palette.OnError,
    onError = Palette.White,
    errorContainer = Palette.ErrorContainer,
    onErrorContainer = Palette.OnError,
    inverseSurface = Palette.Ink,
    inverseOnSurface = Palette.Porcelain,
    inversePrimary = Palette.ClayLight,
    scrim = Palette.Scrim,
    // M3 tonal 面阶（NavigationBar/Sheet/Menu/SearchBar 等读 surfaceContainer 族——不映射会落回
    // 基线薰衣草灰）：暖中性等感知步长，仅桥接用不进 Palette/semantic 层。
    surfaceBright = Palette.Porcelain,
    surfaceDim = Color(0xFFE0D9CF),
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Color(0xFFF6F1EA),
    surfaceContainer = Palette.Linen,
    surfaceContainerHigh = Color(0xFFECE5DB),
    surfaceContainerHighest = Color(0xFFE7E0D5),
)

internal fun brandDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Palette.Clay, // 深档填充也保浅陶配深墨字（#A8765F 死区配深字仅 3.71:1）
    onPrimary = Palette.OnClayInk,
    primaryContainer = Palette.ClayWhisperDark,
    onPrimaryContainer = Palette.ClayCream,
    secondary = Palette.ClayLight,
    onSecondary = Palette.OnClayInk,
    secondaryContainer = Palette.Bark,
    onSecondaryContainer = Palette.Cream,
    tertiary = Palette.GoldDark,
    onTertiary = Palette.Espresso,
    tertiaryContainer = Palette.WarnContainerDark,
    onTertiaryContainer = Palette.OnWarnDark,
    background = Palette.Espresso,
    onBackground = Palette.Cream,
    // 沉浸决议：深档同口径 surface=background（深暖灰一底到边）。
    surface = Palette.Espresso,
    onSurface = Palette.Cream,
    surfaceVariant = Palette.Bark,
    onSurfaceVariant = Palette.Sand,
    surfaceTint = Palette.ClayDark,
    outline = Palette.Taupe,
    outlineVariant = Palette.BarkLine,
    error = Palette.OnErrorDark,
    onError = Palette.Espresso,
    errorContainer = Palette.ErrorContainerDark,
    onErrorContainer = Palette.OnErrorDark,
    inverseSurface = Palette.Cream,
    inverseOnSurface = Palette.Espresso,
    inversePrimary = Palette.Clay,
    scrim = Palette.Scrim,
    surfaceBright = Color(0xFF3A342C),
    surfaceDim = Palette.Espresso,
    surfaceContainerLowest = Color(0xFF0F0C0A),
    surfaceContainerLow = Palette.Coffee,
    surfaceContainer = Color(0xFF211D18),
    surfaceContainerHigh = Palette.BarkLine,
    surfaceContainerHighest = Color(0xFF36312A),
)

/**
 * 琉璃主题（第二张脸·见 FABLE5_THEME_LIULI_PROPOSAL.md §4.2）M3 换装桥·昼档（冷灰瓷白 + 钴蓝）。
 * 让未迁移屏的 `MaterialTheme.*` 也变琉璃色（D-15 甲：琉璃色 + 暖陶器型）；已迁移组件直接读 [AppTheme].colors。
 * tonal 面阶为冷中性等感知步长（仅桥接用·不进 Palette/semantic）。
 */
internal fun brandLiuliLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Palette.Cobalt26,
    onPrimary = Palette.White, // 白字 on 钴蓝填充（4.6·达标）
    primaryContainer = Palette.Cobalt26Container,
    onPrimaryContainer = Palette.Cobalt26OnContainer,
    secondary = Palette.Cobalt26Text, // 钴蓝功能深档（白字达标·on 瓷白作文字达 4.5）
    onSecondary = Palette.White,
    secondaryContainer = Palette.GlassSunken,
    onSecondaryContainer = Palette.InkCool,
    tertiary = Palette.Gold,
    onTertiary = Palette.White,
    tertiaryContainer = Palette.WarnContainer,
    onTertiaryContainer = Palette.OnWarn,
    background = Palette.GlassMist,
    onBackground = Palette.InkCool,
    // 沉浸决议：surface=background 同色（瓷白整屏一底·白色只留内容纸张走 AppTheme.colors.surface.raised）。
    surface = Palette.GlassMist,
    onSurface = Palette.InkCool,
    surfaceVariant = Palette.GlassSunken,
    onSurfaceVariant = Palette.InkCoolSoft,
    surfaceTint = Palette.Cobalt26,
    outline = Palette.InkCoolFaint,
    outlineVariant = Color(0xFFD9DDE6),
    error = Palette.OnError,
    onError = Palette.White,
    errorContainer = Palette.ErrorContainer,
    onErrorContainer = Palette.OnError,
    inverseSurface = Palette.InkCool,
    inverseOnSurface = Palette.GlassMist,
    inversePrimary = Palette.Cobalt26GradStart,
    scrim = Palette.Scrim,
    surfaceBright = Palette.GlassMist,
    surfaceDim = Color(0xFFDDE0E8),
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Color(0xFFF0F2F7),
    surfaceContainer = Palette.GlassSunken,
    surfaceContainerHigh = Color(0xFFE1E4EC),
    surfaceContainerHighest = Color(0xFFD9DDE6),
)

/**
 * 琉璃主题 M3 换装桥·夜档（近黑非纯黑 D-9 + 提亮钴蓝）。
 */
internal fun brandLiuliDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Palette.Cobalt26GradStart, // 渐变起点钴蓝配白字 4.6（M3 实底钮·Cobalt26Bright 留给 surfaceTint/装饰）
    onPrimary = Palette.White,
    primaryContainer = Palette.Cobalt26ContainerDark,
    onPrimaryContainer = Palette.Cobalt26OnContainerDark,
    secondary = Palette.Cobalt26TextDark,
    onSecondary = Palette.NightGlass,
    secondaryContainer = Palette.NightGlassSunken,
    onSecondaryContainer = Palette.MoonWhite,
    tertiary = Palette.GoldDark,
    onTertiary = Palette.NightGlass,
    tertiaryContainer = Palette.WarnContainerDark,
    onTertiaryContainer = Palette.OnWarnDark,
    background = Palette.NightGlass,
    onBackground = Palette.MoonWhite,
    surface = Palette.NightGlass,
    onSurface = Palette.MoonWhite,
    surfaceVariant = Palette.NightGlassSunken,
    onSurfaceVariant = Palette.MoonWhiteSoft,
    surfaceTint = Palette.Cobalt26Bright,
    outline = Palette.MoonWhiteFaint,
    outlineVariant = Palette.NightGlassStroke,
    error = Palette.OnErrorDark,
    onError = Palette.NightGlass,
    errorContainer = Palette.ErrorContainerDark,
    onErrorContainer = Palette.OnErrorDark,
    inverseSurface = Palette.MoonWhite,
    inverseOnSurface = Palette.NightGlass,
    inversePrimary = Palette.Cobalt26,
    scrim = Palette.Scrim,
    surfaceBright = Color(0xFF2C3240),
    surfaceDim = Palette.NightGlass,
    surfaceContainerLowest = Color(0xFF07090D),
    surfaceContainerLow = Palette.NightGlassRaised,
    surfaceContainer = Color(0xFF1A1E26),
    surfaceContainerHigh = Palette.NightGlassStroke,
    surfaceContainerHighest = Color(0xFF343B48),
)
