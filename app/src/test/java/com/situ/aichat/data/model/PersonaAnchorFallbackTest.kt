package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷一《人设编译器》T1-6 / T1-7（图纸 §7.2）：本性锚点的**空列兜底**与「现在」竖线的可见性判据。
 *
 * 断言从图纸 §3.1（Y-1 兜底语义）与 §4.2（D-3 阈值 `> 5`）独立反推，不照抄实现：
 * - Y-E13：老角色 `personalityAnchorJSON` 为空 ⇒ 访问器返回 `personalitySpectrum`（本性 == 现在）；非空 ⇒ 返回解码值
 * - Y-E14：偏移 0 / 5 ⇒ 竖线隐藏；6 / 20 ⇒ 显示（阈值是**严格大于 5**，不是 ≥5）
 *
 * ⚠️ 兜底是**只读**的：本测试同时钉「访问器不写库」——它是纯计算属性，没有任何 DAO 入口可调。
 */
class PersonaAnchorFallbackTest {

    private fun character(spectrumJson: String = "", anchorJson: String = "") = CharacterEntity(
        uuid = "c1",
        name = "林晚",
        creationDate = 0L,
        personalitySpectrumJSON = spectrumJson,
        personalityAnchorJSON = anchorJson,
    )

    @Test
    fun emptyAnchorColumn_fallsBackToCurrentSpectrum() {
        // 现值是一份「相处出来的」非中性光谱：外向 30 / 温暖 25 / 幽默 70。
        val current = PersonalitySpectrum(extroversion = 30, warmth = 25, humor = 70)
        val c = character(spectrumJson = GrowthJson.encode(current), anchorJson = "")

        assertEquals("空锚点列 ⇒ 本性就是她现在的样子", current, c.personalityAnchor)
        assertEquals(current, c.personalitySpectrum)
    }

    @Test
    fun emptyAnchorAndEmptySpectrum_bothFallBackToNeutral() {
        // 全新角色：两列都空 ⇒ 都回落中性全 50（不是崩、也不是零值）。
        val c = character()
        assertEquals(PersonalitySpectrum.NEUTRAL, c.personalityAnchor)
        assertEquals(50, c.personalityAnchor.warmth)
    }

    @Test
    fun nonEmptyAnchorColumn_decodesItselfAndIgnoresCurrent() {
        val anchor = PersonalitySpectrum(extroversion = 30, warmth = 25, humor = 70, independence = 75)
        val current = PersonalitySpectrum(extroversion = 44, warmth = 41, humor = 66, independence = 70)
        val c = character(spectrumJson = GrowthJson.encode(current), anchorJson = GrowthJson.encode(anchor))

        assertEquals("非空锚点列 ⇒ 解码它自己，与现值无关", anchor, c.personalityAnchor)
        assertEquals("现值列一个字节不受影响", current, c.personalitySpectrum)
    }

    @Test
    fun corruptAnchorColumn_fallsBackToNeutralNotCrash() {
        // 解码失败永不抛（GrowthJson 契约）：坏 JSON ⇒ 中性默认，而不是把现值当锚点。
        val c = character(spectrumJson = GrowthJson.encode(PersonalitySpectrum(warmth = 25)), anchorJson = "{坏掉的")
        assertEquals(PersonalitySpectrum.NEUTRAL, c.personalityAnchor)
    }

    @Test
    fun currentMarker_hiddenAtOffsetZeroAndFive() {
        assertFalse("偏移 0（未编译角色的天然状态）⇒ 隐藏", personaCurrentMarkerVisible(anchor = 50, current = 50))
        assertFalse("偏移 5 恰在阈值上 ⇒ 仍隐藏（阈值是严格大于 5）", personaCurrentMarkerVisible(anchor = 50, current = 55))
        assertFalse("负方向偏移 5 同样隐藏", personaCurrentMarkerVisible(anchor = 50, current = 45))
    }

    @Test
    fun currentMarker_visibleAtOffsetSixAndAbove() {
        assertTrue("偏移 6 ⇒ 显示", personaCurrentMarkerVisible(anchor = 50, current = 56))
        assertTrue("负方向偏移 6 ⇒ 显示", personaCurrentMarkerVisible(anchor = 50, current = 44))
        assertTrue("偏移 20 ⇒ 显示", personaCurrentMarkerVisible(anchor = 30, current = 50))
        assertTrue("端点也照判", personaCurrentMarkerVisible(anchor = 0, current = 100))
    }
}
