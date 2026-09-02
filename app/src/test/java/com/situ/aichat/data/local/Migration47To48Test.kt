package com.situ.aichat.data.local

import android.database.SQLException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「我们的日子」卷一《沉淀》T2-4（图纸 §7.2）：`MIGRATION_47_48` 在**真 SQLite** 上跑一遍（照 [Migration43To44Test]）。
 *
 * 与设备侧 `MigrationTest.migration47To48CreatesOurDaysAndAddsBackfillColumn`（Room `MigrationTestHelper` 校验 schema
 * 与 48.json 一致）的分工：本条证「**旧角色行不丢 + 新列落 NULL 且可写 + `our_days` 建成为空 + 唯一索引 (characterUuid, dayKey)
 * 真的拦二插**」，JVM 秒级可跑。v47 `characters` 建表语句与种子 INSERT 的列清单（48 列 = NOT NULL 且无 SQL DEFAULT）
 * **均由脚本从 47.json 机器推导**（PITFALLS §1a），不手写猜列序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration47To48Test {

    private lateinit var helper: SupportSQLiteOpenHelper

    @After fun tearDown() { if (::helper.isInitialized) helper.close() }

    /** v47 的 `characters` 建表语句，逐字取自 app/schemas/com.situ.aichat.data.local.AppDatabase/47.json。 */
    private val charactersV47Sql =
            "CREATE TABLE IF NOT EXISTS `characters` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `avatarPath` TEXT, " +
            "`chatWallpaperPath` TEXT, `systemPrompt` TEXT NOT NULL, `personalityDescription` TEXT NOT NULL, " +
            "`creationDate` INTEGER NOT NULL, `gender` TEXT NOT NULL, `birthday` INTEGER, `ageModeRaw` TEXT NOT NULL, " +
            "`fixedAge` INTEGER NOT NULL, `appearanceDescription` TEXT NOT NULL, `occupation` TEXT NOT NULL, " +
            "`backstory` TEXT NOT NULL, `speakingStyle` TEXT NOT NULL, `catchphrases` TEXT NOT NULL, " +
            "`exampleDialogues` TEXT NOT NULL, `initialInterests` TEXT NOT NULL, `memorySummary` TEXT NOT NULL, " +
            "`previousMemorySummary` TEXT NOT NULL, `offlineMeetingMemorySummary` TEXT NOT NULL, " +
            "`voiceIdentifier` TEXT NOT NULL, `remoteVoiceID` TEXT NOT NULL, `ttsEmotionRaw` TEXT NOT NULL, " +
            "`ttsSpeed` REAL NOT NULL, `ttsPitch` INTEGER NOT NULL, `lastMoodEmoji` TEXT NOT NULL, " +
            "`lastMoodText` TEXT NOT NULL, `lastMoodColorName` TEXT NOT NULL, `firstMessageDate` INTEGER, " +
            "`streakCount` INTEGER NOT NULL, `lastChatDate` INTEGER, `personalitySpectrumJSON` TEXT NOT NULL, " +
            "`relationshipQualityJSON` TEXT NOT NULL, `relationshipPressureJSON` TEXT NOT NULL, " +
            "`relationshipArchetypeId` TEXT, `moodHistoryJSON` TEXT NOT NULL, `dynamicInterestsJSON` TEXT NOT NULL, " +
            "`growthLogJSON` TEXT NOT NULL, `growthMetadataJSON` TEXT NOT NULL, `structuredMemoryJSON` TEXT NOT NULL, " +
            "`structuredMemoryMetadataJSON` TEXT NOT NULL, `previousStructuredMemoryJSON` TEXT NOT NULL, " +
            "`affinitySensePackageJSON` TEXT NOT NULL, `affinitySensePackageGeneratedAt` INTEGER, " +
            "`relationshipMessageCount` INTEGER NOT NULL, `lastRelationshipAnalysisDate` INTEGER, `cityName` TEXT, " +
            "`cityLatitude` REAL, `cityLongitude` REAL, `offlineThemeColorHex` TEXT, `joinedWorld` INTEGER NOT NULL, " +
            "`worldHomeCityId` TEXT NOT NULL, `worldJoinedAt` INTEGER, `momentsDigestedUntilMillis` INTEGER NOT NULL, " +
            "`personalityAnchorJSON` TEXT NOT NULL, `personaCompileMetaJSON` TEXT NOT NULL, " +
            "`personaGainsJSON` TEXT NOT NULL, `personaOperatorsJSON` TEXT NOT NULL, `affectFieldJSON` TEXT NOT NULL, " +
            "`intentQueueJSON` TEXT NOT NULL, PRIMARY KEY(`uuid`))"

    private fun openV47(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(47) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(charactersV47Sql)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_characters_creationDate` ON `characters` (`creationDate`)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null) // 内存库
                .callback(callback)
                .build(),
        )
        return helper.writableDatabase
    }

    /** v47 里插一个旧角色（48 个 NOT NULL 无默认列全给值·脚本从 47.json 推导）。 */
    private fun insertLegacyCharacter(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO characters (" +
                "uuid, name, systemPrompt, personalityDescription, creationDate, gender, ageModeRaw, fixedAge, " +
                "appearanceDescription, occupation, backstory, speakingStyle, catchphrases, exampleDialogues, initialInterests, memorySummary, " +
                "previousMemorySummary, offlineMeetingMemorySummary, voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, " +
                "lastMoodText, lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, relationshipPressureJSON, moodHistoryJSON, dynamicInterestsJSON, " +
                "growthLogJSON, growthMetadataJSON, structuredMemoryJSON, structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, relationshipMessageCount, joinedWorld, " +
                "worldHomeCityId, momentsDigestedUntilMillis, personalityAnchorJSON, personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON, affectFieldJSON, intentQueueJSON" +
                ") VALUES (" +
                "'char-1', '林晚', 'sys-sentinel', 'persona-sentinel', 100, 'female', 'growing', 0, " +
                "'', '', '', '', '', '', '', '', " +
                "'', '', '', '', 'auto', 1.0, 0, '', " +
                "'', 'green', 7, 'spectrum-sentinel', 'quality-sentinel', 'pressure-sentinel', '', '', " +
                "'', 'metadata-sentinel', '', '', '', '', 42, 0, " +
                "'city_yunye', 0, 'anchor-sentinel', '', '', '', 'affect-sentinel', 'intent-sentinel'" +
                ")",
        )
    }

    private fun insertDay(db: SupportSQLiteDatabase, uuid: String, characterUuid: String, dayKey: String) {
        db.execSQL(
            "INSERT INTO our_days (uuid, characterUuid, dayKey, factsJson, messageCount, callSeconds, hasMeeting, hasRelation, " +
                "hasLife, note, factLine, noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, createdAtMillis, " +
                "updatedAtMillis) VALUES ('$uuid', '$characterUuid', '$dayKey', '', 0, 0, 0, 0, 0, '', '', 'none', 0, 0, 0, 0, 1, 1)",
        )
    }

    @Test
    fun migrate47To48_keepsLegacyRow_newColumnNull_andWritable() {
        val db = openV47()
        insertLegacyCharacter(db)

        MIGRATION_47_48.migrate(db)

        db.query(
            "SELECT name, relationshipQualityJSON, affectFieldJSON, intentQueueJSON, relationshipMessageCount, streakCount, " +
                "ourDaysBackfilledAt IS NULL FROM characters WHERE uuid = 'char-1'",
        ).use { c ->
            assertTrue("旧角色行必须还在（加列而非重建表）", c.moveToFirst())
            assertEquals("林晚", c.getString(0))
            assertEquals("quality-sentinel", c.getString(1))
            assertEquals("affect-sentinel", c.getString(2))
            assertEquals("intent-sentinel", c.getString(3))
            assertEquals(42, c.getInt(4))
            assertEquals(7, c.getInt(5))
            assertEquals("新列 ourDaysBackfilledAt 存量行落 NULL = 未回填（E14）", 1, c.getInt(6))
        }
        db.query("SELECT COUNT(*) FROM characters").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("行不丢也不多", 1, c.getInt(0))
        }

        db.execSQL("UPDATE characters SET ourDaysBackfilledAt = 1756800000000 WHERE uuid = 'char-1'")
        db.query("SELECT ourDaysBackfilledAt FROM characters WHERE uuid = 'char-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("新列可写", 1756800000000L, c.getLong(0))
        }
    }

    @Test
    fun migrate47To48_createsEmptyOurDaysTable_withExpectedColumns() {
        val db = openV47()
        MIGRATION_47_48.migrate(db)

        db.query("SELECT COUNT(*) FROM our_days").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("新表建成且为空", 0, c.getInt(0))
        }
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(our_days)").use { c ->
            while (c.moveToNext()) columns.add(c.getString(1))
        }
        assertEquals(
            listOf(
                "uuid", "characterUuid", "dayKey", "factsJson", "messageCount", "callSeconds", "hasMeeting", "hasRelation",
                "hasLife", "note", "factLine", "noteStatus", "noteAttempts", "noteEdited", "hiddenFromMemory", "deleted",
                "generatedAt", "createdAtMillis", "updatedAtMillis", "embedding",
            ),
            columns,
        )
    }

    @Test
    fun migrate47To48_uniqueIndexOnCharacterAndDay_rejectsSecondInsert() {
        val db = openV47()
        insertLegacyCharacter(db)
        MIGRATION_47_48.migrate(db)

        insertDay(db, "d1", "char-1", "2026-09-01")
        insertDay(db, "d2", "char-1", "2026-09-02") // 同角色不同日 OK
        insertDay(db, "d3", "char-2", "2026-09-01") // 不同角色同日 OK
        try {
            insertDay(db, "d4", "char-1", "2026-09-01") // 同 (characterUuid, dayKey) 二插 ⇒ 唯一索引违约
            fail("同 (characterUuid, dayKey) 第二行必须被唯一索引拦下")
        } catch (e: SQLException) {
            // expected
        }
        db.query("SELECT COUNT(*) FROM our_days").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
        }
    }

    @Test
    fun migrate47To48_indicesExist() {
        val db = openV47()
        MIGRATION_47_48.migrate(db)
        val names = mutableListOf<String>()
        db.query("PRAGMA index_list(our_days)").use { c ->
            while (c.moveToNext()) names.add(c.getString(1) + ":" + c.getInt(2))
        }
        assertTrue("普通索引 characterUuid", names.contains("index_our_days_characterUuid:0"))
        assertTrue("唯一索引 (characterUuid, dayKey)", names.contains("index_our_days_characterUuid_dayKey:1"))
    }
}
