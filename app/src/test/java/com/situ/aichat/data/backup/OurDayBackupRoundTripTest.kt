package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OurDayEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「我们的日子」卷一《沉淀》T2-3（图纸 §7.2 · §3.5 · E14）：备份往返 + 真 Room DAO 看门（照 [PromiseBackupTest]）。
 * 断言从图纸 §3.5 / 总图纸 §3.8 独立反推：
 * - 导出 → JSON → 导入：除 `embedding` 外全部列相等；`embedding` **出口即 null**（导出剥向量·卷二重嵌）
 * - 幽灵 characterUuid 行整行跳过；再次恢复按 uuid REPLACE 幂等
 * - 老包（`ourDays == null`）导入零副作用、表空
 * - `CharacterExport.ourDaysBackfilledAt` 往返；老包 JSON 缺该键 ⇒ null（E14：导入后走一次回填）
 * - 真 DAO：`deleteByCharacter` 只清该角色；唯一索引下同 (characterUuid, dayKey) upsert = 覆盖
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDayBackupRoundTripTest {

    private lateinit var src: AppDatabase
    private lateinit var dst: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before fun setUp() { src = newDb(); dst = newDb() }
    @After fun tearDown() { src.close(); dst.close() }

    private fun newDb() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun row(uuid: String, cid: String, dayKey: String, embedding: ByteArray? = null) = OurDayEntity(
        uuid = uuid, characterUuid = cid, dayKey = dayKey, factsJson = """{"messageCount":3}""",
        messageCount = 3, callSeconds = 120, hasMeeting = true, hasRelation = false, hasLife = true,
        note = "手记$uuid", factLine = "事实行$uuid", noteStatus = "ok", noteAttempts = 1, noteEdited = true,
        hiddenFromMemory = false, deleted = false, generatedAt = 5000L, createdAtMillis = 1000L, updatedAtMillis = 2000L,
        embedding = embedding,
    )

    private fun OurDayEntity.withoutEmbedding() = copy(embedding = null)

    @Test
    fun exportImport_rowsEqualExceptEmbedding_whichIsNullOnExit() = runBlocking {
        val a = "charA"
        val dao = src.ourDayDao()
        dao.upsert(row("d1", a, "2026-09-01", embedding = byteArrayOf(1, 2, 3, 4)))
        dao.upsert(row("d2", a, "2026-09-02"))

        val collected = collectOurDays(dao)!!
        val encoded = json.encodeToString(ListSerializer(OurDayExport.serializer()), collected)
        assertFalse("导出 JSON 不含向量", encoded.contains("embedding"))
        val decoded = json.decodeFromString(ListSerializer(OurDayExport.serializer()), encoded)
        restoreOurDays(dst.ourDayDao(), decoded, setOf(a))

        val restored = dst.ourDayDao().getAll().sortedBy { it.uuid }
        assertEquals(
            src.ourDayDao().getAll().map { it.withoutEmbedding() }.sortedBy { it.uuid },
            restored.map { it.withoutEmbedding() },
        )
        assertTrue("恢复后 embedding 一律 null（卷二重嵌）", restored.all { it.embedding == null })
        assertEquals("ok", restored.first { it.uuid == "d1" }.noteStatus)
        assertTrue(restored.first { it.uuid == "d1" }.noteEdited)
    }

    @Test
    fun restore_ghostCharacterRowsSkipped_andReplaceIdempotent() = runBlocking {
        val a = "charA"
        val export = listOf(row("d1", a, "2026-09-01").toExport(), row("d2", "ghostX", "2026-09-01").toExport())
        restoreOurDays(dst.ourDayDao(), export, setOf(a))
        restoreOurDays(dst.ourDayDao(), export, setOf(a))
        assertEquals(listOf("d1"), dst.ourDayDao().getAll().map { it.uuid })
    }

    @Test
    fun oldPackage_nullSection_zeroSideEffects() = runBlocking {
        restoreOurDays(dst.ourDayDao(), null, setOf("charA"))
        assertTrue(dst.ourDayDao().getAll().isEmpty())
        assertNull("空表导出为 null（段不写进包）", collectOurDays(dst.ourDayDao()))
    }

    @Test
    fun oldPackage_missingFields_decodeToDefaults() {
        val minimal = """[{"uuid":"d1","characterUuid":"a","dayKey":"2026-09-01"}]"""
        val e = json.decodeFromString(ListSerializer(OurDayExport.serializer()), minimal).single()
        assertEquals("none", e.noteStatus)
        assertEquals(0, e.noteAttempts)
        assertFalse(e.noteEdited)
        assertFalse(e.deleted)
        assertNull(e.generatedAt)
        val entity = e.toEntity()
        assertEquals("2026-09-01", entity.dayKey)
        assertNull(entity.embedding)
    }

    @Test
    fun characterBackfilledAt_roundTrips_andOldPackageDecodesToNull() {
        val entity = CharacterEntity(uuid = "c1", name = "林晚", creationDate = 100L, ourDaysBackfilledAt = 1756800000000L)
        val export = entity.toExport(avatarArchiveKey = null, chatWallpaperArchiveKey = null)
        val encoded = json.encodeToString(CharacterExport.serializer(), export)
        assertTrue(encoded.contains("\"ourDaysBackfilledAt\":1756800000000"))
        val back = json.decodeFromString(CharacterExport.serializer(), encoded).toEntity(avatarPath = null, chatWallpaperPath = null)
        assertEquals(1756800000000L, back.ourDaysBackfilledAt)

        // 未回填的角色：字段为 null ⇒ encodeDefaults=false 不写键；老包同样无键 ⇒ 解出 null（E14）。
        val old = """{"uuid":"c2","name":"旧角色","creationDate":100}"""
        val decodedOld = json.decodeFromString(CharacterExport.serializer(), old)
        assertNull(decodedOld.ourDaysBackfilledAt)
        assertNull(decodedOld.toEntity(avatarPath = null, chatWallpaperPath = null).ourDaysBackfilledAt)
    }

    @Test
    fun dao_deleteByCharacter_clearsOnlyThatCharacter_andUpsertReplacesSameUuid() = runBlocking {
        val dao = src.ourDayDao()
        dao.upsert(row("d1", "charA", "2026-09-01"))
        dao.upsert(row("d2", "charB", "2026-09-01"))
        dao.upsert(row("d1", "charA", "2026-09-01").copy(note = "改写"))
        assertEquals("同 uuid upsert 覆盖不新增", 2, dao.getAll().size)
        assertEquals("改写", dao.byDay("charA", "2026-09-01")!!.note)

        dao.deleteByCharacter("charA")
        assertEquals(listOf("d2"), dao.getAll().map { it.uuid })
        assertEquals(1, dao.countForCharacter("charB"))
        assertEquals(0, dao.countForCharacter("charA"))
    }
}
