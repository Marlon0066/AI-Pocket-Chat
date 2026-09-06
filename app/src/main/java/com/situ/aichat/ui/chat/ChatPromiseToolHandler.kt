package com.situ.aichat.ui.chat

import android.util.Log
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.promise.PromiseChatTool
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.promise.PromiseReconciliation
import com.situ.aichat.promise.PromiseToolAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聊天内「我们的约定」记账动作的**闸门 + 落库 + 当场提示**（图纸 2026-09-06 约定工具调用化 §3.5）。
 *
 * 分工：解码归 [PromiseChatTool]（工具 / 暗号两路），搬运归引擎，本类只做四件事——
 * ① 见面中短路（见面里说定的走见面回顾便车·E5）；② [screen] 四道闸（模型说的每个字都过闸再落库·宁漏勿错）；
 * ③ 落库只经 [PromiseLedgerService]（`register` / `applyChange` / `resolveManually`，**绝不直碰 DAO**）；
 * ④ 组 [PromiseHint] 并排队显示（等 [blocked] 让位 → 4 秒后自动收）。
 *
 * **两个 scope 有意不同**（§0.②-8）：落库跑在调用方的回合协程（`chatTurnScope`·退出会话不取消 → 账照记），
 * 提示的等待 / 计时跑本类的 [scope]（VM `viewModelScope`·屏没了提示自然作废，账本页仍有）。
 *
 * 日志纪律（REDLINES §3）：只打计数 / 状态，**绝不打约定内容与证据**。
 */
internal class ChatPromiseToolHandler(
    private val scope: CoroutineScope,
    private val ledger: PromiseLedgerService,
    private val conversationRepo: ConversationRepository,
    private val blocked: StateFlow<Boolean>,
    private val conversationUuid: String,
) {

    private val _hint = MutableStateFlow<PromiseHint?>(null)
    val hint: StateFlow<PromiseHint?> = _hint.asStateFlow()

    private var hintJob: Job? = null
    private var counter = 0L

    /**
     * 过闸 → 落库 → 出提示（图纸 §3.5 锁定顺序）。全程 `runCatching` 包裹：账本出任何岔子都不许炸掉回合。
     *
     * @param numberedOpen 本轮注入块里的带编号 open 清单（`resolve_promise.no` 的语义单源·
     *   [com.situ.aichat.promise.PromiseInjectionRenderer.numberedOpen]）。
     * @param haystack 证据海（本轮上下文 + 本轮回复·去空白后），由 [haystack] 组装。
     */
    suspend fun applyAndShow(
        actions: List<PromiseToolAction>,
        numberedOpen: List<PromiseEntity>,
        characterUuid: String,
        haystack: String,
        now: Long,
    ) {
        try {
            // ① 见面（线下）回合：工具本就不下发，暗号即便解析出也不落库（职责边界·E5）。
            if (conversationRepo.get(conversationUuid)?.isInOfflineMode == true) return

            val screened = screen(actions, numberedOpen, haystack)
            if (screened.isEmpty()) return

            // ② 落库（唯一写口 = PromiseLedgerService）。被守卫拦下（去重 / 金额 / 编号过期）→ 不计入、无提示。
            val recorded = ArrayList<PromiseEntity>()
            val fulfilled = ArrayList<PromiseEntity>()
            val cancelled = ArrayList<PromiseEntity>()
            for (r in screened.records) {
                val saved = ledger.register(
                    characterUuid = characterUuid,
                    conversationUuid = conversationUuid,
                    content = r.content,
                    dueAtMillis = r.dueAtMillis,
                    sourceRaw = PromiseSource.CHAT,
                    sourceSessionId = "",
                    now = now,
                )
                if (saved != null) recorded.add(saved)
            }
            for ((target, change) in screened.resolves) {
                if (ledger.applyChange(change, now)) {
                    if (change.status == PromiseStatus.FULFILLED) fulfilled.add(target) else cancelled.add(target)
                }
            }

            // ③ 组提示（全空 → 无提示）。
            hintFor(PromiseApplyOutcome(recorded, fulfilled, cancelled), ++counter)?.let(::show)
        } catch (e: CancellationException) {
            throw e // 回合被打断 / 取消：不吞不记（吞掉会让引擎在已取消的协程里继续走后段·复核 R1 🟡-3）
        } catch (e: Exception) {
            Log.w(TAG, "约定工具落库失败 count=${actions.size}", e) // 只打计数，绝不打内容 / 证据
        }
    }

    /**
     * 撤销刚记下的那条（「不是约定」·D-2 点了直接生效不二次确认）。走 [PromiseLedgerService.resolveManually]
     * → `resolutionEvidence` 恒空，「证据空 = 手动标记」不变量继续成立。
     * 目标已被别处了结（返 false）→ 直接收提示，不报错（E11）。
     */
    fun undoRecorded(uuid: String) {
        hintJob?.cancel()
        hintJob = scope.launch {
            val ok = try {
                ledger.resolveManually(uuid, PromiseStatus.CANCELLED, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "约定撤销失败", e) // 只打异常类型，绝不打内容
                false
            }
            if (ok) show(PromiseHint(PromiseHint.Kind.UNDONE, "", null, 0, 0, 0, ++counter)) else dismiss()
        }
    }

    fun dismiss() {
        hintJob?.cancel()
        _hint.value = null
    }

    /**
     * 排队显示（§3.5 锁定）：等 [blocked] 变 false 才出现（日历 toast / 断网条 / 网络恢复条 / 赴约钮在场时让它们先）；
     * 等超 [BLOCKED_WAIT_CAP_MILLIS] **放弃显示**（账已记，账本页看得到），绝不叠着显示。
     */
    private fun show(hint: PromiseHint) {
        hintJob?.cancel()
        hintJob = scope.launch {
            if (withTimeoutOrNull(BLOCKED_WAIT_CAP_MILLIS) { blocked.first { !it } } == null) {
                // 等超上限 → 放弃显示（账已记）。顺手清掉可能还挂着的上一条：它的计时 Job 已被本次 cancel，
                // 不清就会永远停在屏上，「记下了」那条只剩「不是约定」可点 → 误撤真约定（复核 R1 🟡-2）。
                _hint.value = null
                return@launch
            }
            _hint.value = hint
            delay(if (hint.kind == PromiseHint.Kind.UNDONE) UNDONE_MILLIS else HINT_MILLIS)
            if (_hint.value?.seq == hint.seq) _hint.value = null // 期间被别的提示接管 → 不越权清
        }
    }

    companion object {
        private const val TAG = "ChatPromiseTool"

        /** 提示停留时长（图纸 §9② 锁定·与日历 toast 同档）。 */
        const val HINT_MILLIS = 4000L

        /** 「好，当我没记」停留时长（图纸 §9② 锁定）。 */
        const val UNDONE_MILLIS = 1500L

        /** 等同槽其它横幅让位的上限；超时放弃显示（图纸 §9② 锁定）。 */
        const val BLOCKED_WAIT_CAP_MILLIS = 8000L

        /** 证据海：窗口内全部消息正文 + 本轮原始回复，去全部空白（[PromiseLedgerService.normalize] 单源）。 */
        fun haystack(messages: List<MessageEntity>, replyText: String): String =
            PromiseLedgerService.normalize(messages.joinToString("\n") { it.content } + "\n" + replyText)

        /**
         * 纯函数闸门（不碰 DB）。Record：非空 → ≤[PromiseReconciliation.NEW_CONTENT_MAX_LEN] 码点 → 证据闸；
         * Resolve：同 `no` 只取首条 → 编号在清单范围内 → status 白名单 → 证据闸。各取前
         * [PromiseChatTool.RECORD_CAP] / [PromiseChatTool.RESOLVE_CAP] 条。
         * 证据闸复用 [PromiseReconciliation.evidenceOk]（= 对账闸二平移到工具路·单源）。金额 / 去重由 `register` 挡。
         */
        fun screen(
            actions: List<PromiseToolAction>,
            numberedOpen: List<PromiseEntity>,
            normHaystack: String,
        ): Screened {
            val records = ArrayList<PromiseToolAction.Record>()
            val resolves = ArrayList<Pair<PromiseEntity, PromiseReconciliation.VerifiedChange>>()
            val seenNos = HashSet<Int>()
            for (action in actions) {
                when (action) {
                    is PromiseToolAction.Record -> {
                        if (records.size >= PromiseChatTool.RECORD_CAP) continue
                        val content = action.content.trim()
                        if (content.isEmpty()) continue
                        if (content.codePointCount(0, content.length) > PromiseReconciliation.NEW_CONTENT_MAX_LEN) continue
                        if (!PromiseReconciliation.evidenceOk(action.evidence, normHaystack)) continue
                        records.add(action.copy(content = content))
                    }
                    is PromiseToolAction.Resolve -> {
                        if (resolves.size >= PromiseChatTool.RESOLVE_CAP) continue
                        if (!seenNos.add(action.no)) continue // 同 no 重复只取首条
                        if (action.no !in 1..numberedOpen.size) continue // 编号越界（含清单为空）
                        if (action.status != PromiseStatus.FULFILLED && action.status != PromiseStatus.CANCELLED) continue
                        if (!PromiseReconciliation.evidenceOk(action.evidence, normHaystack)) continue
                        val target = numberedOpen[action.no - 1]
                        resolves.add(target to PromiseReconciliation.VerifiedChange(target.uuid, action.status, action.evidence))
                    }
                }
            }
            return Screened(records, resolves)
        }

        /**
         * 纯函数：落库结果 → 提示（图纸 §3.5 / D-4 锁定）。单记 / 单兑现 / 单取消各自成条（撤销键只在单记那条上）；
         * 其余组合（含同轮既记又了结）合并成一条计数提示；全空 → null。
         */
        fun hintFor(outcome: PromiseApplyOutcome, seq: Long): PromiseHint? {
            val r = outcome.recorded.size
            val f = outcome.fulfilled.size
            val c = outcome.cancelled.size
            return when {
                r + f + c == 0 -> null
                r == 1 && f + c == 0 ->
                    PromiseHint(PromiseHint.Kind.RECORDED, outcome.recorded[0].content, outcome.recorded[0].uuid, r, f, c, seq)
                f == 1 && r + c == 0 ->
                    PromiseHint(PromiseHint.Kind.FULFILLED, outcome.fulfilled[0].content, null, r, f, c, seq)
                c == 1 && r + f == 0 ->
                    PromiseHint(PromiseHint.Kind.CANCELLED, outcome.cancelled[0].content, null, r, f, c, seq)
                else -> PromiseHint(PromiseHint.Kind.MERGED, "", null, r, f, c, seq)
            }
        }
    }

    /** 过闸后的待落库动作（Resolve 已完成 no→uuid 映射）。 */
    internal data class Screened(
        val records: List<PromiseToolAction.Record>,
        val resolves: List<Pair<PromiseEntity, PromiseReconciliation.VerifiedChange>>,
    ) {
        fun isEmpty(): Boolean = records.isEmpty() && resolves.isEmpty()
    }
}

/** 本轮实际落库成功的三组（被守卫 / 陈旧防护拦下的不在内）。 */
internal data class PromiseApplyOutcome(
    val recorded: List<PromiseEntity>,
    val fulfilled: List<PromiseEntity>,
    val cancelled: List<PromiseEntity>,
)

/**
 * 当场提示的瞬态 UI 态（**不落库、不进消息表**）。两张脸共用此类型与 `promiseHintText` 纯函数。
 * [undoUuid] 仅 [Kind.RECORDED] 非空（撤的就是刚记下的那一条）；[recorded]/[fulfilled]/[cancelled] 供
 * [Kind.MERGED] 组「记下 N 条 · 兑现 M 条」；[seq] 让计时只清自己那一条。
 */
internal data class PromiseHint(
    val kind: Kind,
    val content: String,
    val undoUuid: String?,
    val recorded: Int,
    val fulfilled: Int,
    val cancelled: Int,
    val seq: Long,
) {
    enum class Kind { RECORDED, FULFILLED, CANCELLED, MERGED, UNDONE }
}
