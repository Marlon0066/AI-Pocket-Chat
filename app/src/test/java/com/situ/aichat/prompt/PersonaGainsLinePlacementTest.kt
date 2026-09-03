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
 * 图纸 2026-09-03 T2-1 / T2-2（E1 / E15 / P2）：敏感点行在 Growth 段的**落位**——真 `PromptBuilder.buildMessages` 装配。
 * - T2-1：增益全默认 ⇒ 全文不含「你特别在意 / 不太吃这套」；8 维全静默时连「你的性格表现：」标题都不出（回归钉·逐字节同前）；
 *   有敏感点 ⇒ 平铺行落在「你的性格表现：」块末尾（8 维行之后）、下一行已是别的段；8 维全静默仍出标题 + 该行
 * - T2-2：命中配对的角色 ⇒ 反差句排在平铺行**之前**（P2·同关系矛盾句「恒排该段最前」先例）
 *
 * ⚠️ `line` 字面量随席位/入句变体规格变更而更新（g02 入句变体「被晾着、消息不回」取代原标签「被冷落 · 已读不回」），
 * 属**预期的规格变更**不是回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PersonaGainsLinePlacementTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 7, 11, 13, 39).atZone(zone).toInstant()
    private val line = "- 你特别在意被叫全名、被晾着、消息不回，对被夸奖肯定不太吃这套。"
    private val gains = GrowthJson.encode(
        PersonaGains(system = mapOf("g02" to 2, "g04" to 0), custom = listOf(CustomGain(id = "u1", label = "被叫全名", level = 2))),
    )

    /** J1 命中（g02+g03 双敏感被消费）+ g05 敏感 / g16 无感留给平铺行 ⇒ 反差句 1 行 + 平铺行 1 行。 */
    private val pairLine = "- 你怕被丢下，可对方一贴近你又想躲——你自己也说不清要哪个。"
    private val pairFlatLine = "- 你特别在意被批评否定，对收到礼物不太吃这套。"
    private val pairGains = GrowthJson.encode(
        PersonaGains(system = mapOf("g02" to 2, "g03" to 2, "g05" to 2, "g16" to 0)),
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
    fun defaultGains_rendersNoLine_andNoHeaderWhenAllDimsSilent_E1() {
        val silent = systemText(PersonalitySpectrum.NEUTRAL, "")
        assertFalse(silent.contains("你特别在意"))
        assertFalse(silent.contains("不太吃这套"))
        assertFalse("8 维全静默 + 无敏感点 ⇒ 标题都不出（同修缮前）", silent.contains("你的性格表现："))

        val loud = systemText(PersonalitySpectrum(extroversion = 90), "")
        assertTrue(loud.contains("你的性格表现：\n"))
        assertFalse(loud.contains("你特别在意"))
    }

    @Test
    fun gainsLine_sitsAtEndOfPersonalityBlock_afterTraitLines_T2_1() {
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
    fun allDimsSilent_withGains_stillRendersHeaderPlusLine_E15() {
        val text = systemText(PersonalitySpectrum.NEUTRAL, gains)
        assertTrue(text.contains("你的性格表现：\n$line\n"))
    }

    /** T2-2（P2）：反差句恒排在平铺行之前——两行都在性格块里，且中间没有别的行。 */
    @Test
    fun contrastLine_precedesFlatLine_T2_2() {
        val text = systemText(PersonalitySpectrum(extroversion = 90), pairGains)
        val atPair = text.indexOf(pairLine)
        val atFlat = text.indexOf(pairFlatLine)
        assertTrue("反差句已上屏", atPair >= 0)
        assertTrue("平铺行已上屏", atFlat >= 0)
        assertTrue("反差句在平铺行之前", atPair < atFlat)
        assertTrue("两行相邻（反差句紧接平铺行）", text.contains("$pairLine\n$pairFlatLine"))
        val header = text.indexOf("你的性格表现：\n")
        assertTrue("两行都在性格块内", header in 0 until atPair)
    }
}
