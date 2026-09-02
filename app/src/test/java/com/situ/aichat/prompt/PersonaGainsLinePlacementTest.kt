package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonalitySpectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感内核·修缮卷 T2-3（图纸 §3.7 · E40 / E41）：敏感点行在 Growth 段的**落位**——真 `PromptBuilder.buildMessages` 装配。
 * - E41：增益全默认 ⇒ 全文不含「你特别在意 / 不太吃这套」；8 维全静默时连「你的性格表现：」标题都不出（与修缮前逐字节同形）
 * - E40：有敏感点 ⇒ 该行落在「你的性格表现：」块末尾（8 维行之后）、下一行已是别的段；8 维全静默仍出标题 + 该行
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PersonaGainsLinePlacementTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 7, 11, 13, 39).atZone(zone).toInstant()
    private val line = "- 你特别在意被叫全名、被冷落 · 已读不回，对被夸奖肯定不太吃这套。"
    private val gains = GrowthJson.encode(
        PersonaGains(system = mapOf("g02" to 2, "g04" to 0), custom = listOf(CustomGain(id = "u1", label = "被叫全名", level = 2))),
    )

    private fun systemText(spectrum: PersonalitySpectrum, gainsJson: String): String {
        val msgs = PromptBuilder.buildMessages(
            character = CharacterEntity(
                uuid = "c1", name = "小雨", creationDate = 0L,
                personalitySpectrumJSON = GrowthJson.encode(spectrum), personaGainsJSON = gainsJson,
            ),
            conversation = null,
            sortedMessages = listOf(MessageEntity(messageUUID = "u1", conversationUuid = "cv1", roleRaw = "user", content = "我到啦", timestamp = now.toEpochMilli() - 60_000)),
            userProfile = null, appSettings = AppSettings(), strings = PromptStrings(RuntimeEnvironment.getApplication()),
            todaySchedule = null, todayScheduleEvents = emptyList(), now = now,
        )
        return msgs.filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }
    }

    @Test
    fun defaultGains_rendersNoLine_andNoHeaderWhenAllDimsSilent_E41() {
        val silent = systemText(PersonalitySpectrum.NEUTRAL, "")
        assertFalse(silent.contains("你特别在意"))
        assertFalse(silent.contains("不太吃这套"))
        assertFalse("8 维全静默 + 无敏感点 ⇒ 标题都不出（同修缮前）", silent.contains("你的性格表现："))

        val loud = systemText(PersonalitySpectrum(extroversion = 90), "")
        assertTrue(loud.contains("你的性格表现：\n"))
        assertFalse(loud.contains("你特别在意"))
    }

    @Test
    fun gainsLine_sitsAtEndOfPersonalityBlock_afterTraitLines_E40() {
        val text = systemText(PersonalitySpectrum(extroversion = 90), gains)
        assertEquals("恰出现一次", 1, Regex(Regex.escape(line)).findAll(text).count())
        val header = text.indexOf("你的性格表现：\n")
        val at = text.indexOf(line)
        assertTrue(header in 0 until at)
        val between = text.substring(header + "你的性格表现：\n".length, at).trimEnd('\n').split("\n")
        assertEquals("外向 90 一条行为行在前，敏感点行紧随其后", 1, between.size)
        assertTrue(between.single().isNotBlank())
        val next = text.substring(at + line.length).trimStart('\n').lineSequence().first()
        assertFalse("敏感点行是性格块最后一行：下一行已是别的段", next.startsWith("你特别在意") || next.startsWith("- 你特别在意"))
        assertTrue(next.isNotBlank())
    }

    @Test
    fun allDimsSilent_withGains_stillRendersHeaderPlusLine_E40() {
        val text = systemText(PersonalitySpectrum.NEUTRAL, gains)
        assertTrue(text.contains("你的性格表现：\n$line\n"))
    }
}
