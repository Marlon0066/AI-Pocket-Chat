package com.situ.aichat.ui.liuli.settings

import android.app.Activity
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.liuli.designsystem.LiuliSearchSlot
import com.situ.aichat.ui.liuli.home.LiuliNoResults
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.profile.UserProfileViewModel
import com.situ.aichat.ui.settings.ApiConfigViewModel
import com.situ.aichat.ui.settings.SettingsOverviewViewModel
import com.situ.aichat.ui.settings.labelRes
import com.situ.aichat.util.LocaleManager

/** 页底留白：末组脚注 → 导航栏之间的呼吸（契约 §6.5「底内距 = 导航栏 + 24」）。 */
private val PAGE_BOTTOM = 24.dp

/**
 * 设置主页（琉璃·图纸 2026-09-06 卷四 §4.3 T1 · A-4 搜索）。
 *
 * 与暖陶 `SettingsScreen` **共用同三个 VM、同一份分组口径**（FABLE5_SETTINGS_REORG_PROPOSAL）；
 * 这一层只订阅 + 整形，长相全在 [LiuliSettingsContent]（纯参数·可测·整屏被 `hiltViewModel()` 默认形参
 * 掐死的老问题见图纸 F11）。
 */
@Composable
fun LiuliSettingsScreen(
    onBack: () -> Unit,
    callbacks: LiuliSettingsCallbacks,
    modifier: Modifier = Modifier,
    apiViewModel: ApiConfigViewModel = hiltViewModel(),
    profileViewModel: UserProfileViewModel = hiltViewModel(),
    overviewViewModel: SettingsOverviewViewModel = hiltViewModel(),
) {
    val active by apiViewModel.activeConfig.collectAsStateWithLifecycle()
    val advancedEnabled by profileViewModel.advancedModeEnabled.collectAsStateWithLifecycle()
    val emotionAnimEnabled by profileViewModel.emotionAnimationEnabled.collectAsStateWithLifecycle()
    val textingToneEnabled by profileViewModel.textingToneEnabled.collectAsStateWithLifecycle()
    val skin by overviewViewModel.appSkin.collectAsStateWithLifecycle()
    val appearanceMode by overviewViewModel.appearanceMode.collectAsStateWithLifecycle()
    val ttsProvider by overviewViewModel.ttsProvider.collectAsStateWithLifecycle()
    val notifEnabled by overviewViewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val worldVividnessTier by overviewViewModel.worldVividnessTier.collectAsStateWithLifecycle()
    val embedderLoadState by overviewViewModel.embedderLoadState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val currentTag = LocaleManager.currentTag(context)

    val state = LiuliSettingsState(
        activeConfigLabel = active?.let { "${it.providerName} · ${it.modelName}" },
        advancedEnabled = advancedEnabled,
        emotionAnimEnabled = emotionAnimEnabled,
        textingToneEnabled = textingToneEnabled,
        appearanceLabel = "${stringResource(skin.labelRes())} · ${stringResource(appearanceMode.labelRes())}",
        ttsProviderName = ttsProvider.displayName,
        notifEnabled = notifEnabled,
        worldTierLabel = stringResource(
            when (worldVividnessTier) {
                AppSettings.WORLD_VIVIDNESS_LITE -> R.string.world_vividness_lite_name
                AppSettings.WORLD_VIVIDNESS_RICH -> R.string.world_vividness_rich_name
                else -> R.string.world_vividness_std_name
            },
        ),
        embedderState = embedderLoadState,
        currentLangTag = currentTag,
        version = BuildConfig.VERSION_NAME,
    )

    LiuliSettingsContent(
        state = state,
        callbacks = callbacks,
        actions = LiuliSettingsActions(
            onSetEmotionAnimation = { profileViewModel.setEmotionAnimation(it) },
            onSetTextingTone = { profileViewModel.setTextingTone(it) },
            onSetAdvancedMode = { profileViewModel.setAdvancedMode(it) },
            onSelectLanguage = { tag ->
                if (tag != currentTag) {
                    // 13.10d：33+ 由框架自动重建界面（返回 false）；<33 需手动 recreate（返回 true）。
                    if (LocaleManager.setLanguage(context, tag)) {
                        (context as? Activity)?.recreate()
                    }
                }
            },
        ),
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * 设置主页内容层（纯参数）：大标题带 → 搜索槽 → 十一组，收起后标题住进玻璃顶栏。
 *
 * 搜索只筛**组**（A-4）：命中不到的整组不组合，全都不组合就显无结果条。
 */
@Composable
internal fun LiuliSettingsContent(
    state: LiuliSettingsState,
    callbacks: LiuliSettingsCallbacks,
    actions: LiuliSettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var term by rememberSaveable { mutableStateOf("") }
    var showLangDialog by rememberSaveable { mutableStateOf(false) }
    val advancedBadge = stringResource(R.string.settings_advanced_badge)
    val bottomInset = PAGE_BOTTOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = stringResource(R.string.settings_screen_title),
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(stringResource(R.string.settings_screen_title)) }
            item(key = "search") {
                LiuliSearchSlot(
                    value = term,
                    onValueChange = { term = it },
                    placeholder = stringResource(R.string.settings_search_hint),
                    clearContentDescription = stringResource(R.string.chat_list_search_clear),
                    modifier = Modifier.padding(
                        start = LiuliPageGeometry.gutter,
                        end = LiuliPageGeometry.gutter,
                        top = LiuliPageGeometry.titleGap,
                        bottom = LiuliPageGeometry.gutter,
                    ),
                )
            }
            item(key = "groups") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    var shown = 0
                    if (personalizeGroup(term, state, callbacks)) shown++
                    if (apiGroup(term, state, callbacks)) shown++
                    if (chatBehaviorGroup(term, state, callbacks, actions)) shown++
                    if (memoryLoreGroup(term, state, callbacks, advancedBadge)) shown++
                    if (voiceGroup(term, state, callbacks)) shown++
                    if (autoContentGroup(term, callbacks)) shown++
                    if (storyGroup(term, callbacks)) shown++
                    if (worldGroup(term, state, callbacks)) shown++
                    if (systemGroup(term, state, callbacks) { showLangDialog = true }) shown++
                    if (dataGroup(term, state, callbacks, advancedBadge)) shown++
                    if (aboutGroup(term, state, callbacks, actions)) shown++
                    if (shown == 0) {
                        // 文案专用键（复核 R1 🟡-1 裁决 T-1：`contacts_no_results` 实文是「未找到匹配的角色」，设置页读着是错的）。
                        LiuliNoResults(
                            text = stringResource(R.string.settings_search_no_results),
                            clearText = stringResource(R.string.chat_list_search_clear),
                            onClear = { term = "" },
                        )
                    }
                }
            }
        }
    }

    if (showLangDialog) {
        LiuliLanguageDialog(
            current = state.currentLangTag,
            onDismiss = { showLangDialog = false },
            onSelect = { tag ->
                showLangDialog = false
                actions.onSelectLanguage(tag)
            },
        )
    }
}
