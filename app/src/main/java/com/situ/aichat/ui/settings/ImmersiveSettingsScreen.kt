package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlin.math.roundToInt

/**
 * 线下见面设置页（10.2f；仿 iOS `OfflineModeSettingsView`，M3 原生）。
 * 邀约主导权 + 沉浸输入 + 叙事风格（含 custom 三编辑框）+ 见面记忆（次数/字数）+ 背景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveSettingsScreen(
    onBack: () -> Unit,
    viewModel: ImmersiveSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = "线下见面",
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            // 邀约主导权
            SettingsSection(
                title = "邀约主导权",
                footer = "开启时，角色会根据聊天情境主动提议见面。关闭后，只有你从聊天工具栏点「发起线下见面」才会进入线下模式。",
            ) {
                SettingsSwitchRow(
                    title = "角色主动发起见面",
                    subtitle = "关闭后，角色不会主动邀你见面",
                    checked = settings.characterCanInitiateOfflineMeeting,
                    onCheckedChange = { viewModel.setCharacterCanInitiate(it) },
                )
            }

            // 沉浸输入
            SettingsSection(
                title = "沉浸输入",
                footer = "开启后，线下见面时你可以分步描述环境、动作、对话和内心想法，获得更沉浸的角色扮演体验。",
            ) {
                SettingsSwitchRow(
                    title = "线下见面沉浸模式",
                    subtitle = "输入框变为环境→动作→对话→内心四步输入",
                    checked = settings.offlineImmersiveInputEnabled,
                    onCheckedChange = { viewModel.setImmersiveInputEnabled(it) },
                )
            }

            // 叙事风格
            SettingsSection(
                title = "叙事风格",
                footer = narrativeDetailFooter(settings.offlineNarrativeDetailRaw),
            ) {
                NarrativeDetailOptions(
                    selectedRaw = settings.offlineNarrativeDetailRaw,
                    onSelect = { viewModel.setNarrativeDetail(it) },
                )
            }
            // custom 三编辑框卡外裸排（§4.A1·所属分区卡后、下一分区前·padding horizontal 20）。
            if (settings.offlineNarrativeDetailRaw == "custom") {
                CustomPromptEditor(
                    title = "写作风格指导",
                    subtitle = "控制整体的写作风格、对话与描写的比例",
                    value = settings.offlineCustomStylePrompt,
                    placeholder = "对话为主，动作和环境简单穿插。不要用文学腔，像朋友聊天一样自然",
                    onValueChange = { viewModel.setCustomStyle(it) },
                )
                CustomPromptEditor(
                    title = "每轮叙事指令",
                    subtitle = "每行写一条指令，系统每轮随机抽一条发给 AI",
                    value = settings.offlineCustomDirectivePrompt,
                    placeholder = "让角色主动问用户一个问题\n让角色提起一件最近的小事\n让角色注意到周围的某个东西",
                    onValueChange = { viewModel.setCustomDirective(it) },
                )
                CustomPromptEditor(
                    title = "情绪底色",
                    subtitle = "每行写一种情绪氛围，系统每轮随机抽一条",
                    value = settings.offlineCustomEmotionPrompt,
                    placeholder = "轻松愉快的聊天氛围\n安静但不尴尬的相处\n有点想靠近的心情",
                    onValueChange = { viewModel.setCustomEmotion(it) },
                )
            }

            // 见面记忆（余温消息 ListItem :175 无独立标题·折入本段卡·脚注随制式落卡下——见 §11 D-A1）
            SettingsSection(
                title = "见面记忆",
                footer = "注入最近 N 次见面的完整摘要，更早的合并为一行存档；「记忆上限」是注入文本的字数预算，超出时最早的完整摘要自动降为存档行。",
            ) {
                SliderRow(
                    label = "注入最近见面次数",
                    valueLabel = "${settings.meetingMemoryInjectCount} 次",
                    value = settings.meetingMemoryInjectCount.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    onValueChange = { viewModel.setMeetingMemoryInjectCount(it.roundToInt()) },
                )
                // settings-slider-infobutton：用共享 SettingsSliderRow 以挂 ⓘ 说明（iOS 此滑块有 info.circle）。
                SettingsSliderRow(
                    label = "见面记忆上限",
                    valueLabel = "${settings.meetingMemoryMaxLength} 字",
                    value = settings.meetingMemoryMaxLength.toFloat(),
                    valueRange = 200f..3000f,
                    steps = 27, // 200..3000 步进 100
                    infoMessage = stringResource(R.string.mem_meeting_info),
                    onManualInput = { viewModel.setMeetingMemoryMaxLength(it) }, // settings-slider-manualinput（手填不做 100 吸附）
                    onValueChange = { viewModel.setMeetingMemoryMaxLength((it / 100f).roundToInt() * 100) },
                )
                // 见面后余温消息（涟漪①·§3.10）
                SettingsSwitchRow(
                    title = "见面后余温消息",
                    subtitle = "见面结束几小时后，TA 会主动发来一条回味见面的短消息",
                    checked = settings.offlineAfterglowEnabled,
                    onCheckedChange = { viewModel.setOfflineAfterglowEnabled(it) },
                )
            }

            // 背景
            SettingsSection(
                title = "背景",
                footer = "线下模式是角色邀请你面对面见面时的沉浸式叙事界面。",
            ) {
                BackgroundStyleOptions(
                    selectedRaw = settings.offlineBackgroundStyleRaw,
                    onSelect = { viewModel.setBackgroundStyle(it) },
                )
                when (settings.offlineBackgroundStyleRaw) {
                    "particle" -> ParticleStyleOptions(
                        selectedRaw = settings.offlineParticleStyleRaw,
                        onSelect = { viewModel.setParticleStyle(it) },
                    )
                    "solidColor" -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("背景色", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            AppTextField(
                                value = settings.offlineBackgroundColor,
                                onValueChange = { viewModel.setBackgroundColor(it.trim().take(6)) },
                                placeholder = "FF6B6B",
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    keyboardType = KeyboardType.Ascii,
                                ),
                                modifier = Modifier.width(140.dp),
                            )
                        }
                    }
                    // customImage 说明文字进卡（原私有 SectionFooter 已删·卡内行样式 padding 16）。
                    "customImage" -> Text(
                        "自定义背景图请在对应角色的档案中设置（每个角色独立）；未设置时回退到柔和粒子。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 琉璃卷五复用（`ui/liuli` 树借同一份实现·改这里两张脸同时变）。 */
internal fun narrativeDetailFooter(raw: String): String = when (raw) {
    "normal" -> "像真人约会的自然对话风格，偶尔有环境描写和心理活动。"
    "detailed" -> "文学化叙事风格，丰富的感官描写、情绪渲染和叙事技巧。"
    "custom" -> "你可以自己编辑下方的三个模块来控制 AI 的写作方式。"
    else -> "AI 自由发挥，只保留基本格式。适合喜欢自然对话的用户。"
}

@Composable
private fun NarrativeDetailOptions(selectedRaw: String, onSelect: (String) -> Unit) {
    OptionRow("平淡", selectedRaw == "plain") { onSelect("plain") }
    OptionRow("正常", selectedRaw == "normal") { onSelect("normal") }
    OptionRow("细腻", selectedRaw == "detailed") { onSelect("detailed") }
    OptionRow("自定义", selectedRaw == "custom") { onSelect("custom") }
}

@Composable
private fun BackgroundStyleOptions(selectedRaw: String, onSelect: (String) -> Unit) {
    OptionRow("柔和粒子", selectedRaw == "particle") { onSelect("particle") }
    OptionRow("纯色", selectedRaw == "solidColor") { onSelect("solidColor") }
    OptionRow("自定义图片", selectedRaw == "customImage") { onSelect("customImage") }
}

@Composable
private fun ParticleStyleOptions(selectedRaw: String, onSelect: (String) -> Unit) {
    OptionRow("✦ 星光", selectedRaw == "stars") { onSelect("stars") }
    OptionRow("✧ 萤火", selectedRaw == "firefly") { onSelect("firefly") }
    OptionRow("· 微尘", selectedRaw == "dust") { onSelect("dust") }
}

@Composable
private fun OptionRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppRadio(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        AppSlider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun CustomPromptEditor(
    title: String,
    subtitle: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppTextArea(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
