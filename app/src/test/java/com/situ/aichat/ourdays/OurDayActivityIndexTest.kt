package com.situ.aichat.ourdays

import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * T1-2（卷一图纸 §7.2）：活动索引 = 各源按 §3.3 口径映射到日键的并集。断言从总图纸 Z-2 / §3.3 / E4 独立反推。
 */
class OurDayActivityIndexTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun ms(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, d, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun meeting(uuid: String, started: Long, kind: String = "meeting") = OfflineMeetingMemoryEntity(
        uuid = uuid, characterUuid = "c1", kindRaw = kind, startedAtMillis = started, createdAtMillis = 1, updatedAtMillis = 1,
    )
    private fun promise(uuid: String, created: Long, resolved: Long? = null) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = "约定", createdAtMillis = created, updatedAtMillis = created, resolvedAtMillis = resolved,
        statusRaw = if (resolved != null) "fulfilled" else "open",
    )

    @Test
    fun 消息时间戳按本地日分键_跨零点分两日() {
        val s = OurDaySources.EMPTY.copy(messageTimestamps = listOf(ms(1, 23, 58), ms(2, 0, 3), ms(2, 10)))
        assertEquals(setOf("2026-09-01", "2026-09-02"), OurDayActivityIndex.activeDays(s, zone))
    }

    @Test
    fun legacy见面与非meeting行排除_E4() {
        val s = OurDaySources.EMPTY.copy(
            meetings = listOf(
                meeting("legacy0", started = 0L, kind = "legacy"),
                meeting("legacyZero", started = 0L), // kind=meeting 但 startedAt=0 也排除
                meeting("otherKind", started = ms(3, 15), kind = "legacy"),
                meeting("real", started = ms(5, 19)),
            ),
        )
        assertEquals(setOf("2026-09-05"), OurDayActivityIndex.activeDays(s, zone))
    }

    @Test
    fun 约定created与resolved落两日() {
        val s = OurDaySources.EMPTY.copy(promises = listOf(promise("p1", created = ms(1, 12), resolved = ms(4, 9))))
        assertEquals(setOf("2026-09-01", "2026-09-04"), OurDayActivityIndex.activeDays(s, zone))
    }

    @Test
    fun 朋友圈发帖与互动两列表并集() {
        val s = OurDaySources.EMPTY.copy(
            momentPostTimestamps = listOf(ms(6, 8)),
            momentInteractionTimestamps = listOf(ms(7, 8), ms(6, 20)),
        )
        assertEquals(setOf("2026-09-06", "2026-09-07"), OurDayActivityIndex.activeDays(s, zone))
    }

    @Test
    fun 礼物红包里程碑交换日记各按自身时间戳入索引() {
        val s = OurDaySources.EMPTY.copy(
            gifts = listOf(GiftRecordEntity(uuid = "g1", timestamp = ms(8, 10), receiverCharacterUUID = "c1")),
            redPackets = listOf(RedPacketRecordEntity(uuid = "r1", createdAt = ms(9, 10), receiverCharacterUUID = "c1")),
            milestones = listOf(MilestoneEntity(uuid = "m1", characterUuid = "c1", relationshipName = "朋友", establishedDate = ms(10, 10))),
            exchangeDiaries = listOf(DiaryEntryEntity(uuid = "d1", timestamp = ms(11, 10), authorCharacterUuid = "c1", triggerTypeRaw = "exchange")),
        )
        assertEquals(setOf("2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11"), OurDayActivityIndex.activeDays(s, zone))
    }

    @Test
    fun 空源返空集() {
        assertTrue(OurDayActivityIndex.activeDays(OurDaySources.EMPTY, zone).isEmpty())
    }

    @Test
    fun 同一时间戳不同时区落不同日键_E2() {
        val t = ms(2, 2) // 上海 09-02 02:00 = UTC 09-01 18:00
        val s = OurDaySources.EMPTY.copy(messageTimestamps = listOf(t))
        assertEquals(setOf("2026-09-02"), OurDayActivityIndex.activeDays(s, zone))
        assertEquals(setOf("2026-09-01"), OurDayActivityIndex.activeDays(s, ZoneOffset.UTC))
    }

    @Test
    fun 多源同日只出一键() {
        val s = OurDaySources.EMPTY.copy(
            messageTimestamps = listOf(ms(2, 9)),
            gifts = listOf(GiftRecordEntity(uuid = "g1", timestamp = ms(2, 10))),
            promises = listOf(promise("p1", created = ms(2, 11))),
        )
        assertEquals(setOf("2026-09-02"), OurDayActivityIndex.activeDays(s, zone))
    }
}
