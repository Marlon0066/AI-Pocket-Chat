package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.util.DateFormatters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 关系里程碑段（`## 你和{userName}的关系`）渲染 —— [buildCharacterGrowthContent] 第 4 段的实现。
 *
 * 由图纸《2026-09-03 关系历程注入根治》从 `PromptBuilderGrowth.kt` 抽出（C1 只搬不改）后就地改造（C2）：
 * ① 当前关系起点改取「当前名分**连续同名段的最早一条**」——旧实现取 `milestones.last()`，
 *    时期变化（恋人·蜜月期 → 恋人·倦怠期）会把起始日顶成最近一次变化日，是事实错误；
 * ② 历程连续同名去重**先于** `takeLast(10)`——旧实现先取后不去重，10 个名额可能被同一名字占满；
 * ③ 「AI 判断：」前缀删除、`triggerTypeRaw` 不再参与渲染（数据溯源元信息，资料页已用圆点颜色表达）；
 * ④ 理由分层：去重后最近 [REASON_TAIL_COUNT] 条带理由，更早的只出关系名；
 * ⑤ 补「在一起 N 天」与节奏句——模型算不准日期算术，代码只补这一类它做不好的事。
 *
 * **不设闸**（图纸 §4-C·用户 2026-09-03 裁决）：注入端不做任何内容校验 / 长度拒绝 / 禁词过滤，
 * `reason` 有什么就渲染什么，唯一省略条件是 `reason.isEmpty()`。文体由分析师提示词（图纸件 5）在源头管。
 */

private val milestoneDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneId.systemDefault())

/** 历程注入条数上限：**去重后**取最近 10 条（图纸 §7 锁定）。 */
private const val MAX_MILESTONES = 10

/** 带理由的条数：只有去重后最近 3 条带理由（图纸 §3 件 2·人对久远转折只记得「那时候成了朋友」）。 */
private const val REASON_TAIL_COUNT = 3

/** 节奏句阈值（图纸 §3 件 4·§7 锁定）：`1..3` 快、`4..180` 平、`>180` 慢。 */
private const val PACE_FAST_MAX_DAYS = 3
private const val PACE_NORMAL_MAX_DAYS = 180

/**
 * 将关系里程碑列表（升序，= iOS `sortedMilestones`）转为提示词。
 *
 * [firstMessageDate] = `CharacterEntity.firstMessageDate`（第一次聊天，与相识行同源）：只用于节奏句的
 * 基准 `D`，为 null 时节奏句整行缺席（与相识行本身的缺席规则一致，见 `PromptBuilderContent`）。
 */
internal fun buildRelationshipMilestoneDescription(
    milestones: List<MilestoneEntity>,
    userName: String,
    nowMillis: Long,
    firstMessageDate: Long?,
): String {
    if (milestones.isEmpty()) return ""

    val deduped = dedupeConsecutiveSameName(milestones)
    // 去重保留每段最早一条 ⇒ 末元素即「当前名分这一段的起点」（分手复合会取复合那次，不会误取分手前那次）。
    val current = deduped.last()
    val currentDateStr = milestoneDateFormatter.format(Instant.ofEpochMilli(current.establishedDate))
    val togetherDays = TimeAnchorFormatter.calendarDayDifference(
        Instant.ofEpochMilli(current.establishedDate),
        Instant.ofEpochMilli(nowMillis),
    )

    val parts = mutableListOf<String>()
    parts.add("## 你和${userName}的关系")
    parts.add(
        if (togetherDays < 1) {
            // 当天确立（0）与时钟回拨（负）：「在一起 N 天了」半句省略。
            "你们现在是「${current.relationshipName}」——${currentDateStr}确立。"
        } else {
            "你们现在是「${current.relationshipName}」——从 $currentDateStr 算起，在一起 $togetherDays 天了。"
        },
    )

    val recent = deduped.takeLast(MAX_MILESTONES)
    parts.add("你们一路是这么走过来的：")
    val reasonFromIndex = recent.size - REASON_TAIL_COUNT
    for ((index, milestone) in recent.withIndex()) {
        val dateStr = milestoneDateFormatter.format(Instant.ofEpochMilli(milestone.establishedDate))
        val relative = DateFormatters.relativeDay(milestone.establishedDate, nowMillis)
        val head = if (relative.isEmpty()) "- ${dateStr}：" else "- $dateStr · $relative："
        // 同一自然日的多条：理由只保留最后一条的（关系名行照常各出各的），免得同一天几句理由堆在一起。
        val hasSameDayLater = index < recent.lastIndex && isSameLocalDay(
            milestone.establishedDate,
            recent[index + 1].establishedDate,
        )
        val reason = if (index >= reasonFromIndex && !hasSameDayLater) milestone.reason else ""
        parts.add(
            if (reason.isEmpty()) "$head${milestone.relationshipName}" else "$head${milestone.relationshipName}（${reason}）",
        )
    }

    val paceLine = relationshipPaceLine(firstMessageDate, current.establishedDate)
    if (paceLine.isNotEmpty()) parts.add(paceLine)

    parts.add("请根据当前的关系状态来调整你的语气、称呼和互动方式。关系是动态变化的，可能升级也可能降级。")
    return parts.joinToString("\n")
}

/** 连续同名去重：每段只保留**最早**一条（= 这个名分真正确立的那天）。非连续的同名各段各留（分手复合）。 */
internal fun dedupeConsecutiveSameName(milestones: List<MilestoneEntity>): List<MilestoneEntity> =
    milestones.filterIndexed { index, m -> index == 0 || milestones[index - 1].relationshipName != m.relationshipName }

/**
 * 节奏句（图纸 §3 件 4）：`D` = 从第一次聊天到当前名分确立隔了几天。
 * [firstMessageDate] 为 null 或 `D <= 0`（同日确立 / 时钟回拨）→ 整行缺席（返回 `""`）。
 */
internal fun relationshipPaceLine(firstMessageDate: Long?, segmentStartMillis: Long): String {
    if (firstMessageDate == null) return ""
    val days = TimeAnchorFormatter.calendarDayDifference(
        Instant.ofEpochMilli(firstMessageDate),
        Instant.ofEpochMilli(segmentStartMillis),
    )
    return when {
        days <= 0 -> ""
        days <= PACE_FAST_MAX_DAYS -> "你们认识 $days 天后就成了现在这个关系，快得几乎没有过渡。"
        days <= PACE_NORMAL_MAX_DAYS -> "你们认识 $days 天后成了现在这个关系。"
        else -> "你们认识 $days 天后才成了现在这个关系，这一步你们走了很久。"
    }
}

/** 两个时刻是否落在同一自然日（复用相识行同一支日历日差函数，不自己写日期减法）。 */
private fun isSameLocalDay(aMillis: Long, bMillis: Long): Boolean =
    TimeAnchorFormatter.calendarDayDifference(Instant.ofEpochMilli(aMillis), Instant.ofEpochMilli(bMillis)) == 0
