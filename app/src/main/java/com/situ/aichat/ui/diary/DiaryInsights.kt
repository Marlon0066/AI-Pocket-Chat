package com.situ.aichat.ui.diary

import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日记回顾与统计的纯计算（R5·契约 §2 F4）。零 LLM、零 IO——全部从已加载的 entries 推导，
 * VM 在 map 里调用，T1 独立看门。口径：**发布才算**（O4 锁定）·用户日记（排 TA 的信与宠物日记）。
 */
internal object DiaryInsights {

    /** 一份轻统计：连续天数 + 已发布篇数 + 总字数 + 心情计数（emoji → 次数·降序）+ 近 30 天每日心情色块。 */
    data class Stats(
        val streakDays: Int,
        val publishedCount: Int,
        val totalChars: Int,
        val moodCounts: List<Pair<String, Int>>,
        /** 近 30 天（[today - 29 .. today]）每日代表心情 emoji，无记录或无心情为 null。 */
        val recentDayMoods: List<String?> = emptyList(),
    )

    /** 用户已发布日记（排草稿/宠物/交换信·streak 与统计共用的口径闸）。 */
    private fun published(entries: List<DiaryEntryWithComments>) =
        entries.asSequence()
            .map { it.entry }
            .filter { !it.isDraft && !it.isPetDiary && it.authorCharacterUuid == null }

    /**
     * 连续记录天数（O4：发布才算）。今天已发布 → 从今天往回数；今天还没写 → 从昨天往回数
     * （火苗未灭·今天还来得及）；昨天也没有 → 0。
     */
    fun streakDays(
        entries: List<DiaryEntryWithComments>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val days = published(entries)
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .toHashSet()
        var cursor = if (today in days) today else today.minusDays(1)
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 那年今天：往年同月同日的已发布用户日记（新→旧）。 */
    fun onThisDay(
        entries: List<DiaryEntryWithComments>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DiaryEntryWithComments> =
        entries.filter { ewc ->
            val e = ewc.entry
            if (e.isDraft || e.isPetDiary || e.authorCharacterUuid != null) return@filter false
            val day = Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()
            day.year < today.year && day.monthValue == today.monthValue && day.dayOfMonth == today.dayOfMonth
        }.sortedByDescending { it.entry.timestamp }

    /** 轻统计（统计面板用）。 */
    fun stats(
        entries: List<DiaryEntryWithComments>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Stats {
        val pub = published(entries).toList()
        val moods = pub.mapNotNull { it.moodEmoji?.takeIf(String::isNotEmpty) }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }
        // 近 30 天每日最新带心情的已发布日记（或空串标记“有写但未选心情”，null 标记“当日无发布”）。
        val byDay = pub.sortedByDescending { it.timestamp }
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        val recent = (29 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val list = byDay[day] ?: return@map null
            list.firstNotNullOfOrNull { it.moodEmoji?.takeIf(String::isNotEmpty) } ?: ""
        }
        return Stats(
            streakDays = streakDays(entries, today, zone),
            publishedCount = pub.size,
            totalChars = pub.sumOf { it.content.length },
            moodCounts = moods,
            recentDayMoods = recent,
        )
    }
}
