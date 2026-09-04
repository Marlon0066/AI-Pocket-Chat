package com.situ.aichat.ui.settings

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppColors
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.profile.UserProfileViewModel
import com.situ.aichat.util.LocaleManager

/**
 * 设置页（SETTINGS_REORG·2026-07-02 过审）：九组新序按「日常优先」排（用户自报高频 = 外观 + API 置顶，
 * 装完不动的沉底）。分组口径见 `FABLE5_SETTINGS_REORG_PROPOSAL.md` §3（取代 PROFILE_REDESIGN §3-D2 旧口径）。
 * 高级门（D4）只覆盖提示词模块 + 上下文日志两行（展开动画 + 徽标），其余项恒显；
 * 行 = 紧凑自绘（D7），部分行尾回显当前值（D6·动态四路由 [SettingsOverviewViewModel] 供数）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenApiConfig: () -> Unit,
    onOpenApiFunctions: () -> Unit,
    onOpenMemorySettings: () -> Unit,
    onOpenSystemToggles: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenImmersiveSettings: () -> Unit,
    onOpenStickerManagement: () -> Unit,
    onOpenGrowthSettings: () -> Unit,
    onOpenReplyRules: () -> Unit,
    onOpenContentFilter: () -> Unit,
    onOpenCalendarAwareness: () -> Unit,
    onOpenWorldBooks: () -> Unit,
    onOpenPromptModules: () -> Unit,
    onOpenTtsConfig: () -> Unit,
    onOpenVoiceCallSettings: () -> Unit,
    onOpenDiarySettings: () -> Unit,
    onOpenMomentSettings: () -> Unit,
    onOpenStoryGlobalSettings: () -> Unit,
    onOpenWorldSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenBackgroundReliability: () -> Unit,
    onOpenContextLog: () -> Unit,
    onOpenPerfCollect: () -> Unit,
    onOpenAbout: () -> Unit,
    apiViewModel: ApiConfigViewModel = hiltViewModel(),
    profileViewModel: UserProfileViewModel = hiltViewModel(),
    overviewViewModel: SettingsOverviewViewModel = hiltViewModel(),
) {
    val active by apiViewModel.activeConfig.collectAsStateWithLifecycle()
    val advancedEnabled by profileViewModel.advancedModeEnabled.collectAsStateWithLifecycle()
    val emotionAnimEnabled by profileViewModel.emotionAnimationEnabled.collectAsStateWithLifecycle()
    val textingToneEnabled by profileViewModel.textingToneEnabled.collectAsStateWithLifecycle()
    // D6 行尾回显：外观（配色+深浅）/ TTS 提供商 / 通知总开关。
    val palette by overviewViewModel.themePalette.collectAsStateWithLifecycle()
    val appearanceMode by overviewViewModel.appearanceMode.collectAsStateWithLifecycle()
    val ttsProvider by overviewViewModel.ttsProvider.collectAsStateWithLifecycle()
    val notifEnabled by overviewViewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val worldVividnessTier by overviewViewModel.worldVividnessTier.collectAsStateWithLifecycle()
    val embedderLoadState by overviewViewModel.embedderLoadState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLangDialog by remember { mutableStateOf(false) }
    val currentTag = LocaleManager.currentTag(context)
    val advancedBadge = stringResource(R.string.settings_advanced_badge)

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_screen_title),
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
                .contentMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ① 个性化（D1：用户自报高频，随手就摸的放最顺手）
            SettingsGroupCard(stringResource(R.string.settings_group_personalize)) {
                SettingsRow(
                    Icons.Filled.Palette,
                    stringResource(R.string.appearance_title),
                    value = "${stringResource(palette.labelRes())} · ${stringResource(appearanceMode.labelRes())}",
                    onClick = onOpenAppearance,
                )
                SettingsRow(Icons.Filled.Mood, stringResource(R.string.settings_sticker_title), onClick = onOpenStickerManagement)
            }

            // ② API 与模型（D1：换模型 = 用户自报高频，紧随其后）
            SettingsGroupCard(stringResource(R.string.settings_group_api)) {
                SettingsRow(
                    Icons.Filled.Dns,
                    stringResource(R.string.settings_api_config),
                    subtitle = active?.let { "${it.providerName} · ${it.modelName}" } ?: stringResource(R.string.settings_api_not_configured),
                    onClick = onOpenApiConfig,
                )
                SettingsRow(Icons.Filled.SwapHoriz, stringResource(R.string.api_fn_assign_title), subtitle = stringResource(R.string.api_fn_assign_subtitle), onClick = onOpenApiFunctions)
            }

            // ③ 聊天行为：管「TA 怎么回消息」
            SettingsGroupCard(stringResource(R.string.settings_group_chat_behavior)) {
                SettingsRow(Icons.Filled.Forum, stringResource(R.string.reply_rule_title), subtitle = stringResource(R.string.reply_rule_entry_subtitle), onClick = onOpenReplyRules)
                SettingsSwitchRow(
                    title = stringResource(R.string.chat_effect_emotion_title),
                    checked = emotionAnimEnabled,
                    onCheckedChange = { profileViewModel.setEmotionAnimation(it) },
                    icon = Icons.Filled.AutoAwesome,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_texting_tone_title),
                    subtitle = stringResource(R.string.settings_texting_tone_desc),
                    checked = textingToneEnabled,
                    onCheckedChange = { profileViewModel.setTextingTone(it) },
                    icon = Icons.AutoMirrored.Filled.Chat,
                )
                SettingsRow(Icons.Filled.Bedtime, stringResource(R.string.immersive_settings_title), onClick = onOpenImmersiveSettings)
                SettingsRow(Icons.Filled.FilterAlt, stringResource(R.string.content_filter_title), onClick = onOpenContentFilter)
            }

            // ④ 记忆与设定：管「TA 记得什么、是谁」。记忆 = 二合一 hub（D3）。
            SettingsGroupCard(stringResource(R.string.settings_group_memory_lore)) {
                SettingsRow(Icons.Filled.Memory, stringResource(R.string.mem_settings_title), subtitle = stringResource(R.string.mem_settings_entry_subtitle), onClick = onOpenMemorySettings)
                SettingsRow(AppFeatureIcons.Worldbook, stringResource(R.string.wb_hub_title), onClick = onOpenWorldBooks)
                SettingsRow(Icons.Filled.Insights, stringResource(R.string.growth_settings_title), subtitle = stringResource(R.string.growth_settings_entry_subtitle), onClick = onOpenGrowthSettings)
                SettingsRow(Icons.Filled.CalendarMonth, stringResource(R.string.cal_title), onClick = onOpenCalendarAwareness)
                AdvancedGatedRow(advancedEnabled) {
                    SettingsRow(Icons.Filled.Extension, stringResource(R.string.pm_title), badge = advancedBadge, onClick = onOpenPromptModules)
                }
            }

            // ⑤ 语音
            SettingsGroupCard(stringResource(R.string.settings_group_voice)) {
                SettingsRow(Icons.Filled.GraphicEq, stringResource(R.string.settings_tts_row_title), value = ttsProvider.displayName, onClick = onOpenTtsConfig)
                SettingsRow(Icons.Filled.Call, stringResource(R.string.voice_call_settings_title), onClick = onOpenVoiceCallSettings)
            }

            // ⑥ AI 自动创作
            SettingsGroupCard(stringResource(R.string.settings_group_auto_content)) {
                SettingsRow(Icons.Filled.Book, stringResource(R.string.diary_settings_title), onClick = onOpenDiarySettings)
                SettingsRow(Icons.Filled.Groups, stringResource(R.string.moment_settings_title), onClick = onOpenMomentSettings)
            }

            // ⑥.2 故事（卷四 J1·提案 §10.1：全局创作偏好的唯一的家·与世界组同构「一组一行」）
            SettingsGroupCard(stringResource(R.string.settings_group_story)) {
                SettingsRow(AppFeatureIcons.Story, stringResource(R.string.story_global_settings_title), subtitle = stringResource(R.string.story_global_settings_subtitle), onClick = onOpenStoryGlobalSettings)
            }

            // ⑥.5 世界（W13 图纸 §4.3·行尾回显鲜活度档名）
            SettingsGroupCard(stringResource(R.string.settings_group_world)) {
                SettingsRow(
                    Icons.Filled.Public,
                    stringResource(R.string.world_settings_title),
                    value = stringResource(
                        when (worldVividnessTier) {
                            AppSettings.WORLD_VIVIDNESS_LITE -> R.string.world_vividness_lite_name
                            AppSettings.WORLD_VIVIDNESS_RICH -> R.string.world_vividness_rich_name
                            else -> R.string.world_vividness_std_name
                        },
                    ),
                    onClick = onOpenWorldSettings,
                )
            }

            // ⑦ 系统与通知：装完基本不动；通知 + 后台保障配对（「提醒不响」一处全解决）。
            SettingsGroupCard(stringResource(R.string.settings_group_system)) {
                SettingsRow(Icons.Filled.Notifications, stringResource(R.string.notif_settings_title), value = onOffLabel(notifEnabled), onClick = onOpenNotificationSettings)
                SettingsRow(Icons.Filled.Bolt, stringResource(R.string.bg_title), subtitle = stringResource(R.string.bg_entry_subtitle), onClick = onOpenBackgroundReliability)
                SettingsRow(Icons.Filled.Tune, stringResource(R.string.sys_settings_title), subtitle = stringResource(R.string.sys_settings_entry_subtitle), onClick = onOpenSystemToggles)
                SettingsRow(Icons.Filled.Language, stringResource(R.string.settings_language), value = langLabel(currentTag), onClick = { showLangDialog = true })
            }

            // ⑧ 数据与诊断
            SettingsGroupCard(stringResource(R.string.settings_group_data)) {
                SettingsRow(Icons.Filled.Backup, stringResource(R.string.backup_title), onClick = onOpenBackup)
                AdvancedGatedRow(advancedEnabled) {
                    SettingsRow(Icons.AutoMirrored.Filled.ReceiptLong, stringResource(R.string.settings_context_log_title), badge = advancedBadge, onClick = onOpenContextLog)
                }
                AdvancedGatedRow(advancedEnabled) {
                    SettingsRow(Icons.Filled.Speed, stringResource(R.string.perf_title), badge = advancedBadge, onClick = onOpenPerfCollect)
                }
                // 深层记忆状态（记忆健壮性 #3·只读诊断行·被动读 loadState 不触发加载·FABLE5_EMBEDDER_STATUS_UI_PROPOSAL）。
                AdvancedGatedRow(advancedEnabled) {
                    EmbedderStatusRow(embedderLoadState)
                }
            }

            // ⑨ 关于（高级开关并入本组·D4：门后只剩两行，开关文案点名它们）
            SettingsGroupCard(stringResource(R.string.settings_group_about)) {
                SettingsRow(Icons.Filled.Info, stringResource(R.string.about_title), value = BuildConfig.VERSION_NAME, onClick = onOpenAbout)
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_advanced_toggle_title),
                    subtitle = stringResource(R.string.settings_advanced_toggle_subtitle),
                    checked = advancedEnabled,
                    onCheckedChange = { profileViewModel.setAdvancedMode(it) },
                    icon = Icons.Filled.Settings,
                )
            }
        }
    }

    if (showLangDialog) {
        LanguageDialog(
            current = currentTag,
            onDismiss = { showLangDialog = false },
            onSelect = { tag ->
                showLangDialog = false
                if (tag != currentTag) {
                    // 13.10d：33+ 由框架自动重建界面（返回 false）；<33 需手动 recreate（返回 true）。
                    if (LocaleManager.setLanguage(context, tag)) {
                        (context as? Activity)?.recreate()
                    }
                }
            },
        )
    }
}

/** 分组卡片（标题 + appCardSurface·设计语言 v2·标题挂 heading()）。`internal` = 同包全局子屏复用同一份长相（卷四）。 */
@Composable
internal fun SettingsGroupCard(title: String, content: @Composable () -> Unit) {
    // gutter 16→20（军规）；标题 start 8→4 保观感缩进 24（20+4）与子页分区对齐（V-c）。
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).semantics { heading() },
        )
        Column(Modifier.fillMaxWidth().appCardSurface()) { content() }
    }
}

/**
 * 分组卡内的单条设置入口——**委托 [AppSettingsRow]**（R1 复核 🟡-1 返工 2026-09-04）。
 *
 * 原为一份私有自绘行（22dp 裸图标 + M3 `bodyLarge` + 16dp padding + 52dp 行高）。C9 把同屏的
 * [com.situ.aichat.ui.components.SettingsSwitchRow] 换成 [AppSettingsRow]（30dp 陶土瓦片 + 13sp 题 +
 * 18dp padding + 56dp 行高）之后，**同一张分组卡里出现两种行语言**——图标左缘、文字左缘、题字号三处对不齐
 * （收编前两者都走 M3 列表行，观感反而是齐的）。故本函数改为薄委托：长相单源收归 [AppSettingsRow]，
 * 本层只保留「导航行恒有 chevron」这一条语义默认。
 *
 * `internal`：同包的 [StoryGlobalSettingsScreen] 复用同一份长相，绝不复刻第二份。
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    value: String? = null,
    badge: String? = null,
) {
    AppSettingsRow(
        title = title,
        modifier = Modifier.fillMaxWidth(),
        subtitle = subtitle,
        icon = icon,
        value = value,
        badge = badge,
        showChevron = true,
        onClick = onClick,
    )
}

/**
 * 深层记忆（向量嵌入器）加载状态只读行（记忆健壮性 #3·诊断区）：镜像 [SettingsRow] 布局但**不可点、无箭头**——
 * 图标 + 标题 + 随态副标题 + 行尾（色点 + 状态词）。三态：已就绪(柔绿) / 待唤起(灰·中性) / 暂不可用(琥珀)；
 * 色点纯装饰，语义由状态词与副标题承载。数据 = [SettingsOverviewViewModel.embedderLoadState]（被动·读不触发加载）。
 * `internal` 便于渲染测试（三态各显正确文案）。
 */
@Composable
internal fun EmbedderStatusRow(state: TextEmbedder.LoadState) {
    val statusWord = stringResource(
        when (state) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_unavailable
        },
    )
    val hint = stringResource(
        when (state) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_hint_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_hint_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_hint_unavailable
        },
    )
    val dotColor = dotColorFor(state, AppTheme.colors)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.embedder_status_title), style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(10.dp).background(dotColor, CircleShape))
        Text(statusWord, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * 深层记忆状态行的色点取色（记忆健壮性 #3）：已就绪=柔绿 onSuccess / 待唤起=中性灰 tertiary / 暂不可用=琥珀 onWarning。
 * 抽成纯函数便于 T1 断言三态映射不被误配（色点纯装饰·语义由状态词承载，故 error 红档留给真错误）。
 */
internal fun dotColorFor(state: TextEmbedder.LoadState, colors: AppColors): Color =
    when (state) {
        TextEmbedder.LoadState.LOADED -> colors.status.onSuccess
        TextEmbedder.LoadState.NOT_ATTEMPTED -> colors.text.tertiary
        TextEmbedder.LoadState.FAILED -> colors.status.onWarning
    }

/** 高级门控行（D4）：随页底开关展开 / 收起（不再整组凭空出现）；系统「减少动画」时直切。 */
@Composable
private fun AdvancedGatedRow(visible: Boolean, content: @Composable () -> Unit) {
    if (rememberReduceMotion()) {
        if (visible) content()
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            content()
        }
    }
}

/** D6 布尔回显文案：已开启 / 已关闭。 */
@Composable
private fun onOffLabel(enabled: Boolean): String =
    stringResource(if (enabled) R.string.settings_state_on else R.string.settings_state_off)

@Composable
private fun langLabel(tag: String): String =
    if (tag == "en") stringResource(R.string.lang_option_en) else stringResource(R.string.lang_option_zh)

@Composable
private fun LanguageDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    // 语言只保留「简体中文 / English」两项（i18n Phase 0 移除「跟随系统」）。
    val options = listOf(
        "zh-CN" to R.string.lang_option_zh,
        "en" to R.string.lang_option_en,
    )
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_language),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column {
                options.forEach { (tag, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = tag == current, onClick = { onSelect(tag) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppRadio(selected = tag == current, onClick = { onSelect(tag) })
                        Text(
                            stringResource(labelRes),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
    )
}
