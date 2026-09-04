@file:OptIn(ExperimentalMaterial3Api::class)

package com.situ.aichat.ui.story

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.story.PersonaPresets
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import kotlinx.coroutines.launch

/** 计数行转警示色的阈值（图纸 §4.4：超 280 变色，300 拒收）。`internal` = 创建屏同一口径复用（卷四）。 */
internal const val PACING_WARN_CHARS = 280

/**
 * 统一编辑页（故事二期卷二·提案 §11 = A5 的解法）——**全案文本设定唯一的编辑长相**：
 * 忌口 / 身份 / 技法 / 规则 / 节拍 / 画像 / 节奏 + 档案九节 + 三个全局变体（忌口 / 场面节拍 / 口味画像），
 * 共 19 种入口一个页面。
 *
 * 结构（mockup 屏 6 下半）：顶栏（字段名 + 副题）→ 三态段（仅三态字段）→ 继承层只读预览 / 已关闭说明
 * → 身份预设 chips（仅写作身份）→ 正文区（+ 节奏偏好的计数）→ 档案族注解 → 底部「恢复默认 / 保存」。
 *
 * 未保存返回弹确认（F3 防丢稿口径）；保存防重入 + 失败不返回。进程死亡不做草稿恢复（图纸 J7）。
 */
@Composable
fun StoryFieldEditorScreen(
    onBack: () -> Unit,
    viewModel: StoryFieldEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    // 路由参数不认识（老链接 / 脏参数）→ 不渲染半截页，直接退出。
    LaunchedEffect(viewModel.invalid) { if (viewModel.invalid) onBack() }

    val s = state
    fun leave() {
        if (s?.dirty == true) confirmDiscard = true else onBack()
    }
    BackHandler { leave() }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            // 标题走 VM 的 titleRes 单源（本书字段读注册表 / 三个全局哨兵各自的词条·装载中也已就位）；
            // 副标题移进内容区顶部第一行（图纸 §4.7）——门楣的 title 槽只收单行居中标题。
            AppTopBar(
                title = stringResource(viewModel.titleRes),
                onBack = { leave() },
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        if (s == null) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 副标题（原在顶栏 title 槽第二行·取值表达式原样搬含空白守卫）：横向内边距由外层 Column
                // 已给的 16dp 承担，这里只补竖向 8dp（图纸 §4.7·落值登记 §11 D-5）。
                val subtitle = when {
                    s.field == null -> null
                    s.isArchive -> s.bookTitle
                    else -> stringResource(R.string.story_field_editor_sub_book)
                }
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = AppTypography.settingsRowSubtitle,
                        color = AppTheme.colors.text.secondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                if (s.showModeSegment) ModeSegment(s.mode, viewModel::setMode)
                when {
                    s.mode == StoryFieldMode.FOLLOW -> InheritedPreview(s.inheritedText)
                    s.mode == StoryFieldMode.OFF -> Text(
                        stringResource(R.string.story_field_off_hint),
                        style = AppTheme.typography.secondary,
                        color = AppTheme.colors.text.tertiary,
                    )
                    else -> {
                        if (s.showPresetChips) PresetChips(R.string.story_field_chips_hint, viewModel::applyPreset)
                        AppTextArea(
                            value = s.text,
                            onValueChange = viewModel::setText,
                            minHeight = 220.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        s.maxChars?.let { max -> CharCounter(s.text.length, max) }
                        if (s.isArchive) {
                            Text(
                                stringResource(R.string.story_field_archive_note),
                                style = AppTheme.typography.caption.copy(fontSize = 10.5.sp),
                                color = AppTheme.colors.text.tertiary,
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(bottom = 4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (s.factoryDefault != null) {
                    AppButton(onClick = { confirmRestore = true }, style = AppButtonStyle.Tonal) {
                        Text(stringResource(R.string.story_field_restore_default))
                    }
                }
                Spacer(Modifier.weight(1f))
                AppButton(
                    onClick = { scope.launch { if (viewModel.save()) onBack() } },
                    style = AppButtonStyle.Primary,
                    enabled = !saving,
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }

    if (confirmDiscard) {
        AppDialog(
            onDismissRequest = { confirmDiscard = false },
            title = stringResource(R.string.story_field_discard_title),
            confirmText = stringResource(R.string.story_field_discard_yes),
            onConfirm = { confirmDiscard = false; onBack() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.story_field_discard_no),
            onDismiss = { confirmDiscard = false },
        )
    }

    if (confirmRestore) {
        AppDialog(
            onDismissRequest = { confirmRestore = false },
            title = stringResource(R.string.story_field_restore_title),
            body = stringResource(R.string.story_field_restore_body),
            confirmText = stringResource(R.string.story_field_restore_default),
            onConfirm = { confirmRestore = false; viewModel.restoreDefault() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmRestore = false },
        )
    }

    error?.let { msg ->
        AppDialog(
            onDismissRequest = viewModel::dismissError,
            title = stringResource(R.string.story_settings_save_failed),
            body = msg,
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = viewModel::dismissError,
        )
    }
}

@Composable
private fun ModeSegment(mode: StoryFieldMode, onSelect: (StoryFieldMode) -> Unit) {
    AppSegmentedControl(
        options = listOf(StoryFieldMode.FOLLOW, StoryFieldMode.CUSTOM, StoryFieldMode.OFF),
        selected = mode,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
        label = {
            stringResource(
                when (it) {
                    StoryFieldMode.FOLLOW -> R.string.story_field_mode_follow
                    StoryFieldMode.CUSTOM -> R.string.story_field_mode_custom
                    StoryFieldMode.OFF -> R.string.story_field_mode_off
                },
            )
        },
    )
}

/** 「跟随全局」态：把这一层**实际会注入**的文本原样摊开（只读·凹陷底），不让用户猜跟随的是什么。 */
@Composable
private fun InheritedPreview(text: String?) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface.sunken).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.story_field_inherit_label),
            style = AppTheme.typography.caption,
            color = c.text.tertiary,
        )
        Text(
            text ?: stringResource(R.string.story_field_off_hint),
            style = AppTheme.typography.secondary.copy(fontSize = 12.sp),
            color = c.text.secondary,
        )
    }
}

/**
 * 写作身份专属：三档预设 chips，点一下把全文填进草稿，用户继续改（不落库·图纸 §4.4）。
 * 三档文案单源 = [PersonaPresets]（物料 M）；触感由 [AppButton] 自带 light，勿再叠一次。
 * `internal` + [hintRes] 形参 = 创建屏共用同一份（卷四·两处只有提示词条不同，绝不复刻组件）。
 */
@Composable
internal fun PresetChips(@StringRes hintRes: Int, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PersonaPresets.all.forEach { (labelRes, text) ->
                AppButton(
                    onClick = { onPick(text) },
                    style = AppButtonStyle.Tonal,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(stringResource(labelRes)) }
            }
        }
        Text(stringResource(hintRes), style = AppTheme.typography.caption, color = AppTheme.colors.text.tertiary)
    }
}

/**
 * 节奏偏好的计数行：超 [PACING_WARN_CHARS] 转警示色，到顶就拒收新输入（绝不静默截·E6）。
 * `internal` = 创建屏的节奏栏复用同一份（卷四 §4.4·两处计数长相必须一致，绝不复刻第二份）。
 */
@Composable
internal fun CharCounter(length: Int, max: Int) {
    Text(
        stringResource(R.string.story_editor_count_limited, length, max),
        style = AppTheme.typography.caption.copy(fontSize = 11.sp),
        color = if (length > PACING_WARN_CHARS) AppTheme.colors.status.onWarning else AppTheme.colors.text.tertiary,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}
