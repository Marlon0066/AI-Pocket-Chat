package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 系统功能开关页（P12.1b）。把早被各服务/PromptBuilder/ChatViewModel 读取以 gate 子系统、却一直只有默认值
 * 无界面的「子系统总开关」接上读写 UI（对齐 iOS SettingsView 通用/成长 区的系统开关）。
 *
 * 「角色主动送礼」受「货币系统」二级门控：货币关时不显示该行（1:1 iOS）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemTogglesScreen(
    onBack: () -> Unit,
    viewModel: SystemTogglesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.sys_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth(),
        ) {
            SettingsSection(title = stringResource(R.string.sys_section_growth)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.sys_growth_title),
                    subtitle = stringResource(R.string.sys_growth_subtitle),
                    checked = s.growthSystemEnabled,
                    onCheckedChange = viewModel::setGrowthSystemEnabled,
                )
                // 成长关时隐藏「自动关系进化」（1:1 iOS GrowthRelationshipSettingsView：该行在 if growthSystemEnabled 内；
                // 关系自动进化依赖成长分析产生的信号，故与成长视觉耦合）。
                if (s.growthSystemEnabled) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.sys_relationship_title),
                        subtitle = stringResource(R.string.sys_relationship_subtitle),
                        checked = s.relationshipAutoAdvanceEnabled,
                        onCheckedChange = viewModel::setRelationshipAutoAdvanceEnabled,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.sys_section_systems)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.sys_schedule_title),
                    subtitle = stringResource(R.string.sys_schedule_subtitle),
                    checked = s.scheduleSystemEnabled,
                    onCheckedChange = viewModel::setScheduleSystemEnabled,
                )
                // 角色跨日程互动（「角色之间互相来往」）依赖日程系统——日程关时整块隐藏
                // （ScheduleCoordinator 在日程关时直接 return，跨角色逻辑不可能触发）。
                if (s.scheduleSystemEnabled) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.sys_cross_character_title),
                        subtitle = stringResource(R.string.sys_cross_character_subtitle),
                        checked = s.crossCharacterLevel > 0,
                        // 开 → 恢复到「偶尔」(1) 作默认起点；关 → 0。频率档由下方分段控件细调。
                        onCheckedChange = { on -> viewModel.setCrossCharacterLevel(if (on) 1 else 0) },
                    )
                    // 开启时露出三档频率（偶尔/经常/频繁 = 1/2/3），左右内距对齐开关行。
                    if (s.crossCharacterLevel > 0) {
                        AppSegmentedControl(
                            options = listOf(1, 2, 3),
                            selected = s.crossCharacterLevel.coerceIn(1, 3),
                            onSelect = viewModel::setCrossCharacterLevel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            label = { level ->
                                stringResource(
                                    when (level) {
                                        1 -> R.string.cross_level_occasional
                                        2 -> R.string.cross_level_often
                                        else -> R.string.cross_level_frequent
                                    },
                                )
                            },
                        )
                    }
                }
                SettingsSwitchRow(
                    title = stringResource(R.string.sys_pet_title),
                    subtitle = stringResource(R.string.sys_pet_subtitle),
                    checked = s.petSystemEnabled,
                    onCheckedChange = viewModel::setPetSystemEnabled,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.sys_currency_title),
                    subtitle = stringResource(R.string.sys_currency_subtitle),
                    checked = s.currencySystemEnabled,
                    onCheckedChange = viewModel::setCurrencySystemEnabled,
                )
                // 货币系统关时隐藏主动送礼（1:1 iOS 二级门控）。
                if (s.currencySystemEnabled) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.sys_proactive_gift_title),
                        subtitle = stringResource(R.string.sys_proactive_gift_subtitle),
                        checked = s.characterProactiveGiftEnabled,
                        onCheckedChange = viewModel::setCharacterProactiveGiftEnabled,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
