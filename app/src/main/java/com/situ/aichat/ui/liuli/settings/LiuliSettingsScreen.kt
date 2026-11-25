package com.situ.aichat.ui.liuli.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.designsystem.LiuliSearchSlot
import com.situ.aichat.ui.liuli.home.LiuliNoResults
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.SettingsOverviewViewModel
import com.situ.aichat.ui.settings.hub.SettingsHubEntry
import com.situ.aichat.ui.settings.hub.SettingsHubGroup
import com.situ.aichat.ui.settings.hub.SettingsHubSnapshot
import com.situ.aichat.ui.settings.hub.filterSettingsHub
import com.situ.aichat.ui.settings.hub.resolvedTrailingValue
import com.situ.aichat.util.LocaleManager
import com.situ.aichat.work.BackgroundReliability

private val PAGE_BOTTOM = 24.dp

/**
 * 琉璃 2.0 设置主页（设置全面重构卷一）：
 * 与暖陶共用 [SettingsHubEntry] 目录、[SettingsOverviewViewModel] 单一快照流和行级搜索；
 * 语言弹窗放在 [LiuliPage] 内容层内，享有跨窗真玻璃质感（PITFALLS #25）。
 */
@Composable
fun LiuliSettingsScreen(
    onBack: () -> Unit,
    onOpenEntry: (SettingsHubEntry) -> Unit,
    modifier: Modifier = Modifier,
    overviewViewModel: SettingsOverviewViewModel = hiltViewModel(),
) {
    val snapshot by overviewViewModel.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isBatteryExempt by remember { mutableStateOf(BackgroundReliability.isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryExempt = BackgroundReliability.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val currentTag = LocaleManager.currentTag(context)

    LiuliSettingsContent(
        snapshot = snapshot,
        isBatteryExempt = isBatteryExempt,
        currentLangTag = currentTag,
        versionName = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onOpenEntry = onOpenEntry,
        onSelectLanguage = { tag ->
            if (tag != currentTag && LocaleManager.setLanguage(context, tag)) {
                (context as? Activity)?.recreate()
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun LiuliSettingsContent(
    snapshot: SettingsHubSnapshot,
    isBatteryExempt: Boolean,
    currentLangTag: String,
    versionName: String,
    onBack: () -> Unit,
    onOpenEntry: (SettingsHubEntry) -> Unit,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var term by rememberSaveable { mutableStateOf("") }
    var showLangDialog by rememberSaveable { mutableStateOf(false) }
    val bottomInset = PAGE_BOTTOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val titles = SettingsHubEntry.entries.associateWith { stringResource(it.titleRes) }
    val subtitles = SettingsHubEntry.entries.associateWith { it.subtitleRes?.let { res -> stringResource(res) } }
    val keywords = SettingsHubEntry.entries.associateWith { stringArrayResource(it.keywordsRes).toList() }
    val searching = term.trim().isNotEmpty()
    val searchHits = if (searching) {
        filterSettingsHub(
            term = term,
            titleOf = { titles[it].orEmpty() },
            subtitleOf = { subtitles[it] },
            keywordsOf = { keywords[it].orEmpty() },
        )
    } else {
        emptyList()
    }
    val openOrDialog: (SettingsHubEntry) -> Unit = { entry ->
        if (entry == SettingsHubEntry.LANGUAGE) showLangDialog = true else onOpenEntry(entry)
    }

    LiuliPage(
        title = stringResource(R.string.settings_screen_title),
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(stringResource(R.string.settings_screen_title)) }
            item(key = "search") {
                LiuliSearchSlot(
                    value = term,
                    onValueChange = { term = it },
                    placeholder = stringResource(R.string.settings_search_hint),
                    clearContentDescription = stringResource(R.string.chat_list_search_clear),
                    modifier = Modifier.padding(
                        start = LiuliPageGeometry.gutter,
                        end = LiuliPageGeometry.gutter,
                        top = LiuliPageGeometry.titleGap,
                        bottom = LiuliPageGeometry.gutter,
                    ),
                )
            }
            item(key = "body") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    if (searching) {
                        if (searchHits.isEmpty()) {
                            LiuliNoResults(
                                text = stringResource(R.string.settings_search_no_results),
                                clearText = stringResource(R.string.chat_list_search_clear),
                                onClear = { term = "" },
                            )
                        } else {
                            LiuliGroup(header = stringResource(R.string.settings_hub_search_results)) {
                                searchHits.forEachIndexed { idx, hit ->
                                    val (valText, valWarn) = resolvedTrailingValue(
                                        entry = hit.entry,
                                        snapshot = snapshot,
                                        isBatteryExempt = isBatteryExempt,
                                        currentLangTag = currentLangTag,
                                        versionName = versionName,
                                    )
                                    val sub = hit.matchedKeyword?.let {
                                        stringResource(R.string.settings_hub_search_matched, it)
                                    } ?: subtitles[hit.entry]
                                    LiuliHubRow(
                                        entry = hit.entry,
                                        title = titles[hit.entry].orEmpty(),
                                        subtitle = sub,
                                        value = valText,
                                        valueWarning = valWarn,
                                        divider = idx > 0,
                                        onClick = { openOrDialog(hit.entry) },
                                    )
                                }
                            }
                        }
                    } else {
                        // 顶部两张大卡
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = LiuliPageGeometry.groupGap),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SettingsHubEntry.heroEntries.forEach { entry ->
                                val (valText, valWarn) = resolvedTrailingValue(
                                    entry = entry,
                                    snapshot = snapshot,
                                    isBatteryExempt = isBatteryExempt,
                                    currentLangTag = currentLangTag,
                                    versionName = versionName,
                                )
                                LiuliSettingsHeroCard(
                                    title = titles[entry].orEmpty(),
                                    value = valText.orEmpty(),
                                    icon = entry.icon,
                                    tileTone = entry.tileTone,
                                    valueWarning = valWarn,
                                    onClick = { openOrDialog(entry) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        // 五组核心入口
                        SettingsHubGroup.entries.forEach { group ->
                            LiuliGroup(header = stringResource(group.titleRes)) {
                                SettingsHubEntry.entriesIn(group).forEachIndexed { idx, entry ->
                                    val (valText, valWarn) = resolvedTrailingValue(
                                        entry = entry,
                                        snapshot = snapshot,
                                        isBatteryExempt = isBatteryExempt,
                                        currentLangTag = currentLangTag,
                                        versionName = versionName,
                                    )
                                    LiuliHubRow(
                                        entry = entry,
                                        title = titles[entry].orEmpty(),
                                        subtitle = subtitles[entry],
                                        value = valText,
                                        valueWarning = valWarn,
                                        divider = idx > 0,
                                        onClick = { openOrDialog(entry) },
                                    )
                                }
                            }
                        }

                        // 底部两张独立卡：高级 & 关于
                        SettingsHubEntry.footerEntries.forEach { entry ->
                            val (valText, valWarn) = resolvedTrailingValue(
                                entry = entry,
                                snapshot = snapshot,
                                isBatteryExempt = isBatteryExempt,
                                currentLangTag = currentLangTag,
                                versionName = versionName,
                            )
                            LiuliGroup {
                                LiuliHubRow(
                                    entry = entry,
                                    title = titles[entry].orEmpty(),
                                    subtitle = subtitles[entry],
                                    value = valText,
                                    valueWarning = valWarn,
                                    divider = false,
                                    onClick = { openOrDialog(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showLangDialog) {
            LiuliLanguageDialog(
                current = currentLangTag,
                onDismiss = { showLangDialog = false },
                onSelect = { tag ->
                    showLangDialog = false
                    onSelectLanguage(tag)
                },
            )
        }
    }
}

@Composable
private fun LiuliHubRow(
    entry: SettingsHubEntry,
    title: String,
    subtitle: String?,
    value: String?,
    valueWarning: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
) {
    if (entry == SettingsHubEntry.LANGUAGE) {
        LiuliValueRow(
            title = title,
            value = value.orEmpty(),
            onClick = onClick,
            icon = entry.icon,
            tileColor = entry.tileTone,
            subtitle = subtitle,
            valueWarning = valueWarning,
            divider = divider,
        )
    } else {
        LiuliNavRow(
            title = title,
            onClick = onClick,
            icon = entry.icon,
            tileColor = entry.tileTone,
            subtitle = subtitle,
            value = value,
            valueWarning = valueWarning,
            divider = divider,
        )
    }
}
