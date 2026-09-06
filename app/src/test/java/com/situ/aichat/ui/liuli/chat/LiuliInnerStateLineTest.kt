package com.situ.aichat.ui.liuli.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.prompt.InnerStateScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * T1-2 顶栏「此刻」一句（图纸 2026-09-05 卷二B §7 · A-6）：取句规则与两道回退闸。
 *
 * 取句规则从规格反推：剥 `此刻你心里：` 前缀 → 取到第一个「。」为止（含句号）；整段没句号就整段都要；
 * 空段 / 只剩前缀 ⇒ null（副标回退到日程 → 心情行）。
 */
class LiuliInnerStateLineTest {

    private val prefix = InnerStateScripts.PREFIX

    @Test fun emptyRender_meansNoSubtitleLine() {
        assertNull("内核一句都没有 ⇒ 副标回退", liuliFirstInnerSentence(""))
    }

    @Test fun prefixOnly_alsoMeansNoLine() {
        assertNull("只剩前缀（不该发生，但不许把空串显上去）", liuliFirstInnerSentence(prefix))
    }

    @Test fun threeSentences_keepOnlyTheFirstWithItsFullStop() {
        val rendered = prefix + "你有点想他。你又觉得说出来矫情。今天风挺大。"
        assertEquals("你有点想他。", liuliFirstInnerSentence(rendered))
    }

    @Test fun singleSentence_isReturnedWhole() {
        assertEquals("你有点想他。", liuliFirstInnerSentence(prefix + "你有点想他。"))
    }

    @Test fun noFullStop_takesTheWholeBody() {
        assertEquals("你有点想他", liuliFirstInnerSentence(prefix + "你有点想他"))
    }

    @Test fun growthSystemOff_shortCircuitsBeforeRendering() {
        val character = CharacterEntity(uuid = "c1", name = "云野", creationDate = NOW)
        assertNull(
            "成长系统关 = 场系统整个不在（同 PromptBuilderSchedule.innerLine 第一道闸）",
            liuliInnerStateFirstSentence(character, "我", NOW, ZONE, growthEnabled = false),
        )
    }

    @Test fun freshCharacter_hasNothingToSay_soTheLineIsNull() {
        // 全新角色：四场在基线、无算子无意图 ⇒ 内核给不出句子 ⇒ 副标不该硬挤一行出来。
        val character = CharacterEntity(uuid = "c1", name = "云野", creationDate = NOW)
        assertNull(liuliInnerStateFirstSentence(character, "我", NOW, ZONE, growthEnabled = true))
    }

    private companion object {
        const val NOW = 1_757_000_000_000L
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
