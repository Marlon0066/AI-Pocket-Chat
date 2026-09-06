package com.situ.aichat.promise

import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 承诺账本对账（记忆改造一期·部件②·图纸 §3.12 / 四期·§3.3-§3.4）：对账提示词构建 + JSON 宽容解析 +
 * 三路裁决（changes 四道闸 / new 附加闸 / 四期新增 dates 补日期五闸·独立丢弃不连坐）。
 * **全纯函数 object**——不碰 DB / 网络 / 协程（落库归 [PromiseLedgerService]，LLM 调用归
 * [com.situ.aichat.prompt.memory.MemoryDigestCoordinator]）。
 *
 * ⚠️ **自有强耦合（图纸 §6）**：[buildPrompt] 的 JSON schema ↔ [parseAndVerify] 的解析 DTO 必须同文件共存，
 * 改任一侧（字段名 / 结构）必须同步另一侧，否则静默破坏约定对账。金额守卫正则 / normalize 单源在 [PromiseLedgerService]。
 */
object PromiseReconciliation {

    /** 新约定一次最多提取条数（图纸 §3.12·锁定）。 */
    const val NEW_CAP = 3

    /** 补日期一次最多条数（记忆改造四期·图纸 §3.4·锁定）。 */
    const val DATES_CAP = 3

    /** 证据最短字数（去空白后·图纸 §3.12·闸二）。 */
    const val EVIDENCE_MIN_LEN = 6

    /** 新约定 content 最长字数（图纸 §3.12·新约定附加闸·垃圾防线）。 */
    const val NEW_CONTENT_MAX_LEN = 60

    private val reconJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // 无 locale 敏感符号 → Locale.ROOT 恒 ASCII 数字（照 DateFormatters 范式）。
    private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    // ── 对账提示词（§3.12 逐字·与 [parseAndVerify] 强耦合） ──

    /**
     * [charName]/[userName] 空则分别回退 "AI 角色"/"用户"；[open] 为进行中约定升序清单（编号 = 下标 +1·空则该段替换为
     * 「（当前清单为空）」）；[nowText] 由调用方按当前时间格式化传入；[materialText] = 对话 + 生活素材预格式化文本。
     */
    fun buildPrompt(
        charName: String,
        userName: String,
        nowText: String,
        open: List<PromiseEntity>,
        materialText: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val cName = charName.ifBlank { "AI 角色" }
        val uName = userName.ifBlank { "用户" }
        val listBlock = if (open.isEmpty()) {
            "（当前清单为空）"
        } else {
            open.mapIndexed { i, p ->
                // 记忆改造四期·§3.3：dueMark 让模型知道哪些条目「未定日期」需要补（任务 3）。
                val dueMark = if (p.dueAtMillis != null) "·约在 ${dateStr(p.dueAtMillis, zone)}" else "·未定日期"
                "${i + 1}. ${p.content}（${dateStr(p.createdAtMillis, zone)} 定下$dueMark）"
            }.joinToString("\n")
        }
        return "你在帮 AI 角色「$cName」维护一份与用户「$uName」之间的约定清单。请读下面的对话与生活素材，完成三件事：\n" +
            "1. 判断清单上哪些编号的约定已经兑现，或已经取消/告吹。判定必须谨慎：只有素材里有明确证据才输出，并把能证明的那句原话一字不差地抄进 evidence；拿不准的一律不要输出（维持原状）。\n" +
            "2. 找出素材里新出现的约定：双方明确说定、之后要一起做或某一方答应对方要做的具体事情，并把说定的那句原话抄进 evidence。随口一提没有下文的不算；金钱类承诺（发红包、转账、给多少钱、送多贵的礼物）不算约定，不要提取。清单上已经有的事（哪怕措辞不同）不要再当新约定输出。\n" +
            "3. 给清单上标着「未定日期」的约定补日期：只有素材里明确说定了具体日子才补，并把说定日子的那句原话抄进 evidence；拿不准的不要输出。\n" +
            "\n" +
            "当前时间：$nowText\n" +
            "\n" +
            "进行中的约定清单：\n" +
            listBlock + "\n" +
            "\n" +
            "只输出 JSON（不要代码块、不要解释）：\n" +
            "{\"changes\":[{\"no\":1,\"status\":\"fulfilled|cancelled\",\"evidence\":\"素材原话逐字引用\"}]," +
            "\"new\":[{\"content\":\"一句话概括，第三人称，提到两人时用他们的名字、不要写「用户」「角色」，不超过40字\",\"due\":\"能确定具体日期就输出 yyyy-MM-dd，确定不了就 null\",\"evidence\":\"素材原话逐字引用\"}]," +
            "\"dates\":[{\"no\":1,\"due\":\"yyyy-MM-dd\",\"evidence\":\"素材原话逐字引用\"}]}\n" +
            "\n" +
            "规则：没有变化就输出空数组；新约定一次最多提取 3 条；补日期只对标着「未定日期」的条目、一次最多 3 条；只依据下面给出的素材判断，不要编造。\n" +
            "\n" +
            "素材：\n" +
            materialText
    }

    // ── 宽容解析 + 四道闸（§3.12·逐条独立丢弃不连坐·照 OpenLoopScanService.parseScanResult） ──

    /**
     * 宽容解析：剥 `<think>` → [JSONExtractor] 取首 `{` 到末 `}` → 宽松反序列化 → DTO；整体解析失败抛
     * [PromiseReconcileParseException]。四道闸代码侧裁决（编号 / 证据 / status / 新约定附加闸）逐条独立丢弃；
     * no→uuid 映射据 [openList] 完成。
     */
    fun parseAndVerify(
        raw: String,
        openList: List<PromiseEntity>,
        materialText: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Verified {
        val stripped = MemoryService.strippingThinkingTags(raw)
        val jsonStr = JSONExtractor.extract(stripped)
        val dto = runCatching { reconJson.decodeFromString(ReconcileDto.serializer(), jsonStr) }.getOrNull()
            ?: throw PromiseReconcileParseException("约定对账 JSON 解析失败")
        val normMaterial = PromiseLedgerService.normalize(materialText)

        // changes：闸一（编号范围 + 同 no 只取首条）→ 闸三（status 白名单）→ 闸二（证据）。
        val seenNos = HashSet<Int>()
        val changes = dto.changes.mapNotNull { c ->
            if (c.no !in 1..openList.size) return@mapNotNull null // 闸一：越界丢弃
            if (!seenNos.add(c.no)) return@mapNotNull null // 同 no 重复只取首条
            if (c.status != PromiseStatus.FULFILLED && c.status != PromiseStatus.CANCELLED) return@mapNotNull null // 闸三
            if (!evidenceOk(c.evidence, normMaterial)) return@mapNotNull null // 闸二
            VerifiedChange(openList[c.no - 1].uuid, c.status, c.evidence)
        }

        // new：content 空白/超长丢弃 → 金额守卫 → 闸二证据 → due 解析（失败 null）；超 3 条截前 3。
        val newPromises = dto.new.mapNotNull { n ->
            val content = n.content.trim()
            if (content.isEmpty()) return@mapNotNull null
            if (content.codePointCount(0, content.length) > NEW_CONTENT_MAX_LEN) return@mapNotNull null
            if (PromiseLedgerService.AMOUNT_GUARD.containsMatchIn(content)) return@mapNotNull null
            if (!evidenceOk(n.evidence, normMaterial)) return@mapNotNull null
            VerifiedNew(content, parseDue(n.due, zone), n.evidence)
        }.take(NEW_CAP)

        // dates（记忆改造四期·补日期第三路·§3.4 五闸·独立 seenDateNos·逐条独立丢弃）：
        // 闸一（编号范围 + 同 no 只取首条）→ 闸二'（目标已有日期丢·只补空）→ 闸三（证据·同闸二）→
        // 闸四（parseDue 必须成功·dates 路 due 就是全部载荷）；超 3 条截前 3。
        val seenDateNos = HashSet<Int>()
        val dates = dto.dates.mapNotNull { d ->
            if (d.no !in 1..openList.size) return@mapNotNull null // 闸一：越界丢弃
            if (!seenDateNos.add(d.no)) return@mapNotNull null // 同 no 重复只取首条
            val target = openList[d.no - 1]
            if (target.dueAtMillis != null) return@mapNotNull null // 闸二'：只补空日期（改期不做）
            if (!evidenceOk(d.evidence, normMaterial)) return@mapNotNull null // 闸三：证据
            val due = parseDue(d.due, zone) ?: return@mapNotNull null // 闸四：due 必须解析成功
            VerifiedDate(target.uuid, due, d.evidence)
        }.take(DATES_CAP)

        return Verified(changes, newPromises, dates)
    }

    /**
     * 闸二：去空白后 <6 字 → 假；不是素材去空白后的子串 → 假。
     * `internal`（2026-09-06 约定工具调用化）：聊天内工具路的证据闸复用**同一实现**，不许另写一遍。
     */
    internal fun evidenceOk(evidence: String, normMaterial: String): Boolean {
        val ne = PromiseLedgerService.normalize(evidence)
        if (ne.codePointCount(0, ne.length) < EVIDENCE_MIN_LEN) return false
        return normMaterial.contains(ne)
    }

    /**
     * due 解析：null/空/"null" → null；否则按纯日期 `yyyy-MM-dd`（补 09:00·[zone]）；失败 → null。
     * `internal`（2026-09-06 约定工具调用化）：[PromiseChatTool] 的 `due` 参数复用**同一实现**，两路补 09:00 一致。
     */
    internal fun parseDue(raw: String?, zone: ZoneId): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
        return runCatching { LocalDate.parse(s).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun dateStr(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(YMD)

    // ── 对账产出结果类型（供 [PromiseLedgerService.applyReconciliation] 落库） ──

    /** 单条状态变更裁决（no→uuid 映射已完成）。[status] ∈ fulfilled | cancelled。 */
    data class VerifiedChange(val promiseUuid: String, val status: String, val evidence: String)

    /** 单条新约定裁决（金额守卫 / 长度 / due 解析已过闸）。[dueAtMillis] 解析失败 → null。 */
    data class VerifiedNew(val content: String, val dueAtMillis: Long?, val evidence: String)

    /**
     * 单条补日期裁决（记忆改造四期·§3.4·五闸已过·no→uuid 已映射）。[dueAtMillis] 必非空（闸四保证解析成功）；
     * [evidence] 只做闸门不落库（§3.5-③ 明令不写 resolutionEvidence）。
     */
    data class VerifiedDate(val promiseUuid: String, val dueAtMillis: Long, val evidence: String)

    /**
     * 对账裁决结果：状态变更 + 新约定 + 补日期。各路逐条独立丢弃不连坐（图纸 §3.12 / 四期 §3.4）。
     * [dates] 默认空 → [com.situ.aichat.prompt.memory.MemoryDigestCoordinator] 与既有构造零改（新增第三路纯 additive）。
     */
    data class Verified(
        val changes: List<VerifiedChange>,
        val newPromises: List<VerifiedNew>,
        val dates: List<VerifiedDate> = emptyList(),
    )

    // ── 内部序列化 DTO（⚠️ 与 buildPrompt 的 JSON schema 强耦合·改一侧同步另一侧） ──

    @Serializable
    private data class ReconcileDto(
        val changes: List<ChangeDto> = emptyList(),
        val new: List<NewDto> = emptyList(),
        val dates: List<DateDto> = emptyList(),
    )

    @Serializable
    private data class ChangeDto(val no: Int = 0, val status: String = "", val evidence: String = "")

    @Serializable
    private data class NewDto(val content: String = "", val due: String? = null, val evidence: String = "")

    @Serializable
    private data class DateDto(val no: Int = 0, val due: String? = null, val evidence: String = "")
}

/** 对账 JSON 整体解析失败（确定性错误·由 DigestCoordinator 据此重试一次后静默放弃本批）。 */
class PromiseReconcileParseException(message: String) : Exception(message)
