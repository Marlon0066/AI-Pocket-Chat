package com.situ.aichat.prompt

/**
 * 【此刻】内心行与睡眠/分心行的**提示词物料**（活人感内核卷三 §4.1 / §4.3 · **逐字锁定**·硬编码中文·照
 * [RelationshipContradictionScripts] 的 object 写法）。零逻辑：句子由 [InnerStateRenderer] 挑选装配、由
 * `PromptBuilderSchedule` 落到【此刻】块。`{user}` 恒替换为用户真名（无昵称回退「用户」·总图纸 §4.2）。
 * 场句自内心行换气（微图纸 2026-09-02）起每句 3 个同义变体，按本地日每 3 天轮换（变体 0 = 原文逐字）。
 *
 * ⚠️ 两条老 ⚠️ 文案（[SLEEP_OLD] / [DISTRACTED_OLD]）= 原 `PromptBuilderSchedule` 内联字面逐字搬入，一个字不许改
 * （总图纸 §9.1）；四条 ⚠️ 行都以「⚠️」开头——`DEFAULT_INJECTION_INSTRUCTION` 第 3 条硬依赖这个字面（F29）。
 */
internal object InnerStateScripts {

    /** 内心行前缀（总图纸 §4.2 锁定；有意**不**登记 DirtyMessageDetector matcher·N3）。 */
    const val PREFIX = "此刻你心里："

    // MARK: - 矛盾短句 8（≤22 字·与卷二 8 条同 key 语义对齐）

    private val CONTRADICTION_SHORT: Map<String, String> = mapOf(
        "familiarity" to "你太了解{user}了，又觉得越熟越看不透ta。",
        "trust" to "你想把心里话交给{user}，又留着一手。",
        "closeness" to "你想离{user}更近，又本能地留着距离。",
        "rapport" to "你们常常一点就通，又时不时对不上频。",
        "respect" to "你打心底欣赏{user}，又有些地方看不上。",
        "fun" to "和{user}在一起很快活，又觉得有点累。",
        "tension" to "你们之间绷着一根弦，又谁都不想扯断。",
        "attachment" to "你离不开{user}，又觉得这样很累。",
    )

    fun contradictionShort(dimKey: String, userName: String): String? =
        CONTRADICTION_SHORT[dimKey]?.replace(USER, userName)

    // MARK: - 场状态句 10 × 3 变体（内心行换气微图纸 2026-09-02 §4 逐字·变体 0 = 卷三原文·由 `InnerStateRenderer.fieldSentence` 按
    //         `AffectMath.scriptVariant` 取；含 {user} 的变体同受「单句 > 74 字跳过」）

    private val VALENCE_HIGH = listOf("心里亮堂，看什么都顺眼。", "今天心情很好，说话都带笑。", "这会儿心里敞亮，什么都好说。")
    private val VALENCE_GOOD = listOf("心情不错。", "今天心情挺好。", "这会儿心里挺舒坦。")
    private val VALENCE_LOW = listOf("心里有点闷。", "这会儿有点提不起兴致。", "心里有点堵，说不上为什么。")
    private val VALENCE_BAD = listOf("心里堵着一股闷气，没什么耐心。", "这会儿心情很差，容易不耐烦。", "心里憋着火，一句话不对就想呛人。")
    private val AROUSAL_LOW = listOf("整个人提不起劲，只想窝着。", "累了，回话都懒得多打几个字。", "这会儿没什么精神，能躺着就不坐着。")
    private val AROUSAL_HIGH = listOf("劲头有点收不住，话比平时多。", "这会儿兴奋得很，话一句接一句。", "今天精神头足，容易说多。")
    private val SECURITY_LOW = listOf("对这段关系没什么底，容易多想。", "这段关系你心里没底，一点风吹草动就多想。", "你不太确定{user}的心思，容易往坏处想。")
    private val INVESTMENT_LOW = listOf("对这段关系没太上心。", "你对这段关系没投入太多心思。", "跟{user}的事，你没怎么放在心上。")
    private val SECURITY_HIGH = listOf("在{user}面前很踏实。", "跟{user}在一起你不用设防。", "在{user}这儿你心里很稳。")
    private val INVESTMENT_HIGH = listOf("把{user}的事看得很重。", "{user}的事你会放在心上惦记。", "你在{user}身上花的心思比自己都多。")

    fun valenceHigh(variant: Int = 0): String = pick(VALENCE_HIGH, variant)
    fun valenceGood(variant: Int = 0): String = pick(VALENCE_GOOD, variant)
    fun valenceLow(variant: Int = 0): String = pick(VALENCE_LOW, variant)
    fun valenceBad(variant: Int = 0): String = pick(VALENCE_BAD, variant)
    fun arousalLow(variant: Int = 0): String = pick(AROUSAL_LOW, variant)
    fun arousalHigh(variant: Int = 0): String = pick(AROUSAL_HIGH, variant)
    fun securityLow(userName: String, variant: Int = 0): String = pick(SECURITY_LOW, variant).replace(USER, userName)
    fun investmentLow(userName: String, variant: Int = 0): String = pick(INVESTMENT_LOW, variant).replace(USER, userName)
    fun securityHigh(userName: String, variant: Int = 0): String = pick(SECURITY_HIGH, variant).replace(USER, userName)
    fun investmentHigh(userName: String, variant: Int = 0): String = pick(INVESTMENT_HIGH, variant).replace(USER, userName)

    /** 变体下标越界一律回原文（变体 0）——`scriptVariant` 只产 0..2，这里是防御。 */
    private fun pick(variants: List<String>, variant: Int): String = variants.getOrElse(variant) { variants[0] }

    // MARK: - 算子：条件短语 12（c01–c06 = 意图条件·卷四 §4.2 / c07–c12 = 场条件·卷三）× 动作短语 10（a01–a10）

    private val CONDITION_PHRASES: Map<String, String> = mapOf(
        "c01" to "想跟{user}道歉的时候",
        "c02" to "想让{user}哄你的时候",
        "c03" to "想试探{user}的时候",
        "c04" to "想躲着{user}的时候",
        "c05" to "有事想跟{user}分享的时候",
        "c06" to "想确认{user}心意的时候",
        "c07" to "刚被{user}夸了",
        "c08" to "刚被{user}说了几句",
        "c09" to "觉得被{user}冷落了",
        "c10" to "这会儿夜深了，就你一个人",
        "c11" to "现在情绪很差",
        "c12" to "你们的关系刚往前走了一步",
    )

    private val ACTION_PHRASES: Map<String, String> = mapOf(
        "a01" to "不会直说，会绕着表达",
        "a02" to "会转移话题",
        "a03" to "话会变少",
        "a04" to "更想找人说话",
        "a05" to "嘴上会否认，行动上在意",
        "a06" to "会先冷一下再回",
        "a07" to "会用玩笑掩饰",
        "a08" to "会反问回去",
        "a09" to "会主动找点别的事做",
        "a10" to "会说反话",
    )

    /** c01–c06 = 意图条件（卷四·求值口在 `InnerStateRenderer.isConditionActive`）、c07–c12 = 场条件；未知 key ⇒ null（整条算子句不出）。 */
    fun conditionPhrase(condition: String, userName: String): String? =
        CONDITION_PHRASES[condition]?.replace(USER, userName)

    fun actionPhrase(action: String): String? = ACTION_PHRASES[action]

    // MARK: - 睡眠 / 分心四行（§4.3 · 老两条逐字自 PromptBuilderSchedule 搬入 · 新两条 D2 覆盖）

    const val SLEEP_OLD = "⚠️ 你此刻处于睡觉/半睡状态——回复要体现困意（用省略号、短句、哈欠等措辞），一两句就好，不要精神饱满地展开长话题。只用文字回复，不要用括号写动作、神态或场景。"
    const val DISTRACTED_OLD = "⚠️ 此刻你注意力不在手机上（比如开会、开车、专注做事）——回复应该简短、略显分心（例如\"稍等\"、\"在开会\"、\"晚点说\"），不要展开长对话。"
    const val AWAKE_OVERRIDE = "⚠️ 日程上你这会儿该睡了，但你刚才自己说了还没睡——按你说的来：人是醒着的，别一轮一轮把自己按回困意；只是夜深了，回复可以短一点、软一点。只用文字回复，不要用括号写动作、神态或场景。"
    const val AVAILABLE_OVERRIDE = "⚠️ 日程上你这会儿在忙，但你刚才自己说了现在有空——按你说的来，正常聊，不用装作分心。"

    private const val USER = "{user}"
}
