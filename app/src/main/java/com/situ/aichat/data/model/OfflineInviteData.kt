package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 线下见面 邀约/结束卡片的结构化数据（1:1 iOS `OfflineInviteData`，存在 `MessageEntity.content` 的 JSON）。
 * [MessageKind.OFFLINE_INVITE_CARD] / [MessageKind.OFFLINE_END_CARD] 的关联数据。
 *
 * 字段名 1:1 iOS（camelCase：tensionHint/hiddenTension/finalMood），与 iOS 持久化卡片字节兼容
 * （iOS 该 struct 用默认 Codable 键 = camelCase；LLM 工具输出的 snake_case 是另一条解析路径，10.2c 处理）。
 *
 * @param type 判别："offline_invite" / "offline_end"（解析时校验）。
 * @param tensionHint 邀约卡显示的隐晦暗示（≤12 字，不剧透；前端可见）。
 * @param hiddenTension 完整心事种子（给 LLM 常驻指令，前端不显示；进入时写入 markerStart 消息）。
 * @param farewell 已废弃，仅兼容老数据（新路径告别走内容块）。
 * @param finalMood 结束情绪基调：warm/sweet/melancholic/awkward/neutral。
 * @param responded 用户响应："accepted"/"declined"/"continued"（再待一会儿）/null（未响应）。
 */
@Serializable
data class OfflineInviteData(
    val type: String,
    val location: String? = null,
    val activity: String? = null,
    val invitation: String? = null,
    val tensionHint: String? = null,
    val hiddenTension: String? = null,
    val farewell: String? = null,
    val finalMood: String? = null,
    val responded: String? = null,
) {
    /**
     * 给 LLM 的脱敏表示（留痕改造 2026-08-31·仿 [FutureMeetingProposalData.llmRepresentation]）：
     * 只露 地点/活动 + 实时 responded 状态；invitation 台词、tensionHint、hiddenTension、原始 JSON 一律不进 LLM。
     * 非邀约型（offline_end）→ null（调用方整条跳过）。
     *
     * ⚠️ 措辞强耦合（三处互指·改任一侧必须同步 + 过锁测试）：
     * ① 绝不含「邀约卡片」四字连写——[com.situ.aichat.offline.OfflineMeetingAction] sysRecordInviteRegex
     *    会把含该字样的复读解析成新卡（毒循环）；
     * ② 以「[系统记录：」开头且含「线下见面邀约」——[com.situ.aichat.prompt.DirtyMessageDetector]
     *    matchesSystemRecordLabel 靠这两段识别 AI 复读并折叠。
     *
     * 状态字面量 accepted/declined 与 [com.situ.aichat.ui.chat.ChatOfflineController] 的写入侧同字面（现状散点·单源化未立项）。
     */
    fun llmRepresentation(userName: String = "用户"): String? {
        if (type != OfflineInviteJson.TYPE_INVITE) return null
        val parts = listOfNotNull(
            location?.takeIf { it.isNotBlank() }?.let { "地点=$it" },
            activity?.takeIf { it.isNotBlank() }?.let { "活动=$it" },
        )
        val detail = if (parts.isEmpty()) "" else " | " + parts.joinToString(" | ")
        val status = when (responded) {
            "accepted" -> "对方接受了，你们随后见了面"
            "declined" -> "对方婉拒了，这次没见成"
            else -> "对方还没回应" // null / "continued" / 未知值一律按未回应
        }
        return "[系统记录：你向${userName}发出了线下见面邀约$detail | 状态=$status]"
    }
}

/** 线下邀约/结束卡 JSON 编解码（1:1 iOS `parseOfflineInvite`/`makeOfflineInviteContent`；encodeDefaults=false 省略 null）。 */
object OfflineInviteJson {
    const val TYPE_INVITE = "offline_invite"
    const val TYPE_END = "offline_end"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(data: OfflineInviteData): String = json.encodeToString(OfflineInviteData.serializer(), data)

    /**
     * 解析消息内容为邀约/结束卡（1:1 iOS `parseOfflineInvite`）：
     * - 以 "{" 开头（排除 plainText）；type ∈ {offline_invite, offline_end}；失败 → null。
     */
    fun parse(content: String): OfflineInviteData? {
        if (!content.startsWith("{")) return null
        val data = runCatching { json.decodeFromString<OfflineInviteData>(content) }.getOrNull() ?: return null
        return if (data.type == TYPE_INVITE || data.type == TYPE_END) data else null
    }

    /** 邀约卡 JSON（= iOS `makeOfflineInviteContent`）。 */
    fun makeInvite(
        location: String,
        activity: String,
        invitation: String,
        tensionHint: String? = null,
        hiddenTension: String? = null,
    ): String = encode(
        OfflineInviteData(
            type = TYPE_INVITE,
            location = location,
            activity = activity,
            invitation = invitation,
            tensionHint = tensionHint?.takeIf { it.isNotBlank() },
            hiddenTension = hiddenTension?.takeIf { it.isNotBlank() },
        ),
    )

    /**
     * 结束确认卡 JSON（responded=null，用户点卡片按钮后由状态机改写）。
     * [farewell] 已废弃，仅在 LLM 违反协议填了 farewell（或降级路径）时写入以兼容展示（1:1 iOS handleOfflineMeetingAction endMeeting）。
     */
    fun makeEnd(finalMood: String?, farewell: String? = null): String =
        encode(
            OfflineInviteData(
                type = TYPE_END,
                farewell = farewell?.takeIf { it.isNotBlank() },
                finalMood = finalMood?.takeIf { it.isNotBlank() },
            ),
        )
}
