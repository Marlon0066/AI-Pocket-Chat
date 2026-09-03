package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MilestoneEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T1（图纸《2026-09-03 关系历程注入根治》§8）：[buildRelationshipMilestoneDescription] 九组。
 *
 * 断言**从图纸 §3 件 1/2/4 规格独立反推**（模板逐字重打 + 天数手算后经独立日历核算），不照抄实现输出。
 * 纯 JVM（java.time 与纯函数，无安卓依赖）；毫秒一律由本地日期经 [ZONE] 构造，故与运行机时区无关。
 */
class RelationshipMilestonePromptTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        const val TAIL = "请根据当前的关系状态来调整你的语气、称呼和互动方式。关系是动态变化的，可能升级也可能降级。"
        const val JOURNEY = "你们一路是这么走过来的："
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(ZONE).toInstant().toEpochMilli()

    private fun milestone(name: String, millis: Long, reason: String = "初始设定", trigger: String = "aiAutomatic") =
        MilestoneEntity(
            uuid = "$name-$millis", characterUuid = "c1", relationshipName = name,
            establishedDate = millis, reason = reason, triggerTypeRaw = trigger,
        )

    private fun render(
        milestones: List<MilestoneEntity>,
        firstMessageDate: Long? = null,
        now: Long = at(2026, 9, 3),
    ) = buildRelationshipMilestoneDescription(milestones, "小明", now, firstMessageDate)

    // ① 完整一例：三次关系变化 + 全部三行理由 + 平缓节奏句 → 整段逐字。
    @Test fun `完整一例_逐字`() {
        val out = render(
            milestones = listOf(
                milestone("朋友", at(2026, 1, 5), reason = "初始设定", trigger = "userAdvance"),
                milestone("好朋友", at(2026, 3, 10), reason = "两个人聊到半夜，林晚第一次说起家里的事。"),
                milestone("恋人", at(2026, 6, 20), reason = "林晚说出了一直没敢提的那件事，小明没有回避，认真接住了。"),
            ),
            firstMessageDate = at(2026, 1, 5),
        )
        assertEquals(
            "## 你和小明的关系\n" +
                "你们现在是「恋人」——从 2026年6月20日 算起，在一起 75 天了。\n" +
                "$JOURNEY\n" +
                "- 2026年1月5日 · 241天前：朋友（初始设定）\n" +
                "- 2026年3月10日 · 177天前：好朋友（两个人聊到半夜，林晚第一次说起家里的事。）\n" +
                "- 2026年6月20日 · 75天前：恋人（林晚说出了一直没敢提的那件事，小明没有回避，认真接住了。）\n" +
                "你们认识 166 天后成了现在这个关系。\n" +
                TAIL,
            out,
        )
        // 「AI 判断：」前缀已删除（triggerTypeRaw 不再参与渲染）。
        assertFalse("AI 判断前缀已删", out.contains("AI 判断"))
    }

    // ② 空历程 → 空串（整段不出）。
    @Test fun `空历程_返回空串`() {
        assertEquals("", render(emptyList(), firstMessageDate = at(2026, 1, 5)))
    }

    // ③ 仅一条里程碑：当前段起点 = 它自己；同日确立 ⇒ 节奏句缺席（D=0）。
    @Test fun `仅一条里程碑_逐字`() {
        val day = at(2026, 8, 30)
        assertEquals(
            "## 你和小明的关系\n" +
                "你们现在是「朋友」——从 2026年8月30日 算起，在一起 4 天了。\n" +
                "$JOURNEY\n" +
                "- 2026年8月30日 · 4天前：朋友（初始设定）\n" +
                TAIL,
            render(listOf(milestone("朋友", day)), firstMessageDate = day),
        )
    }

    // ④ 分手复合：当前段起点取**复合**那次，不是分手前那次；末尾连续同名只留最早一条。
    @Test fun `分手复合_当前段取复合日`() {
        val out = render(
            milestones = listOf(
                milestone("朋友", at(2026, 1, 5)),
                milestone("恋人", at(2026, 2, 1), reason = "r1"),
                milestone("前任", at(2026, 3, 1), reason = "r2"),
                milestone("恋人", at(2026, 5, 1), reason = "r3"),
                milestone("恋人", at(2026, 6, 1), reason = "r4"),
            ),
            firstMessageDate = at(2026, 1, 5),
        )
        val lines = out.lines()
        assertEquals("你们现在是「恋人」——从 2026年5月1日 算起，在一起 125 天了。", lines[1])
        assertFalse("不取分手前那次恋人", out.contains("2026年2月1日 算起"))
        // 去重后 4 条：末段 6月1日 恋人被并入 5月1日 那条。
        assertEquals(
            listOf(
                "- 2026年1月5日 · 241天前：朋友",
                "- 2026年2月1日 · 214天前：恋人（r1）",
                "- 2026年3月1日 · 186天前：前任（r2）",
                "- 2026年5月1日 · 125天前：恋人（r3）",
            ),
            lines.filter { it.startsWith("- ") },
        )
        assertFalse("被去重的同名条不出现", out.contains("2026年6月1日"))
    }

    // ⑤ 先去重、后取 10（旧实现先 take 后不去重，10 个名额会被同一名字占满）。
    @Test fun `二十五条_先去重后取十`() {
        // (a) 5 个名字各连发 5 条 = 25 条 → 去重后只剩 5 行。
        val runs = (1..5).flatMap { r -> (1..5).map { i -> milestone("关系$r", at(2026, r, i)) } }
        assertEquals(25, runs.size)
        val dedupedRows = render(runs).lines().filter { it.startsWith("- ") }
        assertEquals(5, dedupedRows.size)
        // 末 3 行按分层带「（初始设定）」，比名字时剥掉。
        assertEquals(
            listOf("关系1", "关系2", "关系3", "关系4", "关系5"),
            dedupedRows.map { it.substringAfterLast("：").substringBefore("（") },
        )

        // (b) 25 条全不同名 → 渲染最近 10 条（第 16..25 条）。
        val distinct = (1..25).map { i -> milestone("名分$i", at(2025, 1, 1) + i * 86_400_000L) }
        val rows = render(distinct).lines().filter { it.startsWith("- ") }
        assertEquals(10, rows.size)
        assertTrue("首行 = 第 16 条", rows.first().endsWith("：名分16"))
        assertTrue("末行 = 第 25 条", rows.last().contains("：名分25"))
    }

    // ⑥ firstMessageDate 为 null → 节奏句整行缺席（与相识行本身的缺席规则一致）。
    @Test fun `首聊日为空_节奏句缺席`() {
        val out = render(listOf(milestone("恋人", at(2026, 6, 20))), firstMessageDate = null)
        assertFalse("无节奏句", out.contains("你们认识"))
        // 缺席 = 少一行，其余不受影响。
        assertEquals(JOURNEY, out.lines()[2])
        assertEquals(TAIL, out.lines().last())
    }

    // ⑦ D == 0（同日认识即确立）→ 节奏句缺席；时钟回拨（D < 0）同样缺席。
    @Test fun `D为零或负_节奏句缺席`() {
        val start = at(2026, 6, 20)
        assertFalse(render(listOf(milestone("恋人", start)), firstMessageDate = at(2026, 6, 20, hour = 1)).contains("你们认识"))
        assertFalse(render(listOf(milestone("恋人", start)), firstMessageDate = at(2026, 6, 25)).contains("你们认识"))
        assertEquals("", relationshipPaceLine(at(2026, 6, 20, hour = 1), start))
        assertEquals("", relationshipPaceLine(null, start))
    }

    // ⑧ 节奏句四阈值（1..3 快 / 4..180 平 / >180 慢）。
    @Test fun `节奏句四阈值`() {
        val start = at(2026, 9, 1)
        fun paceAfter(days: Long) = relationshipPaceLine(start - days * 86_400_000L, start)
        assertEquals("你们认识 1 天后就成了现在这个关系，快得几乎没有过渡。", paceAfter(1))
        assertEquals("你们认识 3 天后就成了现在这个关系，快得几乎没有过渡。", paceAfter(3))
        assertEquals("你们认识 4 天后成了现在这个关系。", paceAfter(4))
        assertEquals("你们认识 180 天后成了现在这个关系。", paceAfter(180))
        assertEquals("你们认识 181 天后才成了现在这个关系，这一步你们走了很久。", paceAfter(181))
    }

    // ⑨ 理由分层（第 4 条及更早无括号）+ 同日多条只留最后一条的理由 + reason 空则无括号。
    @Test fun `理由分层_同日只留最后_空理由无括号`() {
        val out = render(
            milestones = listOf(
                milestone("朋友", at(2026, 1, 5), reason = "r1"),
                milestone("好朋友", at(2026, 2, 1), reason = "r2"),
                milestone("暧昧对象", at(2026, 3, 1), reason = "r3"),
                milestone("恋人", at(2026, 6, 20, hour = 12), reason = "r4"),
                milestone("热恋期", at(2026, 6, 20, hour = 20), reason = "r5"),
                milestone("老夫老妻", at(2026, 8, 1), reason = ""),
            ),
            firstMessageDate = at(2026, 1, 5),
        )
        assertEquals(
            listOf(
                "- 2026年1月5日 · 241天前：朋友",          // 第 6 条起往前数第 6 → 无理由
                "- 2026年2月1日 · 214天前：好朋友",         // 第 5 → 无理由
                "- 2026年3月1日 · 186天前：暧昧对象",       // 第 4 → 无理由
                "- 2026年6月20日 · 75天前：恋人",           // 落在最近 3 条，但同日还有更后一条 → 理由让给它
                "- 2026年6月20日 · 75天前：热恋期（r5）",   // 同日最后一条 → 带理由
                "- 2026年8月1日 · 33天前：老夫老妻",        // reason 空 → 括号连同内容一起省略
            ),
            out.lines().filter { it.startsWith("- ") },
        )
        assertFalse("空理由不留空括号", out.contains("（）"))
        assertFalse("被分层掉的理由不在场", out.contains("r1") || out.contains("r2") || out.contains("r3") || out.contains("r4"))
    }

    // 补：去重纯函数本身（非连续同名各段各留）。
    @Test fun `去重只并连续同名`() {
        val list = listOf(
            milestone("朋友", at(2026, 1, 1)),
            milestone("朋友", at(2026, 1, 2)),
            milestone("恋人", at(2026, 1, 3)),
            milestone("朋友", at(2026, 1, 4)),
        )
        assertEquals(
            listOf(at(2026, 1, 1), at(2026, 1, 3), at(2026, 1, 4)),
            dedupeConsecutiveSameName(list).map { it.establishedDate },
        )
        assertEquals(emptyList<Long>(), dedupeConsecutiveSameName(emptyList()).map { it.establishedDate })
    }
}
