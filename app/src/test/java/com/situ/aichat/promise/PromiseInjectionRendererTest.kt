package com.situ.aichat.promise

import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import com.situ.aichat.data.local.entity.PromiseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 【我们的约定】注入块渲染纯逻辑（记忆改造一期·部件①·图纸 §3.3-B / T1-1/2/3）。断言从图纸 §3.3-B/§5 独立反推、
 * 逐字节比对锁定文本——排序 / 软上限 / 年龄标签（本地日历日差·E19）/ 到期后缀三态（E14）/ 已结窗过滤 / 指引行。
 */
class PromiseInjectionRendererTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai") // UTC+8 无 DST·确定性
    private val guidance =
        "以上是你们正式定下的约定清单，你都记得。拖了很久或快到日子的那件，可以在合适的时机自然地主动提一句；" +
            "同一件事别反复念叨，一次也别罗列好几件。不要把这份清单或「【我们的约定】」这个标题原样抄进回复。"

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 7, 10, 12, 0)

    // open 行形态：行首 `{n}. `（2026-09-06 约定工具调用化 §9①）——已结行仍是 `- `，故此正则也把两组分开。
    private val OPEN_LINE = Regex("""^\d+\. """)

    private fun open(
        uuid: String,
        content: String,
        created: Long,
        due: Long? = null,
        source: String = PromiseSource.CHAT,
    ) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = content, statusRaw = PromiseStatus.OPEN,
        dueAtMillis = due, sourceRaw = source, createdAtMillis = created, updatedAtMillis = created,
    )

    private fun resolved(uuid: String, content: String, status: String, resolvedAt: Long) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = content, statusRaw = status,
        resolvedAtMillis = resolvedAt, resolutionEvidence = "证据", createdAtMillis = 0L, updatedAtMillis = resolvedAt,
    )

    // ── T1-1 ──

    @Test fun empty_returnsEmptyString() {
        assertEquals("", PromiseInjectionRenderer.render(emptyList(), now, zone))
    }

    @Test fun titleAndGuidance_byteExact_openOnly() {
        val out = PromiseInjectionRenderer.render(
            listOf(open("p1", "一起去看画展", at(2026, 7, 10, 8, 0))),
            now, zone,
        )
        assertEquals(
            "【我们的约定】\n" +
                "1. 2026-07-10（今天·聊天中）定下：一起去看画展\n" +
                guidance,
            out,
        )
    }

    @Test fun resolvedGroup_headerAndLines_byteExact() {
        val out = PromiseInjectionRenderer.render(
            listOf(
                open("p1", "一起去看画展", at(2026, 7, 9, 8, 0)),
                resolved("r1", "帮忙改简历", PromiseStatus.FULFILLED, at(2026, 7, 9, 20, 0)),
                resolved("r2", "周末爬山", PromiseStatus.CANCELLED, at(2026, 7, 8, 10, 0)),
            ),
            now, zone,
        )
        assertEquals(
            "【我们的约定】\n" +
                "1. 2026-07-09（昨天·聊天中）定下：一起去看画展\n" +
                "（最近了结）\n" +
                "- 已兑现（7月9日）：帮忙改简历\n" +
                "- 已取消（7月8日）：周末爬山\n" +
                guidance,
            out,
        )
    }

    @Test fun sorting_dueFirstAscending_thenNullDueByCreatedAt() {
        val rows = listOf(
            open("n2", "无期约定二", at(2026, 7, 5, 9, 0)),
            open("d2", "有期二", at(2026, 7, 1, 9, 0), due = at(2026, 8, 20, 9, 0)),
            open("n1", "无期约定一", at(2026, 7, 3, 9, 0)),
            open("d1", "有期一", at(2026, 7, 2, 9, 0), due = at(2026, 8, 10, 9, 0)),
        )
        val out = PromiseInjectionRenderer.render(rows, now, zone)
        val lines = out.lines().filter { OPEN_LINE.containsMatchIn(it) }
        // due 升序在前（有期一 8/10 → 有期二 8/20），其后 null-due 按 createdAt 升序（无期一 7/3 → 无期二 7/5）。
        assertEquals(
            listOf("有期一", "有期二", "无期约定一", "无期约定二"),
            lines.map { it.substringAfter("定下：").substringBefore("（约在") },
        )
    }

    @Test fun softCap_open20_takesEarliestByCreatedAt() {
        val rows = (1..25).map { i -> open("p$i", "约定$i", at(2026, 6, 1, 0, 0) + i * 60_000L) }
        val out = PromiseInjectionRenderer.render(rows, now, zone)
        val openLines = out.lines().filter { OPEN_LINE.containsMatchIn(it) }
        assertEquals(20, openLines.size)
        // null-due 按 createdAt 升序 → 取最早 20（约定1..约定20）。
        assertTrue(openLines.first().startsWith("1. "))
        assertTrue(openLines.first().endsWith("约定1"))
        assertTrue(openLines.last().startsWith("20. "))
        assertTrue(openLines.last().endsWith("约定20"))
    }

    @Test fun softCap_resolved5_mostRecentByResolvedAt() {
        val rows = (1..7).map { i -> resolved("r$i", "了结$i", PromiseStatus.FULFILLED, now - i * 3_600_000L) }
        val out = PromiseInjectionRenderer.render(rows, now, zone)
        val resolvedLines = out.lines().filter { it.startsWith("- 已兑现") }
        assertEquals(5, resolvedLines.size)
        // resolvedAt 降序 → 最近 5（了结1 最新 … 了结5）。
        assertTrue(resolvedLines.first().endsWith("了结1"))
        assertTrue(resolvedLines.last().endsWith("了结5"))
    }

    @Test fun resolved_outsideSevenDayWindow_excluded() {
        val rows = listOf(
            resolved("rIn", "窗内", PromiseStatus.FULFILLED, now - 6L * 24 * 60 * 60 * 1000),
            resolved("rOut", "窗外", PromiseStatus.FULFILLED, now - 8L * 24 * 60 * 60 * 1000),
        )
        val out = PromiseInjectionRenderer.render(rows, now, zone)
        assertTrue("窗内应出现", out.contains("窗内"))
        assertTrue("窗外（>7 天）应被过滤", !out.contains("窗外"))
    }

    // ── T1-2 dueSuffix 三态 + E14 ──

    @Test fun dueSuffix_none_whenNullDue() {
        val out = PromiseInjectionRenderer.render(listOf(open("p1", "闲约", at(2026, 7, 10, 8, 0))), now, zone)
        assertTrue(out.contains("定下：闲约\n"))
    }

    @Test fun dueSuffix_future_yueZai() {
        val out = PromiseInjectionRenderer.render(
            listOf(open("p1", "看展", at(2026, 7, 10, 8, 0), due = at(2026, 7, 20, 9, 0))),
            now, zone,
        )
        assertTrue(out.contains("定下：看展（约在7月20日）"))
    }

    @Test fun dueSuffix_dueTodayCountsAsFuture() {
        val out = PromiseInjectionRenderer.render(
            listOf(open("p1", "今天见", at(2026, 7, 10, 8, 0), due = at(2026, 7, 10, 15, 0))),
            now, zone,
        )
        assertTrue("due 本地日 == now 本地日 → 约在", out.contains("定下：今天见（约在7月10日）"))
    }

    @Test fun dueSuffix_past_yiGuo_e14() {
        val out = PromiseInjectionRenderer.render(
            listOf(open("p1", "早该做", at(2026, 7, 1, 8, 0), due = at(2026, 7, 5, 9, 0))),
            now, zone,
        )
        assertTrue("过期 due → 原定…已过", out.contains("定下：早该做（原定7月5日，已过）"))
    }

    // ── T1-3 年龄标签（本地日历日差·E19） ──

    @Test fun ageLabel_today_yesterday_nDaysAgo() {
        val out = PromiseInjectionRenderer.render(
            listOf(
                open("t", "今约", at(2026, 7, 10, 6, 0)),
                open("y", "昨约", at(2026, 7, 9, 6, 0)),
                open("n", "旧约", at(2026, 7, 3, 6, 0)),
            ),
            now, zone,
        )
        assertTrue(out.contains("（今天·聊天中）定下：今约"))
        assertTrue(out.contains("（昨天·聊天中）定下：昨约"))
        assertTrue(out.contains("（7天前·聊天中）定下：旧约"))
    }

    @Test fun ageLabel_crossMidnight_isCalendarDayNot24h_e19() {
        // now = 07-10 00:30；约定 07-09 23:00（相差仅 1.5h，但跨了自然日）→ 应为「昨天」而非「今天」。
        val nowMidnight = at(2026, 7, 10, 0, 30)
        val out = PromiseInjectionRenderer.render(
            listOf(open("p1", "临界约", at(2026, 7, 9, 23, 0))),
            nowMidnight, zone,
        )
        assertTrue("跨午夜按日历日差 → 昨天", out.contains("（昨天·聊天中）定下：临界约"))
    }

    @Test fun openLine_meetingSource_rendersJianMianShi() {
        val out = PromiseInjectionRenderer.render(
            listOf(open("p1", "见面约", at(2026, 7, 10, 8, 0), source = PromiseSource.MEETING_BACKFILL)),
            now, zone,
        )
        assertTrue(out.contains("（今天·见面时）定下：见面约"))
    }

    // ── 三期抽取的单源纯函数直测（图纸 §3.6 / §7 T1-1）——注入用例上方一字不改守字节级不变，这里补直测 ──

    @Test fun sortedOpen_filtersOpenOnly_dueAscendingThenCreatedAscending() {
        val rows = listOf(
            resolved("r1", "已结项", PromiseStatus.FULFILLED, at(2026, 7, 9, 20, 0)),
            open("n2", "无期二", at(2026, 7, 5, 9, 0)),
            open("d2", "有期二", at(2026, 7, 1, 9, 0), due = at(2026, 8, 20, 9, 0)),
            open("n1", "无期一", at(2026, 7, 3, 9, 0)),
            open("d1", "有期一", at(2026, 7, 2, 9, 0), due = at(2026, 8, 10, 9, 0)),
        )
        val sorted = PromiseInjectionRenderer.sortedOpen(rows)
        // resolved 被过滤；due 升序在前（有期一 8/10 → 有期二 8/20），其后 no-due 按 created 升序（无期一 7/3 → 无期二 7/5）。
        assertEquals(listOf("有期一", "有期二", "无期一", "无期二"), sorted.map { it.content })
    }

    @Test fun isDueUpcoming_todayTrue_yesterdayFalse_byLocalDate() {
        // due 今天 15:00（晚于 now 12:00）→ true：判据是本地日历日而非毫秒差（E11/E19）。
        assertTrue(PromiseInjectionRenderer.isDueUpcoming(at(2026, 7, 10, 15, 0), now, zone))
        // due 昨天 → false。
        assertTrue(!PromiseInjectionRenderer.isDueUpcoming(at(2026, 7, 9, 23, 0), now, zone))
        // 跨午夜：now = 07-10 00:30，due = 07-09 23:00（相差仅 1.5h 但跨了自然日）→ false。
        val nowMidnight = at(2026, 7, 10, 0, 30)
        assertTrue(!PromiseInjectionRenderer.isDueUpcoming(at(2026, 7, 9, 23, 0), nowMidnight, zone))
        // due 恰今天 00:00 vs now 00:30 → true（同本地日）。
        assertTrue(PromiseInjectionRenderer.isDueUpcoming(at(2026, 7, 10, 0, 0), nowMidnight, zone))
    }

    // ── T1-12（2026-09-06 约定工具调用化）：编号 = resolve_promise.no 的单源，渲染端与映射端必须同序 ──

    @Test fun numberedOpen_matchesRenderedNumbering_andCapsAt20() {
        val rows = (1..25).map { i -> open("p$i", "约定$i", at(2026, 6, 1, 0, 0) + i * 60_000L) } +
            listOf(
                open("d1", "有期一", at(2026, 7, 2, 9, 0), due = at(2026, 8, 10, 9, 0)),
                resolved("r1", "已了结的", PromiseStatus.FULFILLED, now - 3_600_000L),
            )
        val numbered = PromiseInjectionRenderer.numberedOpen(rows)
        assertEquals(PromiseInjectionRenderer.OPEN_CAP, numbered.size)
        // 渲染出的每一行 `{n}. …定下：{content}` 的第 n 条内容，必须等于 numberedOpen[n-1] 的内容（同序单源）。
        val renderedContents = PromiseInjectionRenderer.render(rows, now, zone).lines()
            .filter { OPEN_LINE.containsMatchIn(it) }
            .mapIndexed { i, line ->
                assertEquals("第 ${i + 1} 行编号", "${i + 1}. ", OPEN_LINE.find(line)!!.value)
                line.substringAfter("定下：").substringBefore("（约在")
            }
        assertEquals(numbered.map { it.content }, renderedContents)
        // 已了结的那条既不进编号也不进 open 行。
        assertTrue(numbered.none { it.content == "已了结的" })
    }
}
