package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.json.JsonObject

/**
 * 酒馆独立世界书条目 → 实体的核心映射（WB2）。字段名与默认值按酒馆世界书格式
 * 对齐（契约 §2.1），默认值单源 = [WorldBookEntryEntity] 构造默认。
 * 未消费字段（addMemo / outletName / automationId / triggers / characterFilter / match* / extensions / 未知）
 * 整包进 extraJson，导出时原样还原。
 */
internal object WorldBookStEntryMapper {

    /** 映射进列（或由列推导）的 ST 字段——除此之外全部进 extraJson。 */
    val CONSUMED_KEYS = setOf(
        "uid", "key", "keysecondary", "comment", "content", "constant", "vectorized",
        "selective", "selectiveLogic", "order", "position", "depth", "role", "disable",
        "probability", "useProbability", "scanDepth", "caseSensitive", "matchWholeWords",
        "useGroupScoring", "excludeRecursion", "preventRecursion", "delayUntilRecursion",
        "group", "groupOverride", "groupWeight", "sticky", "cooldown", "delay",
        "ignoreBudget", "displayIndex",
    )

    fun map(obj: JsonObject, bookUuid: String, fallbackUid: Int, fallbackDisplayIndex: Int): WorldBookEntryEntity {
        val defaults = WorldBookEntryEntity()
        return WorldBookEntryEntity(
            bookUuid = bookUuid,
            uid = obj.lenientInt("uid") ?: fallbackUid,
            displayIndex = obj.lenientInt("displayIndex") ?: fallbackDisplayIndex,
            keysJson = encodeStringList(obj.lenientStringList("key") ?: emptyList()),
            secondaryKeysJson = encodeStringList(obj.lenientStringList("keysecondary") ?: emptyList()),
            selective = obj.lenientBool("selective") ?: defaults.selective,
            selectiveLogic = obj.lenientInt("selectiveLogic") ?: defaults.selectiveLogic,
            constant = obj.lenientBool("constant") ?: defaults.constant,
            vectorized = obj.lenientBool("vectorized") ?: defaults.vectorized,
            comment = obj.lenientString("comment") ?: defaults.comment,
            content = obj.lenientString("content") ?: defaults.content,
            enabled = !(obj.lenientBool("disable") ?: false),
            insertionOrder = obj.lenientInt("order") ?: defaults.insertionOrder,
            position = obj.lenientInt("position") ?: defaults.position,
            depth = obj.lenientInt("depth") ?: defaults.depth,
            role = obj.lenientInt("role") ?: defaults.role,
            ignoreBudget = obj.lenientBool("ignoreBudget") ?: defaults.ignoreBudget,
            probability = obj.lenientInt("probability") ?: defaults.probability,
            useProbability = obj.lenientBool("useProbability") ?: defaults.useProbability,
            scanDepth = obj.lenientInt("scanDepth"),
            caseSensitive = obj.lenientBool("caseSensitive"),
            matchWholeWords = obj.lenientBool("matchWholeWords"),
            useGroupScoring = obj.lenientBool("useGroupScoring"),
            excludeRecursion = obj.lenientBool("excludeRecursion") ?: defaults.excludeRecursion,
            preventRecursion = obj.lenientBool("preventRecursion") ?: defaults.preventRecursion,
            delayUntilRecursion = delayUntilRecursionValue(obj["delayUntilRecursion"]),
            groupName = obj.lenientString("group") ?: defaults.groupName,
            groupOverride = obj.lenientBool("groupOverride") ?: defaults.groupOverride,
            groupWeight = obj.lenientInt("groupWeight") ?: defaults.groupWeight,
            sticky = obj.lenientInt("sticky"),
            cooldown = obj.lenientInt("cooldown"),
            delay = obj.lenientInt("delay"),
            extraJson = extrasFrom(obj, CONSUMED_KEYS),
        )
    }

    /** uid 去重（导出以 uid 当 entries 键，撞键会静默丢条目）：保留首见，撞键者顺延分配新 uid。 */
    fun dedupeUids(entries: List<WorldBookEntryEntity>): List<WorldBookEntryEntity> {
        val seen = mutableSetOf<Int>()
        var next = (entries.maxOfOrNull { it.uid } ?: -1) + 1
        return entries.map { e ->
            if (seen.add(e.uid)) e else e.copy(uid = next++).also { seen.add(it.uid) }
        }
    }
}
