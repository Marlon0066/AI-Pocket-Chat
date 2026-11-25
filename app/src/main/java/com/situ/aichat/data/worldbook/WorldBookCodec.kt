package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 世界书格式编解码门面（WB2·契约 `FABLE5_WORLDBOOK_PROPOSAL.md` §3）。
 *
 * 吃三种输入（自动识别，§3.1）：
 * 1. 酒馆独立世界书 JSON：`{"entries": {"<uid>": {...}}}`——entries 是**对象**；
 * 2. 角色卡 V2/V3 完整卡：`{"spec":"chara_card_v2","data":{"character_book":{...}}}`；
 * 3. 裸 character_book 对象：`{"entries":[...]}`——entries 是**数组**。
 *
 * 吐一种输出（§3.2）：酒馆独立世界书 JSON（生态通用语）。
 * round-trip 承诺：导入 → 导出后已知字段逐字段一致 + 未知字段原样保留（extraJson 机制，
 * 由 [WorldBookCodecTest] 锁定）；唯一例外 = 导出会把缺省字段按 §2.1 默认值补全（白名单行为，契约已注明）。
 *
 * 解析失败一律抛 [WorldBookParseException]，message 是给用户看的人话（WB6 直接展示）。
 */
object WorldBookCodec {

    /** 解析结果：书 + 条目（uuid 已生成、bookUuid 已挂）+ 识别出的格式 + 跳过的坏条目数。 */
    data class ParsedWorldBook(
        val book: WorldBookEntity,
        val entries: List<WorldBookEntryEntity>,
        val format: WorldBookFormat,
        val skippedEntryCount: Int,
    )

    enum class WorldBookFormat { ST_STANDALONE, CHARACTER_CARD, CHARACTER_BOOK }

    /**
     * @param fallbackName 书名兜底（通常传文件名去后缀）；文件里有 name 时优先用文件里的。
     */
    fun parse(jsonText: String, fallbackName: String): ParsedWorldBook {
        val cleanedJson = jsonText.trimStart('\uFEFF')
        val root = try {
            Json.parseToJsonElement(cleanedJson)
        } catch (e: Exception) {
            throw WorldBookParseException("这不是有效的 JSON 文件，可能选错了文件", e)
        }
        val rootObj = root as? JsonObject
            ?: throw WorldBookParseException("文件内容不是 JSON 对象，没认出这是世界书")

        val data = rootObj["data"] as? JsonObject
        return when {
            // 完整角色卡（V2/V3）：世界书内嵌在 data.character_book
            data != null && looksLikeCard(rootObj) -> {
                val bookObj = data["character_book"] as? JsonObject
                    ?: throw WorldBookParseException("这张角色卡里没有内嵌世界书（character_book 为空）")
                WorldBookCardParser.parse(bookObj, cardCharacterName(data), fallbackName)
                    .copy(format = WorldBookFormat.CHARACTER_CARD)
            }
            // 裸 character_book 对象：entries 是数组
            rootObj["entries"] is JsonArray ->
                WorldBookCardParser.parse(rootObj, cardCharacterName = null, fallbackName = fallbackName)
            // 酒馆独立世界书：entries 是对象
            rootObj["entries"] is JsonObject -> WorldBookStParser.parse(rootObj, fallbackName)
            looksLikeCard(rootObj) ->
                throw WorldBookParseException("这张角色卡里没有内嵌世界书（character_book 为空）")
            else -> throw WorldBookParseException(
                "没认出这是酒馆世界书：既没有世界书的 entries 结构，也不是内嵌世界书的角色卡",
            )
        }
    }

    /** 导出为酒馆独立世界书 JSON（§3.2）。 */
    fun exportToStJson(book: WorldBookEntity, entries: List<WorldBookEntryEntity>): String =
        WorldBookExporter.export(book, entries)

    private fun looksLikeCard(rootObj: JsonObject): Boolean =
        rootObj.lenientString("spec")?.startsWith("chara_card") == true || rootObj["data"] is JsonObject

    private fun cardCharacterName(data: JsonObject): String? =
        data.lenientString("name")?.takeIf { it.isNotBlank() }
}

/** 解析失败（message = 给用户看的人话）。 */
class WorldBookParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
