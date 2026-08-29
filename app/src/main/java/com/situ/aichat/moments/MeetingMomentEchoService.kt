package com.situ.aichat.moments

import android.util.Log
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.offline.MeetingMomentEchoPlanner
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import com.situ.aichat.prompt.schedule.CharacterSleepChecker
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 见面后「朋友圈呼应帖」的守卫序（卷二 §5④·图纸 §3.3）：见面当天晚些时候，TA 发一条含蓄相关的动态
 * ——不点名、不复述细节，像真人见完面顺手记一笔。
 *
 * 由 [com.situ.aichat.work.MeetingMomentEchoWorker] 到点驱动；掷点（75%）在见面结束时就掷完了（未中签
 * 压根不排 worker），这里只管「到点了该不该发、能不能发」。八道守卫逐条给出了结方式，排程细节交 worker。
 *
 * **有意不做**：不建呼应专属设置项（随朋友圈既有开关走）；不绕睡眠门（深夜不发圈=真实感）；
 * 绕开「今日上限 / 4h 冷却」（事件驱动非节奏帖·拍板「结束恢复时本条优先」）；失败不补偿不告警。
 */
@Singleton
class MeetingMomentEchoService(
    private val settingsRepo: SettingsRepository,
    private val momentRepo: MomentRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
    private val sleepChecker: CharacterSleepChecker,
    private val momentGenerationService: MomentGenerationService,
    /** 深夜顺延落点的抖动源；生产恒 [Random.Default]，测试从主构造注入固定值。 */
    private val random: Random,
) {

    /** Hilt 用的构造（Dagger 不给 [Random] 建绑定，故随机源在此落默认值，不进依赖图）。 */
    @Inject
    constructor(
        settingsRepo: SettingsRepository,
        momentRepo: MomentRepository,
        conversationRepo: ConversationRepository,
        characterRepo: CharacterRepository,
        apiConfigRepo: ApiConfigRepository,
        offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
        sleepChecker: CharacterSleepChecker,
        momentGenerationService: MomentGenerationService,
    ) : this(
        settingsRepo, momentRepo, conversationRepo, characterRepo, apiConfigRepo,
        offlineMeetingMemoryRepository, sleepChecker, momentGenerationService, Random.Default,
    )

    /** 一次尝试的了结方式（worker 据此决定结束还是改期重排）。 */
    internal sealed interface EchoOutcome {
        /** 发出去了。 */
        object Posted : EchoOutcome

        /** 这场见面就不发了（用户关了发帖 / 已发过 / 材料尽失 / 无 key / 内容两次不合格）——不再排。 */
        object Drop : EchoOutcome

        /** 条件不合适（又在见面 / 在睡 / 摘要还没熟）——30 分钟后再看一眼，计入延后次数。 */
        object Defer : EchoOutcome

        /** 撞上深夜——顺延到次日上午 09:00–11:30（时刻修正，**不**计入延后次数）。 */
        data class DeferLateNight(val minutes: Long) : EchoOutcome
    }

    /**
     * 走一遍守卫序并在全过时发帖。[acceptInstantRow]=true 是 worker 延后到顶的兜底档（即时要点骨架也认）。
     */
    internal suspend fun maybePost(
        conversationUuid: String,
        characterUuid: String,
        sessionId: String,
        acceptInstantRow: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): EchoOutcome {
        val settings = settingsRepo.getAppSettings()
        // ① 用户关了角色自动发帖 → 呼应也不发（不另立门户）。
        if (settings.momentAutoPostFrequency <= 0) return EchoOutcome.Drop
        // ② 幂等：这场见面已经发过了（worker 重投 / 重装恢复）→ 至多一条。
        val echoUuid = MeetingMomentEchoPlanner.echoPostUuid(sessionId)
        if (momentRepo.getPost(echoUuid) != null) return EchoOutcome.Drop
        // ③ 又在见面中 → 人到齐了不发圈。
        val convo = conversationRepo.get(conversationUuid) ?: return EchoOutcome.Drop
        if (OfflineMeetingGate.inMeeting(convo)) return EchoOutcome.Defer
        // ④ 深夜 → 顺延到次日上午（时刻修正，不占延后次数）。
        MeetingMomentEchoPlanner.lateNightRescheduleMinutes(nowMillis, zone, random)?.let {
            return EchoOutcome.DeferLateNight(it)
        }
        // ⑤ 角色在睡 → 等等再说。
        if (sleepChecker.isSleeping(characterUuid, settings.scheduleSystemEnabled, nowMillis, zone)) {
            return EchoOutcome.Defer
        }
        // ⑥ 见面摘要还没熟 → 等它（谓词与余温共用单源）；到兜底档仍无行 = 材料尽失，不发。
        val row = offlineMeetingMemoryRepository.bySessionId(sessionId)
        if (!acceptInstantRow && OfflineSummaryRetryCoordinator.summaryStillPending(row)) return EchoOutcome.Defer
        if (row == null) return EchoOutcome.Drop
        // ⑦ 无 key / 未配置 → 静默放弃（**不**置 MomentApiMissingFlag：那是节奏批路的诊断旗）。
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MOMENT_GENERATION) ?: return EchoOutcome.Drop
        // ⑧ 角色已删 → 没人可发。
        val character = characterRepo.get(characterUuid) ?: return EchoOutcome.Drop
        val post = momentGenerationService.generateEchoPost(character, config, row, nowMillis, zone)
        if (post == null) return EchoOutcome.Drop // 内容两次不合格 → 宁缺毋滥
        Log.d(TAG, "见面呼应帖已发布 session=$sessionId post=${post.uuid}")
        return EchoOutcome.Posted
    }

    private companion object {
        const val TAG = "MeetingMomentEcho"
    }
}
