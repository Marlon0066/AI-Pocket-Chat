package com.situ.aichat.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppColors
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSearchField
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.settings.hub.SettingsHeroCard
import com.situ.aichat.ui.settings.hub.SettingsHubEntry
import com.situ.aichat.ui.settings.hub.SettingsHubGroup
import com.situ.aichat.ui.settings.hub.SettingsHubSnapshot
import com.situ.aichat.ui.settings.hub.filterSettingsHub
import com.situ.aichat.ui.settings.hub.resolvedTrailingValue
import com.situ.aichat.util.LocaleManager
import com.situ.aichat.work.BackgroundReliability

/**
 * 暖陶设置主页（设置全面重构卷一）：
 * 顶部搜索 + 两张大卡（AI 服务 / 外观）+ 五组（聊天 / 记忆 / TA 们的生活 / TA 写的东西 / 系统）+ 高级 + 关于。
 * 目录与琉璃共用 [SettingsHubEntry] 单源；每一行都是入口 + 行尾当前值。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenEntry: (SettingsHubEntry) -> Unit,
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

    SettingsScreenContent(
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    snapshot: SettingsHubSnapshot,
    isBatteryExempt: Boolean,
    currentLangTag: String,
    versionName: String,
    onBack: () -> Unit,
    onOpenEntry: (SettingsHubEntry) -> Unit,
    onSelectLanguage: (String) -> Unit,
) {
    var searchTerm by rememberSaveable { mutableStateOf("") }
    var showLangDialog by rememberSaveable { mutableStateOf(false) }

    val titles = SettingsHubEntry.entries.associateWith { stringResource(it.titleRes) }
    val subtitles = SettingsHubEntry.entries.associateWith { it.subtitleRes?.let { res -> stringResource(res) } }
    val keywords = SettingsHubEntry.entries.associateWith { stringArrayResource(it.keywordsRes).toList() }
    val searching = searchTerm.trim().isNotEmpty()
    val searchHits = if (searching) {
        filterSettingsHub(
            term = searchTerm,
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

    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_screen_title),
                onBack = onBack,
                lifted = lifted,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppSearchField(
                value = searchTerm,
                onValueChange = { searchTerm = it },
                placeholder = stringResource(R.string.settings_search_hint),
                clearContentDescription = stringResource(R.string.chat_list_search_clear),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )

            if (searching) {
                if (searchHits.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_search_no_results),
                            style = AppTheme.typography.body,
                            color = AppTheme.colors.text.secondary,
                        )
                        AppButton(
                            onClick = { searchTerm = "" },
                            style = AppButtonStyle.Tonal,
                        ) {
                            Text(stringResource(R.string.chat_list_search_clear))
                        }
                    }
                } else {
                    SettingsGroupCard(stringResource(R.string.settings_hub_search_results)) {
                        searchHits.forEach { hit ->
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
                            SettingsRow(
                                icon = hit.entry.icon,
                                title = titles[hit.entry].orEmpty(),
                                subtitle = sub,
                                value = valText,
                                valueWarning = valWarn,
                                onClick = { openOrDialog(hit.entry) },
                            )
                        }
                    }
                }
            } else {
                // 顶部两张大卡：AI 服务 + 外观
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
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
                        SettingsHeroCard(
                            title = titles[entry].orEmpty(),
                            value = valText.orEmpty(),
                            icon = entry.icon,
                            valueWarning = valWarn,
                            onClick = { openOrDialog(entry) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 五组核心入口
                SettingsHubGroup.entries.forEach { group ->
                    SettingsGroupCard(stringResource(group.titleRes)) {
                        SettingsHubEntry.entriesIn(group).forEach { entry ->
                            val (valText, valWarn) = resolvedTrailingValue(
                                entry = entry,
                                snapshot = snapshot,
                                isBatteryExempt = isBatteryExempt,
                                currentLangTag = currentLangTag,
                                versionName = versionName,
                            )
                            SettingsRow(
                                icon = entry.icon,
                                title = titles[entry].orEmpty(),
                                subtitle = subtitles[entry],
                                value = valText,
                                valueWarning = valWarn,
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
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                        Column(Modifier.fillMaxWidth().appCardSurface()) {
                            SettingsRow(
                                icon = entry.icon,
                                title = titles[entry].orEmpty(),
                                subtitle = subtitles[entry],
                                value = valText,
                                valueWarning = valWarn,
                                onClick = { openOrDialog(entry) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLangDialog) {
        LanguageDialog(
            current = currentLangTag,
            onDismiss = { showLangDialog = false },
            onSelect = { tag ->
                showLangDialog = false
                onSelectLanguage(tag)
            },
        )
    }
}

/** 分组卡片（标题 + appCardSurface·同包全局子屏复用）。 */
@Composable
internal fun SettingsGroupCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            text = title,
            style = AppTheme.typography.label,
            color = AppTheme.colors.accent.text,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).semantics { heading() },
        )
        Column(Modifier.fillMaxWidth().appCardSurface()) { content() }
    }
}

/** 分组卡内单条设置入口（薄委托 [AppSettingsRow]·同包子屏复用）。 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    value: String? = null,
    valueWarning: Boolean = false,
    badge: String? = null,
) {
    AppSettingsRow(
        title = title,
        modifier = Modifier.fillMaxWidth(),
        subtitle = subtitle,
        icon = icon,
        value = value,
        valueWarning = valueWarning,
        badge = badge,
        showChevron = true,
        onClick = onClick,
    )
}

/** 深层记忆（向量嵌入器）加载状态只读行（用于高级页诊断区）。 */
@Composable
internal fun EmbedderStatusRow(state: TextEmbedder.LoadState) {
    val statusWord = stringResource(
        when (state) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_unavailable
        },
    )
    val hint = stringResource(
        when (state) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_hint_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_hint_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_hint_unavailable
        },
    )
    val dotColor = dotColorFor(state, AppTheme.colors)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Psychology,
            contentDescription = null,
            tint = AppTheme.colors.accent.text,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.embedder_status_title),
                style = AppTheme.typography.settingsRowTitle,
                color = AppTheme.colors.text.primary,
            )
            Text(
                hint,
                style = AppTheme.typography.settingsRowSubtitle,
                color = AppTheme.colors.text.secondary,
            )
        }
        Box(Modifier.size(10.dp).background(dotColor, CircleShape))
        Text(
            statusWord,
            style = AppTheme.typography.settingsHubValue,
            color = AppTheme.colors.text.secondary,
            maxLines = 1,
        )
    }
}

/** 深层记忆状态行色点取色（已就绪=柔绿 / 待唤起=中性灰 / 暂不可用=琥珀）。 */
internal fun dotColorFor(state: TextEmbedder.LoadState, colors: AppColors): Color =
    when (state) {
        TextEmbedder.LoadState.LOADED -> colors.status.onSuccess
        TextEmbedder.LoadState.NOT_ATTEMPTED -> colors.text.tertiary
        TextEmbedder.LoadState.FAILED -> colors.status.onWarning
    }

@Composable
private fun LanguageDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val options = listOf(
        "zh-CN" to R.string.lang_option_zh,
        "en" to R.string.lang_option_en,
    )
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_language),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column {
                options.forEach { (tag, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = tag == current, onClick = { onSelect(tag) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppRadio(selected = tag == current, onClick = null)
                        Text(
                            text = stringResource(labelRes),
                            style = AppTheme.typography.body,
                            color = AppTheme.colors.text.primary,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        },
    )
}
