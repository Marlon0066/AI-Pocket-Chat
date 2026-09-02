package com.situ.aichat.data.model

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 活人感内核·卷一《人设编译器》T1-8（图纸 §7.2 · Y-E24）：封闭词表的**完整性钉**。
 *
 * 词表是编译器「宽进严出」的那道严出闸——少一项 / key 断号 / 资源缺失，都会让 LLM 合法输出被静默丢弃
 * （用户只看到「已忽略 N 条」，查不出为什么）。断言从图纸 §3.2 / §3.3 的锁定表**独立反推**：
 * - 项数：增益恰 27（`g01`–`g27` 连续）· 条件恰 12（`c01`–`c12`）· 动作恰 10（`a01`–`a10`）
 * - 三档系数恰 `0.4 / 1.0 / 1.8`，档位整数恰 `0 / 1 / 2`
 * - **每个 key 都有 zh 与 en 资源**，且两语值不同（证明真是两条资源，不是 en 兜底）
 * - 27 / 12 / 10 条中文标签在此**重新打字**为字面量（PITFALLS §1e 锁定文本双保险），改文案必红
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaVocabTest {

    private fun ctx(locale: Locale): Context {
        val base = RuntimeEnvironment.getApplication()
        val conf = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(conf)
    }

    private val zh get() = ctx(Locale.SIMPLIFIED_CHINESE)
    private val en get() = ctx(Locale.ENGLISH)

    // MARK: - 项数与 key 连续性

    @Test
    fun gains_areExactly27_withContinuousKeys() {
        assertEquals("增益恰 27 项", 27, PersonaVocab.GAINS.size)
        assertEquals("九组展平后也恰 27 项（组间无重复无遗漏）", 27, PersonaVocab.GAIN_KEYS.size)
        assertEquals("展平序无重复", 27, PersonaVocab.GAIN_KEYS.toSet().size)
        assertEquals(
            "key 恰为 g01…g27 连续无缺",
            (1..27).map { "g%02d".format(it) },
            PersonaVocab.GAIN_KEYS,
        )
        assertEquals("GAINS 的键集与展平序一致", PersonaVocab.GAIN_KEYS.toSet(), PersonaVocab.GAINS.keys)
    }

    @Test
    fun gainGroups_areExactly9_ofThreeEach() {
        assertEquals("恰九组", 9, PersonaVocab.GAIN_GROUPS.size)
        PersonaVocab.GAIN_GROUPS.forEachIndexed { i, g ->
            assertEquals("第 ${i + 1} 组恰 3 项", 3, g.keys.size)
        }
    }

    @Test
    fun conditions_areExactly12_withContinuousKeys() {
        assertEquals(12, PersonaVocab.CONDITIONS.size)
        assertEquals((1..12).map { "c%02d".format(it) }.toSet(), PersonaVocab.CONDITIONS.keys)
    }

    @Test
    fun actions_areExactly10_withContinuousKeys() {
        assertEquals(10, PersonaVocab.ACTIONS.size)
        assertEquals((1..10).map { "a%02d".format(it) }.toSet(), PersonaVocab.ACTIONS.keys)
    }

    @Test
    fun operatorCap_isEight() {
        assertEquals("算子条数上限恰 8", 8, PersonaVocab.MAX_OPERATORS)
        assertEquals("专属增益上限恰 10", 10, PersonaGains.MAX_CUSTOM)
        assertEquals("专属标签上限恰 12 字", 12, CustomGain.MAX_LABEL_LENGTH)
    }

    // MARK: - 三档

    @Test
    fun levels_areZeroOneTwo_withLockedFactors() {
        assertEquals(0, PersonaVocab.LEVEL_NUMB)
        assertEquals(1, PersonaVocab.LEVEL_NORMAL)
        assertEquals(2, PersonaVocab.LEVEL_SENSITIVE)
        assertEquals(0.4f, PersonaVocab.gainFactor(PersonaVocab.LEVEL_NUMB), 0f)
        assertEquals(1.0f, PersonaVocab.gainFactor(PersonaVocab.LEVEL_NORMAL), 0f)
        assertEquals(1.8f, PersonaVocab.gainFactor(PersonaVocab.LEVEL_SENSITIVE), 0f)
    }

    @Test
    fun outOfRangeLevel_fallsBackToNormal_neverAmplifies() {
        assertEquals(1.0f, PersonaVocab.gainFactor(-1), 0f)
        assertEquals(1.0f, PersonaVocab.gainFactor(3), 0f)
        assertEquals(1.0f, PersonaVocab.gainFactor(99), 0f)
        assertEquals(
            "越界档位的标签也回落「正常」",
            zh.getString(PersonaVocab.levelLabelRes(PersonaVocab.LEVEL_NORMAL)),
            zh.getString(PersonaVocab.levelLabelRes(7)),
        )
    }

    @Test
    fun newCustomGain_defaultsToSensitive() {
        // D-10：手写/编译出的新专属项默认「很敏感」。CustomGain 的字面量默认与词表常量必须是同一个数。
        assertEquals(PersonaVocab.LEVEL_SENSITIVE, CustomGain().level)
    }

    // MARK: - 双语资源齐备（Y-E24）

    @Test
    fun everyKeyHasBothZhAndEnStrings() {
        val all = PersonaVocab.GAINS.entries.map { "增益 ${it.key}" to it.value } +
            PersonaVocab.CONDITIONS.entries.map { "条件 ${it.key}" to it.value } +
            PersonaVocab.ACTIONS.entries.map { "动作 ${it.key}" to it.value } +
            PersonaVocab.GAIN_GROUPS.mapIndexed { i, g -> "组 ${i + 1}" to g.labelRes } +
            (0..2).map { "档位 $it" to PersonaVocab.levelLabelRes(it) }
        assertEquals("待验资源总数 = 27 + 12 + 10 + 9 + 3", 61, all.size)

        all.forEach { (label, res) ->
            val z = zh.getString(res)
            val e = en.getString(res)
            assertTrue("$label 的 zh 文案不得为空", z.isNotBlank())
            assertTrue("$label 的 en 文案不得为空", e.isNotBlank())
            assertNotEquals("$label 缺 en 翻译（en 回落成了 zh 值）", z, e)
        }
    }

    // MARK: - 锁定文案（图纸 §3.2 / §3.3 逐字，测试里重新打字）

    @Test
    fun gainLabels_matchLockedChineseText() {
        val expected = listOf(
            "被关心问候", "被冷落 · 已读不回", "被黏得太紧",
            "被夸奖肯定", "被批评否定", "被小瞧 · 被当空气",
            "被真正听懂", "被误解", "你记得她说过的小事",
            "被逗笑", "一起做点什么", "例行公事 · 没新鲜感",
            "吵架 · 被凶", "冷战 · 冷暴力", "道歉与和好",
            "收到礼物", "被照顾", "被爽约 · 被辜负",
            "被隐瞒欺骗", "承诺被兑现", "你对她坦白脆弱",
            "被撩 · 暧昧试探", "身体亲密（线下）", "亲密被拒绝",
            "独处 · 深夜", "意外与变化", "被抛弃的信号",
        )
        assertEquals(27, expected.size)
        assertEquals(expected, PersonaVocab.GAIN_KEYS.map { zh.getString(PersonaVocab.GAINS.getValue(it)) })
    }

    @Test
    fun conditionAndActionLabels_matchLockedChineseText() {
        val conditions = listOf(
            "想道歉的时候", "想被哄的时候", "想试探的时候", "想躲的时候",
            "想分享的时候", "想确认的时候", "被夸的时候", "被批评的时候",
            "被冷落的时候", "深夜一个人的时候", "情绪很差的时候", "关系刚有进展的时候",
        )
        val actions = listOf(
            "不直说，绕着表达", "转移话题", "话变少", "更想找人说话", "嘴上否认，行动上在意",
            "先冷一下再回", "用玩笑掩饰", "反问回去", "主动找点别的事做", "说反话",
        )
        assertEquals(
            conditions,
            (1..12).map { zh.getString(PersonaVocab.CONDITIONS.getValue("c%02d".format(it))) },
        )
        assertEquals(
            actions,
            (1..10).map { zh.getString(PersonaVocab.ACTIONS.getValue("a%02d".format(it))) },
        )
    }

    @Test
    fun levelLabels_matchLockedChineseText() {
        assertEquals("不吃这套", zh.getString(PersonaVocab.levelLabelRes(PersonaVocab.LEVEL_NUMB)))
        assertEquals("正常", zh.getString(PersonaVocab.levelLabelRes(PersonaVocab.LEVEL_NORMAL)))
        assertEquals("很敏感", zh.getString(PersonaVocab.levelLabelRes(PersonaVocab.LEVEL_SENSITIVE)))
    }
}
