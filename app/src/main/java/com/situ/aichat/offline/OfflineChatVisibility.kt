package com.situ.aichat.offline

import com.situ.aichat.data.model.MessageKind

/**
 * 「用户可见聊天界面」的消息可见性谓词。隐藏三类消息：
 *
 * **① 系统耳语 [MessageKind.SYSTEM_HINT]**——只喂模型、用户永不可见的后台旁白。例如「取消见面」提示
 * `（用户打开了「发起见面」界面…犹豫了一下取消了）`（让角色感知用户动作并自然搭话）、线下「准备出发」确认、
 * 续场提示等。**按类型无条件隐藏、与是否线下无关**——这类耳语在普通聊天里也会产生（取消见面时
 * isOfflineMode=false），所以不能只靠线下旗判定，否则会以聊天气泡形式漏给用户、破坏沉浸感。
 *
 * **② 线下见面细节（方案 A·2026-06-18 用户拍板）**——见面期间产生的一切消息（AI 第三人称叙事
 * `[场景]/[环境]/[动作]/[对话]…`、用户动作块、入场标记、AI「结束确认卡」，均 isOfflineMode=true）都不进日常聊天，
 * 以免大段角色扮演打断日常对话沉浸感。唯一仍进入日常聊天的见面消息 = 离场标记 [MessageKind.OFFLINE_MARKER_END]
 * （渲染为「— 线下见面结束 · 时长 —」收尾分隔条，见 [com.situ.aichat.ui.chat.OfflineEndDivider]）。
 *
 * **③ 语音通话逐轮转写 `isPartOfVoiceCall`（2026-07-12 用户拍板）**——通话中双方说的每句话由
 * [com.situ.aichat.voice.VoiceCallPersistence] 落成 `isPartOfVoiceCall=true` 的 plainText（供模型历史与记忆），
 * 但**不以气泡出现在聊天流**；用户回看通话内容的唯一入口 = 通话记录卡 [MessageKind.CALL_RECORD_CARD]
 * （聚合整段 transcript·本身 isPartOfVoiceCall=false → 不受此滤）。按旗无条件隐藏、与 kind 无关。
 *
 * 被隐藏的消息仍完整保留在原处（均不受本谓词影响）：见面详情页 [com.situ.aichat.ui.offline.OfflineReviewView]、
 * 通话记录卡展开、以及喂给模型的上下文 [com.situ.aichat.prompt.PromptBuilder]（独立数据源 `getRecent`·照常保留全部）。
 * 邀约卡 [MessageKind.OFFLINE_INVITE_CARD] 发生在「进入见面之前」、本就 isOfflineMode=false → 不被隐藏，作为正常聊天消息保留。
 *
 * **同源约束**：本谓词 = 人类可读规格 + 日常聊天渲染层兜底（[com.situ.aichat.ui.chat] MessageRow）。真实过滤发生在 DAO
 * 四条「可见消息」查询的 SQL 里，**条条都与本谓词同源（改一处必须同步全部）**：排除系统耳语
 * `messageKindRaw != 'system_hint'`（第①条）+ 排除见面细节 `(isOfflineMode = 0 OR messageKindRaw = 'offline_marker_end')`（第②条）
 * + 排除通话转写 `isPartOfVoiceCall = 0`（第③条）：
 *  - [com.situ.aichat.data.local.dao.MessageDao.observeVisibleWindowed]（日常聊天列表·窗口化）
 *  - [com.situ.aichat.data.local.dao.MessageDao.latestVisibleMessage]（见面余温的最新可见消息锚点·OfflineAfterglowService）
 *  - [com.situ.aichat.data.local.dao.MessageDao.latestVisibleMessages]（删消息后会话预览重算·脏行扫描窗取最新 N 条·加固批件①）
 *  - [com.situ.aichat.data.local.dao.MessageDao.getRecentVisible]（通知栏回复线程 + 列表内联快捷回复预览）
 */
object OfflineChatVisibility {

    /**
     * 该消息是否应从用户可见的聊天界面隐藏（系统耳语无条件隐藏；线下见面细节仅留离场标记）。
     * 穷举 when（无 else）：新增 [MessageKind] 时编译器强制在此显式裁定其可见性，避免静默漏判。
     */
    /**
     * 回顾/复盘面的隐藏判定（审计 S8 单源·原 ChatOfflineController 只读回顾 / OfflineMeetingMemoryViewModel /
     * ChatViewModel 线下沉浸流三处各自抄写）：入场/退场标记与系统耳语不给用户看。
     */
    fun isHiddenFromReview(kind: MessageKind): Boolean = when (kind) {
        MessageKind.OFFLINE_MARKER_START, MessageKind.OFFLINE_MARKER_END, MessageKind.SYSTEM_HINT -> true
        else -> false
    }

    fun isHiddenFromDailyChat(isOfflineMode: Boolean, kind: MessageKind, isPartOfVoiceCall: Boolean = false): Boolean {
        // ③ 语音通话逐轮转写按旗无条件隐藏（= SQL `isPartOfVoiceCall = 0`）：只留通话记录卡给用户回看。
        if (isPartOfVoiceCall) return true
        return when (kind) {
            // 系统耳语永远隐藏（与是否线下无关——取消见面提示等普通聊天里也产生）。
            MessageKind.SYSTEM_HINT -> true
            // 离场标记 = 唯一进日常聊天的线下消息（渲染成「线下见面结束」收尾分隔条）。
            MessageKind.OFFLINE_MARKER_END -> false
            // 其余：仅当属于某次线下见面（isOfflineMode=true）时作为「见面细节」隐藏；普通聊天里照常显示。
            MessageKind.PLAIN_TEXT, MessageKind.OFFLINE_INVITE_CARD, MessageKind.OFFLINE_END_CARD,
            MessageKind.CALL_RECORD_CARD, MessageKind.OFFLINE_MARKER_START, MessageKind.SYSTEM_EVENT_CARD,
            MessageKind.GIFT_CARD, MessageKind.RED_PACKET, MessageKind.SCHEDULE_CARD,
            MessageKind.FUTURE_MEETING_PROPOSAL_CARD, MessageKind.FUTURE_MEETING_CHANGE_CARD -> isOfflineMode
        }
    }
}

/**
 * 上面 [OfflineChatVisibility] 的「写入侧」对偶：见面期间用户主动发出的消息（文字 / 语音 / 表情包）落库时应携带的
 * [com.situ.aichat.data.local.entity.MessageEntity.offlineSessionId]——会话处于线下见面（[isInOfflineMode]）→
 * 随当前 [currentOfflineSessionId]；否则返回 null（普通消息）。
 *
 * **配套约束**：落库时一并写 `isOfflineMode = (返回值 != null)`，与助手投递（`ChatViewModel.deliverTextReply`）**完全同源**。
 * 用户消息漏标这一步会同时在两处错乱：
 *  ① 漏进普通聊天列表 / 通知预览（DAO `(isOfflineMode = 0 OR …)` 放行——本就是上面谓词的 SQL 镜像）；
 *  ② 缺席沉浸剧场 / 见面回顾（二者按 `isOfflineMode = 1 AND offlineSessionId = :sessionId` 归组，见 [com.situ.aichat.data.local.dao.MessageDao.observeOfflineSessionMessages]）。
 * 模型上下文（`getRecent` 全量、不过滤线下）不受影响：AI 照常看到用户在见面里说的话。
 */
internal fun outgoingOfflineSessionId(isInOfflineMode: Boolean, currentOfflineSessionId: String?): String? =
    if (isInOfflineMode) currentOfflineSessionId else null
