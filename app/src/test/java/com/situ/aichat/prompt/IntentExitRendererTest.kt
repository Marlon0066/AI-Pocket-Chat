package com.situ.aichat.prompt

import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T1-5（图纸 §7.2 · §4.4 / §4.5 · K-19）：五出口的选择与装配。
 *
 * 断言从图纸独立反推（句子重新打字）：
 * - 聊天：取 effective 最强（同分取 IntentKind 声明序）/ EXPRESSED 变体 / 残留只在无 live 时 / 过期残留（>7d）不出 / RESOLVED 不出
 * - 朋友圈 / 日记 / 礼物：两行逐字、只出最强 1 条、空 ⇒ `""`
 * - 日程：标题 + ≤3 条 `- TA想…` + 尾行逐字、空 ⇒ 空列表
 */
class IntentExitRendererTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun intent(
        kind: IntentKind,
        state: IntentState = IntentState.ACTIVE,
        strength: Int = 50,
        bornAt: Long = now,
        lastChangeAt: Long = now,
        residue: Boolean = false,
    ) = CharacterIntent(id = kind.key, kind = kind, state = state, strength = strength, bornAt = bornAt, lastChangeAt = lastChangeAt, residue = residue)

    // MARK: - ① 聊天

    @Test
    fun chat_picksStrongestLive_activeSentence() {
        val out = IntentExitRenderer.chatCandidate(
            listOf(intent(IntentKind.WANT_COMFORT, strength = 40), intent(IntentKind.WANT_APOLOGIZE, strength = 55)),
            "小明", now,
        )
        assertEquals("你想跟小明道个歉，话到嘴边又咽了回去。", out)
    }

    @Test
    fun chat_expressedVariant() {
        val out = IntentExitRenderer.chatCandidate(listOf(intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED, strength = 25)), "小明", now)
        assertEquals("你已经道过歉了，还在琢磨小明是不是真的不介意。", out)
    }

    @Test
    fun chat_tieBreaksByKindDeclarationOrder() {
        // 同分 50：想确认（声明序 6）与想被哄（声明序 1）⇒ 想被哄
        val out = IntentExitRenderer.chatCandidate(
            listOf(intent(IntentKind.WANT_CONFIRM, strength = 50), intent(IntentKind.WANT_COMFORT, strength = 50)),
            "小明", now,
        )
        assertEquals("你有点想让小明哄哄你，又拉不下脸开口。", out)
    }

    @Test
    fun chat_residueOnlyWhenNoLive_andWithinSevenDays() {
        val residue = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 2 * day, residue = true)
        assertEquals("之前那件事其实没过去，你只是没再提。", IntentExitRenderer.chatCandidate(listOf(residue), "小明", now))
        // 有 live 时残留让位
        assertEquals(
            "你现在只想躲一躲，不太想跟小明多说。",
            IntentExitRenderer.chatCandidate(listOf(residue, intent(IntentKind.WANT_HIDE)), "小明", now),
        )
        // 过期残留（7 天 + 1ms）不出
        assertNull(IntentExitRenderer.chatCandidate(listOf(residue.copy(lastChangeAt = now - 7 * day - 1)), "小明", now))
        // FADED 但没留残留 / RESOLVED ⇒ 不出
        assertNull(IntentExitRenderer.chatCandidate(listOf(residue.copy(residue = false)), "小明", now))
        assertNull(IntentExitRenderer.chatCandidate(listOf(intent(IntentKind.WANT_SHARE, state = IntentState.RESOLVED, strength = 0)), "小明", now))
        assertNull(IntentExitRenderer.chatCandidate(emptyList(), "小明", now))
    }

    @Test
    fun chat_decayedBelowFifteen_isNotLive() {
        // 想被哄 24h 半衰：60 经 3 天 ⇒ 8 < 15 ⇒ 不出
        assertNull(IntentExitRenderer.chatCandidate(listOf(intent(IntentKind.WANT_COMFORT, strength = 60, bornAt = now - 3 * day, lastChangeAt = now - 3 * day)), "小明", now))
    }

    // MARK: - ②④⑤ 两行块

    private val apologyAndComfort = listOf(intent(IntentKind.WANT_APOLOGIZE, strength = 55), intent(IntentKind.WANT_COMFORT, strength = 40))

    @Test
    fun momentBlock_twoLinesVerbatim_onlyStrongest() {
        assertEquals(
            "你心里挂着的事：你想跟小明道个歉，话到嘴边又咽了回去。\n发的内容可以绕着它、只有ta看得懂地暗示它，不要把这句话原样写出来。",
            IntentExitRenderer.momentBlock(apologyAndComfort, "小明", now),
        )
    }

    @Test
    fun diaryBlock_twoLinesVerbatim() {
        assertEquals(
            "你心里挂着的事：你想跟小明道个歉，话到嘴边又咽了回去。\n日记是你自己的地方，可以把这份心思写得比聊天时坦白一些。",
            IntentExitRenderer.diaryBlock(apologyAndComfort, "小明", now),
        )
    }

    @Test
    fun giftBlock_twoLinesVerbatim_expressedVariant() {
        assertEquals(
            "你心里挂着的事：你已经跟用户示过弱了，还在等ta接住你。\n（这件事可以影响你今天送不送、送什么、说什么。）",
            IntentExitRenderer.giftBlock(listOf(intent(IntentKind.WANT_COMFORT, state = IntentState.EXPRESSED, strength = 30)), "用户", now),
        )
    }

    @Test
    fun nonChatBlocks_emptyWhenNoLive_residueDoesNotLeak() {
        val residue = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, residue = true)
        assertEquals("", IntentExitRenderer.momentBlock(listOf(residue), "小明", now))
        assertEquals("", IntentExitRenderer.diaryBlock(listOf(residue), "小明", now))
        assertEquals("", IntentExitRenderer.giftBlock(listOf(residue), "小明", now))
        assertEquals("", IntentExitRenderer.momentBlock(emptyList(), "小明", now))
    }

    // MARK: - ③ 日程

    @Test
    fun scheduleLines_headThreeItemsTail_verbatim_andCapsAtThree() {
        val four = listOf(
            intent(IntentKind.WANT_SHARE, strength = 20),
            intent(IntentKind.WANT_APOLOGIZE, strength = 55),
            intent(IntentKind.WANT_CONFIRM, strength = 40),
            intent(IntentKind.WANT_COMFORT, strength = 30),
        )
        assertEquals(
            listOf(
                "【TA心里挂着的事】",
                "- TA想向小明道歉",
                "- TA想确认小明还在不在乎自己",
                "- TA想被小明哄一哄",
                "这些只能进 innerThought（比如「要不要找个机会跟小明说一声」），不要变成日程事件，也不必每条都用。",
            ),
            IntentExitRenderer.scheduleLines(four, "小明", now),
        )
    }

    // MARK: - 内心行换气（微图纸 2026-09-02 §5）：聊天出口按 variant 轮换、四个非聊天出口恒原文

    @Test
    fun chat_variantPicksSentence_activeExpressedResidue_outOfRangeFallsBack() {
        val active = listOf(intent(IntentKind.WANT_COMFORT, strength = 40), intent(IntentKind.WANT_APOLOGIZE, strength = 55))
        assertEquals("有句道歉你欠着小明，翻来覆去没说出口。", IntentExitRenderer.chatCandidate(active, "小明", now, variant = 2))
        assertEquals("你知道该跟小明说声对不起，一直没找到开口的时机。", IntentExitRenderer.chatCandidate(active, "小明", now, variant = 1))
        assertEquals("默认 = 变体 0 = 原文", "你想跟小明道个歉，话到嘴边又咽了回去。", IntentExitRenderer.chatCandidate(active, "小明", now))
        assertEquals(IntentExitRenderer.chatCandidate(active, "小明", now), IntentExitRenderer.chatCandidate(active, "小明", now, variant = 0))
        val expressed = listOf(intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED, strength = 25))
        assertEquals("歉是道了，你心里还没踏实小明到底原谅没有。", IntentExitRenderer.chatCandidate(expressed, "小明", now, variant = 2))
        val residue = listOf(intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 2 * day, residue = true))
        assertEquals("你嘴上不提了，心里那道坎还在。", IntentExitRenderer.chatCandidate(residue, "小明", now, variant = 2))
        assertEquals("那件事你没忘，只是不想再翻出来。", IntentExitRenderer.chatCandidate(residue, "小明", now, variant = 1))
        assertEquals("越界回原文", "之前那件事其实没过去，你只是没再提。", IntentExitRenderer.chatCandidate(residue, "小明", now, variant = 7))
    }

    @Test
    fun nonChatExits_haveNoVariant_outputStaysVariantZeroVerbatim() {
        // 四个非聊天出口没有 variant 形参、恒原文（微图纸外部行为 2）：上面各例已逐字钉原文，这里再钉「不含变体 ②③ 字样」
        val apology = listOf(intent(IntentKind.WANT_APOLOGIZE, strength = 55))
        val blocks = listOf(
            IntentExitRenderer.momentBlock(apology, "小明", now),
            IntentExitRenderer.diaryBlock(apology, "小明", now),
            IntentExitRenderer.giftBlock(apology, "小明", now),
        )
        for (b in blocks) {
            assertTrue(b, b.startsWith("你心里挂着的事：你想跟小明道个歉，话到嘴边又咽了回去。\n"))
            assertFalse(b.contains("说声对不起"))
            assertFalse(b.contains("翻来覆去"))
        }
        assertEquals(
            listOf("【TA心里挂着的事】", "- TA想向小明道歉", "这些只能进 innerThought（比如「要不要找个机会跟小明说一声」），不要变成日程事件，也不必每条都用。"),
            IntentExitRenderer.scheduleLines(apology, "小明", now),
        )
    }

    @Test
    fun scheduleLines_emptyWhenNoLive() {
        assertTrue(IntentExitRenderer.scheduleLines(emptyList(), "小明", now).isEmpty())
        assertTrue(
            IntentExitRenderer.scheduleLines(
                listOf(intent(IntentKind.WANT_HIDE, state = IntentState.FADED, residue = true)), "小明", now,
            ).isEmpty(),
        )
    }
}
