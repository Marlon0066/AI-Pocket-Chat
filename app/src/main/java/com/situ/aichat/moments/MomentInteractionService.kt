package com.situ.aichat.moments

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.MomentNotificationType
import com.situ.aichat.data.model.dynamicInterests
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatContentPart
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.notification.NotificationScheduleRules
import com.situ.aichat.notification.Notifier
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.schedule.CharacterSleepChecker
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * 朋友圈 AI 互动引擎（M06 7.2.4b）。1:1 移植 iOS `MomentGenerationActor+Interactions`
 * （`autoInteractWithPost` ~21-306 / `generateReplyToComment` ~312 / 互动通知 ~405 /
 * `processPendingInteractions` ~437）。M06 最复杂的异步块——spec §4 韧性头号风险。
 *
 * 职责：对一条动态按相关性评分决定哪些角色点赞 / 评论，AI 还会互相接话；并在用户评论后延迟回复。
 * 调度（延迟 30~120s 后触发本类、用户操作后触发）由 [MomentDelayedTaskRegistry] 管理；并发 LLM 调用
 * 由全局 [MomentLlmSlot]（上限 2）限流。跨协程一律用 uuid 重查、绝不传 Room 对象跨线程（spec §4.3）。
 *
 * **韧性不变量**：所有延迟产物（评论 / 点赞 / 回复）都必须能被前台恢复（7.2.5）补偿重建——被 HyperOS
 * 杀后台后，本类的延迟循环会丢失，靠恢复重跑。睡眠角色不丢，入 [MomentPendingInteractionStore] 待醒后补。
 *
 * **安卓有意偏差**：评论 vision 暂走「盲图」分支——安卓 LLM 客户端 [ChatMessageDto] 当前纯文本（多模态
 * 后续才接），故 `visionEnabled=false`，提示词告知「有 N 张图但看不到」；多模态落地后翻开即真 vision。
 */
@Singleton
class MomentInteractionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val momentRepo: MomentRepository,
    private val characterRepo: CharacterRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val sleepChecker: CharacterSleepChecker,
    private val messageDao: MessageDao,
    private val contextLog: ContextLogService,
    private val llmSlot: MomentLlmSlot,
    private val userProfileDao: UserProfileDao,
    private val scheduleDao: ScheduleDao,
    private val conversationDao: ConversationDao,
) {

    /**
     * 对某条动态自动互动：评分 → 点赞（同步）→ 评论（活跃度打散时间 + 逐条延迟 + 三重校验）。
     * 1:1 iOS `autoInteractWithPost`。跨协程安全的业务键 [postUuid] 真实查库一次；查不到 / 软删 → 静默返回
     * （绝不触碰僵尸对象，M-21 教训）。被取消（删帖）时 `delay`/suspend 调用抛 [CancellationException] 退出。
     */
    suspend fun autoInteractWithPost(
        postUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        // 帖子存活校验（过滤软删，避免对已删帖产生互动）。
        val post = momentRepo.getPost(postUuid) ?: return
        if (post.isSoftDeleted) return
        // API 守卫。
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MOMENT_GENERATION) ?: return
        val settings = settingsRepo.getAppSettings()

        val allCharacters = characterRepo.getAll()
        if (allCharacters.isEmpty()) return

        // 候选 = 全角色 排帖子作者 + 排已评论角色（Recovery 路径可能在已有 AI 评论的帖上重跑）。
        val existingAICommentUuids = momentRepo.commentsForPost(postUuid)
            .filter { it.authorTypeRaw == MomentAuthorType.CHARACTER.raw }
            .mapNotNull { it.characterUuid }
            .toSet()
        val candidates = allCharacters.filter {
            it.uuid != post.characterUuid && it.uuid !in existingAICommentUuids
        }
        if (candidates.isEmpty()) return

        // 见面门（卷一 B1·置于睡眠分流前，日程开/关两分支都覆盖）：见面中的角色本轮不互动、不入待互动队列。
        val availableCandidates = candidates.filterNot { OfflineMeetingGate.characterInMeeting(conversationDao, it.uuid) }
        if (availableCandidates.isEmpty()) return

        // 睡着的角色入待互动队列（仅日程系统开启时），醒后由前台恢复补处理（7.2.5）。
        val awakeCandidates: List<CharacterEntity> = if (settings.scheduleSystemEnabled) {
            val awake = mutableListOf<CharacterEntity>()
            for (candidate in availableCandidates) {
                if (sleepChecker.isSleeping(candidate.uuid, scheduleSystemEnabled = true, nowMillis, zone)) {
                    MomentPendingInteractionStore.add(
                        context = context,
                        postUuid = post.uuid,
                        postTimestampMillis = post.timestamp,
                        postAuthorUuid = post.characterUuid,
                        characterUuid = candidate.uuid,
                        nowMillis = nowMillis,
                    )
                } else {
                    awake.add(candidate)
                }
            }
            awake
        } else {
            availableCandidates
        }
        if (awakeCandidates.isEmpty()) return

        // 相关性打分（关系 0.4 + 兴趣 0.35 + 活跃 0.25），复用 7.2.2 纯算法评分器。
        val candidateScores = awakeCandidates.map { character ->
            MomentRelevanceScorer.CandidateScore(
                characterUuid = character.uuid,
                score = MomentRelevanceScorer.score(
                    relationship = character.relationshipQuality,
                    initialInterests = character.initialInterests,
                    dynamicInterests = character.dynamicInterests,
                    recentMessageCount = recentMessageCount(character.uuid, nowMillis),
                    postContent = post.content,
                ),
            )
        }

        // 剩余评论预算 = max(0, 上限 - 已有 AI 评论数)；Recovery 已补过 / 用户调小上限时自动收敛。
        val remainingCommentBudget = maxOf(0, settings.momentAutoCommentFrequency - existingAICommentUuids.size)
        val selectionConfig = MomentRelevanceScorer.SelectionConfig.withUserSettings(
            likeUpperBound = LIKE_UPPER_BOUND,
            commentUpperBound = remainingCommentBudget,
            autoLikeEnabled = settings.momentAutoLikeEnabled,
        )
        val selection = MomentRelevanceScorer.selectInteractions(
            candidates = candidateScores,
            config = selectionConfig,
            random = { Random.nextDouble() },
        )
        val characterByUuid = awakeCandidates.associateBy { it.uuid }
        Log.d(
            TAG,
            "朋友圈互动筛选 post=${postUuid.take(6)} 候选=${awakeCandidates.size} → " +
                "点赞=${selection.likeUuids.size} 评论=${selection.commentUuids.size}",
        )

        // 自动点赞（selection 已保证「评论者必点赞」不变量 commentUuids ⊆ likeUuids）。
        val postByUser = post.authorTypeRaw == MomentAuthorType.USER.raw
        for (uuid in selection.likeUuids) {
            if (uuid !in characterByUuid) continue
            if (momentRepo.hasLiked(postUuid, uuid)) continue
            momentRepo.addLike(postUuid, MomentAuthorType.CHARACTER, uuid)
            if (postByUser) {
                createInteractionNotification(MomentNotificationType.LIKE_ON_USER_POST, uuid, post, nowMillis = nowMillis)
            } else if (momentRepo.hasUserLike(postUuid)) {
                createInteractionNotification(MomentNotificationType.CO_LIKE, uuid, post, nowMillis = nowMillis)
            }
        }

        // 自动评论（按活跃度打散时间，整体窗口约 1-15 分钟）。
        if (selection.commentUuids.isEmpty()) return
        val userNickname = userNickname()
        val strings = MomentCommentPromptStrings.from(PromptStrings(context))
        val characterNames = allCharacters.associate { it.uuid to it.name }
        val scoresByUuid = candidateScores.associate { it.characterUuid to it.score }

        // 为每个评论候选预算延迟（活跃越高越快），按延迟升序（先到先评）。
        val slots = selection.commentUuids.map { uuid ->
            val activity = scoresByUuid[uuid]?.activity ?: 0.0
            val delaySeconds = MomentRelevanceScorer.interactionDelaySeconds(
                activityScore = activity,
                randomJitter = Random.nextDouble(),
            )
            CommentSlot(uuid = uuid, delayMs = (delaySeconds * 1000).toLong())
        }.sortedBy { it.delayMs }

        val startTime = System.currentTimeMillis()
        for (slot in slots) {
            // sleep 到目标时间点（相对进入评论阶段的起点）；取消时 delay 抛 CancellationException 退出（=iOS break）。
            val remaining = slot.delayMs - (System.currentTimeMillis() - startTime)
            if (remaining > 0) delay(remaining)

            // 中途校验：帖子已删 → 中止评论链（取消由 suspend 调用自身处理）。
            val livePost = momentRepo.getPost(postUuid)
            if (livePost == null || livePost.isSoftDeleted) break

            if (slot.uuid !in characterByUuid) continue
            val character = characterByUuid.getValue(slot.uuid)
            if (momentRepo.commentCountByCharacter(postUuid, character.uuid) > 0) continue

            // 本条评论的生成时刻：评论链 1-15min，用 fresh now 让时间/日程上下文与 iOS 每条 fresh Date() 一致
            // （否则跨时段的链尾会沿用入口时刻的时段/「正在做的事」）。
            val genNow = System.currentTimeMillis()
            // AI 回 AI 决策：sleep 完成后做，此时前面 slot 产生的评论已落库可见（nil = 直接评帖子）。
            val replyResolution = selectReplyTargetForSlot(postUuid, character.uuid, characterNames)
            val replyTargetForLlm = replyResolution?.let { res ->
                refetchReplyTargetIfAlive(res.commentUuid)?.let { target ->
                    CommentReplyTarget(
                        timeDescription = DateFormatters.momentTimeDescription(target.timestamp, genNow, zone),
                        authorName = res.authorName,
                        content = target.content,
                    )
                }
            }

            // LLM 调用前取槽，满则跳过该条评论（不阻塞整个方法）；finally 保证释放（=iOS defer）。
            if (!llmSlot.tryAcquire()) continue
            try {
                val commentContent = generateCommentContent(
                    character = character,
                    post = post,
                    replyTarget = replyTargetForLlm,
                    userNickname = userNickname,
                    characterNames = characterNames,
                    config = config,
                    strings = strings,
                    scheduleSystemEnabled = settings.scheduleSystemEnabled,
                    nowMillis = genNow,
                    zone = zone,
                )
                if (!commentContent.isNullOrBlank()) {
                    // 落库前再校验目标存活（cascade 下 parent 被删会连带新评论消失，宁可降级为普通评论）。
                    val finalTarget = replyResolution?.let { refetchReplyTargetIfAlive(it.commentUuid) }
                    val finalReplyToName = if (finalTarget != null) replyResolution.authorName else null
                    // 每条评论 Room insert 即时提交（=iOS 每条独立 save，防中途被杀丢前面的）。
                    momentRepo.addComment(
                        postUuid = postUuid,
                        content = commentContent,
                        authorType = MomentAuthorType.CHARACTER,
                        characterUuid = character.uuid,
                        replyToName = finalReplyToName,
                        parentCommentUuid = finalTarget?.uuid,
                    )
                    // 互动通知：角色评论了用户的帖子（AI 回 AI 时 preview 加「回复 XX：」前缀，避免失去上下文）。
                    if (postByUser) {
                        val preview = if (finalReplyToName != null) {
                            context.getString(R.string.moment_notif_comment_reply_prefix, finalReplyToName, commentContent)
                        } else {
                            commentContent
                        }
                        createInteractionNotification(MomentNotificationType.COMMENT_ON_USER_POST, character.uuid, post, preview, genNow)
                    }
                    // 评论者自动点赞（冗余保障；selection 在 autoLike=true 时已加入，保留防 selection 路径变更）。
                    if (settings.momentAutoLikeEnabled && !momentRepo.hasLiked(postUuid, character.uuid)) {
                        momentRepo.addLike(postUuid, MomentAuthorType.CHARACTER, character.uuid)
                        if (postByUser) {
                            createInteractionNotification(MomentNotificationType.LIKE_ON_USER_POST, character.uuid, post, nowMillis = genNow)
                        } else if (momentRepo.hasUserLike(postUuid)) {
                            createInteractionNotification(MomentNotificationType.CO_LIKE, character.uuid, post, nowMillis = genNow)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // 取消传播 → 退出协程（=iOS catch CancellationError break）
            } catch (e: Exception) {
                Log.w(TAG, "朋友圈评论生成失败 post=${postUuid.take(6)}，跳过该条", e)
            } finally {
                llmSlot.release()
            }
        }
    }

    /**
     * AI 回复用户的评论（用户在详情页评论后延迟触发，1:1 iOS `generateReplyToComment`）。
     * 业务键真实查库；评论必须属于该帖（一致性校验）。回复角色：被回的是 AI 评论 → 该 AI；否则帖主是
     * AI → 帖主；都不是 → 随机一个角色。不取 LLM 槽（=iOS，仅 autoInteract 评论链限流）。
     */
    suspend fun generateReplyToComment(
        commentUuid: String,
        postUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val userComment = momentRepo.getComment(commentUuid) ?: return
        val post = momentRepo.getPost(postUuid) ?: return
        if (post.isSoftDeleted) return
        // 一致性：评论必须属于该帖（防调用方传参错位，iOS userComment.post?.uuid == postUUID）。
        if (userComment.postUuid != postUuid) return
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MOMENT_GENERATION) ?: return

        val allCharacters = characterRepo.getAll()
        val parentComment = userComment.parentCommentUuid?.let { momentRepo.getComment(it) }
        val replyCharacterUuid: String? = when {
            parentComment != null && parentComment.authorTypeRaw == MomentAuthorType.CHARACTER.raw -> parentComment.characterUuid
            post.authorTypeRaw == MomentAuthorType.CHARACTER.raw -> post.characterUuid
            else -> allCharacters.randomOrNull()?.uuid
        }
        val character = replyCharacterUuid?.let { characterRepo.get(it) } ?: return

        val userNickname = userNickname()
        val strings = MomentCommentPromptStrings.from(PromptStrings(context))
        val characterNames = allCharacters.associate { it.uuid to it.name }
        val scheduleSystemEnabled = settingsRepo.getAppSettings().scheduleSystemEnabled

        // 被回的是用户评论 → 用户昵称；是角色评论 → 该角色名（兜底「朋友」）。
        val userCommentByUser = userComment.authorTypeRaw == MomentAuthorType.USER.raw
        val replyTargetAuthorName = if (userCommentByUser) {
            userNickname
        } else {
            userComment.characterUuid?.let { characterNames[it] }?.takeIf { it.isNotEmpty() } ?: strings.friendFallback
        }
        val replyTarget = CommentReplyTarget(
            timeDescription = DateFormatters.momentTimeDescription(userComment.timestamp, nowMillis, zone),
            authorName = replyTargetAuthorName,
            content = userComment.content,
        )

        try {
            val content = generateCommentContent(
                character = character,
                post = post,
                replyTarget = replyTarget,
                userNickname = userNickname,
                characterNames = characterNames,
                config = config,
                strings = strings,
                scheduleSystemEnabled = scheduleSystemEnabled,
                nowMillis = nowMillis,
                zone = zone,
            ) ?: return
            momentRepo.addComment(
                postUuid = postUuid,
                content = content,
                authorType = MomentAuthorType.CHARACTER,
                characterUuid = character.uuid,
                replyToName = if (userCommentByUser) userNickname else null,
                parentCommentUuid = userComment.uuid,
            )
            // 互动通知：角色回复了用户的评论。
            if (userCommentByUser) {
                createInteractionNotification(MomentNotificationType.REPLY_TO_USER_COMMENT, character.uuid, post, content, nowMillis)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "朋友圈回复生成失败 post=${postUuid.take(6)} comment=${commentUuid.take(6)}", e)
        }
    }

    // ---- 待互动队列 drain（睡眠角色醒后补，7.2.5）----

    /**
     * 处理待互动队列（1:1 iOS `processPendingInteractions`）：睡着时入队的互动，醒后补点赞 + 评论。
     * 回前台调用。移除 24h 前入队的项；仍在睡的角色保留待下次；帖没了/已互动则丢弃；每项后 delay 5~20s。
     * **幂等**：靠 alreadyInteracted 去重，故中途被取消（未 save remaining）下次重跑也不会重复互动。
     * 沉浸模式跳过且**不清空队列**（退出沉浸后下次继续，P10 stub）。
     */
    suspend fun processPendingInteractions(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val queue = MomentPendingInteractionStore.load(context)
        if (queue.isEmpty()) return
        // 移除 24h 前入队的项（过期）。
        val fresh = queue.filter { it.queuedAtMillis >= nowMillis - DAY_MS }
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MOMENT_GENERATION)
        if (config == null) {
            MomentPendingInteractionStore.save(context, fresh)
            return
        }
        val settings = settingsRepo.getAppSettings()
        val userNickname = userNickname()
        val strings = MomentCommentPromptStrings.from(PromptStrings(context))
        val characterNames = characterRepo.getAll().associate { it.uuid to it.name }

        val remaining = mutableListOf<MomentPendingInteractionStore.PendingInteraction>()
        for (item in fresh) {
            // 仍在睡 → 保留待下次（不消费）。
            if (sleepChecker.isSleeping(item.characterUuid, settings.scheduleSystemEnabled, nowMillis, zone)) {
                remaining.add(item)
                continue
            }
            // 见面中 → 同样保留待下次（卷一 B1·不消费）。
            if (OfflineMeetingGate.characterInMeeting(conversationDao, item.characterUuid)) {
                remaining.add(item)
                continue
            }
            val post = momentRepo.getPost(item.postUuid) ?: continue
            if (post.isSoftDeleted) continue
            val character = characterRepo.get(item.characterUuid) ?: continue
            // 已评论或已赞过 → 丢弃（去重，幂等）。
            if (momentRepo.commentCountByCharacter(post.uuid, character.uuid) > 0 ||
                momentRepo.hasLiked(post.uuid, character.uuid)
            ) {
                continue
            }

            val postByUser = post.authorTypeRaw == MomentAuthorType.USER.raw
            // 点赞（待互动总是点赞，不看 autoLike 设置=iOS）。
            momentRepo.addLike(post.uuid, MomentAuthorType.CHARACTER, character.uuid)
            if (postByUser) {
                createInteractionNotification(MomentNotificationType.LIKE_ON_USER_POST, character.uuid, post, nowMillis = nowMillis)
            } else if (momentRepo.hasUserLike(post.uuid)) {
                createInteractionNotification(MomentNotificationType.CO_LIKE, character.uuid, post, nowMillis = nowMillis)
            }
            // 评论（仅频率>0；replyTarget=null 不接话=iOS）。
            if (settings.momentAutoCommentFrequency <= 0) continue
            val genNow = System.currentTimeMillis()
            try {
                val content = generateCommentContent(
                    character = character,
                    post = post,
                    replyTarget = null,
                    userNickname = userNickname,
                    characterNames = characterNames,
                    config = config,
                    strings = strings,
                    scheduleSystemEnabled = settings.scheduleSystemEnabled,
                    nowMillis = genNow,
                    zone = zone,
                )
                if (!content.isNullOrBlank()) {
                    momentRepo.addComment(post.uuid, content, MomentAuthorType.CHARACTER, character.uuid)
                    if (postByUser) {
                        createInteractionNotification(MomentNotificationType.COMMENT_ON_USER_POST, character.uuid, post, content, genNow)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "待互动评论生成失败 post=${post.uuid.take(6)}", e)
            }
            delay(Random.nextLong(PENDING_DELAY_MIN_MS, PENDING_DELAY_MAX_MS + 1))
        }
        MomentPendingInteractionStore.save(context, remaining)
    }

    // ---- 调度（延迟触发，经 [MomentDelayedTaskRegistry]）----

    /**
     * AI 自动发帖后排互动（发帖后 30~120s 触发，1:1 iOS `MomentGenerationActor.swift:128-147` 内联 Task）。
     * 由 [MomentGenerationService] 落帖后调用（b-3 接线）。注册表去重 + 删帖可取消 + 被杀后靠恢复重建。
     */
    fun scheduleGeneratedPostInteraction(postUuid: String) {
        MomentDelayedTaskRegistry.register(postUuid, MomentDelayedTaskRegistry.Purpose.AutoInteraction) {
            delay(Random.nextLong(GENERATED_INTERACTION_MIN_MS, GENERATED_INTERACTION_MAX_MS + 1))
            autoInteractWithPost(postUuid)
        }
    }

    /**
     * 用户发布动态后排 AI 互动（延迟 delayMinutes*60 + random(0..60)s，1:1 iOS `scheduleAIInteraction`）。
     * 供 7.2.7 发布 UI 调用（当前无调用方）。
     */
    fun scheduleAIInteraction(postUuid: String, delayMinutes: Int) {
        MomentDelayedTaskRegistry.register(postUuid, MomentDelayedTaskRegistry.Purpose.AutoInteraction) {
            delay(delayMinutes * 60_000L + Random.nextLong(0, USER_INTERACTION_JITTER_MS + 1))
            autoInteractWithPost(postUuid)
        }
    }

    /**
     * 用户评论后排 AI 回复（延迟 delayMinutes*60 + random(0..30)s，1:1 iOS `scheduleAIReply`）。
     * 供 7.2.8 详情 UI 调用（当前无调用方）。
     */
    fun scheduleAIReply(commentUuid: String, postUuid: String, delayMinutes: Int) {
        MomentDelayedTaskRegistry.register(postUuid, MomentDelayedTaskRegistry.Purpose.Reply(commentUuid)) {
            delay(delayMinutes * 60_000L + Random.nextLong(0, USER_REPLY_JITTER_MS + 1))
            generateReplyToComment(commentUuid, postUuid)
        }
    }

    /** 取消某帖的全部在途延迟互动任务（删帖时调，1:1 iOS `cancelPendingInteractions`）。供 7.2.7 删帖调用。 */
    fun cancelPendingInteractions(postUuid: String) {
        MomentDelayedTaskRegistry.cancelAll(postUuid)
    }

    // ---- 评论生成 ----

    /**
     * 生成评论内容（temp 0.9，剥 think，空 → null 跳过）。装配 [MomentCommentPromptBuilder] 系统提示词。
     * [characterNames] = uuid→名 快照（解析帖主名 + 已有评论作者名）。vision 暂关（见类注释）。
     */
    private suspend fun generateCommentContent(
        character: CharacterEntity,
        post: MomentPostEntity,
        replyTarget: CommentReplyTarget?,
        userNickname: String,
        characterNames: Map<String, String>,
        config: ApiConfigValues,
        strings: MomentCommentPromptStrings,
        scheduleSystemEnabled: Boolean,
        nowMillis: Long,
        zone: ZoneId,
    ): String? {
        val postAuthorName = if (post.authorTypeRaw == MomentAuthorType.USER.raw) {
            userNickname
        } else {
            post.characterUuid?.let { characterNames[it] }?.takeIf { it.isNotEmpty() } ?: strings.friendFallback
        }
        val existingComments = momentRepo.commentsForPost(post.uuid)
            .filter { it.authorTypeRaw == MomentAuthorType.CHARACTER.raw }
            .map { c ->
                CommentContextLine(
                    timeDescription = DateFormatters.momentTimeDescription(c.timestamp, nowMillis, zone),
                    authorName = c.characterUuid?.let { characterNames[it] }?.takeIf { it.isNotEmpty() } ?: strings.friendFallback,
                    content = c.content,
                )
            }
        // 首图编码放在装配之前：编码失败（文件没了）就退回盲图分支，避免「文案说看得到、报文里却没图」。
        val firstPhotoDataUri = if (config.visionEnabled && post.imagePaths.isNotEmpty()) {
            ContentImageStore.loadAsDataUri(post.imagePaths.first())
        } else {
            null
        }
        val canSeePhotos = firstPhotoDataUri != null
        val systemPrompt = MomentCommentPromptBuilder.build(
            strings = strings,
            character = character,
            postAuthorName = postAuthorName,
            postTimeDescription = DateFormatters.momentTimeDescription(post.timestamp, nowMillis, zone),
            postContent = post.content,
            isPostByCharacter = post.authorTypeRaw == MomentAuthorType.CHARACTER.raw,
            nowContext = MomentPromptContext.buildNowContext(MomentPromptContext.NowScenario.COMMENT, nowMillis, zone),
            scheduleContext = buildCommentScheduleLine(character, strings, scheduleSystemEnabled, nowMillis, zone),
            photoCount = post.imagePaths.size,
            // 图片多模态一期（拍板④）：视觉能力真值上线——配置支持看图且这条动态真有图，就挂首图并走
            // photosVision 文案（文案原话「You can see the first one attached below」= 只挂第一张，
            // 与下面 contentParts 的构造严格对齐）；否则照旧走「有 N 张图但你看不到」的盲图分支。
            visionEnabled = canSeePhotos,
            existingComments = existingComments,
            replyTarget = replyTarget,
        )

        val messages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            if (firstPhotoDataUri != null) {
                ChatMessageDto(
                    role = "user",
                    contentParts = listOf(
                        ChatContentPart.Text(strings.userMessage),
                        ChatContentPart.ImageUrl(firstPhotoDataUri),
                    ),
                )
            } else {
                ChatMessageDto(role = "user", content = strings.userMessage)
            },
        )
        val buffer = contextLog.completion(
            source = LogSource.MOMENT_COMMENT,
            characterName = character.name,
            config = config,
            messages = messages,
            temperature = COMMENT_TEMPERATURE,
        )
        // 脏数据门（1:1 iOS GeneratedContentValidator）：非正文（Token count/{"error"}/纯数字…）→ null 不入库。
        return MemoryService.strippingThinkingTags(buffer).takeIf { GeneratedContentValidator.isLikelyValid(it) }
    }

    /**
     * 评论日程上下文行（1:1 iOS `buildCommentScheduleContext`）：日程系统开 + 有当前非 userInteraction 事件
     * 才注入「正在做…（在…）（心情：…）」。否则 null。
     */
    private suspend fun buildCommentScheduleLine(
        character: CharacterEntity,
        strings: MomentCommentPromptStrings,
        scheduleSystemEnabled: Boolean,
        nowMillis: Long,
        zone: ZoneId,
    ): String? {
        if (!scheduleSystemEnabled) return null
        val today = DateFormatters.startOfDayMillis(nowMillis, zone)
        val schedule = scheduleDao.scheduleFor(character.uuid, today) ?: return null
        val events = scheduleDao.eventsForSchedule(schedule.uuid)
        val current = NotificationScheduleRules.currentEvent(events, nowMillis) ?: return null
        if (current.eventTypeRaw == EVENT_TYPE_USER_INTERACTION) return null
        return MomentCommentPromptBuilder.scheduleLine(
            strings = strings,
            activity = current.activity,
            location = current.location,
            moodText = current.moodText,
        )
    }

    // ---- AI 回 AI 评论目标决策 ----

    /**
     * 为某 slot 决策「回前面某条评论还是直接评帖子」。仅考虑顶级 character 评论（A 版 UI 最多 2 级缩进）。
     * 每次新鲜查库（看得到前面 slot 刚落的评论）。非 null = 回某条评论；null = 直接评帖子（1:1 iOS）。
     */
    private suspend fun selectReplyTargetForSlot(
        postUuid: String,
        speakerCharacterUuid: String,
        characterNames: Map<String, String>,
    ): ReplyTargetResolution? {
        val aiComments = momentRepo.commentsForPost(postUuid)
            .filter { it.authorTypeRaw == MomentAuthorType.CHARACTER.raw }
        // 分离顶级评论 vs 回复，并统计每条顶级评论已被回几次。
        val topLevel = mutableListOf<MomentCommentEntity>()
        val replyCountByParent = HashMap<String, Int>()
        for (c in aiComments) {
            val parentUuid = c.parentCommentUuid
            if (parentUuid != null) replyCountByParent[parentUuid] = (replyCountByParent[parentUuid] ?: 0) + 1
            else topLevel.add(c)
        }
        if (topLevel.isEmpty()) return null

        val candidates = topLevel.map { c ->
            MomentRelevanceScorer.ReplyCandidate(
                commentUuid = c.uuid,
                authorCharacterUuid = c.characterUuid ?: "",
                timestamp = c.timestamp,
                existingReplyCount = replyCountByParent[c.uuid] ?: 0,
            )
        }
        val targetUuid = MomentRelevanceScorer.selectReplyTarget(
            existingComments = candidates,
            currentCharacterUuid = speakerCharacterUuid,
            config = MomentRelevanceScorer.ReplyConfig(),
            random = { Random.nextDouble() },
        ) ?: return null

        // 拿作者名；查不到就放弃（降级为直接评帖子，比设 replyToName=「朋友」更干净）。
        val target = topLevel.firstOrNull { it.uuid == targetUuid } ?: return null
        val authorUuid = target.characterUuid ?: return null
        val name = characterNames[authorUuid]?.takeIf { it.isNotEmpty() } ?: return null
        return ReplyTargetResolution(commentUuid = targetUuid, authorName = name)
    }

    /** 落库前再查目标评论是否仍存在（LLM 生成期间用户可能删它，cascade 会连带回复消失）。null → 降级。 */
    private suspend fun refetchReplyTargetIfAlive(targetUuid: String): MomentCommentEntity? =
        momentRepo.getComment(targetUuid)

    // ---- 共用辅助 ----

    /** 用户昵称（空则兜底「用户」，复用提示词层 `pb_user_fallback`）。 */
    private suspend fun userNickname(): String =
        userProfileDao.get()?.nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.pb_user_fallback)

    /** 某角色最近 7 天与用户的非 system 消息数（活跃度维度数据源，iOS `fetchRecentMessageCount`）。 */
    private suspend fun recentMessageCount(characterUuid: String, nowMillis: Long): Int =
        messageDao.countRecentNonSystemForCharacter(characterUuid, nowMillis - ACTIVITY_WINDOW_MS)

    /**
     * 创建互动通知记录 + 顺手清理 30 天前已读通知（一次最多 50 条），1:1 iOS `createNotification`。
     * 通知驱动未读红点；系统通知投递 + 帖子详情深链随 7.2.7/7.2.8 路由落地（决策①）。
     */
    private suspend fun createInteractionNotification(
        type: MomentNotificationType,
        characterUuid: String,
        post: MomentPostEntity,
        contentPreview: String = "",
        nowMillis: Long,
    ) {
        momentRepo.addNotification(
            type = type,
            characterUuid = characterUuid,
            contentPreview = contentPreview,
            postTimestampMillis = post.timestamp,
        )
        momentRepo.deleteOldReadNotifications(nowMillis - THIRTY_DAYS_MS, NOTIFICATION_CLEANUP_LIMIT)
        // 决策①（P7.2.8）：互动同时发系统通知 + 深链进帖子详情（有意偏离 iOS app 内列表；铁律#1 原生达同效）。
        postSystemNotification(type, characterUuid, post, contentPreview)
    }

    /**
     * 朋友圈互动系统通知（决策①）。标题随类型 + 角色名（与 in-app 通知列表共用 4 类串）；正文 = 内容预览
     * （点赞类预览为空 → 仅标题）。notificationId 按 (类型,帖,角色) 稳定 → 同类重复互动替换不刷屏。无通知权限
     * 时 [Notifier] 静默跳过，in-app 红点/列表仍照常驱动。
     */
    private suspend fun postSystemNotification(
        type: MomentNotificationType,
        characterUuid: String,
        post: MomentPostEntity,
        contentPreview: String,
    ) {
        // 前台判定（卷一 C1）：App 前台（含见面剧场里）不弹横幅——App 内红点即提示（2-5b 拍板同源·
        // 对照 ChatReplyDeliverer.notifyIfNotViewing）。ProcessLifecycleOwner 纯 JVM 缺席 → runCatching 兜底。
        val appForeground = runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }.getOrDefault(false)
        if (appForeground) return
        val name = characterRepo.get(characterUuid)?.name ?: context.getString(R.string.moment_author_ai)
        val titleRes = when (type) {
            MomentNotificationType.COMMENT_ON_USER_POST -> R.string.moment_notif_title_comment
            MomentNotificationType.REPLY_TO_USER_COMMENT -> R.string.moment_notif_title_reply
            MomentNotificationType.LIKE_ON_USER_POST -> R.string.moment_notif_title_like
            MomentNotificationType.CO_LIKE -> R.string.moment_notif_title_colike
        }
        Notifier.postMomentInteraction(
            context = context,
            notificationId = interactionNotificationId(type, post.uuid, characterUuid),
            title = context.getString(titleRes, name),
            body = contentPreview,
            postUuid = post.uuid,
        )
    }

    /** 决策结果：要回复的评论 uuid + 该评论作者显示名（落库前会再 refetch 做存活校验）。 */
    private data class ReplyTargetResolution(val commentUuid: String, val authorName: String)

    /** 一个评论候选的延迟槽位（uuid + 相对评论阶段起点的延迟毫秒）。 */
    private data class CommentSlot(val uuid: String, val delayMs: Long)

    companion object {
        private const val TAG = "MomentInteract"

        /** 互动通知稳定 id（(类型,帖,角色) 复合）；P1-44 删角色撤已弹与发出处共用单源，改拼法必须同步。 */
        internal fun interactionNotificationId(type: MomentNotificationType, postUuid: String, characterUuid: String): Int =
            "moment:${type.raw}:$postUuid:$characterUuid".hashCode()

        /** 每帖 AI 点赞上限（硬编码，iOS `+Interactions.swift` likeUpperBound=5）。 */
        const val LIKE_UPPER_BOUND = 5

        /** AI 发帖后排互动延迟窗口 30~120s（iOS `Actor.swift:138` random(30...120)）。 */
        const val GENERATED_INTERACTION_MIN_MS = 30_000L
        const val GENERATED_INTERACTION_MAX_MS = 120_000L

        /** 用户发帖→互动的随机抖动上限 60s（iOS `+Interaction.swift:57` random(0...60)）。 */
        const val USER_INTERACTION_JITTER_MS = 60_000L

        /** 用户评论→回复的随机抖动上限 30s（iOS `+Interaction.swift:85` random(0...30)）。 */
        const val USER_REPLY_JITTER_MS = 30_000L

        /** 评论生成温度（iOS `+Content.swift` generateCommentContent temperature 0.9）。 */
        const val COMMENT_TEMPERATURE = 0.9

        /** 活跃度统计窗口：最近 7 天（iOS `fetchRecentMessageCount` 默认 days=7）。 */
        const val ACTIVITY_WINDOW_MS = 7L * 24 * 3600 * 1000

        /** 待互动队列项过期窗口：24 小时（iOS `oneDayAgo` 清理）。 */
        const val DAY_MS = 24L * 3600 * 1000

        /** 待互动队列每项处理后随机延迟 5~20s（iOS `Task.sleep(5...20)`）。 */
        const val PENDING_DELAY_MIN_MS = 5_000L
        const val PENDING_DELAY_MAX_MS = 20_000L

        /** 通知清理阈值：30 天前已读，一次最多清 50 条（iOS createNotification）。 */
        const val THIRTY_DAYS_MS = 30L * 24 * 3600 * 1000
        const val NOTIFICATION_CLEANUP_LIMIT = 50

        /** 日程事件类型「聊天写回/线下记录」，评论日程上下文跳过它（=iOS userInteraction 过滤）。 */
        const val EVENT_TYPE_USER_INTERACTION = "userInteraction"
    }
}
