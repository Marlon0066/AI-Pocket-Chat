package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 角色卡 V2/V3 内嵌世界书（character_book）解析（WB2·契约 §3.1-2）。
 * 映射**与酒馆导入角色卡世界书的结果保持一致**（参照其 `convertCharacterBook()` 行为·2026-07-02 核对）：
 * - 卡条目的 ST 专属设置藏在 `entry.extensions` 里（snake_case）：`extensions.position` 数字覆盖
 *   `position: 'before_char'/'after_char'` 字符串、probability/depth/selectiveLogic/sticky… 全从 extensions 读；
 * - **卡格式独有默认值分叉**：`selective` 缺省 = false（独立格式 = true）、`position` 缺省 = after(1)；
 * - `extensions` 整包保留（与酒馆一致——它导入后也把 extensions 原样挂回条目上）；
 *   卡独有字段（priority / name / 条目级 case_sensitive——ST 转换器同样忽略它）与未知字段一并进 extraJson 不丢。
 */
internal object WorldBookCardParser {

    private val CONSUMED_BOOK_KEYS = setOf(
        "entries", "name", "description", "scan_depth", "token_budget", "recursive_scanning",
    )
    private val CONSUMED_ENTRY_KEYS = setOf(
        "id", "keys", "secondary_keys", "comment", "content", "constant", "selective",
        "insertion_order", "enabled", "position", "extensions",
    )

    fun parse(bookObj: JsonObject, cardCharacterName: String?, fallbackName: String): WorldBookCodec.ParsedWorldBook {
        val book = WorldBookEntity(
            name = bookObj.lenientString("name")?.takeIf { it.isNotBlank() }
                ?: cardCharacterName?.let { "${it}的世界书" }
                ?: fallbackName,
            description = bookObj.lenientString("description") ?: "",
            scanDepth = bookObj.lenientInt("scan_depth"),
            tokenBudget = bookObj.lenientInt("token_budget"),
            recursiveScanning = bookObj.lenientBool("recursive_scanning"),
            extraJson = extrasFrom(bookObj, CONSUMED_BOOK_KEYS),
        )

        val entriesArr = bookObj["entries"] as? JsonArray ?: JsonArray(emptyList())
        var skipped = 0
        val entries = buildList {
            entriesArr.forEachIndexed { index, element ->
                val entryObj = element as? JsonObject
                if (entryObj == null) {
                    skipped++
                    return@forEachIndexed
                }
                add(mapCardEntry(entryObj, book.uuid, index))
            }
        }
        return WorldBookCodec.ParsedWorldBook(
            book = book,
            entries = WorldBookStEntryMapper.dedupeUids(entries),
            format = WorldBookCodec.WorldBookFormat.CHARACTER_BOOK,
            skippedEntryCount = skipped,
        )
    }

    private fun mapCardEntry(obj: JsonObject, bookUuid: String, index: Int): WorldBookEntryEntity {
        val ext = obj["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        return WorldBookEntryEntity(
            bookUuid = bookUuid,
            uid = obj.lenientInt("id") ?: index,
            displayIndex = ext.lenientInt("display_index") ?: index,
            keysJson = encodeStringList(obj.lenientStringList("keys") ?: emptyList()),
            secondaryKeysJson = encodeStringList(obj.lenientStringList("secondary_keys") ?: emptyList()),
            comment = obj.lenientString("comment") ?: "",
            content = obj.lenientString("content") ?: "",
            constant = obj.lenientBool("constant") ?: false,
            // 卡格式默认 false（与酒馆导入一致），≠ 独立格式默认 true。
            selective = obj.lenientBool("selective") ?: false,
            vectorized = ext.lenientBool("vectorized") ?: false,
            selectiveLogic = ext.lenientInt("selectiveLogic") ?: 0,
            enabled = obj.lenientBool("enabled") ?: true,
            insertionOrder = obj.lenientInt("insertion_order") ?: 100,
            position = ext.lenientInt("position")
                ?: if (obj.lenientString("position") == "before_char") 0 else 1,
            depth = ext.lenientInt("depth") ?: 4,
            role = ext.lenientInt("role") ?: 0,
            ignoreBudget = ext.lenientBool("ignore_budget") ?: false,
            probability = ext.lenientInt("probability") ?: 100,
            useProbability = ext.lenientBool("useProbability") ?: true,
            scanDepth = ext.lenientInt("scan_depth"),
            caseSensitive = ext.lenientBool("case_sensitive"),
            matchWholeWords = ext.lenientBool("match_whole_words"),
            useGroupScoring = ext.lenientBool("use_group_scoring"),
            excludeRecursion = ext.lenientBool("exclude_recursion") ?: false,
            preventRecursion = ext.lenientBool("prevent_recursion") ?: false,
            delayUntilRecursion = delayUntilRecursionValue(ext["delay_until_recursion"]),
            groupName = ext.lenientString("group") ?: "",
            groupOverride = ext.lenientBool("group_override") ?: false,
            groupWeight = ext.lenientInt("group_weight") ?: 100,
            sticky = ext.lenientInt("sticky"),
            cooldown = ext.lenientInt("cooldown"),
            delay = ext.lenientInt("delay"),
            extraJson = cardEntryExtras(obj, ext),
        )
    }

    /**
     * 卡条目的 extraJson（以 ST 独立格式字段名落库，导出即成 ST 形态）：
     * extensions 里的保留档字段转 ST 名；extensions 整包原样保留；卡独有/未知字段原样保留。
     */
    private fun cardEntryExtras(obj: JsonObject, ext: JsonObject): String {
        val extras = linkedMapOf<String, JsonElement>()
        ext["outlet_name"]?.let { extras["outletName"] = it }
        ext["automation_id"]?.let { extras["automationId"] = it }
        ext["triggers"]?.let { extras["triggers"] = it }
        ext["match_persona_description"]?.let { extras["matchPersonaDescription"] = it }
        ext["match_character_description"]?.let { extras["matchCharacterDescription"] = it }
        ext["match_character_personality"]?.let { extras["matchCharacterPersonality"] = it }
        ext["match_character_depth_prompt"]?.let { extras["matchCharacterDepthPrompt"] = it }
        ext["match_scenario"]?.let { extras["matchScenario"] = it }
        ext["match_creator_notes"]?.let { extras["matchCreatorNotes"] = it }
        if (ext.isNotEmpty()) extras["extensions"] = ext
        obj.forEach { (k, v) ->
            if (k !in CONSUMED_ENTRY_KEYS && k !in extras) extras[k] = v
        }
        return if (extras.isEmpty()) "" else JsonObject(extras).toString()
    }
}
