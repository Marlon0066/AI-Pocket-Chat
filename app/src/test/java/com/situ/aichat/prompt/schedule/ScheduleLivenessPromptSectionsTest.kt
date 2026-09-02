package com.situ.aichat.prompt.schedule

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * T1-2（图纸 2026-07-10 日程专项 §7·E12/E13/E15/E16/E19）：B/C/D/E 段纯函数。
 * 锁定文案「重新打字」为字面量断言（图纸 §4）；tier 边界 24/25/49/50/74/75 全打点。
 */
class ScheduleLivenessPromptSectionsTest {

    private val zone = ZoneOffset.UTC
    private val date: LocalDate = LocalDate.of(2026, 7, 10)

    private fun char(
        dynamicInterestsJSON: String = "",
        moodHistoryJSON: String = "",
        relationshipQualityJSON: String = "",
        firstMessageDate: Long? = null,
        streakCount: Int = 0,
        memorySummary: String = "",
    ) = CharacterEntity(
        uuid = "c1", name = "夏晴子", creationDate = 0L,
        dynamicInterestsJSON = dynamicInterestsJSON, moodHistoryJSON = moodHistoryJSON,
        relationshipQualityJSON = relationshipQualityJSON, firstMessageDate = firstMessageDate,
        streakCount = streakCount, memorySummary = memorySummary,
    )

    // ── B 兴趣行 ──

    @Test
    fun `B 兴趣行_heat降序取5_锁定文案`() {
        val interests = (1..7).map { DynamicInterest(name = "兴趣$it", heat = it * 10) }
        val line = ScheduleLivenessPromptSections.interestsLine(
            char(dynamicInterestsJSON = GrowthJson.encodeDynamicInterests(interests)),
        )
        assertEquals("最近热衷：兴趣7、兴趣6、兴趣5、兴趣4、兴趣3（按热衷程度排序，日程优先体现这些）", line)
    }

    @Test
    fun `B 无成长数据或JSON损坏_行缺席_E13`() {
        assertNull(ScheduleLivenessPromptSections.interestsLine(char()))
        assertNull(ScheduleLivenessPromptSections.interestsLine(char(dynamicInterestsJSON = "{broken")))
    }

    // ── I 意图块（卷四 T2-6 ③·图纸 §4.5）──

    @Test
    fun `I 意图块_标题_条目_尾行锁定文案_无live缺席`() {
        val now = System.currentTimeMillis()
        val queue = GrowthJson.encode(
            IntentQueueState(
                intents = listOf(
                    CharacterIntent(id = "i", kind = IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 50, bornAt = now, lastChangeAt = now),
                    CharacterIntent(id = "j", kind = IntentKind.WANT_SHARE, state = IntentState.FADED, strength = 5, bornAt = now, lastChangeAt = now, residue = true),
                ),
            ),
        )
        assertEquals(
            listOf(
                "【TA心里挂着的事】",
                "- TA想向小明道歉",
                "这些只能进 innerThought（比如「要不要找个机会跟小明说一声」），不要变成日程事件，也不必每条都用。",
            ),
            ScheduleLivenessPromptSections.intentSection(char().copy(intentQueueJSON = queue), "小明", now),
        )
        assertTrue(ScheduleLivenessPromptSections.intentSection(char(), "小明", now).isEmpty())
        assertTrue(ScheduleLivenessPromptSections.intentSection(char().copy(intentQueueJSON = "{坏"), "小明", now).isEmpty())
    }

    // ── C 心情走向 ──

    private fun moods(vararg colors: String): String =
        GrowthJson.encodeMoodHistory(colors.mapIndexed { i, c -> MoodHistoryEntry(timestamp = (i + 1) * 1000L, emoji = "🙂", colorName = c) })

    @Test
    fun `C 红3条_持续低落`() {
        val line = ScheduleLivenessPromptSections.moodTrendLine(char(moodHistoryJSON = moods("red", "red", "green", "red", "yellow")))
        assertEquals("最近心情走向：持续低落", line)
    }

    @Test
    fun `C 只按最近5条判定_旧红不算`() {
        // 8 条：最旧 3 红 + 最新 5 绿 → 按 timestamp 倒序取 5 = 全绿
        val line = ScheduleLivenessPromptSections.moodTrendLine(
            char(moodHistoryJSON = moods("red", "red", "red", "green", "green", "green", "green", "green")),
        )
        assertEquals("最近心情走向：不错", line)
    }

    @Test
    fun `C 混合_平稳·黄3_起伏·少于3条_缺席E15`() {
        assertEquals("最近心情走向：平稳", ScheduleLivenessPromptSections.moodTrendLine(char(moodHistoryJSON = moods("red", "yellow", "green", "red", "yellow"))))
        assertEquals("最近心情走向：有些起伏", ScheduleLivenessPromptSections.moodTrendLine(char(moodHistoryJSON = moods("yellow", "yellow", "yellow"))))
        assertNull(ScheduleLivenessPromptSections.moodTrendLine(char(moodHistoryJSON = moods("red", "red"))))
    }

    // ── D 关系块 ──

    private fun charWithScore(score: Int, firstMillis: Long? = 0L, streak: Int = 0): CharacterEntity =
        char(
            relationshipQualityJSON = GrowthJson.encode(
                RelationshipQuality(familiarity = score, trust = score, closeness = score, attachment = score),
            ),
            firstMessageDate = firstMillis, streakCount = streak,
        )

    @Test
    fun `D tier边界_24新识_25熟络_49熟络_50亲密_74亲密_75深厚`() {
        assertEquals("新识", ScheduleLivenessPromptSections.relationshipTier(charWithScore(24)))
        assertEquals("熟络", ScheduleLivenessPromptSections.relationshipTier(charWithScore(25)))
        assertEquals("熟络", ScheduleLivenessPromptSections.relationshipTier(charWithScore(49)))
        assertEquals("亲密", ScheduleLivenessPromptSections.relationshipTier(charWithScore(50)))
        assertEquals("亲密", ScheduleLivenessPromptSections.relationshipTier(charWithScore(74)))
        assertEquals("深厚", ScheduleLivenessPromptSections.relationshipTier(charWithScore(75)))
    }

    @Test
    fun `D 块结构_相识天数_连聊分句_锁定文案`() {
        // firstMessageDate = 2026-07-01 UTC 0点 → 相识 9 天
        val first = LocalDate.of(2026, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lines = ScheduleLivenessPromptSections.relationshipSection(charWithScore(80, first, streak = 5), date, zone)
        assertEquals(
            listOf(
                "【和用户的关系】",
                "相识 9 天、最近连续聊了 5 天，关系阶段：深厚",
                "在今天的 innerThought 里，想到用户的事件数参考：2–3 个，自然流露，但TA依然有自己的生活重心",
            ),
            lines,
        )
    }

    @Test
    fun `D streak不足2省略分句_新识文案_未来时间戳取0天E19_无首聊缺席`() {
        val lines = ScheduleLivenessPromptSections.relationshipSection(
            charWithScore(0, LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli(), streak = 1), date, zone,
        )
        assertEquals("相识 0 天，关系阶段：新识", lines[1])
        assertEquals("在今天的 innerThought 里，想到用户的事件数参考：最多 1 个，且要克制含蓄", lines[2])
        assertTrue(ScheduleLivenessPromptSections.relationshipSection(charWithScore(50, firstMillis = null), date, zone).isEmpty())
    }

    // ── E 长期记忆块 ──

    @Test
    fun `E 无标题或空_缺席E12`() {
        assertTrue(ScheduleLivenessPromptSections.longTermMemorySection(char()).isEmpty())
        assertTrue(ScheduleLivenessPromptSections.longTermMemorySection(char(memorySummary = "没有分区标题的旧文本")).isEmpty())
    }

    @Test
    fun `E 300字整行预算_超停`() {
        val l100 = "甲".repeat(100)
        val l150 = "乙".repeat(150)
        val l99 = "丙".repeat(99)
        val summary = "【长期事实】\n$l100\n$l150\n$l99\n【近期经历】\n最近的事"
        val lines = ScheduleLivenessPromptSections.longTermMemorySection(char(memorySummary = summary))
        // 100+150=250 ≤300；再加 99 会到 349 >300 → 停。头 + 2 行 + 尾规则行 = 4 行
        assertEquals(4, lines.size)
        assertEquals("【TA的长期记忆】", lines[0])
        assertEquals(l100, lines[1])
        assertEquals(l150, lines[2])
        assertEquals(
            "其中关于TA自己生活的事实（习惯、宠物、在学的东西等）应自然体现在日程里；关于用户的事实只能在 innerThought 里出现，不得作为 activity。",
            lines[3],
        )
    }

    @Test
    fun `E 首行独超300_截前300字_E16`() {
        val huge = "丁".repeat(400)
        val lines = ScheduleLivenessPromptSections.longTermMemorySection(char(memorySummary = "【长期事实】\n$huge"))
        assertEquals("丁".repeat(300), lines[1])
        assertEquals(3, lines.size)
    }

    // ── 人称指名（图纸二 §7 T2-1）：4 段函数传真名 → 渲染真名、旧「用户」称呼绝迹 ──

    @Test
    fun `命名 四段传小明_渲染真名不含用户旧称呼`() {
        // relationship（块名 + 事件数参考行两处「用户」→「小明」）
        val first = LocalDate.of(2026, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rel = ScheduleLivenessPromptSections.relationshipSection(charWithScore(80, first, streak = 5), date, zone, "小明")
        assertEquals("【和小明的关系】", rel[0])
        assertTrue(rel[2].contains("想到小明的事件数参考"))
        assertFalse(rel.any { it.contains("和用户的关系") || it.contains("想到用户的事件数参考") })

        // longTerm（「关于用户的事实」→「关于小明的事实」；「TA自己」= 角色引用不动）
        val lt = ScheduleLivenessPromptSections.longTermMemorySection(char(memorySummary = "【长期事实】\n她养了一只猫"), "小明")
        assertTrue(lt.last().contains("关于小明的事实只能在 innerThought 里出现"))
        assertFalse(lt.last().contains("关于用户的事实"))
        assertTrue(lt.last().contains("关于TA自己生活的事实")) // 角色引用「TA」原样保留

        // todayPromises（见面行 + 约好的/不写与X互动 尾句三处「用户」→「小明」）
        val tp = ScheduleLivenessPromptSections.todayPromisesSection(
            ScheduleLivenessContext(todayMeetings = listOf(ScheduleLivenessContext.MeetingLine("15:00", "美术馆", "看展"))),
            "小明",
        )
        assertTrue(tp.any { it.contains("和小明见面") })
        val tpReq = tp.last()
        assertTrue(tpReq.contains("和小明约好的"))
        assertTrue(tpReq.contains("activity 不写与小明互动"))
        assertFalse(tp.any { it.contains("和用户见面") || it.contains("和用户约好的") || it.contains("不写与用户互动") })

        // openLoops（「属于用户自己的事」→「属于小明自己的事」；「TA的日程」= 角色引用不动）
        val ol = ScheduleLivenessPromptSections.openLoopsSection(listOf("用户下周面试"), "小明")
        assertTrue(ol.last().contains("属于小明自己的事"))
        assertFalse(ol.last().contains("属于用户自己的事"))
        assertTrue(ol.last().contains("更不能排进TA的日程")) // 角色引用「TA」原样保留
    }

    @Test
    fun `命名 省略userName默认回退用户_字节零变化E1`() {
        // 尾参默认「用户」= 旧调用点/回归钉字节不变（§1e 尾参默认字节级零变化专测）
        val first = LocalDate.of(2026, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("【和用户的关系】", ScheduleLivenessPromptSections.relationshipSection(charWithScore(80, first, streak = 5), date, zone)[0])
        assertTrue(
            ScheduleLivenessPromptSections.longTermMemorySection(char(memorySummary = "【长期事实】\n她养了一只猫"))
                .last().contains("关于用户的事实"),
        )
        assertTrue(
            ScheduleLivenessPromptSections.todayPromisesSection(
                ScheduleLivenessContext(todayMeetings = listOf(ScheduleLivenessContext.MeetingLine("15:00", "美术馆", "看展"))),
            ).any { it.contains("和用户见面") },
        )
        assertTrue(
            ScheduleLivenessPromptSections.openLoopsSection(listOf("用户下周面试")).last().contains("属于用户自己的事"),
        )
    }
}
