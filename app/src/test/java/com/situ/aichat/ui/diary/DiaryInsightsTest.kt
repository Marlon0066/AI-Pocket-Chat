package com.situ.aichat.ui.diary

import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * T1：回顾与统计纯计算（R5·契约 §2 F4）。口径从锁定规格独立反推：**发布才算**（O4）、
 * 排草稿/宠物/交换信、断档归零、今天没写火苗不灭（从昨天回数）。
 */
class DiaryInsightsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 7, 3)

    private fun entry(
        date: LocalDate,
        draft: Boolean = false,
        pet: Boolean = false,
        author: String? = null,
        mood: String? = null,
        content: String = "abc",
    ) = DiaryEntryWithComments(
        entry = DiaryEntryEntity(
            uuid = "$date-$draft-$pet-$author-$mood",
            content = content,
            timestamp = date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli(),
            isDraft = draft,
            isPetDiary = pet,
            authorCharacterUuid = author,
            moodEmoji = mood,
        ),
        comments = emptyList(),
    )

    @Test fun `streak counts back from today when today is published`() {
        val entries = listOf(entry(today), entry(today.minusDays(1)), entry(today.minusDays(2)))
        assertEquals(3, DiaryInsights.streakDays(entries, today, zone))
    }

    @Test fun `streak survives an unwritten today by counting from yesterday`() {
        val entries = listOf(entry(today.minusDays(1)), entry(today.minusDays(2)))
        assertEquals(2, DiaryInsights.streakDays(entries, today, zone))
    }

    @Test fun `streak breaks on a gap and is zero when nothing recent`() {
        // 前天有、昨天断 → 0（今天也没写）。
        assertEquals(0, DiaryInsights.streakDays(listOf(entry(today.minusDays(2))), today, zone))
        assertEquals(0, DiaryInsights.streakDays(emptyList(), today, zone))
    }

    @Test fun `streak counts only published user diaries`() {
        val entries = listOf(
            entry(today, draft = true),                 // 草稿不算（O4）
            entry(today.minusDays(1), pet = true),      // 宠物日记不算
            entry(today.minusDays(1), author = "c1"),   // TA 的信不算
            entry(today.minusDays(1)),                  // 只有这条算
        )
        assertEquals(1, DiaryInsights.streakDays(entries, today, zone))
    }

    @Test fun `same day multiple entries count once`() {
        val entries = listOf(entry(today), entry(today, mood = "😊"), entry(today.minusDays(1)))
        assertEquals(2, DiaryInsights.streakDays(entries, today, zone))
    }

    @Test fun `onThisDay picks prior years same month-day, newest first, filtered`() {
        val lastYear = today.minusYears(1)
        val twoYears = today.minusYears(2)
        val entries = listOf(
            entry(today),                       // 今年不算
            entry(lastYear),
            entry(twoYears),
            entry(lastYear, draft = true),      // 草稿不算
            entry(lastYear.minusDays(1)),       // 日期不同不算
            entry(lastYear, author = "c1"),     // TA 的信不算
        )
        val hits = DiaryInsights.onThisDay(entries, today, zone)
        assertEquals(2, hits.size)
        assertEquals(lastYear.year, 2025)
        assertEquals(
            listOf(lastYear, twoYears),
            hits.map { java.time.Instant.ofEpochMilli(it.entry.timestamp).atZone(zone).toLocalDate() },
        )
    }

    @Test fun `stats aggregate counts chars and mood frequencies`() {
        val entries = listOf(
            entry(today, mood = "😊", content = "十二345"),
            entry(today.minusDays(1), mood = "😊", content = "ab"),
            entry(today.minusDays(2), mood = "😌", content = "c"),
            entry(today.minusDays(3), draft = true, content = "ignored"),
        )
        val stats = DiaryInsights.stats(entries, today, zone)
        assertEquals(3, stats.publishedCount)
        assertEquals(8, stats.totalChars)
        assertEquals(listOf("😊" to 2, "😌" to 1), stats.moodCounts)
        assertEquals(3, stats.streakDays)
    }

    @Test fun `recentDayMoods maps 30 day window excluding drafts pets and letters`() {
        val entries = listOf(
            entry(today, mood = "😊"),
            entry(today.minusDays(1), mood = null),            // 已发布无心情 → ""
            entry(today.minusDays(2), draft = true, mood = "😢"), // 草稿不计入
            entry(today.minusDays(3), pet = true, mood = "🐾"),   // 宠物不计入
            entry(today.minusDays(4), author = "c1", mood = "💌"), // 来信不计入
            entry(today.minusDays(29), mood = "😌"),              // 窗口第一天（index 0）
            entry(today.minusDays(30), mood = "🥰"),              // 窗口外
        )
        val moods = DiaryInsights.stats(entries, today, zone).recentDayMoods
        assertEquals(30, moods.size)
        assertEquals("😌", moods[0])
        assertEquals(null, moods[25]) // today - 4 (letter excluded)
        assertEquals(null, moods[26]) // today - 3 (pet excluded)
        assertEquals(null, moods[27]) // today - 2 (draft excluded)
        assertEquals("", moods[28])   // today - 1 (published without mood)
        assertEquals("😊", moods[29]) // today
    }
}
