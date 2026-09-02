package com.situ.aichat.data.backup

import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.entity.OurDayEntity
import kotlinx.serialization.Serializable

/**
 * 「我们的日子」表备份（卷一《沉淀》图纸 §3.5·逐字照 [PromiseExport] 范式）：**顶层全局段**，整体恢复一次；
 * characterUuid 为幽灵（导入库中不存在的角色）→ 整行跳过（无 FK）；uuid 原样保留 → 再导入按 uuid REPLACE 幂等。
 * 旧版备份（无此段）导入后表空 + `characters.ourDaysBackfilledAt` 为 null → 下次 catch-up 走一次回填（E14）。
 * 字段序 = 实体序去 `embedding`（导出剥向量·照 [WorldMemoryExport]·体积 / 隐私·卷二回填重嵌）。
 */
@Serializable
data class OurDayExport(
    val uuid: String = "",
    val characterUuid: String = "",
    val dayKey: String = "",
    val factsJson: String = "",
    val messageCount: Int = 0,
    val callSeconds: Int = 0,
    val hasMeeting: Boolean = false,
    val hasRelation: Boolean = false,
    val hasLife: Boolean = false,
    val note: String = "",
    val factLine: String = "",
    val noteStatus: String = "none",
    val noteAttempts: Int = 0,
    val noteEdited: Boolean = false,
    val hiddenFromMemory: Boolean = false,
    val deleted: Boolean = false,
    val generatedAt: Long? = null,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
)

/** 导出剥 embedding（卷二回填重嵌）。 */
internal fun OurDayEntity.toExport() = OurDayExport(
    uuid, characterUuid, dayKey, factsJson, messageCount, callSeconds, hasMeeting, hasRelation, hasLife,
    note, factLine, noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, generatedAt,
    createdAtMillis, updatedAtMillis,
)

/** 恢复后 embedding = null（卷二向量回填重嵌）。 */
internal fun OurDayExport.toEntity() = OurDayEntity(
    uuid, characterUuid, dayKey, factsJson, messageCount, callSeconds, hasMeeting, hasRelation, hasLife,
    note, factLine, noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, generatedAt,
    createdAtMillis, updatedAtMillis, embedding = null,
)

/** 导出采集（Exporter 全局段之一·空表返 null 照 [collectPromises]）。 */
internal suspend fun collectOurDays(dao: OurDayDao): List<OurDayExport>? =
    dao.getAll().map { it.toExport() }.ifEmpty { null }

/** 恢复（Importer 事务内·幽灵 characterUuid 行跳过·uuid REPLACE 幂等）。 */
internal suspend fun restoreOurDays(
    dao: OurDayDao,
    data: List<OurDayExport>?,
    existingCharacterUuids: Set<String>,
) {
    data
        ?.filter { it.characterUuid in existingCharacterUuids }
        ?.forEach { dao.upsert(it.toEntity()) }
}
