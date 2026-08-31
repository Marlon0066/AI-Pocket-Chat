package com.situ.aichat.prompt.schedule

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.EconomicStatusTier
import com.situ.aichat.data.model.dynamicInterests
import com.situ.aichat.data.model.moodHistory
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.prompt.memory.MemorySummarySections
import com.situ.aichat.util.takeCodePoints
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 日程生成提示词的活人感段落渲染（图纸 2026-07-10 日程专项 C4/C6）。全部纯函数：输入角色/素材包，
 * 输出提示词行；素材缺失一律返回 null / emptyList = 该段缺席（老角色零数据 = 提示词与加料前一致）。
 * **只加输入**——本文件绝不触碰输出 JSON 格式指令；文案逐字锁定于图纸 §4，改动须回图纸过审。
 */
internal object ScheduleLivenessPromptSections {

    // ── B. 兴趣升级行（含 backfill·图纸 §4-B）─────────────────────────────

    /** 动态兴趣行：heat 降序 take 5；无成长数据返回 null。 */
    fun interestsLine(character: CharacterEntity): String? {
        val interests = character.dynamicInterests
        if (interests.isEmpty()) return null
        val names = interests.sortedByDescending { it.heat }.take(5).map { it.name }
        return "最近热衷：${names.joinToString("、")}（按热衷程度排序，日程优先体现这些）"
    }

    // ── C. 心情走向行（!backfill·图纸 §4-C）──────────────────────────────

    /** 心情走向：按 timestamp 倒序取最近 5 条（KDoc 明令不可直接取列表前 N），<3 条返回 null。 */
    fun moodTrendLine(character: CharacterEntity): String? {
        val recent = character.moodHistory.sortedByDescending { it.timestamp }.take(5)
        if (recent.size < 3) return null
        val colors = recent.groupingBy { it.colorName }.eachCount()
        val trend = when {
            (colors["red"] ?: 0) >= 3 -> "持续低落"
            (colors["yellow"] ?: 0) >= 3 -> "有些起伏"
            (colors["green"] ?: 0) >= 3 -> "不错"
            else -> "平稳"
        }
        return "最近心情走向：$trend"
    }

    // ── D. 关系块（!backfill·图纸 §4-D）─────────────────────────────────

    /** 关系档位（锁定公式：(熟悉+信任+亲近+依恋)/4 整除·边界 25/50/75）。 */
    fun relationshipTier(character: CharacterEntity): String {
        val q = character.relationshipQuality
        val score = (q.familiarity + q.trust + q.closeness + q.attachment) / 4
        return when {
            score < 25 -> "新识"
            score < 50 -> "熟络"
            score < 75 -> "亲密"
            else -> "深厚"
        }
    }

    /** 【和用户的关系】块；从没聊过（firstMessageDate 空）整块缺席。 */
    fun relationshipSection(character: CharacterEntity, date: LocalDate, zone: ZoneId, userName: String = "用户"): List<String> {
        val firstMillis = character.firstMessageDate ?: return emptyList()
        val firstDate = Instant.ofEpochMilli(firstMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(firstDate, date).coerceAtLeast(0)
        val tier = relationshipTier(character)
        val tierGuide = when (tier) {
            "新识" -> "最多 1 个，且要克制含蓄"
            "熟络" -> "1–2 个"
            "亲密" -> "2–3 个，自然流露"
            else -> "2–3 个，自然流露，但TA依然有自己的生活重心"
        }
        val streakClause = if (character.streakCount >= 2) "、最近连续聊了 ${character.streakCount} 天" else ""
        return listOf(
            "【和${userName}的关系】",
            "相识 $days 天$streakClause，关系阶段：$tier",
            "在今天的 innerThought 里，想到${userName}的事件数参考：$tierGuide",
        )
    }

    // ── E. 长期记忆块（!backfill·图纸 §4-E）─────────────────────────────

    /** 【TA的长期记忆】块：整行累加预算 300 字（加入即超则停；首行独超取前 300 字）；无【长期事实】节缺席。 */
    fun longTermMemorySection(character: CharacterEntity, userName: String = "用户"): List<String> {
        val facts = MemorySummarySections.parse(character.memorySummary).longTermFacts
        if (facts.isEmpty()) return emptyList()
        val picked = mutableListOf<String>()
        var budget = 0
        for (line in facts) {
            if (budget + line.length > LONG_TERM_BUDGET_CHARS) break
            picked.add(line)
            budget += line.length
        }
        if (picked.isEmpty()) picked.add(facts.first().takeCodePoints(LONG_TERM_BUDGET_CHARS))
        return buildList {
            add("【TA的长期记忆】")
            addAll(picked)
            add(
                "其中关于TA自己生活的事实（习惯、宠物、在学的东西等）应自然体现在日程里；" +
                    "关于${userName}的事实只能在 innerThought 里出现，不得作为 activity。",
            )
        }
    }

    /** 长期事实行预算（图纸 §9-②）。 */
    const val LONG_TERM_BUDGET_CHARS = 300

    // ── G. 多日摘要块（图纸 §4-G·C6）─────────────────────────────────────

    /** 【最近几天做过什么】：反撞车 + 跨日小事件线 + 允许平淡。摘要空则缺席。 */
    fun recentDaysSection(digest: List<String>): List<String> {
        if (digest.isEmpty()) return emptyList()
        return buildList {
            add("【最近几天做过什么】")
            addAll(digest)
            add(
                "日常活动（上班、吃饭、做家务）可以每天重复；特殊活动（看展、聚会、远足、看电影等）" +
                    "这几天做过的，今天不要再排。若最近几天有未完结的小事（网购待收货、感冒、备考、准备礼物），" +
                    "今天可以自然延续它。多数日子允许平淡——真实的生活不是每天都有大事。",
            )
        }
    }

    // ── H. 今天的约定（硬锚点）+ 近期已定（图纸 §4-H·C6）──────────────────

    /** 【今天的约定】：见面约定行 + 账本约定行；两者皆空整块缺席。 */
    fun todayPromisesSection(liveness: ScheduleLivenessContext, userName: String = "用户"): List<String> {
        if (liveness.todayMeetings.isEmpty() && liveness.todayPromises.isEmpty()) return emptyList()
        return buildList {
            add("【今天的约定】（这是TA今天必须兑现的真实约定）")
            for (m in liveness.todayMeetings) {
                val place = m.location.takeIf { it.isNotBlank() }?.let { "，地点：$it" } ?: ""
                val doing = m.activity.takeIf { it.isNotBlank() }?.let { "，一起$it" } ?: ""
                add("- 今天${m.timeText}和${userName}见面$place$doing")
            }
            for (content in liveness.todayPromises) add("- $content")
            add(
                "要求：为每条约定安排对应的日程事件，并预留合理的准备时间（收拾、出门、在路上）。" +
                    "约定事件的 activity 可以如实写赴约内容（例如「去美术馆看展（和${userName}约好的）」）" +
                    "——这是「activity 不写与${userName}互动」规则的唯一例外，因为它来自真实的约定账本。" +
                    "除约定本身外，仍不得虚构任何互动细节或对话。同一件事若出现两条，只安排一次。",
            )
        }
    }

    /** 【近期已定的约定】背景组：只准期待、不准提前排。空则缺席。 */
    fun upcomingPromisesSection(liveness: ScheduleLivenessContext): List<String> {
        if (liveness.upcomingPromises.isEmpty()) return emptyList()
        return buildList {
            add("【近期已定的约定】（还没到日子，今天不要安排）")
            for (p in liveness.upcomingPromises) add("- ${p.content}（${p.dueDateText}）")
            add("TA心里记着这些约定，innerThought 里可以自然期待，但绝不能提前排进今天的日程。")
        }
    }

    // ── H2. 惦记块（图纸 §4-H2·C6）───────────────────────────────────────

    /** 【TA心里惦记的事】：只进 innerThought，user_event 绝不排日程。空则缺席。 */
    fun openLoopsSection(loops: List<String>, userName: String = "用户"): List<String> {
        if (loops.isEmpty()) return emptyList()
        return buildList {
            add("【TA心里惦记的事】")
            for (content in loops) add("- $content")
            add(
                "这些只能出现在 innerThought 里（比如「不知道她面试结果怎么样」），绝不能变成日程事件；" +
                    "属于${userName}自己的事（考试、体检、出差）更不能排进TA的日程。不必每条都用，自然浮现一两处即可。",
            )
        }
    }

    // ── H3. 余温块（图纸 §4-H3·C6）───────────────────────────────────────

    /** 【最近见面】余温一行；无 48h 内见面则缺席。 */
    fun afterglowSection(afterglow: ScheduleLivenessContext.AfterglowLine?): List<String> {
        if (afterglow == null) return emptyList()
        val doing = afterglow.activity.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
        val place = afterglow.location.takeIf { it.isNotBlank() }?.let { "（在$it）" } ?: ""
        return listOf(
            "【最近见面】",
            "${afterglow.dayWord}你们线下见过面$doing$place。今天的日程和 innerThought 可以自然带一点" +
                "见面后的余温（回味、心情偏暖），不强制、不重演见面内容。",
        )
    }

    // ── H4. 经济块（图纸 §4-H4·C6·含 backfill）──────────────────────────

    /** 【TA的经济状况】：档位标签 + 既有引导文案原文（EconomicStatusTier 零改）；tier null 缺席。 */
    fun economicSection(tier: EconomicStatusTier?): List<String> {
        if (tier == null) return emptyList()
        return listOf("【TA的经济状况】：${tier.promptLabel}", tier.promptGuidance)
    }
}
