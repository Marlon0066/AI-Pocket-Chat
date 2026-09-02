package com.situ.aichat.ourdays

import java.time.ZoneId

/**
 * 活动索引（总图纸 Z-2）：各源按 §3.3 口径映射到日键的**并集** = 「哪些日子有页可写」的候选全集。
 * 纯函数；是否真「有互动」由 [OurDayFactsBuilder] 的细口径（`hasActivity`）二次裁决（例如只有 system 消息的一天在此入选、在彼落空）。
 */
internal object OurDayActivityIndex {

    /** 见面档案的结构化行种类（legacy 行 `startedAtMillis == 0` / `kindRaw != "meeting"` 一律排除·E4）。 */
    const val MEETING_KIND = "meeting"

    fun activeDays(sources: OurDaySources, zone: ZoneId): Set<String> {
        val days = LinkedHashSet<String>()
        fun add(millis: Long) { days.add(OurDayKey.dayKey(millis, zone)) }
        sources.messageTimestamps.forEach(::add)
        sources.meetings.asSequence()
            .filter { it.kindRaw == MEETING_KIND && it.startedAtMillis != 0L }
            .forEach { add(it.startedAtMillis) }
        sources.gifts.forEach { add(it.timestamp) }
        sources.redPackets.forEach { add(it.createdAt) }
        sources.promises.forEach { p ->
            add(p.createdAtMillis)
            p.resolvedAtMillis?.let(::add)
        }
        sources.milestones.forEach { add(it.establishedDate) }
        sources.momentPostTimestamps.forEach(::add)
        sources.momentInteractionTimestamps.forEach(::add)
        sources.exchangeDiaries.forEach { add(it.timestamp) }
        return days
    }
}
