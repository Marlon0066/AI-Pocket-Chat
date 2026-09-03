package com.situ.aichat.prompt

import com.situ.aichat.data.model.PersonaVocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图纸 2026-09-03 T1-9：反差配对表 [PersonaGainsPairs.PAIRS] 与入句变体 [gainInlineLabel] 的**结构金标**。
 *
 * 断言从图纸 §4.0–§4.2 规格独立反推（数量 / 分布 / 声明序 / key 合法性 / 人称规矩），锁定文本在本文件里
 * **重新打字为字面量**——照抄实现输出的测试锁不住任何东西。
 */
class PersonaGainsPairsTest {

    private val pairs = PersonaGainsPairs.PAIRS

    /** ① 总数 76 · ③ tier 分布 32 / 8 / 36（图纸 §9 ② 锁定数值）。 */
    @Test
    fun size_andTierDistribution_areLocked() {
        assertEquals("配对表总数", 76, pairs.size)
        assertEquals("tier 1 双敏感拉扯", 32, pairs.count { it.tier == 1 })
        assertEquals("tier 2 跨组反差", 8, pairs.count { it.tier == 2 })
        assertEquals("tier 3 同组反差", 36, pairs.count { it.tier == 3 })
    }

    /** ② id 无重复（重复 id = 复核时对不上表行）。 */
    @Test
    fun ids_areUnique() {
        val dup = pairs.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertEquals("重复 id", emptySet<String>(), dup)
    }

    /** ④ 声明序即优先级：tier 必须非降序——运行时 sort 是禁区（图纸 §9 ④），故声明序本身就是承重件。 */
    @Test
    fun declarationOrder_isTierNonDescending() {
        pairs.zipWithNext { a, b ->
            assertTrue("声明序 ${a.id}(tier ${a.tier}) 之后不该出现 ${b.id}(tier ${b.tier})", a.tier <= b.tier)
        }
    }

    /** ⑤ 全部 key ∈ GAIN_KEYS（写错一个字母 ⇒ 该条永不命中，且静默）。 */
    @Test
    fun allKeys_areInVocabulary() {
        val known = PersonaVocab.GAIN_KEYS.toSet()
        for (p in pairs) {
            for (k in p.sensitive + p.numb) {
                assertTrue("${p.id} 的 key $k 不在 27 项词表里", k in known)
            }
        }
    }

    /** ⑥ 同一条的 sensitive 与 numb 无交集（同一项不能既很敏感又不吃这套 ⇒ 该条恒不命中）。 */
    @Test
    fun sensitiveAndNumb_neverOverlap() {
        for (p in pairs) {
            assertEquals("${p.id} 两侧撞 key", emptySet<String>(), p.sensitive.toSet() intersect p.numb.toSet())
        }
    }

    /** tier 结构：tier 1 恒「双敏感、零无感」，tier 2/3 恒「一敏感 × 一无感」（图纸 §4.1 表头）。 */
    @Test
    fun tierShapes_matchSpec() {
        for (p in pairs) {
            if (p.tier == 1) {
                assertEquals("${p.id} tier1 应两项敏感", 2, p.sensitive.size)
                assertTrue("${p.id} tier1 不该有无感项", p.numb.isEmpty())
            } else {
                assertEquals("${p.id} 应一项敏感", 1, p.sensitive.size)
                assertEquals("${p.id} 应一项无感", 1, p.numb.size)
            }
        }
    }

    /**
     * ⑦ 人称规矩（图纸 §4.1 锁定 · E14）：`{user}` 换真名，`「」` 内的「他」是她的**内心独白**、一律不替换。
     * J4 / J6 / I1 三条是唯一带引号独白的，渲染后必须逐字保住原文。
     */
    @Test
    fun quotedInnerMonologue_isNeverRewritten_E14() {
        fun render(id: String) = pairs.single { it.id == id }.render("阿澈")
        assertEquals("- 夜深了一个人的时候，你最容易往「他是不是不要我了」上想。", render("J4"))
        assertEquals("- 任何一点风吹草动，你都先往「他要走了」那边想。", render("J6"))
        assertEquals("- 天塌下来你都不慌，唯独「他是不是要走了」这件事，你受不了。", render("I1"))
    }

    /** 渲染契约：前导 `"- "` 与句号由 [GainPair.render] 保证；`{user}` 一个不留。 */
    @Test
    fun render_carriesBulletAndPeriod_andLeavesNoPlaceholder() {
        for (p in pairs) {
            val line = p.render("阿澈")
            assertTrue("${p.id} 缺前导 \"- \"", line.startsWith("- "))
            assertTrue("${p.id} 缺句号", line.endsWith("。"))
            assertTrue("${p.id} 残留 {user} 占位符", !line.contains("{user}"))
        }
    }

    /** 二次回指用「他」不换名：J8 逐字（第一次 `{user}` → 真名，句中「他」原样）。 */
    @Test
    fun secondMention_staysAsPronoun() {
        assertEquals("- 你怕阿澈走，可他真寸步不离，你又喘不上气。", pairs.single { it.id == "J8" }.render("阿澈"))
    }

    /** ⑧-a 入句变体恰 11 项、key 全在词表内（用「与原标签不同」反推登记项集合）。 */
    @Test
    fun inlineLabels_areElevenRegisteredKeys() {
        val changed = PersonaVocab.GAIN_KEYS.filter { gainInlineLabel(it, "阿澈") != PersonaVocab.gainLabel(it) }
        assertEquals(
            listOf("g02", "g06", "g09", "g12", "g13", "g14", "g18", "g21", "g22", "g23", "g25"),
            changed,
        )
        assertEquals("登记项数", 11, changed.size)
    }

    /** ⑧-b 登记项逐字锁定（图纸 §4.2），含两处人称修复的 `{user}` 替换。 */
    @Test
    fun inlineLabels_areLockedVerbatim() {
        val expected = mapOf(
            "g02" to "被晾着、消息不回",
            "g06" to "被看轻、被当空气",
            "g09" to "阿澈记得你随口说过的小事",
            "g12" to "日子过成例行公事、没了新鲜感",
            "g13" to "吵架、被凶",
            "g14" to "冷战、被冷处理",
            "g18" to "被放鸽子、被辜负",
            "g21" to "阿澈在你面前露出脆弱",
            "g22" to "被撩、被试探",
            "g23" to "线下的身体接触",
            "g25" to "深夜一个人待着",
        )
        for ((key, value) in expected) assertEquals(key, value, gainInlineLabel(key, "阿澈"))
        assertNotEquals("g09 原标签的「你」= 用户，入句必须换人称", PersonaVocab.gainLabel("g09"), gainInlineLabel("g09", "阿澈"))
    }

    /** ⑧-c 未登记 key 回落原标签；未知 key ⇒ null（与 `PersonaVocab.gainLabel` 同语义）。 */
    @Test
    fun unregisteredKey_fallsBackToOriginalLabel_unknownKeyIsNull() {
        assertEquals("被关心问候", gainInlineLabel("g01", "阿澈"))
        assertEquals("被抛弃的信号", gainInlineLabel("g27", "阿澈"))
        assertNull(gainInlineLabel("g99", "阿澈"))
        assertNull(gainInlineLabel("", "阿澈"))
    }
}
