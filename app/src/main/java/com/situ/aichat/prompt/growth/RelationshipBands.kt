package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.AffectField

/**
 * 全库「0–100 → 档」边界与名分平衡点的唯一定义点（活人感内核卷零 chunk1·只搬不改）。
 *
 * **本对象不统一语义**——四套边界服务四个不同用途（行为剧本渲染 / 原型二维渲染 / 触发敏感度 /
 * 日程独白频率），合并即改产品行为（图纸 Z-N4）。搬到一处是为了让分歧**可见**、让卷二/卷三只改一个文件。
 *
 * 搬迁纪律：逐值照搬现值、顺序不许调、消费点只把字面量换成常量引用（`when` 的比较符号一律不动）。
 * 保真钉 = `RelationshipBandsPinTest`。
 */
internal object RelationshipBands {
    /**
     * ① 行为剧本六档边界（[com.situ.aichat.prompt.buildCharacterGrowthContent] 的性格/关系描述共用）。
     * 语义：`< SCRIPT_LOW` 最低档，`< SCRIPT_MID_LOW` 次低，`[SCRIPT_SILENT_MIN, SCRIPT_SILENT_MAX]`
     * 静默（不输出省 token），`<= SCRIPT_HIGH` 次高，else 最高。
     */
    const val SCRIPT_LOW = 20
    const val SCRIPT_MID_LOW = 40
    const val SCRIPT_SILENT_MIN = 40
    const val SCRIPT_SILENT_MAX = 60
    const val SCRIPT_HIGH = 80

    /** ② 原型二维渲染水位三档 + 静默（`bandFor`）。 */
    const val WATER_L1_MAX = 0.30f
    const val WATER_SILENT_MAX = 0.65f
    const val WATER_L3_MAX = 0.90f

    /**
     * ③ 关系跃迁探测边界（`VoiceCallPostReplyRounds` 触发敏感度·**与渲染无关**）。
     * 原注释一并搬来：非均匀边界（= iOS），**末位 100 用于打破 96+ 饱和死锁**——勿删末位。
     */
    val CROSSING_BOUNDARIES = intArrayOf(10, 20, 30, 50, 70, 85, 95, 100)

    /** ④ 日程关系档位：取维 + 边界（`ScheduleLivenessPromptSections.relationshipTier`）。 */
    val TIER_DIMENSION_KEYS = listOf("familiarity", "trust", "closeness", "attachment")
    const val TIER_FAMILIAR_MAX = 25
    const val TIER_CLOSE_MAX = 50
    const val TIER_DEEP_MAX = 75

    /** ⑤ 名分 → 自然平衡点关键词表（搬自 [equilibriumPoint]·**顺序即优先级**，先匹配先返回）。 */
    val EQUILIBRIUM_INTIMATE = listOf("恋人", "热恋", "老夫老妻", "灵魂伴侣", "伴侣", "爱人", "lover", "partner", "soulmate")
    val EQUILIBRIUM_CLOSE = listOf("好朋友", "死党", "闺蜜", "知己", "暧昧", "损友", "best friend", "close friend")
    val EQUILIBRIUM_FRIEND = listOf("朋友", "普通朋友", "网友", "friend")
    val EQUILIBRIUM_DISTANT = listOf("陌生人", "点头之交", "stranger")
    const val EQUILIBRIUM_INTIMATE_VALUE = 70
    const val EQUILIBRIUM_CLOSE_VALUE = 55
    const val EQUILIBRIUM_FRIEND_VALUE = 40
    const val EQUILIBRIUM_DISTANT_VALUE = 20
    const val EQUILIBRIUM_DEFAULT_VALUE = 35

    // MARK: - 卷零止血常量（chunk2·跑飞棘轮封顶与泄压）

    /** 止血 H1：亲密压力（跷跷板规则1）能把张力推到的上限。到顶即不再推——**不下拉**。 */
    const val TENSION_INTERPLAY_CAP = 60

    /**
     * 止血 H2：信任-坦诚联动（跷跷板规则3）能把坦诚推到的上限 = 出厂初值 50 + 20（图纸 Z-1）。
     * **自卷三起**规则 3 与观测台改用 `anchor.openness + `[SPRING_BAND]（卷三图纸 §3.4）；本常量只剩两处消费：
     * ① 锚点列为空的未编译角色（K-4：Y-1 兜底让 anchor == current，天花板若跟现值走就是棘轮复活）；
     * ② [KernelPullback] 一次性历史扫（已跑过即戳，卷三 N-4 不动）。
     */
    const val OPENNESS_INTERPLAY_CAP = 70

    /** 止血 H3：每次成长分析张力恒定回落量。 */
    const val TENSION_RELIEF_PER_ANALYSIS = 2

    /** 张力回落地板（与 [computeDecayedQuality] 的 tension floor 同值·勿分叉）。 */
    const val TENSION_RELIEF_FLOOR = 5

    // MARK: - 卷二《正负双压》矛盾判定（chunk4·渲染层唯一新阈值）

    /**
     * 矛盾判定阈（**两侧同阈**）：某维正压与负压**都** `>= 55` 时输出矛盾句，并**跳过**该维的既有渲染。
     *
     * 这道判定加在两条渲染路各自的静默/分档**之前**，既有分支一行不动——所以无矛盾输入时输出逐字节不变。
     * 它解的正是那个实证：依恋 正80/负75 ⇒ 净额 5 ⇒ 恰好落进 40–60 静默区被筛掉，
     * 一个正在痛苦挣扎的人被渲染成「你对ta没有依恋」。
     */
    const val PRESSURE_CONTRADICTION_MIN = 55

    /**
     * 一次最多输出几条矛盾句。8 维全矛盾时输出 8 句同尾巴的长句 = 稀释
     * （复杂度该花在算出**该说哪两句**，而不是说得更多）；其余矛盾维回落各自既有渲染。
     */
    const val PRESSURE_CONTRADICTION_MAX_LINES = 2

    // MARK: - 卷三《场内核与渲染收编》四场常量与节律（图纸 §3.6·**锁定值**·消费点 = AffectMath / AffectKernel / InnerStateRenderer / AttentionJudge）

    /** 饱和位移上限：`saturate(raw) = sign(raw) × round(6·tanh(|raw|/6))`（总图纸保险 2；`|raw| < 0.5 ⇒ 0` 即「废保底」）。 */
    const val SATURATE_MAX_STEP = 6.0

    /** 锚点弹簧系数：每次分析把性格维往锚点拉回 `round(0.15 × |x − anchor|)`（保险 4）。 */
    const val SPRING_K = 0.15

    /** 离锚硬上限 ±20（界内钳住、界外只许往里·K-5）；规则 3 天花板 = `anchor.openness + SPRING_BAND`。 */
    const val SPRING_BAND = 20

    /** 日位移预算 40（净额单位·只管分析通道 16 维·K-3）。单源住 [AffectField.DAILY_BUDGET]（解码钳位在 data/model 层），这里是别名。 */
    const val DAILY_BUDGET = AffectField.DAILY_BUDGET

    /** 投影基量：`saturate(coef × BASE_HIT × gainFactor(level))`。 */
    const val BASE_HIT = 10.0

    /** 单场单次分析位移上限（逐命中饱和后求和再钳）。 */
    const val FIELD_STEP_CAP = 12

    /** 效价半衰期 24h（→ 0：「昨天的余温」次日还剩一半）。 */
    const val VALENCE_HALF_LIFE_MS = 24L * 3_600_000L

    /** 激活半衰期 4h（→ `arousalBaseline(hour)`）。 */
    const val AROUSAL_HALF_LIFE_MS = 4L * 3_600_000L

    /** 慢场半衰期 30d（安全感 → 50 · 投入度 → 30）。 */
    const val SLOW_HALF_LIFE_MS = 30L * 86_400_000L

    /** 松弛取整原地不动且 dt ≥ 1h 时向目标走 1（防长尾永远差一格）。**修缮卷起只对快场**（半衰 ≤ [RELAX_SNAP_HALF_LIFE_MAX_MS]）。 */
    const val RELAX_SNAP_MS = 3_600_000L

    /** 修缮卷 J1：补步只对半衰期 ≤ 24h 的场（慢场永不补步——慢场改惰性参考值，tick 不碰）。 */
    const val RELAX_SNAP_HALF_LIFE_MAX_MS = 24L * 3_600_000L

    /** 修缮卷 J3：慢场每场每日 |Δ| 上限 15（分析通道落用前钳）。单源住 [AffectField.FIELD_DAY_CAP]，这里是别名。 */
    const val FIELD_DAY_CAP = AffectField.FIELD_DAY_CAP

    /** 修缮卷：跷跷板规则 3（信任 → 坦诚）与卷零拉回坦诚维共用的信任门槛（原字面 `70`）。 */
    const val OPENNESS_INTERPLAY_TRUST_MIN = 70

    /** 每轮聊天激活 +2，**仅当** `arousal < arousalBaseline(hour) + 30`（白天上限 74 < 句子阈 75）。 */
    const val AROUSAL_PULSE = 2

    /** 算子 c10「夜深了就你一个人」的激活阈。 */
    const val AROUSAL_LOW = 25

    /** D2：自述要推翻日程须 `arousal ≥ 20`（精力只当低权重闸·K-10）。 */
    const val AROUSAL_AWAKE_MIN = 20

    /** 算子 c11「现在情绪很差」的效价阈。 */
    const val VALENCE_BAD = -40

    /** 命中有效期 24h（K-12：c07–c09 / c12 只在最近一次分析后 24h 内成立）。 */
    const val HITS_TTL_MS = 24L * 3_600_000L

    /** 日倾幅度：效价 `nextInt(-6, 7)`、激活 `nextInt(-4, 5)`（K-9）。 */
    const val TILT_VALENCE = 6
    const val TILT_AROUSAL = 4

    /** D2 自述取材：最近 3 轮、3h 内的角色行。 */
    const val SELF_REPORT_ROUNDS = 3
    const val SELF_REPORT_TTL_MS = 3L * 3_600_000L

    // MARK: - 内心行换气（微图纸 2026-09-02·**锁定值**·消费点 = IntentKernel / AffectMath / InnerStateRenderer）

    /** 意图消退（FADED）后同 kind 冷却 3 天：期内不再萌生；无残留的 FADED 也保留到期满供判定（`IntentRules.FADE_COOLDOWN_MS` 是别名）。 */
    const val FADE_COOLDOWN_MS = 72L * 3_600_000L

    /** 慢场句（安全感 / 投入度）只在跨档后 3 天内参与场句评分，之后让位给当天的心情 / 精力句。 */
    const val SLOW_SENTENCE_TTL_MS = 72L * 3_600_000L

    /** 聊天出口台词按本地日每 3 天换一个变体（`AffectMath.scriptVariant`）；四个非聊天出口恒变体 0。 */
    const val SCRIPT_ROTATE_DAYS = 3

    /** 每句台词的变体数（变体 0 = 卷三 / 卷四原文逐字）。 */
    const val SCRIPT_VARIANTS = 3

    /**
     * 慢场档界（= 卷三 §4.1 场句阈值·`InnerStateRenderer.fieldSentence` 与 `AffectMath.trackSlowBands` 同源）：
     * 安全感 `≤ 30` 低 / `≥ 80` 高；投入度 `≤ 10` 低 / `≥ 80` 高。改任一侧必须同步另一侧——档与句子对不上就会「跨档了却出不了句」。
     */
    const val SLOW_SECURITY_LOW_MAX = 30
    const val SLOW_SECURITY_HIGH_MIN = 80
    const val SLOW_INVESTMENT_LOW_MAX = 10
    const val SLOW_INVESTMENT_HIGH_MIN = 80
}
