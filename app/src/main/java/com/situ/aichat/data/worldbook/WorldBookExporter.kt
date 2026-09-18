package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 导出为酒馆独立世界书 JSON（WB2·契约 §3.2）。
 * - 每条目按 ST `newWorldInfoEntryDefinition` 全字段输出（缺省字段补 §2.1 默认值 = 契约注明的白名单行为；
 *   可空字段与酒馆导出文件一致，输出显式 null）；
 * - extraJson（addMemo / outletName / triggers / characterFilter / 未知字段…）原样还原，已占用键不覆盖；
 * - entries 以 uid 为键（解析端已保证 uid 去重）、按 displayIndex 排序输出；
 * - 书级 name/description/scan_depth/token_budget/recursive_scanning 有值才输出 + 书级 extraJson 还原。
 */
internal object WorldBookExporter {

    fun export(book: WorldBookEntity, entries: List<WorldBookEntryEntity>): String {
        val root = linkedMapOf<String, JsonElement>()
        if (book.name.isNotBlank()) root["name"] = JsonPrimitive(book.name)
        if (book.description.isNotBlank()) root["description"] = JsonPrimitive(book.description)
        book.scanDepth?.let { root["scan_depth"] = JsonPrimitive(it) }
        book.tokenBudget?.let { root["token_budget"] = JsonPrimitive(it) }
        book.recursiveScanning?.let { root["recursive_scanning"] = JsonPrimitive(it) }
        parseExtras(book.extraJson).forEach { (k, v) ->
            if (k != "entries" && k !in root) root[k] = v
        }

        val entryMap = linkedMapOf<String, JsonElement>()
        entries.sortedWith(compareBy({ it.displayIndex }, { it.uid })).forEach { e ->
            entryMap[e.uid.toString()] = entryJson(e)
        }
        root["entries"] = JsonObject(entryMap)
        return JsonObject(root).toString()
    }

    private fun entryJson(e: WorldBookEntryEntity): JsonObject {
        val extras = parseExtras(e.extraJson)
        val f = linkedMapOf<String, JsonElement>()
        f["uid"] = JsonPrimitive(e.uid)
        f["key"] = JsonArray(decodeStringList(e.keysJson).map { JsonPrimitive(it) })
        f["keysecondary"] = JsonArray(decodeStringList(e.secondaryKeysJson).map { JsonPrimitive(it) })
        f["comment"] = JsonPrimitive(e.comment)
        f["content"] = JsonPrimitive(e.content)
        f["constant"] = JsonPrimitive(e.constant)
        f["vectorized"] = JsonPrimitive(e.vectorized)
        f["selective"] = JsonPrimitive(e.selective)
        f["selectiveLogic"] = JsonPrimitive(e.selectiveLogic)
        // addMemo 非列：round-trip 用 extras 原值；自建条目按 ST 习惯 = 有标题即 true。
        f["addMemo"] = extras["addMemo"] ?: JsonPrimitive(e.comment.isNotEmpty())
        f["order"] = JsonPrimitive(e.insertionOrder)
        f["position"] = JsonPrimitive(e.position)
        f["depth"] = JsonPrimitive(e.depth)
        f["role"] = JsonPrimitive(e.role)
        f["disable"] = JsonPrimitive(!e.enabled)
        f["ignoreBudget"] = JsonPrimitive(e.ignoreBudget)
        f["excludeRecursion"] = JsonPrimitive(e.excludeRecursion)
        f["preventRecursion"] = JsonPrimitive(e.preventRecursion)
        f["delayUntilRecursion"] = JsonPrimitive(e.delayUntilRecursion)
        f["probability"] = JsonPrimitive(e.probability)
        f["useProbability"] = JsonPrimitive(e.useProbability)
        f["group"] = JsonPrimitive(e.groupName)
        f["groupOverride"] = JsonPrimitive(e.groupOverride)
        f["groupWeight"] = JsonPrimitive(e.groupWeight)
        f["useGroupScoring"] = e.useGroupScoring.toJsonOrNull()
        f["scanDepth"] = e.scanDepth.toJsonOrNull()
        f["caseSensitive"] = e.caseSensitive.toJsonOrNull()
        f["matchWholeWords"] = e.matchWholeWords.toJsonOrNull()
        f["sticky"] = e.sticky.toJsonOrNull()
        f["cooldown"] = e.cooldown.toJsonOrNull()
        f["delay"] = e.delay.toJsonOrNull()
        f["outletName"] = extras["outletName"] ?: JsonPrimitive("")
        f["automationId"] = extras["automationId"] ?: JsonPrimitive("")
        f["triggers"] = extras["triggers"] ?: JsonArray(emptyList())
        f["matchPersonaDescription"] = extras["matchPersonaDescription"] ?: JsonPrimitive(false)
        f["matchCharacterDescription"] = extras["matchCharacterDescription"] ?: JsonPrimitive(false)
        f["matchCharacterPersonality"] = extras["matchCharacterPersonality"] ?: JsonPrimitive(false)
        f["matchCharacterDepthPrompt"] = extras["matchCharacterDepthPrompt"] ?: JsonPrimitive(false)
        f["matchScenario"] = extras["matchScenario"] ?: JsonPrimitive(false)
        f["matchCreatorNotes"] = extras["matchCreatorNotes"] ?: JsonPrimitive(false)
        f["displayIndex"] = JsonPrimitive(e.displayIndex)
        extras.forEach { (k, v) -> if (k !in f) f[k] = v }
        return JsonObject(f)
    }

    private fun Int?.toJsonOrNull(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull

    private fun Boolean?.toJsonOrNull(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull
}
