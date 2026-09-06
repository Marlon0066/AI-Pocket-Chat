package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.prompt.memory.MemoryService

/**
 * 特殊块（线下见面 / 语音通话）在短期窗口里**保留几条**的唯一算法（图纸 2026-09-06 见面窗口与节拍卡七件 §3.B/F）。
 *
 * 单源的意义：截断侧（[truncateToRecentRounds]）与前情提要协调侧
 * （[com.situ.aichat.prompt.memory.InSceneRecapCoordinator]）必须用**同一个**保留数——两边各算一次，
 * 「原文里还在的」与「提要认为已丢的」就会错位（旧实现截断按有效窗口×4、提要按基准×4，中间一段重复注入）。
 */
internal enum class SpecialBlockKind(val label: String) {
    OFFLINE_MEETING("线下见面"),
    VOICE_CALL("通话");

    companion object {
        /** 与原 PromptBuilderWindow.SpecialBlockType.classify 逐字等价：通话优先于线下。 */
        fun classify(message: MessageEntity): SpecialBlockKind? = when {
            message.isPartOfVoiceCall -> VOICE_CALL
            message.isOfflineMode -> OFFLINE_MEETING
            else -> null
        }
    }
}

/**
 * 特殊块保留策略（图纸 2026-09-06 七件 §3.B/F·D-2）：见面按 CJK 字符预算，通话按条数。
 *
 * 为何两把尺子（J3）：见面里角色回一次 = 4–6 个内容块，通话回一次 = 一句话——同一个「条数」对两者相差十倍。
 */
internal data class SpecialBlockPolicy(val meetingBudgetCjk: Int, val callLimit: Int) {
    companion object {
        /** 见面块字符预算（锁定·D-2）。 */
        const val MEETING_BUDGET_CJK = 20_000

        /** 见面块至少保留的最新条数（锁定·防单条超长饿死窗口）。 */
        const val MEETING_MIN_KEEP = 8

        fun from(settings: AppSettings): SpecialBlockPolicy =
            SpecialBlockPolicy(MEETING_BUDGET_CJK, maxOf(settings.shortTermMemoryLength * 4, 1))
    }
}

/**
 * 从**升序**块的尾部起保留几条（0..size）。VOICE_CALL = min(size, callLimit)。OFFLINE_MEETING = 由新到旧累加
 * [MemoryService.cjkLength]：前 [SpecialBlockPolicy.MEETING_MIN_KEEP] 条无条件保留；之后某条会使累计超过预算即停
 * （该条不保留）。
 */
internal fun retainedSpecialBlockCount(
    ascending: List<MessageEntity>,
    kind: SpecialBlockKind,
    policy: SpecialBlockPolicy,
): Int {
    if (ascending.isEmpty()) return 0
    return when (kind) {
        SpecialBlockKind.VOICE_CALL -> minOf(ascending.size, policy.callLimit)
        SpecialBlockKind.OFFLINE_MEETING -> {
            var kept = 0
            var used = 0
            for (m in ascending.asReversed()) {
                val len = MemoryService.cjkLength(m.content)
                if (kept >= SpecialBlockPolicy.MEETING_MIN_KEEP && used + len > policy.meetingBudgetCjk) break
                kept += 1
                used += len
            }
            kept
        }
    }
}
