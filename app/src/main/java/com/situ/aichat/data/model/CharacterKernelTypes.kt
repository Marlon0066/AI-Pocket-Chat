package com.situ.aichat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * 活人感统一内核的新值类型（总图纸 `docs/handoff/2026-09-01-活人感统一内核-总图纸.md` §3.0 指定落位）。
 *
 * 与 [CharacterGrowthTypes.kt] 的分工：那边是移植期六个既有成长类型（**字段定义零碰**·总图纸 §9.3），
 * 本文件只放内核新增类型，各卷逐步添加：
 * - **卷一《人设编译器》**（本次）：[PersonaCompileMeta] / [PersonaGains] / [CustomGain] / [PersonaOperator]
 * - 卷二《正负双压》：`RelationshipPressure`（体量大、独立成 `RelationshipPressure.kt`）
 * - **卷三《场内核与渲染收编》**：[AffectField]（四场 / 日预算 / 最近命中·图纸 §3.1）
 * - **卷四《意图队列 + 性格复盘》**（已落）：[IntentKind] / [IntentState] / [CharacterIntent] / [IntentQueueState]
 *   （意图队列 + 性格复盘计数同住 `intentQueueJSON` 一列·卷四图纸 §3.1 K-1）
 *
 * 编解码一律扩展 [GrowthJson]（复用同一 `Json` 实例：`ignoreUnknownKeys` + `encodeDefaults` +
 * `coerceInputValues`，解码失败永不抛、回落默认）；解码访问器落 `CharacterGrowthTypes.kt` 末尾。
 *
 * 卷一只**编译、存、显示**；**卷三起接消费端**：[PersonaGains] 进 `AffectKernel.project` 投影（档位系数 × 投影表），
 * [PersonaOperator] 的 c07–c12 在 `InnerStateRenderer` 求值（c01–c06 留卷四·恒 false）。
 */

// MARK: - 人设编译元数据

/**
 * 一次人设编译的元数据（卷一图纸 §3.1）。数值本身分散在锚点 / 增益 / 算子三列，这里只记「怎么来的」。
 *
 * [personaHash] 用于 D-2 提醒条：当前 `personalityDescription` 的 hash 与它不等 ⇒ 人设改过、数值还是旧版编译的。
 * [lastFailedAt] > [compiledAt] ⇒ 上一次编译失败（D-5：数值原样不动，只置提示态）。
 */
@Serializable
data class PersonaCompileMeta(
    val source: String = SOURCE_DEFAULT,
    val compiledAt: Long = 0L,
    /** 编译时 `personalityDescription` 的 SHA-256 前 16 位十六进制（卷一图纸 Y-5·仅此一字段参与）。 */
    val personaHash: String = "",
    /** 0 = 从未失败（卷一图纸 Y-4：失败也要落库，否则退出重进即丢失失败态）。 */
    val lastFailedAt: Long = 0L,
    /** 上次编译被丢弃的越界条目数（Y-6：绝不静默吞，计数上屏）。 */
    val droppedCount: Int = 0,
    /**
     * 维度 key → 依据短语（≤24 字），编译时 LLM 给的「我为什么这么打分」。
     * 图纸 §4.2 要求依据短语行在**退出重进后仍显示**（§7.4 装机验收明写「进编辑页…至少一根有楷体依据短语」），
     * 而 §3.1 的四列里没有它的落脚点 —— 挂在 meta 里是零迁移的最小落法（老 JSON 缺键即空 map）。见图纸 §11 偏差 D-6。
     */
    val anchorBasis: Map<String, String> = emptyMap(),
    /**
     * 修缮卷 J7：用户删掉的算子墓碑（`"cNN|aNN"`·≤ [MAX_SUPPRESSED]·先进先出）。「重新生成」把编译产物整体替换，
     * 但**用户决定必须保留**——删了就不该回来；无新增入口（只在编辑页保存时由 `PersonaCompileUseCase` 登记）。
     */
    val suppressedOperators: List<String> = emptyList(),
) {
    companion object {
        /** 从未编译过（新角色 / 老角色的出厂态）。 */
        const val SOURCE_DEFAULT = "default"

        /** 墓碑上限（修缮卷 §3.11 锁定值）：超出去最旧。 */
        const val MAX_SUPPRESSED = 40

        /** 编译产物。 */
        const val SOURCE_COMPILED = "compiled"

        /** 用户手改过（编译后又拖过「本性」滑杆）。 */
        const val SOURCE_MANUAL = "manual"
    }
}

// MARK: - 增益（她吃哪套）

/**
 * 「什么事对她影响大」（卷一图纸 §3.1 / §3.2）。
 *
 * [system] 是 27 项系统词表的档位覆盖：key 恒为 `g01`…`g27`（词表本体在 `PersonaVocab`），值 ∈ 0/1/2。
 * **缺席即 1（正常）**（Y-7）——典型角色只存 6–10 项，JSON 极小；三档系数只在代码里、不入库。
 */
@Serializable
data class PersonaGains(
    val system: Map<String, Int> = emptyMap(),
    /** ②编译读出的专属项 + ③用户手写，合计 ≤ [MAX_CUSTOM]。 */
    val custom: List<CustomGain> = emptyList(),
    /**
     * 修缮卷 J7：用户手调过档位的系统项 `gNN`（≤ 27·去重）。「重新生成」时这些项的档位以 [system] 里的手调值压住编译值
     * （值为 1 的手调项也要写进 [system] 才压得住）；只在编辑页保存时由 `PersonaCompileUseCase` 登记。
     */
    val manualSystem: List<String> = emptyList(),
) {
    companion object {
        /** 专属项合计上限（总图纸 §9.2 锁定值）。 */
        const val MAX_CUSTOM = 10
    }
}

/** 角色专属的敏感点（27 项词表之外的那些·卷一图纸 §3.1）。 */
@Serializable
data class CustomGain(
    val id: String = "",
    /** ≤ [MAX_LABEL_LENGTH] 字。 */
    val label: String = "",
    /** 新项默认「很敏感」= 档位 2（D-10）。三档常量与系数表在 `PersonaVocab`，本处只写档位整数（Y-7：档位存整数、系数不入库）。 */
    val level: Int = 2,
    val origin: String = ORIGIN_COMPILED,
) {
    companion object {
        /** 编译器从人设里读出来的。 */
        const val ORIGIN_COMPILED = "compiled"

        /** 用户手写新增的（超上限淘汰时优先保留·总图纸 §3.3）。 */
        const val ORIGIN_MANUAL = "manual"

        /** 标签字数上限（总图纸 §9.2 锁定值）。 */
        const val MAX_LABEL_LENGTH = 12
    }
}

// MARK: - 算子（她的固定反应）

/**
 * 条件触发的固定反应（卷一图纸 §3.1 / §3.3）。[condition] / [action] 恒为**封闭词表 key**
 * （`PersonaVocab.CONDITIONS` / `PersonaVocab.ACTIONS` 的 `cNN` / `aNN`）——中文标签走 zh 资源，改文案不会失配。
 *
 * 本卷只存不求值：条件求值依赖卷三的场与卷四的意图队列。
 */
@Serializable
data class PersonaOperator(
    val id: String = "",
    val condition: String = "",
    val action: String = "",
    val enabled: Boolean = true,
)

// MARK: - 双标记滑杆的纯判据

/**
 * 「现在」竖线是否可见（卷一图纸 §4.2 D-3 阈值 `> 5`）：本性与现值贴得太近时整条隐藏，
 * 免得两个标记糊在一起、也免得未编译角色（本性==现在 ⇒ 偏移 0）平白多一条线。
 *
 * 抽成纯函数是为了让阈值只有一个落点（UI 与测试共用），别在渲染里重写一遍比较。
 */
internal fun personaCurrentMarkerVisible(anchor: Int, current: Int): Boolean = abs(current - anchor) > 5

// MARK: - 四场（卷三《场内核与渲染收编》· 图纸 §3.1 · 总图纸 §3.4）

/**
 * 角色此刻的四个「场」（卷三图纸 §3.1 逐字）：安全感 / 投入度 是慢场（周/月尺度回落到基线），
 * 效价 / 激活度 是快场（小时/天尺度）。事件经人设增益投影进场（`AffectKernel.project`），场再**单向**扩散到
 * 16 维（`AffectKernel.diffuse`）——禁止任何「维 → 场」方向的函数（总图纸 §3.4 保险 1）。
 *
 * **修缮卷 J1（慢场惰性参考值）**：[security] / [investment] 列里存的是 **[slowRefAt] 时刻的参考值**，不是此刻的值——
 * 整数存储下 30 天半衰的场每轮位移 < 0.5 必被取整吞掉（留补步一周归零、去补步冻结），故照卷四意图强度的 K-3 范式改成
 * 读时按半衰期算（`AffectMath.slowNow`），只有分析通道的事件才改参考值；tick 不碰慢场值。快场仍逐 tick 松弛 + 补步。
 *
 * 持久化在 `CharacterEntity.affectFieldJSON`（**唯二写者** = 每轮 tick 与分析通道，都在 `AffectKernel` 的
 * per-uuid Mutex 内；I-3 列集与其它写者零重叠 ⇒ 列级盲写、不进 CharacterWriteLock）。
 * 空列 / 坏 JSON 同路回默认 `AffectField()`（图纸 K-11：本列无派生源可播种，默认值即正确兜底）。
 */
@Serializable
data class AffectField(
    val security: Int = 50,       // 安全感  0..100   慢场（周/月）·语义 = slowRefAt 时刻的参考值（修缮卷 J1）
    val investment: Int = 30,     // 投入度  0..100   慢场（周/月）·同上
    val valence: Int = 0,         // 效价   -100..100 快场（小时/天）
    val arousal: Int = 30,        // 激活度  0..100   快场（小时/天）
    val updatedAt: Long = 0L,
    val budgetDayStart: Long = 0L,  // 当日本地日起始 millis
    val budgetUsed: Int = 0,        // 当日已用位移预算
    /** K-12：最近一次分析的命中（`gNN` 系统项 + 字面 `bandUp`），最多 [MAX_HITS] 个，供算子 c07–c09 / c12 在 24h 内求值。 */
    val hits: List<String> = emptyList(),
    val hitsAt: Long = 0L,
    /** 修缮卷 J1：慢场参考值对应的时刻；0 = 旧数据（首次 tick / 分析置 now，参考值 = 当前列值）。 */
    val slowRefAt: Long = 0L,
    /** 修缮卷 J3：今日 `|Δ安全感|`、`|Δ投入度|` 已用（每场每日上限 [FIELD_DAY_CAP]·rollDay 归零）。 */
    val slowDayUsed: List<Int> = listOf(0, 0),
    /** 修缮卷 J9：卷零拉回已处理（按角色标记·随备份走；取代 SharedPreferences 全局戳）。 */
    val pullbackDone: Boolean = false,
    /**
     * 内心行换气（微图纸 2026-09-02）：安全感 / 投入度**读值**当前所在慢场档（下标 0 安全感 · 1 投入度；0 低 · 1 中 · 2 高 · −1 未知）。
     * 由 `AffectMath.trackSlowBands` 在 tick / 分析通道的既有那一次写里搭车更新；只供慢场句资格门，观测台不上屏。
     */
    val slowBands: List<Int> = listOf(-1, -1),
    /** 该档的进入时刻；0 = 未知 / 从未跨档（老列首次记档只记档不记时 ⇒ 不出慢场句）。 */
    val slowBandsAt: List<Long> = listOf(0L, 0L),
) {
    companion object {
        /** 修缮卷：6 → 9（hits 与 bandUp 合并后 `take(MAX_HITS)`；`GAIN_HITS_MAX = 8` + bandUp 恰 9）。 */
        const val MAX_HITS = 9
        const val BAND_UP = "bandUp"

        /**
         * 慢场每场每日位移上限 15（修缮卷 J3 锁定值）。与 [DAILY_BUDGET] 同理住在类型这边：解码钳位在 data/model 层、
         * 零依赖 prompt/；`RelationshipBands.FIELD_DAY_CAP` 是它的别名。
         */
        const val FIELD_DAY_CAP = 15

        /**
         * 日位移预算（图纸 §3.6 锁定值 40·净额单位）。住在类型这边而不是 `RelationshipBands`：解码钳位
         * （`GrowthJson.decodeAffectFieldOrNull` 把 `budgetUsed` 钳到 `0..DAILY_BUDGET`）在 data/model 层，
         * 该层至今零依赖 prompt/ 层，且 chunk 1 先于常量段落地（施工日志 §11 D-2）；`RelationshipBands.DAILY_BUDGET` 是它的别名。
         */
        const val DAILY_BUDGET = 40
    }
}

// MARK: - 意图队列（卷四《意图队列 + 性格复盘》· 图纸 §3.1 · 总图纸 §3.6）

/**
 * 六种意图（总图纸 §3.6 逐字）。[key] = 提示词 / JSON 里用的 key，与 `@SerialName` 字面逐字相等（`IntentRulesTest` 钉）。
 * 三要素（触发 / 半衰期 / 了结加成）与关键词表住 `prompt/growth/IntentRules`；本处只是封闭词表。
 */
@Serializable
enum class IntentKind {
    @SerialName("wantComfort") WANT_COMFORT,       // 想被哄
    @SerialName("wantApologize") WANT_APOLOGIZE,   // 想道歉
    @SerialName("wantProbe") WANT_PROBE,           // 想试探
    @SerialName("wantHide") WANT_HIDE,             // 想躲
    @SerialName("wantShare") WANT_SHARE,           // 想分享
    @SerialName("wantConfirm") WANT_CONFIRM,       // 想确认
    ;

    /** 提示词 / JSON 里用的 key（= @SerialName 值）。用 `when` 而不是反射读注解：kotlinx 不向运行时暴露 SerialName。 */
    val key: String
        get() = when (this) {
            WANT_COMFORT -> "wantComfort"
            WANT_APOLOGIZE -> "wantApologize"
            WANT_PROBE -> "wantProbe"
            WANT_HIDE -> "wantHide"
            WANT_SHARE -> "wantShare"
            WANT_CONFIRM -> "wantConfirm"
        }

    companion object {
        fun fromKey(key: String): IntentKind? = entries.firstOrNull { it.key == key.trim() }
    }
}

/** 五状态（总图纸 §3.6 逐字）：`BUDDING → ACTIVE → EXPRESSED → (RESOLVED | FADED)`。 */
@Serializable
enum class IntentState {
    @SerialName("budding") BUDDING,       // 萌生
    @SerialName("active") ACTIVE,         // 活跃
    @SerialName("expressed") EXPRESSED,   // 已表达（强度砍半不归零）
    @SerialName("resolved") RESOLVED,     // 已了结（完全释放 + 关系正压）
    @SerialName("faded") FADED,           // 消退（留残留）
}

/**
 * 一条意图（总图纸 §3.6 逐字）。[strength] 是**上次状态变化时**的值，读取时按半衰期惰性衰减
 * （`IntentRules.effectiveStrength`·卷四 K-3），只有状态变化才重写；[id] 只是主键（`UUID.randomUUID`），不参与任何数值。
 */
@Serializable
data class CharacterIntent(
    val id: String,
    val kind: IntentKind,
    val state: IntentState = IntentState.BUDDING,
    val strength: Int = 50,          // 0..100（上次状态变化时的值·读取时惰性衰减·K-3）
    val bornAt: Long = 0L,
    val lastChangeAt: Long = 0L,
    val residue: Boolean = false,    // FADED 后是否留「那件事没过去」
)

/** 卷四列 `intentQueueJSON` 的整体（K-1：队列 + 性格复盘计数同列；复盘已随修缮卷砍除，两个计数字段只兼容旧 JSON）。 */
@Serializable
data class IntentQueueState(
    val intents: List<CharacterIntent> = emptyList(),
    /** **已停用·兼容旧 JSON**（性格复盘已砍·修缮卷·用户 2026-09-02 拍板）：不再累加、不再消费，解码仍钳 `0..REVIEW_ROUNDS`。 */
    val reviewRoundsAccrued: Int = 0,
    /** **已停用·兼容旧 JSON**：上次复盘成功写回的时刻；生产不再写。 */
    val lastReviewAt: Long = 0L,
) {
    companion object {
        /** 解码时最多保留的条目数（live ≤3 + 冷却中 RESOLVED + 残留 FADED + 冷却中的无残留 FADED·内心行换气）；超出按 lastChangeAt 降序截（最老的冷却条目先走 ⇒ 只是提前重生，不崩）。 */
        const val MAX_STORED = 12

        /** 性格复盘周期（总图纸 §9.2 曾锁定 150·轮）。复盘已砍，本常量**只作解码钳位**用。 */
        const val REVIEW_ROUNDS = 150
    }
}
