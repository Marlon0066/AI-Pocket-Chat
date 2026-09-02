package com.situ.aichat.prompt.schedule

import android.util.Log
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.EconomicStatusTier
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 日程生成的输入上下文（协调器组装后传入）。对齐 iOS `buildPrompt` 的入参集合。 */
data class ScheduleGenerationRequest(
    val character: CharacterEntity,
    val dateMillis: Long,                 // 目标日「当天 0 点」epoch 毫秒
    val zone: ZoneId,                     // 角色时区（当前 = 设备时区，反查时区延后）
    val yesterdayEvents: List<ScheduleEventEntity>,
    val recentConversationSummary: String?,
    val otherCharacterSchedules: List<OtherSchedule>,
    val crossCharacterLevel: Int,
    val isBackfill: Boolean = false,
    // 世界上下文（W9d §3.5/§4.4·joinedWorld 才非默认·只加输入·响应格式/解析零碰）。前三进 prompt，后二只进入库。
    val worldCityName: String? = null,          // 覆盖「所在城市」行 + 入库 cityName
    val worldPlaceNames: List<String> = emptyList(), // 本城真实地点段（剔除你的家·程序城空）
    val worldWeatherLine: String? = null,       // 天气行「今天{城名}的天气：{天气词}」
    val worldWeatherCondition: String? = null,  // 入库 weatherCondition（天气词）
    val worldWeatherEmoji: String? = null,      // 入库 weatherEmoji
    // 活人感加料（图纸 2026-07-10 日程专项 C6·只加输入·默认值 = 旧构造点零改）。
    val economicTier: EconomicStatusTier? = null,        // 经济档（今日与 backfill 都注入·拍板⑤）
    val liveness: ScheduleLivenessContext? = null,       // 素材包（backfill 恒 null = 精简）
    /** 图纸二·人称指名：真实用户名（协调器解析·空回退「用户」）；默认「用户」= 旧构造点/测试零改。 */
    val userName: String = "用户",
) {
    data class OtherSchedule(val name: String, val events: List<ScheduleEventEntity>)
}

/** 日程生成错误（对齐 iOS `ScheduleGenerationError` 子集；天气/缺角色等在协调器侧处理）。 */
sealed class ScheduleGenerationException(message: String) : Exception(message) {
    data object EmptyResponse : ScheduleGenerationException("LLM 返回空内容（重试后仍为空）")
    data object InvalidJsonResponse : ScheduleGenerationException("日程生成结果不是有效的 JSON")
    data object InsufficientEvents : ScheduleGenerationException("日程事件数量不足，生成结果不可用")
}

/**
 * 1:1 port of iOS `ScheduleGenerationService`（+Prompt/+Parsing/+Helpers）核心。无状态：组提示词、
 * 调 LLM（经 [ContextLogService.completion] 记录，temp 0.9 + json_object）、解析校验、入库。纯函数（系统 prompt 合成 /
 * 解析 / 校验 / 时间换算）放在 [Companion] 便于单测，提示词硬编码中文逐字对齐 iOS、不进 values。
 *
 * **本次不做（按 iOS 分层延后）**：今日天气块 + 天气调整(P11)、经济状态块(P9)、按经纬度反查时区
 * （先用设备时区）、空壳日程的 userInteraction 保留(线下 P10)。
 */
@Singleton
class ScheduleGenerationService @Inject constructor(
    private val contextLog: ContextLogService,
    private val scheduleDao: ScheduleDao,
) {

    /**
     * 为 [ScheduleGenerationRequest.character] 在目标日生成并入库日程。
     * 幂等：今日已正式生成（generatedAt != null）则跳过返回 false；成功生成返回 true。
     */
    suspend fun generateSchedule(request: ScheduleGenerationRequest, config: ApiConfigValues): Boolean {
        val charUuid = request.character.uuid
        // 花钱调 LLM 前先检查，避免并发入口重复生成同一天。
        val existing = scheduleDao.scheduleFor(charUuid, request.dateMillis)
        if (existing?.generatedAt != null) {
            Log.d(TAG, "今日日程已存在，跳过: ${request.character.name}")
            return false
        }

        val (system, user) = buildPrompt(request)
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )

        // 完整缓冲后解析；DeepSeek JSON Output 偶发空响应 → 剥 <think> 后若空等 200ms 重试 1 次（对齐成长分析）。
        // 走流式收全量通道（图纸 2026-07-10 日程专项 C1）：空闲计时无总死限，思考模型任想多久；缓冲语义不变。
        var response = ""
        for (attempt in 1..2) {
            val buffer = contextLog.streamedCompletion(
                source = LogSource.SCHEDULE_GENERATION,
                characterName = request.character.name,
                config = config,
                messages = messages,
                temperature = 0.9,
                responseFormat = ResponseFormatDto(type = "json_object"),
            )
            val candidate = MemoryService.strippingThinkingTags(buffer)
            if (candidate.isNotEmpty()) {
                response = candidate
                break
            }
            if (attempt < 2) delay(200)
        }
        if (response.isEmpty()) throw ScheduleGenerationException.EmptyResponse

        val eventData = parseScheduleJSON(response)

        // LLM 调用期间可能有其他路径写入，再次检查。
        val existing2 = scheduleDao.scheduleFor(charUuid, request.dateMillis)
        if (existing2?.generatedAt != null) {
            Log.d(TAG, "日程在生成期间已由其他路径写入，跳过: ${request.character.name}")
            return false
        }

        // 复用空壳行 uuid（线下见面预建，P10）避免同（角色,日期）双行；空壳旧事件先清。
        val scheduleUuid = existing2?.uuid ?: UUID.randomUUID().toString()
        if (existing2 != null) scheduleDao.deleteEventsForSchedule(scheduleUuid)

        val now = System.currentTimeMillis()
        val schedule = CharacterDailyScheduleEntity(
            uuid = scheduleUuid,
            characterUuid = charUuid,
            date = request.dateMillis,
            // joinedWorld 时写世界值，否则维持现值（未加入 = worldXxx 皆 null = 与旧行为一致）。
            cityName = request.worldCityName ?: request.character.cityName,
            weatherCondition = request.worldWeatherCondition,
            weatherEmoji = request.worldWeatherEmoji,
            timezoneIdentifier = request.zone.id,
            generatedAt = now,
            isBackfilled = request.isBackfill,
        )
        val events = eventData.mapIndexed { index, item ->
            ScheduleEventEntity(
                uuid = UUID.randomUUID().toString(),
                scheduleUuid = scheduleUuid,
                startTime = makeDate(request.dateMillis, item.startHour, item.startMinute, request.zone),
                endTime = makeDate(request.dateMillis, item.endHour, item.endMinute, request.zone),
                periodLabel = item.periodLabel.trim(),
                location = item.location.trim(),
                activity = item.activity.trim(),
                moodEmoji = item.moodEmoji.trim(),
                moodText = item.moodText.trimmedOrNull(),
                innerThought = item.innerThought.trimmedOrNull(),
                isPhoneAvailable = item.isPhoneAvailable,
                eventTypeRaw = if (request.isBackfill) "actual" else "planned",
                relatedCharacterNames = item.relatedCharacterName.trimmedOrNull(),
                sourceRaw = "generated",
                sortOrder = index,
            )
        }
        scheduleDao.insertScheduleWithEvents(schedule, events)
        Log.d(TAG, "日程已生成入库: ${request.character.name} 事件数=${events.size} backfill=${request.isBackfill}")
        return true
    }

    // MARK: - User prompt 合成（逐段对齐 iOS buildPrompt）

    internal fun buildPrompt(request: ScheduleGenerationRequest): Pair<String, String> {
        val system = composeSystemPrompt(
            overrides = emptyMap(),                 // 场景覆盖（SchedulePromptSettings UI）→ P12
            legacyRolePart = "",                    // AppSettings.scheduleGenerationSystemPrompt → P12
            defaultRolePart = DEFAULT_GENERATION_SYSTEM_PROMPT,
        )
        val character = request.character
        val date = Instant.ofEpochMilli(request.dateMillis).atZone(request.zone).toLocalDate()
        val weekday = weekdayText(date.dayOfWeek)
        // 节假日表优先（图纸 C3·§4-A）：法定假/调休补班覆盖周末判定；表外日期 null = 现行周末逻辑字节级不变。
        val holidayInfo = ChineseHolidays.dayInfoFor(date)
        val dayTypeHint = when (holidayInfo) {
            is ChineseHolidays.DayInfo.Holiday -> "（法定节假日·${holidayInfo.name}假期）"
            ChineseHolidays.DayInfo.MakeupWorkday -> "（调休补班·按工作日安排）"
            null -> if (isWeekendDay(date.dayOfWeek)) "（休息日）" else "（工作日）"
        }

        val sections = mutableListOf<String>()
        sections.add("请为以下角色生成${date.monthValue}月${date.dayOfMonth}日（$weekday）${dayTypeHint}的日程安排。")
        if (holidayInfo is ChineseHolidays.DayInfo.Holiday) {
            sections.add(
                "今天是${holidayInfo.name}假期。日程可以自然带上节日气息（按角色性格与习俗来，" +
                    "比如春节团聚、中秋吃月饼），不强制、不夸张，角色也可以有自己的过节方式。",
            )
        }
        sections.add("")
        sections.add("【角色信息】")
        sections.add("名字：${character.name}")
        sections.add("性格：${fallbackText(character.personalityDescription, "未设定")}")
        sections.add("职业：${fallbackText(character.occupation, "无固定职业")}")
        sections.add("兴趣爱好：${fallbackText(character.initialInterests, "未设定")}")
        // 动态兴趣（图纸 C4·§4-B·含 backfill）：成长系统演化出的热衷，日程随之更新。
        ScheduleLivenessPromptSections.interestsLine(character)?.let { sections.add(it) }
        // 所在城市：joinedWorld 时用世界城名覆盖（§3.5），否则维持现行 character.cityName 回退（未加入=字节级不变）。
        sections.add("所在城市：${request.worldCityName ?: fallbackText(character.cityName, "未知城市")}")
        val moodText = listOf(character.lastMoodEmoji, character.lastMoodText)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        sections.add("当前心情：${moodText.ifEmpty { "未设定" }}")
        // 心情走向（图纸 C4·§4-C·!backfill）：近 5 条情绪历史的趋势，给这一天定基调。
        if (!request.isBackfill) {
            ScheduleLivenessPromptSections.moodTrendLine(character)?.let { sections.add(it) }
        }
        if (character.backstory.trim().isNotEmpty()) {
            sections.add("背景补充：${character.backstory.trim()}")
        }

        // 关系块（图纸 C4·§4-D·!backfill）：相识天数+关系档位+独白频率校准。
        if (!request.isBackfill) {
            val relationship = ScheduleLivenessPromptSections.relationshipSection(character, date, request.zone, request.userName)
            if (relationship.isNotEmpty()) {
                sections.add("")
                sections.addAll(relationship)
            }
        }

        // 世界地点段 + 天气行（W9d §4.4·【角色信息】块之后追加·只加输入·响应格式/解析零碰）。
        if (request.worldPlaceNames.isNotEmpty()) {
            sections.add("")
            sections.add("【这座城里真实存在的地方】")
            sections.add(request.worldPlaceNames.joinToString("、"))
            sections.add("安排外出活动时，location 优先从上面这些地方里选；在家的活动照常写「家里」；都不合适就写「在城中」。")
        }
        request.worldWeatherLine?.let { line ->
            if (request.worldPlaceNames.isEmpty()) sections.add("") // 程序城无地点段 → 天气行自起空行分隔
            sections.add(line)
        }

        // 长期记忆块（图纸 C4·§4-E·!backfill）：印象笔记的【长期事实】节，角色事实进日程、用户事实只进独白。
        if (!request.isBackfill) {
            val longTerm = ScheduleLivenessPromptSections.longTermMemorySection(character, request.userName)
            if (longTerm.isNotEmpty()) {
                sections.add("")
                sections.addAll(longTerm)
            }
        }

        if (request.yesterdayEvents.isNotEmpty()) {
            val sorted = request.yesterdayEvents.sortedWith(SCHEDULE_EVENT_ORDER)
            sections.add("")
            sections.add("【昨天做了什么】")
            for (event in sorted.take(8)) {
                sections.add("${event.periodLabel} ${event.activity}")
            }
            sorted.lastOrNull()?.let { last ->
                sections.add("")
                sections.add("【昨天最后的活动】")
                sections.add("${last.periodLabel} ${last.activity}（${if (last.isPhoneAvailable) "醒着" else "可能已入睡"}）")
                sections.add("⚠️ 今天的第一个事件必须自然衔接昨天最后的状态。如果昨天深夜还在活动，今天应该从凌晨继续（比如继续玩→洗漱→入睡→睡眠）；如果昨天已入睡，今天应该从睡眠开始。不要出现突兀的跳跃。")
            }
        }

        // 多日摘要（图纸 C6·§4-G）：反撞车 + 跨日小事件线（backfill 无素材包 = 恒缺席）。
        request.liveness?.let { liveness ->
            val recentDays = ScheduleLivenessPromptSections.recentDaysSection(liveness.recentDaysDigest)
            if (recentDays.isNotEmpty()) {
                sections.add("")
                sections.addAll(recentDays)
            }
        }

        request.recentConversationSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { trimmed ->
            sections.add("")
            sections.add("【最近和${request.userName}聊到的事】")
            sections.add(trimmed)
            // 禁令收窄（图纸 C6·§4-F·拍板④）：排约定单源 = 账本（【今天的约定】），聊天降级为背景素材。
            sections.add("⚠️ 这段聊天只用来了解TA最近的生活状态和心情，可作为 innerThought 的素材。不要从聊天里自行提取约定排进日程——今天要赴的约定一律以【今天的约定】为准。禁止在 activity 里写「和${request.userName}发消息/聊天/分享」之类的互动动作，禁止虚构任何对话引用。")
        }

        // 约定硬锚点 + 惦记 + 余温（图纸 C6·§4-H/H2/H3）：liveness 为 null（backfill）恒缺席。
        request.liveness?.let { liveness ->
            for (block in listOf(
                ScheduleLivenessPromptSections.todayPromisesSection(liveness, request.userName),
                ScheduleLivenessPromptSections.upcomingPromisesSection(liveness),
                ScheduleLivenessPromptSections.openLoopsSection(liveness.openLoops, request.userName),
                ScheduleLivenessPromptSections.afterglowSection(liveness.recentMeetingAfterglow),
            )) {
                if (block.isNotEmpty()) {
                    sections.add("")
                    sections.addAll(block)
                }
            }
        }

        // 意图块（活人感内核卷四 §4.5 ③·!backfill）：TA 心里挂着的事只进 innerThought，不变成日程事件；无 live 意图零行。
        if (!request.isBackfill) {
            val intentBlock = ScheduleLivenessPromptSections.intentSection(character, request.userName, System.currentTimeMillis())
            if (intentBlock.isNotEmpty()) {
                sections.add("")
                sections.addAll(intentBlock)
            }
        }

        // 经济块（图纸 C6·§4-H4·拍板⑤含 backfill）：iOS 9.1b-5 既有引导文案接线，只给档位不给数字。
        val economic = ScheduleLivenessPromptSections.economicSection(request.economicTier)
        if (economic.isNotEmpty()) {
            sections.add("")
            sections.addAll(economic)
        }

        if (request.crossCharacterLevel > 0 && request.otherCharacterSchedules.isNotEmpty()) {
            sections.add("")
            sections.add("【其他角色的今日安排】")
            for (item in request.otherCharacterSchedules) {
                val summary = item.events
                    .sortedWith(SCHEDULE_EVENT_ORDER)
                    .take(3)
                    .joinToString("、") { "${it.periodLabel}${it.activity}" }
                sections.add("${item.name}：${summary.ifEmpty { "暂无安排" }}")
            }
            sections.add("互动频率级别：${request.crossCharacterLevel}（1=偶尔约20%概率, 2=经常约50%, 3=频繁约80%）")
            sections.add("如果你觉得合适并且概率允许，可以安排和某个角色在某个时段一起做某件事。不要强行安排。安排了的话在 relatedCharacterName 中填入对方的名字。")
        }

        if (request.isBackfill) {
            sections.add("")
            sections.add("【注意】")
            sections.add("这是对过去一天的回顾性日程，角色已经度过了这一天。请生成简洁的日程，每个事件的 innerThought 可以省略。")
        }

        sections.add("")
        sections.add("【生成要求】")
        sections.add(
            "1. 睡眠锚定（最重要）：先根据角色的职业和性格确定今天的「入睡时间」和「起床时间」，" +
                "然后以这两个时间为锚点安排清醒时段的活动。" +
                "夜猫子型凌晨 2-3 点睡中午起，上班族 23 点睡 7 点起，夜班工作者白天睡晚上上班。" +
                "如果角色的睡觉时间跨越午夜，今天的日程必须从 00:00 的睡眠开始（衔接昨天的入睡），" +
                "到起床时间结束。例如：00:00-08:00 睡觉 → 08:00 起床",
        )
        sections.add(
            "2. 完整 24 小时覆盖：生成 8-14 个时段，事件之间不能有超过 30 分钟的空白。" +
                "日程必须包含以下全部时段（每段至少一个事件）：\n" +
                "   - 凌晨/睡眠（00:00-06:00）\n" +
                "   - 早晨（06:00-09:00）\n" +
                "   - 上午（09:00-12:00）\n" +
                "   - 下午（12:00-18:00）\n" +
                "   - 晚上（18:00-22:00）\n" +
                "   - 深夜（22:00-24:00）\n" +
                "第一个事件必须从 00:00 开始，最后一个事件必须到 23:59 结束",
        )
        sections.add(
            "3. 角色驱动：日程必须紧扣角色的职业、性格和兴趣。" +
                "画家会去画室或美术馆，程序员会写代码或逛技术论坛，学生会上课和泡图书馆。" +
                "不要生成与角色身份无关的通用日程",
        )
        sections.add(
            "4. 工作日与休息日：工作日安排应围绕角色的职业展开（上班、上课、工作等），" +
                "休息日则更自由和休闲。" +
                "自由职业者和无固定职业的角色，工作日和休息日的区别可以更模糊",
        )
        sections.add("5. 时间自然：不要均匀分割，有的活动长 2-3 小时，有的短 30 分钟到 1 小时，睡觉可以 6-9 小时")
        sections.add("6. 天气影响：天气会影响活动选择，下雨天不安排户外运动，改为符合角色兴趣的室内活动")
        sections.add(
            "7. 手机可用性：睡觉时段 isPhoneAvailable 必须为 false。" +
                "白天最多 1-2 个时段 isPhoneAvailable 为 false" +
                "（如开会、上课、运动中），每段持续 20-40 分钟",
        )
        sections.add("8. periodLabel 使用：清晨、上午、临近中午、午间、下午、傍晚、晚上、夜里、深夜")
        sections.add("9. innerThought：角色的内心独白，10-25 个字，第一人称，要体现角色的说话方式和思维特点")
        sections.add("10. moodEmoji：1 个 emoji 表达当时心情")
        sections.add("11. location：具体地点名（如家里、公司、星巴克、中央公园），要符合角色所在城市和生活圈")
        sections.add(
            "12. ⚠️ activity 内容边界（核心）：activity 字段只能描述角色自己在做什么（工作、吃饭、看剧、散步、思考等）。" +
                "严禁写「和${request.userName}发消息/聊天/打电话/视频通话/分享截图」之类的互动动作，" +
                "严禁虚构任何对话内容或引用。" +
                "如需体现角色对${request.userName}的思念或情感，只能写在 innerThought 里（如「突然想到她昨天说的话」），" +
                "不能作为 activity 事件发生。日程是角色独立生活的流水，不是和${request.userName}互动的记录。" +
                "唯一例外：【今天的约定】块里列出的约定，按该块的要求安排成事件。",
        )
        sections.add(
            "13. innerThought 的分寸：按【和${request.userName}的关系】给出的参考控制想到${request.userName}的频率；" +
                "其余事件的 innerThought 写TA自己的生活感受。",
        )

        sections.add("")
        sections.add("输出格式（严格 JSON 对象 {\"events\":[...]}, 不要有任何其他文字、注释、代码块标记）：")
        sections.add(EXAMPLE_JSON)

        return system to sections.joinToString("\n")
    }

    private fun weekdayText(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }

    private fun isWeekendDay(dayOfWeek: DayOfWeek): Boolean =
        dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

    private fun fallbackText(text: String?, default: String): String {
        val trimmed = text?.trim().orEmpty()
        return trimmed.ifEmpty { default }
    }

    private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "ScheduleGen"

        /**
         * 默认日程生成 system prompt（角色定位层）。~~逐字对齐 iOS `defaultGenerationSystemPrompt`~~
         * 图纸 2026-07-10 日程专项 §4-K 质感化升级（iOS 原文已退役为历史参考·铁律 #2）；
         * override / legacy 优先级结构不动（composeSystemPrompt 零碰）。
         */
        const val DEFAULT_GENERATION_SYSTEM_PROMPT =
            "你是一个生活模拟作家，任务是为一个真实生活着的虚拟角色还原TA普通的一天。TA有自己的职业、朋友、爱好和心事；TA的日子大多平淡真实，偶尔有小波澜。日程必须紧扣TA的职业、性格、兴趣和生活习惯，体现TA的个人特色，而不是千篇一律的模板。"

        // 场景覆盖字段 key（对齐 iOS SchedulePromptField）；override 来源 UI 在 P12。
        private const val FIELD_CHARACTER_ROLE_PART = "characterRolePart"
        private const val FIELD_EXTRA_RULES = "extraRules"

        /** 事件排序：sortOrder 优先，相同则 startTime（对齐 iOS scheduleEventSort）。 */
        private val SCHEDULE_EVENT_ORDER =
            compareBy<ScheduleEventEntity>({ it.sortOrder }, { it.startTime })

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        private val scheduleEventListSerializer = ListSerializer(ScheduleEventData.serializer())

        // MARK: - System prompt 合成（三层优先级 + extraRules + JSON 硬约束）—— 纯函数，单测覆盖

        /**
         * 合成日程生成 system prompt。**优先级**：override 的 characterRolePart 非空 > [legacyRolePart] 非空 >
         * [defaultRolePart]。**追加**：override 的 extraRules 按行拆分（空白行过滤）每行 `- ` 前缀。
         * **硬约束**：JSON 格式要求始终末尾。1:1 对齐 iOS `composeSystemPrompt`。
         */
        internal fun composeSystemPrompt(
            overrides: Map<String, String>,
            legacyRolePart: String,
            defaultRolePart: String,
        ): String {
            val overrideRole = resolveOverride(overrides, FIELD_CHARACTER_ROLE_PART, "")
            val rolePart = when {
                overrideRole.isNotEmpty() -> overrideRole
                legacyRolePart.isNotEmpty() -> legacyRolePart
                else -> defaultRolePart
            }

            val extraRules = resolveOverride(overrides, FIELD_EXTRA_RULES, "")
            val parts = mutableListOf(rolePart)
            for (line in extraRules.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) parts.add("- $trimmed")
            }
            parts.add("输出严格的 JSON 对象格式 {\"events\":[...]}, 不要包含任何其他文字。")
            return parts.joinToString("\n")
        }

        /** override 字典取值：非空白则返回（trim 后），否则回退 [fallback]（对齐 iOS ScenePromptOverrideService.resolve 语义）。 */
        private fun resolveOverride(overrides: Map<String, String>, key: String, fallback: String): String {
            val value = overrides[key]?.trim().orEmpty()
            return value.ifEmpty { fallback }
        }

        // MARK: - JSON 解析 + 校验（纯函数，单测覆盖）

        /**
         * 多候选容错解析：先剥 <think>，再依次试 [对象包 {"events":[...]}] 与 [裸数组 [...]]，
         * 取第一个校验通过且非空的结果。全部失败抛 [ScheduleGenerationException.InvalidJsonResponse]。
         */
        internal fun parseScheduleJSON(response: String): List<ScheduleEventData> {
            val cleaned = MemoryService.strippingThinkingTags(response)
            val candidates = listOfNotNull(
                cleaned.trim().ifEmpty { null },
                JSONExtractor.extract(cleaned),
                extractJsonArray(cleaned),
            ).distinct()

            // validateEvents 的 throw（事件不足）也包进 runCatching，与 iOS 一致——逐候选吞掉后继续试，
            // 全部失败统一抛 InvalidJsonResponse（InsufficientEvents 不会从这里冒出）。
            for (candidate in candidates) {
                // 优先对象包
                runCatching {
                    val wrapper = json.decodeFromString(ScheduleEventWrapper.serializer(), candidate)
                    validateEvents(wrapper.events)
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
                // 回退裸数组
                runCatching {
                    val decoded = json.decodeFromString(scheduleEventListSerializer, candidate)
                    validateEvents(decoded)
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            throw ScheduleGenerationException.InvalidJsonResponse
        }

        /**
         * 字段非空 + 时间合法的事件过滤，按开始分钟升序。少于 3 个直接判失败
         * （对齐 iOS `validateEvents` 宽松底线；质量不足的 debug 日志省略）。
         */
        internal fun validateEvents(events: List<ScheduleEventData>): List<ScheduleEventData> {
            val sanitized = events.filter { item ->
                item.activity.trim().isNotEmpty() &&
                    item.location.trim().isNotEmpty() &&
                    item.periodLabel.trim().isNotEmpty() &&
                    item.startHour in 0..23 &&
                    item.endHour in 0..23 &&
                    item.startMinute in 0..59 &&
                    item.endMinute in 0..59 &&
                    minutes(item.endHour, item.endMinute) >= minutes(item.startHour, item.startMinute)
            }
            val sorted = sanitized.sortedBy { minutes(it.startHour, it.startMinute) }
            if (sorted.size < 3) throw ScheduleGenerationException.InsufficientEvents
            return sorted
        }

        /** 裸数组提取：第一个 `[` 到最后一个 `]`（对齐 iOS extractJSONArray 的兜底意图）。 */
        private fun extractJsonArray(text: String): String? {
            val first = text.indexOf('[')
            val last = text.lastIndexOf(']')
            return if (first >= 0 && last > first) text.substring(first, last + 1) else null
        }

        internal fun minutes(hour: Int, minute: Int): Int = hour * 60 + minute

        /** 角色时区下，目标日 [dateMillis] 的 [hour]:[minute]:00 → epoch 毫秒（对齐 iOS makeDate）。 */
        internal fun makeDate(dateMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long {
            val localDate = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
            return localDate.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        }

        /** 输出示例（严格对齐 iOS exampleJSON）。 */
        private val EXAMPLE_JSON = """
            {"events":[
              {
                "startHour": 0,
                "startMinute": 0,
                "endHour": 7,
                "endMinute": 30,
                "periodLabel": "凌晨",
                "location": "家里卧室",
                "activity": "睡觉",
                "moodEmoji": "😴",
                "moodText": "安眠",
                "innerThought": "昨晚追剧太晚了…",
                "isPhoneAvailable": false,
                "relatedCharacterName": null
              },
              {
                "startHour": 7,
                "startMinute": 30,
                "endHour": 9,
                "endMinute": 0,
                "periodLabel": "清晨",
                "location": "家里",
                "activity": "煮咖啡，听播客",
                "moodEmoji": "☕",
                "moodText": "惬意",
                "innerThought": "今天想早点进入状态",
                "isPhoneAvailable": true,
                "relatedCharacterName": null
              }
            ]}
        """.trimIndent()
    }
}
