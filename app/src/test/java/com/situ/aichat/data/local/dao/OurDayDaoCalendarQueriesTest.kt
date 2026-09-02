package com.situ.aichat.data.local.dao

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.data.local.entity.OurDayEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-5（卷三图纸 §7.2·真 Room 内存库）：§3.1 六条只读查询——投影行字段逐一等于实体（embedding 除外）、
 * 全部范围排序、相识日忽略墓碑 / 空表 null、见面天数、最近活跃谓词与排序（同日按 updatedAtMillis）。
 * 断言从 §3.1 SQL 语义独立反推，不照抄实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDayDaoCalendarQueriesTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: OurDayDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.ourDayDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        uuid: String,
        char: String = "c1",
        key: String,
        messageCount: Int = 0,
        callSeconds: Int = 0,
        hasMeeting: Boolean = false,
        hasRelation: Boolean = false,
        hasLife: Boolean = false,
        deleted: Boolean = false,
        updatedAt: Long = 1L,
    ) = OurDayEntity(
        uuid = uuid, characterUuid = char, dayKey = key, factsJson = """{"messageCount":$messageCount}""",
        messageCount = messageCount, callSeconds = callSeconds, hasMeeting = hasMeeting, hasRelation = hasRelation, hasLife = hasLife,
        note = "手记$uuid", factLine = "事实$uuid", noteStatus = "ok", noteAttempts = 2, noteEdited = true, hiddenFromMemory = true,
        deleted = deleted, generatedAt = 77L, createdAtMillis = 5L, updatedAtMillis = updatedAt, embedding = byteArrayOf(1, 2, 3),
    )

    private fun insert(vararg rows: OurDayEntity) = runBlocking { rows.forEach { dao.upsert(it) } }

    @Test
    fun 投影行字段逐一等于实体_embedding除外() {
        val e = row("a", key = "2026-09-01", messageCount = 12, callSeconds = 130, hasMeeting = true, hasRelation = true, hasLife = true, updatedAt = 9L)
        insert(e)
        val r = runBlocking { dao.observeCalendarRow("c1", "2026-09-01").first() }!!
        val expected = OurDayCalendarRow(
            uuid = e.uuid, characterUuid = e.characterUuid, dayKey = e.dayKey, factsJson = e.factsJson, messageCount = e.messageCount,
            callSeconds = e.callSeconds, hasMeeting = e.hasMeeting, hasRelation = e.hasRelation, hasLife = e.hasLife, note = e.note,
            factLine = e.factLine, noteStatus = e.noteStatus, noteAttempts = e.noteAttempts, noteEdited = e.noteEdited,
            hiddenFromMemory = e.hiddenFromMemory, deleted = e.deleted, generatedAt = e.generatedAt, createdAtMillis = e.createdAtMillis,
            updatedAtMillis = e.updatedAtMillis,
        )
        assertEquals(expected, r)
        // 投影行 = 纯值 data class：两次查询结果相等（实体含 ByteArray 时不成立·F3）
        assertEquals(r, runBlocking { dao.observeCalendarRow("c1", "2026-09-01").first() })
    }

    @Test
    fun 单行投影_无行返null() {
        assertNull(runBlocking { dao.observeCalendarRow("c1", "2026-09-01").first() })
    }

    @Test
    fun 单角色范围_闭区间含首末_他人与界外不入_dayKey升序() {
        insert(
            row("a", key = "2026-09-03"), row("b", key = "2026-09-01"), row("c", key = "2026-09-02"),
            row("d", key = "2026-08-31"), row("e", key = "2026-09-04"), row("x", char = "c2", key = "2026-09-02"),
        )
        val keys = runBlocking { dao.observeCalendarRange("c1", "2026-09-01", "2026-09-03").first() }.map { it.dayKey }
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03"), keys)
    }

    @Test
    fun 全部范围_按dayKey再characterUuid升序_不过滤墓碑() {
        insert(
            row("a", char = "zz", key = "2026-09-01"), row("b", char = "aa", key = "2026-09-01"),
            row("c", char = "mm", key = "2026-08-30", deleted = true), row("d", char = "aa", key = "2026-09-05"),
        )
        val got = runBlocking { dao.observeCalendarRangeAll("2026-08-30", "2026-09-01").first() }.map { it.dayKey to it.characterUuid }
        assertEquals(listOf("2026-08-30" to "mm", "2026-09-01" to "aa", "2026-09-01" to "zz"), got)
    }

    @Test
    fun 相识日_取非墓碑最早_墓碑更早也忽略_空表null() {
        assertNull(runBlocking { dao.observeFirstDayKey("c1").first() })
        insert(row("a", key = "2026-01-05", deleted = true), row("b", key = "2026-03-10"), row("c", key = "2026-02-20"), row("x", char = "c2", key = "2025-01-01"))
        assertEquals("2026-02-20", runBlocking { dao.observeFirstDayKey("c1").first() })
    }

    @Test
    fun 见面天数_按天计_墓碑不计_他人不计() {
        insert(
            row("a", key = "2026-09-01", hasMeeting = true), row("b", key = "2026-09-02", hasMeeting = true),
            row("c", key = "2026-09-03", hasMeeting = true, deleted = true), row("d", key = "2026-09-04"),
            row("x", char = "c2", key = "2026-09-01", hasMeeting = true),
        )
        assertEquals(2, runBlocking { dao.observeMeetingDayCount("c1").first() })
        assertEquals(0, runBlocking { dao.observeMeetingDayCount("c3").first() })
    }

    @Test
    fun 最近活跃_五列任一为真才算_墓碑与零互动行排除_空表null() {
        assertNull(runBlocking { dao.observeLatestActiveCharacterUuid().first() })
        insert(
            row("a", char = "quiet", key = "2026-09-09"),                                   // 全零：不算
            row("b", char = "dead", key = "2026-09-08", messageCount = 5, deleted = true),   // 墓碑：不算
            row("c", char = "life", key = "2026-09-07", hasLife = true),
            row("d", char = "chat", key = "2026-09-06", messageCount = 1),
        )
        assertEquals("life", runBlocking { dao.observeLatestActiveCharacterUuid().first() })
    }

    @Test
    fun 最近活跃_同日按updatedAtMillis降序() {
        insert(
            row("a", char = "older", key = "2026-09-07", callSeconds = 30, updatedAt = 100L),
            row("b", char = "newer", key = "2026-09-07", hasRelation = true, updatedAt = 200L),
            row("c", char = "earlierDay", key = "2026-09-06", messageCount = 99, updatedAt = 999L),
        )
        assertEquals("newer", runBlocking { dao.observeLatestActiveCharacterUuid().first() })
    }
}
