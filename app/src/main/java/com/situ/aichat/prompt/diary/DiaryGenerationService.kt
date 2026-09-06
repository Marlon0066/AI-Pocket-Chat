package com.situ.aichat.prompt.diary

import android.content.Context
import android.util.Log
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.pet.PetDiaryPrompts
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.messageLlmSafeText
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日记草稿生成（M07 7.1.2）。**合并 iOS 的 Service(@MainActor) + Actor(@ModelActor) 两份重复实现为一处**：
 * 收集当天聊天/日历素材 + 用户名 → 装配 [DiaryPromptBuilder] → 调 LLM（temp 0.8）→ 返回正文。
 * 「何时生成 / 是否落库」由 [DiaryGenerationCoordinator] 决定（仿 ScheduleCoordinator/ScheduleGenerationService 分层）。
 *
 * 手动「AI 帮我写」与自动生成共用 [generateDraft]。礼物灵感（P9）/ 宠物状态（P8）留参数 / 留空，优雅跳过。
 */
@Singleton
class DiaryGenerationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contextLog: ContextLogService,
    private val apiConfigRepo: ApiConfigRepository,
    private val messageDao: MessageDao,
    private val userProfileDao: UserProfileDao,
    private val calendarReader: CalendarReader,
    private val settingsRepo: SettingsRepository,
    private val petRepository: PetRepository,
    private val offlineMeetingMemoryRepository: com.situ.aichat.data.repository.OfflineMeetingMemoryRepository,
    private val characterRepo: com.situ.aichat.data.repository.CharacterRepository,
) {

    /**
     * 生成一篇日记（不落库）。无可用 API → 置 [DiaryApiMissingFlag] 并返回 null；生成空/失败 → null。
     * R2 心情闭环：返回 [DiaryDraft]（剥离 `MOOD:` 尾行的正文 + 推断心情·白名单外优雅 null）。
     * @param dateMillis 目标日（任意时刻即可，内部按当天 0 点取窗口）；补昨天时传昨天 0 点。
     * @param moodHint 用户手选心情（"😊 开心" 式·撰写页路径注入），空 = 不加心情段。
     */
    suspend fun generateDraft(
        dateMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        giftInspiration: String? = null,
        moodHint: String? = null,
        guide: DiaryGuideAnswers? = null,
        /** 撰写页里已贴好的照片张数（§B8·「AI 帮我写」用；自动日记路恒 0）。 */
        photoCount: Int = 0,
    ): DiaryDraft? {
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.DIARY_GENERATION)
        if (config == null) {
            Log.d(TAG, "日记生成跳过：未配置 API 或 Key 为空")
            DiaryApiMissingFlag.set(context, true)
            return null
        }
        DiaryApiMissingFlag.set(context, false)

        val strings = DiaryPromptStrings.from(PromptStrings(context))
        val settings = settingsRepo.getAppSettings()
        val profile = userProfileDao.get()
        val userName = profile?.nickname?.trim()?.takeIf { it.isNotEmpty() } ?: strings.userFallback
        // 「作为我」写日记：注入 bio(+城市) 人设，让 AI 以我的性格/口吻写（都空 → 不注入人设段）。
        val persona = composePersona(profile, strings)

        val startOfDay = DateFormatters.startOfDayMillis(dateMillis, zone)
        val endOfDay = startOfDay + DAY_MILLIS

        val chatSummary = buildChatSummary(startOfDay, endOfDay, zone, strings)
        val calendarSummary = buildCalendarSummary(startOfDay, endOfDay, zone, settings.calendarIntegrationEnabled, strings)
        // 涟漪②·§3.9：当天有线下见面 → 注入见面素材（跨角色·让日记自然写到它）。
        val meetingSummary = buildOfflineMeetingSummary(startOfDay, endOfDay, zone)
        // 宠物状态注入（P8.2，1:1 iOS DiaryGenerationService.buildPetContextForDiary）；宠物系统关 → 空。
        val petSummary = if (settings.petSystemEnabled) PetDiaryPrompts.buildPetContextForDiary(petRepository.getAll()) else ""

        // 场景覆盖框架（DiaryPromptField）：2026-09-05 起接设置页「日记写作规则」（篇幅/人称/文风/补充规则）；
        // 用户未自定义时三个文本项不进 map、字数恒 1000 ⇒ 与接线前逐字节相同（图纸 §3.4/B1）。
        val systemPrompt = DiaryPromptBuilder.buildSystemPrompt(
            strings = strings,
            userName = userName,
            nowMillis = dateMillis,
            zone = zone,
            chatSummary = chatSummary,
            calendarSummary = calendarSummary,
            persona = persona,
            meetingSummary = meetingSummary,
            petSummary = petSummary,
            giftInspiration = giftInspiration,
            moodHint = moodHint.orEmpty(),
            guide = guide,
            photoCount = photoCount,
            overrides = DiaryRuleOverrides.forUserDiary(settings, userName),
        )

        val messages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = strings.userMessage),
        )

        // 完整缓冲解析；剥 <think> 后若空等 200ms 重试 1 次（对齐成长/日程对 DeepSeek 空响应的处理）。
        var result = ""
        for (attempt in 1..2) {
            // 用户日记非单一角色名下，characterName 留空（= iOS "System"；日志 UI 据空回退显示来源）。
            val buffer = contextLog.completion(
                source = LogSource.DIARY_GENERATION,
                characterName = "",
                config = config,
                messages = messages,
                temperature = 0.85,   // R6-2 ④：用户日记 0.8→0.85，文字更松弛发散
            )
            val candidate = MemoryService.strippingThinkingTags(buffer)
            if (candidate.isNotEmpty()) {
                result = candidate
                break
            }
            if (attempt < 2) delay(200)
        }
        // R2：先剥 `MOOD:` 元数据尾行（心情白名单外优雅 null），再过脏数据门——门检查的是**剥行后的正文**
        // （全文只剩 MOOD 行 → 正文空 → 不入库，与生成失败同路）。
        val draft = DiaryMoodLineParser.extract(result)
        // 脏数据门（1:1 iOS guard GeneratedContentValidator.isLikelyValid else throw）：剥 think 后仍是
        // "Token count:"/{"error"}/纯数字等非正文 → 视为生成失败、不入库（返回 null = iOS 抛错不持久化）。
        return draft.takeIf { GeneratedContentValidator.isLikelyValid(it.content) }
    }

    // MARK: - 素材收集

    /**
     * 当天线下见面素材（涟漪②·§3.9）：跨角色查当日见面行，每次一行「HH:mm 与{角色名}在{地点}{活动}，{摘要首句}」，
     * 无见面 → ""。角色名从 row.characterUuid 解析（用户拍板「与{角色名}」·非 {userName}）；摘要取至首个「。」含。
     */
    private suspend fun buildOfflineMeetingSummary(start: Long, end: Long, zone: ZoneId): String {
        val rows = offlineMeetingMemoryRepository.meetingsOnDay(start, end)
        if (rows.isEmpty()) return ""
        val nameByUuid = rows.map { it.characterUuid }.distinct().associateWith { uuid ->
            characterRepo.get(uuid)?.name?.takeIf { it.isNotEmpty() } ?: "对方"
        }
        return formatDiaryMeetingLines(rows, nameByUuid, zone)
    }

    /**
     * 当天 `[start, end)` 的聊天 → **按角色分组**的日记素材文本（2026-07-13「多角色区分」优化）：
     * 一个用户可能同天和多个角色聊，压成一条「我/对方」流水会让 LLM 串戏 / 张冠李戴。故先列出当天聊过的
     * 角色（组序=各角色首条消息时间序），再**每角色各自取**最早 ≤[CHAT_TAKE] 条（根治晚场角色被全局上限挤掉；
     * 角色多时经 [perCharacterTake] 均摊缩水、地板保底**绝不整组丢角色**）→ 每组「### 今天和 {角色名} 聊了」，
     * 其中**用户消息标「我」、角色消息标该角色真名**（作者=用户始终是「我」·防第三人称漂移）。
     * 脱敏 / 截断沿用 [summarizeChatMessages] 单源（钱路口径不变）。全空 → ""（section 省略）。
     */
    private suspend fun buildChatSummary(start: Long, end: Long, zone: ZoneId, strings: DiaryPromptStrings): String {
        val charUuids = messageDao.characterUuidsWithMessagesInRange(start, end)
        if (charUuids.isEmpty()) return ""
        // 总量保险丝（2026-07-13 复核 🔵-3 用户拍板）：份额均摊；名字缺省回落「对方」。
        val take = perCharacterTake(charUuids.size)
        val groups = charUuids.map { uuid ->
            val name = characterRepo.get(uuid)?.name?.takeIf { it.isNotEmpty() } ?: strings.roleOther
            name to messageDao.messagesForCharacterInRange(uuid, start, end, take)
        }
        return formatChatGroups(groups, zone, strings)
    }

    /** 「关于我」段正文（bio + 城市行·都空 → ""·builder 据此省略整段）。 */
    private fun composePersona(profile: UserProfileEntity?, strings: DiaryPromptStrings): String {
        if (profile == null) return ""
        val parts = mutableListOf<String>()
        profile.bio.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        profile.cityName?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(strings.personaCity.format(it)) }
        return parts.joinToString("\n")
    }

    /**
     * 当天 `[start, end)` 设备日历事件 → `M月d日 HH:mm-HH:mm 标题`。无权限 / 日历感知关 → ""（section 省略）。
     * iOS 后台路径只取事件、不取提醒（提醒=安卓平台缺口），此处一致。
     */
    private suspend fun buildCalendarSummary(
        start: Long,
        end: Long,
        zone: ZoneId,
        calendarEnabled: Boolean,
        strings: DiaryPromptStrings,
    ): String {
        if (!calendarEnabled || !calendarReader.hasPermission()) return ""
        val events = calendarReader.eventsInRange(start, end)
        if (events.isEmpty()) return ""
        return events.joinToString("\n") { e ->
            val startStr = DateFormatters.chineseMonthDayHourMinute(e.begin, zone)
            val endZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.end), zone)
            val endStr = "%02d:%02d".format(endZdt.hour, endZdt.minute)
            val title = e.title.ifBlank { strings.eventUntitled }
            strings.calendarLine.format(startStr, endStr, title)
        }
    }

    internal companion object {
        const val TAG = "DiaryGen"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val CHAT_TAKE = 150               // 每角色（交换日记=该笔友）各自取最早 150 条（2026-07-13 拍板·配 1000 字·每角色独立取不被挤占）
        // 用户日记聊天素材总量保险丝（宽松·2026-07-13 复核 🔵-3 用户拍板）：≤4 角色不生效（4×150=600 恰满）。
        const val CHAT_TOTAL_BUDGET = 600
        // 保险丝地板：角色再多每角色仍保留的份额——只均摊缩水、绝不整组挤掉（与 d7a19dff 根治精神一致）。
        const val CHAT_TAKE_FLOOR = 20
        const val CONTENT_PREVIEW = 200         // 每条消息脱敏内容截断字数（100→200·配长日记素材）
        const val ROLE_USER = "user"

        /** 每角色取条数（纯函数·T1）：`(CHAT_TOTAL_BUDGET / 角色数).coerceIn(CHAT_TAKE_FLOOR, CHAT_TAKE)`。 */
        internal fun perCharacterTake(characterCount: Int): Int =
            (CHAT_TOTAL_BUDGET / characterCount.coerceAtLeast(1)).coerceIn(CHAT_TAKE_FLOOR, CHAT_TAKE)

        /**
         * 纯函数：有序的 (角色名, 该角色当天最早 N 条消息) 分组 → 按角色分段的日记素材（多角色区分·2026-07-13）。
         * 一个用户可能同天和多个角色聊，压成一条「我/对方」流水会让 LLM 串戏/张冠李戴，故按角色切开。
         * 组序由调用方保证（各角色当天首条消息时间序·见 [MessageDao.characterUuidsWithMessagesInRange]）；每组
         * 「### 今天和 {名字} 聊了」+ 收口 [summarizeChatMessages]（用户=「我」/ 角色=名字）。脱敏后空组丢弃；全空 → ""。
         */
        internal fun formatChatGroups(
            groups: List<Pair<String, List<MessageEntity>>>,
            zone: ZoneId,
            strings: DiaryPromptStrings,
        ): String = groups.mapNotNull { (name, msgs) ->
            val body = summarizeChatMessages(msgs, zone, strings, userLabel = strings.roleMe, characterLabel = name)
            if (body.isEmpty()) null else "${strings.chatGroupHeader.format(name)}\n$body"
        }.joinToString("\n\n")

        /**
         * 纯函数：一段（**已按角色/会话过滤好的**）消息列表 → 喂日记 LLM 的「聊天素材」文本（抽出便于独立单测）。
         * 过滤空/脏消息 + 线下事件卡 → 取前 [take] 条 → `[M月d日 HH:mm] {标签}：脱敏内容前 [CONTENT_PREVIEW] 字`。
         *
         * 标签由调用方按**日记作者视角**显式传入（不再靠 role 硬绑「我/对方」）：
         * - 用户日记按角色分组时：[userLabel]=「我」、[characterLabel]=该角色真名（多角色靠名字区分）；
         * - 交换日记 TA 执笔时：[userLabel]=用户名、[characterLabel]=「我」（作者=角色始终是「我」·防第三人称漂移）。
         * 作者本人恒标「我」，另一方标真名——避免「指令说第一人称、素材里却叫名字」的 POV 打架（2026-07-13 拍板）。
         *
         * **结构化卡片绝不喂原文 JSON**（money-path / 隐私契约）：由 [messageLlmSafeText] 单源收口——礼物/红包卡脱敏成系统记录、
         * 通话等无脱敏表示的卡丢弃；**红包领取/拒收/过期系统事件按状态渲染带金额**（已拆可暴露·用户拍板「已拆红包日记带金额」）。
         * 杜绝 amount/cost/原始 JSON 漏进 LogSource.DIARY_GENERATION 提示词。
         */
        internal fun summarizeChatMessages(
            raw: List<MessageEntity>,
            zone: ZoneId,
            strings: DiaryPromptStrings,
            userLabel: String = strings.roleMe,
            characterLabel: String = strings.roleOther,
            take: Int = CHAT_TAKE,
        ): String {
            val lines = raw.asSequence()
                // 不再按 role 滤掉 system：红包领取/拒收/过期系统事件须进日记（[messageLlmSafeText] 渲染带金额=状态驱动·已拆可暴露）。
                // 系统行只有红包系统事件一种（仅 RedPacketService 产出），非红包系统事件由 [messageLlmSafeText] 兜底返 null。
                .filter { it.content.isNotEmpty() }
                .mapNotNull { m ->
                    val kind = MessageKind.fromRaw(m.messageKindRaw)
                    // 线下事件卡 + 脏消息（LLM 鹦鹉学舌的结构化格式入库为 plainText）过滤（对齐 iOS）。
                    if (kind.isOfflineEventCard || DirtyMessageDetector.isDirty(m.content, kind)) return@mapNotNull null
                    // 结构化卡脱敏收口至单源 [messageLlmSafeText]（礼物/红包卡→脱敏无金额·已拆红包事件→带金额·通话等→丢弃），与日程/记忆/通知/故事同口径。
                    val safe = messageLlmSafeText(m) ?: return@mapNotNull null
                    m to safe
                }
                .take(take)
                .toList()
            if (lines.isEmpty()) return ""
            return lines.joinToString("\n") { (m, safe) ->
                val time = DateFormatters.chineseMonthDayHourMinute(m.timestamp, zone)
                val role = if (m.roleRaw == ROLE_USER) userLabel else characterLabel
                strings.chatLine.format(time, role, safe.take(CONTENT_PREVIEW))
            }
        }
    }
}

/**
 * 当日见面行 → 日记素材文本（涟漪②·§3.9·纯函数便于 T2-6）：每行「HH:mm 与{角色名}在{地点}{活动}，{摘要首句}」；
 * 空表 → ""（joinToString 空表即 ""）。角色名由 [nameByUuid] 提供（调用方预解析·缺省「对方」）；摘要取至首个「。」含。
 */
internal fun formatDiaryMeetingLines(
    rows: List<com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity>,
    nameByUuid: Map<String, String>,
    zone: ZoneId,
): String = rows.joinToString("\n") { row ->
    val time = DateFormatters.hourMinute(row.startedAtMillis, zone)
    val name = nameByUuid[row.characterUuid] ?: "对方"
    val firstSentence = if (row.summary.contains("。")) row.summary.substringBefore("。") + "。" else row.summary
    "「$time 与${name}在${row.location}${row.activity}，$firstSentence」"
}
