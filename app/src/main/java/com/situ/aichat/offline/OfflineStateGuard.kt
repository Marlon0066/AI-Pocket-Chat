package com.situ.aichat.offline

import com.situ.aichat.data.model.MessageKind

/** 线下模式状态机一致性的修复动作（1:1 iOS resetOfflineState / orphanSession 两类清理）。 */
enum class OfflineStateRepair {
    /** 状态一致，无需修复。 */
    NONE,

    /** flag=false 但 sessionId 残留 → 只清 sessionId（iOS「orphanSession」）。 */
    CLEAR_SESSION_ID,

    /** flag/marker 异常 → 整体重置（清 flag + sessionId + 散场硬闸，iOS resetOfflineState）。 */
    FULL_RESET,
}

/**
 * 线下模式状态机一致性守护（纯决策，1:1 iOS `ChatViewModel+OfflineStateGuard` + `+ToolCalling` 的恢复判定）。
 *
 * isInOfflineMode + currentOfflineSessionId 两字段耦合，正常流程同事务读写；跨版本升级 / 备份恢复 / 手动调试
 * 可能留下「半状态」。本对象只做**纯判定**——返回 [OfflineStateRepair] / Boolean，落库与弹窗由调用方
 * （10.2c-3c，onAppear/onReappear、冷启动恢复）按结果执行。
 */
object OfflineStateGuard {

    /** 异常恢复提示阈值：最后一条线下消息距今超过此值（毫秒）视为异常中断（1:1 iOS 600s）。 */
    const val RECOVERY_STALE_THRESHOLD_MS = 600_000L

    /**
     * 幂等判定线下脏状态修复（1:1 iOS `ensureOfflineStateConsistency` 的 4 案例）：
     * ① flag=true 但 sessionId 空 → 整体重置；② flag=false 但 sessionId 非空 → 只清 sessionId；
     * ③ flag=true 且 sessionId 有效但无入场标记 → 整体重置；④ 最后一条 session 消息已是离场标记 → 整体重置。
     *
     * @param sessionMessageKinds 当前 session 全部消息的 kind（按时间升序）；仅在 flag=true && sessionId 有效时
     *   用到（案例 ③④），其余情况调用方可传空列表（案例 ①② 先返回，不必查库）。
     */
    fun decide(
        isInOfflineMode: Boolean,
        sessionId: String?,
        sessionMessageKinds: List<MessageKind>,
    ): OfflineStateRepair {
        val trimmed = sessionId?.trim().orEmpty()

        // 案例 ①：flag=true 但 sessionId 空 → 整体重置
        if (isInOfflineMode && trimmed.isEmpty()) return OfflineStateRepair.FULL_RESET
        // 案例 ②：flag=false 但 sessionId 非空 → 只清 sessionId
        if (!isInOfflineMode && trimmed.isNotEmpty()) return OfflineStateRepair.CLEAR_SESSION_ID
        // 仅 flag=true && sessionId 有效时才继续校验 marker 一致性
        if (!isInOfflineMode || trimmed.isEmpty()) return OfflineStateRepair.NONE

        // 案例 ③：找不到入场标记 → 整体重置
        if (sessionMessageKinds.none { it == MessageKind.OFFLINE_MARKER_START }) return OfflineStateRepair.FULL_RESET
        // 案例 ④：最后一条 session 消息已是离场标记 → 上次退出未清 flag → 整体重置
        if (sessionMessageKinds.lastOrNull() == MessageKind.OFFLINE_MARKER_END) return OfflineStateRepair.FULL_RESET

        return OfflineStateRepair.NONE
    }

    /**
     * 是否需要弹「继续 / 结束」异常恢复提示（1:1 iOS `shouldShowOfflineRecoveryPrompt`）。
     * 非线下 / sessionId 空 → false；标记线下但无任何线下消息 → true（异常）；最后线下消息距今 >10min → true。
     *
     * @param lastOfflineMessageAt 当前 session 最后一条线下消息的时间戳（毫秒）；null=无任何线下消息。
     */
    fun shouldShowRecoveryPrompt(
        isInOfflineMode: Boolean,
        sessionId: String?,
        lastOfflineMessageAt: Long?,
        now: Long,
    ): Boolean {
        if (!isInOfflineMode) return false
        if (sessionId?.trim().isNullOrEmpty()) return false
        if (lastOfflineMessageAt == null) return true // 标记线下但无消息 → 异常，需恢复
        return (now - lastOfflineMessageAt) > RECOVERY_STALE_THRESHOLD_MS
    }
}
