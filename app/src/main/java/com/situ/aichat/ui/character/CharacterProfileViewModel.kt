package com.situ.aichat.ui.character

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GiftImpressionTag
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.model.dynamicInterests
import com.situ.aichat.data.model.growthLog
import com.situ.aichat.data.model.moodHistory
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.R
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.memory.ManualMemoryOrganizeService
import com.situ.aichat.economy.CharacterSalaryPayoutService
import com.situ.aichat.economy.EconomyLastViewedStore
import com.situ.aichat.economy.hasEconomyNews
import com.situ.aichat.economy.parseSalaryInput
import com.situ.aichat.gift.GiftImpressionTagService
import com.situ.aichat.meeting.MeetingArrivalPolicy
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.offline.OfflineMeetingSessionExtractor
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import com.situ.aichat.profile.CharacterWalletActivity
import com.situ.aichat.profile.CompanionStats
import com.situ.aichat.profile.CompanionStatsService
import com.situ.aichat.prompt.schedule.ScheduleCoordinator
import com.situ.aichat.ui.schedule.ScheduleTimelineLogic
import com.situ.aichat.util.timeTickFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

/**
 * 角色资料页（14.1，只读展示）的 ViewModel。
 *
 * 数据**实时刷新**（用户拍板：超越 iOS 的 `.task(id:uuid)` 进入时快照 → 退出再进才更新）：
 * 全部走 Room Flow，礼物/流水/角色任一变化即在后台线程重算。💰 钱包**只读不写**。
 */
@HiltViewModel
class CharacterProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    characterRepo: CharacterRepository,
    messageDao: MessageDao,
    giftDao: GiftDao,
    meetingAppointmentDao: MeetingAppointmentDao,
    currencyDao: CurrencyDao,
    scheduleDao: ScheduleDao,
    settingsRepo: SettingsRepository,
    promiseRepository: PromiseRepository,
    private val scheduleCoordinator: ScheduleCoordinator,
    private val companionStatsService: CompanionStatsService,
    private val offlineExtractor: OfflineMeetingSessionExtractor,
    private val offlineRetryCoordinator: OfflineSummaryRetryCoordinator,
    private val salaryPayoutService: CharacterSalaryPayoutService,
    private val economyLastViewed: EconomyLastViewedStore,
    private val conversationRepo: ConversationRepository,
    private val manualMemoryOrganize: ManualMemoryOrganizeService,
    // 卷一 F4：见面回忆卡摘要走行 override（与回忆长廊同源）。
    private val offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
) : ViewModel() {

    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()

    private val characterFlow = characterRepo.observe(characterUuid)

    val character: StateFlow<CharacterEntity?> =
        characterFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 资料页倒数小条（Phase 12·复用聊天侧 MeetingCountdownChip·**角色级** = 该角色「下一个已确认未来约定」·跨其全部会话）。
    // 资料页只读展示性质 → 信息型（无改期/取消菜单；管理在聊天页）。到点/爽约不在此显（与聊天侧到点变身按钮分工）。
    val nextMeetingCountdown: StateFlow<MeetingAppointmentEntity?> =
        combine(meetingAppointmentDao.observeActiveForCharacter(characterUuid), timeTickFlow()) { list, now ->
            list.firstOrNull { MeetingArrivalPolicy.isCountdownState(it.status, it.scheduledAt, now) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stats: StateFlow<CompanionStats?> =
        combine(characterFlow, messageDao.observeNonSystemForCharacter(characterUuid)) { ch, _ -> ch }
            .map { ch -> ch?.let { companionStatsService.compute(it) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 亲友账卡（14.1b·关系账户「TA 眼里的你」）：收礼列表 + 印象标签（标签从同一份记录纯函数算，避免二次查询）。
    private val receivedGiftsFlow = giftDao.observeUserGiftsToCharacterDesc(characterUuid)

    val receivedGifts: StateFlow<List<GiftRecordEntity>> =
        receivedGiftsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val impressionTags: StateFlow<List<GiftImpressionTag>> =
        combine(receivedGiftsFlow, characterFlow) { gifts, ch ->
            if (ch == null) emptyList()
            else GiftImpressionTagService.selectTags(
                records = gifts,
                moodHistory = ch.moodHistory,
                birthday = ch.birthday,
                limit = 3,
                now = System.currentTimeMillis(),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 角色钱包卡（14.1b·💰只读）：钱包实体 + 近 7 天流水 + 本月汇总（窗口起点固定为本会话，Flow 捕获新流水）。
    val wallet: StateFlow<CharacterWalletEntity?> =
        currencyDao.observeCharacterWallet(characterUuid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val zone: ZoneId = ZoneId.systemDefault()
    private val nowMillis: Long = System.currentTimeMillis()
    private val sevenDaysAgo: Long = nowMillis - 7L * 86_400_000L
    private val monthStart: Long =
        java.time.LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val walletActivity: StateFlow<CharacterWalletActivity.Summary> =
        currencyDao.observeCharacterTransactionsSince(characterUuid, minOf(monthStart, sevenDaysAgo))
            .map { CharacterWalletActivity.compute(it, sevenDaysAgo, monthStart) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CharacterWalletActivity.EMPTY)

    // 钱包卡「新变动」徽标（P1-40）：对照「入页快照」的上次浏览时刻——本次浏览全程可见，下次进页才消化
    // （init 已把 lastViewed 刷到 now=浏览即清）。MAX_VALUE 占位防快照读到前误亮；徽标窗口与卡内
    // 「近 7 天流水」同窗（更旧的事件卡上本就不显示，不为其亮灯）。
    private val lastViewedAtEntry = MutableStateFlow(Long.MAX_VALUE)

    val walletHasNews: StateFlow<Boolean> =
        combine(walletActivity, lastViewedAtEntry) { activity, lastViewed ->
            hasEconomyNews(activity.recent.firstOrNull()?.timestamp, lastViewed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 用户手动保存角色月薪 + 发薪日（14.6b·💰涉钱写·1:1 iOS CharacterWalletEditSheet.save）：写回
     * monthlySalary(clamp[0,50000])/salaryInferred=true/salaryDay(clamp[1,28]) + 首次入职储蓄 0.5 月薪——
     * **一笔原子事务** [CharacterSalaryPayoutService.applyManualSalaryEdit]（复核修 HIGH：消除写回与储蓄分离的 TOCTOU
     * 双发）。解析在纯函数 [parseSalaryInput]（空/非数字→0 再 clamp）。
     *
     * 复核修：① 进 [NonCancellable]——离开页面（viewModelScope 取消）不丢这笔钱写/不半提交；② 先 guard 角色仍存在，
     * 避免为已删角色铸孤儿钱包。
     */
    fun saveSalary(monthlySalaryInput: String, salaryDay: Int) {
        if (characterUuid.isEmpty() || character.value == null) return
        val salary = parseSalaryInput(monthlySalaryInput)
        viewModelScope.launch {
            withContext(NonCancellable) {
                salaryPayoutService.applyManualSalaryEdit(characterUuid, salary, salaryDay)
            }
        }
    }

    // 成长智能卡族（14.1d）：兴趣热度 / 成长日志 / 双雷达（性格·关系），均从角色 Flow 解码 JSON 列。
    val dynamicInterests: StateFlow<List<DynamicInterest>> =
        characterFlow.map { it?.dynamicInterests ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val growthLog: StateFlow<List<GrowthLogEntry>> =
        characterFlow.map { it?.growthLog ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val personalitySpectrum: StateFlow<PersonalitySpectrum> =
        characterFlow.map { it?.personalitySpectrum ?: PersonalitySpectrum.NEUTRAL }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonalitySpectrum.NEUTRAL)

    val relationshipQuality: StateFlow<RelationshipQuality> =
        characterFlow.map { it?.relationshipQuality ?: RelationshipQuality.INITIAL }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelationshipQuality.INITIAL)

    // 关系历程卡（14.1c-2）：里程碑按确立日期升序（最新在最后，时间轴默认滚到最右）。
    val milestones: StateFlow<List<MilestoneEntity>> =
        characterRepo.observeMilestones(characterUuid)
            .map { list -> list.sortedBy { it.establishedDate } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 共同记忆卡（14.1c）：10 字段结构化记忆（角色 Flow 解码）+ 5 项档案统计（消息时间戳实时算）。
    val structuredMemory: StateFlow<StructuredMemory> =
        characterFlow
            .map { it?.let { c -> StructuredMemory.decode(c.structuredMemoryJSON) } ?: StructuredMemory.EMPTY }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StructuredMemory.EMPTY)

    val memoryStats: StateFlow<StructuredMemoryStats.Result> =
        combine(characterFlow, messageDao.observeNonSystemForCharacter(characterUuid)) { ch, _ -> ch }
            .map { ch ->
                ch?.let {
                    StructuredMemoryStats.compute(messageDao.nonEmptyTimestampsForCharacter(it.uuid), it.firstMessageDate)
                } ?: StructuredMemoryStats.EMPTY
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StructuredMemoryStats.EMPTY)

    // 见面回忆卡（14.1b-2·HorizontalPager）：session 列表实时刷新——消息变化（新见面）或手动重试（刷「简版」状态）即重抽。
    private val sessionRefresh = MutableStateFlow(0)

    val offlineSessions: StateFlow<List<OfflineMeetingSession>> =
        combine(characterFlow, messageDao.observeNonSystemForCharacter(characterUuid), sessionRefresh) { ch, _, _ -> ch }
            .map { ch ->
                val sessions = ch?.let { offlineExtractor.extractSessions(it) } ?: return@map emptyList()
                // 卷一 F4：摘要/简版徽章从**行**override（与回忆屏 OfflineMeetingMemoryViewModel.reload 同模板）——
                // 骨架仍从 marker 组装（见面一结束即显示），但 blob 早已冻结只读、注入宏直读行，资料页此前只读
                // 骨架 → 摘要永远显示旧值/空。无行（pending）→ 保 extractSessions 原值。
                offlineMeetingMemoryRepository.ensureSeeded(characterUuid)
                val rowBySession = offlineMeetingMemoryRepository.byCharacter(characterUuid).associateBy { it.sessionId }
                sessions.map { session ->
                    val row = rowBySession[session.id]
                    if (row == null) {
                        session
                    } else {
                        session.copy(
                            summaryText = row.summary.ifEmpty { null },
                            // 卷二 G1：instant（即时要点骨架）与 fallback 同属「简版」——谓词只扩来源值不改形。
                            usedFallbackSummary = row.sourceRaw == "fallback" ||
                                row.sourceRaw == OfflineSummaryRetryCoordinator.SOURCE_INSTANT,
                        )
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 手动重试进行中的会话集合（驱动「简版」徽章转圈；状态在 coordinator 单源，批2 复核修 LOW#1）。 */
    val retryingOfflineSessions: StateFlow<Set<String>> = offlineRetryCoordinator.retryingSessionIds

    /** 手动重试「简版」兜底摘要（1:1 iOS onRetryFallback）；完成后 bump 触发 session 重抽刷新标识。 */
    fun retryOfflineFallback(sessionId: String) {
        viewModelScope.launch {
            offlineRetryCoordinator.manuallyRetry(sessionId)
            sessionRefresh.value++
        }
    }

    // ── 记忆护栏第二层（MG-U3·契约 FABLE5_MEMORY_GUARD_UI_PROPOSAL §3/§5）：共同记忆卡遇阻状态条 ──

    /** 该角色记忆整理遇阻（failureDate 非空的会话 >0）→ 卡内显示琥珀状态条；成功清旗标后自然翻 false。 */
    val memoryGuardBlocked: StateFlow<Boolean> =
        conversationRepo.observeMemorySummaryBlockedCount(characterUuid)
            .map { it > 0 }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 本角色手动整理进行中（驱动条忙态·状态在 service 单源）。 */
    val organizingMemory: StateFlow<Boolean> =
        manualMemoryOrganize.organizing
            .map { characterUuid in it }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _memoryGuardToast = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** 「立即整理」结果 toast（string res id 一次性事件）。 */
    val memoryGuardToast: SharedFlow<Int> = _memoryGuardToast.asSharedFlow()

    /** 「立即整理」：NonCancellable——离开页面任务照跑完（契约 §5-2）；结果 toast 页面还在才看得到，无害。 */
    fun organizeMemoryNow() {
        viewModelScope.launch {
            val ok = withContext(NonCancellable) { manualMemoryOrganize.organizeNow(characterUuid) }
            _memoryGuardToast.tryEmit(
                if (ok) R.string.profile_memory_guard_toast_success else R.string.profile_memory_guard_toast_fail,
            )
        }
    }

    // 我们的约定卡（记忆改造三期·D-1/D-2）：7 天窗 / 排序与注入单源（PromiseInjectionRenderer）。
    // internal：PromiseCardState 是 UI 层 internal 类型（图纸 §3.2），public 属性无法暴露它 → 属性同降 internal
    // （Screen 同模块可读·封装更紧·图纸偏差登记见 §11 D-三2）。
    internal val promiseCard: StateFlow<PromiseCardState> =
        combine(
            promiseRepository.observeOpenByCharacter(characterUuid),
            promiseRepository.observeResolvedByCharacter(characterUuid),
        ) { open, resolved -> PromiseCardState.compute(open, resolved, nowMillis) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PromiseCardState.EMPTY)

    // ── 日程卡（14.2a）：仅 scheduleSystemEnabled 才进资料页；数据实时刷新 + 生成失败可重试。 ──
    val scheduleEnabled: StateFlow<Boolean> =
        settingsRepo.appSettings.map { it.scheduleSystemEnabled }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * 今日 0 点（设备时区），= 日程「角色 + date」唯一键的 date 部分。VM 构造时定死一次——与 iOS
     * `.task(id:uuid)` 快照同语义（跨午夜停留在本页不自动翻到新一天，重进资料页即刷新）；动态翻日由
     * 14.2b 全天视图的 selectedDate 承担。
     */
    private val todayStartMillis: Long =
        java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scheduleWithEvents: Flow<Pair<CharacterDailyScheduleEntity?, List<ScheduleEventEntity>>> =
        scheduleDao.observeScheduleFor(characterUuid, todayStartMillis)
            .flatMapLatest { sched ->
                if (sched == null) flowOf(null to emptyList())
                else scheduleDao.observeEventsForSchedule(sched.uuid).map { sched to it }
            }

    /**
     * 资料页日程卡状态（1:1 iOS `ScheduleTimelineCard` 分支）：有正式日程且有已开始事件→内容；
     * 有日程但无已开始事件→隐藏；失败集含本角色→失败；否则→加载。失败集 / Room 数据任一变即重算。
     */
    val scheduleCard: StateFlow<ScheduleCardState> =
        combine(scheduleWithEvents, scheduleCoordinator.failedCharacterUuids) { (sched, events), failed ->
            deriveScheduleCard(sched, events, failed.contains(characterUuid))
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleCardState.Loading)

    private fun deriveScheduleCard(
        schedule: CharacterDailyScheduleEntity?,
        events: List<ScheduleEventEntity>,
        failed: Boolean,
    ): ScheduleCardState {
        // 1:1 iOS ScheduleTimelineCard（L16-53）：闸门是「日程行是否存在」而非 generatedAt——任意行（含
        // generatedAt==null 的空壳，如线下见面预建并追加已开始事件）存在即按已开始事件渲染（空→整卡隐藏）；
        // 仅「无任何日程行」才进 加载/失败 态。（init 的一次性生成仍以 generatedAt==null 为准，二者职责不同。）
        if (schedule != null) {
            val now = System.currentTimeMillis()
            val selected = ScheduleTimelineLogic.selectedEvents(events, now)
            if (selected.isEmpty()) return ScheduleCardState.Hidden
            val rows = selected.map { ScheduleRow(it, ScheduleTimelineLogic.timelineTimeState(it, now)) }
            return ScheduleCardState.Content(
                rows = rows,
                weatherLabel = ScheduleTimelineLogic.compactWeatherLabel(
                    schedule.cityName, schedule.weatherEmoji, schedule.weatherCondition,
                ),
            )
        }
        return if (failed) ScheduleCardState.Failed else ScheduleCardState.Loading
    }

    /** 日程卡「生成失败→重试」：仅重生本角色今日（1:1 iOS manualRetry）；成功后 Room Flow 自动刷新卡片。 */
    fun retrySchedule() {
        viewModelScope.launch { scheduleCoordinator.retryTodayFor(characterUuid) }
    }

    init {
        // P1-40 浏览即清：入页先快照 lastViewed（徽标对照值），再立刻记 now（下次进页不再亮）。
        viewModelScope.launch {
            lastViewedAtEntry.value = economyLastViewed.lastViewed(characterUuid)
            economyLastViewed.markViewed(characterUuid)
        }
        // 进入资料页时若本角色今日尚无正式日程，触发一次性生成（安卓等价 iOS `triggerForNewCharacter`，
        // 让「加载」态能自愈而非空等下次回前台批量 ensure）。幂等：已生成则 retryTodayFor 内部跳过；
        // scheduleSystemEnabled 关 / 无 API 配置则不触发；与批量 ensure 共用 mutex 串行、不会重复调 LLM。
        viewModelScope.launch {
            val existing = scheduleDao.scheduleFor(characterUuid, todayStartMillis)
            if (existing?.generatedAt == null) {
                scheduleCoordinator.retryTodayFor(characterUuid)
            }
        }
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
