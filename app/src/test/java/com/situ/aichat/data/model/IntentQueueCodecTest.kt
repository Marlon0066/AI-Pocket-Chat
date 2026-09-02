package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T1-1（图纸 §7.2 · E22 / E36）：[IntentQueueState] 的编解码与访问器兜底。
 *
 * 断言从图纸 §3.1 / §3.2 的规格**独立反推**，不照抄实现：
 * - 默认值：队列空、复盘计数 0、上次复盘 0；单条意图默认 `BUDDING / 50 / 0 / 0 / residue=false`；`MAX_STORED = 12`、`REVIEW_ROUNDS = 150`
 * - 往返：`encode → decode` 逐字段相等，且 `encode(decode(encode(x))) == encode(x)`（字节相同）
 * - 空串 / 坏 JSON ⇒ `null`；访问器把两者**同路**回默认 `IntentQueueState()`（E36）
 * - `strength` 钳 `0..100`；13 条按 `lastChangeAt` 降序截 12 且**相对顺序不变**；`reviewRoundsAccrued` 钳 `0..150`
 * - 3 live + 3 RESOLVED + 3 FADED 的编码长度 ≤ 1600 字节（图纸 §7.2 T1-1 原写 1500：用 §3.1 锁定的 36 位 UUID 主键 +
 *   `encodeDefaults` 全键实测 1572，1500 不可达——施工登记偏差 D-2，此处取实测之上的最小整百作上限，留复核裁决）
 */
class IntentQueueCodecTest {

    private fun entity(json: String) = CharacterEntity(uuid = "c1", name = "林晚", creationDate = 0L, intentQueueJSON = json)

    private fun intent(
        id: String,
        kind: IntentKind = IntentKind.WANT_APOLOGIZE,
        state: IntentState = IntentState.ACTIVE,
        strength: Int = 50,
        bornAt: Long = 1_700_000_000_000L,
        lastChangeAt: Long = 1_700_000_000_000L,
        residue: Boolean = false,
    ) = CharacterIntent(id = id, kind = kind, state = state, strength = strength, bornAt = bornAt, lastChangeAt = lastChangeAt, residue = residue)

    // MARK: - 默认值

    @Test
    fun defaults_matchSpec() {
        val q = IntentQueueState()
        assertTrue(q.intents.isEmpty())
        assertEquals(0, q.reviewRoundsAccrued)
        assertEquals(0L, q.lastReviewAt)
        assertEquals(12, IntentQueueState.MAX_STORED)
        assertEquals(150, IntentQueueState.REVIEW_ROUNDS)

        val i = CharacterIntent(id = "x", kind = IntentKind.WANT_SHARE)
        assertEquals(IntentState.BUDDING, i.state)
        assertEquals(50, i.strength)
        assertEquals(0L, i.bornAt)
        assertEquals(0L, i.lastChangeAt)
        assertEquals(false, i.residue)
    }

    // MARK: - 往返

    @Test
    fun roundTrip_preservesEveryField_andBytesAreStable() {
        val original = IntentQueueState(
            intents = listOf(
                intent("a", IntentKind.WANT_COMFORT, IntentState.BUDDING, 60, 1_700_000_000_000L, 1_700_000_000_000L),
                intent("b", IntentKind.WANT_APOLOGIZE, IntentState.EXPRESSED, 25, 1_699_900_000_000L, 1_699_950_000_000L),
                intent("c", IntentKind.WANT_HIDE, IntentState.FADED, 9, 1_699_000_000_000L, 1_699_500_000_000L, residue = true),
                intent("d", IntentKind.WANT_CONFIRM, IntentState.RESOLVED, 0, 1_699_100_000_000L, 1_699_600_000_000L),
            ),
            reviewRoundsAccrued = 87,
            lastReviewAt = 1_698_000_000_000L,
        )
        val json = GrowthJson.encode(original)
        assertTrue("编码不得回落空串", json.isNotEmpty())
        val decoded = GrowthJson.decodeIntentQueueOrNull(json)
        assertEquals(original, decoded)
        assertEquals("往返字节相同", json, GrowthJson.encode(decoded!!))
    }

    @Test
    fun kindAndState_serializeWithCamelCaseKeys() {
        val json = GrowthJson.encode(IntentQueueState(intents = listOf(intent("a", IntentKind.WANT_APOLOGIZE, IntentState.EXPRESSED))))
        assertTrue(json, json.contains("\"kind\":\"wantApologize\""))
        assertTrue(json, json.contains("\"state\":\"expressed\""))
    }

    @Test
    fun encodedSize_threeLiveThreeResolvedThreeFaded_isAtMost1600Bytes() {
        val ts = 1_700_000_000_000L
        val intents = buildList {
            add(intent("11111111-2222-3333-4444-555555555501", IntentKind.WANT_COMFORT, IntentState.ACTIVE, 100, ts, ts))
            add(intent("11111111-2222-3333-4444-555555555502", IntentKind.WANT_APOLOGIZE, IntentState.EXPRESSED, 100, ts, ts))
            add(intent("11111111-2222-3333-4444-555555555503", IntentKind.WANT_CONFIRM, IntentState.BUDDING, 100, ts, ts))
            add(intent("11111111-2222-3333-4444-555555555504", IntentKind.WANT_PROBE, IntentState.RESOLVED, 100, ts, ts))
            add(intent("11111111-2222-3333-4444-555555555505", IntentKind.WANT_HIDE, IntentState.RESOLVED, 100, ts, ts))
            add(intent("11111111-2222-3333-4444-555555555506", IntentKind.WANT_SHARE, IntentState.RESOLVED, 100, ts, ts))
            add(intent("11111111-2222-3333-4444-555555555507", IntentKind.WANT_COMFORT, IntentState.FADED, 100, ts, ts, residue = true))
            add(intent("11111111-2222-3333-4444-555555555508", IntentKind.WANT_APOLOGIZE, IntentState.FADED, 100, ts, ts, residue = true))
            add(intent("11111111-2222-3333-4444-555555555509", IntentKind.WANT_CONFIRM, IntentState.FADED, 100, ts, ts, residue = true))
        }
        val worst = IntentQueueState(intents = intents, reviewRoundsAccrued = 150, lastReviewAt = ts)
        val bytes = GrowthJson.encode(worst).toByteArray(Charsets.UTF_8).size
        assertTrue("9 条意图编码 $bytes 字节，超过 1600（图纸原 1500 不可达·§11 D-2）", bytes <= 1600)
    }

    // MARK: - 空串 / 坏 JSON（E36）

    @Test
    fun emptyString_decodesToNull() {
        assertNull(GrowthJson.decodeIntentQueueOrNull(""))
    }

    @Test
    fun brokenJson_decodesToNull() {
        assertNull(GrowthJson.decodeIntentQueueOrNull("{这不是 JSON"))
        assertNull(GrowthJson.decodeIntentQueueOrNull("[1,2,3]"))
        assertNull(GrowthJson.decodeIntentQueueOrNull("null"))
        // 未知 kind：整份解码失败 ⇒ null（N-2：不认识的意图种类不许进队列）
        assertNull(GrowthJson.decodeIntentQueueOrNull("""{"intents":[{"id":"a","kind":"wantMoney"}]}"""))
    }

    @Test
    fun accessor_emptyAndBrokenColumn_bothFallBackToDefaultQueue() {
        assertEquals(IntentQueueState(), entity("").intentQueue)
        assertEquals(IntentQueueState(), entity("{坏").intentQueue)
        assertEquals(entity("").intentQueue, entity("{坏").intentQueue)
    }

    // MARK: - 钳位与截断

    @Test
    fun strength_isClampedInto0To100() {
        val decoded = GrowthJson.decodeIntentQueueOrNull(
            """{"intents":[{"id":"a","kind":"wantComfort","strength":250},{"id":"b","kind":"wantHide","strength":-7}]}""",
        )!!
        assertEquals(100, decoded.intents[0].strength)
        assertEquals(0, decoded.intents[1].strength)
    }

    @Test
    fun reviewRoundsAccrued_isClampedInto0To150() {
        assertEquals(150, GrowthJson.decodeIntentQueueOrNull("""{"reviewRoundsAccrued":999}""")!!.reviewRoundsAccrued)
        assertEquals(0, GrowthJson.decodeIntentQueueOrNull("""{"reviewRoundsAccrued":-3}""")!!.reviewRoundsAccrued)
        assertEquals(150, GrowthJson.decodeIntentQueueOrNull("""{"reviewRoundsAccrued":150}""")!!.reviewRoundsAccrued)
    }

    @Test
    fun thirteenIntents_keepTwelveNewestByLastChangeAt_preservingRelativeOrder() {
        // 13 条：lastChangeAt 依次 13,1,2,…,12（第 0 条最新、第 1 条最旧）⇒ 丢掉 lastChangeAt = 1 的那条（id "i1"），其余按原序
        val ids = (0..12).map { "i$it" }
        val stamps = listOf(13L) + (1L..12L)
        val json = GrowthJson.encode(
            IntentQueueState(intents = ids.zip(stamps).map { (id, t) -> intent(id, lastChangeAt = t * 1_000L) }),
        )
        val decoded = GrowthJson.decodeIntentQueueOrNull(json)!!
        assertEquals(12, decoded.intents.size)
        assertEquals(listOf("i0", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9", "i10", "i11", "i12"), decoded.intents.map { it.id })
    }

    @Test
    fun twelveIntents_areAllKept_inOriginalOrder() {
        val ids = (0..11).map { "i$it" }
        val json = GrowthJson.encode(IntentQueueState(intents = ids.mapIndexed { i, id -> intent(id, lastChangeAt = (12 - i) * 1_000L) }))
        assertEquals(ids, GrowthJson.decodeIntentQueueOrNull(json)!!.intents.map { it.id })
    }

    // MARK: - 老 JSON 缺键 / 未来多键

    @Test
    fun missingKeys_takeDefaults_andUnknownKeysAreIgnored() {
        val decoded = GrowthJson.decodeIntentQueueOrNull("""{"intents":[{"id":"a","kind":"wantShare","futureField":1}],"futureTop":2}""")!!
        val i = decoded.intents.single()
        assertEquals(IntentKind.WANT_SHARE, i.kind)
        assertEquals(IntentState.BUDDING, i.state)
        assertEquals(50, i.strength)
        assertEquals(0L, i.bornAt)
        assertEquals(false, i.residue)
        assertEquals(0, decoded.reviewRoundsAccrued)
        assertEquals(0L, decoded.lastReviewAt)
    }

    @Test
    fun accessor_decodesValidColumn() {
        val json = GrowthJson.encode(IntentQueueState(intents = listOf(intent("a", IntentKind.WANT_PROBE)), reviewRoundsAccrued = 42))
        val q = entity(json).intentQueue
        assertEquals(IntentKind.WANT_PROBE, q.intents.single().kind)
        assertEquals(42, q.reviewRoundsAccrued)
    }
}
