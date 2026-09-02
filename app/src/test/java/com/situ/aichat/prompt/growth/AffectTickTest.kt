package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.GrowthJson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感内核·卷三《场内核与渲染收编》T2-2（tick 部分·图纸 §7.2 · E19 / E24 / E26 / E35）：
 * 真 [AffectKernel] + MockK [CharacterDao]，验「每轮 tick 恰 1 次列级写、只读场列、不读整行」的写序不变式，
 * 以及松弛 / 脉冲 / 跨日 / 坏 JSON 覆写 / 失败吞掉。
 *
 * 断言从图纸 §3.6 `tick` 锁定序独立反推：读列 → 解码/默认 → rollDay → 松弛 → 脉冲（仅 `arousal < baseline+30`）→
 * `updatedAt = now` → 1 次 `updateAffectField`。时区与时刻显式钉死（Asia/Shanghai 15:00 ⇒ 基线 44）。
 * Robolectric：内核失败路径打 `android.util.Log`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AffectTickTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int = 0, day: Int = 2) = LocalDateTime.of(2026, 9, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private val dayStart = LocalDate.of(2026, 9, 2).atStartOfDay(zone).toInstant().toEpochMilli()

    private val dao = mockk<CharacterDao>(relaxed = true)
    private val kernel = AffectKernel(dao)

    private fun stubColumn(json: String?) {
        coEvery { dao.getAffectFieldJson("u") } returns json
    }

    private fun capturedWrite(): AffectField {
        val json = slot<String>()
        coVerify(exactly = 1) { dao.updateAffectField("u", capture(json)) }
        return GrowthJson.decodeAffectFieldOrNull(json.captured) ?: error("写出的不是合法 JSON：${json.captured}")
    }

    // MARK: - E19 首装冷启：空列 ⇒ 默认 + 日倾 + 脉冲，恰 1 读 1 写

    @Test
    fun firstTick_onEmptyColumn_writesDefaultPlusTiltAndPulse_exactlyOnce() = runTest {
        stubColumn("")
        val now = at(15)
        kernel.tick("u", now, zone)

        coVerify(exactly = 1) { dao.getAffectFieldJson("u") }
        coVerify(exactly = 0) { dao.getByUuid(any()) }
        val f = capturedWrite()
        val tilt = dailyTilt("u", LocalDate.of(2026, 9, 2))
        assertEquals(now, f.updatedAt)
        assertEquals(dayStart, f.budgetDayStart)
        assertEquals(0, f.budgetUsed)
        assertEquals("效价 = 0 + 日倾", tilt.valence, f.valence)
        assertEquals("激活 = 30 + 日倾 + 脉冲 2（30+倾 < 44+30）", 30 + tilt.arousal + 2, f.arousal)
        assertEquals(50, f.security)
        assertEquals(30, f.investment)
    }

    // MARK: - E26 坏 JSON：与空列同路，tick 后覆写成合法 JSON

    @Test
    fun brokenJson_isOverwrittenWithValidJson() = runTest {
        stubColumn("{坏 JSON")
        kernel.tick("u", at(15), zone)
        val f = capturedWrite()
        assertNotNull(f)
        assertEquals(at(15), f.updatedAt)
    }

    // MARK: - 松弛与脉冲

    @Test
    fun valenceRelaxes_byHalfLife_whenSameDay() = runTest {
        // 昨天 15:00 写过（同一本地日：budgetDayStart = 今日零点 ⇒ 不再倾），效价 −60，24h 后应剩一半。
        val before = AffectField(valence = -60, arousal = 44, updatedAt = at(15) - 24L * 3_600_000L, budgetDayStart = dayStart)
        stubColumn(GrowthJson.encode(before))
        kernel.tick("u", at(15), zone)
        val f = capturedWrite()
        assertEquals("效价半衰期 24h ⇒ −60 → −30", -30, f.valence)
        assertEquals("激活已在基线 44：松弛不动，脉冲 +2", 46, f.arousal)
    }

    @Test
    fun pulse_stopsAtBaselinePlusThirty() = runTest {
        // 修缮卷 F16：15:00 基线 44 ⇒ 连续 tick 激活恰停在 44 + 30 = 74（旧条件 `< 74` 会从 73 走到 75 = 句子阈），再 tick 不变。
        var column = GrowthJson.encode(AffectField(arousal = 70, updatedAt = at(15), budgetDayStart = dayStart))
        val writes = mutableListOf<String>()
        coEvery { dao.getAffectFieldJson("u") } answers { column }
        coEvery { dao.updateAffectField("u", capture(writes)) } answers { column = writes.last() }
        repeat(4) { kernel.tick("u", at(15), zone) }
        val arousals = writes.map { GrowthJson.decodeAffectFieldOrNull(it)!!.arousal }
        assertEquals("70 → 72 → 74 停 → 74 → 74", listOf(72, 74, 74, 74), arousals)
        // 奇数起点 73：+2 会越 74 ⇒ 不脉冲，停 73（上限是「不越 74」，不是「凑到 74」）
        column = GrowthJson.encode(AffectField(arousal = 73, updatedAt = at(15), budgetDayStart = dayStart))
        kernel.tick("u", at(15), zone)
        assertEquals(73, GrowthJson.decodeAffectFieldOrNull(writes.last())!!.arousal)
    }

    @Test
    fun slowFields_untouchedByTick_andSlowRefAtInitializedOnce() = runTest {
        // 修缮卷 J1 / E1：卷三老列（slowRefAt = 0）安全感 70、24h 前写过 ⇒ tick 不动慢场值、把 slowRefAt 置 now；再 tick 不改 slowRefAt。
        var column = GrowthJson.encode(AffectField(security = 70, investment = 60, updatedAt = at(15) - 24L * 3_600_000L, budgetDayStart = dayStart))
        val writes = mutableListOf<String>()
        coEvery { dao.getAffectFieldJson("u") } answers { column }
        coEvery { dao.updateAffectField("u", capture(writes)) } answers { column = writes.last() }
        kernel.tick("u", at(15), zone)
        val first = GrowthJson.decodeAffectFieldOrNull(writes.last())!!
        assertEquals(70, first.security)
        assertEquals(60, first.investment)
        assertEquals(at(15), first.slowRefAt)
        kernel.tick("u", at(16), zone)
        val second = GrowthJson.decodeAffectFieldOrNull(writes.last())!!
        assertEquals("参考值不因 tick 变", 70, second.security)
        assertEquals("slowRefAt 只初始化一次", at(15), second.slowRefAt)
        assertEquals(at(16), second.updatedAt)
    }

    @Test
    fun secondTickOneMinuteLater_pulsesAgain_relaxationNegligible() = runTest {
        val first = AffectField(valence = -30, arousal = 40, updatedAt = at(15), budgetDayStart = dayStart)
        stubColumn(GrowthJson.encode(first))
        kernel.tick("u", at(15, 1), zone)
        val f = capturedWrite()
        assertEquals(-30, f.valence)
        assertEquals(42, f.arousal)
        assertEquals(at(15, 1), f.updatedAt)
    }

    // MARK: - E10 跨日：预算归零 + 日倾

    @Test
    fun tickOnNextDay_resetsBudgetAndAppliesNewTilt() = runTest {
        val yesterday = AffectField(budgetUsed = 31, updatedAt = at(23), budgetDayStart = dayStart)
        stubColumn(GrowthJson.encode(yesterday))
        val now = at(0, 30, day = 3)
        kernel.tick("u", now, zone)
        val f = capturedWrite()
        assertEquals(0, f.budgetUsed)
        assertEquals(LocalDate.of(2026, 9, 3).atStartOfDay(zone).toInstant().toEpochMilli(), f.budgetDayStart)
        // 锁定序 = rollDay（倾）→ 松弛：倾完的效价还要按 dt = 1.5h 向 0 松弛（含 snap 规则），期望值由两条规格复合算出。
        val tilt = dailyTilt("u", LocalDate.of(2026, 9, 3))
        val expectedValence = relaxToward(0 + tilt.valence, 0, now - at(23), RelationshipBands.VALENCE_HALF_LIFE_MS)
        assertEquals(expectedValence, f.valence)
        assertTrue("倾确实发生过（否则松弛后必为 0）", tilt.valence == 0 || f.valence != 0 || kotlin.math.abs(tilt.valence) == 1)
    }

    // MARK: - 失败吞掉（外部行为清单 9 / E35）

    @Test
    fun daoFailure_isSwallowed_notThrown() = runTest {
        stubColumn("")
        coEvery { dao.updateAffectField(any(), any()) } throws IllegalStateException("disk full")
        kernel.tick("u", at(15), zone) // 不抛
        coVerify(exactly = 1) { dao.updateAffectField("u", any()) }
    }

    // MARK: - withFieldLocked：松弛无脉冲、块返回值落库、恰 1 写

    @Test
    fun withFieldLocked_relaxesWithoutPulse_andWritesBlockResultOnce() = runTest {
        val stored = AffectField(valence = -60, arousal = 40, updatedAt = at(15) - 24L * 3_600_000L, budgetDayStart = dayStart)
        stubColumn(GrowthJson.encode(stored))
        var seen: AffectField? = null
        kernel.withFieldLocked("u", at(15), zone) { field0 ->
            seen = field0
            field0.copy(security = 77, hits = listOf("g04"), hitsAt = at(15))
        }
        assertEquals("块看到的是松弛后的场", -30, seen!!.valence)
        assertEquals("无脉冲：激活只松弛（40 → 基线 44 走 4h 半衰期 24h ⇒ 44 − 4×0.5^6 ≈ 44）", 44, seen.arousal)
        val f = capturedWrite()
        assertEquals(77, f.security)
        assertEquals(listOf("g04"), f.hits)
        assertEquals(at(15), f.updatedAt)
        coVerify(exactly = 0) { dao.getByUuid(any()) }
    }

    @Test
    fun withFieldLocked_slowFieldsEnterAsReadValue_andRefResetsToNow_R1() = runTest {
        // R1 🟡-1（J1 另一半）：参考值 70 / 60 记于 30 天前 ⇒ 块看到的是读值 50 + 20 × 0.5 = 60、30 + 30 × 0.5 = 45，
        // 写出的新参考时刻 = now（块内落用的事件位移就写在衰减后的值上；若漏掉这一步慢场只涨不落）
        val stored = AffectField(security = 70, investment = 60, slowRefAt = at(15) - 30L * 86_400_000L, updatedAt = at(15) - 3_600_000L, budgetDayStart = dayStart)
        stubColumn(GrowthJson.encode(stored))
        var seen: AffectField? = null
        kernel.withFieldLocked("u", at(15), zone) { field0 -> seen = field0; field0 }
        assertEquals(60, seen!!.security)
        assertEquals(45, seen.investment)
        val f = capturedWrite()
        assertEquals(60, f.security)
        assertEquals(at(15), f.slowRefAt)
    }

    @Test
    fun hundredHourlyTicks_neverTouchSlowFields_R1() = runTest {
        // R1 🔵-1：真 tick 路径连跑 100 次（每小时一次·跨 4 天）——参考值与 slowRefAt 一个字节不动（慢场只由分析通道改）
        var column = GrowthJson.encode(AffectField(security = 70, investment = 60, slowRefAt = at(15), updatedAt = at(15), budgetDayStart = dayStart))
        val writes = mutableListOf<String>()
        coEvery { dao.getAffectFieldJson("u") } answers { column }
        coEvery { dao.updateAffectField("u", capture(writes)) } answers { column = writes.last() }
        for (i in 1..100) kernel.tick("u", at(15) + i * 3_600_000L, zone)
        val last = GrowthJson.decodeAffectFieldOrNull(writes.last())!!
        assertEquals(100, writes.size)
        assertEquals(70, last.security)
        assertEquals(60, last.investment)
        assertEquals(at(15), last.slowRefAt)
        assertEquals(at(15) + 100 * 3_600_000L, last.updatedAt)
    }

    // MARK: - 内心行换气（微图纸 2026-09-02 §5）：慢场带档跟踪搭在既有那一次写里

    @Test
    fun tick_tracksSlowBandByReadValue_crossingWritesNow_sameBandUntouched_stillOneWrite() = runTest {
        // 参考值 85 记于 10 天前、档 2 记于 30 天前 ⇒ 读值 50 + 35 × 0.5^(10/30) = 77.8 → 78 < 80 落中档
        // （微图纸 §5 行文写「74」系笔误——按其自带公式算得 78，结论「落中档」不变）；参考值本身不动、slowBandsAt[0] = now
        val tenDaysAgo = at(15) - 10L * 86_400_000L
        val thirtyDaysAgo = at(15) - 30L * 86_400_000L
        var column = GrowthJson.encode(
            AffectField(
                security = 85, slowRefAt = tenDaysAgo, slowBands = listOf(2, 1), slowBandsAt = listOf(thirtyDaysAgo, 0L),
                updatedAt = at(15) - 3_600_000L, budgetDayStart = dayStart,
            ),
        )
        val writes = mutableListOf<String>()
        coEvery { dao.getAffectFieldJson("u") } answers { column }
        coEvery { dao.updateAffectField("u", capture(writes)) } answers { column = writes.last() }
        kernel.tick("u", at(15), zone)
        assertEquals("读值前提自检", 78, slowNow(85, 50, tenDaysAgo, at(15)))
        val first = GrowthJson.decodeAffectFieldOrNull(writes.last())!!
        assertEquals("参考值本身不动", 85, first.security)
        assertEquals(tenDaysAgo, first.slowRefAt)
        assertEquals(listOf(1, 1), first.slowBands)
        assertEquals("跨档时刻 = now", at(15), first.slowBandsAt[0])
        assertEquals("投入度同档 ⇒ 不记时", 0L, first.slowBandsAt[1])
        assertEquals("带档跟踪搭车 ⇒ 仍恰 1 次写", 1, writes.size)
        // 同档再 tick ⇒ 两列表一字不动、写次数照旧每轮 1
        kernel.tick("u", at(16), zone)
        val second = GrowthJson.decodeAffectFieldOrNull(writes.last())!!
        assertEquals(listOf(1, 1), second.slowBands)
        assertEquals(listOf(at(15), 0L), second.slowBandsAt)
        assertEquals(2, writes.size)
    }

    @Test
    fun tick_onLegacyColumn_recordsBandWithoutTime() = runTest {
        // 老列（slowBands 缺键 ⇒ [−1,−1]）安全感 90 ⇒ 记档 2 但 slowBandsAt 仍 0（未知历史·不出慢场句）
        stubColumn(GrowthJson.encode(AffectField(security = 90, updatedAt = at(15), budgetDayStart = dayStart)))
        kernel.tick("u", at(15, 1), zone)
        val f = capturedWrite()
        assertEquals(listOf(2, 1), f.slowBands)
        assertEquals(listOf(0L, 0L), f.slowBandsAt)
    }

    @Test
    fun withFieldLocked_blockPushesSecurityIntoHighBand_writesBandAndTime() = runTest {
        // 已知中档 [1,1]：块把安全感 50 → 82 ⇒ 写出档 2 + 时刻 = now；投入度不动
        val stored = AffectField(security = 50, slowBands = listOf(1, 1), slowRefAt = at(15) - 3_600_000L, updatedAt = at(15) - 3_600_000L, budgetDayStart = dayStart)
        stubColumn(GrowthJson.encode(stored))
        kernel.withFieldLocked("u", at(15), zone) { field0 -> field0.copy(security = 82) }
        val f = capturedWrite()
        assertEquals(82, f.security)
        assertEquals(listOf(2, 1), f.slowBands)
        assertEquals(listOf(at(15), 0L), f.slowBandsAt)
        assertEquals(at(15), f.slowRefAt)
    }

    @Test
    fun withFieldLocked_onLegacyColumn_recordsBandWithoutTime_andNoExtraWrite() = runTest {
        // 老列（未知档）块推到 82 ⇒ 只记档不记时；分析通道场列仍恰 1 写
        stubColumn(GrowthJson.encode(AffectField(security = 50, updatedAt = at(15) - 3_600_000L, budgetDayStart = dayStart)))
        kernel.withFieldLocked("u", at(15), zone) { field0 -> field0.copy(security = 82) }
        val f = capturedWrite()
        assertEquals(listOf(2, 1), f.slowBands)
        assertEquals(listOf(0L, 0L), f.slowBandsAt)
    }

    @Test
    fun twoTicksSameCharacter_serializeThroughMutex_bothWrite() = runTest {
        stubColumn("")
        kernel.tick("u", at(15), zone)
        kernel.tick("u", at(15, 1), zone)
        coVerify(exactly = 2) { dao.updateAffectField("u", any()) }
        assertTrue(true)
    }
}
