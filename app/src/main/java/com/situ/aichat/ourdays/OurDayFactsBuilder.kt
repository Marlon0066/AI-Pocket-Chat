package com.situ.aichat.ourdays

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.CallRecordJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.prompt.schedule.schedulePastLine
import com.situ.aichat.util.takeCodePoints
import java.time.ZoneId

/**
 * 事实层聚合纯函数（总图纸 §3.3 计数口径·锁定）：把「单角色全量源 + 当天消息 + 当日日程事件」切成 [OurDayFacts]。
 * 所有窗判定 = `[dayStart, dayEnd)`（[OurDayKey.dayBounds]）。零时钟、零 DB、零 LLM。
 */
internal object OurDayFactsBuilder {

    private const val ROLE_SYSTEM = "system"
    private const val SENDER_USER = "user"
    private const val EVENT_TYPE_USER_INTERACTION = "userInteraction"
    private const val DIARY_TRIGGER_EXCHANGE = "exchange"
    private const val DIY_FALLBACK_NAME = "手作礼物"
    private const val REACTION_MAX_CODEPOINTS = 60
    private const val FIRST_LINE_MAX_CODEPOINTS = 40

    fun build(
        sources: OurDaySources,
        dayMessages: List<MessageEntity>,
        scheduleEvents: List<ScheduleEventEntity>,
        dayKey: String,
        zone: ZoneId,
    ): OurDayFacts {
        val window = OurDayKey.dayBounds(dayKey, zone)
        fun inWindow(t: Long) = t in window

        val texts = dayMessages.filter {
            inWindow(it.timestamp) && it.roleRaw != ROLE_SYSTEM && it.content.isNotEmpty() &&
                !it.isHeldForDelivery && !it.isPartOfVoiceCall && !MessageKind.fromRaw(it.messageKindRaw).isStructuredCard
        }
        val calls = dayMessages.filter { inWindow(it.timestamp) && it.messageKindRaw == MessageKind.CALL_RECORD_CARD.raw }

        val meetings = sources.meetings
            .filter { it.kindRaw == OurDayActivityIndex.MEETING_KIND && inWindow(it.startedAtMillis) }
            .map { m ->
                OurDayMeetingFact(
                    uuid = m.uuid, location = m.location, activity = m.activity, startedAtMillis = m.startedAtMillis,
                    durationMinutes = if (m.endedAtMillis == 0L) 0 else maxOf(1L, (m.endedAtMillis - m.startedAtMillis) / 60_000L).toInt(),
                    initiatedByUser = m.initiatedByUser, moodRaw = m.moodRaw,
                )
            }
        val gifts = sources.gifts.filter { inWindow(it.timestamp) }.map { g ->
            OurDayGiftFact(
                uuid = g.uuid, fromUser = g.senderType == SENDER_USER,
                giftName = if (g.isDIY) g.diyTitle.ifBlank { DIY_FALLBACK_NAME } else GiftCatalog.find(g.giftItemId)?.name ?: g.giftItemId,
                isDIY = g.isDIY, reactionText = g.reactionText.takeCodePoints(REACTION_MAX_CODEPOINTS), affinityGain = g.affinityGain,
            )
        }
        val redPackets = sources.redPackets.filter { inWindow(it.createdAt) }.map { r ->
            OurDayRedPacketFact(uuid = r.uuid, fromUser = r.senderType == SENDER_USER, status = r.status)
        }
        val promises = sources.promises.flatMap { p ->
            val events = ArrayList<Pair<Long, OurDayPromiseFact>>(2)
            if (inWindow(p.createdAtMillis)) events += p.createdAtMillis to OurDayPromiseFact(p.uuid, p.content, OurDayPromiseEvent.CREATED)
            val resolvedAt = p.resolvedAtMillis
            val resolvedEvent = when (p.statusRaw) {
                PromiseStatus.FULFILLED -> OurDayPromiseEvent.FULFILLED
                PromiseStatus.CANCELLED -> OurDayPromiseEvent.CANCELLED
                else -> null
            }
            if (resolvedAt != null && resolvedEvent != null && inWindow(resolvedAt)) {
                events += resolvedAt to OurDayPromiseFact(p.uuid, p.content, resolvedEvent)
            }
            events
        }.sortedBy { it.first }.map { it.second }
        val milestones = sources.milestones.filter { inWindow(it.establishedDate) }.map { m ->
            OurDayMilestoneFact(uuid = m.uuid, relationshipName = m.relationshipName, phase = m.phase, reason = m.reason)
        }
        val exchangeDiary = sources.exchangeDiaries
            .filter { it.triggerTypeRaw == DIARY_TRIGGER_EXCHANGE && !it.isDraft && inWindow(it.timestamp) }
            .minByOrNull { it.timestamp }
            ?.let { d ->
                OurDayDiaryFact(
                    uuid = d.uuid, moodEmoji = d.moodEmoji,
                    firstLine = d.content.trimStart().substringBefore('\n').trimEnd().takeCodePoints(FIRST_LINE_MAX_CODEPOINTS),
                )
            }
        val scheduleLine = schedulePastLine(
            scheduleEvents.filter { it.eventTypeRaw != EVENT_TYPE_USER_INTERACTION }
                .sortedWith(compareBy({ it.startTime }, { it.sortOrder })),
        )

        return OurDayFacts(
            messageCount = texts.size,
            firstMessageAt = texts.minOfOrNull { it.timestamp },
            lastMessageAt = texts.maxOfOrNull { it.timestamp },
            callCount = calls.size,
            callSeconds = calls.sumOf { CallRecordJson.parse(it.content)?.duration ?: 0 },
            meetings = meetings,
            gifts = gifts,
            redPackets = redPackets,
            promises = promises,
            milestones = milestones,
            momentPosts = sources.momentPostTimestamps.count(::inWindow),
            momentInteractions = sources.momentInteractionTimestamps.count(::inWindow),
            exchangeDiary = exchangeDiary,
            scheduleLine = scheduleLine,
        )
    }
}
