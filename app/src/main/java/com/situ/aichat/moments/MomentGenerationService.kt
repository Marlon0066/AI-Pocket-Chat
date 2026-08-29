package com.situ.aichat.moments

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MomentTriggerType
import com.situ.aichat.data.model.dynamicInterests
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.gift.GiftMomentQueueService
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.PetShopMomentQueueService
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.neglectPhase
import com.situ.aichat.pet.species
import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.offline.MeetingMomentEchoPlanner
import com.situ.aichat.offline.OfflineAfterglowService
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.schedule.CharacterSleepChecker
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.MomentCatchUpWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 朋友圈自动发帖生成引擎（P7.2.3）。1:1 移植 iOS `MomentGenerationActor.checkAndGeneratePosts`
 * （+ `MomentGenerationActor+Content.generatePostContent`），合并 iOS 的 Service/Actor 分层为一处。
 *
 * 由 [com.situ.aichat.work.MomentGenerationWorker] 调用：回前台一次性 + 15min 周期兜底（决策③）。
 * 守卫链（逐字对齐 iOS）：重入锁（>300s 仅告警不强制重置）→ 清理软删除帖 → API 缺失标记 → 沉浸模式
 * （→P10 stub=false）→ 发帖频率>0 → 逐角色：今日上限 → 4h 冷却 → 睡眠则记欠帖 → 礼物分支
 * （P9.2e 已接：命中则 gift_received + 灵感段；宠物商店分支 → P9.3c stub）→ 生成内容（temp 0.9）→ 落库。
 * 失败逐角色 try/catch、不中断其他角色。
 *
 * **7.2.4b 接线**：落帖后调 [MomentInteractionService.scheduleGeneratedPostInteraction]，30~120s 后由
 * 其他角色点赞/评论（经 [MomentDelayedTaskRegistry]，删帖可取消、被杀后靠 7.2.5 恢复重建）。
 * **欠帖补发**（catchUpPost，睡醒后聊天触发）属 7.2.5；此处只 [MomentOwedPostStore.markOwedPost] 记债。
 */
@Singleton
class MomentGenerationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contextLog: ContextLogService,
    private val apiConfigRepo: ApiConfigRepository,
    private val characterRepo: CharacterRepository,
    private val momentRepo: MomentRepository,
    private val scheduleDao: ScheduleDao,
    private val sleepChecker: CharacterSleepChecker,
    private val settingsRepo: SettingsRepository,
    private val interactionService: MomentInteractionService,
    private val backgroundScheduler: BackgroundScheduler,
    private val giftQueue: GiftMomentQueueService,
    private val petShopQueue: PetShopMomentQueueService,
    private val petRepository: PetRepository,
    private val newPostNotifier: MomentNewPostNotifier,
    private val userProfileDao: UserProfileDao,
    private val conversationDao: ConversationDao,
) {

    private data class GateState(val running: Boolean, val startedAt: Long)

    /** 重入锁：回前台 OneShot 与 15min 周期任务名不同、可并发，故需进程内互斥（对齐 iOS isRunning）。 */
    private val gate = AtomicReference(GateState(running = false, startedAt = 0L))

    /**
     * @param notifyOnNewPost 13.7e：true（仅周期后台 worker 路）= 本轮新落库的帖推「X 发了新动态」系统通知；
     *   回前台补发路默认 false（用户马上会在 feed 看到，不打扰）。只控制是否推通知，**不**碰任何发帖守卫/数值/概率。
     */
    suspend fun checkAndGeneratePosts(
        notifyOnNewPost: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (!tryAcquire(nowMillis)) return
        // 13.7e：本轮新建并落库的帖（用于发完后推「新动态」通知；仅 notifyOnNewPost 时收集与消费）。
        val createdThisRun = mutableListOf<MomentPostEntity>()
        try {
            // 顺手清理过期软删除帖（清磁盘图 + 硬删 30 天前），复用 7.2.1 Repository。
            momentRepo.cleanupSoftDeleted(nowMillis - THIRTY_DAYS_MS)

            val config = apiConfigRepo.resolveConfigValues(ApiFunction.MOMENT_GENERATION)
            if (config == null) {
                Log.d(TAG, "朋友圈生成跳过：未配置 API 或 Key 为空")
                MomentApiMissingFlag.set(context, true)
                return
            }
            MomentApiMissingFlag.set(context, false)

            val settings = settingsRepo.getAppSettings()
            if (settings.momentAutoPostFrequency <= 0) return

            val characters = characterRepo.getAll()
            if (characters.isEmpty()) return

            val strings = MomentPromptStrings.from(PromptStrings(context))
            val startOfDay = DateFormatters.startOfDayMillis(nowMillis, zone)

            for (character in characters) {
                try {
                    // 今日发帖上限
                    val todayCount = momentRepo.countPostsForCharacterSince(character.uuid, startOfDay)
                    if (MomentPostGuards.isDailyCapReached(todayCount, settings.momentAutoPostFrequency)) continue
                    // 4h 冷却（最近一条帖，含软删，对齐 iOS lastPost）
                    val lastTs = momentRepo.lastPostTimestampForCharacter(character.uuid)
                    if (MomentPostGuards.isCooldownActive(lastTs, nowMillis)) continue
                    // 见面门（卷一 B1）：角色正在与用户线下见面 → 本轮不发帖（人在对面还刷朋友圈=当场穿帮）。
                    // 与睡眠门并列；**不记欠帖**（见面不是「错过发帖窗口」，下一轮周期照常评估）。
                    if (OfflineMeetingGate.characterInMeeting(conversationDao, character.uuid)) continue
                    // 睡眠 → 记欠帖（当天有效），醒后聊天补发（7.2.5）
                    if (sleepChecker.isSleeping(character.uuid, settings.scheduleSystemEnabled, nowMillis, zone)) {
                        MomentOwedPostStore.markOwedPost(context, character.uuid, nowMillis)
                        continue
                    }
                    // 生成→落库→排延迟互动（礼物分支在 generateAndPublishPost 内解析，主发帖/欠帖补发共用同段）。
                    generateAndPublishPost(character, config, settings.scheduleSystemEnabled, settings.petSystemEnabled, strings, nowMillis, zone)
                        ?.let { createdThisRun.add(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "角色 ${character.uuid} 朋友圈生成失败，跳过", e)
                }
            }
            // 13.7e：后台周期发帖这一轮完毕 → 推「X 发了新动态」系统通知（开关关 / 回前台路 / 无新帖则不推）。
            // 仅多投通知，不回头碰任何发帖逻辑/数值/概率；节流与合并由 newPostNotifier 内部判定。
            if (notifyOnNewPost && settings.momentNewPostNotificationEnabled && createdThisRun.isNotEmpty()) {
                newPostNotifier.notifyNewPosts(createdThisRun, nowMillis, zone)
            }
        } finally {
            release()
        }
    }

    /**
     * 生成一条角色帖 → 落库 → 排延迟互动（30~120s 后其他角色点赞/评论 7.2.4b；被 HyperOS 杀后靠 7.2.5
     * 恢复重建）。[checkAndGeneratePosts] 与 [catchUpPost] 共用（同一段逻辑只写一处）。
     * 返回落库的帖（13.7e 周期路据此推「新动态」通知）；生成空 → null（catchUpPost 忽略返回，行为不变）。
     */
    private suspend fun generateAndPublishPost(
        character: CharacterEntity,
        config: ApiConfigValues,
        scheduleSystemEnabled: Boolean,
        petSystemEnabled: Boolean,
        strings: MomentPromptStrings,
        nowMillis: Long,
        zone: ZoneId,
    ): MomentPostEntity? {
        // 礼物朋友圈分支（P9.2e，1:1 iOS resolveGiftBranch；主发帖与欠帖补发共用本段）：48h 内珍贵/手作收礼、
        // 24h 冷却过 → 注入英文灵感段 + triggerType=gift_received + 代表礼物 uuid。无候选 → 走原 auto_draft。
        // 宠物商店贵价购买分支（P9.3c，1:1 iOS：gift 未命中时才试 resolveMomentBranch）：48h 内 ≥300 购买、24h 冷却过
        // → 注入英文灵感段 + triggerType=pet_shop_purchase（代表物品**不**存 relatedGiftId，仅 hint 入 prompt=iOS）。
        val giftBranch = giftQueue.resolveGiftBranch(character, nowMillis)
        val petShopBranch = if (giftBranch == null) petShopQueue.resolveMomentBranch(character, nowMillis) else null
        val inspiration = giftBranch?.promptHint ?: petShopBranch?.promptHint
        val content = generatePostContent(
            character, config, scheduleSystemEnabled, petSystemEnabled, strings, nowMillis, zone, inspiration,
        ) ?: return null
        val triggerType = when {
            giftBranch != null -> MomentTriggerType.GIFT_RECEIVED
            petShopBranch != null -> MomentTriggerType.PET_SHOP_PURCHASE
            else -> MomentTriggerType.AUTO_DRAFT
        }
        val post = MomentPostEntity(
            uuid = UUID.randomUUID().toString(),
            content = content,
            timestamp = nowMillis,
            authorTypeRaw = "character",
            characterUuid = character.uuid,
            isAutoGenerated = true,
            triggerTypeRaw = triggerType.raw,
            relatedGiftId = giftBranch?.representativeGiftId,
        )
        momentRepo.upsert(post)
        Log.d(TAG, "朋友圈已生成：${character.name} -> ${post.uuid}（${triggerType.raw}）")
        interactionService.scheduleGeneratedPostInteraction(post.uuid)
        return post
    }

    /**
     * 欠帖补发（1:1 iOS `catchUpPost`）：角色发帖时在睡、记了欠帖，醒后聊天触发本方法补发。
     * **不**查 4h 冷却/睡眠（就是为补睡时漏发的），仅守 频率>0 + 今日上限 + API + 角色存活。
     * 由 [MomentCatchUpWorker] 调用（延迟 40~80s 已由 worker initialDelay 实现，故此处不再 sleep）。
     */
    suspend fun catchUpPost(
        characterUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val settings = settingsRepo.getAppSettings()
        if (settings.momentAutoPostFrequency <= 0) return
        val startOfDay = DateFormatters.startOfDayMillis(nowMillis, zone)
        val todayCount = momentRepo.countPostsForCharacterSince(characterUuid, startOfDay)
        if (MomentPostGuards.isDailyCapReached(todayCount, settings.momentAutoPostFrequency)) return
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MOMENT_GENERATION) ?: return
        val character = characterRepo.get(characterUuid) ?: return
        val strings = MomentPromptStrings.from(PromptStrings(context))
        generateAndPublishPost(character, config, settings.scheduleSystemEnabled, settings.petSystemEnabled, strings, nowMillis, zone)
    }

    /**
     * 聊天回复完成后调用：若该角色有当天有效欠帖，清欠帖标记并排 [MomentCatchUpWorker]（延迟 40~80s 补发，
     * 模拟「回完消息顺手发朋友圈」）。先清后排 + 唯一任务名 KEEP → 不重复补发；worker 跨进程死亡仍存活。
     */
    fun triggerCatchUpPostIfNeeded(
        characterUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (!MomentOwedPostStore.hasOwedPost(context, characterUuid, nowMillis, zone)) return
        MomentOwedPostStore.clearOwedPost(context, characterUuid)
        backgroundScheduler.scheduleOneShot(
            uniqueName = MomentCatchUpWorker.uniqueName(characterUuid),
            workerClass = MomentCatchUpWorker::class.java,
            initialDelay = Duration.ofSeconds(Random.nextLong(CATCH_UP_DELAY_MIN_S, CATCH_UP_DELAY_MAX_S + 1)),
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
            inputData = Data.Builder().putString(MomentCatchUpWorker.KEY_CHARACTER_UUID, characterUuid).build(),
        )
    }

    /**
     * 生成一条朋友圈正文（不落库）。装配 [MomentPostPromptBuilder] 系统提示词 → LLM completion(temp 0.9)
     * → 剥 think，空则 200ms 重试 1 次（对齐日记/成长对空响应的处理）。失败/空 → null。
     */
    private suspend fun generatePostContent(
        character: CharacterEntity,
        config: ApiConfigValues,
        scheduleSystemEnabled: Boolean,
        petSystemEnabled: Boolean,
        strings: MomentPromptStrings,
        nowMillis: Long,
        zone: ZoneId,
        giftInspiration: String? = null,
    ): String? {
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" } // 图纸一·B5：发帖 reqContent 用真实用户名
        val recentOwnContents = momentRepo.recentPostsForCharacter(character.uuid, RECENT_POSTS)
            .joinToString("\n") { it.content }
        val recentUserPosts = momentRepo.recentUserPosts(RECENT_POSTS).map {
            RecentUserPost(
                timeDescription = DateFormatters.momentTimeDescription(it.timestamp, nowMillis, zone),
                content = it.content,
                hasImages = it.imagePaths.isNotEmpty(),
            )
        }
        val hotInterests = MomentPostPromptBuilder.hotInterestNames(character.dynamicInterests)
        val traits = MomentPostPromptBuilder.personalityTraits(strings, character.personalitySpectrum.values)
        val nowContext = MomentPromptContext.buildNowContext(MomentPromptContext.NowScenario.POST, nowMillis, zone)
        val schedulePrompt = buildSchedulePrompt(character, scheduleSystemEnabled, nowMillis, zone)

        // 宠物状态注入（moments-logic-1·1:1 iOS MomentGenerationActor+Content）：宠物系统开 + 角色有宠物 + 未离家出走
        // → 注入「你和朋友一起养了一只宠物：名（物种，阶段）」+「可偶尔在动态里提到宠物」。离家出走/无宠物/系统关 → 省略。
        val pet = petRepository.getForCharacter(character.uuid)
        val petStatus = MomentPostPromptBuilder.petStatusBlock(
            strings = strings,
            petEnabled = petSystemEnabled,
            petName = pet?.name,
            speciesDisplay = pet?.species?.displayName,
            stageDisplay = pet?.growthStage?.displayName,
            isRanAway = pet?.neglectPhase == PetNeglectPhase.RAN_AWAY,
        )

        val systemPrompt = MomentPostPromptBuilder.build(
            strings = strings,
            character = character,
            hotInterestNames = hotInterests,
            personalityTraits = traits,
            recentUserPosts = recentUserPosts,
            recentOwnContents = recentOwnContents,
            nowContext = nowContext,
            schedulePrompt = schedulePrompt,
            giftInspiration = giftInspiration,   // P9.2e 礼物分支（resolveGiftBranch 命中时为英文灵感段）
            petStatus = petStatus,    // moments-logic-1（14.4b）
            userName = userName,      // 图纸一·B5：reqContent %s 填真实用户名
        )

        val messages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = strings.userMessage),
        )

        var result = ""
        for (attempt in 1..2) {
            val buffer = contextLog.completion(
                source = LogSource.MOMENT_POST,
                characterName = character.name,
                config = config,
                messages = messages,
                temperature = 0.9,
            )
            val candidate = MemoryService.strippingThinkingTags(buffer)
            if (candidate.isNotEmpty()) {
                result = candidate
                break
            }
            if (attempt < 2) delay(200)
        }
        // 脏数据门（1:1 iOS guard GeneratedContentValidator.isLikelyValid else throw）：剥 think 后仍是
        // "Token count:"/{"error"}/纯数字等非正文 → 视为生成失败、不入库（返回 null = iOS 抛错不持久化）。
        // 动态额外抬最短长度门：提示词要求 50-150 字，<MIN_POST_CONTENT_LENGTH 字基本可断定是聊天腔
        // 短回复/罐头输出而非动态（2026-07-07「嗯嗯，刚看到消息。」入库教训）；丢弃后下一轮周期重试。
        return result.takeIf { GeneratedContentValidator.isLikelyValid(it, MIN_POST_CONTENT_LENGTH) }
    }

    /**
     * 见面后「朋友圈呼应帖」（卷二 §5④·图纸 §3.3）：生成正文 → 落库 → 排延迟互动 → 按设置推「新动态」通知。
     *
     * 走**既有** [generatePostContent] 装配（提示词基座 / 人称 / 忌口零碰），只往现成的 inspiration 灵感槽喂一段
     * 见面素材（[MeetingMomentEchoContent.inspiration]·M3 逐字锁定）；帖身份 = `AUTO_DRAFT` + [MeetingMomentEchoPlanner.echoPostUuid]
     * 确定性 uuid（不加枚举值；worker 重投 / 重装恢复经 upsert 恒至多一条）。见面日期口径与余温共用
     * [OfflineAfterglowService.anchorLabel]。
     *
     * J9 内容防线：[MeetingMomentEchoContent.violation] 不过 → 把原因拼进灵感尾部**重写一次**，仍不过返回 null 静默放弃
     * （宁缺毋滥·与朋友圈自动发帖「宁可不发绝不发错」同源）。
     */
    internal suspend fun generateEchoPost(
        character: CharacterEntity,
        config: ApiConfigValues,
        row: OfflineMeetingMemoryEntity,
        nowMillis: Long,
        zone: ZoneId,
    ): MomentPostEntity? {
        val settings = settingsRepo.getAppSettings()
        val strings = MomentPromptStrings.from(PromptStrings(context))
        val nickname = userProfileDao.get()?.nickname.orEmpty().trim()
        val callName = nickname.takeIf { it.isNotEmpty() && it != MeetingMomentEchoContent.USER_LITERAL }
            ?: MeetingMomentEchoContent.FALLBACK_CALL_NAME
        val dayLabel = OfflineAfterglowService.anchorLabel(row.startedAtMillis, Instant.ofEpochMilli(nowMillis), zone)
        val base = MeetingMomentEchoContent.inspiration(dayLabel, callName, row.location, row.summary)
        var feedback = ""
        repeat(2) {
            val content = generatePostContent(
                character, config, settings.scheduleSystemEnabled, settings.petSystemEnabled,
                strings, nowMillis, zone, giftInspiration = base + feedback,
            )
            val violation = MeetingMomentEchoContent.violation(content, nickname)
            if (content != null && violation == null) {
                val post = MomentPostEntity(
                    uuid = MeetingMomentEchoPlanner.echoPostUuid(row.sessionId),
                    content = content,
                    timestamp = nowMillis,
                    authorTypeRaw = "character",
                    characterUuid = character.uuid,
                    isAutoGenerated = true,
                    triggerTypeRaw = MomentTriggerType.AUTO_DRAFT.raw,
                    relatedGiftId = null,
                )
                momentRepo.upsert(post)
                Log.d(TAG, "见面呼应帖已生成：${character.name} -> ${post.uuid}")
                interactionService.scheduleGeneratedPostInteraction(post.uuid)
                if (settings.momentNewPostNotificationEnabled) {
                    newPostNotifier.notifyNewPosts(listOf(post), nowMillis, zone)
                }
                return post
            }
            Log.i(TAG, "见面呼应帖不合格（$violation）session=${row.sessionId}")
            feedback = "（上一条不合格：$violation。请重写。）"
        }
        return null
    }

    /** 今日日程素材段（硬编码中文）。日程系统关 / 无今日日程 → ""。 */
    private suspend fun buildSchedulePrompt(
        character: CharacterEntity,
        scheduleSystemEnabled: Boolean,
        nowMillis: Long,
        zone: ZoneId,
    ): String {
        if (!scheduleSystemEnabled) return ""
        val today = DateFormatters.startOfDayMillis(nowMillis, zone)
        val schedule = scheduleDao.scheduleFor(character.uuid, today) ?: return ""
        val events = scheduleDao.eventsForSchedule(schedule.uuid)
        return MomentPromptContext.buildSchedulePromptText(events, nowMillis, zone, character.name)
    }

    private fun tryAcquire(now: Long): Boolean {
        while (true) {
            val cur = gate.get()
            if (cur.running) {
                val elapsed = now - cur.startedAt
                if (elapsed > REENTRANT_WARN_MS) Log.w(TAG, "朋友圈生成仍在运行（已 ${elapsed / 1000}s），跳过本次触发")
                return false
            }
            if (gate.compareAndSet(cur, GateState(running = true, startedAt = now))) return true
        }
    }

    private fun release() {
        gate.set(GateState(running = false, startedAt = 0L))
    }

    private companion object {
        const val TAG = "MomentGen"
        const val THIRTY_DAYS_MS = 30L * 24 * 3600 * 1000
        const val REENTRANT_WARN_MS = 300L * 1000
        const val RECENT_POSTS = 3   // iOS fetchLimit 3 (own dedup + user-post context)

        /** 动态正文最短长度（trim 后字符数）：低于此视为聊天腔短回复/罐头输出，不入库。 */
        const val MIN_POST_CONTENT_LENGTH = 10

        /** 欠帖补发延迟 40~80s（iOS `catchUpPost` random(40...80)，安卓由 worker initialDelay 实现）。 */
        const val CATCH_UP_DELAY_MIN_S = 40L
        const val CATCH_UP_DELAY_MAX_S = 80L
    }
}
