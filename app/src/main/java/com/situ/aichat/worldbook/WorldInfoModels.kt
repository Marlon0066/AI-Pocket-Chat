package com.situ.aichat.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity
import kotlin.random.Random

/**
 * 世界书激活引擎的输入 / 输出模型（WB3·契约 `FABLE5_WORLDBOOK_PROPOSAL.md` §4.2）。
 * 引擎纯逻辑零 Android 依赖；随机源经 [WorldInfoRng] 注入（概率掷骰 / 分组抽签可确定性测试）。
 */

/** 随机源缝（生产 = [fromRandom]；测试注入固定值）。 */
fun interface WorldInfoRng {
    /** 返回 [0, bound) 的整数。 */
    fun nextInt(bound: Int): Int

    companion object {
        fun fromRandom(random: Random = Random.Default): WorldInfoRng =
            WorldInfoRng { bound -> random.nextInt(bound) }
    }
}

/** 插入策略（ST `world_info_insertion_strategy`；默认角色书优先 = V2 卡规范要求）。 */
enum class WorldInfoInsertionStrategy { EVENLY, CHARACTER_FIRST, GLOBAL_FIRST }

/**
 * 全局设置。默认值与酒馆全局设置的默认值一致（契约 §2.3）；预算按 D4 拍板字符化（默认 6000 字）。
 * 书级 scan_depth / token_budget / recursive_scanning 只存储不生效——ST 自身同样不应用它们（引擎与 ST 行为对齐）。
 */
data class WorldInfoSettings(
    /** 扫描最近几条消息（ST 默认 2；条目可覆盖）。 */
    val scanDepth: Int = 2,
    /** 世界书总字符预算（D4：默认 6000 字）。 */
    val budgetChars: Int = 6000,
    /** 递归扫描总开关（ST 默认关）。 */
    val recursiveScan: Boolean = false,
    /** 扫描缓冲带「名字: 」前缀（ST 默认开）。 */
    val includeNames: Boolean = true,
    val caseSensitive: Boolean = false,
    /** 整词匹配（ST 默认关；中文场景保持关，契约 §5）。 */
    val matchWholeWords: Boolean = false,
    /** 分组按关键词命中数评分（ST 默认关；条目可覆盖）。 */
    val useGroupScoring: Boolean = false,
    /** 激活数不足时向更早历史扩窗，直到激活满 N 条（0 = 关）。 */
    val minActivations: Int = 0,
    /** 扩窗的最大回溯深度（0 = 不限，至多全部历史）。 */
    val minActivationsMaxDepth: Int = 0,
    /** 递归轮数上限（0 = 不限，扫到干涸为止）。 */
    val maxRecursionSteps: Int = 0,
    val insertionStrategy: WorldInfoInsertionStrategy = WorldInfoInsertionStrategy.CHARACTER_FIRST,
)

/** 扫描输入的一条消息（升序传入；[senderName] 供 include_names 前缀）。 */
data class ScanMessage(val text: String, val senderName: String = "")

data class WorldInfoActivationInput(
    /** 本轮生效的书（调用方已按「启用的全局书 ∪ 绑定书」取好；引擎仍复核 enabled）。 */
    val books: List<WorldBookEntity>,
    val entries: List<WorldBookEntryEntity>,
    /** 最近消息，升序（旧→新）。给足 max(扫描深度, 扩窗上限) 条即可。 */
    val messages: List<ScanMessage>,
    /** 会话消息总数（delay / sticky / cooldown 的计数锚）。 */
    val conversationMessageCount: Int,
    val conversationUuid: String,
    val timedStates: List<WorldBookTimedStateEntity> = emptyList(),
    /** 向量检索命中的条目 uuid（WB5 产出；链接条目只认这个，不走关键词）。 */
    val vectorMatchedEntryUuids: Set<String> = emptySet(),
    val settings: WorldInfoSettings = WorldInfoSettings(),
)

/** @depth 注入项：WB4 在对话历史倒数第 [depth] 条处按 [role]（0=system/1=user/2=assistant）插入。 */
data class AtDepthInjection(val depth: Int, val role: Int, val content: String)

/** 激活条目的诊断摘要（日志用——只带标题与字数，内容全文绝不进日志，遵循日志约定）。 */
data class ActivatedEntrySummary(
    val uid: Int,
    val title: String,
    val bookName: String,
    val contentLength: Int,
)

data class WorldInfoDiagnostics(
    val activated: List<ActivatedEntrySummary>,
    val droppedByBudget: List<ActivatedEntrySummary>,
    /** 解析失败、已按 D3 降级为普通子串的正则形态关键词。 */
    val badRegexKeys: List<String>,
    val sweepCount: Int,
    val outletSkippedCount: Int,
)

data class WorldInfoActivationResult(
    /** 系统提示内「世界书·前」锚点（position 0）。 */
    val before: String,
    /** 「世界书·后」锚点（position 1/5/6 归并）。 */
    val after: String,
    /** 后置区（position 2/3 归并——作者注释的近因等价语义）。 */
    val suffix: String,
    /** @depth 注入（position 4）。 */
    val atDepth: List<AtDepthInjection>,
    /** 本轮触发产生的新时效状态（调用方 upsert）。 */
    val newTimedStates: List<WorldBookTimedStateEntity>,
    /** 已过期的时效状态（调用方删除）。 */
    val expiredTimedStates: List<WorldBookTimedStateEntity>,
    val diagnostics: WorldInfoDiagnostics,
) {
    val isEmpty: Boolean
        get() = before.isBlank() && after.isBlank() && suffix.isBlank() && atDepth.isEmpty()
}

/** 引擎内部流转的已激活候选。 */
internal data class ActivatedCandidate(
    val entry: WorldBookEntryEntity,
    val book: WorldBookEntity,
    /** 关键词命中数（分组评分用；常驻/向量/续贴 = 0）。 */
    val matchCount: Int,
    /** 经 sticky 保持窗直接续贴（跳过概率重掷、不重复产时效状态）。 */
    val viaSticky: Boolean,
)
