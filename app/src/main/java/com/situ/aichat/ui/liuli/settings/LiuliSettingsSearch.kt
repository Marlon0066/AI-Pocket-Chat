package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.Immutable
import com.situ.aichat.prompt.memory.TextEmbedder

/**
 * 设置主页的搜索过滤（图纸 2026-09-06 卷四 A-4）与整形后的只读状态（§3）。
 *
 * 过滤只在**组**这一级：组内任一行的标题 / 副标题命中就整组照原样显示，一条都不命中就整组不组合（含组标题）。
 * 不看行尾值（行尾是「当前是什么」，不是「这是什么」），不改分组顺序，不改行内内容。
 */

/**
 * 命中判定（纯函数·T1 反推）：[term] 去首尾空白后为空 = 全显；否则任一 [texts] 含它（忽略大小写）即命中。
 * null 文本（该行没有副标题）直接跳过。
 */
fun settingsMatches(term: String, vararg texts: String?): Boolean {
    val needle = term.trim()
    if (needle.isEmpty()) return true
    return texts.any { it != null && it.contains(needle, ignoreCase = true) }
}

/**
 * 设置主页行尾要回显的那些值（由 Screen 层从六条 StateFlow 整形一次，纯参数传进内容层便于测）。
 *
 * 只放**已经解析成字符串**的东西：内容层不再碰 VM、不再自己算标签，测试给什么就显什么。
 */
@Immutable
data class LiuliSettingsState(
    /** 「服务商 · 模型」；未配置为 null（行尾改显 `settings_api_not_configured` 警示值）。 */
    val activeConfigLabel: String?,
    val advancedEnabled: Boolean,
    val emotionAnimEnabled: Boolean,
    val textingToneEnabled: Boolean,
    /** 「脸 · 深浅」（外观行行尾）。 */
    val appearanceLabel: String,
    val ttsProviderName: String,
    val notifEnabled: Boolean,
    val worldTierLabel: String,
    val embedderState: TextEmbedder.LoadState,
    val currentLangTag: String,
    val version: String,
)

/** 设置主页的**导航**出口（25 个 `onOpen*`·由选脸包装从自己的形参逐个转手·实参与暖陶分支逐字相同）。 */
@Immutable
data class LiuliSettingsCallbacks(
    val onOpenApiConfig: () -> Unit,
    val onOpenApiFunctions: () -> Unit,
    val onOpenMemorySettings: () -> Unit,
    val onOpenSystemToggles: () -> Unit,
    val onOpenAppearance: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onOpenImmersiveSettings: () -> Unit,
    val onOpenStickerManagement: () -> Unit,
    val onOpenGrowthSettings: () -> Unit,
    val onOpenReplyRules: () -> Unit,
    val onOpenContentFilter: () -> Unit,
    val onOpenCalendarAwareness: () -> Unit,
    val onOpenWorldBooks: () -> Unit,
    val onOpenPromptModules: () -> Unit,
    val onOpenTtsConfig: () -> Unit,
    val onOpenVoiceCallSettings: () -> Unit,
    val onOpenDiarySettings: () -> Unit,
    val onOpenMomentSettings: () -> Unit,
    val onOpenStoryGlobalSettings: () -> Unit,
    val onOpenWorldSettings: () -> Unit,
    val onOpenBackup: () -> Unit,
    val onOpenBackgroundReliability: () -> Unit,
    val onOpenContextLog: () -> Unit,
    val onOpenPerfCollect: () -> Unit,
    val onOpenAbout: () -> Unit,
)

/**
 * 设置主页里**不离开本屏**的三个开关 + 语言切换（由 Screen 层接 VM 与 `LocaleManager`）。
 * 与导航出口分开放：包装层只转手导航，VM 相关的一概不进包装签名（A-1）。
 */
@Immutable
data class LiuliSettingsActions(
    val onSetEmotionAnimation: (Boolean) -> Unit,
    val onSetTextingTone: (Boolean) -> Unit,
    val onSetAdvancedMode: (Boolean) -> Unit,
    /** 语言弹窗里选中某个 tag（Screen 层接 `LocaleManager` + `recreate`·F2 :289-303 逐字搬）。 */
    val onSelectLanguage: (String) -> Unit,
)
