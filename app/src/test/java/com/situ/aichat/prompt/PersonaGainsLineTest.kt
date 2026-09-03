package com.situ.aichat.prompt

import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图纸 2026-09-03 T1-1…T1-8：敏感点行 [buildPersonaGainsLines]（E1–E13）。
 *
 * ⚠️ **整文件按新规格重写**——修缮卷版的 5 例锁死旧口径（60 字 / 敏感 3 席 / 无感 2 席 / 单行 `String` 返回），
 * 那是**预期失效的规格变更**，不是回归。
 *
 * 期望值由图纸 §3.2 六步 + §4.1/§4.2 物料表**独立反推**（另跑一份 Python 复现脚本交叉验算），不照抄实现输出。
 */
class PersonaGainsLineTest {

    private val user = "阿澈"

    private fun lines(system: Map<String, Int> = emptyMap(), custom: List<CustomGain> = emptyList()) =
        buildPersonaGainsLines(PersonaGains(system = system, custom = custom), user)

    private fun custom(label: String, level: Int = 2) = CustomGain(id = "u-$label", label = label, level = level)

    // MARK: - T1-1 回归钉（E1 / E13）

    @Test
    fun allDefault_isEmpty_E1() {
        assertEquals(emptyList<String>(), buildPersonaGainsLines(PersonaGains(), user))
    }

    @Test
    fun normalLevelAndUnknownKey_countAsNothing_E1_E13() {
        assertEquals(
            "正常档 / 未知 key / 非敏感专属项都不算数 ⇒ 与全默认同样一行不出",
            emptyList<String>(),
            lines(system = mapOf("g02" to 1, "g99" to 2), custom = listOf(custom("怕黑", level = 1))),
        )
    }

    // MARK: - T1-2 平铺行三形态之二（E2 / E3）

    @Test
    fun onlyCustomSensitive_rendersFirstHalfOnly_E2() {
        assertEquals(listOf("- 你特别在意被叫全名。"), lines(custom = listOf(custom("被叫全名"))))
    }

    @Test
    fun onlyNumb_rendersSecondHalfOnly_inVocabOrder_E3() {
        assertEquals(
            "不吃这套项按 GAIN_KEYS 序（g01 在 g04 前），不按 map 声明序",
            listOf("- 你对被关心问候、被夸奖肯定不太吃这套。"),
            lines(system = mapOf("g04" to 0, "g01" to 0)),
        )
    }

    // MARK: - T1-3 配对命中 + 剔除（E4）

    @Test
    fun onePairHit_rendersTwoLines_andConsumedItemsLeaveFlatLine_E4() {
        // g02+g03 双敏感 ⇒ J1 命中并**消费**这两项；g05 敏感 / g16 无感未被消费 ⇒ 进平铺行
        val out = lines(system = mapOf("g02" to 2, "g03" to 2, "g05" to 2, "g16" to 0))
        assertEquals(2, out.size)
        assertEquals("- 你怕被丢下，可对方一贴近你又想躲——你自己也说不清要哪个。", out[0])
        assertEquals("- 你特别在意被批评否定，对收到礼物不太吃这套。", out[1])
        assertFalse("g02 被反差句消费掉，不许在平铺行再说一遍", out[1].contains("被晾着"))
        assertFalse("g03 同上", out[1].contains("被黏得太紧"))
    }

    // MARK: - T1-4 上限 3 条 + tier 优先级（E5 / E6）

    @Test
    fun atMostThreePairs_tierAscending_andFourthHitStaysInFlatLine_E5_E6() {
        // 命中 5 条：J1(tier1) / A1 / A2 / B1 / B2(tier3) ⇒ 只出前 3 条，B1 B2 的项仍留在平铺行
        val out = lines(system = mapOf("g02" to 2, "g03" to 2, "g05" to 2, "g06" to 2, "g01" to 0, "g04" to 0))
        assertEquals(4, out.size)
        assertEquals("tier 1 恒排在 tier 3 之前", "- 你怕被丢下，可对方一贴近你又想躲——你自己也说不清要哪个。", out[0])
        assertEquals("同 tier 按声明序：A1 在 A2 前", "- 你对主动的关心没什么反应，可只要被晾着、消息不回，你立刻就不好了。", out[1])
        assertEquals("- 你不吃嘘寒问暖那一套，靠得太近还会让你想退开。", out[2])
        assertEquals("落选的 B1/B2 各项仍进平铺行", "- 你特别在意被批评否定、被看轻、被当空气，对被夸奖肯定不太吃这套。", out[3])
    }

    // MARK: - T1-5 平铺行可空 / 半空（E7 / E8）

    @Test
    fun pairsEatEverything_noFlatLine_E7() {
        val out = lines(system = mapOf("g02" to 2, "g03" to 2))
        assertEquals("两项全被 J1 吃掉 ⇒ 不输出空句子", listOf("- 你怕被丢下，可对方一贴近你又想躲——你自己也说不清要哪个。"), out)
    }

    @Test
    fun pairsEatAllNumb_flatLineKeepsFirstHalfOnly_E8() {
        // A1 消费 g02(敏感) + g01(无感) ⇒ 无感项被吃光，平铺行只剩前半句
        val out = lines(system = mapOf("g02" to 2, "g05" to 2, "g01" to 0))
        assertEquals(2, out.size)
        assertEquals("- 你对主动的关心没什么反应，可只要被晾着、消息不回，你立刻就不好了。", out[0])
        assertEquals("- 你特别在意被批评否定。", out[1])
        assertFalse(out[1].contains("不太吃这套"))
    }

    // MARK: - T1-6 空用户名（E9）

    @Test
    fun emptyUserName_substitutesEmptyString_noFallbackRewrite_E9() {
        // 上游 resolvedUserName 已有兜底，本层再兜会出现两套口径 ⇒ 直接替换为空串
        assertEquals(
            listOf("- 你要的是彻底摊开，留一点没说，在你这就已经算瞒。"),
            buildPersonaGainsLines(PersonaGains(system = mapOf("g19" to 2, "g21" to 2)), ""),
        )
        assertEquals(
            "入句变体里的 {user} 同样直接替换",
            listOf("- 你特别在意在你面前露出脆弱。"),
            buildPersonaGainsLines(PersonaGains(system = mapOf("g21" to 2)), ""),
        )
    }

    // MARK: - T1-7 字数刹车（E10 / E11）

    @Test
    fun overBudget_dropsNumbTailFirst_thenSensitiveTail_pairLinesUntouched_E10() {
        val custom10 = (1..10).map { custom("专属敏感点标签一二三四$it") }   // 各 12 字
        val out = lines(system = mapOf("g02" to 2, "g03" to 2, "g01" to 0, "g04" to 0, "g05" to 0), custom = custom10)
        assertEquals(4, out.size)
        // 反差句三行一个字节不砍（P4）
        assertEquals("- 你怕被丢下，可对方一贴近你又想躲——你自己也说不清要哪个。", out[0])
        assertEquals("- 你对主动的关心没什么反应，可只要被晾着、消息不回，你立刻就不好了。", out[1])
        assertEquals("- 你不吃嘘寒问暖那一套，靠得太近还会让你想退开。", out[2])
        assertTrue("两行合计 ≤ 150 字", out.sumOf { it.length } <= 150)
        // 先砍 numb 末项（g05「被批评否定」）砍到只剩 1，再砍 sensitive 末项
        assertFalse("numb 先砍到只剩 1 项", out[3].contains("被批评否定"))
        assertTrue(out[3].contains("对被夸奖肯定不太吃这套。"))
        assertEquals(
            "sensitive 从末尾砍，留的是前缀",
            "- 你特别在意专属敏感点标签一二三四1、专属敏感点标签一二三四2、专属敏感点标签一二三四3，对被夸奖肯定不太吃这套。",
            out[3],
        )
    }

    @Test
    fun eachSideDownToOne_stillOverBudget_isKept_E11() {
        // 三条长反差句（J7 / J10 / J31 共 116 字）+ 各剩 1 项的平铺行 39 字 = 155 > 150 ⇒ 砍无可砍，保留
        val out = lines(
            system = mapOf("g04" to 2, "g05" to 2, "g12" to 2, "g20" to 2, "g24" to 2, "g27" to 2, "g09" to 0),
            custom = listOf(custom("专属敏感点标签一二三四1")),
        )
        assertEquals(4, out.size)
        assertEquals("- 你特别在意专属敏感点标签一二三四1，对阿澈记得你随口说过的小事不太吃这套。", out[3])
        assertEquals("砍到各剩 1 项仍超 ⇒ 保留（同修缮卷 60 字守卫的语义）", 155, out.sumOf { it.length })
    }

    // MARK: - T1-8 席位与过滤（E12 / E13）

    @Test
    fun unknownKeysAndNonSensitiveCustom_areFilteredOut_E12_E13() {
        val expected = lines(system = mapOf("g02" to 2))
        assertEquals(listOf("- 你特别在意被晾着、消息不回。"), expected)
        assertEquals(
            "g99/g98 不在词表、专属项 level=1 ⇒ 与只有 g02 时输出完全一致",
            expected,
            lines(system = mapOf("g02" to 2, "g99" to 2, "g98" to 0), custom = listOf(custom("怕黑", level = 1))),
        )
    }

    @Test
    fun systemSeats_areFiveSensitiveAndThreeNumb_customsAreUncapped() {
        // 6 项系统敏感（无任何配对命中）⇒ 只进前 5；5 项系统无感 ⇒ 只进前 3；6 条专属项**全留**
        val out = lines(
            system = mapOf(
                "g01" to 2, "g02" to 2, "g04" to 2, "g07" to 2, "g10" to 2, "g16" to 2,
                "g08" to 0, "g12" to 0, "g13" to 0, "g14" to 0, "g15" to 0,
            ),
            custom = listOf("怕黑", "迟到", "大声", "生冷", "加班", "鬼片").map { custom(it) },
        )
        assertEquals(1, out.size)
        assertEquals(
            "- 你特别在意怕黑、迟到、大声、生冷、加班、鬼片、被关心问候、被晾着、消息不回、被夸奖肯定、被真正听懂、被逗笑，" +
                "对被误解、日子过成例行公事、没了新鲜感、吵架、被凶不太吃这套。",
            out.single(),
        )
        assertFalse("第 6 项系统敏感 g16 出局", out.single().contains("收到礼物"))
        assertFalse("第 4 项系统无感 g14 出局", out.single().contains("冷战"))
        assertFalse("第 5 项系统无感 g15 出局", out.single().contains("道歉与和好"))
    }
}
