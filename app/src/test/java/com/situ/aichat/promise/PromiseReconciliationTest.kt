package com.situ.aichat.promise

import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 约定对账纯逻辑（记忆改造一期·部件②·图纸 §3.12 / T1-4/5/6）。断言从图纸 §3.12/§5 独立反推：
 * 提示词哨兵（编号清单/空清单两分支）+ 四道闸（编号越界/重复/未知 status·证据缺失/过短/不在素材·金额/超长/due 解析/超 3 截断）。
 */
class PromiseReconciliationTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int): Long =
        LocalDateTime.of(y, mo, d, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun open(uuid: String, content: String, created: Long) =
        PromiseEntity(uuid = uuid, characterUuid = "c1", content = content, createdAtMillis = created, updatedAtMillis = created)

    private val openList = listOf(
        open("u1", "一起去看画展", at(2026, 7, 1)),
        open("u2", "帮忙改简历", at(2026, 7, 2)),
    )

    private val material = "用户：我们说好周末一起去看画展。\n角色：好呀一言为定。\n用户：简历我已经改好发你啦，谢谢"

    // ── T1-4 提示词哨兵 + changes 闸 ──

    // ── T1-1 提示词 v2 哨兵（记忆改造四期·§3.3·重打字锁定文本·E15-①） ──

    @Test fun buildPrompt_withList_sentinels() {
        val p = PromiseReconciliation.buildPrompt("团子", "小明", "2026-07-10 12:00", openList, material, zone)
        assertTrue(p.contains("你在帮 AI 角色「团子」维护一份与用户「小明」之间的约定清单。请读下面的对话与生活素材，完成三件事："))
        assertTrue(
            "四期任务 3 补日期行",
            p.contains("3. 给清单上标着「未定日期」的约定补日期：只有素材里明确说定了具体日子才补，并把说定日子的那句原话抄进 evidence；拿不准的不要输出。"),
        )
        assertTrue(p.contains("当前时间：2026-07-10 12:00"))
        // 四期 dueMark：openList 两条均无日期 → ·未定日期。
        assertTrue(p.contains("进行中的约定清单：\n1. 一起去看画展（2026-07-01 定下·未定日期）\n2. 帮忙改简历（2026-07-02 定下·未定日期）"))
        assertTrue(p.contains("金钱类承诺（发红包、转账、给多少钱、送多贵的礼物）不算约定"))
        // 2026-09-06 约定工具调用化 §3.4：工具路先记下的，攒批对账不许再当新约定重复输出（E25·逐字锁定）。
        assertTrue(
            "去重指令句",
            p.contains("清单上已经有的事（哪怕措辞不同）不要再当新约定输出。"),
        )
        assertTrue("四期 dates schema 行", p.contains("\"dates\":[{\"no\":1,\"due\":\"yyyy-MM-dd\",\"evidence\":\"素材原话逐字引用\"}]}"))
        assertTrue(
            "四期规则行（补日期 3 条）",
            p.contains("规则：没有变化就输出空数组；新约定一次最多提取 3 条；补日期只对标着「未定日期」的条目、一次最多 3 条；只依据下面给出的素材判断，不要编造。"),
        )
        assertTrue(p.endsWith("素材：\n$material"))
    }

    @Test fun buildPrompt_dueMark_bothStates() {
        // dueMark 两态：有日期 → ·约在 {yyyy-MM-dd}；无日期 → ·未定日期。
        val dated = open("u3", "八月看海", at(2026, 7, 3))
            .copy(dueAtMillis = LocalDate.of(2026, 8, 15).atTime(9, 0).atZone(zone).toInstant().toEpochMilli())
        val undated = open("u4", "改天做饭", at(2026, 7, 4))
        val p = PromiseReconciliation.buildPrompt("团子", "小明", "t", listOf(dated, undated), material, zone)
        assertTrue("有日期 → ·约在", p.contains("1. 八月看海（2026-07-03 定下·约在 2026-08-15）"))
        assertTrue("无日期 → ·未定日期", p.contains("2. 改天做饭（2026-07-04 定下·未定日期）"))
    }

    @Test fun buildPrompt_emptyList_replacedWithPlaceholder() {
        val p = PromiseReconciliation.buildPrompt("团子", "小明", "2026-07-10 12:00", emptyList(), material, zone)
        assertTrue(p.contains("进行中的约定清单：\n（当前清单为空）\n"))
    }

    @Test fun buildPrompt_blankNames_fallback() {
        val p = PromiseReconciliation.buildPrompt("", "", "t", openList, material, zone)
        assertTrue(p.contains("你在帮 AI 角色「AI 角色」维护一份与用户「用户」之间的约定清单"))
    }

    // ── 图纸一·B3（第三人称指名）：new.content 描述加命名要求，JSON 字段名/结构零变（强耦合 §6） ──

    @Test fun buildPrompt_newContentDescription_requiresNames() {
        val p = PromiseReconciliation.buildPrompt("团子", "小明", "t", openList, material, zone)
        // content 值描述含「第三人称 + 用名字 + 不要写「用户」「角色」」（§9 锁定串·只改描述散文）。
        assertTrue(
            "content 描述含命名要求",
            p.contains("\"content\":\"一句话概括，第三人称，提到两人时用他们的名字、不要写「用户」「角色」，不超过40字\""),
        )
        // 字段名/结构未变（强耦合 buildPrompt↔parseAndVerify）：changes/new/dates 三段 schema 键完整。
        assertTrue("changes schema", p.contains("\"changes\":[{\"no\":1,\"status\":\"fulfilled|cancelled\",\"evidence\":\"素材原话逐字引用\"}]"))
        assertTrue("new 三键", p.contains("\"new\":[{\"content\":") && p.contains("\"due\":") && p.contains("\"evidence\":\"素材原话逐字引用\"}]"))
        assertTrue("dates schema", p.contains("\"dates\":[{\"no\":1,\"due\":\"yyyy-MM-dd\",\"evidence\":\"素材原话逐字引用\"}]}"))
    }

    @Test fun changes_outOfRange_duplicate_unknownStatus_dropped_e2() {
        val raw = """
            {"changes":[
              {"no":1,"status":"fulfilled","evidence":"我们说好周末一起去看画展"},
              {"no":1,"status":"cancelled","evidence":"我们说好周末一起去看画展"},
              {"no":3,"status":"fulfilled","evidence":"我们说好周末一起去看画展"},
              {"no":0,"status":"fulfilled","evidence":"我们说好周末一起去看画展"},
              {"no":2,"status":"pending","evidence":"简历我已经改好发你啦"}
            ],"new":[]}
        """.trimIndent()
        val v = PromiseReconciliation.parseAndVerify(raw, openList, material, zone)
        // no=1 只取首条(fulfilled)；no=3/0 越界丢；no=2 status=pending 丢。
        assertEquals(1, v.changes.size)
        assertEquals("u1", v.changes[0].promiseUuid)
        assertEquals(PromiseStatus.FULFILLED, v.changes[0].status)
    }

    // ── T1-5 证据闸 + 解析失败 ──

    @Test fun evidence_missing_tooShort_notInMaterial_dropped_e3() {
        val raw = """
            {"changes":[
              {"no":1,"status":"fulfilled","evidence":""},
              {"no":1,"status":"fulfilled","evidence":"看展"},
              {"no":2,"status":"fulfilled","evidence":"这句话根本不在素材里出现过"}
            ],"new":[]}
        """.trimIndent()
        val v = PromiseReconciliation.parseAndVerify(raw, openList, material, zone)
        assertTrue("缺失/过短/不在素材全丢", v.changes.isEmpty())
    }

    @Test fun evidence_whitespaceInsensitiveSubstring_passes_e3() {
        // 证据带空白，但去空白后是素材去空白的子串 → 通过。
        val raw = """{"changes":[{"no":2,"status":"fulfilled","evidence":"简历 我 已经 改好 发你啦"}],"new":[]}"""
        val v = PromiseReconciliation.parseAndVerify(raw, openList, material, zone)
        assertEquals(1, v.changes.size)
        assertEquals("u2", v.changes[0].promiseUuid)
    }

    @Test fun garbageJson_throwsParseException() {
        var threw = false
        try {
            PromiseReconciliation.parseAndVerify("这不是 JSON，只是一句闲聊没有花括号", openList, material, zone)
        } catch (e: PromiseReconcileParseException) {
            threw = true
        }
        assertTrue("整体 JSON 坏 → 抛 PromiseReconcileParseException", threw)
    }

    // ── T1-6 新约定闸 ──

    @Test fun new_moneyGuard_tooLong_dropped_e4() {
        val longContent = "约定" + "很".repeat(70) // >60 codePoints
        val raw = """
            {"changes":[],"new":[
              {"content":"周末一起去露营","due":null,"evidence":"我们说好周末一起去看画展"},
              {"content":"给你发 200 元红包","due":null,"evidence":"简历我已经改好发你啦"},
              {"content":"$longContent","due":null,"evidence":"简历我已经改好发你啦"},
              {"content":"","due":null,"evidence":"简历我已经改好发你啦"}
            ]}
        """.trimIndent()
        val v = PromiseReconciliation.parseAndVerify(raw, openList, material, zone)
        assertEquals("只留合法一条（金额/超长/空白全丢）", 1, v.newPromises.size)
        assertEquals("周末一起去露营", v.newPromises[0].content)
    }

    @Test fun new_dueParsing_validInvalidNull() {
        val raw = """
            {"changes":[],"new":[
              {"content":"八月中一起看海","due":"2026-08-15","evidence":"我们说好周末一起去看画展"},
              {"content":"改天再约","due":"不是日期","evidence":"简历我已经改好发你啦"},
              {"content":"以后一起做饭","due":null,"evidence":"好呀一言为定"}
            ]}
        """.trimIndent()
        val v = PromiseReconciliation.parseAndVerify(raw, openList, material, zone)
        assertEquals(3, v.newPromises.size)
        val expected = LocalDate.of(2026, 8, 15).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("合法 due → 当日 09:00", expected, v.newPromises[0].dueAtMillis)
        assertNull("非法 due → null", v.newPromises[1].dueAtMillis)
        assertNull("null due → null", v.newPromises[2].dueAtMillis)
    }

    @Test fun new_takesFirst3_whenMoreThan3() {
        val raw = """
            {"changes":[],"new":[
              {"content":"约定一","due":null,"evidence":"我们说好周末一起去看画展"},
              {"content":"约定二","due":null,"evidence":"我们说好周末一起去看画展"},
              {"content":"约定三","due":null,"evidence":"我们说好周末一起去看画展"},
              {"content":"约定四","due":null,"evidence":"我们说好周末一起去看画展"}
            ]}
        """.trimIndent()
        val v = PromiseReconciliation.parseAndVerify(raw, openList, material, zone)
        assertEquals(3, v.newPromises.size)
        assertEquals(listOf("约定一", "约定二", "约定三"), v.newPromises.map { it.content })
    }

    // ── T1-2（记忆改造四期·§3.4 dates 五闸·E7）：断言从五闸规格独立反推 ──

    private val dueTs = LocalDate.of(2026, 8, 15).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    /** u1/u2/u4/u5 无日期·u3 已有日期（供闸二'）。 */
    private val datesList = listOf(
        open("u1", "看画展", at(2026, 7, 1)),
        open("u2", "改简历", at(2026, 7, 2)),
        open("u3", "已定档的事", at(2026, 7, 3)).copy(dueAtMillis = dueTs),
        open("u4", "第四件", at(2026, 7, 4)),
        open("u5", "第五件", at(2026, 7, 5)),
    )
    private val datesMaterial = "用户：看画展就定在2026-07-18。改简历约在2026-07-17。第四件约2026-07-20。第五件约2026-07-21。"

    @Test fun dates_fiveGates_dropped_e7() {
        val raw = """
            {"changes":[],"new":[],"dates":[
              {"no":1,"due":"2026-07-18","evidence":"看画展就定在2026-07-18"},
              {"no":1,"due":"2026-07-19","evidence":"看画展就定在2026-07-18"},
              {"no":9,"due":"2026-07-20","evidence":"看画展就定在2026-07-18"},
              {"no":3,"due":"2026-07-21","evidence":"看画展就定在2026-07-18"},
              {"no":2,"due":"2026-07-17","evidence":"太短"},
              {"no":4,"due":"不是日期","evidence":"第四件约2026-07-20"}
            ]}
        """.trimIndent()
        val v = PromiseReconciliation.parseAndVerify(raw, datesList, datesMaterial, zone)
        // no=1 取首(有效)；no=1 dup 丢；no=9 越界丢；no=3 目标已有日期丢；no=2 证据太短丢；no=4 due 解析失败丢。
        assertEquals(1, v.dates.size)
        assertEquals("u1", v.dates[0].promiseUuid)
        val expected = LocalDate.of(2026, 7, 18).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("合法 due → 当日 09:00", expected, v.dates[0].dueAtMillis)
        assertEquals("evidence 原样保留（只做闸门）", "看画展就定在2026-07-18", v.dates[0].evidence)
    }

    @Test fun dates_capThree_takesFirst3() {
        val raw = """
            {"changes":[],"new":[],"dates":[
              {"no":1,"due":"2026-07-18","evidence":"看画展就定在2026-07-18"},
              {"no":2,"due":"2026-07-17","evidence":"改简历约在2026-07-17"},
              {"no":4,"due":"2026-07-20","evidence":"第四件约2026-07-20"},
              {"no":5,"due":"2026-07-21","evidence":"第五件约2026-07-21"}
            ]}
        """.trimIndent()
        val v = PromiseReconciliation.parseAndVerify(raw, datesList, datesMaterial, zone)
        assertEquals(3, v.dates.size)
        assertEquals(listOf("u1", "u2", "u4"), v.dates.map { it.promiseUuid })
    }
}
