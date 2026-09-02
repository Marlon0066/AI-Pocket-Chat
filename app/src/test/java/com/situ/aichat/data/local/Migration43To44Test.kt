package com.situ.aichat.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷一《人设编译器》T2-7（图纸 §7.2 · Y-E25）：`MIGRATION_43_44` 在**真 SQLite** 上跑一遍。
 *
 * 与设备侧 `MigrationTest.migration43To44AddsPersonaCompileColumns`（Room `MigrationTestHelper` 逐版本校验
 * schema）的分工：那条证「schema 与 44.json 一致」，本条证「**旧行数据不丢 + 四新列真的落 `''` 且可写**」，
 * 在 JVM 上秒级可跑、每次全量都过（`room-testing` 只在 androidTest 配置里，单测够不着，故用
 * [FrameworkSQLiteOpenHelperFactory] 直接开一个真库，v43 建表语句**逐字取自** `app/schemas/…/43.json`
 * 的 createSql —— PITFALLS §1a「DDL 逐字取导出 schema，绝不手写猜列序」）。
 *
 * 断言从图纸 §表3 与 Y-E25 独立反推：加列而非重建、`TEXT NOT NULL DEFAULT ''`、存量行零丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration43To44Test {

    private lateinit var helper: SupportSQLiteOpenHelper

    @After fun tearDown() { if (::helper.isInitialized) helper.close() }

    /** v43 的 `characters` 建表语句，逐字取自 app/schemas/com.situ.aichat.data.local.AppDatabase/43.json。 */
    private val charactersV43Sql =
        "CREATE TABLE IF NOT EXISTS `characters` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`avatarPath` TEXT, `chatWallpaperPath` TEXT, `systemPrompt` TEXT NOT NULL, " +
            "`personalityDescription` TEXT NOT NULL, `creationDate` INTEGER NOT NULL, `gender` TEXT NOT NULL, " +
            "`birthday` INTEGER, `ageModeRaw` TEXT NOT NULL, `fixedAge` INTEGER NOT NULL, " +
            "`appearanceDescription` TEXT NOT NULL, `occupation` TEXT NOT NULL, `backstory` TEXT NOT NULL, " +
            "`speakingStyle` TEXT NOT NULL, `catchphrases` TEXT NOT NULL, `exampleDialogues` TEXT NOT NULL, " +
            "`initialInterests` TEXT NOT NULL, `memorySummary` TEXT NOT NULL, `previousMemorySummary` TEXT NOT NULL, " +
            "`offlineMeetingMemorySummary` TEXT NOT NULL, `voiceIdentifier` TEXT NOT NULL, " +
            "`remoteVoiceID` TEXT NOT NULL, `ttsEmotionRaw` TEXT NOT NULL, `ttsSpeed` REAL NOT NULL, " +
            "`ttsPitch` INTEGER NOT NULL, `lastMoodEmoji` TEXT NOT NULL, `lastMoodText` TEXT NOT NULL, " +
            "`lastMoodColorName` TEXT NOT NULL, `firstMessageDate` INTEGER, `streakCount` INTEGER NOT NULL, " +
            "`lastChatDate` INTEGER, `personalitySpectrumJSON` TEXT NOT NULL, " +
            "`relationshipQualityJSON` TEXT NOT NULL, `relationshipArchetypeId` TEXT, " +
            "`moodHistoryJSON` TEXT NOT NULL, `dynamicInterestsJSON` TEXT NOT NULL, `growthLogJSON` TEXT NOT NULL, " +
            "`growthMetadataJSON` TEXT NOT NULL, `structuredMemoryJSON` TEXT NOT NULL, " +
            "`structuredMemoryMetadataJSON` TEXT NOT NULL, `previousStructuredMemoryJSON` TEXT NOT NULL, " +
            "`affinitySensePackageJSON` TEXT NOT NULL, `affinitySensePackageGeneratedAt` INTEGER, " +
            "`relationshipMessageCount` INTEGER NOT NULL, `lastRelationshipAnalysisDate` INTEGER, " +
            "`cityName` TEXT, `cityLatitude` REAL, `cityLongitude` REAL, `offlineThemeColorHex` TEXT, " +
            "`joinedWorld` INTEGER NOT NULL, `worldHomeCityId` TEXT NOT NULL, `worldJoinedAt` INTEGER, " +
            "`momentsDigestedUntilMillis` INTEGER NOT NULL, PRIMARY KEY(`uuid`))"

    private fun openV43(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(43) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(charactersV43Sql)
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

    /** v43 里插一个「相处过一阵」的旧角色（现值非中性、有成长元数据）。 */
    private fun insertLegacyCharacter(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO characters (uuid, name, systemPrompt, personalityDescription, creationDate, gender, " +
                "ageModeRaw, fixedAge, appearanceDescription, occupation, backstory, speakingStyle, catchphrases, " +
                "exampleDialogues, initialInterests, memorySummary, previousMemorySummary, " +
                "offlineMeetingMemorySummary, voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, " +
                "lastMoodEmoji, lastMoodText, lastMoodColorName, streakCount, personalitySpectrumJSON, " +
                "relationshipQualityJSON, moodHistoryJSON, dynamicInterestsJSON, growthLogJSON, growthMetadataJSON, " +
                "structuredMemoryJSON, structuredMemoryMetadataJSON, previousStructuredMemoryJSON, " +
                "affinitySensePackageJSON, relationshipMessageCount, joinedWorld, worldHomeCityId, " +
                "momentsDigestedUntilMillis) VALUES " +
                "('char-1', '林晚', 'sys-sentinel', 'persona-sentinel', 100, 'female', 'growing', 0, " +
                "'', '', '', '', '', '', '', '', '', '', '', '', 'auto', 1.0, 0, '', '', 'green', 7, " +
                "'spectrum-sentinel', 'quality-sentinel', '', '', '', 'metadata-sentinel', '', '', '', '', 42, 0, " +
                "'city_yunye', 0)",
        )
    }

    @Test
    fun migrate43To44_addsFourEmptyColumns_andKeepsExistingRow() {
        val db = openV43()
        insertLegacyCharacter(db)

        MIGRATION_43_44.migrate(db)

        db.query(
            "SELECT name, personalityDescription, personalitySpectrumJSON, relationshipQualityJSON, " +
                "growthMetadataJSON, relationshipMessageCount, streakCount, personalityAnchorJSON, " +
                "personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON FROM characters WHERE uuid = 'char-1'",
        ).use { c ->
            assertTrue("旧角色行必须还在（加列而非重建表）", c.moveToFirst())
            assertEquals("林晚", c.getString(0))
            assertEquals("persona-sentinel", c.getString(1))
            assertEquals("现值列一个字节不动", "spectrum-sentinel", c.getString(2))
            assertEquals("quality-sentinel", c.getString(3))
            assertEquals("metadata-sentinel", c.getString(4))
            assertEquals(42, c.getInt(5))
            assertEquals(7, c.getInt(6))
            assertEquals("新列 personalityAnchorJSON 存量行落 ''", "", c.getString(7))
            assertEquals("新列 personaCompileMetaJSON 存量行落 ''", "", c.getString(8))
            assertEquals("新列 personaGainsJSON 存量行落 ''", "", c.getString(9))
            assertEquals("新列 personaOperatorsJSON 存量行落 ''", "", c.getString(10))
        }
        db.query("SELECT COUNT(*) FROM characters").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("行不丢也不多", 1, c.getInt(0))
        }
    }

    @Test
    fun migrate43To44_fourColumnsAreWritable_notJustSchemaNames() {
        val db = openV43()
        insertLegacyCharacter(db)
        MIGRATION_43_44.migrate(db)

        db.execSQL(
            "UPDATE characters SET personalityAnchorJSON = '{\"warmth\":25}', " +
                "personaCompileMetaJSON = '{\"source\":\"compiled\"}', " +
                "personaGainsJSON = '{\"system\":{\"g02\":2}}', " +
                "personaOperatorsJSON = '[{\"id\":\"o1\"}]' WHERE uuid = 'char-1'",
        )
        db.query(
            "SELECT personalityAnchorJSON, personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON " +
                "FROM characters WHERE uuid = 'char-1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("{\"warmth\":25}", c.getString(0))
            assertEquals("{\"source\":\"compiled\"}", c.getString(1))
            assertEquals("{\"system\":{\"g02\":2}}", c.getString(2))
            assertEquals("[{\"id\":\"o1\"}]", c.getString(3))
        }
    }

    @Test
    fun migrate43To44_newRowsGetEmptyStringNotNull() {
        val db = openV43()
        MIGRATION_43_44.migrate(db)
        insertLegacyCharacter(db) // 迁移后插入：四列走 DEFAULT ''

        db.query(
            "SELECT personalityAnchorJSON IS NULL, personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON " +
                "FROM characters WHERE uuid = 'char-1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("NOT NULL DEFAULT '' ⇒ 绝不是 NULL", 0, c.getInt(0))
            assertEquals("", c.getString(1))
            assertEquals("", c.getString(2))
            assertEquals("", c.getString(3))
        }
    }
}
