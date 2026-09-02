package com.situ.aichat.data.local.entity

/** 「我们的日子」日历投影行（卷三图纸 §3.1·W-1）：实体去 `embedding`——列表 / 网格 / 日页只读此形态；相等性可靠。 */
data class OurDayCalendarRow(
    val uuid: String,
    val characterUuid: String,
    val dayKey: String,
    val factsJson: String,
    val messageCount: Int,
    val callSeconds: Int,
    val hasMeeting: Boolean,
    val hasRelation: Boolean,
    val hasLife: Boolean,
    val note: String,
    val factLine: String,
    val noteStatus: String,
    val noteAttempts: Int,
    val noteEdited: Boolean,
    val hiddenFromMemory: Boolean,
    val deleted: Boolean,
    val generatedAt: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
