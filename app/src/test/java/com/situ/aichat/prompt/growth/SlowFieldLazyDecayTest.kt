package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.AffectField
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感内核·修缮卷 T1-1（图纸 §7 · E2 / E3 / E6）：慢场惰性参考值 [slowNow] + 只读视图 [fieldForRead]。
 *
 * 断言从图纸 §3.2 公式**独立手算**（不照抄实现）：
 * - E3：`50 + 20 × 0.5^(30d/30d) = 60`；60 天 `50 + 20 × 0.25 = 55`；`refAt == 0` / `now ≤ refAt` ⇒ 原值
 * - E2：参考值 70、每 1h 读一次读 100 次——第 1 小时仍 70（旧补步规则会给 69）、第 100 小时 `50 + 20 × 0.5^(100/720) = 68.17 → 68`
 *   （既不是一周归零、也不是冻结在 70）；读不改 `slowRefAt / updatedAt`
 * - E6：快场离目标 1 格、dt = 1h ⇒ 补步走 1；慢场同条件（半衰 30d）⇒ 不走
 */
class SlowFieldLazyDecayTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val t0 = LocalDateTime.of(2026, 9, 2, 15, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `E3 slowNow 三十天回到一半 六十天回到四分之一`() {
        assertEquals(60, slowNow(70, 50, t0, t0 + 30 * day))
        assertEquals(55, slowNow(70, 50, t0, t0 + 60 * day))
        assertEquals("投入度基线 30：60 → 45", 45, slowNow(60, 30, t0, t0 + 30 * day))
        assertEquals("低于基线也向基线回：30 → 40", 40, slowNow(30, 50, t0, t0 + 30 * day))
        assertEquals("refAt == 0（旧数据）⇒ 原值", 70, slowNow(70, 50, 0L, t0 + 30 * day))
        assertEquals("now ≤ refAt（时钟回拨）⇒ 原值", 70, slowNow(70, 50, t0, t0 - hour))
        assertEquals("已在基线 ⇒ 恒基线", 50, slowNow(50, 50, t0, t0 + 300 * day))
    }

    @Test
    fun `E2 每小时读一百次 不补步不冻结`() {
        val f = AffectField(security = 70, investment = 30, updatedAt = t0, slowRefAt = t0)
        assertEquals("第 1 小时仍 70（旧规则每小时补 1 格 ⇒ 69）", 70, fieldForRead(f, t0 + hour, zone).security)
        var prev = 70
        for (i in 1..100) {
            val v = fieldForRead(f, t0 + i * hour, zone).security
            assert(v <= prev && v >= 68) { "第 $i 小时读值 $v 越出 [68, 上一读值 $prev]" }
            prev = v
        }
        assertEquals("第 100 小时：50 + 20 × 0.5^(100/720) = 68.17 → 68", 68, fieldForRead(f, t0 + 100 * hour, zone).security)
        assertEquals("30 天：60（不冻结）", 60, fieldForRead(f, t0 + 30 * day, zone).security)
    }

    @Test
    fun `fieldForRead 只读 不改 slowRefAt 与 updatedAt 也不动预算与命中`() {
        val f = AffectField(
            security = 70, investment = 60, valence = 40, arousal = 60,
            updatedAt = t0, slowRefAt = t0 - 10 * day, budgetDayStart = t0 - hour, budgetUsed = 12,
            hits = listOf("g04"), hitsAt = t0, slowDayUsed = listOf(3, 4), pullbackDone = true,
        )
        val r = fieldForRead(f, t0 + 6 * hour, zone)
        assertEquals(f.slowRefAt, r.slowRefAt)
        assertEquals(f.updatedAt, r.updatedAt)
        assertEquals(f.budgetDayStart, r.budgetDayStart)
        assertEquals(f.budgetUsed, r.budgetUsed)
        assertEquals(f.hits, r.hits)
        assertEquals(f.hitsAt, r.hitsAt)
        assertEquals(f.slowDayUsed, r.slowDayUsed)
        assertEquals(f.pullbackDone, r.pullbackDone)
        // 慢场按 slowRefAt（10 天前）算：50 + 20 × 0.5^(10.25/30) = 50 + 20 × 0.789 = 65.8 → 66；投入 30 + 30 × 0.789 = 53.7 → 54
        assertEquals(66, r.security)
        assertEquals(54, r.investment)
        // 快场按 updatedAt（6h 前）：效价 40 × 0.5^(6/24) = 33.6 → 34；激活 21:00 基线 40：40 + 20 × 0.5^(6/4) = 47.07 → 47
        assertEquals(34, r.valence)
        assertEquals(47, r.arousal)
    }

    @Test
    fun `updatedAt 为零的旧列 快场不松弛`() {
        val f = AffectField(valence = 40, arousal = 60, updatedAt = 0L)
        val r = fieldForRead(f, t0 + 6 * hour, zone)
        assertEquals(40, r.valence)
        assertEquals(60, r.arousal)
    }

    @Test
    fun `E6 补步只对快场`() {
        assertEquals("效价 1 → 0：dt 1h 补步", 0, relaxToward(1, 0, hour, RelationshipBands.VALENCE_HALF_LIFE_MS))
        assertEquals("激活 29 → 30：dt 1h 补步", 30, relaxToward(29, 30, hour, RelationshipBands.AROUSAL_HALF_LIFE_MS))
        assertEquals("慢场 70 → 50、dt 1h：取整原地不动且**不**补步", 70, relaxToward(70, 50, hour, RelationshipBands.SLOW_HALF_LIFE_MS))
        assertEquals("慢场 51 → 50、dt 1h：同样不补步", 51, relaxToward(51, 50, hour, RelationshipBands.SLOW_HALF_LIFE_MS))
        assertEquals("慢场 51 → 50、dt 30d：靠公式自己到 50.5 → 51（取整）——本函数已不再被慢场调用，只钉守则", 51, relaxToward(51, 50, 30 * day, RelationshipBands.SLOW_HALF_LIFE_MS))
    }
}
