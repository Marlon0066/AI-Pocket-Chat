package com.situ.aichat.notification

import android.content.Context
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.notification.ProactiveMessageComposer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ProactiveDeliveryPipeline 行为测试（主动通知真实感改造 T1-3 / T2-1..T2-5）。
 *
 * 管线是纯类（不新增 work-testing 依赖，D-14）：`now`/`zone`/`isForeground` 全为可注入形参 → 确定性、
 * 与「测试几点跑」无关。断言从图纸 §3.2 闸门顺序 a→g、§9 锁定数值独立反推。
 */
class ProactiveDeliveryPipelineTest {

    private val context: Context = mockk(relaxed = true)
    private val characterRepository: CharacterRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val scheduleDao: ScheduleDao = mockk(relaxed = true)
    private val evaluator: ConversationStateEvaluator = mockk()
    private val composer: ProactiveMessageComposer = mockk()
    private val apiConfigRepository: ApiConfigRepository = mockk(relaxed = true)
    private val templateDao: NotificationTemplateDao = mockk()
    private val deliveryDao: NotificationDeliveryDao = mockk()

    private val conversationRepository: ConversationRepository = mockk(relaxed = true)

    private val pipeline = ProactiveDeliveryPipeline(
        context, characterRepository, settingsRepository, scheduleDao,
        evaluator, composer, apiConfigRepository, templateDao, deliveryDao, conversationRepository,
    )

    private val charId = "c-1"
    private val zone: ZoneId = ZoneOffset.UTC

    /** 基准「现在」= 2026-01-15 12:00 UTC（远离默认免打扰窗 23:00–07:30）。 */
    private val now: Long = LocalDateTime.of(2026, 1, 15, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val character = CharacterEntity(uuid = charId, name = "林深", creationDate = 0L)

    private fun input(
        category: String = "schedule_0",
        occasion: String? = "TA 的日程：[17:00-18:00] 画稿收尾",
        scheduledAt: Long = now - 60_000L, // 1 分钟前到点（新鲜）
    ) = ProactiveDeliveryInput(charId, category, occasion, scheduledAt)

    private fun state(
        phase: ConversationPhase = ConversationPhase.NORMAL,
        unanswered: Int = 0,
        days: Int? = 2,
        uuid: String? = "m-1",
        minutes: Long? = 60L * 50,
    ) = ConversationState(
        phase = phase,
        minutesSinceLastMessage = minutes,
        lastMessageFromUser = false,
        unansweredProactiveCount = unanswered,
        daysSinceLastUserMessage = days,
        latestMessageUuid = uuid,
    )

    /** 一切闸门放行的健康默认桩。 */
    private fun stubHappyPath(
        conversationState: ConversationState = state(),
        composed: String? = "画完最后一笔，想起你说的那家面包店了",
    ) {
        coEvery { characterRepository.get(charId) } returns character
        coEvery { settingsRepository.getAppSettings() } returns AppSettings(
            notificationsEnabled = true,
            scheduleSystemEnabled = false, // 隔离掉睡眠闸
            quietHoursEnabled = false,
        )
        coEvery { settingsRepository.isCharacterNotificationEnabled(charId) } returns true
        coEvery { evaluator.evaluate(charId, any(), any()) } returns conversationState
        coEvery { evaluator.latestMessageUuid(charId) } returns conversationState.latestMessageUuid
        coEvery { apiConfigRepository.resolveConfigValues(any()) } returns
            ApiConfigValues(
                providerType = ApiProviderType.OPENAI_COMPATIBLE,
                apiKey = "k",
                baseUrl = "https://x",
                modelName = "m",
            )
        coEvery { composer.compose(any(), any(), any(), any(), any(), any()) } returns composed
        coEvery { deliveryDao.countDeliveredSince(any(), any()) } returns 0
        coEvery { deliveryDao.recentDeliveredBodies(any(), any()) } returns emptyList()
    }

    private suspend fun run(
        input: ProactiveDeliveryInput = input(),
        attempt: Int = 0,
        foreground: Boolean = false,
    ): ProactiveVerdict = pipeline.execute(input, attempt, now, zone) { foreground }

    // MARK: - T1-3 纯函数（兜底类别映射 / 降频窗）

    @Test fun fallbackCategory_scheduleN_mapsToRandom() {
        assertEquals("random", ProactiveDeliveryPipeline.fallbackCategoryFor("schedule_0"))
        assertEquals("random", ProactiveDeliveryPipeline.fallbackCategoryFor("schedule_2"))
        // 其余用自身类别
        assertEquals("morning", ProactiveDeliveryPipeline.fallbackCategoryFor("morning"))
        assertEquals("streak_remind", ProactiveDeliveryPipeline.fallbackCategoryFor("streak_remind"))
        assertEquals("random", ProactiveDeliveryPipeline.fallbackCategoryFor("random"))
    }

    /** D-6：窗口由「距最后用户消息」天数选，且只在三个疏远相位生效。 */
    @Test fun backoffWindow_perUserDayBucket_andOnlyForDistantPhases() {
        val h = 60L * 60 * 1000
        assertEquals(24 * h, ProactiveDeliveryPipeline.backoffWindowMs(ConversationPhase.DISTANT_EARLY, 4))
        assertEquals(24 * h, ProactiveDeliveryPipeline.backoffWindowMs(ConversationPhase.DISTANT_EARLY, 7))
        assertEquals(48 * h, ProactiveDeliveryPipeline.backoffWindowMs(ConversationPhase.DISTANT_LATE, 8))
        assertEquals(48 * h, ProactiveDeliveryPipeline.backoffWindowMs(ConversationPhase.DISTANT_LATE, 14))
        assertEquals(168 * h, ProactiveDeliveryPipeline.backoffWindowMs(ConversationPhase.LONG_ABSENCE, 15))
        assertEquals(168 * h, ProactiveDeliveryPipeline.backoffWindowMs(ConversationPhase.LONG_ABSENCE, 400))
        // 用户从未说话 → 视同 ≥15 天档
        assertEquals(168 * h, ProactiveDeliveryPipeline.backoffWindowMs(ConversationPhase.LONG_ABSENCE, null))
        // 近相位不降频
        listOf(
            ConversationPhase.SAME_DAY, ConversationPhase.OVERNIGHT, ConversationPhase.NORMAL,
            ConversationPhase.HOT, ConversationPhase.AFTERGLOW,
        ).forEach { assertEquals(null, ProactiveDeliveryPipeline.backoffWindowMs(it, 30)) }
    }

    // MARK: - T2-1 状态闸（E5 热聊 / E7 连发 / E8 降频）

    @Test fun hotPhase_drops() = runTest {
        stubHappyPath(state(phase = ConversationPhase.HOT, minutes = 10))
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.HOT), run())
        // 热聊直接放弃，绝不浪费一次 LLM 调用
        coVerify(exactly = 0) { composer.compose(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun afterglowPhase_drops() = runTest {
        stubHappyPath(state(phase = ConversationPhase.AFTERGLOW, minutes = 90))
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.AFTERGLOW), run())
    }

    /** E7：已有 2 条未回应 → 冻结。 */
    @Test fun twoUnanswered_freezes() = runTest {
        stubHappyPath(state(unanswered = 2))
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.UNANSWERED_STREAK), run())
    }

    /** E7 下半：1 条未回应仍放行（闸是 ≥2）。 */
    @Test fun oneUnanswered_stillDelivers() = runTest {
        stubHappyPath(state(unanswered = 1))
        assertTrue(run() is ProactiveVerdict.Deliver)
    }

    /** E7：用户一回复 → 计数归零 → 立刻解冻（evaluator 侧口径已保证，此处验管线放行）。 */
    @Test fun afterUserReply_countZero_unfreezes() = runTest {
        stubHappyPath(state(unanswered = 0))
        assertTrue(run() is ProactiveVerdict.Deliver)
    }

    /** E8：久别 20 天但 3 天前刚发过 → 168h 窗内 → 放弃。 */
    @Test fun longAbsence_insideBackoffWindow_drops() = runTest {
        stubHappyPath(state(phase = ConversationPhase.LONG_ABSENCE, days = 20))
        coEvery { deliveryDao.countDeliveredSince(charId, now - 168L * 60 * 60 * 1000) } returns 1

        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.BACKOFF_WINDOW), run())
    }

    /** E8 下半：窗外 → 放行（永不归零：久别仍每 7 天最多一条）。 */
    @Test fun longAbsence_outsideBackoffWindow_delivers() = runTest {
        stubHappyPath(state(phase = ConversationPhase.LONG_ABSENCE, days = 20))
        coEvery { deliveryDao.countDeliveredSince(charId, now - 168L * 60 * 60 * 1000) } returns 0

        assertTrue(run() is ProactiveVerdict.Deliver)
    }

    /** 三档降频窗各按自己的天数档查库。 */
    @Test fun backoffGate_queriesWindowMatchingUserDays() = runTest {
        stubHappyPath(state(phase = ConversationPhase.DISTANT_EARLY, days = 5))
        val since = slot<Long>()
        coEvery { deliveryDao.countDeliveredSince(charId, capture(since)) } returns 0

        run()

        assertEquals(now - 24L * 60 * 60 * 1000, since.captured)
    }

    // MARK: - T2-2 硬前提 / 保质期 / 免打扰 / 睡眠

    /** E12：角色在 worker 运行中被删 → 放弃。 */
    @Test fun characterDeleted_drops() = runTest {
        stubHappyPath()
        coEvery { characterRepository.get(charId) } returns null
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.CHARACTER_GONE), run())
    }

    @Test fun globalSwitchOff_drops() = runTest {
        stubHappyPath()
        coEvery { settingsRepository.getAppSettings() } returns AppSettings(notificationsEnabled = false)
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.NOTIFICATIONS_OFF), run())
    }

    @Test fun perCharacterSwitchOff_drops() = runTest {
        stubHappyPath()
        coEvery { settingsRepository.isCharacterNotificationEnabled(charId) } returns false
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.CHARACTER_NOTIFICATIONS_OFF), run())
    }

    /** E3：超保质期 2h（ROM 延迟投递/重试拖过窗）→ 放弃，且**先于**任何联网动作。 */
    @Test fun pastFreshnessWindow_drops_beforeAnyLlmCall() = runTest {
        stubHappyPath()
        val verdict = run(input(scheduledAt = now - 7_200_001L))
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.STALE), verdict)
        coVerify(exactly = 0) { composer.compose(any(), any(), any(), any(), any(), any()) }
    }

    /** 恰 2h 不算超期（±1 精度：> 才 Drop）。 */
    @Test fun exactlyAtFreshnessWindow_stillDelivers() = runTest {
        stubHappyPath()
        assertTrue(run(input(scheduledAt = now - 7_200_000L)) is ProactiveVerdict.Deliver)
    }

    /** E4：到点落在免打扰窗内 → 放弃（now=12:00，窗设 11:00–13:00）。 */
    @Test fun insideQuietHours_drops() = runTest {
        stubHappyPath()
        coEvery { settingsRepository.getAppSettings() } returns AppSettings(
            notificationsEnabled = true,
            scheduleSystemEnabled = false,
            quietHoursEnabled = true,
            quietHoursStartMinute = 11 * 60,
            quietHoursEndMinute = 13 * 60,
        )
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.QUIET_HOURS), run())
    }

    /** 免打扰开关关 → 同一窗口不拦（证明是被开关门控的）。 */
    @Test fun quietHoursDisabled_sameWindow_delivers() = runTest {
        stubHappyPath()
        coEvery { settingsRepository.getAppSettings() } returns AppSettings(
            notificationsEnabled = true,
            scheduleSystemEnabled = false,
            quietHoursEnabled = false,
            quietHoursStartMinute = 11 * 60,
            quietHoursEndMinute = 13 * 60,
        )
        assertTrue(run() is ProactiveVerdict.Deliver)
    }

    // MARK: - T2-3 现做失败 → 重试 → 兜底链（E1 / E2 / E14）

    /** E1：attempt 0/1 得 Retry。 */
    @Test fun composeFails_earlyAttempts_retry() = runTest {
        stubHappyPath(composed = null)
        assertEquals(ProactiveVerdict.Retry, run(attempt = 0))
        assertEquals(ProactiveVerdict.Retry, run(attempt = 1))
    }

    /** E1：attempt 2（第 3 次尝试）→ 不再重试，走兜底链取模板。 */
    @Test fun composeFails_lastAttempt_usesTemplateFallback() = runTest {
        stubHappyPath(composed = null)
        coEvery { templateDao.pickUnused(charId, "random") } returns "在忙什么呢"

        val verdict = run(attempt = 2)

        assertEquals(ProactiveVerdict.Deliver("在忙什么呢", foreground = false), verdict)
        // schedule_N 借 random 池
        coVerify { templateDao.pickUnused(charId, "random") }
    }

    /** E2：兜底文案与最近 3 条已发正文 trim 相等 → 放弃（不重复发同一句）。 */
    @Test fun fallbackBody_duplicateOfRecent_drops() = runTest {
        stubHappyPath(composed = null)
        coEvery { templateDao.pickUnused(charId, "random") } returns "在忙什么呢"
        coEvery { deliveryDao.recentDeliveredBodies(charId, 3) } returns listOf("别的话", "  在忙什么呢  ")

        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.DUPLICATE_BODY), run(attempt = 2))
    }

    /** 现做成功的文案**不**走查重（它本就照当下状态写、天然带时效）。 */
    @Test fun freshBody_isNotDuplicateChecked() = runTest {
        stubHappyPath(composed = "在忙什么呢")
        coEvery { deliveryDao.recentDeliveredBodies(any(), any()) } returns listOf("在忙什么呢")

        assertTrue(run() is ProactiveVerdict.Deliver)
    }

    /** E14：模板池空 + 静态文案也空 → 放弃（宁可不发）。 */
    @Test fun allFallbacksEmpty_drops() = runTest {
        stubHappyPath(composed = null)
        coEvery { templateDao.pickUnused(charId, "random") } returns null
        // NotificationFallbackText.pick 拿 relaxed context 取不到资源 → 空串
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.EMPTY_BODY), run(attempt = 2))
    }

    /** 由头缺失（E18 老闹钟）→ 用锁定兜底由头现做，不放弃。 */
    @Test fun missingOccasion_usesLockedFallbackOccasion() = runTest {
        stubHappyPath()
        val occasion = slot<String>()
        coEvery { composer.compose(any(), capture(occasion), any(), any(), any(), any()) } returns "刚忙完"

        assertTrue(run(input(occasion = null)) is ProactiveVerdict.Deliver)
        assertEquals("想起对方，找个话题聊聊", occasion.captured)
    }

    /** 由头为空白串同样退兜底由头。 */
    @Test fun blankOccasion_usesLockedFallbackOccasion() = runTest {
        stubHappyPath()
        val occasion = slot<String>()
        coEvery { composer.compose(any(), capture(occasion), any(), any(), any(), any()) } returns "刚忙完"

        run(input(occasion = "   "))
        assertEquals(ProactiveMessageComposer.FALLBACK_OCCASION, occasion.captured)
    }

    // MARK: - T2-4 竞态终查（E6）

    /** E6：生成期间用户发了消息（最后一条 uuid 变了）→ 整条丢弃。 */
    @Test fun raceNewMessageDuringCompose_drops() = runTest {
        stubHappyPath(state(uuid = "m-1"))
        coEvery { evaluator.latestMessageUuid(charId) } returns "m-2" // 生成期间来了新消息

        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.RACE_NEW_MESSAGE), run())
    }

    /** 快照前后一致 → 正常投递。 */
    @Test fun noRace_delivers() = runTest {
        stubHappyPath(state(uuid = "m-1"))
        coEvery { evaluator.latestMessageUuid(charId) } returns "m-1"

        assertTrue(run() is ProactiveVerdict.Deliver)
    }

    /** 从无消息到有消息（null → "m-9"）同样算竞态。 */
    @Test fun raceFromEmptyToFirstMessage_drops() = runTest {
        stubHappyPath(state(phase = ConversationPhase.LONG_ABSENCE, days = null, uuid = null, minutes = null))
        coEvery { evaluator.latestMessageUuid(charId) } returns "m-9"

        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.RACE_NEW_MESSAGE), run())
    }

    // MARK: - T2-5 前台分支（E9 可测半边）

    /** App 在前台 → Deliver 带 foreground=true（worker 据此静默物化而非弹横幅）。 */
    @Test fun foregroundApp_verdictCarriesForegroundTrue() = runTest {
        stubHappyPath()
        val verdict = run(foreground = true)
        assertEquals(ProactiveVerdict.Deliver("画完最后一笔，想起你说的那家面包店了", foreground = true), verdict)
    }

    /** App 在后台 → foreground=false（走 Notifier 弹通知）。 */
    @Test fun backgroundApp_verdictCarriesForegroundFalse() = runTest {
        stubHappyPath()
        val verdict = run(foreground = false)
        assertEquals(ProactiveVerdict.Deliver("画完最后一笔，想起你说的那家面包店了", foreground = false), verdict)
    }

    // MARK: - 幂等前提（失败前零写库副作用）

    /** 任一闸放弃时都不得碰模板池（模板 pickUnused 有「用完重置」副作用）。 */
    @Test fun gatesDrop_neverTouchTemplatePool() = runTest {
        stubHappyPath(state(phase = ConversationPhase.HOT, minutes = 5))
        run()
        coVerify(exactly = 0) { templateDao.pickUnused(any(), any()) }
    }
}
