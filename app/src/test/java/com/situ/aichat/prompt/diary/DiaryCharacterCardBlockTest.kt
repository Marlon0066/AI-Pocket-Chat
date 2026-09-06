package com.situ.aichat.prompt.diary

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * T1（图纸 §7 T1-1 / T1-2）：交换日记角色卡块（[DiaryCharacterCardBlock]）。
 * 断言从图纸 §3.1 规格独立反推——十行顺序、非空才出、先宏替换后判空、城市两级回落、全空返 ""。
 */
class DiaryCharacterCardBlockTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** 哨兵模板：每行一个可辨识前缀，便于按序断言而不依赖真资源文案。 */
    private fun sentinels() = DiaryCharacterCardStrings(
        gender = "GEN<%1\$s>",
        age = "AGE<%1\$d>",
        zodiac = "ZOD<%1\$s>",
        occupation = "OCC<%1\$s>",
        appearance = "APP<%1\$s>",
        backstory = "BAK<%1\$s>",
        speaking = "SPK<%1\$s>",
        catchphrases = "CAT<%1\$s>",
        interests = "INT<%1\$s>",
        city = "CITY<%1\$s>",
    )

    private fun character(
        name: String = "小满",
        gender: String = "",
        birthday: Long? = null,
        ageModeRaw: String = "growing",
        fixedAge: Int = 0,
        appearanceDescription: String = "",
        occupation: String = "",
        backstory: String = "",
        speakingStyle: String = "",
        catchphrases: String = "",
        initialInterests: String = "",
        cityName: String? = null,
    ) = CharacterEntity(
        uuid = "c1", name = name, creationDate = 0L, gender = gender, birthday = birthday,
        ageModeRaw = ageModeRaw, fixedAge = fixedAge, appearanceDescription = appearanceDescription,
        occupation = occupation, backstory = backstory, speakingStyle = speakingStyle,
        catchphrases = catchphrases, initialInterests = initialInterests, cityName = cityName,
    )

    private fun build(
        character: CharacterEntity,
        cityName: String? = null,
        userName: String = "小明",
        now: Instant = Instant.ofEpochMilli(1_700_000_000_000L),
    ) = DiaryCharacterCardBlock.build(character, cityName, userName, now, zone, sentinels())

    // MARK: - T1-1 顺序 / 判空 / 城市回落（E1 / E2 / E6 / E7）

    @Test fun `所有项皆空时返回空串——不产出空行也不产出空标签`() {
        assertEquals("", build(character()))
    }

    @Test fun `只填两项时只出两行且保持规格顺序（职业先于兴趣）`() {
        assertEquals(
            listOf("OCC<插画师>", "INT<看展、腌梅子>").joinToString("\n"),
            build(character(occupation = "插画师", initialInterests = "看展、腌梅子")),
        )
    }

    @Test fun `十项俱全时按规格十行顺序输出`() {
        // 1988-06-15 UTC = 双子座；now 取 2023-11-14 UTC → 35 岁。
        val birthday = Instant.parse("1988-06-15T00:00:00Z").toEpochMilli()
        val out = build(
            character(
                gender = "女", birthday = birthday, appearanceDescription = "圆脸短发",
                occupation = "插画师", backstory = "在小城长大", speakingStyle = "慢条斯理",
                catchphrases = "「诶——」", initialInterests = "看展", cityName = "上海",
            ),
            cityName = "云野镇",
        ).lines()
        assertEquals(10, out.size)
        assertEquals("GEN<女>", out[0])
        assertTrue("年龄行在性别之后", out[1].startsWith("AGE<"))
        assertEquals("ZOD<双子座 ♊>", out[2])
        assertEquals("OCC<插画师>", out[3])
        assertEquals("APP<圆脸短发>", out[4])
        assertEquals("BAK<在小城长大>", out[5])
        assertEquals("SPK<慢条斯理>", out[6])
        assertEquals("CAT<「诶——」>", out[7])
        assertEquals("INT<看展>", out[8])
        assertEquals("CITY<云野镇>", out[9])
    }

    @Test fun `城市两级回落——日程行城市优先，无日程时回落角色卡，皆空则整行省略`() {
        // 日程行有城市（加入世界后的世界城名）→ 压过角色卡上的旧城市。
        assertEquals("CITY<云野镇>", build(character(cityName = "上海"), cityName = "云野镇"))
        // 无日程行 → 回落角色卡。
        assertEquals("CITY<上海>", build(character(cityName = "上海"), cityName = null))
        // 日程行城市是空白串 → 同样回落（判空在 trim 之后）。
        assertEquals("CITY<上海>", build(character(cityName = "上海"), cityName = "   "))
        // 两者皆空 → 无住址行。
        assertEquals("", build(character(cityName = null), cityName = null))
    }

    @Test fun `生日为空时星座与年龄各自缺席、互不牵连`() {
        // growing 模式无生日 → 年龄与星座都算不出，整块空。
        assertEquals("", build(character()))
        // fixed 模式有年龄但无生日 → 出年龄行、无星座行（年龄不被星座拖累）。
        val fixedOnly = build(character(ageModeRaw = "fixed", fixedAge = 24))
        assertEquals("AGE<24>", fixedOnly)
        // 有生日但 fixed 年龄为 0（算不出年龄）→ 出星座行、无年龄行（星座不被年龄拖累）。
        val zodiacOnly = build(
            character(ageModeRaw = "fixed", fixedAge = 0, birthday = Instant.parse("1988-06-15T00:00:00Z").toEpochMilli()),
        )
        assertEquals("ZOD<双子座 ♊>", zodiacOnly)
    }

    // MARK: - T1-2 宏替换（E3 / E4）

    @Test fun `字段里的 char 与 user 宏先替换后判空`() {
        val out = build(
            character(occupation = "{{char}} 的助理", speakingStyle = "总喊 {{user}} 全名"),
            userName = "小明",
        )
        assertEquals(listOf("OCC<小满 的助理>", "SPK<总喊 小明 全名>").joinToString("\n"), out)
        assertFalse("宏不得原样漏进提示词", out.contains("{{"))
    }

    @Test fun `替换后只剩空白的字段整行省略`() {
        // 纯空白字段 → trim 后为空 → 该行不出（且不留空行）。
        assertEquals("", build(character(occupation = "   \n  ", backstory = "\t")))
        // 未注册的宏保持字面量（与聊天侧同口径），不因此被判空。
        assertEquals("OCC<{{unknown}}>", build(character(occupation = "{{unknown}}")))
    }
}
