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
import androidx.compose.material.icons.filled.Notifications
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
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliMasterCard
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.GrowthSettingsViewModel
import kotlin.math.roundToInt

private const val DEV_SECTION_TITLE = "开发者"
private const val DEV_OBSERVATORY_TITLE = "内核观测台（仅 debug）"

/** 琉璃 2.0「成长与关系」设置页（设置全面重构卷三）。 */
@Composable
fun LiuliGrowthSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenObservatory: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    viewModel: GrowthSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    LiuliGrowthSettingsContent(
        settings = settings,
        debugBuild = BuildConfig.DEBUG,
        onSetGrowthEnabled = viewModel::setGrowthSystemEnabled,
        onSetRelationshipAdvance = viewModel::setRelationshipAutoAdvanceEnabled,
        onSetAnalysisInterval = viewModel::setGrowthAnalysisInterval,
        onSetMoodHistoryMax = viewModel::setMoodHistoryMaxCount,
        onSetLogMax = viewModel::setGrowthLogMaxCount,
        onSetInterestCooldown = viewModel::setInterestCooldownDays,
        onOpenObservatory = onOpenObservatory,
        onOpenNotifications = onOpenNotifications,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 成长页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliGrowthSettingsContent(
    settings: AppSettings,
    debugBuild: Boolean,
    onSetGrowthEnabled: (Boolean) -> Unit,
    onSetRelationshipAdvance: (Boolean) -> Unit,
    onSetAnalysisInterval: (Int) -> Unit,
    onSetMoodHistoryMax: (Int) -> Unit,
    onSetLogMax: (Int) -> Unit,
    onSetInterestCooldown: (Int) -> Unit,
    onOpenObservatory: () -> Unit,
    onOpenNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.growth_settings_title)
    val rounds = stringResource(R.string.mem_unit_rounds)
    val off = stringResource(R.string.mem_value_off)
    val entries = stringResource(R.string.growth_unit_entries)
    val days = stringResource(R.string.growth_unit_days)
    val enabled = settings.growthSystemEnabled
    val disabledReason = if (!enabled) stringResource(R.string.settings_growth_disabled_reason) else null
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
                    LiuliMasterCard(
                        title = stringResource(R.string.settings_growth_master_title),
                        subtitle = stringResource(R.string.settings_growth_master_sub),
                        icon = Icons.Filled.Insights,
                        tileTone = LiuliTileTone.Mint,
                        checked = enabled,
                        onCheckedChange = onSetGrowthEnabled,
                    )

                    LiuliGroup(header = stringResource(R.string.sys_relationship_title)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.sys_relationship_title),
                            subtitle = stringResource(R.string.sys_relationship_subtitle),
                            checked = enabled && settings.relationshipAutoAdvanceEnabled,
                            enabled = enabled,
                            disabledReason = disabledReason,
                            divider = false,
                            onCheckedChange = onSetRelationshipAdvance,
                        )
                    }

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
                            enabled = enabled,
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
                            enabled = enabled,
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
                            enabled = enabled,
                            onManualInput = onSetLogMax,
                            onValueChange = { onSetLogMax((it / 10f).roundToInt() * 10) },
                        )
                        LiuliSliderRow(
                            title = stringResource(R.string.growth_interest_cooldown),
                            valueLabel = "${settings.interestCooldownDays} $days",
                            value = settings.interestCooldownDays.toFloat(),
                            valueRange = 1f..60f,
                            steps = 58,
                            enabled = enabled,
                            onManualInput = onSetInterestCooldown,
                            onValueChange = { onSetInterestCooldown(it.roundToInt()) },
                        )
                    }

                    LiuliGroup(header = stringResource(R.string.notif_settings_title)) {
                        LiuliNavRow(
                            title = stringResource(R.string.settings_growth_notif_link),
                            subtitle = stringResource(R.string.settings_growth_notif_link_sub),
                            icon = Icons.Filled.Notifications,
                            tileColor = LiuliTileTone.Rose,
                            divider = false,
                            onClick = onOpenNotifications,
                        )
                    }

                    if (debugBuild) {
                        LiuliGroup(header = DEV_SECTION_TITLE) {
                            LiuliNavRow(
                                title = DEV_OBSERVATORY_TITLE,
                                onClick = onOpenObservatory,
                                icon = Icons.Filled.Insights,
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
