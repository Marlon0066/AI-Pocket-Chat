package com.situ.aichat.data.worldbook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 世界书解析的 JSON 宽容读工具（WB2·同包顶层函数）。社区文件来源杂（手编 / 各版本酒馆 / 第三方工具），
 * 数值可能带小数（100.0）、布尔可能写成 0/1、字段可能缺失——一律宽容降级、绝不因类型小瑕疵拒整本书。
 */

/** 宽容取整数：数字（含小数）→ 取整；布尔 → 1/0；数字字符串 → 取整；其余/缺失 → null。 */
internal fun JsonObject.lenientInt(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    p.doubleOrNull?.let { return it.toInt() }
    p.booleanOrNull?.let { return if (it) 1 else 0 }
    return p.content.toDoubleOrNull()?.toInt()
}

/** 宽容取布尔：布尔 → 原值；数字 → ≠0；"true"/"false" 字符串 → 对应值；其余/缺失 → null。 */
internal fun JsonObject.lenientBool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    p.booleanOrNull?.let { return it }
    p.doubleOrNull?.let { return it != 0.0 }
    return when (p.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** 宽容取字符串：任意基元的 content（JsonNull 除外）；缺失/非基元 → null。 */
internal fun JsonObject.lenientString(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

/** 宽容取字符串数组：数组内基元取 content（非基元项跳过）；缺失/非数组 → null。 */
internal fun JsonObject.lenientStringList(key: String): List<String>? {
    val arr = this[key] as? JsonArray ?: return null
    return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
}

/** 判断是否为 ST `/正则/flags` 形态关键词（含首尾双斜杠，内部量词如 `{1,3}` 的逗号不可拆分）。 */
internal fun isRegexKeywordForm(raw: String): Boolean {
    val trimmed = raw.trim()
    return trimmed.length > 2 && trimmed.startsWith("/") && trimmed.lastIndexOf('/') > 0
}

/**
 * 拆分单个关键词输入串：正则形态整串保留（trim 后返回单元素）；普通形态按英文 `,` / 中文 `，` 切分、trim 去空。
 * 导入解析与条目编辑器输入共用此单一事实源。
 */
internal fun splitKeywordTokens(raw: String): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (isRegexKeywordForm(trimmed)) return listOf(trimmed)
    return trimmed.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
}

/** 宽容读关键词列表：兼容标准 JSON 数组（自动拆非正则逗号串）与单字符串形态；缺失/null → null。 */
internal fun JsonObject.lenientKeywordList(key: String): List<String>? = when (val el = this[key]) {
    is JsonArray -> el.flatMap { item ->
        val content = (item as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return@flatMap emptyList()
        splitKeywordTokens(content)
    }
    is JsonPrimitive -> if (el is JsonNull) null else splitKeywordTokens(el.content)
    else -> null
}

/**
 * delayUntilRecursion 双形态（ST 老导出 = 布尔、新导出 = 递归层级数字，契约 §2.1）：
 * 布尔 true → 1 / false → 0；数字 → 取整；缺失 → 0。
 */
internal fun delayUntilRecursionValue(el: JsonElement?): Int {
    val p = el as? JsonPrimitive ?: return 0
    p.booleanOrNull?.let { return if (it) 1 else 0 }
    return p.doubleOrNull?.toInt() ?: 0
}

/** 把对象里未被消费的字段整包收进 extraJson（round-trip 保底；无剩余 → ""）。 */
internal fun extrasFrom(obj: JsonObject, consumedKeys: Set<String>): String {
    val rest = obj.filterKeys { it !in consumedKeys }
    if (rest.isEmpty()) return ""
    return JsonObject(rest).toString()
}

/** extraJson → JsonObject（"" / 解析失败 → 空对象，绝不因脏 extra 拒导出）。 */
internal fun parseExtras(extraJson: String): JsonObject {
    if (extraJson.isBlank()) return JsonObject(emptyMap())
    return try {
        Json.parseToJsonElement(extraJson) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

/** List<String> → 规范 JSON 数组文本（keysJson / secondaryKeysJson 的存储形态）。 */
internal fun encodeStringList(list: List<String>): String =
    JsonArray(list.map { JsonPrimitive(it) }).toString()

/** keysJson / secondaryKeysJson 存储文本 → List<String>（脏数据宽容为空表）。 */
internal fun decodeStringList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
    } catch (_: Exception) {
        emptyList()
    }
}
