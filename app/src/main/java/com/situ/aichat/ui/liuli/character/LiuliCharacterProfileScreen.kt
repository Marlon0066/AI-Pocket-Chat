package com.situ.aichat.ui.liuli.character

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.starfield.StarfieldEntryCard
import com.situ.aichat.ui.ourdays.ProfileOurDaysCard
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.economy.SalaryEditWarningFlag
import com.situ.aichat.ui.character.PromiseCardState
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.profile.CharacterWalletActivity
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.GiftImpressionTag
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.profile.CompanionStats
import com.situ.aichat.ui.character.CharacterProfileViewModel
import com.situ.aichat.ui.character.ProfileTab
import com.situ.aichat.ui.character.ScheduleCardState
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.liuli.page.LiuliActionRow
import com.situ.aichat.ui.liuli.page.LiuliHeroHeader
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliStatCard
import com.situ.aichat.ui.liuli.page.LiuliTabStrip
import com.situ.aichat.ui.liuli.page.rememberHeroCollapsed
import com.situ.aichat.ui.meeting.MeetingCountdownChip

/** 页底留白（图纸 §4.3：`contentPadding(bottom = 32 + 导航栏)`·同暖陶）。 */
private val PAGE_BOTTOM = 32.dp
/** 头图视差系数（A-8：图跟着滚一半·RM 关）。 */
private const val PARALLAX = 0.5f
/** 分段条在列表里的 item 序（0 头图 · 1 动作排 + 统计 · 2 分段条 · 3+ 各节）。 */
private const val SECTION_FIRST_INDEX = 3

/**
 * 角色资料页（琉璃·图纸 2026-09-06 卷四 §4.3 T3 · A-8–A-11）。
 *
 * 与暖陶 `CharacterProfileScreen` 共用 [CharacterProfileViewModel]、共用三段分类与各节顺序 / 条件；
 * 这一层只订阅 + 转手，长相在 [LiuliCharacterProfileContent]（纯参数·可测）。
 */
@Composable
fun LiuliCharacterProfileScreen(
    onBack: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenOfflineMeetings: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
    onOpenStarfield: (String) -> Unit,
    onOpenOurDays: (String) -> Unit,
    onEditMemory: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CharacterProfileViewModel = hiltViewModel(),
) {
    val character by viewModel.character.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val scheduleEnabled by viewModel.scheduleEnabled.collectAsStateWithLifecycle()
    val scheduleCard by viewModel.scheduleCard.collectAsStateWithLifecycle()
    val impressionTags by viewModel.impressionTags.collectAsStateWithLifecycle()
    val receivedGifts by viewModel.receivedGifts.collectAsStateWithLifecycle()
    val milestones by viewModel.milestones.collectAsStateWithLifecycle()
    val growthLog by viewModel.growthLog.collectAsStateWithLifecycle()
    val nextMeeting by viewModel.nextMeetingCountdown.collectAsStateWithLifecycle()
    val promiseCard by viewModel.promiseCard.collectAsStateWithLifecycle()
    val offlineSessions by viewModel.offlineSessions.collectAsStateWithLifecycle()
    val retryingOfflineSessions by viewModel.retryingOfflineSessions.collectAsStateWithLifecycle()
    val memoryStats by viewModel.memoryStats.collectAsStateWithLifecycle()
    val structuredMemory by viewModel.structuredMemory.collectAsStateWithLifecycle()
    val memoryGuardBlocked by viewModel.memoryGuardBlocked.collectAsStateWithLifecycle()
    val organizingMemory by viewModel.organizingMemory.collectAsStateWithLifecycle()
    val dynamicInterests by viewModel.dynamicInterests.collectAsStateWithLifecycle()
    val personalitySpectrum by viewModel.personalitySpectrum.collectAsStateWithLifecycle()
    val relationshipQuality by viewModel.relationshipQuality.collectAsStateWithLifecycle()
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val walletActivity by viewModel.walletActivity.collectAsStateWithLifecycle()
    val walletHasNews by viewModel.walletHasNews.collectAsStateWithLifecycle()

    // 「立即整理」结果 toast（记忆护栏第二层 MG-U3·一次性事件·逐字搬暖陶）。
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.memoryGuardToast.collect { resId ->
            Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
        }
    }

    val c = character ?: return
    LiuliCharacterProfileContent(
        character = c,
        stats = stats,
        milestones = milestones,
        impressionTags = impressionTags,
        receivedGifts = receivedGifts,
        growthLog = growthLog,
        scheduleEnabled = scheduleEnabled,
        scheduleCard = scheduleCard,
        nextMeetingChip = nextMeeting?.let { appt -> { MeetingCountdownChip(appt = appt, characterName = c.name) } },
        promiseCard = promiseCard,
        offlineSessions = offlineSessions,
        retryingOfflineSessions = retryingOfflineSessions,
        memoryStats = memoryStats,
        structuredMemory = structuredMemory,
        memorySummary = c.memorySummary,
        memoryGuardBlocked = memoryGuardBlocked,
        organizingMemory = organizingMemory,
        dynamicInterests = dynamicInterests,
        personalitySpectrum = personalitySpectrum,
        relationshipQuality = relationshipQuality,
        wallet = wallet,
        walletActivity = walletActivity,
        walletHasNews = walletHasNews,
        onBack = onBack,
        onEditCharacter = { onEditCharacter(c.uuid) },
        onOpenSchedule = { onOpenSchedule(c.uuid) },
        onOpenPromises = { onOpenPromises(c.uuid) },
        onOpenOurDays = { onOpenOurDays(c.uuid) },
        onOpenStarfield = { onOpenStarfield(c.uuid) },
        onOpenOfflineMeetings = { onOpenOfflineMeetings(c.uuid) },
        onEditMemory = { onEditMemory(c.uuid) },
        onRetrySchedule = viewModel::retrySchedule,
        onRetryOfflineFallback = viewModel::retryOfflineFallback,
        onOrganizeMemoryNow = viewModel::organizeMemoryNow,
        onSaveSalary = { text, day -> viewModel.saveSalary(text, day) },
        walletWarningShown = { SalaryEditWarningFlag.hasShown(context) },
        onMarkWalletWarningShown = { SalaryEditWarningFlag.markShown(context) },
        modifier = modifier,
    )
}

/**
 * 资料页内容层（纯参数·可测）。骨架：item 0 头图 → item 1 动作排 + 统计卡 → item 2 纸面分段条 →
 * 3+ 当前段的各节；收起后分段条住进玻璃顶栏的 `subBar` 槽（A-11）。
 */
@Composable
internal fun LiuliCharacterProfileContent(
    character: CharacterEntity,
    stats: CompanionStats?,
    milestones: List<MilestoneEntity>,
    impressionTags: List<GiftImpressionTag>,
    receivedGifts: List<GiftRecordEntity>,
    growthLog: List<GrowthLogEntry>,
    scheduleEnabled: Boolean,
    scheduleCard: ScheduleCardState,
    nextMeetingChip: (@Composable () -> Unit)?,
    promiseCard: PromiseCardState,
    offlineSessions: List<OfflineMeetingSession>,
    retryingOfflineSessions: Set<String>,
    memoryStats: StructuredMemoryStats.Result,
    structuredMemory: StructuredMemory,
    memorySummary: String,
    memoryGuardBlocked: Boolean,
    organizingMemory: Boolean,
    dynamicInterests: List<DynamicInterest>,
    personalitySpectrum: PersonalitySpectrum,
    relationshipQuality: RelationshipQuality,
    wallet: CharacterWalletEntity?,
    walletActivity: CharacterWalletActivity.Summary,
    walletHasNews: Boolean,
    onBack: () -> Unit,
    onEditCharacter: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenPromises: () -> Unit,
    onOpenOurDays: () -> Unit,
    onOpenStarfield: () -> Unit,
    onOpenOfflineMeetings: () -> Unit,
    onEditMemory: () -> Unit,
    onRetrySchedule: () -> Unit,
    onRetryOfflineFallback: (String) -> Unit,
    onOrganizeMemoryNow: () -> Unit,
    onSaveSalary: (String, Int) -> Unit,
    walletWarningShown: () -> Boolean,
    onMarkWalletWarningShown: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** 真状态栏高（测试可注入·复核 R1 🔴-1：收起判据与切段落位都得算上它，不能拿 0 或名义 44 顶替）。 */
    statusBarTop: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
) {
    val density = LocalDensity.current
    val heroPx = with(density) { LiuliPageGeometry.hero.roundToPx() }
    // 收起顶栏实高 = 真状态栏 + 44（契约「图底 − 88」的 88 是 44 状态栏时的名义值·`heroCollapseTail`）。
    val barPx = with(density) { (statusBarTop + LiuliPageGeometry.compactBar).roundToPx() }
    val collapsed = rememberHeroCollapsed(listState, heroPx, barPx)
    val reduceMotion = rememberReduceMotion()
    val nowMillis = remember { System.currentTimeMillis() }
    var selectedTab by rememberSaveable { mutableStateOf(ProfileTab.NEAR) }
    // 钱包弹窗 / 面板挂屏级（状态本就提升在这一层，免受 item 回收影响·同暖陶）。
    var showWalletEdit by rememberSaveable { mutableStateOf(false) }
    var showWalletWarning by rememberSaveable { mutableStateOf(false) }
    val bottomInset = PAGE_BOTTOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 覆盖区 = 真状态栏 + 44 + 56（A-11 的 144 = 44 状态栏时的名义值）——漏掉状态栏就是装机 `v4_12` 首项被盖一截。
    val coverPx = with(density) { LiuliPageGeometry.cover(statusBarTop, hasSubBar = true).roundToPx() }

    // 收起态切段：把新段首项顶边送到覆盖区之下（负 scrollOffset·A-11）；未收起时不滚。
    LaunchedEffect(selectedTab) {
        if (collapsed) listState.animateScrollToItem(SECTION_FIRST_INDEX, -coverPx)
    }

    LiuliPage(
        title = character.name,
        onBack = onBack,
        collapsed = collapsed,
        hero = true,
        modifier = modifier,
        actions = {
            LiuliPageCircleAction(
                onClick = onEditCharacter,
                contentDescription = stringResource(R.string.profile_edit_character),
                icon = Icons.Filled.Edit,
            )
        },
        subBar = { LiuliTabStrip(selected = selectedTab, onSelect = { selectedTab = it }, glass = true) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomInset),
        ) {
            item(key = "hero", contentType = "hero") {
                // 外层裁边：视差把图往下挪半程，不裁会压进 item 1 的动作排底下（复核 R1 🟡-4）；裁掉的那条空带在视口之上。
                Box(Modifier.clipToBounds()) {
                    LiuliHeroHeader(
                        name = character.name,
                        avatarPath = character.avatarPath,
                        relationshipLabel = relationshipPillLabel(milestones),
                        subtitle = heroSubtitle(character, nowMillis),
                        modifier = Modifier.graphicsLayer {
                            // 视差：图跟着滚一半（只在它自己还是首个可见项时才有意义·RM 直接不视差）。
                            translationY = if (reduceMotion || listState.firstVisibleItemIndex > 0) {
                                0f
                            } else {
                                listState.firstVisibleItemScrollOffset * PARALLAX
                            }
                        },
                    )
                }
            }
            item(key = "actions_stats", contentType = "actions_stats") {
                Box(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    Column {
                        LiuliActionRow(
                            profileActionItems(
                                onOpenSchedule = onOpenSchedule,
                                onOpenPromises = onOpenPromises,
                                onOpenOurDays = onOpenOurDays,
                                onOpenStarfield = onOpenStarfield,
                            ),
                        )
                        LiuliStatCard(statCardItems(character, stats))
                    }
                }
            }
            item(key = "tabs", contentType = "tabs") {
                LiuliTabStrip(selected = selectedTab, onSelect = { selectedTab = it })
            }
            when (selectedTab) {
                ProfileTab.NEAR -> nearSection(
                    character = character,
                    impressionTags = impressionTags,
                    receivedGifts = receivedGifts,
                    growthLog = growthLog,
                    scheduleEnabled = scheduleEnabled,
                    scheduleCard = scheduleCard,
                    nowMillis = nowMillis,
                    nextMeetingChip = nextMeetingChip,
                    onRetrySchedule = onRetrySchedule,
                    onOpenSchedule = onOpenSchedule,
                )
                ProfileTab.STORY -> storySection(
                    promiseCard = promiseCard,
                    offlineSessions = offlineSessions,
                    retryingOfflineSessions = retryingOfflineSessions,
                    memoryStats = memoryStats,
                    structuredMemory = structuredMemory,
                    memorySummary = memorySummary,
                    memoryGuardBlocked = memoryGuardBlocked,
                    organizingMemory = organizingMemory,
                    milestones = milestones,
                    nowMillis = nowMillis,
                    onOpenStarfield = onOpenStarfield,
                    onOpenOurDays = onOpenOurDays,
                    onOpenPromises = onOpenPromises,
                    onOpenOfflineMeetings = onOpenOfflineMeetings,
                    onRetryOfflineFallback = onRetryOfflineFallback,
                    onOrganizeMemoryNow = onOrganizeMemoryNow,
                    onEditMemory = onEditMemory,
                )
                ProfileTab.PROFILE -> aboutSection(
                    character = character,
                    dynamicInterests = dynamicInterests,
                    personalitySpectrum = personalitySpectrum,
                    relationshipQuality = relationshipQuality,
                    wallet = wallet,
                    walletActivity = walletActivity,
                    walletHasNews = walletHasNews,
                    nowMillis = nowMillis,
                    onEditCharacter = onEditCharacter,
                    onEditWallet = {
                        if (walletWarningShown()) showWalletEdit = true else showWalletWarning = true
                    },
                )
            }
        }
    }
    if (showWalletWarning) {
        LiuliDialog(
            onDismissRequest = { showWalletWarning = false },
            title = stringResource(R.string.wallet_edit_warning_title),
            body = stringResource(R.string.wallet_edit_warning_message),
            confirmText = stringResource(R.string.wallet_edit_warning_continue),
            onConfirm = {
                onMarkWalletWarningShown()
                showWalletWarning = false
                showWalletEdit = true
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showWalletWarning = false },
        )
    }
    if (showWalletEdit) {
        LiuliWalletEditSheet(
            initialSalary = wallet?.monthlySalary ?: 0,
            salaryInferred = wallet?.salaryInferred ?: false,
            initialSalaryDay = wallet?.salaryDay ?: 15,
            onDismiss = { showWalletEdit = false },
            onSave = onSaveSalary,
        )
    }
}
