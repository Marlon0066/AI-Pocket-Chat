package com.situ.aichat.ui.liuli.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.GrowthSettingsViewModel
import kotlin.math.roundToInt

/**
 * 开发者组的两条硬编码中文（与暖陶 `GrowthSettingsScreen.kt:123–129` **同值**·A-6）。
 * 它们只在 `BuildConfig.DEBUG` 下出现，暖陶也没抽资源；改一侧必须同步另一侧。
 */
private const val DEV_SECTION_TITLE = "开发者"
private const val DEV_OBSERVATORY_TITLE = "内核观测台（仅 debug）"

/**
 * 成长设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 2）。与暖陶 `GrowthSettingsScreen` 共用
 * [GrowthSettingsViewModel]；**整个参数区被 `growthSystemEnabled` 门控**（关 = 只出一句引导·逐字照暖陶
 * `:66`），开发者组另由 `BuildConfig.DEBUG` 门控。
 */
@Composable
fun LiuliGrowthSettingsScreen(
    onBack: () -> Unit,
    onOpenObservatory: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: GrowthSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    LiuliGrowthSettingsContent(
        settings = settings,
        debugBuild = BuildConfig.DEBUG,
        onSetAnalysisInterval = viewModel::setGrowthAnalysisInterval,
        onSetMoodHistoryMax = viewModel::setMoodHistoryMaxCount,
        onSetLogMax = viewModel::setGrowthLogMaxCount,
        onSetInterestCooldown = viewModel::setInterestCooldownDays,
        onOpenObservatory = onOpenObservatory,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 成长页内容层（纯参数·可测）。[debugBuild] 显式传进来，测试才能两条分支都渲染得出来。 */
@Composable
internal fun LiuliGrowthSettingsContent(
    settings: AppSettings,
    debugBuild: Boolean,
    onSetAnalysisInterval: (Int) -> Unit,
    onSetMoodHistoryMax: (Int) -> Unit,
    onSetLogMax: (Int) -> Unit,
    onSetInterestCooldown: (Int) -> Unit,
    onOpenObservatory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.growth_settings_title)
    val rounds = stringResource(R.string.mem_unit_rounds)
    val off = stringResource(R.string.mem_value_off)
    val entries = stringResource(R.string.growth_unit_entries)
    val days = stringResource(R.string.growth_unit_days)
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
            if (!settings.growthSystemEnabled) {
                item(key = "disabled-hint") {
                    Text(
                        stringResource(R.string.growth_disabled_hint),
                        style = AppTypography.listPreview,
                        color = AppTheme.colors.text.secondary,
                        modifier = Modifier.padding(
                            start = LiuliPageGeometry.gutter,
                            end = LiuliPageGeometry.gutter,
                            top = LiuliPageGeometry.titleGap,
                            bottom = LiuliPageGeometry.groupPadH,
                        ),
                    )
                }
            }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    if (settings.growthSystemEnabled) {
                        LiuliGroup(
                            header = stringResource(R.string.growth_section_analysis_title),
                            footer = stringResource(R.string.growth_section_analysis_footer),
                        ) {
                            LiuliSliderRow(
                                title = stringResource(R.string.growth_analysis_interval),
                                valueLabel = if (settings.growthAnalysisInterval == 0) {
                                    off
                                } else {
                                    "${settings.growthAnalysisInterval} $rounds"
                                },
                                value = settings.growthAnalysisInterval.toFloat(),
                                valueRange = 0f..100f,
                                steps = 19,
                                divider = false,
                                onManualInput = onSetAnalysisInterval,
                                onValueChange = { onSetAnalysisInterval((it / 5f).roundToInt() * 5) },
                            )
                        }
                        LiuliGroup(header = stringResource(R.string.growth_section_memory_title)) {
                            LiuliSliderRow(
                                title = stringResource(R.string.growth_mood_history_max),
                                valueLabel = "${settings.moodHistoryMaxCount} $entries",
                                value = settings.moodHistoryMaxCount.toFloat(),
                                valueRange = 50f..500f,
                                steps = 8,
                                divider = false,
                                onManualInput = onSetMoodHistoryMax,
                                onValueChange = { onSetMoodHistoryMax((it / 50f).roundToInt() * 50) },
                            )
                            LiuliSliderRow(
                                title = stringResource(R.string.growth_log_max),
                                valueLabel = "${settings.growthLogMaxCount} $entries",
                                value = settings.growthLogMaxCount.toFloat(),
                                valueRange = 20f..300f,
                                steps = 27,
                                onManualInput = onSetLogMax, // 手填不做 10 吸附（同暖陶）
                                onValueChange = { onSetLogMax((it / 10f).roundToInt() * 10) },
                            )
                            LiuliSliderRow(
                                title = stringResource(R.string.growth_interest_cooldown),
                                valueLabel = "${settings.interestCooldownDays} $days",
                                value = settings.interestCooldownDays.toFloat(),
                                valueRange = 1f..60f,
                                steps = 58,
                                onManualInput = onSetInterestCooldown,
                                onValueChange = { onSetInterestCooldown(it.roundToInt()) },
                            )
                        }
                    }
                    // 活人感内核卷零 chunk5 的开发者入口：release 构建整块短路，用户侧零变化（屏本体另有守卫）。
                    if (debugBuild) {
                        LiuliGroup(header = DEV_SECTION_TITLE) {
                            LiuliNavRow(
                                title = DEV_OBSERVATORY_TITLE,
                                onClick = onOpenObservatory,
                                icon = Icons.Filled.Insights,
                                // 诊断类走灰砖（契约 §6.5 图标砖色板「数据与诊断」那一枚）。
                                tileColor = LiuliPalette.tileData,
                                divider = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
