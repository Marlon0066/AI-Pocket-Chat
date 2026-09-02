package com.situ.aichat.prompt.growth

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * 宽松整数解析单源（活人感内核修缮卷 §3.6 · D-9 / D-13）：成长分析与人设编译两条链的 LLM 响应里，
 * 数值字段时常来成 `2.6` / `"3"` / `{"pos":3,"neg":1}` 这类形状——kotlinx 严格 `Int` 一个字段坏就整份判废（分析白跑、编译白跑）。
 *
 * 认四种形状：整数 · 小数（四舍五入）· 数字串（含小数串）· `{pos, neg}` 对象（取差）；其它 ⇒ `null`（调用方按项丢弃、不判废）。
 * `NaN` / `Infinity` 串 ⇒ null（`roundToInt` 对 NaN 抛）。
 */
internal fun JsonElement?.intLenient(): Int? = when (this) {
    is JsonPrimitive -> intOrNull
        ?: doubleOrNull?.takeIf { it.isFinite() }?.roundToInt()
        ?: content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()
    is JsonObject -> if ("pos" in this || "neg" in this) (this["pos"].intLenient() ?: 0) - (this["neg"].intLenient() ?: 0) else null
    else -> null
}
