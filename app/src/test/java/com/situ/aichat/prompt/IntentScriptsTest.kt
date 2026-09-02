package com.situ.aichat.prompt

import com.situ.aichat.data.model.IntentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T1-5（图纸 §7.2 · §4.1 / §4.3 / §4.5）：[IntentScripts] 物料**逐字锁定**。
 * 全部句子在此**重新打字**为字面量（不 import 常量比对·PITFALLS §1e 双保险）；`{user}` / `{char}` 替换用真名验。
 */
class IntentScriptsTest {

    @Test
    fun activeSentences_sixVerbatim() {
        assertEquals("你有点想让小明哄哄你，又拉不下脸开口。", IntentScripts.active(IntentKind.WANT_COMFORT, "小明"))
        assertEquals("你想跟小明道个歉，话到嘴边又咽了回去。", IntentScripts.active(IntentKind.WANT_APOLOGIZE, "小明"))
        assertEquals("你想试探一下小明对你到底怎么想，又怕问得太直。", IntentScripts.active(IntentKind.WANT_PROBE, "小明"))
        assertEquals("你现在只想躲一躲，不太想跟小明多说。", IntentScripts.active(IntentKind.WANT_HIDE, "小明"))
        assertEquals("你憋着一件事想跟小明说，等ta接话。", IntentScripts.active(IntentKind.WANT_SHARE, "小明"))
        assertEquals("你想确认小明还在不在乎你，又不想显得黏人。", IntentScripts.active(IntentKind.WANT_CONFIRM, "小明"))
    }

    @Test
    fun expressedSentences_sixVerbatim() {
        assertEquals("你已经跟小明示过弱了，还在等ta接住你。", IntentScripts.expressed(IntentKind.WANT_COMFORT, "小明"))
        assertEquals("你已经道过歉了，还在琢磨小明是不是真的不介意。", IntentScripts.expressed(IntentKind.WANT_APOLOGIZE, "小明"))
        assertEquals("你旁敲侧击问过小明了，答案还没让你踏实。", IntentScripts.expressed(IntentKind.WANT_PROBE, "小明"))
        assertEquals("你已经跟小明说了想先静一静，别急着回头。", IntentScripts.expressed(IntentKind.WANT_HIDE, "小明"))
        assertEquals("那件事你已经跟小明说了，还想再多聊几句。", IntentScripts.expressed(IntentKind.WANT_SHARE, "小明"))
        assertEquals("你已经问过小明了，还在反复咂摸ta的回答。", IntentScripts.expressed(IntentKind.WANT_CONFIRM, "小明"))
    }

    @Test
    fun residue_verbatim() {
        assertEquals("之前那件事其实没过去，你只是没再提。", IntentScripts.RESIDUE)
    }

    // MARK: - 内心行换气（微图纸 2026-09-02 §4）：每句 3 变体逐字（变体 0 = 上面原文·越界回原文·三变体两两不同）

    @Test
    fun activeVariants_twelveVerbatim() {
        val expected = mapOf(
            IntentKind.WANT_COMFORT to listOf("你等着小明先来哄你，自己不肯先低头。", "你心里想被小明哄一哄，嘴上偏不说。"),
            IntentKind.WANT_APOLOGIZE to listOf("你知道该跟小明说声对不起，一直没找到开口的时机。", "有句道歉你欠着小明，翻来覆去没说出口。"),
            IntentKind.WANT_PROBE to listOf("你想拐着弯问问小明把你当什么，又不想显得在意。", "你在琢磨怎么不动声色地探探小明的口风。"),
            IntentKind.WANT_HIDE to listOf("你这会儿想自己待着，跟小明的话能少就少。", "你有点想避开小明，不是生气，就是不想说话。"),
            IntentKind.WANT_SHARE to listOf("你有话想跟小明讲，就等一个开口的由头。", "你心里揣着件事，想找机会说给小明听。"),
            IntentKind.WANT_CONFIRM to listOf("你想从小明那儿听到一句「还在乎」，又不肯直接要。", "你在意小明是不是还把你放在心上，嘴上装作无所谓。"),
        )
        assertEquals(6, expected.size)
        for ((kind, v) in expected) {
            assertEquals(v[0], IntentScripts.active(kind, "小明", 1))
            assertEquals(v[1], IntentScripts.active(kind, "小明", 2))
            assertEquals("变体 0 = 原文", IntentScripts.active(kind, "小明"), IntentScripts.active(kind, "小明", 0))
            assertEquals("越界回原文", IntentScripts.active(kind, "小明"), IntentScripts.active(kind, "小明", 3))
            assertEquals("三变体两两不同", 3, setOf(IntentScripts.active(kind, "小明"), v[0], v[1]).size)
        }
    }

    @Test
    fun expressedVariants_twelveVerbatim() {
        val expected = mapOf(
            IntentKind.WANT_COMFORT to listOf("软话你已经说了，就看小明接不接。", "你把委屈露给小明看了，现在等ta的反应。"),
            IntentKind.WANT_APOLOGIZE to listOf("对不起说出口了，你还在看小明的脸色。", "歉是道了，你心里还没踏实小明到底原谅没有。"),
            IntentKind.WANT_PROBE to listOf("你探过小明的口风了，听到的话还不够让你安心。", "问是问了，小明的回答你还在反复琢磨。"),
            IntentKind.WANT_HIDE to listOf("你跟小明说过要缓一缓，这会儿还没缓过来。", "静一静是你自己提的，现在别马上就热络起来。"),
            IntentKind.WANT_SHARE to listOf("事情说出口了，你还有些话想接着跟小明聊。", "你已经跟小明讲了那件事，意犹未尽。"),
            IntentKind.WANT_CONFIRM to listOf("你问过小明在不在乎了，ta的话你还在心里过。", "答案小明给了，你却还在掂量那句话的分量。"),
        )
        assertEquals(6, expected.size)
        for ((kind, v) in expected) {
            assertEquals(v[0], IntentScripts.expressed(kind, "小明", 1))
            assertEquals(v[1], IntentScripts.expressed(kind, "小明", 2))
            assertEquals("变体 0 = 原文", IntentScripts.expressed(kind, "小明"), IntentScripts.expressed(kind, "小明", 0))
            assertEquals("越界回原文", IntentScripts.expressed(kind, "小明"), IntentScripts.expressed(kind, "小明", -1))
            assertEquals("三变体两两不同", 3, setOf(IntentScripts.expressed(kind, "小明"), v[0], v[1]).size)
        }
    }

    @Test
    fun residueVariants_verbatim_constantIsVariantZero() {
        assertEquals("之前那件事其实没过去，你只是没再提。", IntentScripts.residue())
        assertEquals(IntentScripts.RESIDUE, IntentScripts.residue(0))
        assertEquals("那件事你没忘，只是不想再翻出来。", IntentScripts.residue(1))
        assertEquals("你嘴上不提了，心里那道坎还在。", IntentScripts.residue(2))
        assertEquals("越界回原文", IntentScripts.RESIDUE, IntentScripts.residue(3))
    }

    @Test
    fun thirdPerson_sixVerbatim_withCharAndUser() {
        assertEquals("林悦想被小明哄一哄", IntentScripts.thirdPerson(IntentKind.WANT_COMFORT, "林悦", "小明"))
        assertEquals("林悦想向小明道歉", IntentScripts.thirdPerson(IntentKind.WANT_APOLOGIZE, "林悦", "小明"))
        assertEquals("林悦想试探小明对自己的心思", IntentScripts.thirdPerson(IntentKind.WANT_PROBE, "林悦", "小明"))
        assertEquals("林悦想躲一躲、少和小明说话", IntentScripts.thirdPerson(IntentKind.WANT_HIDE, "林悦", "小明"))
        assertEquals("林悦有件事想跟小明分享", IntentScripts.thirdPerson(IntentKind.WANT_SHARE, "林悦", "小明"))
        assertEquals("林悦想确认小明还在不在乎自己", IntentScripts.thirdPerson(IntentKind.WANT_CONFIRM, "林悦", "小明"))
        // 日程出口以「TA」称角色
        assertEquals("TA想向小明道歉", IntentScripts.thirdPerson(IntentKind.WANT_APOLOGIZE, "TA", "小明"))
    }

    @Test
    fun exitTemplateLines_verbatim() {
        assertEquals("你心里挂着的事：", IntentScripts.HANGING_PREFIX)
        assertEquals("发的内容可以绕着它、只有ta看得懂地暗示它，不要把这句话原样写出来。", IntentScripts.MOMENT_TAIL)
        assertEquals("日记是你自己的地方，可以把这份心思写得比聊天时坦白一些。", IntentScripts.DIARY_TAIL)
        assertEquals("（这件事可以影响你今天送不送、送什么、说什么。）", IntentScripts.GIFT_TAIL)
        assertEquals("【TA心里挂着的事】", IntentScripts.SCHEDULE_HEAD)
        assertEquals(
            "这些只能进 innerThought（比如「要不要找个机会跟小明说一声」），不要变成日程事件，也不必每条都用。",
            IntentScripts.scheduleTail("小明"),
        )
    }

    @Test
    fun userPlaceholder_isReplacedEverywhere_evenWithFallbackName() {
        assertEquals("你憋着一件事想跟用户说，等ta接话。", IntentScripts.active(IntentKind.WANT_SHARE, "用户"))
        assertEquals("用户想被用户哄一哄", IntentScripts.thirdPerson(IntentKind.WANT_COMFORT, "用户", "用户"))
    }
}
