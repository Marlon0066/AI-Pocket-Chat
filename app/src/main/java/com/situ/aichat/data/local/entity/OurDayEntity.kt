package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「我们的日子」一页 = 一天 × 一角色（总图纸 docs/handoff/2026-09-02-我们的日子-总图纸.md §3.1 · 卷一《沉淀》建）。
 * 两层：事实层快照（[factsJson] + 五个反规范化列·零 LLM·可重算）+ 手记层（[note] 给人看 / [factLine] 给 TA 读·一次调用两产物）。
 *
 * 无外键（照 [PromiseEntity] / [OpenLoopEntity] 先例）——删角色手动级联清：见
 * [com.situ.aichat.data.repository.CharacterRepository.delete]。行只由 [com.situ.aichat.ourdays.OurDayCoordinator]
 * 与备份导入写（总图纸 §3.10）；三类写各自列级 UPDATE，互不覆盖。空日无行（Z-3）；今天永不写页（§9.4）。
 */
@Entity(
    tableName = "our_days",
    indices = [Index("characterUuid"), Index(value = ["characterUuid", "dayKey"], unique = true)],
)
data class OurDayEntity(
    @PrimaryKey val uuid: String,
    val characterUuid: String,
    /** 本地日键 `yyyy-MM-dd`（Locale.ROOT·写入时刻的 clock.zone）。Z-1。 */
    val dayKey: String,
    /** 事实层快照 [com.situ.aichat.ourdays.OurDayFacts] JSON（可重算·refreshFacts 覆写）。 */
    val factsJson: String = "",
    /** 反规范化：当天文字消息数（总图纸 §3.3 计数口径）。 */
    val messageCount: Int = 0,
    /** 反规范化：当天通话总秒数。 */
    val callSeconds: Int = 0,
    val hasMeeting: Boolean = false,
    /** 约定立 / 兑现 / 取消 · 里程碑。 */
    val hasRelation: Boolean = false,
    /** 礼物 · 红包 · 朋友圈 · 交换日记。 */
    val hasLife: Boolean = false,
    /** 手记（TA 第一人称·显示用·不注入）。 */
    val note: String = "",
    /** 事实行（双名第三人称·不含日期前缀·注入与向量源）。 */
    val factLine: String = "",
    /** [OurDayNoteStatus]：none | ok | failed。 */
    val noteStatus: String = OurDayNoteStatus.NONE,
    val noteAttempts: Int = 0,
    /** 用户手改过 ⇒ 自动流程永不覆盖 note/factLine。 */
    val noteEdited: Boolean = false,
    /** 「别让 TA 记」⇒ 卷二三路注入与向量一律排除；置位时 embedding 置 null。 */
    val hiddenFromMemory: Boolean = false,
    /** 墓碑：行留、note/factLine 清空、不自动重生。 */
    val deleted: Boolean = false,
    /** 手记最近一次生成 / 手改时刻。 */
    val generatedAt: Long? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** 向量（卷二填·float32 小端·照 [OfflineMeetingMemoryEntity]）；不手写 equals/hashCode（总图纸 F7）。 */
    val embedding: ByteArray? = null,
)

/** [OurDayEntity.noteStatus] 取值（锁定三串·总图纸 §9.1）。 */
object OurDayNoteStatus {
    const val NONE = "none"
    const val OK = "ok"
    const val FAILED = "failed"
}
