package com.situ.aichat.offline

import android.util.Log
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.proactive.ProactiveReplyDeliverer
import com.situ.aichat.prompt.HistoryTimeDivider
import com.situ.aichat.prompt.TimeAnchorFormatter
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.scheduleTimeOfDayLabel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 见面后「余温消息」生成器（梦剧场 B 部·涟漪①·图纸 §3.10）：见面结束几小时后，TA 主动发一条回味见面的短消息。
 * 由 [com.situ.aichat.work.OfflineAfterglowWorker] 到点驱动（延迟 135–225 分钟·existingPolicy=KEEP）。
 *
 * 上下文装配委托 [OfflineAfterglowPromptAssembler]（复刻 RecoveryReplyGenerator 全量 fan-out）；本类只做四道守卫 +
 * 追加 §3.10 system 指令（2026-07-07 修订：时间锚点加富，见 [generate]）+ LLM 生成/校验；分段落库 + 通知委托
 * [ProactiveReplyDeliverer]（与惦记回连共用·普通聊天同口径分气泡）。
 */
@Singleton
class OfflineAfterglowService @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val messageRepo: MessageRepository,
    private val contextLog: ContextLogService,
    private val deliverer: ProactiveReplyDeliverer,
    private val promptAssembler: OfflineAfterglowPromptAssembler,
    private val userProfileDao: UserProfileDao,
) {

    /**
     * 余温生成的了结方式（卷二 G2·图纸 §3.2）：
     * - [HANDLED]：这一次已了结——发了 / 守卫拦下 / 到了兜底档仍无行。调用方不必再排。
     * - [DEFER_SUMMARY]：**摘要还没熟**（无行、或行还是即时要点骨架）——本次不发，交
     *   [com.situ.aichat.work.OfflineAfterglowWorker] 30 分钟后再看一眼（至多 6 次）。
     */
    internal enum class AfterglowOutcome { HANDLED, DEFER_SUMMARY }

    /**
     * 四道守卫（任一不满足→安静返回·不重试）：①开关；②会话存在且非见面中；③见面结束后无任何非 system 可见消息
     * （用户已回来聊了别的 → 不打扰）；④该 session 的见面摘要**已经熟了**。全过 → 生成 + 落库 + 通知。
     *
     * 守卫④（卷二 G2 改·旧版是「取不到行就静默跳过」= 空窗期余温整条消失的病根）：摘要未熟改**延后重排**——
     * 谓词单源 [OfflineSummaryRetryCoordinator.summaryStillPending]（与朋友圈呼应共用勿复制）。
     * [acceptInstantRow]=true 是 worker 重排到顶（6 次）的兜底档：即时要点骨架里有时段/地点/时长，够写一条余温了。
     * 守卫①②③语义零改，且**每次重排都重新过一遍**（用户中途回来聊过 → 守卫③自然让位）。
     */
    internal suspend fun maybeGenerate(
        conversationUuid: String,
        characterUuid: String,
        sessionId: String,
        acceptInstantRow: Boolean = false,
    ): AfterglowOutcome {
        val settings = settingsRepo.getAppSettings()
        if (!settings.offlineAfterglowEnabled) return AfterglowOutcome.HANDLED // 守卫①
        val convo = conversationRepo.get(conversationUuid) ?: return AfterglowOutcome.HANDLED // 守卫②
        if (convo.isInOfflineMode) return AfterglowOutcome.HANDLED // 守卫②：又进见面了 → 不打扰
        val row = offlineMeetingMemoryRepository.bySessionId(sessionId)
        if (!acceptInstantRow && OfflineSummaryRetryCoordinator.summaryStillPending(row)) {
            return AfterglowOutcome.DEFER_SUMMARY // 守卫④：摘要未熟 → 等一等再说，别空着手发
        }
        // 兜底档仍无行（历史遗留 pending·升级前的旧会话）→ 维持旧静默：没有任何见面事实可回味。
        if (row == null) return AfterglowOutcome.HANDLED
        // 守卫③：见面结束（row.endedAtMillis = 离场标记时刻）之后已有可见消息 = 用户回来聊了别的 → 不打扰。
        val latest = messageRepo.latestVisibleMessage(conversationUuid)
        if (latest != null && latest.timestamp > row.endedAtMillis) return AfterglowOutcome.HANDLED
        val character = characterRepo.get(characterUuid) ?: return AfterglowOutcome.HANDLED
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return AfterglowOutcome.HANDLED

        val text = generate(convo, character, config, settings, row) ?: return AfterglowOutcome.HANDLED
        deliverer.persistAndNotify(conversationUuid, character, settings, text, TAG)
        return AfterglowOutcome.HANDLED
    }

    /**
     * 全量上下文 → 追加 §3.10 system 指令 → LLM。校验：[MemoryService.strippingThinkingTags] 后 trim 非空、
     * ≤120 字符、不含 `[` 或 `【`——不合格重试 1 次，仍不合格返回 null（静默放弃·不发模板兜底话）。
     *
     * 指令时间锚点（2026-07-07 用户拍板修订·取代图纸 §3.10 逐字版）：原版只给两个裸 HH:mm——跨时段长见面
     * （如凌晨开始、傍晚结束）的深夜剧情会把模型带偏成「昨晚见面→现在是清晨」（真机实收：18:16 生成却说
     * 「早上七点多」）。现改注入「今天/昨天 + 时段词」的见面起止 + [TimeAnchorFormatter.formatCurrentMoment]
     * 完整当前时刻行（含日期/星期/时段），并显式要求以真实时刻为准。
     */
    private suspend fun generate(
        convo: ConversationEntity,
        character: CharacterEntity,
        config: ApiConfigValues,
        settings: AppSettings,
        row: OfflineMeetingMemoryEntity,
    ): String? {
        val nowInstant = Instant.now()
        val zone = ZoneId.systemDefault()
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" } // 图纸一·B6：指令用真实用户名
        val startLabel = anchorLabel(row.startedAtMillis, nowInstant, zone)
        val endLabel = anchorLabel(row.endedAtMillis, nowInstant, zone)
        val systemInstruction =
            "（系统提示：你们${startLabel}在${row.location}见了面，${endLabel}结束——${row.summary}。" +
                "${TimeAnchorFormatter.formatCurrentMoment(nowInstant)}——发消息时以这个真实时刻为准，" +
                "日期和时段都不要说错，也不要被见面时的场景带偏时间感。" +
                "请你主动给${userName}发一条见面后的余温消息：1-2 句、口语、像发微信一样自然，回味见面的某个细节或心情即可；" +
                "不要用任何 [标签]，不要长篇抒情，不要问候式空话。）"
        val messages = promptAssembler.assemble(convo, character, settings, nowInstant) +
            ChatMessageDto(role = "system", content = systemInstruction)

        repeat(2) { attempt ->
            val raw = contextLog.completion(
                source = LogSource.OFFLINE_AFTERGLOW,
                characterName = character.name,
                config = config,
                messages = messages,
            )
            val text = MemoryService.strippingThinkingTags(raw).trim()
            if (text.isNotEmpty() && text.length <= MAX_LEN && !text.contains('[') && !text.contains('【')) return text
            Log.i(TAG, "余温消息校验不合格（第 ${attempt + 1} 次·len=${text.length}）conv=${convo.uuid}")
        }
        return null // 两次都不合格 → 静默放弃
    }

    companion object {
        private const val TAG = "OfflineAfterglow"
        private const val MAX_LEN = 120

        /**
         * 时间锚点标签：「今天/昨天/M月D日 周X HH:mm（时段词）」——同 [HistoryTimeDivider] 相对日口径。
         *
         * 卷二 §5④：朋友圈呼应帖的见面日期口径与余温**共用此处**（勿复制函数体——两处各拼一遍必然漂移）。
         */
        internal fun anchorLabel(millis: Long, now: Instant, zone: ZoneId): String {
            val label = HistoryTimeDivider.formatLabel(millis, now, zone)
            val period = scheduleTimeOfDayLabel(Instant.ofEpochMilli(millis).atZone(zone).hour)
            return "$label（$period）"
        }
    }
}
