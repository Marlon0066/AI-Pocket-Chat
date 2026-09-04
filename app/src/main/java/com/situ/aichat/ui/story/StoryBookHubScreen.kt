@file:OptIn(ExperimentalMaterial3Api::class)

package com.situ.aichat.ui.story

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryArcPlanning
import com.situ.aichat.story.StoryGlobalCraftValues
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlinx.coroutines.launch

/** 头部副行的三段拼装料（纯逻辑·空书两值皆 null → 副行只剩状态文案·E1）。 */
internal data class StoryHubProgress(val chapterNumber: Int?, val arcIndex: Int?)

/** 副行取值：没有章就不报章号；有章且有弧起点才报「本弧第 K 章」（K 由既有变速箱纯函数算）。 */
internal fun storyHubProgress(story: StoryEntity): StoryHubProgress {
    val chapterNumber = story.cachedLatestChapterNumber?.takeIf { story.cachedChapterCount > 0 }
    val arcStart = story.currentArcStartChapter
    val arcIndex = if (chapterNumber != null && arcStart != null) {
        StoryArcPlanning.arcIndex(arcStart, chapterNumber)
    } else {
        null
    }
    return StoryHubProgress(chapterNumber, arcIndex)
}

/** 「继续阅读」显隐：一章都没有的书没得读（E1）。 */
internal fun storyHubShowContinue(story: StoryEntity): Boolean = story.cachedChapterCount > 0

/**
 * 书页（故事二期卷二·提案 §8·mockup 屏 1/2）——**这本书全部资产的家**：
 * 头部（封面 + 书名 + 进度 + 继续阅读）+ 双 Tab（档案八节 / 设定四组）。
 *
 * 取代原「故事设定屏 + 独立圣经编辑屏」两处分裂面（审计 A2/D-10），路由名 `storySettings/{storyId}` 与
 * 两处调用点原样复用。关闭（顶栏返回 / 系统返回）落库草稿后再返回，**保存失败不返回**（沿用旧屏口径）。
 */
@Composable
fun StoryBookHubScreen(
    onBack: () -> Unit,
    /** 这本书没了（归档 / 删除 / 被别处删掉）→ 一路弹回书架，别退回一个空的章节列表。 */
    onStoryGone: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenField: (String) -> Unit,
    /** 「全局写作偏好 →」→ App 设置的故事创作子屏（卷四：全局项已从书页迁走）。 */
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
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { resId -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }
    val scope = rememberCoroutineScope()
    // 归档成功 / 删除成功 → 这本书在书页里已经没得看了，弹回书架（= 创建流的既有回法）。
    LaunchedEffect(Unit) { viewModel.exitEvents.collect { onStoryGone() } }
    // 兜底：进过屏之后 story 变 null（别处把它删了）→ 安全退出，不停在空屏上（E8）。
    var loadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(story) {
        if (story != null) loadedOnce = true else if (loadedOnce) onStoryGone()
    }

    // 关闭：落库草稿后返回（在屏幕协程内 await，避免 VM scope 被 pop 取消截断写入）；保存失败不返回。
    fun close() = scope.launch { if (viewModel.persist()) onBack() }
    BackHandler { close() }

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var beatsDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_hub_title),
                onBack = { close() },
                lifted = listState.canScrollBackward,
            )
        },
    ) { padding ->
        val s = story ?: return@Scaffold
        val d = draft
        Column(Modifier.fillMaxSize().padding(padding)) {
            BookHeader(s) {
                scope.launch { viewModel.latestChapterId()?.let(onOpenChapter) }
            }
            AppSegmentedControl(
                options = listOf(0, 1),
                selected = tabIndex,
                onSelect = { tabIndex = it },
                modifier = Modifier.padding(horizontal = 16.dp),
                label = { stringResource(if (it == 0) R.string.story_hub_tab_archive else R.string.story_hub_tab_settings) },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tabIndex == 0) {
                    storyHubArchiveItems(
                        story = s,
                        onOpenField = { field -> onOpenField(field.key) },
                        onOpenBeats = { beatsDialog = true },
                        regenerating = regenerating,
                        // 由屏幕协程 await（照 persist 房规）：VM scope 会随路由 pop 取消，重排要跑数十秒
                        onRegenerateOutline = { scope.launch { viewModel.regenerateOutline() } },
                    )
                } else if (d != null) {
                    storyHubSettingsItems(
                        story = s,
                        draft = d,
                        roles = roles,
                        globals = StoryGlobalCraftValues(
                            sceneBeats = settings.storySceneBeats,
                            tasteProfile = settings.storyTasteProfile,
                            bannedExpressions = settings.storyBannedExpressions,
                        ),
                        hasWorldBooks = hasWorldBooks,
                        reminderEnabled = reminderEnabled,
                        templateCount = templateCount,
                        callbacks = StoryHubSettingsCallbacks(
                            onOpenField = { field -> onOpenField(field.key) },
                            onOpenGlobalSettings = onOpenGlobalSettings,
                            onUpdateDraft = viewModel::updateDraft,
                            onSaveRole = viewModel::saveRole,
                            onDeleteRole = viewModel::deleteRole,
                            onDraftPersona = if (hasCreationConfig) viewModel::draftPersona else null,
                            onChapterChoicesChange = viewModel::setChapterChoicesEnabled,
                            onSceneSnapshotChange = viewModel::setSceneSnapshotEnabled,
                            onWorldInfoChange = viewModel::setWorldInfoEnabled,
                            onReminderChange = viewModel::setReminderEnabled,
                            onSaveTemplate = viewModel::saveAsTemplate,
                            onArchive = viewModel::archiveStory,
                            onDelete = viewModel::deleteStory,
                            onContinue = { scope.launch { viewModel.persist(); viewModel.continueOrResume(); onBack() } },
                            onRestart = { scope.launch { viewModel.persist(); viewModel.restartStory(); onBack() } },
                        ),
                    )
                }
            }
        }
    }

    if (beatsDialog) {
        BeatsReadOnlyDialog(story?.pendingChapterBeats.orEmpty()) { beatsDialog = false }
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

/** 头部：封面缩略 + 书名 + 状态/进度副行 + 「继续阅读」（空书不给这个钮）。 */
@Composable
private fun BookHeader(story: StoryEntity, onContinue: () -> Unit) {
    val c = AppTheme.colors
    val progress = storyHubProgress(story)
    var line = stringResource(storyStatusDisplayNameRes(story.status))
    progress.chapterNumber?.let { line = stringResource(R.string.story_hub_progress_chapter, line, it) }
    progress.arcIndex?.let { line = stringResource(R.string.story_hub_progress_arc, line, it) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        StoryCover(
            coverColorScheme = story.coverColorScheme,
            title = story.title,
            storyId = story.id,
            modifier = Modifier.size(width = 56.dp, height = 76.dp),
            shape = RoundedCornerShape(10.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(story.title, style = AppTheme.typography.titleMedium, color = c.text.primary)
            Text(line, style = AppTheme.typography.secondary, color = c.text.secondary)
        }
        if (storyHubShowContinue(story)) {
            AppButton(onClick = onContinue, style = AppButtonStyle.Primary) {
                Text(stringResource(R.string.story_hub_continue))
            }
        }
    }
}

/** 下一章节拍的只读全文弹窗（编辑收口在卷三导演台·本卷只看不改）。 */
@Composable
private fun BeatsReadOnlyDialog(beats: String, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.story_hub_beats_dialog_title),
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        content = {
            Text(
                beats,
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
        },
    )
}
