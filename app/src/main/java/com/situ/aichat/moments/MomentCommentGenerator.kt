package com.situ.aichat.moments

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatContentPart
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.notification.NotificationScheduleRules
import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 朋友圈评论生成器：装配评论提示词 + 调模型 + 脏数据门、评论日程行、用户昵称。自 [MomentInteractionService]
 * 只搬不改拆出（朋友圈发布页重构·甲 chunk 1·图纸 docs/handoff/2026-09-27-朋友圈发布页-甲-底子.md §2.1 / §9 ④），
 * 互动总管 / 待互动 drain（[MomentPendingDrain]）共用这一份。
 */
@Singleton
class MomentCommentGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val momentRepo: MomentRepository,
    private val contextLog: ContextLogService,
    private val userProfileDao: UserProfileDao,
    private val scheduleDao: ScheduleDao,
) {
    private var worldBookAmbient: com.situ.aichat.worldbook.WorldBookAmbientInjector? = null

    @Inject
    fun bindWorldBookAmbient(injector: com.situ.aichat.worldbook.WorldBookAmbientInjector) {
        worldBookAmbient = injector
    }

    /**
     * 生成评论内容（temp 0.9，剥 think，空 → null 跳过）。装配 [MomentCommentPromptBuilder] 系统提示词。
     * [characterNames] = uuid→名 快照（解析帖主名 + 已有评论作者名）。vision 暂关（见类注释）。
     * [mentioned] = 该角色是被帖主特意提醒来看的（甲 §3.4：提示词多一句「特意提醒了你」；默认 false = 输出逐字节同前）。
     */
    suspend fun generate(
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
        mentioned: Boolean = false,
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
        val worldInfoSection = worldBookAmbient?.buildAmbientSection(
            character = character,
            userName = userNickname,
            userBio = userProfileDao.get()?.bio.orEmpty(),
            contextText = listOfNotNull(post.content, replyTarget?.content).joinToString("\n"),
            nowMillis = nowMillis,
        )
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
            visionEnabled = canSeePhotos,
            existingComments = existingComments,
            replyTarget = replyTarget,
            mentionedByPoster = mentioned,
            worldInfoSection = worldInfoSection,
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

    /** 用户昵称（空则兜底「用户」，复用提示词层 `pb_user_fallback`）。 */
    suspend fun userNickname(): String =
        userProfileDao.get()?.nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.pb_user_fallback)

    companion object {
        /** 评论生成温度（iOS `+Content.swift` generateCommentContent temperature 0.9）。 */
        private const val COMMENT_TEMPERATURE = 0.9

        /** 日程事件类型「聊天写回/线下记录」，评论日程上下文跳过它（=iOS userInteraction 过滤）。 */
        private const val EVENT_TYPE_USER_INTERACTION = "userInteraction"
    }
}
