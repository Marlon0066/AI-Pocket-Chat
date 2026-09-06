package com.situ.aichat.ui.liuli.settings

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliSlider
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.VoiceCallSensitivity
import com.situ.aichat.ui.settings.VoiceCallSettingsViewModel

/** 两端字与滑杆之间的缝（逐字照暖陶 `:81` 的 horizontal 12）。 */
private val END_LABEL_GAP = 12.dp
/** 标题 ↔ 滑杆行的缝（逐字照暖陶的 spacedBy 8）。 */
private val STACK_GAP = 8.dp

/**
 * 语音通话设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 12「一组一滑杆 + 两端字」）。与暖陶
 * `VoiceCallSettingsScreen` 共用 [VoiceCallSettingsViewModel]。
 *
 * 机制锁（F8·逐字搬）：**反向映射 + 松手才落盘**——拖动只改本地态（拇指跟手），松手才
 * `setSliderPosition`（存值 = `0.45 − 滑块`，映射纯函数在暖陶 [VoiceCallSensitivity]·internal 直接 import）。
 * `remember(storedSlider)` 的 key 也逐字照抄：外部值变了才重新播种本地态。
 */
@Composable
fun LiuliVoiceCallSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceCallSettingsViewModel = hiltViewModel(),
) {
    val storedSlider by viewModel.sliderPosition.collectAsStateWithLifecycle()
    LiuliVoiceCallSettingsContent(
        storedSlider = storedSlider,
        onCommitSlider = viewModel::setSliderPosition,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 语音通话设置页内容层（纯参数·可测）。[onCommitSlider] 只在**松手**时收到一次。 */
@Composable
internal fun LiuliVoiceCallSettingsContent(
    storedSlider: Float,
    onCommitSlider: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.voice_call_settings_title)
    // 拖动中只更本地态、松手才落盘（逐字照暖陶 :44 / :79）。
    var localSlider by remember(storedSlider) { mutableFloatStateOf(storedSlider) }
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
                    LiuliGroup(footer = stringResource(R.string.voice_call_settings_footer)) {
                        LiuliRowBase(
                            divider = false,
                            verticalPadding = LiuliPageGeometry.rowTwoLinePad,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                                Text(
                                    stringResource(R.string.voice_call_settings_sensitivity_label),
                                    style = AppTypography.body,
                                    color = colors.text.primary,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.voice_call_settings_harder),
                                        style = AppTypography.secondary,
                                        color = colors.text.secondary,
                                    )
                                    LiuliSlider(
                                        value = localSlider,
                                        onValueChange = { localSlider = it },
                                        onValueChangeFinished = { onCommitSlider(localSlider) },
                                        valueRange = VoiceCallSensitivity.SLIDER_MIN..VoiceCallSensitivity.SLIDER_MAX,
                                        steps = VoiceCallSensitivity.SLIDER_STEPS,
                                        modifier = Modifier.weight(1f).padding(horizontal = END_LABEL_GAP),
                                    )
                                    Text(
                                        stringResource(R.string.voice_call_settings_easier),
                                        style = AppTypography.secondary,
                                        color = colors.text.secondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
