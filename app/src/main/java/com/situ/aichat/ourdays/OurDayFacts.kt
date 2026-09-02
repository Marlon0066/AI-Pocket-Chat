package com.situ.aichat.ourdays

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「我们的日子」事实层（总图纸 docs/handoff/2026-09-02-我们的日子-总图纸.md §3.3 · 卷一 · 逐字）：一天 × 一角色的
 * 零 LLM 聚合快照，存进 `our_days.factsJson`（可重算·`refreshFacts` 覆写）。`encodeDefaults = false` ⇒ 零值不落 JSON；
 * `ignoreUnknownKeys = true` ⇒ 后续加字段老快照照常解。**永不含红包金额**（[OurDayRedPacketFact]·§9.5 grep 钉）。
 */
@Serializable
data class OurDayFacts(
    val version: Int = 1,
    val messageCount: Int = 0,
    val firstMessageAt: Long? = null,
    val lastMessageAt: Long? = null,
    val callCount: Int = 0,
    val callSeconds: Int = 0,
    val meetings: List<OurDayMeetingFact> = emptyList(),
    val gifts: List<OurDayGiftFact> = emptyList(),
    val redPackets: List<OurDayRedPacketFact> = emptyList(),
    val promises: List<OurDayPromiseFact> = emptyList(),
    val milestones: List<OurDayMilestoneFact> = emptyList(),
    val momentPosts: Int = 0,
    val momentInteractions: Int = 0,
    val exchangeDiary: OurDayDiaryFact? = null,
    /** 「TA 的一天」压缩行（schedulePastLine 输出·无标签）；空 = 无日程。不计入 hasActivity。 */
    val scheduleLine: String = "",
)

@Serializable
data class OurDayMeetingFact(
    val uuid: String,
    val location: String,
    val activity: String,
    val startedAtMillis: Long,
    val durationMinutes: Int,
    val initiatedByUser: Boolean? = null,
    val moodRaw: String = "",
)

@Serializable
data class OurDayGiftFact(
    val uuid: String,
    val fromUser: Boolean,
    val giftName: String,
    val isDIY: Boolean,
    val reactionText: String = "",
    val affinityGain: Int = 0,
)

/** 永不含金额（§9.5 grep 钉）。 */
@Serializable
data class OurDayRedPacketFact(val uuid: String, val fromUser: Boolean, val status: String)

/** [event] ∈ [OurDayPromiseEvent]：created | fulfilled | cancelled。 */
@Serializable
data class OurDayPromiseFact(val uuid: String, val content: String, val event: String)

/** [OurDayPromiseFact.event] 三串（锁定·总图纸 §9.1）。 */
object OurDayPromiseEvent {
    const val CREATED = "created"
    const val FULFILLED = "fulfilled"
    const val CANCELLED = "cancelled"
}

@Serializable
data class OurDayMilestoneFact(
    val uuid: String,
    val relationshipName: String,
    val phase: String? = null,
    val reason: String = "",
)

@Serializable
data class OurDayDiaryFact(val uuid: String, val moodEmoji: String? = null, val firstLine: String = "")

/** 事实层 JSON 编解码单源（照 `GrowthJson` 纹路）。 */
object OurDayFactsJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(facts: OurDayFacts): String = json.encodeToString(OurDayFacts.serializer(), facts)

    /** 坏 / 空 JSON → null（调用方按需 `refreshFacts` 重算）。 */
    fun decodeOrNull(s: String): OurDayFacts? =
        if (s.isEmpty()) null else runCatching { json.decodeFromString(OurDayFacts.serializer(), s) }.getOrNull()
}

/** 「有互动」判据（总图纸 §3.3 锁定·`scheduleLine` 不计）：为 false 的日子不建行（Z-3）。 */
val OurDayFacts.hasActivity: Boolean
    get() = messageCount > 0 || callCount > 0 || meetings.isNotEmpty() || gifts.isNotEmpty() ||
        redPackets.isNotEmpty() || promises.isNotEmpty() || milestones.isNotEmpty() ||
        momentPosts > 0 || momentInteractions > 0 || exchangeDiary != null

/** 热度分单源（卷三图纸 §3.4·W-2）：`messageCount + (callSeconds / 60) * 3`（整除）。UI 从投影行算与本式同源。 */
fun ourDayHeatScore(messageCount: Int, callSeconds: Int): Int = messageCount + (callSeconds / 60) * 3

/** 热度档位单源（提案 D-4 锁定）：0 无 / 1..9 淡 / 10..39 中 / ≥40 深。 */
fun ourDayHeatLevel(score: Int): Int = when {
    score <= 0 -> 0
    score <= 9 -> 1
    score <= 39 -> 2
    else -> 3
}

/** 热度分（卷三用·锁定）：委托 [ourDayHeatScore]。档位 0 无 / 1..9 淡 / 10..39 中 / ≥40 深。 */
val OurDayFacts.heatScore: Int
    get() = ourDayHeatScore(messageCount, callSeconds)

/** 反规范化列（总图纸 §3.3）。 */
val OurDayFacts.hasMeeting: Boolean get() = meetings.isNotEmpty()
val OurDayFacts.hasRelation: Boolean get() = promises.isNotEmpty() || milestones.isNotEmpty()
val OurDayFacts.hasLife: Boolean
    get() = gifts.isNotEmpty() || redPackets.isNotEmpty() || momentPosts > 0 || momentInteractions > 0 || exchangeDiary != null
