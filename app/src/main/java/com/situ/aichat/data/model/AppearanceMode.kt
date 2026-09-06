package com.situ.aichat.data.model

/**
 * 深浅外观模式（1:1 iOS `Models/AppSettings.swift` 的 `AppearanceMode`，raw 串对齐 "system"/"light"/"dark"）。
 *
 * iOS 把 `AppearanceMode.colorScheme` 映射为 SwiftUI `preferredColorScheme`（nil = 跟随系统）；
 * 安卓这里映射为 Compose 的 `darkTheme: Boolean`——跟随系统时由 `isSystemInDarkTheme()` 决定。
 *
 * 纯枚举 + 纯函数，无 Compose 依赖，便于单测反推 iOS 语义。
 */
enum class AppearanceMode(val raw: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /**
     * 解析为是否走深色（纯函数，反推 iOS `colorScheme` 语义）：
     * 跟随系统 → 交给系统 `systemInDark`、浅色 → false、深色 → true。
     */
    fun resolveDarkTheme(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** 解析持久化 raw 串，未知/空回退跟随系统（1:1 iOS `AppearanceMode(rawValue:) ?? .system`）。 */
        fun fromRaw(raw: String?): AppearanceMode =
            entries.firstOrNull { it.raw == raw } ?: SYSTEM
    }
}

/**
 * 界面「脸」（琉璃第二张脸·见 FABLE5_THEME_LIULI_PROPOSAL.md §7.1）。与 [AppearanceMode] 深浅**正交**：
 * 脸管配色 + 器型家族（暖陶 / 琉璃），深浅管明暗，两两组合成各自的 AppColors。
 *
 * raw 串持久化（DataStore key 仍是历史遗留的 `"theme_palette"`，值域 `"clay"` / `"liuli"`）；未知/空回退暖陶
 * ——老用户存的 `"qinghua"`（青花已推翻）由此静默回退暖陶，**不做迁移**。
 */
enum class AppSkin(val raw: String) {
    CLAY("clay"),   // 暖陶（默认·设计语言主强调 #BE8A76·第一张脸）
    LIULI("liuli"); // 琉璃（液态玻璃·冷灰瓷白 + 钴蓝·第二张脸）

    companion object {
        fun fromRaw(raw: String?): AppSkin =
            entries.firstOrNull { it.raw == raw } ?: CLAY
    }
}

/**
 * 琉璃玻璃「透明度」两档（契约 §4.1 · iOS 26.1 清透 / 着色同构）。住 data 层是因为要经 DataStore 持久化
 * （数据层绝不依赖 UI 包）；`effective()` 等玻璃语义的扩展留在 `ui/liuli/glass`。未知/空回退清透。
 */
enum class GlassTier(val raw: String) {
    CLEAR("clear"),   // 清透：染色轻、身后内容更透
    TINTED("tinted"); // 着色：染色重、字更清楚（无实时模糊能力时强制此档）

    companion object {
        fun fromRaw(raw: String?): GlassTier =
            entries.firstOrNull { it.raw == raw } ?: CLEAR
    }
}

/**
 * 根部主题读取的外观快照：脸 + 深浅模式 + Material You 动态取色开关 + 玻璃透明度档。
 *
 * iOS 这一项是「多主题 currentThemeID」。安卓 2026-06-30 起开放多主题配色，2026-09-04 起升级为
 * 「两张脸一个大脑」（[skin]·见 FABLE5_THEME_LIULI_PROPOSAL.md §1）；[skin] 与 [mode] 深浅正交。
 * 动态取色（[useDynamicColor]）仍为安卓特有 opt-in；[glassTier] 只影响琉璃的玻璃片。
 */
data class AppearanceState(
    val mode: AppearanceMode = AppearanceMode.SYSTEM,
    // Fable-5 Phase 0：默认关动态取色=品牌调色板，Monet 降 opt-in（设计语言 §1.5）。
    val useDynamicColor: Boolean = false,
    // 界面「脸」（默认暖陶·与深浅正交·见 FABLE5_THEME_LIULI_PROPOSAL.md §7.1）。
    val skin: AppSkin = AppSkin.CLAY,
    // 琉璃玻璃透明度档（默认清透·只影响琉璃玻璃片）。
    val glassTier: GlassTier = GlassTier.CLEAR,
) {
    companion object {
        /** 加载完成前的默认值 = 当前现状（跟随系统 + 默认暖陶 + 清透），保证无回归、无首帧闪烁。 */
        val DEFAULT = AppearanceState()
    }
}
