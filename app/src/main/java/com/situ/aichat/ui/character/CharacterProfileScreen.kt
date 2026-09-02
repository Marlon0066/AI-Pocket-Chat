package com.situ.aichat.ui.character

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.economy.SalaryEditWarningFlag
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.meeting.MeetingCountdownChip
import com.situ.aichat.ui.ourdays.ProfileOurDaysCard
import com.situ.aichat.ui.starfield.StarfieldEntryCard

/**
 * 角色资料页（14.1，只读展示）：折叠头（Hero+统计条）+ 吸顶三 Tab（近况/故事/资料）。
 * 结构 = 单 LazyColumn + stickyHeader（图纸 2026-07-15-资料页三Tab重构 D-A）：Hero/Stats 作前两 item 随滚
 * 自然离场，Tab 栏 stickyHeader 吸顶到 TopAppBar 下沿。切 Tab 用点击（AppSegmentedControl·禁横滑/Pager·D-C）；
 * 系统返回直接退屏（不加 BackHandler·D-C）。除共同记忆卡的「记忆原文」外，13 张卡只搬不改（§2「只搬不改」）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CharacterProfileScreen(
    onBack: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenOfflineMeetings: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
    onOpenStarfield: (String) -> Unit,
    /** 「我们的日子」日历页（卷三图纸 §2.2·预选该角色）。 */
    onOpenOurDays: (String) -> Unit,
    /** 记忆手动编辑页（图纸 2026-09-01 件③）。 */
    onEditMemory: (String) -> Unit,
    viewModel: CharacterProfileViewModel = hiltViewModel(),
) {
    val character by viewModel.character.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val scheduleEnabled by viewModel.scheduleEnabled.collectAsStateWithLifecycle()
    val scheduleCard by viewModel.scheduleCard.collectAsStateWithLifecycle()
    val impressionTags by viewModel.impressionTags.collectAsStateWithLifecycle()
    val receivedGifts by viewModel.receivedGifts.collectAsStateWithLifecycle()
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val walletActivity by viewModel.walletActivity.collectAsStateWithLifecycle()
    val offlineSessions by viewModel.offlineSessions.collectAsStateWithLifecycle()
    val memoryStats by viewModel.memoryStats.collectAsStateWithLifecycle()
    val structuredMemory by viewModel.structuredMemory.collectAsStateWithLifecycle()
    val milestones by viewModel.milestones.collectAsStateWithLifecycle()
    val dynamicInterests by viewModel.dynamicInterests.collectAsStateWithLifecycle()
    val growthLog by viewModel.growthLog.collectAsStateWithLifecycle()
    val personalitySpectrum by viewModel.personalitySpectrum.collectAsStateWithLifecycle()
    val relationshipQuality by viewModel.relationshipQuality.collectAsStateWithLifecycle()
    val walletHasNews by viewModel.walletHasNews.collectAsStateWithLifecycle()
    val retryingOfflineSessions by viewModel.retryingOfflineSessions.collectAsStateWithLifecycle()
    val nextMeeting by viewModel.nextMeetingCountdown.collectAsStateWithLifecycle()
    val promiseCard by viewModel.promiseCard.collectAsStateWithLifecycle()
    val memoryGuardBlocked by viewModel.memoryGuardBlocked.collectAsStateWithLifecycle()
    val organizingMemory by viewModel.organizingMemory.collectAsStateWithLifecycle()

    // 角色月薪编辑（14.6b·💰涉钱写）：首次弹一次性提醒（设备本地标记），之后直接开编辑面板。
    val context = LocalContext.current

    // 「立即整理」结果 toast（记忆护栏第二层 MG-U3·一次性事件）。
    LaunchedEffect(Unit) {
        viewModel.memoryGuardToast.collect { resId ->
            Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
        }
    }
    // E1#0：升 rememberSaveable——转屏/进程死亡后月薪面板随之重开，面板内输入（同升 saveable）才真正不丢。
    var showWalletEdit by rememberSaveable { mutableStateOf(false) }
    var showWalletWarning by rememberSaveable { mutableStateOf(false) }

    // 三 Tab 选中态（默认落「近况」·图纸 §3）+ 列表滚动态（转屏/进程死亡后 selectedTab 存活）。
    var selectedTab by rememberSaveable { mutableStateOf(ProfileTab.NEAR) }
    val listState = rememberLazyListState()
    // 切 Tab 吸顶（图纸 D-B/E6）：若已滚过 Tab 栏（index≥2）则把 Tab 栏吸到顶，防新 Tab 内容停在旧偏移。
    LaunchedEffect(selectedTab) {
        if (listState.firstVisibleItemIndex >= 2) listState.scrollToItem(2)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(character?.name.orEmpty(), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    character?.let { c ->
                        IconButton(onClick = { onEditCharacter(c.uuid) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profile_edit_character))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val c = character
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val nowMillis = remember { System.currentTimeMillis() }
        val cardPadding = Modifier.padding(horizontal = 20.dp)
        // 折叠头 + 吸顶三 Tab（图纸 D-A/D-B）：hero/stats 作前两 item 随滚离场 → tabs stickyHeader 吸顶（index 2）
        // → when(selectedTab) 分发对应 Tab 的卡。Tab 分类与各 Tab 内相对序 = 图纸 §3 表（锁定·含成长日志上移近况末条压舱石）。
        // 卡调用点参数逐字节不变（只搬不改），仅换「在哪个 Tab 的 LazyListScope 被 emit」。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // hero 自带 bottom 4dp：与 spacedBy 16 合成 hero→统计条 20dp（1:1 原间距）。
            item(key = "hero", contentType = "hero") { HeroSection(c, Modifier.padding(bottom = 4.dp)) }
            item(key = "stats", contentType = "stats") { StatsBar(character = c, stats = stats, modifier = cardPadding) }
            // 吸顶 Tab 栏（index 2·底色不透明遮挡下方滚动内容）。
            stickyHeader(key = "tabs", contentType = "tabs") { ProfileTabBar(selectedTab) { selectedTab = it } }

            when (selectedTab) {
                ProfileTab.NEAR -> {
                    // 资料页倒数小条（Phase 12·角色级·信息型·复用聊天侧 MeetingCountdownChip）：有「下一个已确认未来约定」时显。
                    nextMeeting?.let { appt ->
                        item(key = "meeting_countdown", contentType = "meeting_countdown") {
                            Box(modifier = cardPadding, contentAlignment = Alignment.Center) {
                                MeetingCountdownChip(appt = appt, characterName = c.name)
                            }
                        }
                    }

                    // 亲友账卡「TA 眼里的你」——标签与礼物都空时整卡不渲染（连同其上间距）。
                    if (impressionTags.isNotEmpty() || receivedGifts.isNotEmpty()) {
                        item(key = "account", contentType = "account") {
                            RelationshipAccountCard(
                                characterName = c.name,
                                tags = impressionTags,
                                gifts = receivedGifts,
                                nowMillis = nowMillis,
                                modifier = cardPadding,
                            )
                        }
                    }

                    // 日程卡「今日行程」（14.2a）——仅 scheduleSystemEnabled 且非 Hidden 才显（含其上间距）。
                    if (scheduleEnabled && scheduleCard !is ScheduleCardState.Hidden) {
                        item(key = "schedule", contentType = "schedule") {
                            ScheduleTimelineCard(
                                state = scheduleCard,
                                onRetry = viewModel::retrySchedule,
                                onOpenFullDay = { onOpenSchedule(c.uuid) },
                                modifier = cardPadding,
                            )
                        }
                    }

                    // 成长日志卡（最近 5 条时间线）——「近况」末条压舱石（图纸 §3·上移自原列表末位·保证 Tab 永不空）。
                    item(key = "growthlog", contentType = "growthlog") {
                        GrowthLogCard(log = growthLog, modifier = cardPadding)
                    }
                }

                ProfileTab.STORY -> {
                    // 记忆星空入口卡（图纸 2026-07-16-记忆星空·A 案）：STORY 顶部新增，既有四卡原样不动。
                    item(key = "starfield_entry", contentType = "starfield_entry") {
                        StarfieldEntryCard(onOpen = { onOpenStarfield(c.uuid) }, modifier = cardPadding)
                    }

                    // 「我们的日子」轻卡（卷三图纸 §2.2·提案 D-2）：紧随星空卡、约定卡之前；空态仍渲染。
                    item(key = "our_days", contentType = "our_days") {
                        ProfileOurDaysCard(onOpen = { onOpenOurDays(c.uuid) }, modifier = cardPadding)
                    }

                    // 我们的约定卡（记忆改造三期·D-1）：一条约定都没有 → 整卡不渲染（含其上间距·照亲友账卡先例）。
                    if (promiseCard.hasAny) {
                        item(key = "promises", contentType = "promises") {
                            ProfilePromisesCard(
                                state = promiseCard,
                                nowMillis = nowMillis,
                                onOpenAll = { onOpenPromises(c.uuid) },
                                modifier = cardPadding,
                            )
                        }
                    }

                    // 见面回忆卡（HorizontalPager）——始终渲染，空态有文案。
                    item(key = "meetings", contentType = "meetings") {
                        OfflineMeetingMemorySection(
                            sessions = offlineSessions,
                            onOpenAll = { onOpenOfflineMeetings(c.uuid) },
                            onRetryFallback = viewModel::retryOfflineFallback,
                            modifier = cardPadding,
                            retryingSessionIds = retryingOfflineSessions,
                        )
                    }

                    // 共同记忆卡（5 统计 chip + 10 字段 chip + 记忆原文）。
                    item(key = "memory", contentType = "memory") {
                        SharedMemoryCard(
                            stats = memoryStats,
                            memory = structuredMemory,
                            memorySummary = c.memorySummary,
                            modifier = cardPadding,
                            guardBlocked = memoryGuardBlocked,
                            organizing = organizingMemory,
                            onOrganizeNow = viewModel::organizeMemoryNow,
                            onEditMemory = { onEditMemory(c.uuid) },
                            editInProgressBlocked = organizingMemory,
                        )
                    }

                    // 关系历程卡（横向里程碑时间轴）。
                    item(key = "timeline", contentType = "timeline") {
                        RelationshipTimelineCard(milestones = milestones, modifier = cardPadding)
                    }
                }

                ProfileTab.PROFILE -> {
                    // 兴趣热度卡（Top8 热度条）。
                    item(key = "interest", contentType = "interest") {
                        InterestHeatCard(interests = dynamicInterests, modifier = cardPadding)
                    }

                    // 角色钱包卡（💰只读）——永不隐藏；下沉到参考区（不与情感内容并排·建议二）。
                    item(key = "wallet", contentType = "wallet") {
                        CharacterWalletCard(
                            characterName = c.name,
                            wallet = wallet,
                            activity = walletActivity,
                            nowMillis = nowMillis,
                            modifier = cardPadding,
                            showNewBadge = walletHasNews,
                            onEdit = {
                                if (SalaryEditWarningFlag.hasShown(context)) showWalletEdit = true else showWalletWarning = true
                            },
                        )
                    }

                    // 双雷达卡（性格光谱·紫 / 关系质感·粉，Compose Canvas 蛛网图）。
                    item(key = "personality_radar", contentType = "radar") {
                        PersonalityRadarCard(spectrum = personalitySpectrum, onEdit = { onEditCharacter(c.uuid) }, modifier = cardPadding)
                    }
                    item(key = "relationship_radar", contentType = "radar") {
                        RelationshipRadarCard(quality = relationshipQuality, onEdit = { onEditCharacter(c.uuid) }, modifier = cardPadding)
                    }

                    // 角色信息段（静态设定·默认收起点击展开）。
                    item(key = "charinfo", contentType = "charinfo") {
                        CharacterInfoCard(character = c, modifier = cardPadding)
                    }
                }
            }
        }

        // 钱包编辑弹窗/面板挂屏级而非滚动内容里（=iOS .sheet 挂 CharacterProfileView 层）：
        // 状态本就提升在 screen，免受 item 回收影响。
        if (showWalletWarning) {
            AppDialog(
                onDismissRequest = { showWalletWarning = false },
                title = stringResource(R.string.wallet_edit_warning_title),
                body = stringResource(R.string.wallet_edit_warning_message),
                confirmText = stringResource(R.string.wallet_edit_warning_continue),
                onConfirm = {
                    SalaryEditWarningFlag.markShown(context)
                    showWalletWarning = false
                    showWalletEdit = true
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showWalletWarning = false },
            )
        }
        if (showWalletEdit) {
            CharacterWalletEditSheet(
                initialSalary = wallet?.monthlySalary ?: 0,
                salaryInferred = wallet?.salaryInferred ?: false,
                initialSalaryDay = wallet?.salaryDay ?: 15,
                onDismiss = { showWalletEdit = false },
                onSave = { text, day -> viewModel.saveSalary(text, day) },
            )
        }
    }
}
