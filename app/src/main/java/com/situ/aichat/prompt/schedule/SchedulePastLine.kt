package com.situ.aichat.prompt.schedule

import com.situ.aichat.data.local.entity.ScheduleEventEntity

/**
 * 「[✓已发生] 旧戏份压缩」的一行流水账（招3·2026-07-11 过审）——**只搬不改**自 `buildScheduleModule` 的内联 join
 * （Z-14·「我们的日子」总图纸 §0.3 / 卷一图纸 §2.1）：保留时段词、删钟点 / 地点 / 心情，事件间以「 → 」串联。
 * 不含 `[✓已发生]` 标签与 `isNotEmpty` 守卫（红线字面留在 `PromptBuilderSchedule` 原处）；空表返 `""`。
 * 日程模块与「我们的日子」事实层（`OurDayFactsBuilder.scheduleLine`）共用此函数。
 * 哨兵：`ScheduleModulePastCompressionTest` / `PromptBuilderScheduleTest` / `SchedulePastLineTest`（输出字节级不变）。
 */
internal fun schedulePastLine(events: List<ScheduleEventEntity>): String =
    events.joinToString(" → ") { event ->
        val periodPart = if (event.periodLabel.isEmpty()) "" else "${event.periodLabel} "
        "$periodPart${event.activity}"
    }
