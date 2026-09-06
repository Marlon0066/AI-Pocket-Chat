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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSegmentRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.SystemTogglesViewModel

/** 跨角色互动的三档（1 / 2 / 3 = 偶尔 / 经常 / 频繁·存的是整数档位·零碰）。 */
private val CROSS_LEVELS = listOf(1, 2, 3)

/**
 * 功能开关页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 24「无标题分段」）。与暖陶 `SystemTogglesScreen` 共用
 * [SystemTogglesViewModel]。
 *
 * **三层门控逐字继承**（E20）：成长关 → 藏「自动关系进化」；日程关 → 藏「角色之间互相来往」整块；
 * 跨角色开（`level > 0`）→ 才露三档分段。**💰 相邻**：货币总开关门控「角色主动送礼」——门控逻辑零碰。
 * 开关写值也逐字：开 → 恢复到 1 作默认起点，关 → 0。
 */
@Composable
fun LiuliSystemTogglesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SystemTogglesViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    LiuliSystemTogglesContent(
        settings = settings,
        onSetGrowth = viewModel::setGrowthSystemEnabled,
        onSetRelationshipAutoAdvance = viewModel::setRelationshipAutoAdvanceEnabled,
        onSetSchedule = viewModel::setScheduleSystemEnabled,
        onSetCrossCharacterLevel = viewModel::setCrossCharacterLevel,
        onSetPet = viewModel::setPetSystemEnabled,
        onSetCurrency = viewModel::setCurrencySystemEnabled,
        onSetProactiveGift = viewModel::setCharacterProactiveGiftEnabled,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 功能开关页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliSystemTogglesContent(
    settings: AppSettings,
    onSetGrowth: (Boolean) -> Unit,
    onSetRelationshipAutoAdvance: (Boolean) -> Unit,
    onSetSchedule: (Boolean) -> Unit,
    onSetCrossCharacterLevel: (Int) -> Unit,
    onSetPet: (Boolean) -> Unit,
    onSetCurrency: (Boolean) -> Unit,
    onSetProactiveGift: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.sys_settings_title)
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
                    LiuliGroup(header = stringResource(R.string.sys_section_growth)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.sys_growth_title),
                            subtitle = stringResource(R.string.sys_growth_subtitle),
                            checked = settings.growthSystemEnabled,
                            onCheckedChange = onSetGrowth,
                            divider = false,
                        )
                        // 成长关时隐藏「自动关系进化」（关系自动进化依赖成长分析产生的信号）。
                        if (settings.growthSystemEnabled) {
                            LiuliToggleRow(
                                title = stringResource(R.string.sys_relationship_title),
                                subtitle = stringResource(R.string.sys_relationship_subtitle),
                                checked = settings.relationshipAutoAdvanceEnabled,
                                onCheckedChange = onSetRelationshipAutoAdvance,
                            )
                        }
                    }
                    LiuliGroup(header = stringResource(R.string.sys_section_systems)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.sys_schedule_title),
                            subtitle = stringResource(R.string.sys_schedule_subtitle),
                            checked = settings.scheduleSystemEnabled,
                            onCheckedChange = onSetSchedule,
                            divider = false,
                        )
                        // 跨日程互动依赖日程系统——日程关时整块隐藏（协调器在日程关时直接 return）。
                        if (settings.scheduleSystemEnabled) {
                            LiuliToggleRow(
                                title = stringResource(R.string.sys_cross_character_title),
                                subtitle = stringResource(R.string.sys_cross_character_subtitle),
                                checked = settings.crossCharacterLevel > 0,
                                // 开 → 恢复到「偶尔」(1) 作默认起点；关 → 0。频率档由下面的分段细调。
                                onCheckedChange = { on -> onSetCrossCharacterLevel(if (on) 1 else 0) },
                            )
                            if (settings.crossCharacterLevel > 0) {
                                LiuliSegmentRow(
                                    // 上一行已经点了名，分段自己不再念一遍（卷四 R1 🟡-2）。
                                    title = null,
                                    // 分段紧贴上面的开关行（暖陶无发丝·复核 R1 C-7）。
                                    divider = false,
                                    options = CROSS_LEVELS,
                                    selected = settings.crossCharacterLevel.coerceIn(1, 3),
                                    label = { level ->
                                        stringResource(
                                            when (level) {
                                                1 -> R.string.cross_level_occasional
                                                2 -> R.string.cross_level_often
                                                else -> R.string.cross_level_frequent
                                            },
                                        )
                                    },
                                    onSelect = onSetCrossCharacterLevel,
                                )
                            }
                        }
                        LiuliToggleRow(
                            title = stringResource(R.string.sys_pet_title),
                            subtitle = stringResource(R.string.sys_pet_subtitle),
                            checked = settings.petSystemEnabled,
                            onCheckedChange = onSetPet,
                        )
                        LiuliToggleRow(
                            title = stringResource(R.string.sys_currency_title),
                            subtitle = stringResource(R.string.sys_currency_subtitle),
                            checked = settings.currencySystemEnabled,
                            onCheckedChange = onSetCurrency,
                        )
                        // 💰 二级门控：货币系统关时隐藏主动送礼（1:1 iOS·门控逻辑零碰）。
                        if (settings.currencySystemEnabled) {
                            LiuliToggleRow(
                                title = stringResource(R.string.sys_proactive_gift_title),
                                subtitle = stringResource(R.string.sys_proactive_gift_subtitle),
                                checked = settings.characterProactiveGiftEnabled,
                                onCheckedChange = onSetProactiveGift,
                            )
                        }
                    }
                }
            }
        }
    }
}
