package com.situ.aichat.prompt.diary

import android.content.Context
import android.util.Log
import androidx.work.Data
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.DiaryCommentWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 日记 AI 角色评论（M07 7.1.3）。1:1 iOS `DiaryGenerationService.scheduleCharacterComments` +
 * `generateCommentsForEntry` + `generateDiaryCommentContent`。
 *
 * **触发**：仅用户「发布」(非草稿) 且可见性 openToAI 的日记才调 [scheduleComments]（撰写页 7.1.5 接）。
 * **调度**：iOS 用 in-process `Task.detached` + `DelayedTaskRegistry`；安卓改用 WorkManager 唯一一次性任务
 * （name = `diary_comments_{uuid}`，删除日记时 [cancelComments] 取消）。**这比 iOS 更可靠**——iOS 的 Task 在
 * app 被杀后丢失且无恢复，WorkManager 跨进程死亡仍存活；worker 内 refetch 为 null 时优雅早退兜底。
 */
@Singleton
class DiaryCommentService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contextLog: ContextLogService,
    private val apiConfigRepo: ApiConfigRepository,
    private val diaryRepository: DiaryRepository,
    private val characterDao: CharacterDao,
    private val userProfileDao: UserProfileDao,
    private val settingsRepo: SettingsRepository,
    private val backgroundScheduler: BackgroundScheduler,
) {

    /** 发布日记后调度延迟评论。delay = delayMinutes*60 + random(0..60)s（1:1 iOS）。 */
    fun scheduleComments(entryUuid: String, delayMinutes: Int) {
        val delaySeconds = scheduleDelaySeconds(delayMinutes, Random.nextLong(0, 61))
        backgroundScheduler.scheduleOneShot(
            uniqueName = uniqueName(entryUuid),
            workerClass = DiaryCommentWorker::class.java,
            initialDelay = Duration.ofSeconds(delaySeconds),
            requireNetwork = true,
            inputData = Data.Builder().putString(DiaryCommentWorker.KEY_ENTRY_UUID, entryUuid).build(),
        )
    }

    /** 取消某日记的待执行评论任务（删除日记时调，对齐 iOS cancelPendingComments）。 */
    fun cancelComments(entryUuid: String) {
        backgroundScheduler.cancel(uniqueName(entryUuid))
    }

    /**
     * 用户回复某条角色评论后，调度该角色的回应（R3 评论区活化·每根评论限 1 轮）。
     * 短延迟 = [REPLY_BASE_DELAY_SECONDS] + jitter(0..30)s——回复是对话往来，比首评快得多才像真人。
     * 唯一名含 rootCommentId：同根重复调度自然合并；删日记级联删评论后 worker refetch 空优雅早退。
     */
    fun scheduleReply(entryUuid: String, rootCommentId: String) {
        backgroundScheduler.scheduleOneShot(
            uniqueName = "$REPLY_UNIQUE_PREFIX$rootCommentId",
            workerClass = DiaryCommentWorker::class.java,
            initialDelay = Duration.ofSeconds(REPLY_BASE_DELAY_SECONDS + Random.nextLong(0, 31)),
            requireNetwork = true,
            inputData = Data.Builder()
                .putString(DiaryCommentWorker.KEY_ENTRY_UUID, entryUuid)
                .putString(DiaryCommentWorker.KEY_ROOT_COMMENT_ID, rootCommentId)
                .build(),
        )
    }

    /**
     * 生成某根评论下的角色回应（worker 调·R3 + R6-1）。共同守卫链：日记在 + openToAI + API + 互动开关 +
     * 根评论在 + **尚未回应过**（幂等·worker 重试安全）。两条路径按根评论归属分派：
     * - **R3**（角色根）：用户回复了角色的评论 → 该角色回应。上下文 = 日记正文 + 角色原评论 + 用户回复。
     * - **R6-1**（用户根 × 交换日记）：用户在 TA 的信下发顶层留言 → **信的作者本人**回应（每条留言限 1 轮）。
     *   上下文 = TA 的信正文 + 用户留言（作者视角提示词 [DiaryCommentPromptBuilder.buildExchangeReply]）。
     */
    suspend fun generateReplyForComment(entryUuid: String, rootCommentId: String) {
        val entry = diaryRepository.getEntry(entryUuid) ?: return
        if (DiaryVisibility.fromRaw(entry.visibilityRaw) != DiaryVisibility.OPEN_TO_AI) return
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.DIARY_GENERATION) ?: return
        if (!settingsRepo.getAppSettings().diaryCharacterInteractionEnabled) return

        val comments = diaryRepository.commentsForEntry(entryUuid)
        val root = comments.firstOrNull { it.id == rootCommentId } ?: return
        val children = comments.filter { it.parentCommentId == rootCommentId }
        val strings = DiaryCommentPromptStrings.from(PromptStrings(context))
        val userName = userProfileDao.get()?.nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.diary_user_fallback)

        // R6-1 交换日记留言：用户顶层留言 + 这封信有作者 → 作者回应。
        val exchangeAuthorUuid = entry.authorCharacterUuid
        if (root.isFromUser && root.parentCommentId == null && exchangeAuthorUuid != null) {
            // 幂等：作者已回应过这条留言 → 不再生成（1 轮上限的服务端兜底）。
            if (children.any { !it.isFromUser }) return
            val author = characterDao.getByUuid(exchangeAuthorUuid) ?: return
            val system = DiaryCommentPromptBuilder.buildExchangeReply(
                strings = strings,
                characterName = author.name,
                personality = author.personalityDescription,
                systemPrompt = author.systemPrompt,
                userName = userName,
                entryContent = entry.content,
                userComment = root.content,
                photoCount = entry.imagePaths.size,
            )
            val content = completeComment(system, strings.replyUserMessage, author.name, config) ?: return
            diaryRepository.addComment(
                entryUuid = entryUuid,
                content = content,
                characterUuid = exchangeAuthorUuid,
                timestamp = System.currentTimeMillis(),
                parentCommentId = rootCommentId,
            )
            return
        }

        // R3：角色根评论下的一轮回应。
        val characterUuid = root.characterUuid?.takeIf { !root.isFromUser } ?: return
        val userReply = children.lastOrNull { it.isFromUser } ?: return
        // 幂等：该用户回复之后角色已回应过 → 不再生成（1 轮上限的服务端兜底）。
        if (children.any { !it.isFromUser && it.timestamp > userReply.timestamp }) return
        val character = characterDao.getByUuid(characterUuid) ?: return

        val system = DiaryCommentPromptBuilder.buildReply(
            strings = strings,
            characterName = character.name,
            personality = character.personalityDescription,
            systemPrompt = character.systemPrompt,
            userName = userName,
            entryContent = entry.content,
            rootComment = root.content,
            userReply = userReply.content,
            photoCount = entry.imagePaths.size,
        )
        val content = completeComment(system, strings.replyUserMessage, character.name, config) ?: return
        diaryRepository.addComment(
            entryUuid = entryUuid,
            content = content,
            characterUuid = characterUuid,
            timestamp = System.currentTimeMillis(),
            parentCommentId = rootCommentId,
        )
    }

    /**
     * 为某日记生成 1~2 条 AI 角色评论（worker 调）。1:1 iOS `generateCommentsForEntry`：
     * refetch → openToAI 守卫 → API → 开关 → 候选(空=全部) → 随机 1~2 个 → 逐个生成(同角色去重，间隔 10~30s)。
     */
    suspend fun generateCommentsForEntry(entryUuid: String) {
        val entry = diaryRepository.getEntry(entryUuid) ?: return
        if (DiaryVisibility.fromRaw(entry.visibilityRaw) != DiaryVisibility.OPEN_TO_AI) return
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.DIARY_GENERATION) ?: return
        val settings = settingsRepo.getAppSettings()
        if (!settings.diaryCharacterInteractionEnabled) return

        val all = characterDao.getAll()
        if (all.isEmpty()) return
        val candidates = resolveCandidates(all, settings.diaryInteractingCharacterUUIDs)
        if (candidates.isEmpty()) return

        val strings = DiaryCommentPromptStrings.from(PromptStrings(context))
        val userName = userProfileDao.get()?.nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.diary_user_fallback)

        val count = commentCount(Random.nextInt(1, 3), candidates.size) // 1..2 inclusive
        val shuffled = candidates.shuffled()
        val commented = mutableSetOf<String>()
        for (i in 0 until count) {
            val character = shuffled[i]
            // 同角色不重复评同一篇（DB 查，跨 worker 重试也安全）。
            if (diaryRepository.commentCountByCharacter(entryUuid, character.uuid) > 0) {
                commented.add(character.uuid) // 早前已评过（worker 重试）→ 点赞口径仍算评论者
                continue
            }
            val content = generateComment(strings, character, entry.content, userName, config, entry.imagePaths.size) ?: continue
            diaryRepository.addComment(entryUuid, content, character.uuid, System.currentTimeMillis())
            commented.add(character.uuid)
            if (i < count - 1) delay(Random.nextLong(10_000, 30_001)) // 10..30s（1:1 iOS）
        }

        // R3 角色点赞：随评论批同场决定，**零额外 LLM 调用**；评论者必点赞（评了=在乎）、旁观者低概率点缀，
        // 封顶 3；emoji 从固定小集合选。唯一索引 + IGNORE 防重（worker 重试安全）。
        val likers = pickLikers(candidates.map { it.uuid }, commented, Random)
        likers.forEach { uuid ->
            diaryRepository.addReaction(entryUuid, uuid, REACTION_EMOJIS.random())
        }
    }

    /** 单条评论 LLM 生成（temp 0.9）。剥 think 后为空 → null（跳过该条，对齐 iOS catch→continue）。 */
    private suspend fun generateComment(
        strings: DiaryCommentPromptStrings,
        character: CharacterEntity,
        entryContent: String,
        userName: String,
        config: com.situ.aichat.data.remote.llm.ApiConfigValues,
        /** 日记附图张数（§B8）：>0 时给角色一句「有 N 张照片但你看不到」，免得评论与照片脱节。 */
        photoCount: Int,
    ): String? {
        val system = DiaryCommentPromptBuilder.build(
            strings = strings,
            characterName = character.name,
            personality = character.personalityDescription,
            systemPrompt = character.systemPrompt,
            userName = userName,
            entryContent = entryContent,
            photoCount = photoCount,
        )
        return completeComment(system, strings.userMessage, character.name, config)
    }

    /** 评论/回应共用的 LLM 调用（temp 0.9 + 脏数据门）。失败/非正文 → null 不入库。 */
    private suspend fun completeComment(
        system: String,
        userMessage: String,
        characterName: String,
        config: com.situ.aichat.data.remote.llm.ApiConfigValues,
    ): String? {
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = userMessage),
        )
        return try {
            val buffer = contextLog.completion(
                source = LogSource.DIARY_COMMENT,
                characterName = characterName,
                config = config,
                messages = messages,
                temperature = 0.9,
            )
            // 脏数据门（1:1 iOS GeneratedContentValidator）：非正文（Token count/{"error"}/纯数字…）→ null 不入库。
            MemoryService.strippingThinkingTags(buffer).takeIf { GeneratedContentValidator.isLikelyValid(it) }
        } catch (e: Exception) {
            Log.w(TAG, "日记评论生成失败: $characterName - ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DiaryComment"
        private const val UNIQUE_PREFIX = "diary_comments_"
        private const val REPLY_UNIQUE_PREFIX = "diary_reply_"

        /** 角色回应用户回复的基础延迟（秒）——对话往来比首评快得多才像真人（首评 1~15 分钟）。 */
        internal const val REPLY_BASE_DELAY_SECONDS = 15L

        fun uniqueName(entryUuid: String): String = "$UNIQUE_PREFIX$entryUuid"

        /** 调度延迟秒数 = delayMinutes*60 + jitter(0..60)（1:1 iOS scheduleCharacterComments）。 */
        internal fun scheduleDelaySeconds(delayMinutes: Int, jitterSeconds: Long): Long =
            delayMinutes.toLong() * 60 + jitterSeconds

        /** 评论条数 = min(随机 1~2, 候选数)（1:1 iOS）。 */
        internal fun commentCount(randomOneOrTwo: Int, candidateCount: Int): Int =
            minOf(randomOneOrTwo, candidateCount)

        /**
         * 候选角色：[allowedCsv] 逗号分隔的允许 uuid 集合；**空 = 全部角色**（1:1 iOS）。
         * 去空白、过滤空项；非空时按集合 contains 过滤。
         */
        internal fun resolveCandidates(all: List<CharacterEntity>, allowedCsv: String): List<CharacterEntity> {
            val allowed = allowedCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return if (allowed.isEmpty()) all else all.filter { it.uuid in allowed }
        }

        /** 点赞 emoji 固定小集合（R3·无 LLM 决策）。 */
        internal val REACTION_EMOJIS = listOf("❤️", "🥰", "✨")

        /**
         * 挑点赞者（R3·纯函数）：评论者以 [pCommenter]（默认 1.0=必点·评了=在乎）、其余候选以 [pOther]
         * （默认 0.25 点缀）独立掷骰，评论者优先、总数封顶 [cap]。确定性由调用方传入 [random] 控制（可测）。
         */
        internal fun pickLikers(
            candidateUuids: List<String>,
            commenterUuids: Set<String>,
            random: Random,
            cap: Int = 3,
            pCommenter: Double = 1.0,
            pOther: Double = 0.25,
        ): List<String> {
            val ordered = candidateUuids.distinct().sortedByDescending { it in commenterUuids }
            val picked = mutableListOf<String>()
            for (uuid in ordered) {
                if (picked.size >= cap) break
                val p = if (uuid in commenterUuids) pCommenter else pOther
                if (random.nextDouble() < p) picked.add(uuid)
            }
            return picked
        }
    }
}
