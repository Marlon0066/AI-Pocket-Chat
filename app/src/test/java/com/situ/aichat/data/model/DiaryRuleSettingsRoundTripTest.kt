package com.situ.aichat.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2-3（图纸 §7·E16/E17）：日记写作规则 8 字段随 `AppSettings` **整份序列化往返进备份**——图纸 F8 判定
 * 「备份零工作」的守卫钉。备份两侧的 Json 配置逐字取自 `BackupExporter`/`BackupImporter`
 * （`ignoreUnknownKeys=true` + `encodeDefaults=false`）。
 */
class DiaryRuleSettingsRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    @Test fun `默认实例往返后 8 字段不变`() {
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), AppSettings()))
        assertEquals(AppSettings.DEFAULT_DIARY_WORD_COUNT, back.diaryWordCount)
        assertEquals("", back.diaryNarrativePerson)
        assertEquals("", back.diaryStyleHint)
        assertEquals("", back.diaryExtraRules)
        assertEquals(AppSettings.DEFAULT_DIARY_WORD_COUNT, back.diaryExchangeWordCount)
        assertEquals("", back.diaryExchangeNarrativePerson)
        assertEquals("", back.diaryExchangeStyleHint)
        assertEquals("", back.diaryExchangeExtraRules)
    }

    @Test fun `自定义值往返后逐字保真（含百分号与换行）`() {
        val custom = AppSettings(
            diaryWordCount = 1500,
            diaryNarrativePerson = "用「我」写",
            diaryStyleHint = "保留 100% 的真诚",
            diaryExtraRules = "别写天气\n多写手上的动作",
            diaryExchangeWordCount = 800,
            diaryExchangeNarrativePerson = "第一人称写{角色名}自己",
            diaryExchangeStyleHint = "多写对{用户名}的在意",
            diaryExchangeExtraRules = "不要写成给我的信",
        )
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), custom))
        assertEquals(custom.diaryWordCount, back.diaryWordCount)
        assertEquals(custom.diaryNarrativePerson, back.diaryNarrativePerson)
        assertEquals(custom.diaryStyleHint, back.diaryStyleHint)
        assertEquals(custom.diaryExtraRules, back.diaryExtraRules)
        assertEquals(custom.diaryExchangeWordCount, back.diaryExchangeWordCount)
        assertEquals(custom.diaryExchangeNarrativePerson, back.diaryExchangeNarrativePerson)
        assertEquals(custom.diaryExchangeStyleHint, back.diaryExchangeStyleHint)
        assertEquals(custom.diaryExchangeExtraRules, back.diaryExchangeExtraRules)
    }

    @Test fun `老备份（不含这 8 个字段）解码 - 8 项取默认且其它字段不受影响`() {
        // 一段「本次改动之前」形态的旧 appSettings JSON：只带几个别的字段，8 个新键一个都没有。
        val legacy = """
            {"shortTermMemoryLength":40,"diaryCommentDelay":9,"lastViewedDiaryDate":1700000000000,
             "diaryExchangePartnerUuid":"c1","momentAutoPostFrequency":3}
        """.trimIndent()
        val back = json.decodeFromString(AppSettings.serializer(), legacy)
        // 8 项取默认（= 与升级前行为一致）。
        assertEquals(AppSettings.DEFAULT_DIARY_WORD_COUNT, back.diaryWordCount)
        assertEquals(AppSettings.DEFAULT_DIARY_WORD_COUNT, back.diaryExchangeWordCount)
        assertEquals("", back.diaryNarrativePerson)
        assertEquals("", back.diaryStyleHint)
        assertEquals("", back.diaryExtraRules)
        assertEquals("", back.diaryExchangeNarrativePerson)
        assertEquals("", back.diaryExchangeStyleHint)
        assertEquals("", back.diaryExchangeExtraRules)
        // 老包里真有的字段照旧恢复，不被新字段挤掉。
        assertEquals(40, back.shortTermMemoryLength)
        assertEquals(9, back.diaryCommentDelay)
        assertEquals(1_700_000_000_000L, back.lastViewedDiaryDate)
        assertEquals("c1", back.diaryExchangePartnerUuid)
        assertEquals(3, back.momentAutoPostFrequency)
    }
}
