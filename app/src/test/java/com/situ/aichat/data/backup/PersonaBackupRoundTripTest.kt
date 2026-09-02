package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonaCompileMeta
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.affectField
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.data.model.intentQueue
import com.situ.aichat.data.model.personaCompileMeta
import com.situ.aichat.data.model.personaGains
import com.situ.aichat.data.model.personaOperators
import com.situ.aichat.data.model.personalityAnchor
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.model.toQuality
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷一《人设编译器》T2-6（图纸 §7.2 · Y-E22）：人设编译四新列的**备份三处对称**看门。
 *
 * 三处对称（`BackupModels.CharacterExport` 字段 / `BackupExportMappers` 搬运 / `BackupImportMappers` 搬运）
 * 缺任一处，恢复备份后编译成果全丢且**零报错**——这条测试就是那道网。断言从图纸 §表3 与 Y-E22 独立反推：
 * - 四列 Entity → Export → JSON → Export → Entity 往返**逐字节相等**
 * - **老备份**（JSON 里根本没有这四个键）导入不崩，四列落 `""` ⇒ 三个解码访问器走默认、锚点走 Y-1 兜底
 *
 * 注：仓里既有的 [BackupMapperRoundTripTest] 只覆盖钱路实体，角色实体的往返此前**无任何测试**（图纸 Y-F20 的
 * grep 用错了类名 `BackupCharacter`，真名是 `CharacterExport`）——本条是角色侧的第一张网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaBackupRoundTripTest {

    /** 与备份实际使用的 Json 同口径：老包缺键必须靠 kotlinx 默认值兜底，不能抛。 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun character(
        anchor: String = "",
        meta: String = "",
        gains: String = "",
        operators: String = "",
        pressure: String = "",
        affect: String = "",
        intent: String = "",
        /** 修缮卷 F19：访问器交叉校验「压强派生净额 == 净额列」，压强列非空的用例须传自洽的净额列（否则按规格回落播种）。 */
        quality: String = """{"familiarity":72,"trust":55,"attachment":88}""",
    ) = CharacterEntity(
        uuid = "c1",
        name = "林晚",
        creationDate = 1_700_000_000_000L,
        personalityDescription = "高冷毒舌、嘴硬心软、怕黑",
        personalitySpectrumJSON = """{"extroversion":44,"warmth":41}""",
        personalityAnchorJSON = anchor,
        personaCompileMetaJSON = meta,
        personaGainsJSON = gains,
        personaOperatorsJSON = operators,
        relationshipQualityJSON = quality,
        relationshipPressureJSON = pressure,
        affectFieldJSON = affect,
        intentQueueJSON = intent,
    )

    @Test
    fun personaColumns_surviveEntityExportJsonRoundTrip() {
        val anchor = """{"extroversion":30,"emotionality":50,"adventurousness":50,"warmth":25,"humor":70,""" +
            """"independence":75,"curiosity":50,"openness":35}"""
        val meta = """{"source":"compiled","compiledAt":1700000000000,"personaHash":"a1b2c3d4e5f60718",""" +
            """"lastFailedAt":0,"droppedCount":2}"""
        val gains = """{"system":{"g02":2,"g04":0,"g25":2},"custom":[{"id":"u1","label":"被叫全名",""" +
            """"level":2,"origin":"compiled"}]}"""
        val operators = """[{"id":"o1","condition":"c01","action":"a01","enabled":true}]"""
        val entity = character(anchor = anchor, meta = meta, gains = gains, operators = operators)

        val export = entity.toExport(avatarArchiveKey = null, chatWallpaperArchiveKey = null)
        val decoded = json.decodeFromString(
            CharacterExport.serializer(),
            json.encodeToString(CharacterExport.serializer(), export),
        )
        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)

        // 四列绝对快照逐字穿越（备份恢复 = 快照覆盖，绝不重算）。
        assertEquals(anchor, back.personalityAnchorJSON)
        assertEquals(meta, back.personaCompileMetaJSON)
        assertEquals(gains, back.personaGainsJSON)
        assertEquals(operators, back.personaOperatorsJSON)
        // 现值列不受牵连。
        assertEquals(entity.personalitySpectrumJSON, back.personalitySpectrumJSON)
        // 解码后语义也对（不是只搬了字符串）。
        assertEquals(25, back.personalityAnchor.warmth)
        assertEquals(PersonaCompileMeta.SOURCE_COMPILED, back.personaCompileMeta.source)
        assertEquals(2, back.personaCompileMeta.droppedCount)
        assertEquals(2, back.personaGains.system["g02"])
        assertEquals(1, back.personaGains.custom.size)
        assertEquals("c01", back.personaOperators.single().condition)
    }

    @Test
    fun legacyBackupWithoutPersonaFields_importsToEmptyColumnsAndDefaults() {
        // 老备份：JSON 里连这四个键都没有（模拟卷一之前导出的包）。
        val legacyJson = """
            {"uuid":"c1","name":"林晚","creationDate":1700000000000,
             "personalityDescription":"高冷毒舌、嘴硬心软、怕黑",
             "personalitySpectrumJSON":"{\"extroversion\":44,\"warmth\":41}"}
        """.trimIndent()
        val decoded = json.decodeFromString(CharacterExport.serializer(), legacyJson)

        assertEquals("老包缺字段 ⇒ 空串兜底，不抛", "", decoded.personalityAnchorJSON)
        assertEquals("", decoded.personaCompileMetaJSON)
        assertEquals("", decoded.personaGainsJSON)
        assertEquals("", decoded.personaOperatorsJSON)

        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)
        // 行为等同「从未编译过」：不崩、不清零。
        assertEquals(PersonaCompileMeta.SOURCE_DEFAULT, back.personaCompileMeta.source)
        assertEquals(0L, back.personaCompileMeta.compiledAt)
        assertTrue(back.personaGains.system.isEmpty())
        assertTrue(back.personaGains.custom.isEmpty())
        assertTrue(back.personaOperators.isEmpty())
        // 锚点走 Y-1 兜底 = 恢复出来的现值本身（本性 == 现在 ⇒ 竖线自动隐藏）。
        assertEquals(44, back.personalityAnchor.extroversion)
        assertEquals(41, back.personalityAnchor.warmth)
        assertEquals(back.personalitySpectrum, back.personalityAnchor)
    }

    // MARK: - 卷二《正负双压》T2-8（图纸 §7.2 · P-E22）：压强列的备份三处对称

    @Test
    fun `pressureColumn_survivesEntityExportJsonRoundTrip`() {
        // 一个「正负双高」的角色：依恋 正80/负75（净额 5）——恰恰是备份丢了就再也养不回来的那种状态。
        val pressure = GrowthJson.encode(
            RelationshipPressure(
                pos = listOf(72, 55, 40, 30, 60, 20, 80, 80),
                neg = listOf(0, 0, 0, 0, 0, 0, 60, 75),
            ),
        )
        // 净额列与压强列自洽（I-1）——修缮卷 F19 起访问器会把不自洽的列当坏列回落播种。
        val entity = character(pressure = pressure, quality = GrowthJson.encode(GrowthJson.decodeRelationshipPressure(pressure).toQuality()))

        val export = entity.toExport(avatarArchiveKey = null, chatWallpaperArchiveKey = null)
        val decoded = json.decodeFromString(
            CharacterExport.serializer(),
            json.encodeToString(CharacterExport.serializer(), export),
        )
        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)

        assertEquals("压强列必须逐字节穿越三处搬运", pressure, back.relationshipPressureJSON)
        assertEquals("净额列不受牵连", entity.relationshipQualityJSON, back.relationshipQualityJSON)
        // 解码后语义也对：那两股力还在，没被压成一个数。
        assertEquals(80, back.relationshipPressure.pos[7])
        assertEquals(75, back.relationshipPressure.neg[7])
        assertEquals("派生净额仍是 5", 5, back.relationshipPressure.toQuality().attachment)
    }

    @Test
    fun `legacyBackupWithoutPressureField_importsToEmptyColumnAndSeeds`() {
        // 老备份：JSON 里没有 relationshipPressureJSON 这个键（卷二之前导出的包）。
        val legacyJson = """
            {"uuid":"c1","name":"林晚","creationDate":1700000000000,
             "relationshipQualityJSON":"{\"familiarity\":72,\"trust\":55,\"attachment\":88}"}
        """.trimIndent()
        val decoded = json.decodeFromString(CharacterExport.serializer(), legacyJson)

        assertEquals("老包缺字段 ⇒ 空串兜底，不抛", "", decoded.relationshipPressureJSON)

        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)
        // 空列 ⇒ 访问器按 fromQuality 播种：正压 = 恢复出来的净额、负压 0（不崩、不清零）。
        assertEquals(RelationshipPressure.fromQuality(back.relationshipQuality), back.relationshipPressure)
        assertEquals(72, back.relationshipPressure.pos[0])
        assertTrue(back.relationshipPressure.neg.all { it == 0 })
        assertEquals("播种后派生净额与恢复出来的那一列完全一致", back.relationshipQuality, back.relationshipPressure.toQuality())
    }

    // MARK: - 卷三《场内核与渲染收编》T2-5（图纸 §7.2 · E22）：四场列的备份三处对称

    @Test
    fun `affectFieldColumn_survivesEntityExportJsonRoundTrip`() {
        // 一个「昨晚刚吵过架」的角色：效价 −70、激活 12、命中里还挂着 g13——恰是备份丢了就凭空开朗起来的那种状态。
        val affect = GrowthJson.encode(
            AffectField(
                security = 62, investment = 71, valence = -70, arousal = 12,
                updatedAt = 1_700_000_000_000L, budgetDayStart = 1_699_977_600_000L, budgetUsed = 17,
                hits = listOf("g13", "g05", AffectField.BAND_UP), hitsAt = 1_699_999_000_000L,
            ),
        )
        val entity = character(affect = affect)

        val export = entity.toExport(avatarArchiveKey = null, chatWallpaperArchiveKey = null)
        val decoded = json.decodeFromString(
            CharacterExport.serializer(),
            json.encodeToString(CharacterExport.serializer(), export),
        )
        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)

        assertEquals("场列必须逐字节穿越三处搬运", affect, back.affectFieldJSON)
        assertEquals("净额列不受牵连", entity.relationshipQualityJSON, back.relationshipQualityJSON)
        assertEquals("压强列不受牵连", entity.relationshipPressureJSON, back.relationshipPressureJSON)
        // 解码后语义也对：那股闷气与命中都还在。
        assertEquals(-70, back.affectField.valence)
        assertEquals(12, back.affectField.arousal)
        assertEquals(17, back.affectField.budgetUsed)
        assertEquals(listOf("g13", "g05", "bandUp"), back.affectField.hits)
    }

    @Test
    fun `legacyBackupWithoutAffectField_importsToEmptyColumnAndDefaultField`() {
        // 老备份：JSON 里没有 affectFieldJSON 这个键（卷三之前导出的包）。
        val legacyJson = """
            {"uuid":"c1","name":"林晚","creationDate":1700000000000,
             "relationshipQualityJSON":"{\"familiarity\":72,\"trust\":55,\"attachment\":88}"}
        """.trimIndent()
        val decoded = json.decodeFromString(CharacterExport.serializer(), legacyJson)

        assertEquals("老包缺字段 ⇒ 空串兜底，不抛", "", decoded.affectFieldJSON)

        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)
        // 空列 ⇒ 访问器回默认场（安全感 50 / 投入 30 / 效价 0 / 激活 30，命中空）：不崩、不清零、不补算。
        assertEquals(AffectField(), back.affectField)
        assertEquals(50, back.affectField.security)
        assertEquals(30, back.affectField.investment)
        assertEquals(0, back.affectField.valence)
        assertEquals(30, back.affectField.arousal)
        assertTrue(back.affectField.hits.isEmpty())
    }

    @Test
    fun `修缮卷 E45 新增 JSON 字段随原串透传 老包缺字段回默认`() {
        // 修缮卷给场列加了 slowRefAt / slowDayUsed / pullbackDone、压强列加了 relaxedAt——备份映射零改动（原串透传·F26），
        // 这里钉「新字段真的跟着走」+「老包（卷三 / 卷二格式的列串）缺这些键回默认」。
        val affect = GrowthJson.encode(
            AffectField(security = 62, investment = 71, slowRefAt = 1_699_900_000_000L, slowDayUsed = listOf(9, 4), pullbackDone = true),
        )
        val pressure = GrowthJson.encode(RelationshipPressure(pos = listOf(72, 55, 10, 10, 35, 20, 5, 88), neg = List(8) { 0 }, relaxedAt = 1_699_800_000_000L))
        val entity = character(affect = affect, pressure = pressure)   // 净额列默认串 = [72,55,10,10,35,20,5,88]，与压强自洽

        val export = entity.toExport(avatarArchiveKey = null, chatWallpaperArchiveKey = null)
        val back = json.decodeFromString(CharacterExport.serializer(), json.encodeToString(CharacterExport.serializer(), export))
            .toEntity(avatarPath = null, chatWallpaperPath = null)

        assertEquals("场列原串透传", affect, back.affectFieldJSON)
        assertEquals("压强列原串透传", pressure, back.relationshipPressureJSON)
        assertEquals(1_699_900_000_000L, back.affectField.slowRefAt)
        assertEquals(listOf(9, 4), back.affectField.slowDayUsed)
        assertEquals(true, back.affectField.pullbackDone)
        assertEquals(1_699_800_000_000L, back.relationshipPressure.relaxedAt)

        // 老包：列串是卷三 / 卷二格式（没有新键）⇒ 新字段回默认，其余字段照旧。
        val legacy = character(
            affect = """{"security":62,"investment":71,"valence":-70,"arousal":12}""",
            pressure = """{"pos":[72,55,10,10,35,20,5,88],"neg":[0,0,0,0,0,0,0,0]}""",
        )
        val legacyBack = json.decodeFromString(CharacterExport.serializer(), json.encodeToString(CharacterExport.serializer(), legacy.toExport(null, null)))
            .toEntity(avatarPath = null, chatWallpaperPath = null)
        assertEquals(0L, legacyBack.affectField.slowRefAt)
        assertEquals(listOf(0, 0), legacyBack.affectField.slowDayUsed)
        assertEquals(false, legacyBack.affectField.pullbackDone)
        assertEquals(-70, legacyBack.affectField.valence)
        assertEquals(0L, legacyBack.relationshipPressure.relaxedAt)
        assertEquals(72, legacyBack.relationshipPressure.pos[0])
    }

    // MARK: - 卷四《意图队列 + 性格复盘》T2-7（图纸 §7.2 · E22）：意图列的备份三处对称

    @Test
    fun `intentQueueColumn_survivesEntityExportJsonRoundTrip`() {
        // 一个「想道歉但嘴硬、复盘攒了 120 轮」的角色——恰是备份丢了就凭空释然的那种状态。
        val intent = GrowthJson.encode(
            IntentQueueState(
                intents = listOf(
                    CharacterIntent(
                        id = "i1", kind = IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED, strength = 25,
                        bornAt = 1_699_900_000_000L, lastChangeAt = 1_699_950_000_000L,
                    ),
                ),
                reviewRoundsAccrued = 120,
                lastReviewAt = 1_698_000_000_000L,
            ),
        )
        val entity = character(intent = intent)

        val export = entity.toExport(avatarArchiveKey = null, chatWallpaperArchiveKey = null)
        val decoded = json.decodeFromString(
            CharacterExport.serializer(),
            json.encodeToString(CharacterExport.serializer(), export),
        )
        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)

        assertEquals("意图列必须逐字节穿越三处搬运", intent, back.intentQueueJSON)
        assertEquals("场列不受牵连", entity.affectFieldJSON, back.affectFieldJSON)
        assertEquals("净额列不受牵连", entity.relationshipQualityJSON, back.relationshipQualityJSON)
        // 解码后语义也对：那份没说完的道歉与复盘计数都还在。
        assertEquals(IntentKind.WANT_APOLOGIZE, back.intentQueue.intents.single().kind)
        assertEquals(IntentState.EXPRESSED, back.intentQueue.intents.single().state)
        assertEquals(120, back.intentQueue.reviewRoundsAccrued)
    }

    @Test
    fun `legacyBackupWithoutIntentQueue_importsToEmptyColumnAndDefaultQueue`() {
        // 老备份：JSON 里没有 intentQueueJSON 这个键（卷四之前导出的包）。
        val legacyJson = """
            {"uuid":"c1","name":"林晚","creationDate":1700000000000,
             "relationshipQualityJSON":"{\"familiarity\":72,\"trust\":55,\"attachment\":88}"}
        """.trimIndent()
        val decoded = json.decodeFromString(CharacterExport.serializer(), legacyJson)

        assertEquals("老包缺字段 ⇒ 空串兜底，不抛", "", decoded.intentQueueJSON)

        val back = decoded.toEntity(avatarPath = null, chatWallpaperPath = null)
        // 空列 ⇒ 访问器回默认队列（空队列 / 计数 0 / 从未复盘）：不崩、不清零、不补算。
        assertEquals(IntentQueueState(), back.intentQueue)
        assertTrue(back.intentQueue.intents.isEmpty())
        assertEquals(0, back.intentQueue.reviewRoundsAccrued)
        assertEquals(0L, back.intentQueue.lastReviewAt)
    }
}
