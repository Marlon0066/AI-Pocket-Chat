package com.situ.aichat.ourdays

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 事实层渲染行（总图纸 §4.2 · 逐字锁定 · 零项整行省略）：喂手记提示词的【这一天的记录】段。
 * 人名双轨：`characterName` = 角色名；`userRefName` = 昵称，空则「用户」（Z-10·由调用方解析）。
 */
internal object OurDayFactsRenderer {

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun render(facts: OurDayFacts, characterName: String, userRefName: String, zone: ZoneId): String {
        val lines = mutableListOf<String>()
        fun hhmm(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(HHMM)

        val first = facts.firstMessageAt
        val last = facts.lastMessageAt
        if (facts.messageCount > 0 && first != null && last != null) {
            lines += "- 聊天：${facts.messageCount} 条，${hhmm(first)}–${hhmm(last)}"
        }
        if (facts.callCount > 0) {
            lines += "- 语音通话：${facts.callCount} 次，共约 ${facts.callSeconds / 60} 分钟"
        }
        for (m in facts.meetings) {
            val segments = mutableListOf(m.location, m.activity)
            if (m.durationMinutes != 0) segments += "约 ${m.durationMinutes} 分钟"
            when (m.initiatedByUser) {
                true -> segments += "${userRefName}约的"
                false -> segments += "${characterName}约的"
                null -> Unit
            }
            lines += "- 线下见面：${segments.joinToString("，")}"
        }
        for (g in facts.gifts) {
            lines += if (g.fromUser) {
                val reaction = if (g.reactionText.isEmpty()) "" else "，${characterName}的反应：${g.reactionText}"
                "- 礼物：${userRefName}送了「${g.giftName}」$reaction"
            } else {
                "- 礼物：${characterName}送了「${g.giftName}」"
            }
        }
        for (r in facts.redPackets) {
            val who = if (r.fromUser) userRefName else characterName
            lines += "- 红包：${who}发了一个红包（${statusWord(r.status)}）"
        }
        for (p in facts.promises) {
            val verb = when (p.event) {
                OurDayPromiseEvent.CREATED -> "定下"
                OurDayPromiseEvent.FULFILLED -> "兑现"
                OurDayPromiseEvent.CANCELLED -> "取消"
                else -> continue
            }
            lines += "- 约定：$verb「${p.content}」"
        }
        for (m in facts.milestones) {
            val because = if (m.reason.isEmpty()) "" else "，因为${m.reason}"
            lines += "- 里程碑：关系变成「${m.relationshipName}」$because"
        }
        if (facts.momentPosts != 0 || facts.momentInteractions != 0) {
            lines += "- 朋友圈：${characterName}发了 ${facts.momentPosts} 条动态，互动 ${facts.momentInteractions} 次"
        }
        facts.exchangeDiary?.let { d ->
            val mood = d.moodEmoji?.takeIf { it.isNotEmpty() }?.let { "（心情 $it）" } ?: ""
            lines += "- 交换日记：${characterName}写了一篇日记$mood：${d.firstLine}"
        }
        if (facts.scheduleLine.isNotEmpty()) {
            lines += "- ${characterName}这天的日程：${facts.scheduleLine}"
        }
        return lines.joinToString("\n")
    }

    /** 红包状态词（锁定）：pending 还没拆 / accepted 收下了 / rejected 没收 / expired 过期了 / 其它原串。 */
    private fun statusWord(status: String): String = when (status) {
        "pending" -> "还没拆"
        "accepted" -> "收下了"
        "rejected" -> "没收"
        "expired" -> "过期了"
        else -> status
    }
}
