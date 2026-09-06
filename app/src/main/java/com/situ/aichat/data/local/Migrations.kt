package com.situ.aichat.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 显式迁移链 v1→v15（P12.2「数据保命」）。**由 app/schemas 下导出的版本快照逐版本确定性 diff 生成**
 * （非手写）：迁移历史纯增量（仅新表 + 新列，无删表/删列/改类型/改索引/改主键）。新表 DDL 直接取自
 * 快照 createSql（字节级一致）；新增列用 ALTER ADD COLUMN，NOT NULL 列按实体默认值补 DEFAULT 回填旧行。
 * 每步由 [com.situ.aichat.MigrationTest] 用 MigrationTestHelper 对照快照校验。新增版本时：升 @Database.version、
 * 追加 MIGRATION_n_(n+1)、加入 [ALL_MIGRATIONS]、补 MigrationTest 用例——**切勿回退到 fallbackToDestructiveMigration**。
 */

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `lastMemorySummarySuccessDate` INTEGER")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `lastMemorySummaryFailureDate` INTEGER")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `lastMemorySummaryAttemptDate` INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `character_daily_schedules` (`uuid` TEXT NOT NULL, `characterUuid` TEXT NOT NULL, `date` INTEGER NOT NULL, `cityName` TEXT, `weatherCondition` TEXT, `weatherEmoji` TEXT, `temperatureHigh` REAL, `temperatureLow` REAL, `timezoneIdentifier` TEXT, `generatedAt` INTEGER, `lastWeatherCheckAt` INTEGER, `isBackfilled` INTEGER NOT NULL, PRIMARY KEY(`uuid`), FOREIGN KEY(`characterUuid`) REFERENCES `characters`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_daily_schedules_characterUuid_date` ON `character_daily_schedules` (`characterUuid`, `date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_daily_schedules_characterUuid` ON `character_daily_schedules` (`characterUuid`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `schedule_events` (`uuid` TEXT NOT NULL, `scheduleUuid` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `periodLabel` TEXT NOT NULL, `location` TEXT NOT NULL, `activity` TEXT NOT NULL, `moodEmoji` TEXT NOT NULL, `moodText` TEXT, `innerThought` TEXT, `isPhoneAvailable` INTEGER NOT NULL, `eventTypeRaw` TEXT NOT NULL, `relatedCharacterNames` TEXT, `relatedMessageUUID` TEXT, `sourceRaw` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`uuid`), FOREIGN KEY(`scheduleUuid`) REFERENCES `character_daily_schedules`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_events_scheduleUuid` ON `schedule_events` (`scheduleUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_events_startTime` ON `schedule_events` (`startTime`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `notification_templates` (`id` TEXT NOT NULL, `characterId` TEXT NOT NULL, `category` TEXT NOT NULL, `content` TEXT NOT NULL, `isUsed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_templates_characterId_category` ON `notification_templates` (`characterId`, `category`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `notification_delivery_records` (`id` TEXT NOT NULL, `characterId` TEXT NOT NULL, `category` TEXT NOT NULL, `deliveryIdentifier` TEXT NOT NULL, `requestIdentifier` TEXT NOT NULL, `conversationUuid` TEXT NOT NULL, `notificationBody` TEXT NOT NULL, `windowId` TEXT NOT NULL, `windowStartMinute` INTEGER NOT NULL, `windowEndMinute` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, `deliveredAt` INTEGER, `materializedAt` INTEGER, `materializedMessageId` TEXT, `respondedAt` INTEGER, `responseLatency` REAL, `stateRaw` TEXT NOT NULL, `statsApplied` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_delivery_records_deliveryIdentifier` ON `notification_delivery_records` (`deliveryIdentifier`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_delivery_records_characterId_category` ON `notification_delivery_records` (`characterId`, `category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_delivery_records_scheduledAt` ON `notification_delivery_records` (`scheduledAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_delivery_records_stateRaw` ON `notification_delivery_records` (`stateRaw`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `notification_window_stats` (`id` TEXT NOT NULL, `characterId` TEXT NOT NULL, `category` TEXT NOT NULL, `windowId` TEXT NOT NULL, `windowStartMinute` INTEGER NOT NULL, `windowEndMinute` INTEGER NOT NULL, `scheduledCount` INTEGER NOT NULL, `responseCount` INTEGER NOT NULL, `smoothedScore` REAL NOT NULL, `lastScheduledAt` INTEGER, `lastRespondedAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_window_stats_characterId_category_windowId` ON `notification_window_stats` (`characterId`, `category`, `windowId`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `diary_entries` (`uuid` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `imagePathsJson` TEXT NOT NULL, `moodEmoji` TEXT, `moodText` TEXT, `isAutoGenerated` INTEGER NOT NULL, `isDraft` INTEGER NOT NULL, `isPetDiary` INTEGER NOT NULL, `petSpeciesRaw` TEXT, `visibilityRaw` TEXT NOT NULL, `triggerTypeRaw` TEXT NOT NULL, `relatedGiftId` TEXT, PRIMARY KEY(`uuid`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_entries_timestamp` ON `diary_entries` (`timestamp`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `diary_comments` (`id` TEXT NOT NULL, `entryUuid` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `characterUuid` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`entryUuid`) REFERENCES `diary_entries`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_comments_entryUuid` ON `diary_comments` (`entryUuid`)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `moment_post` (`uuid` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `authorTypeRaw` TEXT NOT NULL, `characterUuid` TEXT, `isAutoGenerated` INTEGER NOT NULL, `imagePathsJson` TEXT NOT NULL, `isSoftDeleted` INTEGER NOT NULL, `triggerTypeRaw` TEXT NOT NULL, `relatedGiftId` TEXT, PRIMARY KEY(`uuid`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_post_timestamp` ON `moment_post` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_post_characterUuid` ON `moment_post` (`characterUuid`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `moment_comment` (`uuid` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `authorTypeRaw` TEXT NOT NULL, `characterUuid` TEXT, `replyToName` TEXT, `postUuid` TEXT, `parentCommentUuid` TEXT, PRIMARY KEY(`uuid`), FOREIGN KEY(`postUuid`) REFERENCES `moment_post`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`parentCommentUuid`) REFERENCES `moment_comment`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_comment_postUuid` ON `moment_comment` (`postUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_comment_parentCommentUuid` ON `moment_comment` (`parentCommentUuid`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `moment_like` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `authorTypeRaw` TEXT NOT NULL, `characterUuid` TEXT, `postUuid` TEXT, FOREIGN KEY(`postUuid`) REFERENCES `moment_post`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_like_postUuid_characterUuid` ON `moment_like` (`postUuid`, `characterUuid`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `moment_notification` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `typeRaw` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isRead` INTEGER NOT NULL, `characterUuid` TEXT NOT NULL, `contentPreview` TEXT NOT NULL, `postTimestamp` REAL NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_notification_isRead` ON `moment_notification` (`isRead`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moment_notification_timestamp` ON `moment_notification` (`timestamp`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `custom_sticker` (`stickerUuid` TEXT NOT NULL, `name` TEXT NOT NULL, `semanticDescription` TEXT NOT NULL, `isAnimated` INTEGER NOT NULL, `imagePath` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `usageCount` INTEGER NOT NULL, PRIMARY KEY(`stickerUuid`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_sticker_createdAt` ON `custom_sticker` (`createdAt`)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `character_pet` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `speciesRaw` TEXT NOT NULL, `isHiddenSpecies` INTEGER NOT NULL, `personalityTypeRaw` TEXT NOT NULL, `adoptedDate` INTEGER NOT NULL, `hunger` INTEGER NOT NULL, `cleanliness` INTEGER NOT NULL, `happiness` INTEGER NOT NULL, `health` INTEGER NOT NULL, `growthStageRaw` TEXT NOT NULL, `growthPoints` INTEGER NOT NULL, `totalInteractions` INTEGER NOT NULL, `lastFedDate` INTEGER, `lastCleanedDate` INTEGER, `lastPlayedDate` INTEGER, `lastInteractionDate` INTEGER, `neglectPhaseRaw` TEXT NOT NULL, `petGrowthLogJson` TEXT NOT NULL, `petMetadataJson` TEXT NOT NULL, `characterUuid` TEXT NOT NULL, PRIMARY KEY(`uuid`), FOREIGN KEY(`characterUuid`) REFERENCES `characters`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_pet_characterUuid` ON `character_pet` (`characterUuid`)")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `user_wallet` (`uuid` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `coinBalance` INTEGER NOT NULL, `totalEarned` INTEGER NOT NULL, `totalSpent` INTEGER NOT NULL, PRIMARY KEY(`uuid`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `character_wallet` (`uuid` TEXT NOT NULL, `characterUuid` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `coinBalance` INTEGER NOT NULL, `totalEarned` INTEGER NOT NULL, `totalSpent` INTEGER NOT NULL, `monthlySalary` INTEGER NOT NULL, `salaryInferred` INTEGER NOT NULL, `salaryDay` INTEGER NOT NULL, `lastSalaryDate` INTEGER, `lastEconomicScanDate` INTEGER, `lastProactiveGiftDate` INTEGER, `affinityFromUser` INTEGER NOT NULL, `affinityToUser` INTEGER NOT NULL, PRIMARY KEY(`uuid`), FOREIGN KEY(`characterUuid`) REFERENCES `characters`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_wallet_characterUuid` ON `character_wallet` (`characterUuid`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `currency_transaction` (`uuid` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `ownerTypeRaw` TEXT NOT NULL, `characterUuid` TEXT NOT NULL, `kindRaw` TEXT NOT NULL, `categoryRaw` TEXT NOT NULL, `amount` INTEGER NOT NULL, `balanceAfter` INTEGER NOT NULL, `relatedEntityId` TEXT, `note` TEXT NOT NULL, PRIMARY KEY(`uuid`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_currency_transaction_relatedEntityId` ON `currency_transaction` (`relatedEntityId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_currency_transaction_characterUuid` ON `currency_transaction` (`characterUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_currency_transaction_timestamp` ON `currency_transaction` (`timestamp`)")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `gift_records` (`uuid` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `senderType` TEXT NOT NULL, `senderCharacterUUID` TEXT NOT NULL, `receiverType` TEXT NOT NULL, `receiverCharacterUUID` TEXT NOT NULL, `giftItemId` TEXT NOT NULL, `pricePaid` INTEGER NOT NULL, `isDIY` INTEGER NOT NULL, `diyTitle` TEXT NOT NULL, `diyContent` TEXT NOT NULL, `diyImagePath` TEXT, `context` TEXT NOT NULL, `senderMessage` TEXT NOT NULL, `reactionText` TEXT NOT NULL, `reactionMoodEmoji` TEXT NOT NULL, `affinityGain` INTEGER NOT NULL, `relationshipImpactJSON` TEXT NOT NULL, PRIMARY KEY(`uuid`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_gift_records_receiverCharacterUUID` ON `gift_records` (`receiverCharacterUUID`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_gift_records_senderCharacterUUID` ON `gift_records` (`senderCharacterUUID`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_gift_records_timestamp` ON `gift_records` (`timestamp`)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `red_packet_records` (`uuid` TEXT NOT NULL, `messageUuid` TEXT NOT NULL, `conversationUuid` TEXT NOT NULL, `senderType` TEXT NOT NULL, `senderCharacterUUID` TEXT NOT NULL, `receiverType` TEXT NOT NULL, `receiverCharacterUUID` TEXT NOT NULL, `amount` INTEGER NOT NULL, `blessingText` TEXT NOT NULL, `festivalId` TEXT, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `rejectionReason` TEXT NOT NULL, `notifiedExpiringSoon` INTEGER NOT NULL, PRIMARY KEY(`uuid`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_red_packet_records_messageUuid` ON `red_packet_records` (`messageUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_red_packet_records_conversationUuid` ON `red_packet_records` (`conversationUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_red_packet_records_status` ON `red_packet_records` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_red_packet_records_receiverCharacterUUID` ON `red_packet_records` (`receiverCharacterUUID`)")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `isInOfflineMode` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `currentOfflineSessionId` TEXT")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `currentSceneProgress` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `pendingOfflineSummarySessionId` TEXT")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `pendingOfflineSummaryFailCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `pendingOfflineSummaryLastAttemptAt` INTEGER")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `offlineSummaryFallbackSessionIds` TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `stories` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `genre` TEXT NOT NULL, `coverColorScheme` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `worldSetting` TEXT, `plotDirection` TEXT, `writingStyle` TEXT NOT NULL, `chapterLengthPreference` INTEGER NOT NULL, `maxChapters` INTEGER, `autoExtendCount` INTEGER NOT NULL, `chatInfluenceWeight` TEXT NOT NULL, `narrativePerson` TEXT NOT NULL, `updateMode` TEXT NOT NULL, `unlockHour` INTEGER NOT NULL, `unlockMinute` INTEGER NOT NULL, `status` TEXT NOT NULL, `storySummary` TEXT, `currentArc` TEXT, `characterStates` TEXT, `openThreads` TEXT, `storyBible` TEXT, `lastCompressedAtChapter` INTEGER, `storyOutline` TEXT, `pendingChapterBeats` TEXT, `currentArcStartChapter` INTEGER, `customPromptsJson` TEXT, `requestedEndingType` TEXT, `requestedEndingDetail` TEXT, `rewriteInstruction` TEXT, `cachedChapterCount` INTEGER NOT NULL, `cachedLatestChapterNumber` INTEGER, `cachedLatestChapterTitle` TEXT, `cachedLatestChapterCreatedAt` INTEGER, `cachedHasPendingChoice` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stories_updatedAt` ON `stories` (`updatedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stories_status` ON `stories` (`status`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `story_chapters` (`id` TEXT NOT NULL, `storyId` TEXT NOT NULL, `chapterNumber` INTEGER NOT NULL, `title` TEXT NOT NULL, `teaser` TEXT, `createdAt` INTEGER NOT NULL, `content` TEXT NOT NULL, `mood` TEXT NOT NULL, `scenes` TEXT, `hasChoice` INTEGER NOT NULL, `choicePrompt` TEXT, `choiceOptions` TEXT, `userChoice` TEXT, `choiceMadeAt` INTEGER, `chapterSummary` TEXT, `unlockAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`storyId`) REFERENCES `stories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_chapters_storyId` ON `story_chapters` (`storyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_chapters_createdAt` ON `story_chapters` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_chapters_chapterNumber` ON `story_chapters` (`chapterNumber`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `story_character_roles` (`id` TEXT NOT NULL, `storyId` TEXT NOT NULL, `roleName` TEXT NOT NULL, `roleType` TEXT NOT NULL, `roleDescription` TEXT, `isUserRole` INTEGER NOT NULL, `characterId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`storyId`) REFERENCES `stories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_character_roles_storyId` ON `story_character_roles` (`storyId`)")
    }
}

/** 兑换码使用记录表（14.6c）。DDL 与 Room 为 [com.situ.aichat.data.local.entity.RedeemCodeUsageEntity] 生成的
 *  16.json 完全一致（codeHash 唯一索引）；MigrationTestHelper 在真机批校验 schema 匹配。 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `redeem_code_usage` (`uuid` TEXT NOT NULL, `codeHash` TEXT NOT NULL, `redeemedAt` INTEGER NOT NULL, `amount` INTEGER NOT NULL, PRIMARY KEY(`uuid`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_redeem_code_usage_codeHash` ON `redeem_code_usage` (`codeHash`)")
    }
}

/** v16→v17（批 D·上下文日志）：新建 `log_entries` 表 + timestampMillis 索引（容量轮转用）。 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `log_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL, `characterName` TEXT NOT NULL, `modelName` TEXT NOT NULL, " +
                "`isSuccess` INTEGER NOT NULL, `source` TEXT NOT NULL, `messageCount` INTEGER NOT NULL, " +
                "`durationMillis` INTEGER, `errorMessage` TEXT, `fullContext` TEXT NOT NULL, " +
                "`responseContent` TEXT, `contextSegmentsJson` TEXT NOT NULL, `promptTokens` INTEGER NOT NULL, " +
                "`completionTokens` INTEGER NOT NULL, `reasoningTokens` INTEGER NOT NULL, " +
                "`cacheHitTokens` INTEGER NOT NULL, `cacheMissTokens` INTEGER NOT NULL, " +
                "`isTokenEstimated` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_log_entries_timestampMillis` ON `log_entries` (`timestampMillis`)")
    }
}

/** v17→v18（聊天壁纸）：characters 表加 chatWallpaperPath 列（per-角色全屏壁纸绝对路径·nullable·无默认）。
 *  仅新增 nullable 列、旧行零丢失（MigrationTest 校验 18.json 一致 + 旧角色行存活回填 null）。 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE characters ADD COLUMN chatWallpaperPath TEXT")
    }
}

/** v18→v19（未来约定见面）：新建 `meeting_appointments` 表 + 4 索引（characterUuid/conversationUuid/status/scheduledAt）。
 *  无 FK——按 ID 关联角色/会话，删角色/会话时手动清理 + 撤到点通知（避免级联静默删 → 孤儿通知）。
 *  DDL 取自 Room 为 [com.situ.aichat.data.local.entity.MeetingAppointmentEntity] 生成的 19.json createSql
 *  （字节级一致），MigrationTest 校验。仅新增表、旧行零丢失。 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `meeting_appointments` (`uuid` TEXT NOT NULL, " +
                "`characterUuid` TEXT NOT NULL, `conversationUuid` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`proposedBy` TEXT NOT NULL, `source` TEXT NOT NULL, `scheduledAt` INTEGER NOT NULL, " +
                "`timeGranularity` TEXT NOT NULL, `rawWhenText` TEXT NOT NULL, `location` TEXT NOT NULL, " +
                "`activity` TEXT NOT NULL, `invitationText` TEXT NOT NULL, `tensionHint` TEXT NOT NULL, " +
                "`hiddenTensionSeed` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `confirmedAt` INTEGER, " +
                "`outcomeAt` INTEGER, `honoredSessionId` TEXT, `lastReminderScheduledAt` INTEGER, " +
                "PRIMARY KEY(`uuid`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_appointments_characterUuid` ON `meeting_appointments` (`characterUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_appointments_conversationUuid` ON `meeting_appointments` (`conversationUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_appointments_status` ON `meeting_appointments` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_appointments_scheduledAt` ON `meeting_appointments` (`scheduledAt`)")
    }
}

/** v19→v20（未来约定见面·识别扫描节奏）：conversations 加两列 `lastMeetingScanSuccessDate`/`lastMeetingScanFailureDate`
 *  （均 nullable INTEGER·跨进程持久化扫描双轨冷却·仿 lastMemorySummary*）。纯增量·旧行零丢失·新列默认 NULL。 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN lastMeetingScanSuccessDate INTEGER")
        db.execSQL("ALTER TABLE conversations ADD COLUMN lastMeetingScanFailureDate INTEGER")
    }
}

/** v20→v21（世界书 WB1·契约 `FABLE5_WORLDBOOK_PROPOSAL.md` §4.1）：新建世界书四表——
 *  `world_books`（书）/ `world_book_entries`（条目·FK 级联删书清条目）/ `world_book_bindings`
 *  （角色×书多对多·双 FK 级联）/ `world_book_timed_states`（sticky/cooldown 会话态·双 FK 级联）+ 3 索引。
 *  DDL 取自 Room 生成的 21.json createSql（字节级一致），MigrationTest 校验。仅新增表、旧行零丢失。 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_books` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, `scanDepth` INTEGER, `tokenBudget` INTEGER, " +
                "`recursiveScanning` INTEGER, `isGlobal` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
                "`extraJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`uuid`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_book_entries` (`uuid` TEXT NOT NULL, `bookUuid` TEXT NOT NULL, " +
                "`uid` INTEGER NOT NULL, `displayIndex` INTEGER NOT NULL, `keysJson` TEXT NOT NULL, " +
                "`secondaryKeysJson` TEXT NOT NULL, `selective` INTEGER NOT NULL, `selectiveLogic` INTEGER NOT NULL, " +
                "`constant` INTEGER NOT NULL, `vectorized` INTEGER NOT NULL, `comment` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `insertionOrder` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, `depth` INTEGER NOT NULL, `role` INTEGER NOT NULL, " +
                "`ignoreBudget` INTEGER NOT NULL, `probability` INTEGER NOT NULL, `useProbability` INTEGER NOT NULL, " +
                "`scanDepth` INTEGER, `caseSensitive` INTEGER, `matchWholeWords` INTEGER, " +
                "`excludeRecursion` INTEGER NOT NULL, `preventRecursion` INTEGER NOT NULL, " +
                "`delayUntilRecursion` INTEGER NOT NULL, `groupName` TEXT NOT NULL, `groupOverride` INTEGER NOT NULL, " +
                "`groupWeight` INTEGER NOT NULL, `useGroupScoring` INTEGER, `sticky` INTEGER, `cooldown` INTEGER, " +
                "`delay` INTEGER, `extraJson` TEXT NOT NULL, `embedding` BLOB, `embeddingSignature` TEXT, " +
                "PRIMARY KEY(`uuid`), FOREIGN KEY(`bookUuid`) REFERENCES `world_books`(`uuid`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_book_entries_bookUuid` ON `world_book_entries` (`bookUuid`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_book_bindings` (`characterUuid` TEXT NOT NULL, " +
                "`bookUuid` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`characterUuid`, `bookUuid`), " +
                "FOREIGN KEY(`characterUuid`) REFERENCES `characters`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`bookUuid`) REFERENCES `world_books`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_book_bindings_bookUuid` ON `world_book_bindings` (`bookUuid`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_book_timed_states` (`conversationUuid` TEXT NOT NULL, " +
                "`entryUuid` TEXT NOT NULL, `effectType` TEXT NOT NULL, `triggeredAtMessageCount` INTEGER NOT NULL, " +
                "`durationMessages` INTEGER NOT NULL, PRIMARY KEY(`conversationUuid`, `entryUuid`, `effectType`), " +
                "FOREIGN KEY(`conversationUuid`) REFERENCES `conversations`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`entryUuid`) REFERENCES `world_book_entries`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_book_timed_states_entryUuid` ON `world_book_timed_states` (`entryUuid`)")
    }
}

/** v21→v22（日记重设计 R3/R4·契约 `FABLE5_DIARY_REDESIGN_PROPOSAL.md` §2）：
 *  ① `diary_comments` 加 `parentCommentId`（nullable·一层回复线程根 id）+ `isFromUser`（NOT NULL 默认 0·用户回复标记）；
 *  ② `diary_entries` 加 `authorCharacterUuid`（nullable·R4 交换日记作者·null=用户日记）；
 *  ③ 新建 `diary_reactions`（角色点赞·FK 级联删日记·同角色同条目唯一索引防重）。
 *  纯增量·旧行零丢失·DDL 与 Room 生成的 22.json createSql 一致（MigrationTest 逐步校验）。 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE diary_comments ADD COLUMN parentCommentId TEXT")
        db.execSQL("ALTER TABLE diary_comments ADD COLUMN isFromUser INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE diary_entries ADD COLUMN authorCharacterUuid TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diary_reactions` (`id` TEXT NOT NULL, `entryUuid` TEXT NOT NULL, " +
                "`characterUuid` TEXT NOT NULL, `emoji` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`entryUuid`) REFERENCES `diary_entries`(`uuid`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_reactions_entryUuid` ON `diary_reactions` (`entryUuid`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_diary_reactions_entryUuid_characterUuid` " +
                "ON `diary_reactions` (`entryUuid`, `characterUuid`)",
        )
    }
}

/** v22→v23（故事×世界书 ST5·契约 `FABLE5_STORY_REDESIGN_PROPOSAL.md` §4）：
 *  `stories` 加 `worldInfoEnabled`（NOT NULL 默认 1 = 世界观设定参与生成默认开，旧故事行回填 1）。
 *  纯增量·旧行零丢失·列类型与 Room 生成的 23.json createSql 一致（MigrationTest 逐步校验）。 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `worldInfoEnabled` INTEGER NOT NULL DEFAULT 1")
    }
}

/** v23→v24（日记重设计 R5·契约 `FABLE5_DIARY_REDESIGN_PROPOSAL.md` §2 F4）：
 *  新建 `monthly_reviews`（月度回顾·独立轻实体不混入 diary_entries·monthStartMillis 唯一索引 =
 *  每月一篇幂等）。纯增量·旧行零丢失·DDL 与 Room 生成的 24.json createSql 一致（MigrationTest 逐步校验）。 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `monthly_reviews` (`uuid` TEXT NOT NULL, `monthStartMillis` INTEGER NOT NULL, " +
                "`content` TEXT NOT NULL, `moodCountsJson` TEXT NOT NULL, `generatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`uuid`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_monthly_reviews_monthStartMillis` " +
                "ON `monthly_reviews` (`monthStartMillis`)",
        )
    }
}

/** v24→v25（日记重设计 R6-3①·孤儿信语义 A「人走信留」·契约 `FABLE5_DIARY_REDESIGN_PROPOSAL.md` §6）：
 *  `diary_entries` 加 `authorNameSnapshot`（nullable·交换日记作者名快照）+ 一次性从 `characters` 回填存量
 *  交换日记的作者名（角色仍在的照抄名字；已删的查不到 → 保持 NULL）。纯增量·旧行零丢失·列类型与
 *  Room 生成的 25.json createSql 一致（MigrationTest 逐步校验 + 回填断言）。 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE diary_entries ADD COLUMN authorNameSnapshot TEXT")
        // 回填存量交换日记：作者角色仍在 → 抄下当时的名字；已删 → 子查询为 NULL，保持 NULL。
        db.execSQL(
            "UPDATE diary_entries SET authorNameSnapshot = " +
                "(SELECT name FROM characters WHERE characters.uuid = diary_entries.authorCharacterUuid) " +
                "WHERE authorCharacterUuid IS NOT NULL",
        )
    }
}

/** v25→v26（世界系统 W1 数据底座·契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / W1 图纸
 *  `docs/handoff/2026-07-03-世界系统W1数据底座.md`）：新建 8 张世界表——
 *  `world_state`（单行状态·种子/时区/位置/单调锚）/ `world_travel`（在途旅行·一 owner 一行）/
 *  `world_native_state`（原住民状态·双燃料眼缘/招募指针）/ `world_relationship`（角色↔角色**有向**多维边·**不设 FK**·
 *  混合域删除走仓库层事务）/ `world_relationship_event`（关系事件流水）/ `world_event`（须被记住/通知/小报消费的世界事件）/
 *  `world_city_lore`（首访点亮风物志·一次定稿 canon）/ `world_discovery`（奇观/城市发现）+ 3 索引
 *  （world_relationship.toId、world_relationship_event.pairKey+happenedAt、world_event.happenedAt）；
 *  再给 `characters` 加 3 列（joinedWorld/worldHomeCityId/worldJoinedAt·旧角色回填「不加入」+ 家乡城）。
 *  DDL 取自 Room 生成的 26.json createSql（字节级一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_state` (`id` INTEGER NOT NULL, `seed` INTEGER NOT NULL, " +
                "`userTimezoneId` TEXT, `userHomeCityId` TEXT NOT NULL, `userCurrentCityId` TEXT NOT NULL, " +
                "`lastSettledAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_travel` (`ownerId` TEXT NOT NULL, `fromCityId` TEXT NOT NULL, " +
                "`toCityId` TEXT NOT NULL, `departAt` INTEGER NOT NULL, `arriveAt` INTEGER NOT NULL, " +
                "`modeRaw` TEXT NOT NULL, `costGold` INTEGER NOT NULL, PRIMARY KEY(`ownerId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_native_state` (`nativeId` TEXT NOT NULL, `discovered` INTEGER NOT NULL, " +
                "`discoveredAt` INTEGER, `narrativeFuel` INTEGER NOT NULL, `giftFuel` INTEGER NOT NULL, " +
                "`encounterCount` INTEGER NOT NULL, `lastEncounterAt` INTEGER, `recruitedCharacterUuid` TEXT, " +
                "`currentCityId` TEXT, PRIMARY KEY(`nativeId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_relationship` (`fromId` TEXT NOT NULL, `toId` TEXT NOT NULL, " +
                "`typesJson` TEXT NOT NULL, `closeness` INTEGER NOT NULL, `trust` INTEGER NOT NULL, " +
                "`tension` INTEGER NOT NULL, `colorRaw` TEXT NOT NULL, `trajectoryRaw` TEXT NOT NULL, " +
                "`bond` TEXT NOT NULL, `origin` TEXT NOT NULL, `dormant` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`fromId`, `toId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_relationship_toId` ON `world_relationship` (`toId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_relationship_event` (`uuid` TEXT NOT NULL, `pairKey` TEXT NOT NULL, " +
                "`actorId` TEXT NOT NULL, `targetId` TEXT NOT NULL, `kindRaw` TEXT NOT NULL, `arcId` TEXT, " +
                "`summary` TEXT NOT NULL, `happenedAt` INTEGER NOT NULL, `settledAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`uuid`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_world_relationship_event_pairKey_happenedAt` " +
                "ON `world_relationship_event` (`pairKey`, `happenedAt`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_event` (`uuid` TEXT NOT NULL, `kindRaw` TEXT NOT NULL, " +
                "`involvedIdsJson` TEXT NOT NULL, `cityId` TEXT, `summary` TEXT NOT NULL, `happenedAt` INTEGER NOT NULL, " +
                "`notifiedAt` INTEGER, `seenAt` INTEGER, PRIMARY KEY(`uuid`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_event_happenedAt` ON `world_event` (`happenedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_city_lore` (`cityId` TEXT NOT NULL, `loreJson` TEXT NOT NULL, " +
                "`generatedAt` INTEGER NOT NULL, PRIMARY KEY(`cityId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_discovery` (`placeId` TEXT NOT NULL, `discoveredAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`placeId`))",
        )
        // characters +3 列（旧行回填：不加入世界 + 家乡城 + 未加入时刻 NULL）。
        db.execSQL("ALTER TABLE characters ADD COLUMN joinedWorld INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE characters ADD COLUMN worldHomeCityId TEXT NOT NULL DEFAULT 'city_yunye'")
        db.execSQL("ALTER TABLE characters ADD COLUMN worldJoinedAt INTEGER")
    }
}

/**
 * v26→v27（故事 ST8 结局档案）：stories 加 finalEndingType（可空 TEXT·完结时定格的结局类型徽章数据源）。
 * 纯增量·旧行新列回填 NULL（= 非用户请求的自然/满章结局，档案卡显示中性「全书完」）。守护漏列 / 误动旧表。
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE stories ADD COLUMN finalEndingType TEXT")
    }
}

/** v27→v28（世界系统 W5 联动闭环·契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §9 / W5 图纸
 *  `docs/handoff/2026-07-04-世界系统W5联动闭环.md` §3.1）：新建 3 张表——
 *  `world_memory`（per-角色双视角世界记忆 + 可空 embedding·两索引 characterUuid/happenedAt）/
 *  `world_bulletin`（每日开机小报·模板文 + 润色缓存·设备本地不入备份）/
 *  `world_llm_spend`（LLM 每日各类目花费台账·预算硬顶·设备本地不入备份）。
 *  DDL 逐字取自 Room 生成的 28.json createSql（字节级一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_memory` (`uuid` TEXT NOT NULL, `characterUuid` TEXT NOT NULL, " +
                "`otherIdsJson` TEXT NOT NULL, `kindRaw` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                "`happenedAt` INTEGER NOT NULL, `sourceUuid` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`embedding` BLOB, PRIMARY KEY(`uuid`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_memory_characterUuid` ON `world_memory` (`characterUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_memory_happenedAt` ON `world_memory` (`happenedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_bulletin` (`epochDay` INTEGER NOT NULL, `windowStartMs` INTEGER NOT NULL, " +
                "`windowEndMs` INTEGER NOT NULL, `eventsHash` INTEGER NOT NULL, `templateText` TEXT NOT NULL, " +
                "`polishedText` TEXT, `polishedAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_llm_spend` (`epochDay` INTEGER NOT NULL, `category` TEXT NOT NULL, " +
                "`count` INTEGER NOT NULL, PRIMARY KEY(`epochDay`, `category`))",
        )
    }
}

/** v28→v29（线下见面「梦剧场」B 部·契约 `FABLE5_MEETING_THEATER_PROPOSAL.md` §B2 决议 B-1 / 图纸
 *  `docs/handoff/2026-07-04-线下见面梦剧场.md` §3.2）：新建 `offline_meeting_memories`（每次见面一行的结构化回忆·
 *  两索引 characterUuid/sessionId）。**不在迁移里搬数据**——旧 blob（`characters.offlineMeetingMemorySummary`）
 *  冻结只读，播种走懒路径（Repository.ensureSeeded 首次访问解析）。DDL 逐字取自 Room 生成的 29.json createSql
 *  （字节级一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `offline_meeting_memories` (`uuid` TEXT NOT NULL, " +
                "`characterUuid` TEXT NOT NULL, `conversationUuid` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                "`kindRaw` TEXT NOT NULL, `startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER NOT NULL, " +
                "`location` TEXT NOT NULL, `activity` TEXT NOT NULL, `moodRaw` TEXT NOT NULL, " +
                "`initiatedByUser` INTEGER, `messageCount` INTEGER NOT NULL, `summary` TEXT NOT NULL, " +
                "`highlightsJson` TEXT NOT NULL, `promisesJson` TEXT NOT NULL, `sourceRaw` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_meeting_memories_characterUuid` ON `offline_meeting_memories` (`characterUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_meeting_memories_sessionId` ON `offline_meeting_memories` (`sessionId`)")
    }
}

/** v29→v30（活人感一期 P2 承诺回连·图纸 §3.2）：新建 `open_loops`（「心里惦记的事」结构化行·两索引
 *  conversationUuid/characterUuid·无 FK·手动级联清）+ conversations 加两列
 *  `lastOpenLoopScanSuccessDate`/`lastOpenLoopScanFailureDate`（均可空 INTEGER·扫描双轨冷却·仿 lastMeetingScan*）。
 *  DDL 逐字取自 Room 生成的 30.json createSql（字节级一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `open_loops` (`uuid` TEXT NOT NULL, `conversationUuid` TEXT NOT NULL, " +
                "`characterUuid` TEXT NOT NULL, `content` TEXT NOT NULL, `typeRaw` TEXT NOT NULL, " +
                "`dueAt` INTEGER, `statusRaw` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`resolvedAt` INTEGER, PRIMARY KEY(`uuid`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_open_loops_conversationUuid` ON `open_loops` (`conversationUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_open_loops_characterUuid` ON `open_loops` (`characterUuid`)")
        db.execSQL("ALTER TABLE conversations ADD COLUMN lastOpenLoopScanSuccessDate INTEGER")
        db.execSQL("ALTER TABLE conversations ADD COLUMN lastOpenLoopScanFailureDate INTEGER")
    }
}

/** v30→v31（世界二期战役 B·用户自建居民·契约 `FABLE5_WORLD_ART_RESIDENTS_PROPOSAL.md` §3 / 图纸
 *  `docs/handoff/2026-07-07-世界二期战役B-用户自建居民.md` §3.1）：新建 `world_user_resident`（用户自建居民静态人设·
 *  PK slug·与官方原住民常量平权、只多一张静态人设表·运行态仍共用 world_native_state）。DDL 逐字取自 Room 生成的
 *  31.json createSql（字节级一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_user_resident` (`slug` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`gender` TEXT NOT NULL, `age` INTEGER NOT NULL, `cityId` TEXT NOT NULL, `occupation` TEXT NOT NULL, " +
                "`personaBrief` TEXT NOT NULL, `traitsJson` TEXT NOT NULL, `freeformLore` TEXT NOT NULL, " +
                "`initialRelationText` TEXT NOT NULL, `fuelBias` TEXT NOT NULL, `avatarPath` TEXT, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`slug`))",
        )
    }
}

/**
 * v32（故事长篇稳定性 L1·契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §3.1）：
 * stories 新增圣经结构化压缩水位线列 lastBibleCompressedAtChapter（可空，null=从未压缩）。
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `lastBibleCompressedAtChapter` INTEGER")
    }
}

/** v32→v33（记忆改造一期·部件① 承诺账本 + 部件③/朋友圈/日记消化标记·图纸 §3.4）：
 *  ① 新建 `promises`（「我们的约定」结构化行·索引 characterUuid·无 FK·手动级联清·会话删不连坐）；
 *  ② `offline_meeting_memories` 加 `digestedAtMillis`（可空·降级前消化标记·NULL=未消化）；
 *  ③ `diary_entries` 加 `digestedAtMillis`（可空·交换日记回流消化标记）；
 *  ④ `characters` 加 `momentsDigestedUntilMillis`（NOT NULL 默认 0·朋友圈消化水位线·0=从未消化）。
 *  DDL 逐字取自 Room 生成的 33.json createSql（字节级一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `promises` (`uuid` TEXT NOT NULL, `characterUuid` TEXT NOT NULL, " +
                "`conversationUuid` TEXT NOT NULL, `content` TEXT NOT NULL, `statusRaw` TEXT NOT NULL, " +
                "`dueAtMillis` INTEGER, `sourceRaw` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, " +
                "`openLoopUuid` TEXT, `resolvedAtMillis` INTEGER, `resolutionEvidence` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_promises_characterUuid` ON `promises` (`characterUuid`)")
        db.execSQL("ALTER TABLE offline_meeting_memories ADD COLUMN digestedAtMillis INTEGER")
        db.execSQL("ALTER TABLE diary_entries ADD COLUMN digestedAtMillis INTEGER")
        db.execSQL("ALTER TABLE characters ADD COLUMN momentsDigestedUntilMillis INTEGER NOT NULL DEFAULT 0")
    }
}

/** v33→v34（记忆改造二期·部件⑤ 场内滚动压缩·前情提要·图纸 §3.2-A / §3.4）：
 *  conversations 加三列 `inSceneRecapText`（TEXT NOT NULL 默认 ''·前情提要正文）、
 *  `inSceneRecapSessionKey`（TEXT NOT NULL 默认 ''·惰性失效判据 key）、
 *  `inSceneRecapUntilMillis`（INTEGER NOT NULL 默认 0·已覆盖水位）。
 *  DDL 逐字取自 Room 生成的 34.json createSql（字节级一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN inSceneRecapText TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversations ADD COLUMN inSceneRecapSessionKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversations ADD COLUMN inSceneRecapUntilMillis INTEGER NOT NULL DEFAULT 0")
    }
}

/** v34→v35（记忆改造四期·部件⑥ 见面档案入向量索引·图纸 §3.2 / §2.2）：
 *  offline_meeting_memories 加 embedding（可空 BLOB·见面档案语义向量·NULL=待后台限流回填·legacy/空 summary 行永不建）。
 *  ALTER ADD COLUMN 的列定义逐字取自 Room 生成的 35.json createSql（`embedding` BLOB），MigrationTest 校验；纯增量·旧行零丢失。 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE offline_meeting_memories ADD COLUMN embedding BLOB")
    }
}

// 成长原型校准（图纸 §3.4）：characters +relationshipArchetypeId。DDL 逐字取 36.json（TEXT·可空·无默认）。
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE characters ADD COLUMN relationshipArchetypeId TEXT")
    }
}

/** v36→v37（上下文日志工具可见性·2026-07-12）：log_entries 加 `toolInfoJson`
 *  （TEXT NOT NULL 默认 ''·[com.situ.aichat.diagnostics.LogToolInfo] 序列化·空=旧行/无遥测→详情页隐藏该节）。
 *  DDL 逐字取自 Room 生成的 37.json createSql（实体带 @ColumnInfo(defaultValue="''")，字节级一致），
 *  MigrationTest 校验；纯增量·旧行零丢失。 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE log_entries ADD COLUMN toolInfoJson TEXT NOT NULL DEFAULT ''")
    }
}

/** v37→v38（相处偏好·四小件 2026-07-16）：user_profile 加 `companionPreference`
 *  （TEXT NOT NULL 默认 ''·「希望 TA 怎么待你」·空=旧行/未填→persona 段不注入该行）。
 *  DDL 逐字取自 Room 生成的 38.json createSql（实体带 @ColumnInfo(defaultValue="''")，字节级一致），
 *  MigrationTest 校验；纯增量·旧行零丢失。 */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN companionPreference TEXT NOT NULL DEFAULT ''")
    }
}

/** v38→v39（故事推进主权 ST11·图纸 docs/handoff/2026-07-17-故事推进主权.md）：`story_chapters` 加
 *  `aiSuggestedEnding`（INTEGER NOT NULL 默认 0 = LLM 未自标结局，v38 存量章全 false）。该列只承载
 *  「AI 建议完结」的印，不参与状态决策——完结权归用户（拍板②）。列名/类型与 Room 生成的 39.json
 *  createSql 一致（MigrationTest 逐版本校验），纯增量·旧行零丢失。 */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `story_chapters` ADD COLUMN `aiSuggestedEnding` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v39→v40（故事无限连载卷二·图纸 docs/handoff/2026-07-26-故事无限连载卷二单模式化与终章弧.md §3.1）：
 *  `stories` 加三列——`arcHistory`（弧线简史 B2）/ `finaleEndingType` + `finaleEndingDetail`（终章弧收尾计划 J1），
 *  均可空 TEXT，v39 存量行全 NULL。列名/类型逐字取自 Room 生成的 40.json createSql（MigrationTest 逐版本校验）。
 *
 *  **外加一条存量数据归一化（J2 拍板：全面无限连载单模式）**：把所有旧「有限模式」书转成无限连载
 *  （`maxChapters = NULL, autoExtendCount = 0`）。两列本身**有意保留不删**（J3：删列要 Room 重建表，
 *  收益为零风险为正），只退役「写非 null/非 0」的语义；备份导入侧同样归一化（防老备份把满章语义带回）。
 *  已完结的书 status 不动，照旧留在档案；在读的书失去章数上限、改由终章弧收尾。 */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `arcHistory` TEXT")
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `finaleEndingType` TEXT")
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `finaleEndingDetail` TEXT")
        db.execSQL("UPDATE `stories` SET `maxChapters` = NULL, `autoExtendCount` = 0")
    }
}

/** v40→v41（故事阅读器掌控力 C3·图纸 docs/handoff/2026-08-01-故事阅读器掌控力-图纸三.md §3.5）：
 *  两表各加一可空 TEXT 列，v40 存量行全 NULL——
 *  `story_chapters.previousDraftJson`（「上一版」单槽：重写前那版的 12 个内容字段编码 JSON）、
 *  `stories.pendingRewriteDraftJson`（重写期的旧稿接力棒：删章前先落库，materialize 时搬进新章并清空）。
 *  列名/类型逐字取自 Room 生成的 41.json createSql（MigrationTest 逐版本校验），纯增量·旧行零丢失·零数据改写。 */
val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `story_chapters` ADD COLUMN `previousDraftJson` TEXT")
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `pendingRewriteDraftJson` TEXT")
    }
}

/** v41→v42（故事「我的模板」·图纸 docs/handoff/2026-08-02-故事提示词实验室-图纸四.md §3.2）：
 *  新建 `user_story_templates` 表（整套创作设定存单列 JSON·无索引无外键·与故事表零关联）。
 *  DDL 逐字取自 Room 生成的 42.json createSql（`${'$'}{TABLE_NAME}` 换成真表名，其余字节一致），MigrationTest 校验；
 *  纯建表·旧表零触碰·零数据改写。 */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_story_templates` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, PRIMARY KEY(`uuid`))",
        )
    }
}

/** v42→v43（故事二期卷一·图纸 docs/handoff/2026-08-02-故事二期卷一-生成大脑.md §3.1）：三表共六列，
 *  存量行全落 NULL / 0——
 *  `stories` 的账本族三件（`intimacyLedger` 关系史两段制 / `sceneState` 章末场景快照 / `sceneLedger` 场景台账）
 *  + `pendingBeatsUserEdited`（章级节拍是否被用户在导演台改过·INTEGER NOT NULL DEFAULT 0 = 存量书一律「AI 预排」）、
 *  `story_chapters.userRating`（读者三档快评 1/2/3·NULL = 未评）、
 *  `story_character_roles.intimatePersona`（角色私下反差）。
 *  表名/列型逐字对照 42.json createSql（MigrationTest 逐版本校验），纯增量·旧行零丢失·零数据改写。 */
val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `intimacyLedger` TEXT")
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `sceneState` TEXT")
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `sceneLedger` TEXT")
        db.execSQL("ALTER TABLE `stories` ADD COLUMN `pendingBeatsUserEdited` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `story_chapters` ADD COLUMN `userRating` INTEGER")
        db.execSQL("ALTER TABLE `story_character_roles` ADD COLUMN `intimatePersona` TEXT")
    }
}

/** v43→v44（活人感统一内核·卷一《人设编译器》·图纸 docs/handoff/2026-09-01-活人感内核-卷一-人设编译器.md §表3）：
 *  `characters` 加人设编译四列——`personalityAnchorJSON`（本性锚点）/ `personaCompileMetaJSON`（编译元数据）/
 *  `personaGainsJSON`（增益）/ `personaOperatorsJSON`（算子），全部 TEXT NOT NULL DEFAULT ''。
 *  存量行一律落 ''  = 「从未编译过」：锚点走解码访问器兜底（图纸 Y-1 · 本性 == 现在），其余三列解码回落默认值，
 *  **不扫全库、不写库、不清零**。列型与 `worldHomeCityId` / `momentsDigestedUntilMillis` 同例（实体侧无
 *  @ColumnInfo(defaultValue)，DEFAULT '' 只服务存量行回填，MigrationTest 逐版本校验）；纯增量·旧行零丢失。 */
val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE characters ADD COLUMN personalityAnchorJSON TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE characters ADD COLUMN personaCompileMetaJSON TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE characters ADD COLUMN personaGainsJSON TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE characters ADD COLUMN personaOperatorsJSON TEXT NOT NULL DEFAULT ''")
    }
}

/** v44→v45（活人感统一内核·卷二《正负双压》·图纸 docs/handoff/2026-09-02-活人感内核-卷二-正负双压.md §表3）：
 *  `characters` 加 `relationshipPressureJSON`（关系 8 维正压/负压双记账），TEXT NOT NULL DEFAULT ''。
 *  存量行一律落 '' = 「还没分开记过」：解码访问器按 `RelationshipPressure.fromQuality` 播种
 *  （pos = 当前净额、neg = 0，满足不变式 I-1），**不扫全库、不写库、不清零**；净额列 `relationshipQualityJSON`
 *  语义一字不变（7 类下游读者零改动）。列型与卷一四列同例；纯增量·旧行零丢失。 */
val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE characters ADD COLUMN relationshipPressureJSON TEXT NOT NULL DEFAULT ''")
    }
}

/** v45→v46（活人感统一内核·卷三《场内核与渲染收编》·图纸 docs/handoff/2026-09-02-活人感内核-卷三-场内核与渲染收编.md §3.2）：
 *  `characters` 加 `affectFieldJSON`（四场 + 日预算 + 最近命中），TEXT NOT NULL DEFAULT ''。
 *  存量行一律落 '' = 「还没写过场」：解码访问器回默认 `AffectField()`（K-11：本列无派生源可播种），
 *  **不扫全库、不写库**，首次 tick 才写出默认 + 脉冲。列型与卷一/卷二同例；纯增量·旧行零丢失。 */
val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE characters ADD COLUMN affectFieldJSON TEXT NOT NULL DEFAULT ''")
    }
}

/** v46→v47（活人感统一内核·卷四《意图队列 + 性格复盘》·图纸 docs/handoff/2026-09-02-活人感内核-卷四-意图队列与性格复盘.md §3.2）：
 *  `characters` 加 `intentQueueJSON`（意图队列 + 性格复盘计数），TEXT NOT NULL DEFAULT ''。
 *  存量行一律落 '' = 「还没写过意图」：解码访问器回默认 `IntentQueueState()`（E36：本列无派生源可播种），
 *  **不扫全库、不写库**，首次分析通道 / 有变化的 tick 才写出。列型与卷一/二/三同例；纯增量·旧行零丢失。 */
val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE characters ADD COLUMN intentQueueJSON TEXT NOT NULL DEFAULT ''")
    }
}

/** v47→v48（「我们的日子」卷一《沉淀》·总图纸 docs/handoff/2026-09-02-我们的日子-总图纸.md §3.1 / §3.2）：
 *  ① 新建 `our_days`（一天 × 一角色的事实快照 + 手记 + 事实行·索引 characterUuid + 唯一索引 (characterUuid, dayKey)·无 FK·手动级联清）；
 *  ② `characters` 加 `ourDaysBackfilledAt`（可空·一次性回填完成标记·NULL = 未回填）。
 *  DDL 逐字取自 Room 生成的 48.json createSql（`${'$'}{TABLE_NAME}` 换真表名，其余字节一致），MigrationTest 校验；纯增量·旧表零丢失。 */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS `our_days` (`uuid` TEXT NOT NULL, `characterUuid` TEXT NOT NULL, " +
                "`dayKey` TEXT NOT NULL, `factsJson` TEXT NOT NULL, `messageCount` INTEGER NOT NULL, " +
                "`callSeconds` INTEGER NOT NULL, `hasMeeting` INTEGER NOT NULL, `hasRelation` INTEGER NOT NULL, " +
                "`hasLife` INTEGER NOT NULL, `note` TEXT NOT NULL, `factLine` TEXT NOT NULL, `noteStatus` TEXT NOT NULL, " +
                "`noteAttempts` INTEGER NOT NULL, `noteEdited` INTEGER NOT NULL, `hiddenFromMemory` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, `generatedAt` INTEGER, `createdAtMillis` INTEGER NOT NULL, " +
                "`updatedAtMillis` INTEGER NOT NULL, `embedding` BLOB, PRIMARY KEY(`uuid`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_our_days_characterUuid` ON `our_days` (`characterUuid`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_our_days_characterUuid_dayKey` ON `our_days` (`characterUuid`, `dayKey`)")
        db.execSQL("ALTER TABLE characters ADD COLUMN ourDaysBackfilledAt INTEGER")
    }
}

/** v48→v49（散场硬闸·图纸 docs/handoff/2026-09-06-见面窗口与节拍卡七件.md §3.E）：
 *  `conversations` 加 `offlineEndHoldTurns`（点「再待一会儿」后不许散场的剩余 AI 回合数·老行默认 0 = 无闸）。 */
val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `offlineEndHoldTurns` INTEGER NOT NULL DEFAULT 0")
    }
}

/** 全部迁移（按序），注入 Room.databaseBuilder().addMigrations(*ALL_MIGRATIONS)。 */
val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_18_19,
    MIGRATION_19_20,
    MIGRATION_20_21,
    MIGRATION_21_22,
    MIGRATION_22_23,
    MIGRATION_23_24,
    MIGRATION_24_25,
    MIGRATION_25_26,
    MIGRATION_26_27,
    MIGRATION_27_28,
    MIGRATION_28_29,
    MIGRATION_29_30,
    MIGRATION_30_31,
    MIGRATION_31_32,
    MIGRATION_32_33,
    MIGRATION_33_34,
    MIGRATION_34_35,
    MIGRATION_35_36,
    MIGRATION_36_37,
    MIGRATION_37_38,
    MIGRATION_38_39,
    MIGRATION_39_40,
    MIGRATION_40_41,
    MIGRATION_41_42,
    MIGRATION_42_43,
    MIGRATION_43_44,
    MIGRATION_44_45,
    MIGRATION_45_46,
    MIGRATION_46_47,
    MIGRATION_47_48,
    MIGRATION_48_49,
)
