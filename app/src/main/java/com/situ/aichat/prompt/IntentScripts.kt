package com.situ.aichat.prompt

import com.situ.aichat.data.model.IntentKind

/**
 * 意图的**提示词物料**（活人感内核卷四图纸 §4.1 / §4.3 / §4.5 · **逐字锁定**·硬编码中文·照 [InnerStateScripts] 的
 * object 写法）。零逻辑：句子由 [IntentExitRenderer] 挑选装配、由五个出口落到各自提示词。
 * `{user}` 恒替换为用户真名（无昵称回退「用户」）；`{char}` = 角色名或「TA」（日程出口全篇以 TA 称角色）。
 */
internal object IntentScripts {

    // MARK: - 意图句 12 × 3 变体（§4.1 · 你 = 角色 · 内心行换气微图纸 2026-09-02 §4 逐字·变体 0 = 卷四原文·只在聊天出口轮换）

    private val ACTIVE: Map<IntentKind, List<String>> = mapOf(
        IntentKind.WANT_COMFORT to listOf("你有点想让{user}哄哄你，又拉不下脸开口。", "你等着{user}先来哄你，自己不肯先低头。", "你心里想被{user}哄一哄，嘴上偏不说。"),
        IntentKind.WANT_APOLOGIZE to listOf("你想跟{user}道个歉，话到嘴边又咽了回去。", "你知道该跟{user}说声对不起，一直没找到开口的时机。", "有句道歉你欠着{user}，翻来覆去没说出口。"),
        IntentKind.WANT_PROBE to listOf("你想试探一下{user}对你到底怎么想，又怕问得太直。", "你想拐着弯问问{user}把你当什么，又不想显得在意。", "你在琢磨怎么不动声色地探探{user}的口风。"),
        IntentKind.WANT_HIDE to listOf("你现在只想躲一躲，不太想跟{user}多说。", "你这会儿想自己待着，跟{user}的话能少就少。", "你有点想避开{user}，不是生气，就是不想说话。"),
        IntentKind.WANT_SHARE to listOf("你憋着一件事想跟{user}说，等ta接话。", "你有话想跟{user}讲，就等一个开口的由头。", "你心里揣着件事，想找机会说给{user}听。"),
        IntentKind.WANT_CONFIRM to listOf("你想确认{user}还在不在乎你，又不想显得黏人。", "你想从{user}那儿听到一句「还在乎」，又不肯直接要。", "你在意{user}是不是还把你放在心上，嘴上装作无所谓。"),
    )

    private val EXPRESSED: Map<IntentKind, List<String>> = mapOf(
        IntentKind.WANT_COMFORT to listOf("你已经跟{user}示过弱了，还在等ta接住你。", "软话你已经说了，就看{user}接不接。", "你把委屈露给{user}看了，现在等ta的反应。"),
        IntentKind.WANT_APOLOGIZE to listOf("你已经道过歉了，还在琢磨{user}是不是真的不介意。", "对不起说出口了，你还在看{user}的脸色。", "歉是道了，你心里还没踏实{user}到底原谅没有。"),
        IntentKind.WANT_PROBE to listOf("你旁敲侧击问过{user}了，答案还没让你踏实。", "你探过{user}的口风了，听到的话还不够让你安心。", "问是问了，{user}的回答你还在反复琢磨。"),
        IntentKind.WANT_HIDE to listOf("你已经跟{user}说了想先静一静，别急着回头。", "你跟{user}说过要缓一缓，这会儿还没缓过来。", "静一静是你自己提的，现在别马上就热络起来。"),
        IntentKind.WANT_SHARE to listOf("那件事你已经跟{user}说了，还想再多聊几句。", "事情说出口了，你还有些话想接着跟{user}聊。", "你已经跟{user}讲了那件事，意犹未尽。"),
        IntentKind.WANT_CONFIRM to listOf("你已经问过{user}了，还在反复咂摸ta的回答。", "你问过{user}在不在乎了，ta的话你还在心里过。", "答案{user}给了，你却还在掂量那句话的分量。"),
    )

    /** 活跃（BUDDING / ACTIVE）句；[variant] 只由聊天出口传（`AffectMath.scriptVariant`·0..2·越界回原文），四个非聊天出口恒 0。 */
    fun active(kind: IntentKind, userName: String, variant: Int = 0): String = pick(ACTIVE.getValue(kind), variant).replace(USER, userName)

    /** 已表达（EXPRESSED）句（[variant] 同上）。 */
    fun expressed(kind: IntentKind, userName: String, variant: Int = 0): String = pick(EXPRESSED.getValue(kind), variant).replace(USER, userName)

    /** 残留句（FADED ∧ residue · 7 天内 · **只在聊天出口**）；[RESIDUE] 常量保留 = 变体 0，轮换取值口 = [residue]。 */
    const val RESIDUE = "之前那件事其实没过去，你只是没再提。"
    private val RESIDUE_VARIANTS = listOf(RESIDUE, "那件事你没忘，只是不想再翻出来。", "你嘴上不提了，心里那道坎还在。")

    fun residue(variant: Int = 0): String = pick(RESIDUE_VARIANTS, variant)

    /** 变体下标越界一律回原文（变体 0）——防御，`scriptVariant` 只产 0..2。 */
    private fun pick(variants: List<String>, variant: Int): String = variants.getOrElse(variant) { variants[0] }

    // MARK: - 第三人称短句 6（§4.3 · 双名 · 分析提示词 / growthLog / 日程出口共用）

    private val THIRD_PERSON: Map<IntentKind, String> = mapOf(
        IntentKind.WANT_COMFORT to "{char}想被{user}哄一哄",
        IntentKind.WANT_APOLOGIZE to "{char}想向{user}道歉",
        IntentKind.WANT_PROBE to "{char}想试探{user}对自己的心思",
        IntentKind.WANT_HIDE to "{char}想躲一躲、少和{user}说话",
        IntentKind.WANT_SHARE to "{char}有件事想跟{user}分享",
        IntentKind.WANT_CONFIRM to "{char}想确认{user}还在不在乎自己",
    )

    fun thirdPerson(kind: IntentKind, charName: String, userName: String): String =
        THIRD_PERSON.getValue(kind).replace(CHAR, charName).replace(USER, userName)

    // MARK: - 四个非聊天出口的模板行（§4.5 · 逐字锁定）

    /** 朋友圈 / 交换日记 / 礼物三出口共用的首行前缀，后接 §4.1 的句子。 */
    const val HANGING_PREFIX = "你心里挂着的事："
    const val MOMENT_TAIL = "发的内容可以绕着它、只有ta看得懂地暗示它，不要把这句话原样写出来。"
    const val DIARY_TAIL = "日记是你自己的地方，可以把这份心思写得比聊天时坦白一些。"
    const val GIFT_TAIL = "（这件事可以影响你今天送不送、送什么、说什么。）"

    /** 日程出口标题（有意**不**登记 DirtyMessageDetector matcher·N-9：日程输出是 JSON 不回流聊天）。 */
    const val SCHEDULE_HEAD = "【TA心里挂着的事】"

    fun scheduleTail(userName: String): String =
        "这些只能进 innerThought（比如「要不要找个机会跟{user}说一声」），不要变成日程事件，也不必每条都用。".replace(USER, userName)

    private const val USER = "{user}"
    private const val CHAR = "{char}"
}
