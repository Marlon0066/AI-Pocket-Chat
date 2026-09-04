package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind

/**
 * 「重新生成」的范围判据——**长按菜单给不给这一项** 与 **引擎删哪些消息** 的唯一单源
 * （2026-09-04 用户拍板根治·独立复核 R2 🔴-1）。
 *
 * ## 为什么必须单源
 *
 * 此前两侧各算各的，且**取数都不同源**：菜单在 UI 可见流（`observeVisibleWindowed`·滤掉暂扣 /
 * `system_hint` / 线下叙事 / 通话转写）上 `takeLastWhile { roleRaw == "assistant" }`，引擎在
 * DB 全量（`getRecent`·零过滤）上做同样的事。尾部一旦有隐藏行，两者分歧成两类事故：
 * - **假选项**：DB 末尾是隐藏的 SYSTEM_HINT（`roleRaw="user"`——爽约旁白 / 通话失联圆场 / 见面取消
 *   提示，其后的生成失败时会长期滞留）→ 菜单给了这一项，引擎 `trailing` 为空、静默什么都不做；
 * - **误删**：末尾两条可见 assistant 之间夹隐藏 user 行（通话逐轮转写 / 见面期叙事）→ 引擎删掉的是
 *   **通话记录卡**或**「线下见面结束」分隔条**（各自是回看通话 / 见面回顾的唯一入口），用户长按的那条
 *   反而纹丝不动。
 *
 * 现在两侧都调本对象、且都喂**可见流**（引擎走 `recentVisibleChronological`），分歧面消除。
 *
 * ## 一轮包含什么
 *
 * 只有 AI 的**文本类**消息算「可重来的回复」：普通文字与日程卡（`isStructuredCard == false`，
 * 二者都是同一次生成的文字产物）。**事件 / 结构化卡遇到即停**——通话记录卡、见面结束条、红包、礼物、
 * 邀约卡、未来约定卡：它们不是「AI 说的一句话」，而是一次事件的凭证与入口，重来无意义、删掉是净损失
 * （红包礼物更直连钱路）。因此通话刚结束 / 见面刚结束 / 刚收到红包时，整屏都不给「重新生成」——
 * 这是有意的：那一刻本就没有「可以重说的一轮」。
 */
internal object RegenerableTurn {

    /** 单条是否算「可重来的一轮」的成员。[message] 应来自可见流（隐藏行在 SQL 层已滤掉）。 */
    fun isPartOfTurn(message: MessageEntity): Boolean =
        message.roleRaw == "assistant" && !MessageKind.fromRaw(message.messageKindRaw).isStructuredCard

    /**
     * 可见流末尾连续的「可重来」段（时间正序输入·最新在末）——引擎按这个删，菜单按这个给。
     * 空 = 当下没有可重来的一轮（最后一条是用户消息 / 事件卡 / 会话为空）。
     */
    fun trailing(visibleChronological: List<MessageEntity>): List<MessageEntity> =
        visibleChronological.takeLastWhile(::isPartOfTurn)

    /** [trailing] 的 uuid 集合——列表逐行判「本行给不给菜单项」用。 */
    fun trailingUuids(visibleChronological: List<MessageEntity>): Set<String> =
        trailing(visibleChronological).mapTo(HashSet()) { it.messageUUID }

    /**
     * 行级判据：本行此刻给不给「重新生成」。[trailingUuids] 来自 [trailingUuids]，
     * [isBusy] = 有回合在跑（`ChatViewModel.isSending`）——引擎的并发门会挡下，给了也是假选项。
     */
    fun canRegenerate(messageUuid: String, trailingUuids: Set<String>, isBusy: Boolean): Boolean =
        !isBusy && messageUuid in trailingUuids
}
