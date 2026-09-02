package com.situ.aichat.prompt

import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·修缮卷 T1-14（图纸 §3.7 · E40 / E41 / E42）：敏感点行 [buildPersonaGainsLine]。
 * 文本三形态与「你特别在意」「不太吃这套」字面锁定；专属项排在系统项前；取前 3 / 前 2；超 60 字先去 numb 末项再去 sensitive 末项。
 */
class PersonaGainsLineTest {

    @Test
    fun bothParts_E40() {
        val gains = PersonaGains(
            system = mapOf("g02" to 2, "g04" to 0),
            custom = listOf(CustomGain(id = "u1", label = "被叫全名", level = 2)),
        )
        assertEquals("- 你特别在意被叫全名、被冷落 · 已读不回，对被夸奖肯定不太吃这套。", buildPersonaGainsLine(gains))
    }

    @Test
    fun allNormal_isEmpty_E41() {
        assertEquals("", buildPersonaGainsLine(PersonaGains()))
        assertEquals("正常档 / 未知 key 不算", "", buildPersonaGainsLine(PersonaGains(system = mapOf("g02" to 1, "g99" to 2), custom = listOf(CustomGain(id = "u", label = "怕黑", level = 1)))))
    }

    @Test
    fun onlySensitive_andOnlyNumb_forms() {
        assertEquals("- 你特别在意被冷落 · 已读不回。", buildPersonaGainsLine(PersonaGains(system = mapOf("g02" to 2))))
        assertEquals("不吃这套项按 GAIN_KEYS 序（g01 在 g04 前）", "- 你对被关心问候、被夸奖肯定不太吃这套。", buildPersonaGainsLine(PersonaGains(system = mapOf("g04" to 0, "g01" to 0))))
    }

    @Test
    fun caps_threeSensitive_twoNumb_customFirst() {
        val gains = PersonaGains(
            system = mapOf("g01" to 2, "g02" to 2, "g03" to 2, "g04" to 0, "g05" to 0, "g06" to 0),
            custom = listOf(CustomGain(id = "u1", label = "怕黑", level = 2), CustomGain(id = "u2", label = "迟到", level = 2)),
        )
        assertEquals("- 你特别在意怕黑、迟到、被关心问候，对被夸奖肯定、被批评否定不太吃这套。", buildPersonaGainsLine(gains))
    }

    @Test
    fun overSixty_dropsNumbLastFirst_thenSensitiveLast_E42() {
        val long3 = (1..3).map { CustomGain(id = "u$it", label = "专属敏感点标签一二三四$it", level = 2) }   // 各 12 字
        // 7 + 12×3 + 2 顿号 = 45；「，对」2 + 5 + 1 + 5「不太吃这套。」6 ⇒ 64 > 60 ⇒ 去 numb 末项（GAIN_KEYS 序末 = g04）⇒ 58
        val a = buildPersonaGainsLine(PersonaGains(system = mapOf("g04" to 0, "g01" to 0), custom = long3))
        assertTrue(a.length <= 60)
        assertEquals("- 你特别在意${long3.joinToString("、") { it.label }}，对被关心问候不太吃这套。", a)
        // numb 只剩 1（9 字「你记得她说过的小事」）：7 + 38 + 2 + 9 + 6 = 62 > 60 ⇒ 再去 sensitive 末项 ⇒ 49
        val b = buildPersonaGainsLine(PersonaGains(system = mapOf("g09" to 0), custom = long3))
        assertTrue(b.length <= 60)
        assertEquals("- 你特别在意${long3.take(2).joinToString("、") { it.label }}，对你记得她说过的小事不太吃这套。", b)
    }
}
