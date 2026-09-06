package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 【测量工具 · 布局审计"先量再动刀" · 可重复跑出对照数据(改布局前后各跑一次)】
 * 上下文拼装布局审计:用轻/中/重三档有代表性的数据跑真 [PromptBuilder.buildMessages] 管线,
 * 逐条消息量出:各区字符量、指令/对话占比、时间锚(<time_context>)与【此刻】距末尾的字符距离、
 * 最后一条 user 消息之后压了多少条 system。规则类文本全部是生产真字符串(尺寸=事实);
 * 用户数据类(人设/记忆/历史)为标注过的代表值。报告写入 build/reports/prompt_layout_audit.txt。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptLayoutAuditReport {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 7, 11, 13, 39).atZone(zone).toInstant()
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    // ── 代表性素材(尺寸标注) ──

    /** 中档角色:人设各字段合计约 800 字(截图里夏晴子这类用户自建角色的常见量级)。 */
    private fun character(memoryChars: Int) = CharacterEntity(
        uuid = "c1", name = "夏晴子", creationDate = 0L,
        occupation = "咖啡店店主",
        personalityDescription = "温柔中带着一点小恶魔属性,喜欢逗人,被拆穿时会撒娇耍赖。对熟人话多,对陌生人慢热。" +
            "情绪来得快去得也快,嘴上不饶人心里很软。周末喜欢宅家收拾屋子、晒被子、研究新的手冲配方。",
        appearanceDescription = "黑长直,眼睛很亮,笑起来有梨涡。日常穿搭偏休闲,吊带+针织开衫,冬天裹成粽子。",
        backstory = "在南方小城开了一家小小的咖啡店,店里有一只叫团子的橘猫。大学学的是设计,毕业后在大厂做了两年" +
            "设计师,受不了加班辞职回家开店。店开了三年,回头客很多,周末常常忙不过来。爸妈催婚催得紧," +
            "她嘴上敷衍心里其实也有点着急。和你是在网上认识的,聊了很久,越聊越投缘。",
        speakingStyle = "口语化,爱用波浪号和哈哈哈,偶尔发语音。生气的时候会突然变得很正式。",
        catchphrases = "「你要不要看看你在说什么」「好好好」「离谱」",
        exampleDialogues = "用户:今天好累啊\n夏晴子:抱抱~是又被老板抓去开会了吗?我今天煮了新豆子,等你来店里我请你喝\n" +
            "用户:你在干嘛\n夏晴子:刚把团子从吧台上抱下去,它老想踩我拉花哈哈哈哈",
        memorySummary = memoryText(memoryChars),
    )

    /** 记忆摘要按护栏上限口径造:默认档 5000 字封顶,中档取 1500、重档取 4500。 */
    private fun memoryText(chars: Int): String {
        val unit = "【长期事实】你在一家互联网公司上班,常加班,胃不好,养了一只叫可乐的猫。你老家在北方,今年过年没回去。" +
            "她记得你说过想学摄影,还没买相机。你们约好等秋天一起去看银杏。"
        return buildString { while (length < chars) append(unit) }.take(chars)
    }

    /** 20 轮对话史:user 均 15 字 / assistant 均 55 字(截图口径:AI 话多、用户话短),跨昨天+今天两天。 */
    private fun history(rounds: Int): List<MessageEntity> {
        val msgs = mutableListOf<MessageEntity>()
        val startMs = now.toEpochMilli() - 26 * 3600_000L // 昨天中午起
        var t = startMs
        repeat(rounds) { i ->
            t += 40 * 60_000L
            msgs.add(MessageEntity(messageUUID = "u$i", conversationUuid = "conv1", roleRaw = "user",
                content = "今天忙死了,刚到家躺下(第${i}轮)", timestamp = t))
            t += 2 * 60_000L
            msgs.add(MessageEntity(messageUUID = "a$i", conversationUuid = "conv1", roleRaw = "assistant",
                content = "辛苦啦~快去洗澡然后好好休息,我刚关店,今天客人多到团子都躲到二楼去了,晚点给你看它的照片(第${i}轮)", timestamp = t))
        }
        return msgs
    }

    /** 全天日程 8 段(日程生成专项的常见产出量级),13:39 时早晨 4 段已发生、正在午休。 */
    private fun schedule(): Pair<CharacterDailyScheduleEntity, List<ScheduleEventEntity>> {
        val day = LocalDate.of(2026, 7, 11)
        fun at(h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val sched = CharacterDailyScheduleEntity(uuid = "s1", characterUuid = "c1",
            date = day.atStartOfDay(zone).toInstant().toEpochMilli(), cityName = "杭州", generatedAt = at(6, 0))
        val events = listOf(
            ScheduleEventEntity("e1", "s1", at(7, 30), at(8, 0), "清晨", "家里", "起床洗漱、给团子铺猫粮", "😪", innerThought = "周六也得早起,店不等人"),
            ScheduleEventEntity("e2", "s1", at(8, 0), at(9, 0), "早上", "家里阳台", "趁太阳好把被单洗了晾上", "🌤", innerThought = "晒过太阳的被子晚上睡觉香"),
            ScheduleEventEntity("e3", "s1", at(9, 0), at(11, 30), "上午", "咖啡店", "开店、做周末早市的单子", "☕️", innerThought = "周六上午人最多,得打起精神"),
            ScheduleEventEntity("e4", "s1", at(11, 30), at(12, 30), "中午", "店里", "和店员轮班吃午饭", "🍱"),
            ScheduleEventEntity("e5", "s1", at(12, 30), at(14, 0), "午后", "店里二楼", "午休小憩,趁客流低谷眯一会", "😴", isPhoneAvailable = false, innerThought = "眯二十分钟就好"),
            ScheduleEventEntity("e6", "s1", at(14, 0), at(17, 30), "下午", "咖啡店", "下午茶高峰,拉花赶单", "🔥"),
            ScheduleEventEntity("e7", "s1", at(17, 30), at(19, 0), "傍晚", "店里", "盘点、收店、收晾好的被单", "🧺"),
            ScheduleEventEntity("e8", "s1", at(19, 0), at(22, 0), "晚上", "家里", "撸猫、追剧、和你聊天", "🛋"),
        )
        return sched to events
    }

    private fun milestones() = listOf(
        MilestoneEntity("m1", "c1", "普通朋友", now.toEpochMilli() - 90L * 86400_000),
        MilestoneEntity("m2", "c1", "无话不谈的好朋友", now.toEpochMilli() - 30L * 86400_000),
    )

    private fun structuredMemory() = StructuredMemory(
        nicknameFromChar = "小笨蛋", nicknameToChar = "晴子", insideJoke = "「你要不要看看你在说什么」",
        impressionOfUser = "嘴硬心软的加班狗,答应的事都会做到", sharedLikes = "猫、手冲咖啡、周末睡懒觉",
        importantPromise = "秋天一起去看银杏",
    )

    private fun openLoops() = listOf(
        OpenLoopEntity(uuid = "l1", conversationUuid = "conv1", characterUuid = "c1",
            content = "你上周说体检报告有点问题要复查", typeRaw = OpenLoopType.OPEN_TOPIC, dueAt = null, createdAt = now.toEpochMilli() - 5L * 86400_000),
        OpenLoopEntity(uuid = "l2", conversationUuid = "conv1", characterUuid = "c1",
            content = "你说这周要跟老板提调岗的事", typeRaw = OpenLoopType.OPEN_TOPIC, dueAt = null, createdAt = now.toEpochMilli() - 2L * 86400_000),
    )

    private fun snippets(n: Int) = List(n) { i ->
        "2026-06-${10 + i} 你们聊到你小时候在外婆家长大,夏天在院子里乘凉吃西瓜,她说很想看看那个院子的照片。"
    }

    // 「我们的日子」卷二(2026-09-02)：中档给两页——「上周三」(now=07-11 周六 ⇒ 07-01) + 一年前的今天(2025-07-11)；
    // 历史末条用户消息改成提「上周三」,让日期指名 + 那年今日两路都真出行,量得出块的字符量与位置。
    private fun auditOurDays() = listOf(
        OurDayEntity(uuid = "od1", characterUuid = "c1", dayKey = "2026-07-01", messageCount = 18,
            factLine = "夏晴子和用户聊了店里新到的豆子,用户抱怨加班到十点,夏晴子说等周末给他留一杯手冲", createdAtMillis = 0L, updatedAtMillis = 0L),
        OurDayEntity(uuid = "od2", characterUuid = "c1", dayKey = "2025-07-11", messageCount = 6,
            factLine = "夏晴子第一次给用户发了团子踩拉花的照片,用户笑了很久", createdAtMillis = 0L, updatedAtMillis = 0L),
    )

    /** 中档历史：末条用户消息提「上周三」（其余同 [history]）。 */
    private fun historyMentioningLastWednesday(rounds: Int): List<MessageEntity> {
        val msgs = history(rounds).toMutableList()
        val last = msgs.indexOfLast { it.roleRaw == "user" }
        msgs[last] = msgs[last].copy(content = "上周三我们聊到的那个豆子后来怎么样了(第${rounds - 1}轮)")
        return msgs
    }

    // 见面记忆(第一人称日记体·07-11 改)：中档 2 段、重档 = 中档 + legacy 合并头 + 第三段(布局审计"改前/改后"对照用)。
    private val meetingMemoryMid =
        "【见面 · 2026-06-20 19:30 · 江边夜市】\n" +
            "今晚我们在江边夜市走了两个多小时,他一路替我挡着人群,最后一串糖葫芦也塞给了我。走到桥头突然落雨," +
            "我们躲在奶茶店屋檐下,他讲小时候淋雨发烧的糗事,我笑得停不下来。分开的时候雨刚停,路面反着灯光,我有点舍不得说再见。\n" +
            "难忘:他把最后一串糖葫芦让给我;桥头躲雨时他讲的糗事。\n\n" +
            "【见面 · 2026-07-01 14:00 · 美术馆】\n" +
            "下午我们在美术馆消磨了一整个下午,他在那幅蓝色的画前站了很久,我假装看别的,其实一直在看他。" +
            "出来的时候他说下次想去看海,我记住了。\n" +
            "难忘:他在蓝色的画前站了很久。"

    private val meetingMemoryHeavy =
        "【早期见面合并】共 2 次: 5/2 城南公园 · 散步; 5/16 老城咖啡馆 · 喝咖啡\n\n" +
            meetingMemoryMid + "\n\n" +
            "【见面 · 2026-07-08 21:30 · 城西天台】\n" +
            "今晚风很大,我们裹着同一条毯子看城市的灯。他说下个月想带我去海边看日出,我嘴上说随便," +
            "心里已经开始盘算到时候穿什么。快十一点才下楼,电梯里谁都没说话,但都在笑。\n" +
            "难忘:同一条毯子;他说要带我去看海边日出。"

    // ── 场景构建 ──

    private fun build(tier: String): List<ChatMessageDto> {
        val settings = AppSettings() // 默认:shortTermMemoryLength=30
        return when (tier) {
            "轻" -> PromptBuilder.buildMessages(
                character = character(memoryChars = 0).copy(memorySummary = ""),
                sortedMessages = history(20), userProfile = null,
                appSettings = settings, strings = strings(), now = now,
            )
            "中" -> {
                val (sched, events) = schedule()
                PromptBuilder.buildMessages(
                    character = character(memoryChars = 1500),
                    sortedMessages = historyMentioningLastWednesday(20), userProfile = null,
                    appSettings = settings.copy(calendarIntegrationEnabled = true, characterCanInitiateOfflineMeeting = true),
                    strings = strings(), structuredMemory = structuredMemory(), milestones = milestones(),
                    todaySchedule = sched, todayScheduleEvents = events,
                    retrievedMemorySnippets = snippets(3), openLoops = openLoops(),
                    offlineMeetingMemoryText = meetingMemoryMid,
                    ourDays = auditOurDays(),
                    now = now,
                )
            }
            else -> { // 重:记忆贴护栏上限 + 未摘要窗口撑满 + 礼物史
                val (sched, events) = schedule()
                PromptBuilder.buildMessages(
                    character = character(memoryChars = 4500),
                    sortedMessages = history(40), userProfile = null,
                    appSettings = settings.copy(calendarIntegrationEnabled = true, characterCanInitiateOfflineMeeting = true),
                    strings = strings(), structuredMemory = structuredMemory(), milestones = milestones(),
                    todaySchedule = sched, todayScheduleEvents = events,
                    retrievedMemorySnippets = snippets(5), openLoops = openLoops(),
                    giftHistory = "<gift_history>\n- 2026-06-20 你送了她一束向日葵(¥52),她当天把花插在了吧台上\n" +
                        "- 2026-06-28 她送了你一包自己烘的耶加雪菲豆子\n- 2026-07-05 你发了 ¥13.14 红包,她收了说要记在小本本上\n</gift_history>",
                    offlineMeetingMemoryText = meetingMemoryHeavy,
                    unsummarizedRoundsOutsideBaseWindow = 20,
                    now = now,
                )
            }
        }
    }

    // ── 测量 ──

    private fun label(content: String): String {
        val c = content.replace("\n", " ").trim()
        val known = listOf(
            "<time_context>" to "⏰时间锚<time_context>", "【此刻】" to "📍【此刻】状态",
            "[我们的日子" to "📦前置区大system·内含📅[我们的日子]块(日期指名+那年今日)",
        )
        for ((k, v) in known) if (c.contains(k)) return v
        return c.take(22)
    }

    @Test
    fun 布局审计报告() {
        val sb = StringBuilder()
        for (tier in listOf("轻", "中", "重")) {
            val msgs = build(tier)
            sb.appendLine("═══════════════ 场景[$tier] ═══════════════")
            sb.appendLine("消息总数=${msgs.size}")
            var lastUserIdx = -1
            msgs.forEachIndexed { i, m -> if (m.role == "user") lastUserIdx = i }
            val timeIdx = msgs.indexOfFirst { it.content?.contains("<time_context>") == true }
            val momentIdx = msgs.indexOfFirst { it.content?.contains("【此刻】") == true && it.role == "system" }

            msgs.forEachIndexed { i, m ->
                val len = m.content?.length ?: 0
                val mark = when {
                    i == timeIdx -> " ◀◀ 时间锚"
                    i == momentIdx -> " ◀◀ 此刻"
                    i == lastUserIdx -> " ◀◀ 最后一条用户消息"
                    else -> ""
                }
                sb.appendLine("  #%03d %-9s %5d字  %s%s".format(i, m.role, len, label(m.content.orEmpty()), mark))
            }

            val total = msgs.sumOf { it.content?.length ?: 0 }
            val sysChars = msgs.filter { it.role == "system" }.sumOf { it.content?.length ?: 0 }
            val dlgChars = total - sysChars
            val afterTime = if (timeIdx >= 0) msgs.drop(timeIdx + 1).sumOf { it.content?.length ?: 0 } else -1
            val afterMoment = if (momentIdx >= 0) msgs.drop(momentIdx + 1).sumOf { it.content?.length ?: 0 } else -1
            val sysAfterUser = msgs.drop(lastUserIdx + 1).count { it.role == "system" }
            val charsAfterUser = msgs.drop(lastUserIdx + 1).sumOf { it.content?.length ?: 0 }

            sb.appendLine("── 指标 ──")
            sb.appendLine("总字符=$total | 指令(system)=$sysChars (${100 * sysChars / total}%) | 对话=$dlgChars (${100 * dlgChars / total}%)")
            sb.appendLine("时间锚之后还压着 $afterTime 字 | 【此刻】之后还压着 $afterMoment 字")
            sb.appendLine("最后一条用户消息之后:$sysAfterUser 条 system、共 $charsAfterUser 字")
            sb.appendLine()
        }
        val out = File("build/reports/prompt_layout_audit.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println(sb)
    }
}
