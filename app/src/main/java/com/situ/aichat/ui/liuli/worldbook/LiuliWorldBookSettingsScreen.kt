package com.situ.aichat.ui.liuli.worldbook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.worldbook.WorldInfoInsertionStrategy
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliMenuRow
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliStepperRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.worldbook.WorldBookSettingsViewModel
import com.situ.aichat.ui.worldbook.strategyLabelRes

/** 三种插入策略的固定顺序（逐字照暖陶 `:154–158`·顺序即用户看到的顺序）。 */
private val INSERTION_STRATEGIES = listOf(
    WorldInfoInsertionStrategy.CHARACTER_FIRST,
    WorldInfoInsertionStrategy.GLOBAL_FIRST,
    WorldInfoInsertionStrategy.EVENLY,
)

/**
 * 世界书设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 19「步进器 ×2 · 下拉 ×1」）。与暖陶
 * `WorldBookSettingsScreen` 共用 [WorldBookSettingsViewModel]（VM 本体写在暖陶那个文件里）。
 *
 * **零碰**：插入策略枚举以 `.name` 存串，读回走 `runCatching { valueOf(...) }.getOrDefault(CHARACTER_FIRST)`
 * ——存的是枚举名不是序号，改名就读不回来。策略显示名借暖陶 [strategyLabelRes]（§2.2-2 已提 internal）。
 */
@Composable
fun LiuliWorldBookSettingsScreen(
    onBack: () -> Unit,
    onOpenMemorySettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorldBookSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    LiuliWorldBookSettingsContent(
        settings = settings,
        onSetScanDepth = viewModel::setScanDepth,
        onSetBudgetChars = viewModel::setBudgetChars,
        onSetRecursiveScan = viewModel::setRecursiveScan,
        onSetMaxRecursionSteps = viewModel::setMaxRecursionSteps,
        onSetInsertionStrategy = viewModel::setInsertionStrategy,
        onSetCaseSensitive = viewModel::setCaseSensitive,
        onSetMatchWholeWords = viewModel::setMatchWholeWords,
        onOpenMemorySettings = onOpenMemorySettings,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 世界书设置页内容层（纯参数·可测）。六组的分法与联动门控逐字继承暖陶。 */
@Composable
internal fun LiuliWorldBookSettingsContent(
    settings: AppSettings,
    onSetScanDepth: (Int) -> Unit,
    onSetBudgetChars: (Int) -> Unit,
    onSetRecursiveScan: (Boolean) -> Unit,
    onSetMaxRecursionSteps: (Int) -> Unit,
    onSetInsertionStrategy: (WorldInfoInsertionStrategy) -> Unit,
    onSetCaseSensitive: (Boolean) -> Unit,
    onSetMatchWholeWords: (Boolean) -> Unit,
    onOpenMemorySettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.wb_settings_title)
    var strategyMenuOpen by remember { mutableStateOf(false) }
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup {
                        LiuliStepperRow(
                            title = stringResource(R.string.wb_scan_range),
                            value = settings.worldInfoScanDepth,
                            range = 1..10,
                            valueText = stringResource(R.string.wb_n_messages, settings.worldInfoScanDepth),
                            onChange = onSetScanDepth,
                            hint = stringResource(R.string.wb_scan_range_sub),
                            divider = false,
                        )
                    }
                    LiuliGroup {
                        LiuliSliderRow(
                            title = stringResource(R.string.wb_budget),
                            valueLabel = stringResource(R.string.wb_char_count, settings.worldInfoBudgetChars),
                            value = settings.worldInfoBudgetChars.toFloat(),
                            valueRange = 1_000f..20_000f,
                            steps = 18,
                            divider = false,
                            info = stringResource(R.string.wb_budget_sub),
                            onManualInput = onSetBudgetChars,
                            onValueChange = { onSetBudgetChars(it.toInt()) },
                        )
                    }
                    LiuliGroup {
                        LiuliToggleRow(
                            title = stringResource(R.string.wb_linkage),
                            subtitle = stringResource(R.string.wb_linkage_sub),
                            checked = settings.worldInfoRecursiveScan,
                            onCheckedChange = onSetRecursiveScan,
                            divider = false,
                        )
                        // 联动层数只在联动开时出（逐字照暖陶 :133）；0 = 不限层。
                        if (settings.worldInfoRecursiveScan) {
                            LiuliStepperRow(
                                title = stringResource(R.string.wb_linkage_depth),
                                value = settings.worldInfoMaxRecursionSteps,
                                range = 0..10,
                                valueText = if (settings.worldInfoMaxRecursionSteps == 0) {
                                    stringResource(R.string.wb_unlimited)
                                } else {
                                    stringResource(R.string.wb_layers_value, settings.worldInfoMaxRecursionSteps)
                                },
                                onChange = onSetMaxRecursionSteps,
                            )
                        }
                    }
                    LiuliGroup {
                        // 存的是枚举名；读不回来就回落 CHARACTER_FIRST（逐字照暖陶 :152）。
                        val current = runCatching { WorldInfoInsertionStrategy.valueOf(settings.worldInfoInsertionStrategy) }
                            .getOrDefault(WorldInfoInsertionStrategy.CHARACTER_FIRST)
                        LiuliMenuRow(
                            title = stringResource(R.string.wb_insertion_pref),
                            value = stringResource(strategyLabelRes(current)),
                            options = INSERTION_STRATEGIES.map { strategy ->
                                LiuliMenuEntry(
                                    text = stringResource(strategyLabelRes(strategy)),
                                    selected = strategy == current,
                                    onClick = { onSetInsertionStrategy(strategy) },
                                )
                            },
                            expanded = strategyMenuOpen,
                            onExpandedChange = { strategyMenuOpen = it },
                            divider = false,
                        )
                    }
                    LiuliGroup(header = stringResource(R.string.wb_adv_matching_section)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.wb_case_label),
                            checked = settings.worldInfoCaseSensitive,
                            onCheckedChange = onSetCaseSensitive,
                            divider = false,
                        )
                        LiuliToggleRow(
                            title = stringResource(R.string.wb_whole_label),
                            subtitle = stringResource(R.string.wb_whole_warning),
                            checked = settings.worldInfoMatchWholeWords,
                            onCheckedChange = onSetMatchWholeWords,
                        )
                    }
                    LiuliGroup {
                        LiuliNavRow(
                            title = stringResource(R.string.wb_semantic_row),
                            subtitle = stringResource(R.string.wb_semantic_sub),
                            onClick = onOpenMemorySettings,
                            divider = false,
                        )
                    }
                }
            }
        }
    }
}
