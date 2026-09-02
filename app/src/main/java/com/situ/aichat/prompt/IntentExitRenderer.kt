package com.situ.aichat.prompt

import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.prompt.growth.IntentRules

/**
 * 五出口的选择 + 装配纯函数（活人感内核卷四图纸 §4.4 / §4.5）：同一个内在冲动从五个出口冒出来。
 *
 * - 聊天出口 [chatCandidate]：`InnerStateRenderer.render` 候选序第 2 位；无 live 时可退到残留句；台词按 `variant` 轮换（内心行换气）
 * - 四个非聊天出口各只出**最强的 1 条**（日程出口列全部 live、≤ [IntentRules.QUEUE_CAP] 条）；残留句只在聊天出口（K-19）；
 *   四个非聊天出口**不传 variant** ⇒ 文案恒为原文（微图纸外部行为 2）
 * - 无 live 意图 ⇒ 返回 `""` / `emptyList()` ⇒ 调用方不插任何行（外部行为清单 4）
 *
 * 全部用 [IntentRules.isLive] / [IntentRules.effectiveStrength] 只读判定，**渲染层零写、零数值再判定**（图纸 §9.4）。
 */
internal object IntentExitRenderer {

    /** live 条目按 effective 降序；同分取 [com.situ.aichat.data.model.IntentKind] 声明序靠前（§4.4）。 */
    private fun live(intents: List<CharacterIntent>, now: Long): List<CharacterIntent> =
        intents.filter { IntentRules.isLive(it, now) }
            .sortedWith(compareByDescending<CharacterIntent> { IntentRules.effectiveStrength(it, now) }.thenBy { it.kind.ordinal })

    /** §4.1：按 state 取活跃 / 已表达句；[variant] 只由聊天出口传（内心行换气），四个非聊天出口不传 = 恒 0 = 原文。 */
    private fun sentence(i: CharacterIntent, userName: String, variant: Int = 0): String =
        if (i.state == IntentState.EXPRESSED) IntentScripts.expressed(i.kind, userName, variant) else IntentScripts.active(i.kind, userName, variant)

    private fun topSentence(intents: List<CharacterIntent>, userName: String, now: Long, variant: Int = 0): String? =
        live(intents, now).firstOrNull()?.let { sentence(it, userName, variant) }

    /** 聊天出口选中的那条 live 意图的 kind（无 live ⇒ null）——`InnerStateRenderer` 的算子同源去重用它，与 [chatCandidate] 同一排序（修缮卷 R1 D-2）。 */
    internal fun strongestLiveKind(intents: List<CharacterIntent>, now: Long): IntentKind? = live(intents, now).firstOrNull()?.kind

    // MARK: - ① 聊天（§4.4）

    /**
     * 最强 live 的句子；无 live ⇒ 7 天内的残留 ⇒ [IntentScripts.residue]；否则 `null`。
     * [variant] = 台词变体（内心行换气微图纸·`AffectMath.scriptVariant`·默认 0 = 原文）——**只有本出口吃它**，四个非聊天出口不传。
     */
    fun chatCandidate(intents: List<CharacterIntent>, userName: String, now: Long, variant: Int = 0): String? {
        topSentence(intents, userName, now, variant)?.let { return it }
        val hasResidue = intents.any {
            it.state == IntentState.FADED && it.residue && now - it.lastChangeAt <= IntentRules.RESIDUE_TTL_MS
        }
        return if (hasResidue) IntentScripts.residue(variant) else null
    }

    // MARK: - ②④⑤ 朋友圈 / 交换日记 / 主动送礼（§4.5 · 两行 · 空 ⇒ ""）

    fun momentBlock(intents: List<CharacterIntent>, userName: String, now: Long): String =
        twoLineBlock(intents, userName, now, IntentScripts.MOMENT_TAIL)

    fun diaryBlock(intents: List<CharacterIntent>, userName: String, now: Long): String =
        twoLineBlock(intents, userName, now, IntentScripts.DIARY_TAIL)

    fun giftBlock(intents: List<CharacterIntent>, userName: String, now: Long): String =
        twoLineBlock(intents, userName, now, IntentScripts.GIFT_TAIL)

    private fun twoLineBlock(intents: List<CharacterIntent>, userName: String, now: Long, tail: String): String {
        val top = topSentence(intents, userName, now) ?: return ""
        return IntentScripts.HANGING_PREFIX + top + "\n" + tail
    }

    // MARK: - ③ 日程（§4.5 · 标题 + ≤3 条第三人称「TA」+ 尾行 · 空 ⇒ 空列表）

    fun scheduleLines(intents: List<CharacterIntent>, userName: String, now: Long): List<String> {
        val top = live(intents, now).take(IntentRules.QUEUE_CAP)
        if (top.isEmpty()) return emptyList()
        return buildList {
            add(IntentScripts.SCHEDULE_HEAD)
            for (i in top) add("- " + IntentScripts.thirdPerson(i.kind, "TA", userName))
            add(IntentScripts.scheduleTail(userName))
        }
    }
}
