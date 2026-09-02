package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.prompt.PromptBuilder

/** 她最近自己说的状态（D2 三源之一·权重最高）。 */
internal enum class SelfReport { AWAKE, SLEEPY, AVAILABLE, NONE }

/** 日程给的输入信号（D2 三源之二·中权重）；只在在线主路「情况 1 有进行中事件」时非 NONE。 */
internal enum class ScheduleSignal { SLEEP, PHONE_UNAVAILABLE, NONE }

/** 裁决结论（§4.3 表）；文案映射在 `PromptBuilderSchedule`（[NONE] ⇒ 不加行）。 */
internal enum class AttentionVerdict { SLEEP_OLD, DISTRACTED_OLD, AWAKE_OVERRIDE, AVAILABLE_OVERRIDE, NONE }

/**
 * 睡眠 / 分心移交（活人感内核卷三 §4.3 · 总图纸 §4.3 D2）：日程不再直接下 ⚠️ 指令，改由这里综合
 * 「她刚说的 › 日程 › 精力」三源判定——精力只当「刚说的」要推翻日程时的低权重闸（`arousal ≥ 20`·K-10），
 * 单靠精力永不新增 ⚠️ 行（E32）。**纯逻辑、零状态**；只用于在线主路（线下版零改动·N-5）。
 */
internal object AttentionJudge {

    private val AWAKE_WORDS = listOf("睡不着", "没睡", "还没睡", "不困", "醒了", "失眠", "还醒着", "睡不着觉")
    private val SLEEPY_WORDS = listOf("困了", "好困", "想睡", "要睡了", "去睡了", "睡了", "晚安", "睡觉了", "眼皮")
    private val AVAILABLE_WORDS = listOf("有空", "不忙", "忙完了", "开完会", "下班了", "闲着", "现在能聊")

    /**
     * 自述取材（锁定）：[messages] 升序；找倒数第 [RelationshipBands.SELF_REPORT_ROUNDS] 条 user 的下标（不足 ⇒ 从 0）；
     * 取其后全部 `assistant && !isOfflineMode && timestamp ≥ now − 3h` 的 content，升序返回。
     * 按轮切、不按条数 ⇒ 用户把角色回复条数设 1 或 6 都不影响（E33）。
     */
    fun recentCharacterLines(messages: List<MessageEntity>, nowMillis: Long): List<String> {
        val userIndices = messages.indices.filter { messages[it].roleRaw == ROLE_USER }
        val rounds = RelationshipBands.SELF_REPORT_ROUNDS
        val start = if (userIndices.size >= rounds) userIndices[userIndices.size - rounds] else 0
        val cutoff = nowMillis - RelationshipBands.SELF_REPORT_TTL_MS
        return messages.subList(start, messages.size)
            .filter { it.roleRaw == PromptBuilder.ROLE_ASSISTANT && !it.isOfflineMode && it.timestamp >= cutoff }
            .map { it.content }
    }

    /**
     * 关键词自述判定（锁定）：从最新往旧扫，首个命中即定；同一句内判定序 `AWAKE → SLEEPY → AVAILABLE`
     * （「不困」含「困」，故先判 AWAKE）；全无 ⇒ [SelfReport.NONE]。
     * 修缮卷 F21：疑问句守卫——关键词紧跟「吗 / 没 / 呢 / ? / ？」的是在问对方（「你睡了吗」），不算自述（E39）。
     */
    fun selfReport(lines: List<String>): SelfReport {
        for (line in lines.asReversed()) {
            when {
                AWAKE_WORDS.any { containsStatement(line, it) } -> return SelfReport.AWAKE
                SLEEPY_WORDS.any { containsStatement(line, it) } -> return SelfReport.SLEEPY
                AVAILABLE_WORDS.any { containsStatement(line, it) } -> return SelfReport.AVAILABLE
            }
        }
        return SelfReport.NONE
    }

    private val QUESTION_TAILS = setOf('吗', '没', '呢', '?', '？')

    /** [word] 在 [line] 里的某次出现**不**紧跟疑问尾字 ⇒ 陈述命中；每次出现都要查（「睡了吗…我睡了」后一次算）。 */
    private fun containsStatement(line: String, word: String): Boolean {
        var i = line.indexOf(word)
        while (i >= 0) {
            if (line.getOrNull(i + word.length) !in QUESTION_TAILS) return true
            i = line.indexOf(word, i + 1)
        }
        return false
    }

    /**
     * 裁决表（§4.3 锁定）：
     * | schedule | selfReport | 精力闸 | 结论 |
     * | SLEEP | AWAKE | `arousal ≥ 20` | AWAKE_OVERRIDE |
     * | SLEEP | AWAKE | `< 20` | SLEEP_OLD（退化保护·E14）|
     * | SLEEP | 其它 | — | SLEEP_OLD |
     * | PHONE_UNAVAILABLE | AVAILABLE | `arousal ≥ 20` | AVAILABLE_OVERRIDE |
     * | PHONE_UNAVAILABLE | 其它 | — | DISTRACTED_OLD |
     * | NONE | SLEEPY | `hour ∈ 22..23 ∪ 0..6` | SLEEP_OLD |
     * | NONE | 其它 | — | NONE |
     */
    fun judge(selfReport: SelfReport, schedule: ScheduleSignal, arousal: Int, hour: Int): AttentionVerdict {
        val awakeEnough = arousal >= RelationshipBands.AROUSAL_AWAKE_MIN
        return when (schedule) {
            ScheduleSignal.SLEEP ->
                if (selfReport == SelfReport.AWAKE && awakeEnough) AttentionVerdict.AWAKE_OVERRIDE else AttentionVerdict.SLEEP_OLD
            ScheduleSignal.PHONE_UNAVAILABLE ->
                if (selfReport == SelfReport.AVAILABLE && awakeEnough) AttentionVerdict.AVAILABLE_OVERRIDE else AttentionVerdict.DISTRACTED_OLD
            ScheduleSignal.NONE ->
                if (selfReport == SelfReport.SLEEPY && (hour >= 22 || hour <= 6)) AttentionVerdict.SLEEP_OLD else AttentionVerdict.NONE
        }
    }
}
