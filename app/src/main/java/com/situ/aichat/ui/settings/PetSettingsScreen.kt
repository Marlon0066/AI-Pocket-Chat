package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.SettingsMasterCard
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 暖陶「宠物」独立页（设置全面重构卷三）：
 * 顶卡总控宠物系统 + 从属「宠物日记自动生成」（从「日记」设置页迁入），总开关关时从属项置灰带锁说明。
 */
@Composable
fun PetSettingsScreen(
    onBack: () -> Unit,
    viewModel: SystemTogglesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    PetSettingsContent(
        settings = s,
        onSetPetEnabled = viewModel::setPetSystemEnabled,
        onSetPetDiaryAutoGenerate = viewModel::setPetDiaryAutoGenerateEnabled,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PetSettingsContent(
    settings: AppSettings,
    onSetPetEnabled: (Boolean) -> Unit,
    onSetPetDiaryAutoGenerate: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    val enabled = settings.petSystemEnabled
    val disabledReason = if (!enabled) stringResource(R.string.settings_pet_disabled_reason) else null

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_hub_pet),
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
                .contentMaxWidth(),
        ) {
            SettingsMasterCard(
                title = stringResource(R.string.settings_pet_master_title),
                subtitle = stringResource(R.string.settings_pet_master_sub),
                icon = Icons.Filled.Pets,
                checked = enabled,
                onCheckedChange = onSetPetEnabled,
            )

            SettingsSection(title = stringResource(R.string.diary_settings_autogen_header)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.diary_settings_pet_autogen_label),
                    checked = enabled && settings.petDiaryAutoGenerateEnabled,
                    enabled = enabled,
                    disabledReason = disabledReason,
                    onCheckedChange = onSetPetDiaryAutoGenerate,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
