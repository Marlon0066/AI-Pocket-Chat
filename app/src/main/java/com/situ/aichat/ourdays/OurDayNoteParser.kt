package com.situ.aichat.ourdays

import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 手记产物（总图纸 §3.4）：[note] 给人看（第一人称）/ [factLine] 给 TA 读（双名第三人称·不含日期前缀）。 */
data class NoteResult(val note: String, val factLine: String)

/** 解析结果：成功带 [NoteResult]，失败带中文原因（只进 Logcat·不进提示词）。 */
internal sealed class NoteParse {
    data class Success(val result: NoteResult) : NoteParse()
    data class Failure(val reason: String) : NoteParse()
}

/**
 * 手记 JSON 解析 + 校验（总图纸 §3.4 锁定·范式 F24）：剥思考 → 两候选（原文 trim / [JSONExtractor.extract]）逐个解成
 * JsonObject → `stringField`（须为 JSON string 原语）→ 校验。任一不满足 = 本次失败（调用方计 1 次 attempt）。
 */
internal object OurDayNoteParser {

    const val NOTE_MIN = 40
    const val NOTE_MAX = 600
    const val FACT_LINE_MIN = 8
    const val FACT_LINE_MAX = 120

    /**
     * factLine 若以 `[` 或日期样式开头则先剥去前缀（总图纸 §3.4 逐字正则 · R1 🟡-1 修订）：
     * 方括号形态吃到右括号为止（含「[2026-08-22 周六] 」这种卷二注入行原样回写·O-5），裸日期形态吃到首个空白。
     */
    private val DATE_PREFIX = Regex("""^(?:\[\d{4}-\d{1,2}-\d{1,2}[^\]]*\]|\d{4}-\d{1,2}-\d{1,2}\S*)\s*""")

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): NoteParse {
        val cleaned = MemoryService.strippingThinkingTags(raw)
        val obj = listOf(cleaned.trim(), JSONExtractor.extract(cleaned))
            .firstNotNullOfOrNull { candidate -> runCatching { json.parseToJsonElement(candidate) }.getOrNull() as? JsonObject }
            ?: return NoteParse.Failure("输出不是合法的 JSON 对象")

        val note = stringField(obj, "note")?.trim() ?: return NoteParse.Failure("缺少 note 字段")
        val rawFactLine = stringField(obj, "factLine")?.trim() ?: return NoteParse.Failure("缺少 factLine 字段")
        val factLine = rawFactLine.replaceFirst(DATE_PREFIX, "").trim()

        if (note.isEmpty()) return NoteParse.Failure("note 为空")
        if (factLine.isEmpty()) return NoteParse.Failure("factLine 为空")
        val noteLen = note.codePointCount(0, note.length)
        if (noteLen < NOTE_MIN) return NoteParse.Failure("note 太短（$noteLen 字·需 $NOTE_MIN–$NOTE_MAX）")
        if (noteLen > NOTE_MAX) return NoteParse.Failure("note 太长（$noteLen 字·需 $NOTE_MIN–$NOTE_MAX）")
        if (factLine.contains('\n') || factLine.contains('\r')) return NoteParse.Failure("factLine 含换行")
        val factLen = factLine.codePointCount(0, factLine.length)
        if (factLen < FACT_LINE_MIN) return NoteParse.Failure("factLine 太短（$factLen 字·需 $FACT_LINE_MIN–$FACT_LINE_MAX）")
        if (factLen > FACT_LINE_MAX) return NoteParse.Failure("factLine 太长（$factLen 字·需 $FACT_LINE_MIN–$FACT_LINE_MAX）")
        if (!GeneratedContentValidator.isLikelyValid(note)) return NoteParse.Failure("note 疑似非正文")
        return NoteParse.Success(NoteResult(note, factLine))
    }

    /** JSON 字符串字段（须为 JSON string 原语，否则视为缺失·照 OfflineMeetingSummarySchema）。 */
    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
