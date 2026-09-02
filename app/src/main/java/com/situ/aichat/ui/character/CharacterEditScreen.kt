package com.situ.aichat.ui.character

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppTheme
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.prompt.ZodiacCalculator
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.worldbook.WorldBookBindingSection
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.util.WallpaperStore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import androidx.compose.ui.res.stringArrayResource
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality

/**
 * Create / edit a character. Ports the field set of iOS `CharacterDetailView` (edit form):
 * avatar + 基础信息 + 角色设定 + 交流风格 + 高级. Growth dimensions & relationship (P4), city
 * (P5, 高德), offline theme (P10) and prompt-module overrides (P2.5) are intentionally deferred.
 * Voice (remote TTS: voice id / emotion / speed / pitch) is editable here; the system voice id is set
 * on the TTS settings screen. Visuals are native Material 3.
 *
 * [onSaved] receives the conversation uuid in create mode (so the caller can open the chat) or null
 * in edit mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(
    onCancel: () -> Unit,
    onSaved: (conversationUuid: String?) -> Unit,
    onEditModules: (characterUuid: String) -> Unit = {},
    onOpenOfflineMeetings: (characterUuid: String) -> Unit = {},
    onOpenWorldBooks: () -> Unit = {},
    focusVoiceSection: Boolean = false,
    viewModel: CharacterEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val compiling by viewModel.compiling.collectAsStateWithLifecycle()
    val personaNeedsSave by viewModel.personaNeedsSave.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingAvatarCropUri by remember { mutableStateOf<Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // 选完图先进圆形取景裁剪屏（甲 3）；「就这样」才存裁好的成品图，「取消」不改原头像。
        pendingAvatarCropUri = uri
    }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    // 本次编辑会话内裁剪产生的中间成品图路径（只含本会话 WallpaperStore.save 出来的·绝不含 DB 在用路径）；
    // 重选/移除时即时回收防孤儿（复核 confirmed MED）；取消/退出不保存遗留的由冷启 WallpaperMaintenanceService 兜底。
    val sessionCroppedWallpapers = remember { mutableListOf<String>() }
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // 选完图先进裁剪取景编辑器（契约 §10 C1）；「完成」才存裁好的成品图，「取消」不改。
        pendingCropUri = uri
    }

    var showDatePicker by remember { mutableStateOf(false) }

    // VU1 §4.4：从拨号门深链带 focusVoice=true 进来时，一次性滚到语音区（闩锁·PITFALLS 1d「功成身退」）。
    val scrollState = rememberScrollState()
    var voiceSectionY by remember { mutableStateOf<Int?>(null) }
    var focusScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(voiceSectionY) {
        if (focusVoiceSection && !focusScrolled) {
            voiceSectionY?.let { scrollState.animateScrollTo(it); focusScrolled = true }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (viewModel.isEditing) R.string.char_title_edit else R.string.char_title_create), modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    AppButton(onClick = onCancel, style = AppButtonStyle.Text) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    AppButton(
                        onClick = { viewModel.save(onSaved) },
                        style = AppButtonStyle.Text,
                        enabled = state.canSave && !saving,
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding() // C4：键盘弹起时滚动视口让位，下半部字段可滚到键盘上方（国行 IME 高达屏 40%+）
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Avatar ----
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        CharacterAvatar(
                            name = state.name.ifEmpty { "?" },
                            avatarPath = state.avatarPath,
                            size = 96.dp,
                            modifier = Modifier.clickable {
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.char_avatar_change),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(6.dp).clickable {
                                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                        }
                    }
                    if (state.avatarPath != null) {
                        AppButton(onClick = { viewModel.update { it.copy(avatarPath = null) } }, style = AppButtonStyle.Text, danger = true) {
                            Text(stringResource(R.string.char_avatar_remove))
                        }
                    }
                }
            }

            // ---- 聊天壁纸 ----
            SectionHeader(stringResource(R.string.char_section_wallpaper))
            WallpaperPicker(
                wallpaperPath = state.chatWallpaperPath,
                onPick = { pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = {
                    sessionCroppedWallpapers.forEach { WallpaperStore.delete(it) }
                    sessionCroppedWallpapers.clear()
                    viewModel.update { it.copy(chatWallpaperPath = null) }
                },
            )
            Text(
                stringResource(R.string.char_wallpaper_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- 基础信息 ----
            SectionHeader(stringResource(R.string.char_section_basic))
            FormField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = stringResource(R.string.char_field_name),
                placeholder = stringResource(R.string.char_hint_name),
                singleLine = true,
            )
            FormField(
                value = state.gender,
                onValueChange = { v -> viewModel.update { it.copy(gender = v) } },
                label = stringResource(R.string.char_field_gender),
                placeholder = stringResource(R.string.char_hint_gender),
                singleLine = true,
            )
            // Birthday + zodiac
            if (state.birthdayMillis == null) {
                AppButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal) {
                    Text(stringResource(R.string.char_field_birthday) + "：" + stringResource(R.string.char_birthday_unset))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).clickable { showDatePicker = true }) {
                        Text(stringResource(R.string.char_field_birthday), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(DateFormat.getDateInstance(DateFormat.LONG).format(Date(state.birthdayMillis!!)))
                        val zodiac = remember(state.birthdayMillis) { ZodiacCalculator.zodiacSign(state.birthdayMillis!!) }
                        if (zodiac.isNotEmpty()) {
                            Text(
                                stringResource(R.string.char_field_zodiac) + "：" + zodiac,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.update { it.copy(birthdayMillis = null) } }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.char_birthday_clear))
                    }
                }
            }
            // Age mode
            Text(stringResource(R.string.char_age_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppSegmentedControl(
                options = listOf(AgeMode.GROWING, AgeMode.FIXED),
                selected = state.ageModeRaw,
                onSelect = { mode -> viewModel.update { it.copy(ageModeRaw = mode) } },
                modifier = Modifier.fillMaxWidth(),
                label = { stringResource(if (it == AgeMode.GROWING) R.string.char_age_growing else R.string.char_age_fixed) },
            )
            if (state.ageModeRaw == AgeMode.FIXED) {
                FormField(
                    value = state.fixedAge,
                    onValueChange = { v -> viewModel.update { it.copy(fixedAge = v.filter(Char::isDigit)) } },
                    label = stringResource(R.string.char_field_fixed_age),
                    placeholder = "",
                    singleLine = true,
                    keyboardType = KeyboardType.Number,
                )
            } else {
                state.birthdayMillis?.let { millis ->
                    val age = remember(millis) { yearsSince(millis) }
                    if (age > 0) {
                        Text(
                            stringResource(R.string.char_age_current) + "：" + stringResource(R.string.char_age_value, age),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SectionFooter(stringResource(R.string.char_footer_basic))

            // ---- 关系（14.1e-2：定义 + 快捷标签 → 播种/追加里程碑）----
            SectionHeader(stringResource(R.string.char_section_relationship_def))
            FormField(
                value = state.relationshipName,
                onValueChange = { v -> viewModel.update { it.copy(relationshipName = v) } },
                label = stringResource(R.string.char_section_relationship_def),
                placeholder = stringResource(R.string.char_hint_relationship),
                singleLine = true,
            )
            val quickTags = stringArrayResource(R.array.char_relationship_quick_tags)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickTags.forEach { tag ->
                    AppChoiceChip(
                        selected = state.relationshipName == tag,
                        onClick = { viewModel.update { it.copy(relationshipName = tag) } },
                        label = tag,
                    )
                }
            }
            SectionFooter(stringResource(if (viewModel.isEditing) R.string.char_footer_relationship_edit else R.string.char_footer_relationship_create))

            // ---- 角色设定 ----
            SectionHeader(stringResource(R.string.char_section_setup))
            FormField(
                value = state.occupation,
                onValueChange = { v -> viewModel.update { it.copy(occupation = v) } },
                label = stringResource(R.string.char_field_occupation),
                placeholder = stringResource(R.string.char_hint_occupation),
                singleLine = true,
            )
            FormField(
                value = state.personalityDescription,
                onValueChange = { v -> viewModel.update { it.copy(personalityDescription = v) } },
                label = stringResource(R.string.char_field_personality),
                placeholder = stringResource(R.string.char_hint_personality),
                footer = stringResource(R.string.char_footer_personality),
            )
            FormField(
                value = state.appearanceDescription,
                onValueChange = { v -> viewModel.update { it.copy(appearanceDescription = v) } },
                label = stringResource(R.string.char_field_appearance),
                placeholder = stringResource(R.string.char_hint_appearance),
                footer = stringResource(R.string.char_footer_appearance),
            )
            FormField(
                value = state.backstory,
                onValueChange = { v -> viewModel.update { it.copy(backstory = v) } },
                label = stringResource(R.string.char_field_backstory),
                placeholder = stringResource(R.string.char_hint_backstory),
                footer = stringResource(R.string.char_footer_backstory),
            )
            SectionFooter(stringResource(R.string.char_footer_setup))

            // ---- 交流风格 ----
            SectionHeader(stringResource(R.string.char_section_communication))
            FormField(
                value = state.catchphrases,
                onValueChange = { v -> viewModel.update { it.copy(catchphrases = v) } },
                label = stringResource(R.string.char_field_catchphrases),
                placeholder = stringResource(R.string.char_hint_catchphrases),
                singleLine = true,
            )
            FormField(
                value = state.speakingStyle,
                onValueChange = { v -> viewModel.update { it.copy(speakingStyle = v) } },
                label = stringResource(R.string.char_field_speaking),
                placeholder = stringResource(R.string.char_hint_speaking),
                footer = stringResource(R.string.char_footer_speaking),
            )
            FormField(
                value = state.exampleDialogues,
                onValueChange = { v -> viewModel.update { it.copy(exampleDialogues = v) } },
                label = stringResource(R.string.char_field_examples),
                placeholder = stringResource(R.string.char_hint_examples),
                footer = stringResource(R.string.char_footer_examples),
            )
            FormField(
                value = state.initialInterests,
                onValueChange = { v -> viewModel.update { it.copy(initialInterests = v) } },
                label = stringResource(R.string.char_field_interests),
                placeholder = stringResource(R.string.char_hint_interests),
                footer = stringResource(R.string.char_footer_interests),
            )
            SectionFooter(stringResource(R.string.char_footer_communication))

            // ---- 性格光谱（活人感内核·卷一：原地扩展为「本性锚点」+ 生成卡·图纸 §4.1/§4.2/D-7）----
            SectionHeader(stringResource(R.string.char_section_personality))
            // 生成卡只在编辑模式露面：新建时角色还没 uuid、编译无从谈起，首次保存后自动跑一次（D-1/Y-E21）。
            if (viewModel.isEditing) {
                PersonaCompileCard(
                    meta = state.personaCompileMeta,
                    personaStale = state.personaStale,
                    personaBlank = state.personalityDescription.isBlank(),
                    compiling = compiling,
                    needsSave = personaNeedsSave,
                    onCompile = { viewModel.compilePersona() },
                )
            }
            val personalityHints = stringArrayResource(R.array.char_personality_hints)
            PersonalitySpectrum.DIMENSION_NAMES.forEachIndexed { i, dim ->
                PersonaAnchorSlider(
                    name = dim,
                    hint = personalityHints.getOrElse(i) { "" },
                    anchor = state.personalityAnchor.values[i],
                    current = state.personalitySpectrum.values[i],
                    basis = state.personaCompileMeta.anchorBasis[PersonalitySpectrum.DIMENSION_KEYS[i]],
                    onChange = { v -> viewModel.update { it.copy(personalityAnchor = it.personalityAnchor.setValue(i, v)) } },
                )
            }
            SectionFooter(stringResource(R.string.char_footer_personality_spectrum))
            PersonaGainsSection(
                gains = state.personaGains,
                onChange = { g -> viewModel.update { it.copy(personaGains = g) } },
            )
            PersonaOperatorsSection(
                operators = state.personaOperators,
                onChange = { ops -> viewModel.update { it.copy(personaOperators = ops) } },
            )

            // ---- 关系质感（8 维滑块·14.1e）----
            SectionHeader(stringResource(R.string.char_section_relationship))
            val relationshipHints = stringArrayResource(R.array.char_relationship_hints)
            RelationshipQuality.DIMENSION_NAMES.forEachIndexed { i, dim ->
                DimensionSlider(
                    name = dim,
                    hint = relationshipHints.getOrElse(i) { "" },
                    value = state.relationshipQuality.values[i],
                    onChange = { v -> viewModel.update { it.copy(relationshipQuality = it.relationshipQuality.setValue(i, v)) } },
                )
            }
            SectionFooter(stringResource(R.string.char_footer_relationship))

            // ---- 线下主题色（14.1e-2：预设色板，空=默认 teal）----
            SectionHeader(stringResource(R.string.char_section_offline_theme))
            OfflineThemeColorPicker(
                selectedHex = state.offlineThemeColorHex,
                onSelect = { hex -> viewModel.update { it.copy(offlineThemeColorHex = hex) } },
            )
            SectionFooter(stringResource(R.string.char_footer_offline_theme))

            // ---- 高级 ----
            SectionHeader(stringResource(R.string.char_section_advanced))
            FormField(
                value = state.systemPrompt,
                onValueChange = { v -> viewModel.update { it.copy(systemPrompt = v) } },
                label = stringResource(R.string.char_field_system_prompt),
                placeholder = stringResource(R.string.char_hint_system_prompt),
                footer = stringResource(R.string.char_footer_system_prompt),
            )

            // ---- 语音（P10.1b-2 音色·情绪·语速·音调 + P10.1c 系统音色选择 + 试听） ----
            val systemVoices by viewModel.systemVoices.collectAsStateWithLifecycle()
            val voicePreviewBusy by viewModel.previewBusy.collectAsStateWithLifecycle()
            val voicePreviewError by viewModel.previewError.collectAsStateWithLifecycle()
            // VU1 §4.4（R1 🟡-2 修订）：定位锚**包裹**语音区而非独立空 Box——spacedBy(12.dp) 会给每个子项
            // 发一个间距槽，独立空 Box 会凭空多出 12dp 空隙。⚠️ 必须用同 spacedBy(12.dp) 的 Column 包（不是 Box）：
            // VoiceSettingsSection 平铺多个兄弟节点、靠父 Column 排版，Box 会把它们全部叠置（R1 装机实证）。
            Column(
                modifier = Modifier.onGloballyPositioned { voiceSectionY = it.positionInParent().y.roundToInt() },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VoiceSettingsSection(
                    voiceIdentifier = state.voiceIdentifier,
                    remoteVoiceId = state.remoteVoiceID,
                    emotionRaw = state.ttsEmotionRaw,
                    speed = state.ttsSpeed,
                    pitch = state.ttsPitch,
                    systemVoices = systemVoices,
                    previewBusy = voicePreviewBusy,
                    previewError = voicePreviewError,
                    onLoadSystemVoices = viewModel::loadSystemVoices,
                    onPreview = viewModel::preview,
                    onUpdate = viewModel::update,
                )
            }

            // ---- 提示词模块（角色级覆盖，仅编辑模式 → P2.5） ----
            if (viewModel.isEditing) {
                val hasOverride by viewModel.hasModuleOverride.collectAsStateWithLifecycle()
                SectionHeader(stringResource(R.string.pm_title))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pm_use_character_override), modifier = Modifier.weight(1f))
                    AppSwitch(checked = hasOverride, onCheckedChange = { viewModel.setModuleOverride(it) })
                }
                if (hasOverride) {
                    AppButton(onClick = { viewModel.editingUuid?.let(onEditModules) }, style = AppButtonStyle.Text) {
                        Text(stringResource(R.string.pm_edit_character_modules))
                    }
                } else {
                    SectionFooter(stringResource(R.string.pm_using_global))
                }
                SectionFooter(stringResource(R.string.pm_use_character_override_footer))
            }

            // ---- 世界（加入世界开关 + 住址·W13 图纸 §4.1·与世界书二选一） ----
            if (viewModel.isEditing) {
                CharacterWorldSection()
            } else {
                CharacterWorldCreateSection(
                    joined = state.joinWorld,
                    onToggle = { on -> viewModel.update { it.copy(joinWorld = on) } },
                )
            }

            // ---- 世界观（世界书绑定·仅编辑模式·WB7c 契约 FABLE5_WORLDBOOK_PROPOSAL.md §12.5） ----
            if (viewModel.isEditing) {
                SectionHeader(stringResource(R.string.wb_binding_section))
                WorldBookBindingSection(onManageBooks = onOpenWorldBooks)
                SectionFooter(stringResource(R.string.wb_binding_footer))
            }

            // ---- 见面回忆（角色档案，仅编辑模式 → 10.2e M16；也是兜底 Toast 指向的页面） ----
            if (viewModel.isEditing) {
                SectionHeader("见面回忆")
                AppButton(onClick = { viewModel.editingUuid?.let(onOpenOfflineMeetings) }, style = AppButtonStyle.Text) {
                    Text("查看见面回忆")
                }
                SectionFooter("线下见面的纪念卡、只读回顾与规则兜底摘要的手动重试。")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val initial = state.birthdayMillis ?: defaultBirthdayMillis()
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            yearRange = 1900..Calendar.getInstance().get(Calendar.YEAR),
            selectableDates = PastOrPresentDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            colors = DatePickerDefaults.colors(containerColor = AppTheme.colors.surface.raised),
            confirmButton = {
                TextButton(onClick = {
                    viewModel.update { it.copy(birthdayMillis = dpState.selectedDateMillis) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = dpState)
        }
    }

    // 头像圆形取景裁剪屏（甲 3·选图后调整取景·「就这样」才存成品图）。
    pendingAvatarCropUri?.let { uri ->
        AvatarCropScreen(
            uri = uri,
            onCancel = { pendingAvatarCropUri = null },
            onConfirm = { cropped ->
                scope.launch {
                    AvatarStore.save(context, cropped)?.let { path -> viewModel.update { it.copy(avatarPath = path) } }
                }
                pendingAvatarCropUri = null
            },
        )
    }

    // 聊天壁纸裁剪取景编辑器（契约 §10·选图后全屏调整取景·完成存裁好成品图）。
    pendingCropUri?.let { uri ->
        WallpaperCropScreen(
            imageUri = uri,
            onCancel = { pendingCropUri = null },
            onConfirm = { cropped ->
                scope.launch {
                    WallpaperStore.save(context, cropped)?.let { path ->
                        // 即时回收本会话上一张中间成品图（只删本会话 save 出来的·绝不碰 DB 在用壁纸）。
                        sessionCroppedWallpapers.forEach { WallpaperStore.delete(it) }
                        sessionCroppedWallpapers.clear()
                        sessionCroppedWallpapers.add(path)
                        viewModel.update { it.copy(chatWallpaperPath = path) }
                    }
                }
                pendingCropUri = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private object PastOrPresentDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
    override fun isSelectableYear(year: Int): Boolean = year <= Calendar.getInstance().get(Calendar.YEAR)
}

/** 2000-01-01 local, matching iOS's default birthday seed. */
private fun defaultBirthdayMillis(): Long =
    Calendar.getInstance().apply {
        clear()
        set(2000, Calendar.JANUARY, 1)
    }.timeInMillis

private fun yearsSince(millis: Long): Int {
    val birth = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    var age = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
    if (now.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) age--
    return age
}
