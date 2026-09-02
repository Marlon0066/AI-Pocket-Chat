package com.situ.aichat

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.situ.aichat.data.local.ALL_MIGRATIONS
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.MIGRATION_13_14
import com.situ.aichat.data.local.MIGRATION_18_19
import com.situ.aichat.data.local.MIGRATION_19_20
import com.situ.aichat.data.local.MIGRATION_20_21
import com.situ.aichat.data.local.MIGRATION_21_22
import com.situ.aichat.data.local.MIGRATION_22_23
import com.situ.aichat.data.local.MIGRATION_23_24
import com.situ.aichat.data.local.MIGRATION_24_25
import com.situ.aichat.data.local.MIGRATION_25_26
import com.situ.aichat.data.local.MIGRATION_26_27
import com.situ.aichat.data.local.MIGRATION_27_28
import com.situ.aichat.data.local.MIGRATION_28_29
import com.situ.aichat.data.local.MIGRATION_29_30
import com.situ.aichat.data.local.MIGRATION_30_31
import com.situ.aichat.data.local.MIGRATION_32_33
import com.situ.aichat.data.local.MIGRATION_33_34
import com.situ.aichat.data.local.MIGRATION_34_35
import com.situ.aichat.data.local.MIGRATION_35_36
import com.situ.aichat.data.local.MIGRATION_36_37
import com.situ.aichat.data.local.MIGRATION_37_38
import com.situ.aichat.data.local.MIGRATION_38_39
import com.situ.aichat.data.local.MIGRATION_39_40
import com.situ.aichat.data.local.MIGRATION_40_41
import com.situ.aichat.data.local.MIGRATION_41_42
import com.situ.aichat.data.local.MIGRATION_42_43
import com.situ.aichat.data.local.MIGRATION_43_44
import com.situ.aichat.data.local.MIGRATION_44_45
import com.situ.aichat.data.local.MIGRATION_45_46
import com.situ.aichat.data.local.MIGRATION_46_47
import com.situ.aichat.data.local.MIGRATION_47_48
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 显式迁移链校验（P12.2「数据保命」）。用 [MigrationTestHelper] 按导出的 schema 快照（app/schemas/，已作为
 * androidTest 资产）逐版本校验：每步迁移后的真实 schema 必须与 N.json 完全一致，否则失败——这正是防止「升级静默清库」
 * 的关键保障。**设备/模拟器上运行**（批末期集中验，对齐既有 SherpaSttEngineDeviceTest 的 androidTest 约定）。
 *
 * 新增 DB 版本时：升 @Database.version + 追加 MIGRATION_n_(n+1) + 在 [LATEST_VERSION] 提升后，本测试自动覆盖新步。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /** 逐版本：v1 起每步迁移后对照 N.json 校验 schema（含被删表检查）。覆盖到 [LATEST_VERSION] 的每一步。 */
    @Test
    fun migratesEachStepValidatingSchema() {
        helper.createDatabase(TEST_DB, 1).close()
        for (version in 2..LATEST_VERSION) {
            helper.runMigrationsAndValidate(TEST_DB, version, true, *ALL_MIGRATIONS).close()
        }
    }

    /** 一次性：从 v1 直接迁到最新，校验最终 schema（多步连续应用路径）。 */
    @Test
    fun migratesV1ToLatestAtOnce() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *ALL_MIGRATIONS).close()
    }

    /**
     * 数据保命回归：v13 插入一条会话 → 迁到 v14（新增含 NOT NULL 列）→ 旧行必须存活，且 NOT NULL 新列按实体默认值回填
     * （isInOfflineMode=0 / currentSceneProgress='' / pendingOfflineSummaryFailCount=0 / offlineSummaryFallbackSessionIds=''）。
     * 守护：将来若有人把某步迁移误改成「建新表+丢弃旧表」会在此被抓到。
     */
    @Test
    fun migration13To14PreservesRowsAndBackfillsOfflineDefaults() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14).use { db ->
            db.query(
                "SELECT uuid, isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds FROM conversations",
            ).use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
                assertEquals(0, c.getInt(1))
                assertEquals("", c.getString(2))
                assertEquals(0, c.getInt(3))
                assertEquals("", c.getString(4))
            }
        }
    }

    /**
     * v18→v19（未来约定见面）：纯增量迁移。v18 插一条会话 → 迁到 v19 → 旧行必须存活，且新表 meeting_appointments
     * 被建出且为空（不带入任何数据）。守护：将来若有人把此步误改成「建新表+丢旧表」或漏建新表会在此被抓到
     *（runMigrationsAndValidate 已对照 19.json 校验 schema 一致，本测试再加「旧数据存活 + 新表可用」回归）。
     */
    @Test
    fun migration18To19AddsMeetingTableAndPreservesRows() {
        helper.createDatabase(TEST_DB, 18).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // v18 的 conversations 已含 14→ 的线下列（Room 生成的列无 SQL DEFAULT，须显式给全 NOT NULL 列）。
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 19, true, MIGRATION_18_19).use { db ->
            db.query("SELECT uuid FROM conversations").use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
            }
            // 新表已建且空（纯增量·不带入任何数据）。
            db.query("SELECT COUNT(*) FROM meeting_appointments").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    /**
     * v19→v20（识别扫描节奏）：纯增量·conversations 加 lastMeetingScanSuccessDate/FailureDate 两列。
     * v19 插一条会话 → 迁到 v20 → 旧行存活、新列可读且默认 NULL。守护漏列/误改。
     */
    @Test
    fun migration19To20AddsMeetingScanColumnsAndPreservesRows() {
        helper.createDatabase(TEST_DB, 19).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 20, true, MIGRATION_19_20).use { db ->
            db.query("SELECT uuid, lastMeetingScanSuccessDate, lastMeetingScanFailureDate FROM conversations").use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
                assertTrue("新列 success 默认应为 NULL", c.isNull(1))
                assertTrue("新列 failure 默认应为 NULL", c.isNull(2))
            }
        }
    }

    /**
     * v20→v21（世界书 WB1）：纯增量·新建 world_books / world_book_entries / world_book_bindings /
     * world_book_timed_states 四表 + 3 索引。v20 插一条会话 → 迁到 v21 → 旧行存活、四张新表建成且为空。
     * 守护漏表 / 误动旧表。
     */
    @Test
    fun migration20To21CreatesWorldBookTablesAndPreservesRows() {
        helper.createDatabase(TEST_DB, 20).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21).use { db ->
            db.query("SELECT uuid FROM conversations").use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
            }
            listOf("world_books", "world_book_entries", "world_book_bindings", "world_book_timed_states")
                .forEach { table ->
                    db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                        assertTrue(c.moveToFirst())
                        assertEquals("新表 $table 应建成且为空", 0, c.getInt(0))
                    }
                }
        }
    }

    /**
     * v21→v22（日记重设计 R3/R4）：diary_comments 加 parentCommentId/isFromUser、diary_entries 加
     * authorCharacterUuid、新建 diary_reactions。v21 插一条日记 + 一条评论 → 迁到 v22 → 旧行存活、
     * 新列按默认回填（NULL / 0 / NULL）、reactions 表建成且为空。守护漏列 / 误动旧表。
     */
    @Test
    fun migration21To22AddsDiarySocialColumnsAndPreservesRows() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO diary_entries (uuid, content, timestamp, imagePathsJson, isAutoGenerated, isDraft, " +
                    "isPetDiary, visibilityRaw, triggerTypeRaw) " +
                    "VALUES ('d1', '正文', 100, '', 0, 0, 0, 'openToAI', 'auto_draft')",
            )
            execSQL(
                "INSERT INTO diary_comments (id, entryUuid, content, timestamp, characterUuid) " +
                    "VALUES ('cm1', 'd1', '评论', 200, 'char1')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22).use { db ->
            db.query("SELECT uuid, authorCharacterUuid FROM diary_entries").use { c ->
                assertTrue("迁移后旧日记行应存活", c.moveToFirst())
                assertEquals("d1", c.getString(0))
                assertTrue("新列 authorCharacterUuid 默认应为 NULL（=用户日记）", c.isNull(1))
            }
            db.query("SELECT id, parentCommentId, isFromUser FROM diary_comments").use { c ->
                assertTrue("迁移后旧评论行应存活", c.moveToFirst())
                assertEquals("cm1", c.getString(0))
                assertTrue("新列 parentCommentId 默认应为 NULL（=顶层评论）", c.isNull(1))
                assertEquals("新列 isFromUser 默认应为 0", 0, c.getInt(2))
            }
            db.query("SELECT COUNT(*) FROM diary_reactions").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("新表 diary_reactions 应建成且为空", 0, c.getInt(0))
            }
        }
    }

    /**
     * v22→v23（故事×世界书 ST5）：stories 加 worldInfoEnabled（NOT NULL 默认 1 = 世界观参与生成默认开）。
     * v22 插一条故事 → 迁到 v23 → 旧行存活、新列按默认回填 1。守护漏列 / 误动旧表。
     */
    @Test
    fun migration22To23AddsStoryWorldInfoFlagAndPreservesRows() {
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO stories (id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
                    "chapterLengthPreference, autoExtendCount, chatInfluenceWeight, narrativePerson, updateMode, " +
                    "unlockHour, unlockMinute, status, cachedChapterCount, cachedHasPendingChoice) " +
                    "VALUES ('s1', '书名', '言情', 'sunset', 0, 0, '古风', 1500, 0, 'medium', 'second', 'free', " +
                    "20, 0, 'serializing', 0, 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 23, true, MIGRATION_22_23).use { db ->
            db.query("SELECT id, worldInfoEnabled FROM stories").use { c ->
                assertTrue("迁移后旧故事行应存活", c.moveToFirst())
                assertEquals("s1", c.getString(0))
                assertEquals("新列 worldInfoEnabled 默认应为 1（世界观参与生成默认开）", 1, c.getInt(1))
            }
        }
    }

    /**
     * v23→v24（日记 R5 月度回顾）：纯增量·新建 monthly_reviews（monthStartMillis 唯一 = 每月一篇幂等）。
     * v23 插一条日记 → 迁到 v24 → 旧行存活、新表建成且为空、唯一索引生效（IGNORE 第二次插同月不落）。
     */
    @Test
    fun migration23To24CreatesMonthlyReviewsAndPreservesRows() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO diary_entries (uuid, content, timestamp, imagePathsJson, isAutoGenerated, isDraft, " +
                    "isPetDiary, visibilityRaw, triggerTypeRaw) " +
                    "VALUES ('d1', '正文', 100, '', 0, 0, 0, 'openToAI', 'auto_draft')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 24, true, MIGRATION_23_24).use { db ->
            db.query("SELECT uuid FROM diary_entries").use { c ->
                assertTrue("迁移后旧日记行应存活", c.moveToFirst())
                assertEquals("d1", c.getString(0))
            }
            db.execSQL(
                "INSERT INTO monthly_reviews (uuid, monthStartMillis, content, moodCountsJson, generatedAt) " +
                    "VALUES ('r1', 1000, 'c', '', 0)",
            )
            db.execSQL(
                "INSERT OR IGNORE INTO monthly_reviews (uuid, monthStartMillis, content, moodCountsJson, generatedAt) " +
                    "VALUES ('r2', 1000, 'c2', '', 0)",
            )
            db.query("SELECT COUNT(*) FROM monthly_reviews").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("同月第二篇应被唯一索引 IGNORE", 1, c.getInt(0))
            }
        }
    }

    /**
     * v24→v25（日记 R6-3① 孤儿信 A）：diary_entries 加 authorNameSnapshot，并一次性从 characters 回填存量
     * 交换日记的作者名。构造：一个角色 char1「小满」 + 三条日记（作者在的交换信 / 用户日记 / 作者已删的孤儿信）→
     * 迁到 v25 → 作者在的信回填「小满」、用户日记与孤儿信保持 NULL、旧行全存活。守护漏列 / 回填 SQL 写错。
     */
    @Test
    fun migration24To25AddsAuthorNameSnapshotAndBackfillsFromCharacters() {
        helper.createDatabase(TEST_DB, 24).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // characters 只给全部 NOT NULL 列（可空列省略 = NULL）。
            execSQL(
                "INSERT INTO characters (uuid, name, systemPrompt, personalityDescription, creationDate, gender, " +
                    "ageModeRaw, fixedAge, appearanceDescription, occupation, backstory, speakingStyle, catchphrases, " +
                    "exampleDialogues, initialInterests, memorySummary, previousMemorySummary, offlineMeetingMemorySummary, " +
                    "voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, lastMoodText, " +
                    "lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, moodHistoryJSON, " +
                    "dynamicInterestsJSON, growthLogJSON, growthMetadataJSON, structuredMemoryJSON, " +
                    "structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, " +
                    "relationshipMessageCount) " +
                    "VALUES ('char1', '小满', '', '', 0, '', '', 0, '', '', '', '', '', '', '', '', '', '', '', '', " +
                    "'', 1.0, 0, '', '', 'blue', 0, '', '', '', '', '', '', '', '', '', '', 0)",
            )
            // 作者仍在的交换信 / 用户日记 / 作者已删的孤儿信。
            execSQL(
                "INSERT INTO diary_entries (uuid, content, timestamp, imagePathsJson, isAutoGenerated, isDraft, " +
                    "isPetDiary, visibilityRaw, triggerTypeRaw, authorCharacterUuid) " +
                    "VALUES ('d_ex', '信', 100, '', 0, 0, 0, 'openToAI', 'exchange', 'char1')",
            )
            execSQL(
                "INSERT INTO diary_entries (uuid, content, timestamp, imagePathsJson, isAutoGenerated, isDraft, " +
                    "isPetDiary, visibilityRaw, triggerTypeRaw) " +
                    "VALUES ('d_user', '我的', 100, '', 0, 0, 0, 'openToAI', 'auto_draft')",
            )
            execSQL(
                "INSERT INTO diary_entries (uuid, content, timestamp, imagePathsJson, isAutoGenerated, isDraft, " +
                    "isPetDiary, visibilityRaw, triggerTypeRaw, authorCharacterUuid) " +
                    "VALUES ('d_orphan', '孤儿信', 100, '', 0, 0, 0, 'openToAI', 'exchange', 'ghost')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 25, true, MIGRATION_24_25).use { db ->
            db.query("SELECT uuid, authorNameSnapshot FROM diary_entries ORDER BY uuid").use { c ->
                val snap = mutableMapOf<String, String?>()
                while (c.moveToNext()) snap[c.getString(0)] = if (c.isNull(1)) null else c.getString(1)
                assertEquals("三条旧日记全存活", 3, snap.size)
                assertEquals("作者仍在 → 回填作者名", "小满", snap["d_ex"])
                assertTrue("用户日记 → 快照 NULL", snap["d_user"] == null)
                assertTrue("作者已删的孤儿信 → 子查询 NULL → 保持 NULL", snap["d_orphan"] == null)
            }
        }
    }

    /**
     * v25→v26（世界系统 W1 数据底座）：新建 8 张世界表 + 3 索引，characters 加 3 列
     * （joinedWorld/worldHomeCityId/worldJoinedAt）。v25 插一个角色 → 迁到 v26 → 旧角色行存活、
     * 三新列按默认回填（0 / 'city_yunye' / NULL = 不加入世界 + 家乡城）、8 张新表建成且为空。
     * 守护漏表 / 漏列 / 误动旧表 / 回填默认写错。
     */
    @Test
    fun migration25To26AddsWorldTablesAndCharacterColumnsAndPreservesRows() {
        helper.createDatabase(TEST_DB, 25).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // characters 只给全部 NOT NULL 列（可空列省略 = NULL）；列集同 v24（此步不改 characters 既有列）。
            execSQL(
                "INSERT INTO characters (uuid, name, systemPrompt, personalityDescription, creationDate, gender, " +
                    "ageModeRaw, fixedAge, appearanceDescription, occupation, backstory, speakingStyle, catchphrases, " +
                    "exampleDialogues, initialInterests, memorySummary, previousMemorySummary, offlineMeetingMemorySummary, " +
                    "voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, lastMoodText, " +
                    "lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, moodHistoryJSON, " +
                    "dynamicInterestsJSON, growthLogJSON, growthMetadataJSON, structuredMemoryJSON, " +
                    "structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, " +
                    "relationshipMessageCount) " +
                    "VALUES ('char1', '小满', '', '', 0, '', '', 0, '', '', '', '', '', '', '', '', '', '', '', '', " +
                    "'', 1.0, 0, '', '', 'blue', 0, '', '', '', '', '', '', '', '', '', '', 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 26, true, MIGRATION_25_26).use { db ->
            db.query("SELECT uuid, joinedWorld, worldHomeCityId, worldJoinedAt FROM characters").use { c ->
                assertTrue("迁移后旧角色行应存活", c.moveToFirst())
                assertEquals("char1", c.getString(0))
                assertEquals("新列 joinedWorld 默认应为 0（不加入世界）", 0, c.getInt(1))
                assertEquals("新列 worldHomeCityId 默认应为 'city_yunye'（家乡城）", "city_yunye", c.getString(2))
                assertTrue("新列 worldJoinedAt 默认应为 NULL", c.isNull(3))
            }
            listOf(
                "world_state", "world_travel", "world_native_state", "world_relationship",
                "world_relationship_event", "world_event", "world_city_lore", "world_discovery",
            ).forEach { table ->
                db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("新表 $table 应建成且为空", 0, c.getInt(0))
                }
            }
        }
    }

    /**
     * v26→v27（故事 ST8 结局档案）：stories 加 finalEndingType（可空 TEXT·完结结局类型徽章数据源）。
     * v26 插一条故事（含 v23 起的 worldInfoEnabled）→ 迁到 v27 → 旧行存活、新列可读且默认 NULL。守护漏列 / 误动旧表。
     */
    @Test
    fun migration26To27AddsStoryFinalEndingTypeColumnAndPreservesRows() {
        helper.createDatabase(TEST_DB, 26).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO stories (id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
                    "chapterLengthPreference, autoExtendCount, chatInfluenceWeight, narrativePerson, updateMode, " +
                    "unlockHour, unlockMinute, status, cachedChapterCount, cachedHasPendingChoice, worldInfoEnabled) " +
                    "VALUES ('s1', '书名', '言情', 'sunset', 0, 0, '古风', 1500, 0, 'medium', 'second', 'free', " +
                    "20, 0, 'completed', 0, 0, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 27, true, MIGRATION_26_27).use { db ->
            db.query("SELECT id, finalEndingType FROM stories").use { c ->
                assertTrue("迁移后旧故事行应存活", c.moveToFirst())
                assertEquals("s1", c.getString(0))
                assertTrue("新列 finalEndingType 默认应为 NULL（=非用户请求的自然结局）", c.isNull(1))
            }
        }
    }

    /**
     * v27→v28（世界系统 W5 联动闭环）：纯增量·新建 world_memory / world_bulletin / world_llm_spend 三表
     * （world_memory 两索引）。v27 插一条会话 → 迁到 v28 → 旧行存活、三张新表建成且为空、world_memory
     * embedding 可空、world_llm_spend 复合主键（同 (day,category) 第二次 IGNORE）。守护漏表 / 漏索引 / 误动旧表。
     */
    @Test
    fun migration27To28CreatesWorldLinkTablesAndPreservesRows() {
        helper.createDatabase(TEST_DB, 27).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds, lastMeetingScanSuccessDate, lastMeetingScanFailureDate) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '', NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 28, true, MIGRATION_27_28).use { db ->
            db.query("SELECT uuid FROM conversations").use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
            }
            listOf("world_memory", "world_bulletin", "world_llm_spend").forEach { table ->
                db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("新表 $table 应建成且为空", 0, c.getInt(0))
                }
            }
            // world_memory embedding 可空 + happenedAt 索引可用（按索引列查不报错）。
            db.execSQL(
                "INSERT INTO world_memory (uuid, characterUuid, otherIdsJson, kindRaw, content, happenedAt, " +
                    "sourceUuid, createdAt) VALUES ('m1', 'char1', '[]', 'rel_first_meet', '正文', 100, 'src1', 100)",
            )
            db.query("SELECT embedding FROM world_memory WHERE happenedAt = 100").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("embedding 未写 → NULL", c.isNull(0))
            }
            // world_llm_spend 复合主键：同 (day, category) 第二次 IGNORE。
            db.execSQL("INSERT INTO world_llm_spend (epochDay, category, count) VALUES (1, 'bulletin', 1)")
            db.execSQL("INSERT OR IGNORE INTO world_llm_spend (epochDay, category, count) VALUES (1, 'bulletin', 9)")
            db.query("SELECT count FROM world_llm_spend WHERE epochDay = 1 AND category = 'bulletin'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("复合主键第二次 IGNORE·count 仍为 1", 1, c.getInt(0))
            }
        }
    }

    /**
     * v28→v29（线下见面「梦剧场」B 部）：纯增量·新建 offline_meeting_memories（两索引 characterUuid/sessionId·
     * initiatedByUser 可空）。v28 插一条会话 → 迁到 v29 → 旧行存活、新表建成且为空、可空列写 NULL 可读、按 sessionId
     * 索引查不报错。守护漏表 / 漏索引 / 误动旧表。
     */
    @Test
    fun migration28To29CreatesOfflineMeetingMemoriesAndPreservesRows() {
        helper.createDatabase(TEST_DB, 28).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds, lastMeetingScanSuccessDate, lastMeetingScanFailureDate) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '', NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 29, true, MIGRATION_28_29).use { db ->
            db.query("SELECT uuid FROM conversations").use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM `offline_meeting_memories`").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("新表 offline_meeting_memories 应建成且为空", 0, c.getInt(0))
            }
            // 可空列 initiatedByUser 写 NULL 可读 + sessionId 索引可用（按索引列查不报错）。
            db.execSQL(
                "INSERT INTO offline_meeting_memories (uuid, characterUuid, conversationUuid, sessionId, kindRaw, " +
                    "startedAtMillis, endedAtMillis, location, activity, moodRaw, initiatedByUser, messageCount, " +
                    "summary, highlightsJson, promisesJson, sourceRaw, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('m1', 'char1', '', 'sess1', 'meeting', 100, 200, '公园', '散步', 'warm', NULL, 5, " +
                    "'摘要', '[]', '[]', 'llm', 100, 100)",
            )
            db.query("SELECT initiatedByUser FROM offline_meeting_memories WHERE sessionId = 'sess1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("initiatedByUser 未写 → NULL", c.isNull(0))
            }
        }
    }

    /**
     * v29→v30（活人感一期 P2 承诺回连）：纯增量·新建 open_loops（两索引 conversationUuid/characterUuid·
     * dueAt/resolvedAt 可空）+ conversations 加 lastOpenLoopScanSuccessDate/FailureDate 两列。v29 插一条会话 →
     * 迁到 v30 → 旧行存活、新列默认 NULL、新表建成且为空、两索引可用（按索引列查不报错）。守护漏表 / 漏列 / 漏索引 / 误动旧表。
     */
    @Test
    fun migration29To30CreatesOpenLoopsAndAddsScanColumnsAndPreservesRows() {
        helper.createDatabase(TEST_DB, 29).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds, lastMeetingScanSuccessDate, lastMeetingScanFailureDate) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '', NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 30, true, MIGRATION_29_30).use { db ->
            db.query("SELECT uuid, lastOpenLoopScanSuccessDate, lastOpenLoopScanFailureDate FROM conversations").use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
                assertTrue("新列 lastOpenLoopScanSuccessDate 默认应为 NULL", c.isNull(1))
                assertTrue("新列 lastOpenLoopScanFailureDate 默认应为 NULL", c.isNull(2))
            }
            db.query("SELECT COUNT(*) FROM `open_loops`").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("新表 open_loops 应建成且为空", 0, c.getInt(0))
            }
            // 可空列 dueAt/resolvedAt 写 NULL 可读 + characterUuid 索引可用（按索引列查不报错）。
            db.execSQL(
                "INSERT INTO open_loops (uuid, conversationUuid, characterUuid, content, typeRaw, dueAt, " +
                    "statusRaw, createdAt, resolvedAt) " +
                    "VALUES ('o1', 'c1', 'char1', '就是今天要问的事', 'user_event', NULL, 'open', 100, NULL)",
            )
            db.query("SELECT dueAt, resolvedAt FROM open_loops WHERE characterUuid = 'char1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("dueAt 未写 → NULL", c.isNull(0))
                assertTrue("resolvedAt 未写 → NULL", c.isNull(1))
            }
        }
    }

    /**
     * v30→v31（世界二期战役 B·用户自建居民）：纯增量·新建 world_user_resident（PK slug·avatarPath 可空）。
     * v30 插一条会话 → 迁到 v31 → 旧行存活、新表建成且为空、PK 唯一（第二次同 slug IGNORE）、可空列写 NULL 可读。
     * 守护漏表 / 误动旧表。
     */
    @Test
    fun migration30To31CreatesUserResidentTableAndPreservesRows() {
        helper.createDatabase(TEST_DB, 30).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds, lastMeetingScanSuccessDate, lastMeetingScanFailureDate, " +
                    "lastOpenLoopScanSuccessDate, lastOpenLoopScanFailureDate) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '', " +
                    "NULL, NULL, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 31, true, MIGRATION_30_31).use { db ->
            db.query("SELECT uuid FROM conversations").use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM `world_user_resident`").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("新表 world_user_resident 应建成且为空", 0, c.getInt(0))
            }
            // PK 唯一（第二次同 slug IGNORE）+ 可空 avatarPath 写 NULL 可读。
            db.execSQL(
                "INSERT INTO world_user_resident (slug, name, gender, age, cityId, occupation, personaBrief, " +
                    "traitsJson, freeformLore, initialRelationText, fuelBias, avatarPath, createdAt) " +
                    "VALUES ('resident_ab12cd34', '江晚棠', 'female', 26, 'city_yunye', '旧书店店主', '安静', " +
                    "'[\"温吞\"]', '', '', 'balanced', NULL, 100)",
            )
            db.execSQL(
                "INSERT OR IGNORE INTO world_user_resident (slug, name, gender, age, cityId, occupation, personaBrief, " +
                    "traitsJson, freeformLore, initialRelationText, fuelBias, avatarPath, createdAt) " +
                    "VALUES ('resident_ab12cd34', '撞名', 'male', 30, 'city_yunye', '', '', '[]', '', '', 'gift', 'p', 200)",
            )
            db.query("SELECT COUNT(*), MAX(avatarPath IS NULL) FROM world_user_resident WHERE slug = 'resident_ab12cd34'")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("同 slug 第二次应被 PK IGNORE", 1, c.getInt(0))
                    assertEquals("avatarPath 未写 → NULL", 1, c.getInt(1))
                }
        }
    }

    /**
     * v32→v33（记忆改造一期·部件① 承诺账本 + 三消化标记列）：新建 promises 表 + characters 加
     * momentsDigestedUntilMillis（NOT NULL 默认 0）+ offline_meeting_memories/diary_entries 各加
     * digestedAtMillis（可空）。v32 插一个角色 + 一条见面档案 + 一条日记 → 迁到 v33 → 旧行存活、
     * promises 建成且为空可插、三新列默认值正确（0 / NULL / NULL）。守护漏表 / 漏列 / 误动旧表。
     */
    @Test
    fun migration32To33CreatesPromisesAndAddsDigestColumnsAndPreservesRows() {
        helper.createDatabase(TEST_DB, 32).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // characters 只给全部 NOT NULL 列（v26 起含 joinedWorld/worldHomeCityId·可空列省略 = NULL）。
            execSQL(
                "INSERT INTO characters (uuid, name, systemPrompt, personalityDescription, creationDate, gender, " +
                    "ageModeRaw, fixedAge, appearanceDescription, occupation, backstory, speakingStyle, catchphrases, " +
                    "exampleDialogues, initialInterests, memorySummary, previousMemorySummary, offlineMeetingMemorySummary, " +
                    "voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, lastMoodText, " +
                    "lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, moodHistoryJSON, " +
                    "dynamicInterestsJSON, growthLogJSON, growthMetadataJSON, structuredMemoryJSON, " +
                    "structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, " +
                    "relationshipMessageCount, joinedWorld, worldHomeCityId) " +
                    "VALUES ('char1', '小满', '', '', 0, '', '', 0, '', '', '', '', '', '', '', '', '', '', '', '', " +
                    "'', 1.0, 0, '', '', 'blue', 0, '', '', '', '', '', '', '', '', '', '', 0, 0, 'city_yunye')",
            )
            execSQL(
                "INSERT INTO offline_meeting_memories (uuid, characterUuid, conversationUuid, sessionId, kindRaw, " +
                    "startedAtMillis, endedAtMillis, location, activity, moodRaw, initiatedByUser, messageCount, " +
                    "summary, highlightsJson, promisesJson, sourceRaw, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('mm1', 'char1', '', 'sess1', 'meeting', 100, 200, '公园', '散步', 'warm', NULL, 5, " +
                    "'摘要', '[]', '[]', 'llm', 100, 100)",
            )
            execSQL(
                "INSERT INTO diary_entries (uuid, content, timestamp, imagePathsJson, isAutoGenerated, isDraft, " +
                    "isPetDiary, visibilityRaw, triggerTypeRaw) " +
                    "VALUES ('d1', '正文', 100, '', 0, 0, 0, 'openToAI', 'exchange')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 33, true, MIGRATION_32_33).use { db ->
            // 角色旧行存活 + 新列 momentsDigestedUntilMillis 默认 0。
            db.query("SELECT uuid, momentsDigestedUntilMillis FROM characters").use { c ->
                assertTrue("迁移后旧角色行应存活", c.moveToFirst())
                assertEquals("char1", c.getString(0))
                assertEquals("新列 momentsDigestedUntilMillis 默认应为 0（从未消化）", 0, c.getInt(1))
            }
            // 见面档案旧行存活 + digestedAtMillis 默认 NULL。
            db.query("SELECT uuid, digestedAtMillis FROM offline_meeting_memories").use { c ->
                assertTrue("迁移后旧见面档案行应存活", c.moveToFirst())
                assertEquals("mm1", c.getString(0))
                assertTrue("新列 digestedAtMillis 默认应为 NULL（未消化）", c.isNull(1))
            }
            // 日记旧行存活 + digestedAtMillis 默认 NULL。
            db.query("SELECT uuid, digestedAtMillis FROM diary_entries").use { c ->
                assertTrue("迁移后旧日记行应存活", c.moveToFirst())
                assertEquals("d1", c.getString(0))
                assertTrue("新列 digestedAtMillis 默认应为 NULL（未消化）", c.isNull(1))
            }
            // 新表 promises 建成且为空 + 可空列写 NULL 可读 + characterUuid 索引可用（按索引列查不报错）。
            db.query("SELECT COUNT(*) FROM `promises`").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("新表 promises 应建成且为空", 0, c.getInt(0))
            }
            db.execSQL(
                "INSERT INTO promises (uuid, characterUuid, conversationUuid, content, statusRaw, dueAtMillis, " +
                    "sourceRaw, sourceSessionId, openLoopUuid, resolvedAtMillis, resolutionEvidence, " +
                    "createdAtMillis, updatedAtMillis) " +
                    "VALUES ('p1', 'char1', 'c1', '周末一起去看展', 'open', NULL, 'chat', '', NULL, NULL, '', 100, 100)",
            )
            db.query("SELECT dueAtMillis, resolvedAtMillis FROM promises WHERE characterUuid = 'char1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("dueAtMillis 未写 → NULL", c.isNull(0))
                assertTrue("resolvedAtMillis 未写 → NULL", c.isNull(1))
            }
        }
    }

    /**
     * v33→v34（记忆改造二期·部件⑤ 场内前情提要）：conversations 加三列 inSceneRecapText（TEXT NOT NULL 默认 ''）、
     * inSceneRecapSessionKey（TEXT NOT NULL 默认 ''）、inSceneRecapUntilMillis（INTEGER NOT NULL 默认 0）。
     * v33 插一条会话 → 迁到 v34 → 旧行存活、三新列默认值正确（'' / '' / 0）。守护漏列 / 误动旧表。
     */
    @Test
    fun migration33To34AddsRecapColumnsAndPreservesRows() {
        helper.createDatabase(TEST_DB, 33).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO conversations (uuid, title, characterUuid, creationDate, isPinned, isArchived, " +
                    "isReservedForNotifications, lastMessagePreview, lastMessageRole, moodEmoji, moodText, " +
                    "moodColorName, cachedUnreadCount, voiceRoundsSinceLastVoice, voiceNextThreshold, " +
                    "isInOfflineMode, currentSceneProgress, pendingOfflineSummaryFailCount, " +
                    "offlineSummaryFallbackSessionIds) " +
                    "VALUES ('c1', 'Hi', 'char1', 0, 0, 0, 0, '', 'assistant', '😀', '', 'blue', 0, 0, 3, 0, '', 0, '')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 34, true, MIGRATION_33_34).use { db ->
            db.query(
                "SELECT uuid, inSceneRecapText, inSceneRecapSessionKey, inSceneRecapUntilMillis FROM conversations",
            ).use { c ->
                assertTrue("迁移后旧会话行应存活", c.moveToFirst())
                assertEquals("c1", c.getString(0))
                assertEquals("新列 inSceneRecapText 默认应为空串", "", c.getString(1))
                assertEquals("新列 inSceneRecapSessionKey 默认应为空串", "", c.getString(2))
                assertEquals("新列 inSceneRecapUntilMillis 默认应为 0", 0L, c.getLong(3))
            }
        }
    }

    /**
     * v34→v35（记忆改造四期·部件⑥ 见面档案入向量索引）：纯增量·offline_meeting_memories 加 embedding（可空 BLOB）。
     * v34 插一条见面档案（无 embedding 列）→ 迁到 v35 → 旧行存活、新列可读且默认 NULL。守护漏列 / 误动旧表。
     */
    @Test
    fun migration34To35AddsMeetingEmbeddingColumnAndPreservesRows() {
        helper.createDatabase(TEST_DB, 34).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO offline_meeting_memories (uuid, characterUuid, conversationUuid, sessionId, kindRaw, " +
                    "startedAtMillis, endedAtMillis, location, activity, moodRaw, initiatedByUser, messageCount, " +
                    "summary, highlightsJson, promisesJson, sourceRaw, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('mm1', 'char1', '', 'sess1', 'meeting', 100, 200, '公园', '散步', 'warm', NULL, 5, " +
                    "'摘要', '[]', '[]', 'llm', 100, 100)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 35, true, MIGRATION_34_35).use { db ->
            db.query("SELECT uuid, embedding FROM offline_meeting_memories WHERE sessionId = 'sess1'").use { c ->
                assertTrue("迁移后旧见面档案行应存活", c.moveToFirst())
                assertEquals("mm1", c.getString(0))
                assertTrue("新列 embedding 未写 → NULL（待后台回填）", c.isNull(1))
            }
        }
    }

    /**
     * 成长原型校准（图纸 §3.4 / T3-1）：v35 插一条角色（relationshipQualityJSON 有值）→ 迁到 v36 →
     * 旧行存活、新列 relationshipArchetypeId 默认 NULL、既有列（relationshipQualityJSON）原值不变。
     */
    @Test
    fun migration35To36AddsArchetypeIdColumnAndPreservesRows() {
        helper.createDatabase(TEST_DB, 35).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // characters 只给全部 NOT NULL 列（v33 起含 momentsDigestedUntilMillis·可空列省略 = NULL）。
            execSQL(
                "INSERT INTO characters (uuid, name, systemPrompt, personalityDescription, creationDate, gender, " +
                    "ageModeRaw, fixedAge, appearanceDescription, occupation, backstory, speakingStyle, catchphrases, " +
                    "exampleDialogues, initialInterests, memorySummary, previousMemorySummary, offlineMeetingMemorySummary, " +
                    "voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, lastMoodText, " +
                    "lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, moodHistoryJSON, " +
                    "dynamicInterestsJSON, growthLogJSON, growthMetadataJSON, structuredMemoryJSON, " +
                    "structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, " +
                    "relationshipMessageCount, joinedWorld, worldHomeCityId, momentsDigestedUntilMillis) " +
                    "VALUES ('char1', '小满', '', '', 0, '', '', 0, '', '', '', '', '', '', '', '', '', '', '', '', " +
                    "'', 1.0, 0, '', '', 'blue', 0, '', 'rq-sentinel', '', '', '', '', '', '', '', '', 0, 0, 'city_yunye', 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 36, true, MIGRATION_35_36).use { db ->
            db.query("SELECT uuid, relationshipArchetypeId, relationshipQualityJSON FROM characters").use { c ->
                assertTrue("迁移后旧角色行应存活", c.moveToFirst())
                assertEquals("char1", c.getString(0))
                assertTrue("新列 relationshipArchetypeId 未写 → NULL（存量未扫）", c.isNull(1))
                assertEquals("既有列 relationshipQualityJSON 原值不变", "rq-sentinel", c.getString(2))
            }
        }
    }

    /**
     * 上下文日志工具可见性（2026-07-12 / T3）：v36 插一条日志行 → 迁到 v37 →
     * 旧行存活、新列 toolInfoJson 回填默认 ''、既有列（fullContext）原值不变。
     */
    @Test
    fun migration36To37AddsToolInfoJsonColumnAndPreservesRows() {
        helper.createDatabase(TEST_DB, 36).apply {
            // log_entries 给全部 NOT NULL 列（id 自增省略；durationMillis/errorMessage/responseContent 可空省略 = NULL）。
            execSQL(
                "INSERT INTO log_entries (timestampMillis, characterName, modelName, isSuccess, source, " +
                    "messageCount, fullContext, contextSegmentsJson, promptTokens, completionTokens, " +
                    "reasoningTokens, cacheHitTokens, cacheMissTokens, isTokenEstimated) " +
                    "VALUES (100, '夏晴子', 'test-model', 1, 'chat', 3, 'ctx-sentinel', '', 10, 5, 0, 0, 0, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 37, true, MIGRATION_36_37).use { db ->
            db.query("SELECT characterName, toolInfoJson, fullContext FROM log_entries").use { c ->
                assertTrue("迁移后旧日志行应存活", c.moveToFirst())
                assertEquals("夏晴子", c.getString(0))
                assertEquals("新列 toolInfoJson 回填默认空串（详情页据空隐藏工具节）", "", c.getString(1))
                assertEquals("既有列 fullContext 原值不变", "ctx-sentinel", c.getString(2))
            }
        }
    }

    /**
     * 相处偏好（四小件 2026-07-16 / T3-1·E10）：v37 插一条 user_profile 行（含非默认 bio/birthday）→ 迁到 v38 →
     * 旧行存活、新列 companionPreference 回填默认 ''、既有列原值不变。**绝不清库**的直接守护。
     */
    @Test
    fun migration37To38AddsCompanionPreferenceColumnAndPreservesRows() {
        helper.createDatabase(TEST_DB, 37).apply {
            // user_profile 单例行：给全部 NOT NULL 列（id/nickname/bio）+ 两个可空列取非默认值，验「既有列原值不变」。
            execSQL(
                "INSERT INTO user_profile (id, nickname, bio, avatarPath, cityName, cityLatitude, " +
                    "cityLongitude, birthday) " +
                    "VALUES (1, '阿宝', 'bio-sentinel', NULL, '杭州', NULL, NULL, 5875200000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 38, true, MIGRATION_37_38).use { db ->
            db.query(
                "SELECT nickname, bio, companionPreference, cityName, birthday FROM user_profile",
            ).use { c ->
                assertTrue("迁移后旧资料行应存活", c.moveToFirst())
                assertEquals("阿宝", c.getString(0))
                assertEquals("既有列 bio 原值不变", "bio-sentinel", c.getString(1))
                assertEquals("新列 companionPreference 回填默认空串（空=persona 段不注入偏好行）", "", c.getString(2))
                assertEquals("既有列 cityName 原值不变", "杭州", c.getString(3))
                assertEquals("既有列 birthday 原值不变", 5875200000L, c.getLong(4))
            }
        }
    }

    /**
     * 故事推进主权（ST11 / T3·E6）：v38 插一条 story_chapters 行（含非默认 userChoice/hasChoice）→ 迁到 v39 →
     * 旧章存活、新列 aiSuggestedEnding 回填默认 0（存量章一律「AI 未自标结局」= 建议卡不冒头）、既有列原值不变。
     * **绝不清库**的直接守护。
     */
    @Test
    fun migration38To39AddsAiSuggestedEndingColumnAndPreservesRows() {
        helper.createDatabase(TEST_DB, 38).apply {
            // 只插章行（不建父故事行）→ 关 FK 约束，对齐本文件 v13→v14 的既有姿势。
            execSQL("PRAGMA foreign_keys=OFF")
            // story_chapters 全部 NOT NULL 列 + userChoice（可空）取非默认值，验「既有列原值不变」。
            execSQL(
                "INSERT INTO story_chapters (id, storyId, chapterNumber, title, createdAt, content, " +
                    "mood, hasChoice, userChoice) " +
                    "VALUES ('ch-1', 'story-1', 7, '第七章', 100, 'content-sentinel', 'tense', 1, '选项A')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 39, true, MIGRATION_38_39).use { db ->
            db.query(
                "SELECT chapterNumber, title, content, mood, hasChoice, userChoice, aiSuggestedEnding " +
                    "FROM story_chapters",
            ).use { c ->
                assertTrue("迁移后旧章行应存活", c.moveToFirst())
                assertEquals("既有列 chapterNumber 原值不变", 7, c.getInt(0))
                assertEquals("既有列 title 原值不变", "第七章", c.getString(1))
                assertEquals("既有列 content 原值不变", "content-sentinel", c.getString(2))
                assertEquals("既有列 mood 原值不变", "tense", c.getString(3))
                assertEquals("既有列 hasChoice 原值不变", 1, c.getInt(4))
                assertEquals("既有列 userChoice 原值不变", "选项A", c.getString(5))
                assertEquals("新列 aiSuggestedEnding 回填默认 0（存量章一律未自标结局）", 0, c.getInt(6))
            }
        }
    }

    /**
     * 故事无限连载卷二（T3·E1）：v39 插两本书——一本「有限模式」存量书（maxChapters=60/autoExtendCount=2）、
     * 一本本就无限的书 → 迁到 v40 → ①三新列 arcHistory/finaleEndingType/finaleEndingDetail 回填 NULL；
     * ②**存量有限书一键转无限**（maxChapters=NULL、autoExtendCount=0·J2）；③既有列原值一字不变、行不丢。
     */
    @Test
    fun migration39To40AddsFinaleColumnsAndNormalizesLegacyFiniteStories() {
        helper.createDatabase(TEST_DB, 39).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // 有限模式存量书：maxChapters/autoExtendCount 非默认值 + 几个既有列取哨兵值。
            execSQL(
                "INSERT INTO stories (id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
                    "chapterLengthPreference, maxChapters, autoExtendCount, chatInfluenceWeight, narrativePerson, " +
                    "updateMode, unlockHour, unlockMinute, worldInfoEnabled, status, storyOutline, " +
                    "currentArcStartChapter, cachedChapterCount, cachedHasPendingChoice) " +
                    "VALUES ('story-finite', '有限旧书', '悬疑', 'amber', 100, 200, '严肃文学', " +
                    "1500, 60, 2, 'medium', 'second', 'free', 20, 0, 1, 'serializing', 'outline-sentinel', " +
                    "13, 12, 0)",
            )
            // 本就无限的书：两列已是 NULL/0，迁移后应保持原样。
            execSQL(
                "INSERT INTO stories (id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
                    "chapterLengthPreference, maxChapters, autoExtendCount, chatInfluenceWeight, narrativePerson, " +
                    "updateMode, unlockHour, unlockMinute, worldInfoEnabled, status, cachedChapterCount, " +
                    "cachedHasPendingChoice) " +
                    "VALUES ('story-infinite', '无限旧书', '言情', 'rose', 300, 400, '古风', " +
                    "3000, NULL, 0, 'heavy', 'first', 'chase', 21, 30, 0, 'completed', 5, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 40, true, MIGRATION_39_40).use { db ->
            db.query(
                "SELECT title, genre, chapterLengthPreference, maxChapters, autoExtendCount, status, " +
                    "storyOutline, currentArcStartChapter, cachedChapterCount, " +
                    "arcHistory, finaleEndingType, finaleEndingDetail FROM stories WHERE id = 'story-finite'",
            ).use { c ->
                assertTrue("迁移后有限模式旧书应存活", c.moveToFirst())
                assertEquals("既有列 title 原值不变", "有限旧书", c.getString(0))
                assertEquals("既有列 genre 原值不变", "悬疑", c.getString(1))
                assertEquals("既有列 chapterLengthPreference 原值不变", 1500, c.getInt(2))
                assertTrue("J2 存量转无限：maxChapters 归 NULL", c.isNull(3))
                assertEquals("J2 存量转无限：autoExtendCount 归 0", 0, c.getInt(4))
                assertEquals("既有列 status 原值不变（不因转无限而改状态）", "serializing", c.getString(5))
                assertEquals("既有列 storyOutline 原值不变", "outline-sentinel", c.getString(6))
                assertEquals("既有列 currentArcStartChapter 原值不变", 13, c.getInt(7))
                assertEquals("既有列 cachedChapterCount 原值不变", 12, c.getInt(8))
                assertTrue("新列 arcHistory 回填 NULL", c.isNull(9))
                assertTrue("新列 finaleEndingType 回填 NULL", c.isNull(10))
                assertTrue("新列 finaleEndingDetail 回填 NULL", c.isNull(11))
            }
            db.query(
                "SELECT title, maxChapters, autoExtendCount, status, unlockMinute, arcHistory " +
                    "FROM stories WHERE id = 'story-infinite'",
            ).use { c ->
                assertTrue("迁移后无限模式旧书应存活", c.moveToFirst())
                assertEquals("既有列 title 原值不变", "无限旧书", c.getString(0))
                assertTrue("本就无限的书 maxChapters 保持 NULL", c.isNull(1))
                assertEquals("本就无限的书 autoExtendCount 保持 0", 0, c.getInt(2))
                assertEquals("已完结书 status 不动（照旧留在档案）", "completed", c.getString(3))
                assertEquals("既有列 unlockMinute 原值不变", 30, c.getInt(4))
                assertTrue("新列 arcHistory 回填 NULL", c.isNull(5))
            }
        }
    }

    /**
     * 故事阅读器掌控力 C3（T3·E15）：v40 插一本书 + 一章（均取哨兵值）→ 迁到 v41 →
     * ①两张表各自的新列回填 NULL（`story_chapters.previousDraftJson` = 存量章没有可回翻的旧稿；
     * `stories.pendingRewriteDraftJson` = 存量书没有进行中的重写）；②既有列一字不变、行不丢。
     * 本步是**纯加列零数据改写**，任何「重建表」式写法都会在此被抓到。
     */
    @Test
    fun migration40To41AddsDraftColumns() {
        helper.createDatabase(TEST_DB, 40).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO stories (id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
                    "chapterLengthPreference, autoExtendCount, chatInfluenceWeight, narrativePerson, " +
                    "updateMode, unlockHour, unlockMinute, worldInfoEnabled, status, storySummary, " +
                    "characterStates, rewriteInstruction, cachedChapterCount, cachedHasPendingChoice) " +
                    "VALUES ('story-1', '旧书', '悬疑', 'amber', 100, 200, '严肃文学', " +
                    "1500, 0, 'medium', 'second', 'free', 20, 0, 1, 'serializing', 'summary-sentinel', " +
                    "'states-sentinel', 'instruction-sentinel', 9, 1)",
            )
            execSQL(
                "INSERT INTO story_chapters (id, storyId, chapterNumber, title, createdAt, content, " +
                    "mood, hasChoice, userChoice, chapterSummary, aiSuggestedEnding) " +
                    "VALUES ('ch-1', 'story-1', 7, '第七章', 100, 'content-sentinel', 'tense', 1, '选项A', " +
                    "'chapter-summary-sentinel', 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 41, true, MIGRATION_40_41).use { db ->
            db.query(
                "SELECT chapterNumber, title, content, mood, hasChoice, userChoice, chapterSummary, " +
                    "aiSuggestedEnding, previousDraftJson FROM story_chapters WHERE id = 'ch-1'",
            ).use { c ->
                assertTrue("迁移后旧章行应存活", c.moveToFirst())
                assertEquals("既有列 chapterNumber 原值不变", 7, c.getInt(0))
                assertEquals("既有列 title 原值不变", "第七章", c.getString(1))
                assertEquals("既有列 content 原值不变", "content-sentinel", c.getString(2))
                assertEquals("既有列 mood 原值不变", "tense", c.getString(3))
                assertEquals("既有列 hasChoice 原值不变", 1, c.getInt(4))
                assertEquals("既有列 userChoice 原值不变", "选项A", c.getString(5))
                assertEquals("既有列 chapterSummary 原值不变", "chapter-summary-sentinel", c.getString(6))
                assertEquals("既有列 aiSuggestedEnding 原值不变", 1, c.getInt(7))
                assertTrue("新列 previousDraftJson 回填 NULL（存量章无旧稿可回翻）", c.isNull(8))
            }
            db.query(
                "SELECT title, storySummary, characterStates, rewriteInstruction, cachedChapterCount, " +
                    "pendingRewriteDraftJson FROM stories WHERE id = 'story-1'",
            ).use { c ->
                assertTrue("迁移后旧书行应存活", c.moveToFirst())
                assertEquals("既有列 title 原值不变", "旧书", c.getString(0))
                assertEquals("既有列 storySummary 原值不变", "summary-sentinel", c.getString(1))
                assertEquals("既有列 characterStates 原值不变", "states-sentinel", c.getString(2))
                assertEquals("既有列 rewriteInstruction 原值不变", "instruction-sentinel", c.getString(3))
                assertEquals("既有列 cachedChapterCount 原值不变", 9, c.getInt(4))
                assertTrue("新列 pendingRewriteDraftJson 回填 NULL（存量书无进行中的重写）", c.isNull(5))
            }
        }
    }

    /**
     * 故事「我的模板」（图纸四 T3·E12）：v41 插一本书 → 迁到 v42 →
     * ①旧书行一字不变、行不丢（本步**只建新表**，任何误动旧表的写法都会在此被抓到）；
     * ②`user_story_templates` 建成且为空；③可插入、PK 唯一（同 uuid 第二次 REPLACE 覆盖成一行）。
     */
    @Test
    fun migration41To42CreatesUserTemplates() {
        helper.createDatabase(TEST_DB, 41).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO stories (id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
                    "chapterLengthPreference, autoExtendCount, chatInfluenceWeight, narrativePerson, " +
                    "updateMode, unlockHour, unlockMinute, worldInfoEnabled, status, storySummary, " +
                    "characterStates, rewriteInstruction, cachedChapterCount, cachedHasPendingChoice) " +
                    "VALUES ('story-1', '旧书', '悬疑', 'amber', 100, 200, '严肃文学', " +
                    "1500, 0, 'medium', 'second', 'chase', 21, 30, 1, 'serializing', 'summary-sentinel', " +
                    "'states-sentinel', 'instruction-sentinel', 9, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 42, true, MIGRATION_41_42).use { db ->
            db.query(
                "SELECT title, genre, writingStyle, updateMode, unlockHour, unlockMinute, storySummary, " +
                    "cachedChapterCount FROM stories WHERE id = 'story-1'",
            ).use { c ->
                assertTrue("迁移后旧书行应存活", c.moveToFirst())
                assertEquals("既有列 title 原值不变", "旧书", c.getString(0))
                assertEquals("既有列 genre 原值不变", "悬疑", c.getString(1))
                assertEquals("既有列 writingStyle 原值不变", "严肃文学", c.getString(2))
                assertEquals("既有列 updateMode 原值不变", "chase", c.getString(3))
                assertEquals("既有列 unlockHour 原值不变", 21, c.getInt(4))
                assertEquals("既有列 unlockMinute 原值不变", 30, c.getInt(5))
                assertEquals("既有列 storySummary 原值不变", "summary-sentinel", c.getString(6))
                assertEquals("既有列 cachedChapterCount 原值不变", 9, c.getInt(7))
            }
            db.query("SELECT COUNT(*) FROM `user_story_templates`").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("新表 user_story_templates 应建成且为空", 0, c.getInt(0))
            }
            db.execSQL(
                "INSERT INTO user_story_templates (uuid, name, createdAt, payloadJson) " +
                    "VALUES ('tpl-1', '深夜都市线', 1700, '{\"genre\":\"悬疑\"}')",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO user_story_templates (uuid, name, createdAt, payloadJson) " +
                    "VALUES ('tpl-1', '改了名', 1800, '{\"genre\":\"言情\"}')",
            )
            db.query("SELECT COUNT(*), MAX(name), MAX(payloadJson) FROM user_story_templates WHERE uuid = 'tpl-1'")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("同 uuid 第二次应 REPLACE 成一行", 1, c.getInt(0))
                    assertEquals("改了名", c.getString(1))
                    assertEquals("{\"genre\":\"言情\"}", c.getString(2))
                }
        }
    }

    /**
     * 故事二期卷一（图纸 §7 T3·E4）：v42 插一本带章带角色的旧书 → 迁到 v43 →
     * ①三表旧行一字不变、行不丢；②`stories` 四新列（intimacyLedger / sceneState / sceneLedger 落 NULL、
     * pendingBeatsUserEdited 落 0 = 存量书的节拍一律算「AI 预排」）；③`story_chapters.userRating` 落 NULL（未评）；
     * ④`story_character_roles.intimatePersona` 落 NULL；⑤三张表都能写入新列的真值（列真的可用，不是只在 schema 里）。
     */
    @Test
    fun migration42To43AddsNarrativeColumns() {
        helper.createDatabase(TEST_DB, 42).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO stories (id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
                    "chapterLengthPreference, autoExtendCount, chatInfluenceWeight, narrativePerson, " +
                    "updateMode, unlockHour, unlockMinute, worldInfoEnabled, status, storySummary, " +
                    "characterStates, pendingChapterBeats, arcHistory, cachedChapterCount, cachedHasPendingChoice) " +
                    "VALUES ('story-1', '旧书', '悬疑', 'amber', 100, 200, '严肃文学', " +
                    "1500, 0, 'medium', 'second', 'chase', 21, 30, 1, 'serializing', 'summary-sentinel', " +
                    "'states-sentinel', 'beats-sentinel', 'arc-history-sentinel', 9, 1)",
            )
            execSQL(
                "INSERT INTO story_chapters (id, storyId, chapterNumber, title, createdAt, content, " +
                    "mood, hasChoice, userChoice, chapterSummary, aiSuggestedEnding) " +
                    "VALUES ('ch-1', 'story-1', 7, '第七章', 100, 'content-sentinel', 'tense', 1, '选项A', " +
                    "'chapter-summary-sentinel', 1)",
            )
            execSQL(
                "INSERT INTO story_character_roles (id, storyId, roleName, roleType, roleDescription, " +
                    "isUserRole, characterId) " +
                    "VALUES ('role-1', 'story-1', '林晚', 'protagonist', 'role-desc-sentinel', 0, 'char-uuid-1')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 43, true, MIGRATION_42_43).use { db ->
            db.query(
                "SELECT title, storySummary, characterStates, pendingChapterBeats, arcHistory, cachedChapterCount, " +
                    "intimacyLedger, sceneState, sceneLedger, pendingBeatsUserEdited FROM stories WHERE id = 'story-1'",
            ).use { c ->
                assertTrue("迁移后旧书行应存活", c.moveToFirst())
                assertEquals("既有列 title 原值不变", "旧书", c.getString(0))
                assertEquals("既有列 storySummary 原值不变", "summary-sentinel", c.getString(1))
                assertEquals("既有列 characterStates 原值不变", "states-sentinel", c.getString(2))
                assertEquals("既有列 pendingChapterBeats 原值不变", "beats-sentinel", c.getString(3))
                assertEquals("既有列 arcHistory 原值不变", "arc-history-sentinel", c.getString(4))
                assertEquals("既有列 cachedChapterCount 原值不变", 9, c.getInt(5))
                assertTrue("新列 intimacyLedger 回填 NULL（存量书没有关系史账本）", c.isNull(6))
                assertTrue("新列 sceneState 回填 NULL（存量书没有场景快照）", c.isNull(7))
                assertTrue("新列 sceneLedger 回填 NULL（存量书没有场景台账）", c.isNull(8))
                assertEquals("新列 pendingBeatsUserEdited 回填 0（存量节拍一律算 AI 预排）", 0, c.getInt(9))
            }
            db.query(
                "SELECT chapterNumber, title, content, chapterSummary, aiSuggestedEnding, userRating " +
                    "FROM story_chapters WHERE id = 'ch-1'",
            ).use { c ->
                assertTrue("迁移后旧章行应存活", c.moveToFirst())
                assertEquals("既有列 chapterNumber 原值不变", 7, c.getInt(0))
                assertEquals("既有列 title 原值不变", "第七章", c.getString(1))
                assertEquals("既有列 content 原值不变", "content-sentinel", c.getString(2))
                assertEquals("既有列 chapterSummary 原值不变", "chapter-summary-sentinel", c.getString(3))
                assertEquals("既有列 aiSuggestedEnding 原值不变", 1, c.getInt(4))
                assertTrue("新列 userRating 回填 NULL（存量章未评）", c.isNull(5))
            }
            db.query(
                "SELECT roleName, roleType, roleDescription, isUserRole, characterId, intimatePersona " +
                    "FROM story_character_roles WHERE id = 'role-1'",
            ).use { c ->
                assertTrue("迁移后旧角色行应存活", c.moveToFirst())
                assertEquals("既有列 roleName 原值不变", "林晚", c.getString(0))
                assertEquals("既有列 roleType 原值不变", "protagonist", c.getString(1))
                assertEquals("既有列 roleDescription 原值不变", "role-desc-sentinel", c.getString(2))
                assertEquals("既有列 isUserRole 原值不变", 0, c.getInt(3))
                assertEquals("既有列 characterId 原值不变", "char-uuid-1", c.getString(4))
                assertTrue("新列 intimatePersona 回填 NULL", c.isNull(5))
            }

            // 六个新列真的可写（不是只在 schema 里挂个名）。
            db.execSQL(
                "UPDATE stories SET intimacyLedger = '【里程碑】第1章·初吻', sceneState = '卧室｜两人相拥', " +
                    "sceneLedger = '第1章·雨夜·车里', pendingBeatsUserEdited = 1 WHERE id = 'story-1'",
            )
            db.execSQL("UPDATE story_chapters SET userRating = 3 WHERE id = 'ch-1'")
            db.execSQL("UPDATE story_character_roles SET intimatePersona = '人前清冷' WHERE id = 'role-1'")
            db.query(
                "SELECT intimacyLedger, sceneState, sceneLedger, pendingBeatsUserEdited FROM stories WHERE id = 'story-1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("【里程碑】第1章·初吻", c.getString(0))
                assertEquals("卧室｜两人相拥", c.getString(1))
                assertEquals("第1章·雨夜·车里", c.getString(2))
                assertEquals(1, c.getInt(3))
            }
            db.query("SELECT userRating FROM story_chapters WHERE id = 'ch-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(3, c.getInt(0))
            }
            db.query("SELECT intimatePersona FROM story_character_roles WHERE id = 'role-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("人前清冷", c.getString(0))
            }
        }
    }

    /**
     * 活人感统一内核·卷一《人设编译器》（图纸 §7.2 T2-7·E Y-E25）：v43 插一个旧角色 → 迁到 v44 →
     * ①旧行一字不变、行不丢；②`characters` 四新列全部落 `''`（= 从未编译过：锚点走访问器兜底「本性 == 现在」，
     * 其余三列解码回落默认值，**不清零不崩**）；③四新列真的可写（不是只在 schema 里挂个名）。
     */
    @Test
    fun migration43To44AddsPersonaCompileColumns() {
        helper.createDatabase(TEST_DB, 43).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO characters (uuid, name, systemPrompt, personalityDescription, creationDate, gender, ageModeRaw, fixedAge, appearanceDescription, occupation, backstory, speakingStyle, catchphrases, exampleDialogues, initialInterests, memorySummary, previousMemorySummary, offlineMeetingMemorySummary, voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, lastMoodText, lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, moodHistoryJSON, dynamicInterestsJSON, growthLogJSON, growthMetadataJSON, structuredMemoryJSON, structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, relationshipMessageCount, joinedWorld, worldHomeCityId, momentsDigestedUntilMillis) " +
                    "VALUES ('char-1', '林晚', 'sys-sentinel', 'persona-sentinel', 100, 'female', 'growing', 0, " +
                    "'', '', '', '', '', '', '', '', '', '', '', '', 'auto', 1.0, 0, '', '', 'green', 0, " +
                    "'spectrum-sentinel', 'quality-sentinel', '', '', '', 'metadata-sentinel', '', '', '', '', 0, 0, " +
                    "'city_yunye', 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 44, true, MIGRATION_43_44).use { db ->
            db.query(
                "SELECT name, personalityDescription, personalitySpectrumJSON, relationshipQualityJSON, " +
                    "growthMetadataJSON, personalityAnchorJSON, personaCompileMetaJSON, personaGainsJSON, " +
                    "personaOperatorsJSON FROM characters WHERE uuid = 'char-1'",
            ).use { c ->
                assertTrue("旧角色行必须还在", c.moveToFirst())
                assertEquals("林晚", c.getString(0))
                assertEquals("persona-sentinel", c.getString(1))
                assertEquals("spectrum-sentinel", c.getString(2))       // 现值列一个字节不动
                assertEquals("quality-sentinel", c.getString(3))
                assertEquals("metadata-sentinel", c.getString(4))
                assertEquals("新列 personalityAnchorJSON 回填空串", "", c.getString(5))
                assertEquals("新列 personaCompileMetaJSON 回填空串", "", c.getString(6))
                assertEquals("新列 personaGainsJSON 回填空串", "", c.getString(7))
                assertEquals("新列 personaOperatorsJSON 回填空串", "", c.getString(8))
            }

            db.execSQL(
                "UPDATE characters SET personalityAnchorJSON = '{\"warmth\":25}', " +
                    "personaCompileMetaJSON = '{\"source\":\"compiled\"}', personaGainsJSON = '{\"system\":{}}', " +
                    "personaOperatorsJSON = '[]' WHERE uuid = 'char-1'",
            )
            db.query(
                "SELECT personalityAnchorJSON, personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON " +
                    "FROM characters WHERE uuid = 'char-1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("{\"warmth\":25}", c.getString(0))
                assertEquals("{\"source\":\"compiled\"}", c.getString(1))
                assertEquals("{\"system\":{}}", c.getString(2))
                assertEquals("[]", c.getString(3))
            }
        }
    }

    /**
     * 活人感统一内核·卷二《正负双压》（图纸 §7.2 T3-1·P-E23）：v44 插一个旧角色 → 迁到 v45 →
     * ①旧行一字不变、行不丢；②`relationshipPressureJSON` 落 `''`（= 还没分开记过：解码访问器按
     * `RelationshipPressure.fromQuality` 播种 pos=净额/neg=0，**不清零不崩**）；③新列真的可写。
     */
    @Test
    fun migration44To45AddsRelationshipPressureColumn() {
        helper.createDatabase(TEST_DB, 44).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
                "INSERT INTO characters (uuid, name, systemPrompt, personalityDescription, creationDate, gender, ageModeRaw, fixedAge, appearanceDescription, occupation, backstory, speakingStyle, catchphrases, exampleDialogues, initialInterests, memorySummary, previousMemorySummary, offlineMeetingMemorySummary, voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, lastMoodText, lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, moodHistoryJSON, dynamicInterestsJSON, growthLogJSON, growthMetadataJSON, structuredMemoryJSON, structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, relationshipMessageCount, joinedWorld, worldHomeCityId, momentsDigestedUntilMillis, personalityAnchorJSON, personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON) " +
                    "VALUES ('char-1', '林晚', 'sys-sentinel', 'persona-sentinel', 100, 'female', 'growing', 0, " +
                    "'', '', '', '', '', '', '', '', '', '', '', '', 'auto', 1.0, 0, '', '', 'green', 0, " +
                    "'spectrum-sentinel', 'quality-sentinel', '', '', '', 'metadata-sentinel', '', '', '', '', 0, 0, " +
                    "'city_yunye', 0, 'anchor-sentinel', '', '', '')",   // 卷三 R1 F-1：卷一三列 NOT NULL 无 DEFAULT，漏列在真设备必撞约束
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 45, true, MIGRATION_44_45).use { db ->
            db.query(
                "SELECT name, personalitySpectrumJSON, relationshipQualityJSON, personalityAnchorJSON, " +
                    "relationshipPressureJSON FROM characters WHERE uuid = 'char-1'",
            ).use { c ->
                assertTrue("旧角色行必须还在", c.moveToFirst())
                assertEquals("林晚", c.getString(0))
                assertEquals("spectrum-sentinel", c.getString(1))
                assertEquals("quality-sentinel", c.getString(2))   // 净额列一个字节不动（下游 7 类读者零改动）
                assertEquals("anchor-sentinel", c.getString(3))    // 卷一四列一个字节不动
                assertEquals("新列 relationshipPressureJSON 回填空串", "", c.getString(4))
            }

            db.execSQL(
                "UPDATE characters SET relationshipPressureJSON = '{\"pos\":[80,0,0,0,0,0,0,0],\"neg\":[75,0,0,0,0,0,0,0]}' " +
                    "WHERE uuid = 'char-1'",
            )
            db.query("SELECT relationshipPressureJSON FROM characters WHERE uuid = 'char-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("{\"pos\":[80,0,0,0,0,0,0,0],\"neg\":[75,0,0,0,0,0,0,0]}", c.getString(0))
            }
        }
    }

    /**
     * 活人感统一内核·卷三《场内核与渲染收编》（图纸 §7.2 T3-1）：v45 插一个旧角色 → 迁到 v46 →
     * ①旧行一字不变、行不丢（净额列 / 压强列 / 卷一锚点列全部原样）；②`affectFieldJSON` 落 `''`
     * （= 还没写过场：解码访问器回默认 `AffectField()`，**不崩不清零、不扫库**）；③新列真的可写。
     */
    @Test
    fun migration45To46AddsAffectFieldColumn() {
        helper.createDatabase(TEST_DB, 45).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // 列清单 = 45.json 里「NOT NULL 且无 SQL DEFAULT」的全部列（机器推导·PITFALLS §1a「DDL 逐字取 schema json」）：
            // 只列一部分列会撞 NOT NULL 约束（卷二 44→45 用例漏了卷一三列，已登记卷三 §11）。
            execSQL(
                "INSERT INTO characters (" +
                    "uuid, name, systemPrompt, personalityDescription, creationDate, gender, ageModeRaw, fixedAge, " +
                    "appearanceDescription, occupation, backstory, speakingStyle, catchphrases, exampleDialogues, initialInterests, memorySummary, " +
                    "previousMemorySummary, offlineMeetingMemorySummary, voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, " +
                    "lastMoodText, lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, relationshipPressureJSON, moodHistoryJSON, dynamicInterestsJSON, " +
                    "growthLogJSON, growthMetadataJSON, structuredMemoryJSON, structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, relationshipMessageCount, joinedWorld, " +
                    "worldHomeCityId, momentsDigestedUntilMillis, personalityAnchorJSON, personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON) " +
                    "VALUES (" +
                    "'char-1', '林晚', 'sys-sentinel', 'persona-sentinel', 100, 'female', 'growing', 0, " +
                    "'', '', '', '', '', '', '', '', " +
                    "'', '', '', '', '', 1.0, 0, '', " +
                    "'', 'green', 0, 'spectrum-sentinel', 'quality-sentinel', 'pressure-sentinel', '', '', " +
                    "'', 'metadata-sentinel', '', '', '', '', 0, 0, " +
                    "'city_yunye', 0, 'anchor-sentinel', '', '', '')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 46, true, MIGRATION_45_46).use { db ->
            db.query(
                "SELECT name, relationshipQualityJSON, relationshipPressureJSON, personalityAnchorJSON, " +
                    "affectFieldJSON FROM characters WHERE uuid = 'char-1'",
            ).use { c ->
                assertTrue("旧角色行必须还在", c.moveToFirst())
                assertEquals("林晚", c.getString(0))
                assertEquals("quality-sentinel", c.getString(1))   // 净额列一个字节不动
                assertEquals("pressure-sentinel", c.getString(2))  // 卷二压强列一个字节不动
                assertEquals("anchor-sentinel", c.getString(3))    // 卷一锚点列一个字节不动
                assertEquals("新列 affectFieldJSON 回填空串", "", c.getString(4))
            }

            db.execSQL(
                "UPDATE characters SET affectFieldJSON = '{\"security\":62,\"valence\":-70,\"hits\":[\"g04\"]}' " +
                    "WHERE uuid = 'char-1'",
            )
            db.query("SELECT affectFieldJSON FROM characters WHERE uuid = 'char-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("{\"security\":62,\"valence\":-70,\"hits\":[\"g04\"]}", c.getString(0))
            }
        }
    }

    /**
     * 活人感统一内核·卷四《意图队列 + 性格复盘》（图纸 §7.2 T3-1 · §3.2）：v46 插一个旧角色 → 迁到 v47 →
     * ①旧行一字不变、行不丢（净额列 / 压强列 / 卷三场列全部原样）；②`intentQueueJSON` 落 `''`
     * （= 还没写过意图：解码访问器回默认 `IntentQueueState()`，**不崩不清零、不扫库**）；③新列真的可写。
     */
    @Test
    fun migration46To47AddsIntentQueueColumn() {
        helper.createDatabase(TEST_DB, 46).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            // 列清单 = 46.json 里「NOT NULL 且无 SQL DEFAULT」的全部 47 列（施工前脚本机器推导·PITFALLS §1a）：
            // 卷三 affectFieldJSON 在 46.json 里同样 NOT NULL 且无 defaultValue（Kotlin 默认值不进 SQL），必须列
            // ——图纸 §3.2 原写「不必列」经脚本复核已更正（卷四图纸 §11 D-1）。
            execSQL(
                "INSERT INTO characters (" +
                    "uuid, name, systemPrompt, personalityDescription, creationDate, gender, ageModeRaw, fixedAge, " +
                    "appearanceDescription, occupation, backstory, speakingStyle, catchphrases, exampleDialogues, initialInterests, memorySummary, " +
                    "previousMemorySummary, offlineMeetingMemorySummary, voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, lastMoodEmoji, " +
                    "lastMoodText, lastMoodColorName, streakCount, personalitySpectrumJSON, relationshipQualityJSON, relationshipPressureJSON, moodHistoryJSON, dynamicInterestsJSON, " +
                    "growthLogJSON, growthMetadataJSON, structuredMemoryJSON, structuredMemoryMetadataJSON, previousStructuredMemoryJSON, affinitySensePackageJSON, relationshipMessageCount, joinedWorld, " +
                    "worldHomeCityId, momentsDigestedUntilMillis, personalityAnchorJSON, personaCompileMetaJSON, personaGainsJSON, personaOperatorsJSON, affectFieldJSON) " +
                    "VALUES (" +
                    "'char-1', '林晚', 'sys-sentinel', 'persona-sentinel', 100, 'female', 'growing', 0, " +
                    "'', '', '', '', '', '', '', '', " +
                    "'', '', '', '', '', 1.0, 0, '', " +
                    "'', 'green', 0, 'spectrum-sentinel', 'quality-sentinel', 'pressure-sentinel', '', '', " +
                    "'', 'metadata-sentinel', '', '', '', '', 0, 0, " +
                    "'city_yunye', 0, 'anchor-sentinel', '', '', '', 'affect-sentinel')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 47, true, MIGRATION_46_47).use { db ->
            db.query(
                "SELECT name, relationshipQualityJSON, relationshipPressureJSON, affectFieldJSON, " +
                    "intentQueueJSON FROM characters WHERE uuid = 'char-1'",
            ).use { c ->
                assertTrue("旧角色行必须还在", c.moveToFirst())
                assertEquals("林晚", c.getString(0))
                assertEquals("quality-sentinel", c.getString(1))   // 净额列一个字节不动
                assertEquals("pressure-sentinel", c.getString(2))  // 卷二压强列一个字节不动
                assertEquals("affect-sentinel", c.getString(3))    // 卷三场列一个字节不动
                assertEquals("新列 intentQueueJSON 回填空串", "", c.getString(4))
            }

            db.execSQL("UPDATE characters SET intentQueueJSON = '{\"intents\":[]}' WHERE uuid = 'char-1'")
            db.query("SELECT intentQueueJSON FROM characters WHERE uuid = 'char-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("{\"intents\":[]}", c.getString(0))
            }
        }
    }

    /**
     * 「我们的日子」卷一《沉淀》（图纸 §7.2 T3-1 · 总图纸 §3.1 / §3.2）：v47 插一个旧角色 → 迁到 v48 →
     * ①旧行一字不变、行不丢；②`ourDaysBackfilledAt` 落 NULL（= 未回填·E14）且可写；③`our_days` 建成且为空。
     * 种子 INSERT 列清单 = 47.json 里「NOT NULL 且无 SQL DEFAULT」的全部 48 列（脚本机器推导·PITFALLS §1a）。
     */
    @Test
    fun migration47To48CreatesOurDaysAndAddsBackfillColumn() {
        helper.createDatabase(TEST_DB, 47).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            execSQL(
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
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 48, true, MIGRATION_47_48).use { db ->
            db.query(
                "SELECT name, relationshipQualityJSON, affectFieldJSON, intentQueueJSON, ourDaysBackfilledAt IS NULL " +
                    "FROM characters WHERE uuid = 'char-1'",
            ).use { c ->
                assertTrue("旧角色行必须还在", c.moveToFirst())
                assertEquals("林晚", c.getString(0))
                assertEquals("quality-sentinel", c.getString(1))
                assertEquals("affect-sentinel", c.getString(2))
                assertEquals("intent-sentinel", c.getString(3))
                assertEquals("新列 ourDaysBackfilledAt 落 NULL", 1, c.getInt(4))
            }
            db.query("SELECT COUNT(*) FROM our_days").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("our_days 建成且为空", 0, c.getInt(0))
            }
            db.execSQL("UPDATE characters SET ourDaysBackfilledAt = 1756800000000 WHERE uuid = 'char-1'")
            db.query("SELECT ourDaysBackfilledAt FROM characters WHERE uuid = 'char-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1756800000000L, c.getLong(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /**
         * 逐版本循环校验的上界。**升 DB 版本时必须同步升此常量**，否则新步静默无覆盖
         * （2026-07-16 四小件：此值曾 stale 在 31、DB 已 37，v31→v37 六步循环零覆盖——本卷一并清账到 38）。
         */
        const val LATEST_VERSION = 48
    }
}
