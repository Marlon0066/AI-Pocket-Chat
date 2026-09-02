package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.prompt.schedule.schedulePastLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-7（卷一图纸 §7.2）：`schedulePastLine` 只搬不改的纯函数钉——期望从招3 过审规格反推
 * （时段词 + 活动·「 → 」串联·删钟点 / 地点 / 心情），与哨兵 `ScheduleModulePastCompressionTest` 同口径。
 */
class SchedulePastLineTest {

    private fun event(uuid: String, period: String, activity: String, location: String = "咖啡店", mood: String? = "惬意") =
        ScheduleEventEntity(uuid, "s1", 1_000L, 2_000L, period, location, activity, moodText = mood)

    @Test
    fun 两事件_时段词加活动_箭头串联() {
        val line = schedulePastLine(listOf(event("e1", "早上", "晾被单", "家里阳台"), event("e2", "上午", "开店")))
        assertEquals("早上 晾被单 → 上午 开店", line)
    }

    @Test
    fun 时段词空_无前缀无多余空格() {
        val line = schedulePastLine(listOf(event("e1", "", "晾被单"), event("e2", "上午", "开店")))
        assertEquals("晾被单 → 上午 开店", line)
    }

    @Test
    fun 空表返空串_E32() {
        assertEquals("", schedulePastLine(emptyList()))
    }

    @Test
    fun 单事件无箭头_且不含钟点地点心情() {
        val line = schedulePastLine(listOf(event("e1", "早上", "晾被单", "家里阳台", "惬意")))
        assertEquals("早上 晾被单", line)
    }
}
