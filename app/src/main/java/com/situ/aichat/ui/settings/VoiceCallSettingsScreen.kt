package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 语音通话设置（P10.1h-3）。1:1 对齐 iOS `VoiceCallSettingsView`：单个「打断灵敏度」滑块，
 * 越靠右越容易打断 AI（存值 = 0.45 − 滑块）。两端文案「不易打断 / 容易打断」+ footer 说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallSettingsScreen(
    onBack: () -> Unit,
    viewModel: VoiceCallSettingsViewModel = hiltViewModel(),
) {
    val storedSlider by viewModel.sliderPosition.collectAsStateWithLifecycle()
    // Local drag state seeded from the persisted value; persist once per drag on release so the
    // thumb tracks the finger smoothly instead of round-tripping through DataStore each tick.
    var localSlider by remember(storedSlider) { mutableFloatStateOf(storedSlider) }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.voice_call_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.voice_call_settings_sensitivity_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.voice_call_settings_harder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppSlider(
                    value = localSlider,
                    onValueChange = { localSlider = it },
                    onValueChangeFinished = { viewModel.setSliderPosition(localSlider) },
                    valueRange = VoiceCallSensitivity.SLIDER_MIN..VoiceCallSensitivity.SLIDER_MAX,
                    steps = VoiceCallSensitivity.SLIDER_STEPS,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Text(
                    stringResource(R.string.voice_call_settings_easier),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.voice_call_settings_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
