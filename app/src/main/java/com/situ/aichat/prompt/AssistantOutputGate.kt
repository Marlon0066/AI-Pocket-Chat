package com.situ.aichat.prompt

import android.util.Log
import com.situ.aichat.data.model.MessageKind

/**
 * assistant 输出落库前置闸（2026-09-01 图纸「记忆与防污染加固批」件①·单源）。
 *
 * 为什么要有它：判脏原本只在**显示层**做——脏内容照旧落库，于是它会继续喂给下一轮提示词、进摘要素材、
 * 进向量库、进通知预览，污染面远大于「用户看见一条怪消息」。本闸把防线前移到**落库前**：判脏即丢弃，
 * 那一段从不存在。
 *
 * 判定复用 [DirtyMessageDetector]（规则零增改）；[kind] 必须与调用方将落库的值同口径——否则同一段文本
 * 在闸门与落库两侧被判成不同类型，闸门形同虚设。
 * 日志只记来源/reason/长度，绝不记内容（LOGGING_AUDIT 规矩）。
 *
 * 职责边界：只判不删库、不重试、不发通知。整轮被丢空后的重试职责恒在 [com.situ.aichat.ui.chat.AssistantTurnEngine]
 * 的既有空回合循环（丢空 = 返回空表 = 天然接入那条链）。
 */
object AssistantOutputGate {
    private const val TAG = "AssistantOutputGate"

    /** true = 判脏，调用方丢弃不落库。 */
    fun shouldDiscard(content: String, kind: MessageKind, source: String): Boolean {
        val reason = DirtyMessageDetector.detect(content, kind) ?: return false
        Log.w(TAG, "拦截脏输出 source=$source reason=${reason.raw} len=${content.length}")
        return true
    }

    /**
     * 文字分条路：按落库同口径逐段推断 kind
     * （含 `[#E1]`→[MessageKind.SCHEDULE_CARD] 免检旁路·有意保留：与检测器「宁可漏判」哲学一致，
     * 强行按 PLAIN_TEXT 全检会误杀含日程回声的正当日程卡段）。
     */
    fun filterSegments(segments: List<String>, isOfflineMode: Boolean, source: String): List<String> =
        segments.filterNot { seg ->
            shouldDiscard(seg, MessageKindInference.forAssistantText(seg, isOfflineMode), source)
        }

    /** 语音条/单条短文本路（落库 kind 恒 [MessageKind.PLAIN_TEXT] 的调用方用）。 */
    fun filterPlainChunks(chunks: List<String>, source: String): List<String> =
        chunks.filterNot { shouldDiscard(it, MessageKind.PLAIN_TEXT, source) }
}
