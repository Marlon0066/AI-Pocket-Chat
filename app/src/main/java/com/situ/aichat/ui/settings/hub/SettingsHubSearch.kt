package com.situ.aichat.ui.settings.hub

import androidx.compose.runtime.Immutable

/**
 * 设置主页共享搜索逻辑（两张脸共用）。
 *
 * [settingsMatches] 保持原有纯函数语义一字不变：trim 后为空全显，否则任一非空文本包含（忽略大小写）即命中。
 */
fun settingsMatches(term: String, vararg texts: String?): Boolean {
    val needle = term.trim()
    if (needle.isEmpty()) return true
    return texts.any { it != null && it.contains(needle, ignoreCase = true) }
}

/** 行级搜索命中结果：若由关键词（而非标题/副标题）命中，[matchedKeyword] 记录首个命中的关键词用于行副标提示。 */
@Immutable
data class SettingsSearchHit(
    val entry: SettingsHubEntry,
    val matchedKeyword: String? = null,
)

/**
 * 行级搜索过滤：按目录顺序返回所有命中的入口。
 * 当 [term] 去首尾空白为空时返回空列表（由调用方渲染正常分组视图）。
 */
fun filterSettingsHub(
    term: String,
    entries: List<SettingsHubEntry> = SettingsHubEntry.entries,
    titleOf: (SettingsHubEntry) -> String,
    subtitleOf: (SettingsHubEntry) -> String? = { null },
    keywordsOf: (SettingsHubEntry) -> List<String>,
): List<SettingsSearchHit> {
    val needle = term.trim()
    if (needle.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        val title = titleOf(entry)
        val subtitle = subtitleOf(entry)
        when {
            title.contains(needle, ignoreCase = true) ||
                (subtitle != null && subtitle.contains(needle, ignoreCase = true)) -> {
                SettingsSearchHit(entry = entry, matchedKeyword = null)
            }
            else -> {
                val hitKw = keywordsOf(entry).firstOrNull { it.contains(needle, ignoreCase = true) }
                hitKw?.let { SettingsSearchHit(entry = entry, matchedKeyword = it) }
            }
        }
    }
}
