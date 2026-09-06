package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `Conversation` @Model (MVP subset: chat-essential fields).
 * Offline-mode and wallpaper columns are added later when those features land.
 */
@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["characterUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("characterUuid"),
        Index("isArchived"),
        Index("creationDate"),
        Index("lastMessageDate"),
    ],
)
data class ConversationEntity(
    @PrimaryKey val uuid: String,
    val title: String,
    val characterUuid: String,
    val creationDate: Long,

    // Memory summary cursors (mirror iOS Conversation):
    val lastSummarizedMessageDate: Long? = null,   // 摘要游标（已总结到哪）
    val lastMemorySummarySuccessDate: Long? = null, // 双轨判定的时间轨起点
    val lastMemorySummaryFailureDate: Long? = null, // 失败短冷却起点
    val lastMemorySummaryAttemptDate: Long? = null, // 老字段，仅历史记录，不参与判定

    // 未来约定见面·识别扫描节奏（双轨判定·跨进程持久化，仿 lastMemorySummary*）：
    val lastMeetingScanSuccessDate: Long? = null, // 扫描成功时间轨起点（成功冷却）
    val lastMeetingScanFailureDate: Long? = null, // 扫描失败短冷却起点

    // 惦记的事·扫描节奏（活人感一期 P2·仿 lastMeetingScan*·跨进程持久化）：
    val lastOpenLoopScanSuccessDate: Long? = null, // 扫描成功时间轨起点（成功冷却）
    val lastOpenLoopScanFailureDate: Long? = null, // 扫描失败短冷却起点

    val isPinned: Boolean = false,

    /**
     * 【历史残留·2026-09-06 起无人读写】`isArchived` 列所属的聊天归档功能已删除（图纸 docs/handoff/2026-09-06-删除聊天归档功能.md）。
     * 本列与 [Index] 保留而非 DROP：conversations 是 messages / world_book_timed_states 的外键父表且
     * onDelete=CASCADE，minSdk 29 无原生 DROP COLUMN → 删列须重建父表，手写重建漏 `PRAGMA foreign_keys=OFF`
     * 会级联删光全部消息。收益（一个死 boolean）远小于风险。日后要清请走 Room @DeleteColumn AutoMigration，另立一卷。
     * **禁止任何查询再引用本列**（看门测试：DashboardCountParityTest「归档残留列不再影响任何查询」）。
     */
    val isArchived: Boolean = false,

    val isReservedForNotifications: Boolean = false,

    val lastReadDate: Long? = null,
    val lastMessageDate: Long? = null,
    val lastMessagePreview: String = "",
    val lastMessageRole: String = "",

    val moodEmoji: String = "",
    val moodText: String = "",
    val moodColorName: String = "green",

    val cachedUnreadCount: Int = 0,

    val voiceRoundsSinceLastVoice: Int = 0,
    val voiceNextThreshold: Int = 0,

    // 线下见面（M16 / P10.2）——1:1 iOS Conversation 线下字段。每次见面一个 sessionId 贯穿其所有消息；
    // isInOfflineMode + currentOfflineSessionId 耦合（进入/退出同一事务写）。摘要退避状态持久化在此（跨进程）。
    val isInOfflineMode: Boolean = false,
    val currentOfflineSessionId: String? = null,
    /**
     * 【历史残留·2026-09-06 起无人读写】原线下节拍卡正文（图纸 docs/handoff/2026-09-06-见面窗口与节拍卡七件.md G）；
     * 列保留理由同 [isArchived]（conversations 是外键父表，删列须重建父表）。进入/退出线下态仍照旧清空。
     */
    val currentSceneProgress: String = "",
    /** 待生成见面摘要的 sessionId（退出时设、提取成功清；重启/重进自动重试）。 */
    val pendingOfflineSummarySessionId: String? = null,
    /** 摘要失败重试计数（退避用，成功/兜底归 0）。 */
    val pendingOfflineSummaryFailCount: Int = 0,
    /** 最近一次摘要尝试时间（毫秒，退避窗口判定；iOS Date?）。 */
    val pendingOfflineSummaryLastAttemptAt: Long? = null,
    /** 规则兜底摘要对应的 sessionId 列表（逗号分隔，UI 显示「简化」标识 + 自愈升级用）。 */
    val offlineSummaryFallbackSessionIds: String = "",

    /**
     * 散场硬闸剩余轮数（图纸 2026-09-06 见面窗口与节拍卡七件 §3.E·D-1）：用户点「再待一会儿」置 3；
     * 每个**成功**的 AI 回合尾递减 1（SQL 守卫不为负）；> 0 时本轮不下发 end_offline_meeting 且任何结束动作被
     * [com.situ.aichat.offline.OfflineMeetingService.handleEndMeeting] 丢弃。进入/退出/重置线下态归零。
     */
    val offlineEndHoldTurns: Int = 0,

    // 场内滚动压缩·前情提要（记忆改造二期·部件⑤·图纸 §3.2-A）——进行中的长见面/长通话，被截断窗口静默丢弃
    // 的早期部分压缩成一段前情提要挂在此，注入在截断提示之后。**惰性失效**：注入/续写前校验 sessionKey 是否 ==
    // 当前场景 key（见面 = currentOfflineSessionId；通话 = "call:"+本场首条消息 timestamp），不匹配视同无前情提要；
    // 场景结束不清这三列——下一场生成时整组覆写，旧值永不再匹配 → 自然失效（省清理钩子、杜绝漏清）。
    /** 前情提要正文（无标题·纯文本）；空=尚未生成。 */
    val inSceneRecapText: String = "",
    /** 前情提要所属场景 key（惰性失效判据）；空=无。 */
    val inSceneRecapSessionKey: String = "",
    /** 已覆盖到的消息 timestamp 水位（下一场次覆盖从此续起）；0=未覆盖。 */
    val inSceneRecapUntilMillis: Long = 0,
)
