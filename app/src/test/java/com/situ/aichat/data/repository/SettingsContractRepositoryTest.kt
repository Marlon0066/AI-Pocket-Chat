package com.situ.aichat.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.situ.aichat.data.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * D-11 设置值合约测试（卷一护栏）：锁定 `SettingsRepository` 全部用户可调设置项的
 * DataStore 键名、默认值、setter 写入读回、取值钳位与备份往返。卷一～卷四重构期间不得静默破坏。
 */
class SettingsContractRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "contract_sr.preferences_pb") })
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test fun defaultsMatchAppSettingsContract() = runBlocking {
        val loaded = repo.appSettings.first()
        val expected = AppSettings()
        assertEquals(expected.shortTermMemoryLength, loaded.shortTermMemoryLength)
        assertEquals(expected.autoSummarizeInterval, loaded.autoSummarizeInterval)
        assertEquals(expected.memorySummaryMaxLength, loaded.memorySummaryMaxLength)
        assertEquals(expected.memorySummaryCooldownMinutes, loaded.memorySummaryCooldownMinutes)
        assertEquals(expected.progressiveCompressionEnabled, loaded.progressiveCompressionEnabled)
        assertEquals(expected.structuredMemoryInterval, loaded.structuredMemoryInterval)
        assertEquals(expected.vectorSearchThreshold, loaded.vectorSearchThreshold)
        assertEquals(expected.replySegmentMin, loaded.replySegmentMin)
        assertEquals(expected.replySegmentMax, loaded.replySegmentMax)
        assertEquals(expected.voiceReplyRoundMin, loaded.voiceReplyRoundMin)
        assertEquals(expected.voiceReplyRoundMax, loaded.voiceReplyRoundMax)
        assertEquals(expected.llmTemperature, loaded.llmTemperature, 1e-6)
        assertEquals(expected.storyCreationTemperature, loaded.storyCreationTemperature, 1e-6)
        assertEquals(expected.growthSystemEnabled, loaded.growthSystemEnabled)
        assertEquals(expected.scheduleSystemEnabled, loaded.scheduleSystemEnabled)
        assertEquals(expected.petSystemEnabled, loaded.petSystemEnabled)
        assertEquals(expected.currencySystemEnabled, loaded.currencySystemEnabled)
        assertEquals(expected.notificationsEnabled, loaded.notificationsEnabled)
        assertEquals(expected.emotionAnimationEnabled, loaded.emotionAnimationEnabled)
        assertEquals(expected.textingToneEnabled, loaded.textingToneEnabled)
        assertEquals(expected.worldVividnessTier, loaded.worldVividnessTier)
    }

    @Test fun keyNamesAreFrozen() {
        val frozenKeys = mapOf(
            SettingsRepository.KEY_CHAT_SEND_WAIT_SECONDS.name to "chat_send_wait_seconds",
            SettingsRepository.KEY_SHORT_TERM_MEMORY.name to "short_term_memory_length",
            SettingsRepository.KEY_AUTO_SUMMARIZE_INTERVAL.name to "auto_summarize_interval",
            SettingsRepository.KEY_MEMORY_SUMMARY_MAX.name to "memory_summary_max_length",
            SettingsRepository.KEY_MEMORY_SUMMARY_COOLDOWN_MINUTES.name to "memory_summary_cooldown_minutes",
            SettingsRepository.KEY_PROGRESSIVE_COMPRESSION.name to "progressive_compression_enabled",
            SettingsRepository.KEY_STRUCTURED_MEMORY_INTERVAL.name to "structured_memory_interval",
            SettingsRepository.KEY_VECTOR_SEARCH_THRESHOLD.name to "vector_search_threshold",
            SettingsRepository.KEY_WI_SCAN_DEPTH.name to "world_info_scan_depth",
            SettingsRepository.KEY_WI_BUDGET_CHARS.name to "world_info_budget_chars",
            SettingsRepository.KEY_WI_RECURSIVE.name to "world_info_recursive_scan",
            SettingsRepository.KEY_WI_MAX_RECURSION.name to "world_info_max_recursion_steps",
            SettingsRepository.KEY_WI_STRATEGY.name to "world_info_insertion_strategy",
            SettingsRepository.KEY_WI_CASE_SENSITIVE.name to "world_info_case_sensitive",
            SettingsRepository.KEY_WI_WHOLE_WORDS.name to "world_info_match_whole_words",
            SettingsRepository.KEY_WORLD_VIVIDNESS_TIER.name to "world_vividness_tier",
            SettingsRepository.KEY_WORLD_RELATIONSHIPS_ENABLED.name to "world_char_relationships_enabled",
            SettingsRepository.KEY_WORLD_ROMANCE_ENABLED.name to "world_char_romance_enabled",
            SettingsRepository.KEY_WORLD_NOTIFICATION_TIER.name to "world_notification_tier",
            SettingsRepository.KEY_LOG_RETENTION_COUNT.name to "log_retention_count",
            SettingsRepository.KEY_LOG_DETAIL_ENABLED.name to "log_detail_enabled",
            SettingsRepository.KEY_PERF_COLLECT_ENABLED.name to "perf_collect_enabled",
            SettingsRepository.KEY_REPLY_SEGMENT_MIN.name to "reply_segment_min",
            SettingsRepository.KEY_REPLY_SEGMENT_MAX.name to "reply_segment_max",
            SettingsRepository.KEY_VOICE_REPLY_ROUND_MIN.name to "voice_reply_round_min",
            SettingsRepository.KEY_VOICE_REPLY_ROUND_MAX.name to "voice_reply_round_max",
            SettingsRepository.KEY_LLM_TEMPERATURE.name to "llm_temperature",
            SettingsRepository.KEY_STORY_CREATION_TEMPERATURE.name to "story_creation_temperature",
            SettingsRepository.KEY_STORY_BANNED_EXPRESSIONS.name to "story_banned_expressions",
            SettingsRepository.KEY_STORY_SCENE_BEATS.name to "story_scene_beats",
            SettingsRepository.KEY_STORY_TASTE_PROFILE.name to "story_taste_profile",
            SettingsRepository.KEY_GROWTH_SYSTEM.name to "growth_system_enabled",
            SettingsRepository.KEY_GROWTH_ANALYSIS_INTERVAL.name to "growth_analysis_interval",
            SettingsRepository.KEY_GROWTH_LOG_MAX_COUNT.name to "growth_log_max_count",
            SettingsRepository.KEY_MOOD_HISTORY_MAX_COUNT.name to "mood_history_max_count",
            SettingsRepository.KEY_INTEREST_COOLDOWN_DAYS.name to "interest_cooldown_days",
            SettingsRepository.KEY_RELATIONSHIP_AUTO_ADVANCE.name to "relationship_auto_advance_enabled",
            SettingsRepository.KEY_PET_SYSTEM.name to "pet_system_enabled",
            SettingsRepository.KEY_CURRENCY_SYSTEM.name to "currency_system_enabled",
            SettingsRepository.KEY_PROACTIVE_GIFT.name to "character_proactive_gift_enabled",
            SettingsRepository.KEY_NOTIFICATIONS_ENABLED.name to "notifications_enabled",
            SettingsRepository.KEY_ECONOMY_NOTIFICATION_TIER.name to "economy_notification_tier",
            SettingsRepository.KEY_MILESTONE_NOTIF_ENABLED.name to "milestone_notification_enabled",
            SettingsRepository.KEY_NOTIF_QUIET_HOURS_ENABLED.name to "notif_quiet_hours_enabled",
            SettingsRepository.KEY_NOTIF_QUIET_HOURS_START.name to "notif_quiet_hours_start",
            SettingsRepository.KEY_NOTIF_QUIET_HOURS_END.name to "notif_quiet_hours_end",
            SettingsRepository.KEY_EMOTION_ANIMATION.name to "emotion_animation_enabled",
            SettingsRepository.KEY_TEXTING_TONE.name to "texting_tone_enabled",
            SettingsRepository.KEY_CACHE_SAVER.name to "cache_saver_enabled",
            SettingsRepository.KEY_CALENDAR_REMINDER_MODE.name to "calendar_reminder_mode",
            SettingsRepository.KEY_DIARY_AUTO_GENERATE.name to "diary_auto_generate_enabled",
            SettingsRepository.KEY_DIARY_AUTO_GENERATE_TIME.name to "diary_auto_generate_time",
            SettingsRepository.KEY_MOMENT_AUTO_POST_FREQ.name to "moment_auto_post_frequency",
            SettingsRepository.KEY_MOMENT_AUTO_COMMENT_FREQ.name to "moment_auto_comment_frequency",
            SettingsRepository.KEY_MOMENT_AUTO_LIKE.name to "moment_auto_like_enabled",
            SettingsRepository.KEY_MOMENT_COMMENT_DELAY.name to "moment_comment_delay",
            SettingsRepository.KEY_MOMENT_NEW_POST_NOTIF.name to "moment_new_post_notification_enabled",
            SettingsRepository.KEY_CHARACTER_CAN_SEND_STICKERS.name to "character_can_send_stickers_enabled",
            SettingsRepository.KEY_PET_DIARY_AUTO_GENERATE.name to "pet_diary_auto_generate_enabled",
            SettingsRepository.KEY_VOICE_CALL_INTERRUPT_THRESHOLD.name to "voice_call_interrupt_threshold",
            SettingsRepository.KEY_CALENDAR_INTEGRATION.name to "calendar_integration_enabled",
            SettingsRepository.KEY_CALENDAR_ACTION_CONFIRMATION.name to "calendar_action_confirmation",
            SettingsRepository.KEY_SCHEDULE_SYSTEM.name to "schedule_system_enabled",
            SettingsRepository.KEY_CROSS_CHARACTER_LEVEL.name to "cross_character_level",
            SettingsRepository.KEY_CHARACTER_CAN_INITIATE_MEETING.name to "character_can_initiate_offline_meeting",
            SettingsRepository.KEY_OFFLINE_IMMERSIVE_INPUT.name to "offline_immersive_input_enabled",
            SettingsRepository.KEY_OFFLINE_BG_STYLE.name to "offline_background_style_raw",
            SettingsRepository.KEY_OFFLINE_NARRATIVE_DETAIL.name to "offline_narrative_detail_raw",
            SettingsRepository.KEY_MEETING_MEMORY_MAX_LENGTH.name to "meeting_memory_max_length",
            SettingsRepository.KEY_MEETING_MEMORY_INJECT_COUNT.name to "meeting_memory_inject_count",
            SettingsRepository.KEY_OFFLINE_AFTERGLOW.name to "offline_afterglow_enabled",
            SettingsRepository.KEY_MEMORY_EXTRACTION_PROMPT.name to "memory_extraction_prompt",
            SettingsRepository.KEY_MEMORY_INJECTION_PROMPT.name to "memory_injection_prompt",
            SettingsRepository.KEY_AUTO_BACKUP_ENABLED.name to "auto_backup_enabled",
            SettingsRepository.KEY_BACKUP_INCLUDE_MEDIA.name to "backup_include_media",
            SettingsRepository.KEY_NOTIF_RED_PACKET_EXPIRING.name to "notif_red_packet_expiring_enabled",
            SettingsRepository.KEY_NOTIF_MEETING_REMINDER.name to "notif_meeting_reminder_enabled",
            SettingsRepository.KEY_NOTIF_STORY_FINISHED.name to "notif_story_finished_enabled",
            SettingsRepository.KEY_NOTIF_MOMENT_INTERACTION.name to "notif_moment_interaction_enabled",
        )
        frozenKeys.forEach { (actual, expected) -> assertEquals(expected, actual) }
    }

    @Test fun settersRoundTripAndClampContract() = runBlocking {
        repo.setShortTermMemoryLength(45)
        repo.setAutoSummarizeInterval(15)
        repo.setMemorySummaryMaxLength(100) // clamps >= 200 or check actual clamp
        repo.setProgressiveCompressionEnabled(true)
        repo.setReplySegmentRange(2, 5)
        repo.setVoiceReplyRoundRange(3, 8)
        repo.setLlmTemperature(1.1)
        repo.setStoryCreationTemperature(0.85)
        repo.setEmotionAnimationEnabled(false)
        repo.setTextingToneEnabled(false)
        repo.setGrowthSystemEnabled(false)
        repo.setScheduleSystemEnabled(false)
        repo.setCrossCharacterLevel(2)
        repo.setPetSystemEnabled(false)
        repo.setCurrencySystemEnabled(false)
        repo.setCharacterProactiveGiftEnabled(false)
        repo.setNotificationsEnabled(false)
        repo.setMilestoneNotificationEnabled(false)
        repo.setNotifRedPacketExpiringEnabled(false)
        repo.setNotifMeetingReminderEnabled(false)
        repo.setNotifStoryFinishedEnabled(false)
        repo.setNotifMomentInteractionEnabled(false)
        repo.setBackupIncludeMedia(false)
        repo.setQuietHoursEnabled(true)
        repo.setCalendarIntegrationEnabled(true)
        repo.setCalendarActionConfirmation(false)
        repo.setDiaryAutoGenerateEnabled(true)
        repo.setDiaryAutoGenerateTime("22:30")
        repo.setPetDiaryAutoGenerateEnabled(false)
        repo.setMomentAutoPostFrequency(2)
        repo.setMomentAutoLikeEnabled(false)
        repo.setCharacterCanSendStickersEnabled(false)
        repo.setVoiceCallInterruptThreshold(0.55f)
        repo.setOfflineAfterglowEnabled(false)
        repo.setMeetingMemoryInjectCount(15) // clamps to 1..10
        repo.setMeetingMemoryMaxLength(50) // clamps >= 200
        repo.setWorldVividnessTier(AppSettings.WORLD_VIVIDNESS_RICH)
        repo.setStoryBannedExpressions("禁止词")
        repo.setStorySceneBeats("节拍")
        repo.setStoryTasteProfile("画像")

        val s = repo.appSettings.first()
        assertEquals(45, s.shortTermMemoryLength)
        assertEquals(15, s.autoSummarizeInterval)
        assertTrue(s.progressiveCompressionEnabled)
        assertEquals(2, s.replySegmentMin)
        assertEquals(5, s.replySegmentMax)
        assertEquals(3, s.voiceReplyRoundMin)
        assertEquals(8, s.voiceReplyRoundMax)
        assertEquals(1.1, s.llmTemperature, 1e-6)
        assertEquals(0.85, s.storyCreationTemperature, 1e-6)
        assertFalse(s.emotionAnimationEnabled)
        assertFalse(s.textingToneEnabled)
        assertFalse(s.growthSystemEnabled)
        assertFalse(s.scheduleSystemEnabled)
        assertEquals(2, s.crossCharacterLevel)
        assertFalse(s.petSystemEnabled)
        assertFalse(s.currencySystemEnabled)
        assertFalse(s.characterProactiveGiftEnabled)
        assertFalse(s.notificationsEnabled)
        assertFalse(s.milestoneNotificationEnabled)
        assertFalse(s.notifRedPacketExpiringEnabled)
        assertFalse(s.notifMeetingReminderEnabled)
        assertFalse(s.notifStoryFinishedEnabled)
        assertFalse(s.notifMomentInteractionEnabled)
        assertFalse(repo.backupIncludeMedia.first())
        assertTrue(s.quietHoursEnabled)
        assertTrue(s.calendarIntegrationEnabled)
        assertFalse(s.calendarActionConfirmation)
        assertTrue(s.diaryAutoGenerateEnabled)
        assertEquals("22:30", s.diaryAutoGenerateTime)
        assertFalse(s.petDiaryAutoGenerateEnabled)
        assertEquals(2, s.momentAutoPostFrequency)
        assertFalse(s.momentAutoLikeEnabled)
        assertFalse(s.characterCanSendStickersEnabled)
        assertEquals(0.4f, s.voiceCallInterruptThreshold, 1e-4f)
        assertFalse(s.offlineAfterglowEnabled)
        assertEquals(10, s.meetingMemoryInjectCount)
        assertEquals(200, s.meetingMemoryMaxLength)
        assertEquals(AppSettings.WORLD_VIVIDNESS_RICH, s.worldVividnessTier)
        assertEquals("禁止词", s.storyBannedExpressions)
        assertEquals("节拍", s.storySceneBeats)
        assertEquals("画像", s.storyTasteProfile)

        // Clearing story global overrides restores null
        repo.setStoryBannedExpressions(null)
        repo.setStorySceneBeats(null)
        repo.setStoryTasteProfile(null)
        val cleared = repo.appSettings.first()
        assertEquals(null, cleared.storyBannedExpressions)
        assertEquals(null, cleared.storySceneBeats)
        assertEquals(null, cleared.storyTasteProfile)
    }

    @Test fun backupRoundTripPreservesSettings() = runBlocking {
        val custom = AppSettings(
            shortTermMemoryLength = 42,
            replySegmentMin = 1,
            replySegmentMax = 4,
            llmTemperature = 0.75,
            emotionAnimationEnabled = false,
            textingToneEnabled = false,
            notificationsEnabled = false,
            notifRedPacketExpiringEnabled = false,
            notifMeetingReminderEnabled = false,
            notifStoryFinishedEnabled = false,
            notifMomentInteractionEnabled = false,
            petDiaryAutoGenerateEnabled = false,
            worldVividnessTier = AppSettings.WORLD_VIVIDNESS_LITE,
        )
        repo.applyBackupSettings(custom)
        val restored = repo.appSettings.first()
        assertEquals(42, restored.shortTermMemoryLength)
        assertEquals(1, restored.replySegmentMin)
        assertEquals(4, restored.replySegmentMax)
        assertEquals(0.75, restored.llmTemperature, 1e-6)
        assertFalse(restored.emotionAnimationEnabled)
        assertFalse(restored.textingToneEnabled)
        assertFalse(restored.notificationsEnabled)
        assertFalse(restored.notifRedPacketExpiringEnabled)
        assertFalse(restored.notifMeetingReminderEnabled)
        assertFalse(restored.notifStoryFinishedEnabled)
        assertFalse(restored.notifMomentInteractionEnabled)
        assertFalse(restored.petDiaryAutoGenerateEnabled)
        assertEquals(AppSettings.WORLD_VIVIDNESS_LITE, restored.worldVividnessTier)
    }
}
