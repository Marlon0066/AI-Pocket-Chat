package com.situ.aichat.ui.ourdays

import com.situ.aichat.ourdays.OurDayFacts
import com.situ.aichat.ourdays.OurDayPromiseEvent

/**
 * 事实层文案（卷三图纸 §3.5 / §3.7）：VM 从资源取后实现（照 [DecorStrings] 范式·纯核不碰 Compose / Context）；
 * 测试用假实现重打字面量断言。时刻 `HH:mm` 由 [time] 按调用方时区格式化。
 */
interface OurDayCardStrings {
    fun time(millis: Long): String
    fun chat(count: Int): String
    fun timeRange(from: String, to: String): String
    fun call(minutes: Int): String
    fun callCount(count: Int): String
    fun meeting(place: String): String
    fun duration(minutes: Int): String
    fun initiatedUser(): String
    fun initiatedChar(name: String): String
    fun giftUser(gift: String): String
    fun giftChar(name: String, gift: String): String
    fun reaction(name: String, text: String): String
    fun affinity(gain: Int): String
    fun redPacketUser(): String
    fun redPacketChar(name: String): String
    fun redPacketStatus(status: String): String
    fun promiseCreated(content: String): String
    fun promiseFulfilled(content: String): String
    fun promiseCancelled(content: String): String
    fun milestone(text: String): String
    fun quote(text: String): String
    fun moments(): String
    fun momentsDetail(name: String, posts: Int, interactions: Int): String
    fun momentsInteract(name: String, interactions: Int): String
    fun exchangeDiary(name: String): String
    fun schedule(name: String): String
}

/**
 * 「这一天」事实列表（图纸 §3.5 固定序·零项省略）：聊天 → 通话 → 见面（每场）→ 礼物（每件）→ 红包（每个·**永不含金额**）
 * → 约定（每条）→ 里程碑（每条）→ 朋友圈 → 交换日记 → TA 的一天。detail 段以 ` · ` 连。
 */
internal object OurDayFactItems {

    const val SEP = " · "

    fun build(facts: OurDayFacts, characterName: String, dayKey: String, s: OurDayCardStrings): List<FactItem> = buildList {
        if (facts.messageCount > 0) {
            val first = facts.firstMessageAt
            val last = facts.lastMessageAt
            val detail = if (first != null && last != null) {
                val a = s.time(first)
                val b = s.time(last)
                if (a == b) a else s.timeRange(a, b)
            } else {
                ""
            }
            add(FactItem(FactKind.CHAT, s.chat(facts.messageCount), detail, null))
        }
        if (facts.callCount > 0 || facts.callSeconds > 0) {
            val detail = if (facts.callCount >= 2) s.callCount(facts.callCount) else ""
            add(FactItem(FactKind.CALL, s.call(maxOf(1, facts.callSeconds / 60)), detail, null))
        }
        facts.meetings.forEach { m ->
            val place = m.location.ifBlank { m.activity }
            val parts = buildList {
                add(s.time(m.startedAtMillis))
                if (m.durationMinutes > 0) add(s.duration(m.durationMinutes))
                if (m.activity.isNotBlank() && m.activity != place) add(m.activity)
                when (m.initiatedByUser) {
                    true -> add(s.initiatedUser())
                    false -> add(s.initiatedChar(characterName))
                    null -> Unit
                }
            }
            add(FactItem(FactKind.MEETING, s.meeting(place), parts.joinToString(SEP), FactLink.MEETINGS))
        }
        facts.gifts.forEach { g ->
            val title = if (g.fromUser) s.giftUser(g.giftName) else s.giftChar(characterName, g.giftName)
            val parts = buildList {
                if (g.fromUser && g.reactionText.isNotBlank()) add(s.reaction(characterName, g.reactionText))
                if (g.affinityGain > 0) add(s.affinity(g.affinityGain))
            }
            add(FactItem(FactKind.GIFT, title, parts.joinToString(SEP), null))
        }
        facts.redPackets.forEach { r ->
            val title = if (r.fromUser) s.redPacketUser() else s.redPacketChar(characterName)
            add(FactItem(FactKind.RED_PACKET, title, s.redPacketStatus(r.status), null))
        }
        facts.promises.forEach { p ->
            val title = when (p.event) {
                OurDayPromiseEvent.FULFILLED -> s.promiseFulfilled(p.content)
                OurDayPromiseEvent.CANCELLED -> s.promiseCancelled(p.content)
                else -> s.promiseCreated(p.content)
            }
            add(FactItem(FactKind.PROMISE, title, "", FactLink.PROMISES))
        }
        facts.milestones.forEach { m ->
            val phase = m.phase?.takeIf { it.isNotBlank() }
            val title = s.milestone(if (phase != null) m.relationshipName + SEP + phase else m.relationshipName)
            add(FactItem(FactKind.MILESTONE, title, if (m.reason.isNotBlank()) s.quote(m.reason) else "", null))
        }
        if (facts.momentPosts > 0 || facts.momentInteractions > 0) {
            val detail = if (facts.momentPosts > 0) {
                s.momentsDetail(characterName, facts.momentPosts, facts.momentInteractions)
            } else {
                s.momentsInteract(characterName, facts.momentInteractions)
            }
            add(FactItem(FactKind.MOMENTS, s.moments(), detail, FactLink.MOMENTS))
        }
        facts.exchangeDiary?.let { d ->
            val detail = (d.moodEmoji?.takeIf { it.isNotEmpty() }?.let { "$it " } ?: "") + d.firstLine
            add(FactItem(FactKind.EXCHANGE_DIARY, s.exchangeDiary(characterName), detail, FactLink.DIARY(d.uuid)))
        }
        if (facts.scheduleLine.isNotBlank()) {
            add(FactItem(FactKind.SCHEDULE, s.schedule(characterName), facts.scheduleLine, FactLink.SCHEDULE(dayKey)))
        }
    }
}
