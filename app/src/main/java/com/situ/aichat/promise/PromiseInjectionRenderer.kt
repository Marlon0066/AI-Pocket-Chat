package com.situ.aichat.promise

import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import com.situ.aichat.data.local.entity.PromiseStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 承诺账本 → 【我们的约定】注入块渲染（记忆改造一期·部件①·图纸 §3.3-B）。**全纯函数**——不碰 DB / 网络 /
 * 协程（落库归 [PromiseLedgerService]，取数归 [com.situ.aichat.data.repository.PromiseRepository]）。
 *
 * 硬编码中文（照 [com.situ.aichat.offline.OfflineMeetingMemoryRenderer] 范式，不走字符串资源——与
 * [com.situ.aichat.prompt.DirtyMessageDetector] 的 `【我们的约定】` 字面强耦合要求单一真源；i18n Phase 1 挂起）。
 *
 * ⚠️ **强耦合（图纸 §3.3-D）**：标题行 `【我们的约定】` 是 [com.situ.aichat.prompt.DirtyMessageDetector]
 * `matchesPromiseLedgerRepeat` 与 `pb_mem_format_ban` 两语言枚举的单一真源——改标题字面须同步三处。
 */
object PromiseInjectionRenderer {

    /** 进行中约定注入软上限（图纸 §3.3-B）。 */
    const val OPEN_CAP = 20

    /** 已了结约定注入上限（图纸 §3.3-B）。 */
    const val RESOLVED_CAP = 5

    /** 已了结约定注入窗口（7 天·图纸 §3.3-B）。 */
    const val RESOLVED_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    // 只含数字/字面量、无 locale 敏感符号 → Locale.ROOT 恒输出 ASCII 数字（照 DateFormatters 范式·防非拉丁数字设备）。
    private val ymdFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M'月'd'日'", Locale.ROOT)

    /**
     * [rows] = 注入候选（进行中全量 + 最近 7 天已了结·由 Repository 取全集）；[now] = 当前 epoch millis；[zone] = 时区。
     * 两组皆空 → 返回 ""。选择 / 排序 / 软上限 / 年龄标签 / 到期后缀 / 指引行全在此完成（图纸 §3.3-B 锁定）。
     */
    fun render(rows: List<PromiseEntity>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        // open 组：dueAtMillis 非空者在前按 dueAtMillis 升序，其后 null-due 按 createdAtMillis 升序；软上限取前 20。
        val open = numberedOpen(rows)

        // 已结组：statusRaw != open 且 resolvedAtMillis ≥ now − 7 天，按 resolvedAtMillis 降序取前 5。
        val cutoff = now - RESOLVED_WINDOW_MS
        val resolved = rows
            .filter { it.statusRaw != PromiseStatus.OPEN && (it.resolvedAtMillis ?: Long.MIN_VALUE) >= cutoff }
            .sortedByDescending { it.resolvedAtMillis }
            .take(RESOLVED_CAP)

        if (open.isEmpty() && resolved.isEmpty()) return ""

        val lines = mutableListOf<String>()
        lines.add("【我们的约定】")
        open.forEachIndexed { i, p -> lines.add("${i + 1}. " + renderOpenLine(p, now, zone)) }
        if (resolved.isNotEmpty()) {
            lines.add("（最近了结）")
            for (p in resolved) lines.add(renderResolvedLine(p, zone))
        }
        lines.add(GUIDANCE)
        return lines.joinToString("\n")
    }

    /**
     * open 行**行首编号之后**的部分：`{yyyy-MM-dd}（{age}·{src}）定下：{content}{dueSuffix}`。
     * 编号前缀 `{n}. ` 由 [render] 按 [numberedOpen] 的顺序拼（2026-09-06 约定工具调用化：编号是
     * [com.situ.aichat.promise.PromiseChatTool] `resolve_promise.no` 的语义来源）。
     */
    private fun renderOpenLine(p: PromiseEntity, now: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(p.createdAtMillis).atZone(zone).format(ymdFormatter)
        val age = ageLabel(p.createdAtMillis, now, zone)
        val src = if (p.sourceRaw == PromiseSource.CHAT) "聊天中" else "见面时"
        val dueSuffix = dueSuffix(p.dueAtMillis, now, zone)
        return "$date（$age·$src）定下：${p.content}$dueSuffix"
    }

    /** 已结行：`- 已兑现（{M月d日}）：{content}` / `- 已取消（{M月d日}）：{content}`（日期取 resolvedAtMillis）。 */
    private fun renderResolvedLine(p: PromiseEntity, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(p.resolvedAtMillis ?: 0L).atZone(zone).format(monthDayFormatter)
        val verb = if (p.statusRaw == PromiseStatus.FULFILLED) "已兑现" else "已取消"
        return "- $verb（$date）：${p.content}"
    }

    /** 年龄标签：与 now 同本地日 → 今天；差 1 日 → 昨天；否则 {N}天前（本地日历日差）。 */
    private fun ageLabel(createdMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val created = Instant.ofEpochMilli(createdMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (val diff = ChronoUnit.DAYS.between(created, today)) {
            0L -> "今天"
            1L -> "昨天"
            else -> "${diff}天前"
        }
    }

    /** 到期后缀：null → ""；due 本地日 ≥ now 本地日 → （约在{M月d日}）；否则 → （原定{M月d日}，已过）。 */
    private fun dueSuffix(dueMillis: Long?, nowMillis: Long, zone: ZoneId): String {
        dueMillis ?: return ""
        val md = Instant.ofEpochMilli(dueMillis).atZone(zone).format(monthDayFormatter)
        return if (isDueUpcoming(dueMillis, nowMillis, zone)) "（约在$md）" else "（原定$md，已过）"
    }

    /**
     * 本轮注入块里**带编号的 open 清单单源**（图纸 2026-09-06 约定工具调用化 §0.②-3）：`resolve_promise.no`
     * 的 no（1-based）即此列表下标 +1——渲染端（[render]）与映射端（`ChatPromiseToolHandler`）同一函数、
     * 同一份快照，不存在两处排序漂移。改这里的排序 / 上限即改工具语义。
     */
    fun numberedOpen(rows: List<PromiseEntity>): List<PromiseEntity> = sortedOpen(rows).take(OPEN_CAP)

    /** 进行中排序（单源·三期 UI 与注入共用）：due 非空在前按 due 升序，其后按 createdAtMillis 升序。 */
    fun sortedOpen(rows: List<PromiseEntity>): List<PromiseEntity> {
        val open = rows.filter { it.statusRaw == PromiseStatus.OPEN }
        val (withDue, noDue) = open.partition { it.dueAtMillis != null }
        return withDue.sortedBy { it.dueAtMillis } + noDue.sortedBy { it.createdAtMillis }
    }

    /** 到期未过判据（单源）：due 本地日 ≥ now 本地日（与 [dueSuffix] 同一判据·E19 本地日历日）。 */
    fun isDueUpcoming(dueMillis: Long, nowMillis: Long, zone: ZoneId): Boolean {
        val dueDay = Instant.ofEpochMilli(dueMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return !dueDay.isBefore(today)
    }

    /** 指引行（图纸 §3.3-B 锁定逐字）。 */
    private const val GUIDANCE =
        "以上是你们正式定下的约定清单，你都记得。拖了很久或快到日子的那件，可以在合适的时机自然地主动提一句；" +
            "同一件事别反复念叨，一次也别罗列好几件。不要把这份清单或「【我们的约定】」这个标题原样抄进回复。"
}
