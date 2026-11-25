package com.situ.aichat.ui.liuli.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.StoryGlobalCraftValues
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmentedStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.story.StoryBookHubEffects
import com.situ.aichat.ui.story.StoryHubBookHeader
import com.situ.aichat.ui.story.StoryHubSettingsCallbacks
import com.situ.aichat.ui.story.StorySettingsDraft
import com.situ.aichat.ui.story.StorySettingsViewModel
import com.situ.aichat.ui.story.rememberStoryHubClose
import com.situ.aichat.ui.story.storyHubArchiveItems
import com.situ.aichat.ui.story.storyHubGlobals
import com.situ.aichat.ui.story.storyHubSettingsCallbacks
import com.situ.aichat.ui.story.storyHubTabLabelRes
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch

internal val HUB_ITEM_GAP = 10.dp // 列表项缝（= 暖陶 spacedBy 10）；设定 Tab 卡外操作钮之间同缝
private val REGEN_ICON = 16.dp // 重排行转圈 16（= 暖陶 AppLoadingRingSize.Small 直径）
/** 书页要画的数据（无 VM 页的入参·测试直接造）。 */
@Immutable
internal data class LiuliHubData(
    val story: StoryEntity?,
    val draft: StorySettingsDraft?,
    val roles: List<StoryCharacterRoleEntity>,
    val globals: StoryGlobalCraftValues,
    val hasWorldBooks: Boolean,
    val reminderEnabled: Boolean,
    val templateCount: Int,
    val regenerating: Boolean,
)

/**
 * 琉璃书页（琉璃 2.0 卷六·三·上 §4.9·设计稿 S3）：与暖陶 [com.situ.aichat.ui.story.StoryBookHubScreen] 共用同一个 VM、
 * 进页事 / 关闭（先落库、失败不返回 + BackHandler）/ 回调接线 / 档案八节与头部内容件；封面头与切换条移进列表，
 * 往上滑收成胶囊「书页」+ 玻璃切换条；档案卡换半透明卡、设定 Tab 换琉璃分组卡与行族。
 */
@Composable
internal fun LiuliStoryBookHubScreen(
    onBack: () -> Unit,
    onStoryGone: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenField: (String) -> Unit,
    onOpenGlobalSettings: () -> Unit,
    viewModel: StorySettingsViewModel = hiltViewModel(),
) {
    val story by viewModel.story.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val hasWorldBooks by viewModel.hasWorldBooks.collectAsStateWithLifecycle()
    val hasCreationConfig by viewModel.hasCreationConfig.collectAsStateWithLifecycle()
    val reminderEnabled by viewModel.reminderEnabled.collectAsStateWithLifecycle()
    val templateCount by viewModel.userTemplateCount.collectAsStateWithLifecycle()
    val regenerating by viewModel.regenerating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    StoryBookHubEffects(viewModel, story, onStoryGone)
    val close = rememberStoryHubClose(viewModel, scope, onBack)
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var beatsDialog by remember { mutableStateOf(false) }
    LiuliStoryBookHubPage(
        data = LiuliHubData(story, draft, roles, storyHubGlobals(settings), hasWorldBooks, reminderEnabled, templateCount, regenerating),
        tabIndex = tabIndex,
        onTabChange = { tabIndex = it },
        onClose = close,
        onContinue = { scope.launch { viewModel.latestChapterId()?.let(onOpenChapter) } },
        onOpenField = { field -> onOpenField(field.key) },
        onOpenBeats = { beatsDialog = true },
        // 由屏幕协程 await（照 persist 房规）：VM scope 会随路由 pop 取消，重排要跑数十秒
        onRegenerateOutline = { scope.launch { viewModel.regenerateOutline() } },
        settingsCallbacks = storyHubSettingsCallbacks(viewModel, scope, hasCreationConfig, onOpenField, onOpenGlobalSettings, onBack),
        listState = rememberLazyListState(),
    )

    if (beatsDialog) {
        LiuliDialog(
            onDismissRequest = { beatsDialog = false }, title = stringResource(R.string.story_hub_beats_dialog_title),
            confirmText = stringResource(R.string.action_close), onConfirm = { beatsDialog = false },
            content = {
                Text(
                    story?.pendingChapterBeats.orEmpty(),
                    style = AppTypography.secondary,
                    color = LiuliTheme.onGlass.secondary,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
        )
    }
    error?.let { LiuliStoryAlert(stringResource(R.string.story_settings_save_failed), it, viewModel::dismissError) }
}

/** 无 VM 的书页（测试直接驱动它）。 */
@Composable
internal fun LiuliStoryBookHubPage(
    data: LiuliHubData,
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    onClose: () -> Unit,
    onContinue: () -> Unit,
    onOpenField: (StoryEditableField) -> Unit,
    onOpenBeats: () -> Unit,
    onRegenerateOutline: () -> Unit,
    settingsCallbacks: StoryHubSettingsCallbacks,
    listState: LazyListState,
) {
    val title = stringResource(R.string.story_hub_title)
    val scrolledPast = rememberLargeTitleCollapsed(listState)
    val collapsed = scrolledPast && data.story != null
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gutter = LiuliPageGeometry.gutter
    val tabLabel: @Composable (Int) -> String = { stringResource(storyHubTabLabelRes(it)) }
    LiuliPage(
        title, onBack = onClose, collapsed = collapsed,
        subBar = { LiuliSegmented(listOf(0, 1), tabIndex, tabLabel, onTabChange, style = LiuliSegmentedStyle.Glass, role = Role.Tab) },
    ) {
        val s = data.story ?: return@LiuliPage
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            contentPadding = PaddingValues(gutter, LiuliPageGeometry.navRow, gutter, navBarBottom + LiuliPageGeometry.pageBottom),
            verticalArrangement = Arrangement.spacedBy(HUB_ITEM_GAP),
        ) {
            item(key = "hub-header") {
                StoryHubBookHeader(s, Modifier.fillMaxWidth().padding(top = LiuliPageGeometry.titleTop)) {
                    LiuliButton(onClick = onContinue, style = LiuliButtonStyle.Prominent) { Text(stringResource(R.string.story_hub_continue)) }
                }
            }
            item(key = "hub-tabs") {
                LiuliSegmented(listOf(0, 1), tabIndex, tabLabel, onTabChange, Modifier.fillMaxWidth(), style = LiuliSegmentedStyle.Paper, role = Role.Tab)
            }
            if (tabIndex == 0) {
                storyHubArchiveItems(
                    story = s,
                    onOpenField = onOpenField,
                    onOpenBeats = onOpenBeats,
                    regenerating = data.regenerating,
                    onRegenerateOutline = onRegenerateOutline,
                    cardSurface = { Modifier.liuliStoryCard(LocalIsDarkTheme.current) },
                    regenRow = { r, onClick -> LiuliStoryRegenRow(r, onClick) },
                    regenConfirm = { ok, no -> LiuliStoryRegenConfirm(ok, no) },
                )
            } else {
                data.draft?.let { d ->
                    liuliStoryHubSettingsItems(s, d, data.roles, data.globals, data.hasWorldBooks, data.reminderEnabled, data.templateCount, settingsCallbacks)
                }
            }
        }
    }
}

/** 大纲卡第三区的重排动作行（取代暖陶 `OutlineRegenRow`·其余值同暖陶）：执行中灰字 + 转圈 + 禁点。 */
@Composable
private fun LiuliStoryRegenRow(regenerating: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(0.5.dp).background(LiuliMaterials.divider(LocalIsDarkTheme.current)))
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !regenerating, onClick = onClick).padding(top = 10.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (regenerating) LiuliSpinner(size = REGEN_ICON) else Icon(AppActionIcons.Refresh, contentDescription = null, tint = c.accent.text, modifier = Modifier.size(REGEN_ICON))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (regenerating) R.string.story_outline_regen_running else R.string.story_outline_regen_row),
            style = AppTypography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = if (regenerating) c.text.tertiary else c.accent.text,
        )
    }
}

/** 重排确认（非破坏性·确认钮不走危险色·同暖陶）。 */
@Composable
private fun LiuliStoryRegenConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    LiuliDialog(
        onDismissRequest = onDismiss, title = stringResource(R.string.story_outline_regen_title), body = stringResource(R.string.story_outline_regen_body),
        confirmText = stringResource(R.string.story_outline_regen_confirm), onConfirm = onConfirm,
        dismissText = stringResource(R.string.action_cancel), onDismiss = onDismiss,
    )
}
