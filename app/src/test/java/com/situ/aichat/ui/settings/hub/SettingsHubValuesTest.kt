package com.situ.aichat.ui.settings.hub

import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1：设置主页行尾值映射 + 行级搜索纯函数单测（设置全面重构卷一）。 */
class SettingsHubValuesTest {

    @Test
    fun `行尾值映射覆盖未配置警示、后台未豁免警示、日程来往、备份与默认分支`() {
        val snap = SettingsHubSnapshot(
            activeConfigLabel = null,
            chatSendWaitSeconds = 1.5f,
            replySegmentRange = 2..6,
            stickersEnabled = false,
            shortTermMemoryLength = 30,
            enabledWorldBookCount = 0,
            scheduleEnabled = true,
            crossCharacterLevel = 2,
            diaryAutoEnabled = true,
            diaryAutoTime = "21:30",
            momentAutoPostFrequency = 0,
            storyTemperature = 1.0,
            autoBackupEnabled = true,
            lastBackupAt = 1_700_000_000_000L,
        )

        val ai = trailingValueFor(SettingsHubEntry.AI_SERVICE, snap, false, "zh-CN", "1.0", { "经常" })!!
        assertEquals(R.string.settings_api_not_configured, ai.textRes)
        assertTrue(ai.warning)

        val bgWarn = trailingValueFor(SettingsHubEntry.BACKGROUND_RELIABILITY, snap, false, "zh-CN", "1.0", { "经常" })!!
        assertEquals(R.string.settings_hub_val_bg_not_ready, bgWarn.textRes)
        assertTrue(bgWarn.warning)

        val bgOk = trailingValueFor(SettingsHubEntry.BACKGROUND_RELIABILITY, snap, true, "zh-CN", "1.0", { "经常" })!!
        assertEquals(R.string.settings_hub_val_bg_ready, bgOk.textRes)
        assertFalse(bgOk.warning)

        val reply = trailingValueFor(SettingsHubEntry.REPLY, snap, true, "zh-CN", "1.0", { "经常" })!!
        assertEquals(listOf("1.5", 2, 6), reply.formatArgs)

        val wb = trailingValueFor(SettingsHubEntry.WORLDBOOKS, snap, true, "zh-CN", "1.0", { "经常" })!!
        assertEquals(R.string.settings_hub_val_worldbook_none, wb.textRes)

        val schedule = trailingValueFor(SettingsHubEntry.SCHEDULE, snap, true, "zh-CN", "1.0", { "经常" })!!
        assertEquals(R.string.settings_hub_val_schedule_with_cross, schedule.textRes)
        assertEquals(listOf("经常"), schedule.formatArgs)

        val diary = trailingValueFor(SettingsHubEntry.DIARY, snap, true, "zh-CN", "1.0", { "经常" })!!
        assertEquals(R.string.settings_hub_val_diary_daily, diary.textRes)
        assertEquals(listOf("21:30"), diary.formatArgs)

        val moment = trailingValueFor(SettingsHubEntry.MOMENTS, snap, true, "zh-CN", "1.0", { "经常" })!!
        assertEquals(R.string.settings_hub_val_moment_off, moment.textRes)

        val backup = trailingValueFor(
            SettingsHubEntry.BACKUP,
            snap,
            true,
            "zh-CN",
            "1.0",
            { "经常" },
            formattedBackupDate = { "9月28日" },
        )!!
        assertEquals(R.string.settings_hub_val_backup_last, backup.textRes)
        assertEquals(listOf("9月28日"), backup.formatArgs)

        assertNull(trailingValueFor(SettingsHubEntry.ADVANCED, snap, true, "zh-CN", "1.0", { "经常" }))

        val world = trailingValueFor(
            SettingsHubEntry.WORLD,
            snap.copy(worldVividnessTier = AppSettings.WORLD_VIVIDNESS_RICH),
            true,
            "zh-CN",
            "1.0",
            { "经常" },
        )!!
        assertEquals(R.string.world_vividness_rich_name, world.textRes)
    }

    @Test
    fun `行级搜索区分标题命中与关键词命中`() {
        val hitsTitle = filterSettingsHub(
            term = "外观",
            entries = listOf(SettingsHubEntry.AI_SERVICE, SettingsHubEntry.APPEARANCE),
            titleOf = { if (it == SettingsHubEntry.APPEARANCE) "外观" else "AI 服务" },
            keywordsOf = { if (it == SettingsHubEntry.APPEARANCE) listOf("深色", "琉璃") else listOf("API") },
        )
        assertEquals(1, hitsTitle.size)
        assertEquals(SettingsHubEntry.APPEARANCE, hitsTitle.first().entry)
        assertNull(hitsTitle.first().matchedKeyword)

        val hitsKw = filterSettingsHub(
            term = "api",
            entries = listOf(SettingsHubEntry.AI_SERVICE, SettingsHubEntry.APPEARANCE),
            titleOf = { if (it == SettingsHubEntry.APPEARANCE) "外观" else "AI 服务" },
            keywordsOf = { if (it == SettingsHubEntry.APPEARANCE) listOf("深色", "琉璃") else listOf("API", "模型") },
        )
        assertEquals(1, hitsKw.size)
        assertEquals(SettingsHubEntry.AI_SERVICE, hitsKw.first().entry)
        assertEquals("API", hitsKw.first().matchedKeyword)
    }
}
