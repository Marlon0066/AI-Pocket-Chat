package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonaGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T1-3（图纸 §7.2 · §3.3 / §3.4 · E16–E18 / E37–E41 / E51 / E54 / E55）：
 * [IntentKernel] companion 五个纯函数 `advance` / `applyStatus` / `birth` / `pruneResolved` / `logLines`。
 *
 * 断言从图纸 §3.4 锁定序与 §3.3 三要素表**独立反推**（修缮卷 §3.4 改四步·J4 / J5）：
 * - advance：0 钳未来时间 → 1 消退（强度 / 超时；`residue = kind ∈ RESIDUE_KINDS`、`lastChangeAt = fadedAt`）→ 2 清理（无残留 3 天·残留 7 天·内心行换气）
 *   → 3 晋升（`bornAt == now` 不晋升）→ 4 全清只清 live 且只对 ≤12 字短消息；层 ① 不再表达 / 了结（E17 反例 ≥5 条）
 * - applyStatus：三值 + 未知 key + 无 live 条目 + 已 EXPRESSED 不二次砍半
 * - birth：萌生表每条一例 + 一个不可达反例；强度 40/50/60 按最高档；一次最多 2 个；同 kind 刷新；冷却（RESOLVED 24h / FADED 3 天·内心行换气）；E16 淘汰含新来者
 * - pruneResolved 24h；logLines 三种行逐字 + `lastAnalysisDate` 水位
 */
class IntentKernelAdvanceTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun intent(
        kind: IntentKind,
        state: IntentState = IntentState.ACTIVE,
        strength: Int = 50,
        bornAt: Long = now - hour,
        lastChangeAt: Long = now - hour,
        residue: Boolean = false,
        id: String = kind.key,
    ) = CharacterIntent(id = id, kind = kind, state = state, strength = strength, bornAt = bornAt, lastChangeAt = lastChangeAt, residue = residue)

    private fun queue(vararg intents: CharacterIntent) = IntentQueueState(intents = intents.toList())

    private fun advance(q: IntentQueueState, userText: String = "", at: Long = now) = IntentKernel.advance(q, at, userText)

    // MARK: - advance 1–3：消退 / 清理 / 晋升

    @Test
    fun fade_whenEffectiveDropsBelowFifteen_keepsResidueAndFreezesStrength() {
        // 想被哄 24h 半衰：60 经 3 天 ⇒ 8；J5：lastChangeAt = 衰减到 15 的时刻 = L + 24h × log2(60/15) = L + 48h = now − 1d
        val out = advance(queue(intent(IntentKind.WANT_COMFORT, strength = 60, bornAt = now - 3 * day, lastChangeAt = now - 3 * day)))
        val i = out.intents.single()
        assertEquals(IntentState.FADED, i.state)
        assertTrue("想被哄 ∈ RESIDUE_KINDS", i.residue)
        assertEquals(8, i.strength)
        assertEquals("消退时刻 = 两天前衰到 15 的那一刻，不是 now", now - day, i.lastChangeAt)
    }

    @Test
    fun fade_whenSevenDaysSinceBirth_evenIfStrong_E17() {
        val out = advance(queue(intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED, strength = 100, bornAt = now - 7 * day, lastChangeAt = now)))
        assertEquals(IntentState.FADED, out.intents.single().state)
        assertTrue(out.intents.single().residue)
        assertEquals("超时时刻 = bornAt + 7d = now（decayAt 在未来 ⇒ 取 min）", now, out.intents.single().lastChangeAt)
        // 6 天 23 小时：不超时
        val kept = advance(queue(intent(IntentKind.WANT_APOLOGIZE, strength = 100, bornAt = now - 7 * day + hour, lastChangeAt = now)))
        assertEquals(IntentState.ACTIVE, kept.intents.single().state)
    }

    @Test
    fun residue_isRemovedAfterSevenDays_resolvedIsNotTouchedHere() {
        val old = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 5, lastChangeAt = now - 7 * day, residue = true)
        val fresh = intent(IntentKind.WANT_SHARE, state = IntentState.FADED, strength = 5, lastChangeAt = now - 7 * day + 1, residue = true)
        val resolvedOld = intent(IntentKind.WANT_PROBE, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 30 * day)
        val out = advance(queue(old, fresh, resolvedOld))
        assertEquals(listOf(fresh.id, resolvedOld.id), out.intents.map { it.id })
    }

    @Test
    fun budding_isPromotedOnlyWhenBornBeforeNow_withoutTouchingStrength() {
        val earlier = intent(IntentKind.WANT_CONFIRM, state = IntentState.BUDDING, strength = 60, bornAt = now - 1, lastChangeAt = now - 1)
        val justNow = intent(IntentKind.WANT_SHARE, state = IntentState.BUDDING, strength = 60, bornAt = now, lastChangeAt = now)
        val future = intent(IntentKind.WANT_PROBE, state = IntentState.BUDDING, strength = 60, bornAt = now + day, lastChangeAt = now + day)
        val out = advance(queue(earlier, justNow, future))
        assertEquals(IntentState.ACTIVE, out.intents[0].state)
        assertEquals(60, out.intents[0].strength)
        assertEquals(now - 1, out.intents[0].lastChangeAt)
        assertEquals(IntentState.BUDDING, out.intents[1].state)
        assertEquals("E51 未来出生不晋升", IntentState.BUDDING, out.intents[2].state)
        assertEquals("修缮卷 E12：未来时间钳到 now", now, out.intents[2].bornAt)
        assertEquals(now, out.intents[2].lastChangeAt)
    }

    @Test
    fun futureBornAt_isClampedThisTick_andPromotedNextTick_E12() {
        val future = intent(IntentKind.WANT_PROBE, state = IntentState.BUDDING, strength = 60, bornAt = now + day, lastChangeAt = now + day)
        val tick1 = advance(queue(future))
        assertEquals(IntentState.BUDDING, tick1.intents.single().state)
        assertEquals(now, tick1.intents.single().bornAt)
        val tick2 = advance(tick1, at = now + 1)
        assertEquals("再下一 tick 晋升", IntentState.ACTIVE, tick2.intents.single().state)
    }

    // MARK: - 修缮卷 J5：残留只给负向意图 + 消退时刻按可算出的时刻记

    @Test
    fun share_fadesWithoutResidue_keptAsFadedThreeDaysForCooldown_thenRemoved_E13() {
        // 想分享 24h 半衰：50 经 44h ⇒ 50 × 0.5^(44/24) = 14.0 < 15 ⇒ 消退；想分享 ∉ RESIDUE_KINDS ⇒ 无残留
        // 内心行换气改期望：无残留的 FADED 不再同 tick 清，保留 3 天供同类冷却判定（fadedAt = L + 24h × log2(50/15) ≈ L + 41.69h）
        val born = now - 44 * hour
        val out = advance(queue(intent(IntentKind.WANT_SHARE, strength = 50, bornAt = born, lastChangeAt = born)))
        val i = out.intents.single()
        assertEquals(IntentState.FADED, i.state)
        assertFalse("想分享不留残留（J5 不变）", i.residue)
        assertTrue("fadedAt ≈ L + 41.69h（实际 ${(i.lastChangeAt - born) / 3.6e6}h）", kotlin.math.abs(i.lastChangeAt - (born + (41.69 * hour).toLong())) < 60_000L)
        assertEquals("3 天前保留", 1, advance(out, at = i.lastChangeAt + 72 * hour - 1).intents.size)
        assertTrue("3 天后清", advance(out, at = i.lastChangeAt + 72 * hour).intents.isEmpty())
    }

    @Test
    fun advance_fadedWithoutResidue_keptThreeDays_residueStillSevenDays() {
        // 内心行换气：无残留 FADED 满 72h 清、未满留；残留 FADED 仍按 7 天（72h 的残留照留）
        val shareFresh = intent(IntentKind.WANT_SHARE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 72 * hour + 1, id = "s-fresh")
        val shareOld = intent(IntentKind.WANT_SHARE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 72 * hour, id = "s-old")
        val hideResidue = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 72 * hour, residue = true, id = "h-res")
        val hideOldResidue = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 7 * day, residue = true, id = "h-old")
        val out = advance(queue(shareFresh, shareOld, hideResidue, hideOldResidue))
        assertEquals(listOf("s-fresh", "h-res"), out.intents.map { it.id })
    }

    @Test
    fun comfort_after30DaysAway_fadesAtDecayTime_andResidueExpiresInTheSameTick_E14() {
        // 想被哄 50、停 30 天：decayAt = L + 24h × log2(50/15) ≈ L + 41.7h ⇒ fadedAt 在 28 天前 ⇒ TTL 7 天已过 ⇒ 同 tick 清理，不补挂
        val out = advance(queue(intent(IntentKind.WANT_COMFORT, strength = 50, bornAt = now - 30 * day, lastChangeAt = now - 30 * day)))
        assertTrue("久别归来无残留句", out.intents.isEmpty())
    }

    @Test
    fun expressedComfort_after20h_fadesAtDecayTime_residueKept_E15() {
        // EXPRESSED 25、20h 后：25 × 0.5^(20/24) = 14.0 < 15 ⇒ 消退；decayAt = L + 24h × log2(25/15) = L + 17.69h（手算 log2(5/3) = 0.737）
        val born = now - 21 * hour
        val last = now - 20 * hour
        val out = advance(queue(intent(IntentKind.WANT_COMFORT, state = IntentState.EXPRESSED, strength = 25, bornAt = born, lastChangeAt = last)))
        val i = out.intents.single()
        assertEquals(IntentState.FADED, i.state)
        assertTrue(i.residue)
        assertEquals(14, i.strength)
        val expectedFadedAt = last + (17.69 * hour).toLong()
        assertTrue("lastChangeAt ≈ L + 17.7h（实际 ${(i.lastChangeAt - last) / 3.6e6}h）", kotlin.math.abs(i.lastChangeAt - expectedFadedAt) < 60_000L)
        // 残留保留到 7 天：6d23h 后仍在、7d 后清
        assertEquals(1, advance(out, at = i.lastChangeAt + 7 * day - 1).intents.size)
        assertTrue(advance(out, at = i.lastChangeAt + 7 * day).intents.isEmpty())
    }

    @Test
    fun fadedAt_neverBeforeLastChangeAt_whenStrengthAlreadyBelowFadeMin() {
        // 强度本就 < 15（坏数据 / 砍半后）：decayAt = lastChangeAt ⇒ fadedAt = lastChangeAt（不早于它）
        val i = intent(IntentKind.WANT_HIDE, strength = 10, bornAt = now - 2 * hour, lastChangeAt = now - hour)
        val out = advance(queue(i)).intents.single()
        assertEquals(IntentState.FADED, out.state)
        assertEquals(now - hour, out.lastChangeAt)
    }

    // MARK: - advance 4–6：全清 / 了结 / 表达

    @Test
    fun clearAll_removesOnlyLive_keepsResolvedAndFaded_E18() {
        val q = queue(
            intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED),
            intent(IntentKind.WANT_COMFORT, state = IntentState.ACTIVE),
            intent(IntentKind.WANT_HIDE, state = IntentState.RESOLVED, strength = 0),
            intent(IntentKind.WANT_SHARE, state = IntentState.FADED, strength = 5, residue = true),
        )
        val out = advance(q, userText = "好啦，没事了")
        assertEquals(listOf(IntentState.RESOLVED, IntentState.FADED), out.intents.map { it.state })
        assertEquals("全清不记 growthLog（K-21）——纯函数层无日志入口", 2, out.intents.size)
    }

    @Test
    fun clearAll_onlyForShortMessages_trimmed_E16() {
        val live = queue(intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED), intent(IntentKind.WANT_COMFORT))
        assertTrue("7 字含「过去了」⇒ 清（有意接受）", advance(live, userText = "过去了一趟超市").intents.isEmpty())
        assertTrue("恰 12 字 ⇒ 清", advance(live, userText = "今天过去了一天真累啊啊啊").intents.isEmpty())
        assertEquals("13 字 ⇒ 不清", 2, advance(live, userText = "今天过去了一天真的累啊啊啊").intents.size)
        assertTrue("首尾空白不计", advance(live, userText = "  没事了  \n").intents.isEmpty())
        assertEquals("长句里的全清词不算", 2, advance(live, userText = "我跟你说啊那件事早就过去了我现在一点都不在意的").intents.size)
    }

    @Test
    fun userOrReplyKeywords_noLongerChangeState_E17() {
        // 层 ① 表达 / 了结表已删（J4）：这些词此前会推进状态，现在零变化（表达 / 了结只经层 ② applyStatus）
        val expressed = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED, strength = 25)
        val active = intent(IntentKind.WANT_COMFORT, state = IntentState.ACTIVE, strength = 50)
        val share = intent(IntentKind.WANT_SHARE, state = IntentState.EXPRESSED, strength = 30)
        val q = queue(expressed, active, share)
        for (text in listOf("哈哈", "我今天", "我在", "没关系", "对不起啦", "抱抱", "然后呢", "真的假的")) {
            assertEquals("「$text」不该改任何状态", q, advance(q, userText = text))
        }
    }

    @Test
    fun emptyText_skipsClearAll_butLifecycleStillRuns() {
        val expressed = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED, strength = 25)
        val active = intent(IntentKind.WANT_COMFORT, state = IntentState.ACTIVE, strength = 50)
        val budding = intent(IntentKind.WANT_SHARE, state = IntentState.BUDDING, strength = 50, bornAt = now - 1, lastChangeAt = now - 1)
        val out = advance(queue(expressed, active, budding), userText = "")
        assertEquals(expressed, out.intents[0])
        assertEquals(active, out.intents[1])
        assertEquals("晋升属步骤 3，空文本也跑", IntentState.ACTIVE, out.intents[2].state)
    }

    // MARK: - applyStatus（层 ②）

    @Test
    fun applyStatus_threeValues_unknownKey_noLive_alreadyExpressed() {
        val apology = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 50, lastChangeAt = now)
        val comfort = intent(IntentKind.WANT_COMFORT, state = IntentState.EXPRESSED, strength = 25)
        val probe = intent(IntentKind.WANT_PROBE, state = IntentState.ACTIVE, strength = 50)
        val faded = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 5, residue = true)
        val q = queue(apology, comfort, probe, faded)
        val out = IntentKernel.applyStatus(
            q,
            mapOf(
                "wantApologize" to "resolved",
                "wantComfort" to "expressed",     // E55 已 EXPRESSED ⇒ 不动
                "wantProbe" to "open",
                "wantHide" to "resolved",         // 无 live 条目（FADED）⇒ 忽略（E38）
                "wantShare" to "resolved",        // 队列里没有 ⇒ 忽略（E38）
                "wantMoney" to "resolved",        // 未知 key ⇒ 忽略（E37）
            ),
            now,
        )
        assertEquals(IntentState.RESOLVED, out.intents[0].state)
        assertEquals(0, out.intents[0].strength)
        assertEquals(now, out.intents[0].lastChangeAt)
        assertEquals(comfort, out.intents[1])
        assertEquals(probe, out.intents[2])
        assertEquals(faded, out.intents[3])
        assertEquals("绝不萌生（N-2）", 4, out.intents.size)
    }

    @Test
    fun applyStatus_expressed_halvesActiveOrBudding() {
        val active = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 51, lastChangeAt = now)
        val budding = intent(IntentKind.WANT_SHARE, state = IntentState.BUDDING, strength = 60, bornAt = now, lastChangeAt = now)
        val out = IntentKernel.applyStatus(queue(active, budding), mapOf("wantApologize" to "expressed", "wantShare" to "expressed"), now)
        assertEquals(IntentState.EXPRESSED, out.intents[0].state)
        assertEquals(26, out.intents[0].strength)
        assertEquals(IntentState.EXPRESSED, out.intents[1].state)
        assertEquals(30, out.intents[1].strength)
    }

    @Test
    fun applyStatus_emptyMap_isIdentity_E56() {
        val q = queue(intent(IntentKind.WANT_APOLOGIZE))
        assertEquals(q, IntentKernel.applyStatus(q, emptyMap(), now))
    }

    // MARK: - birth：萌生表六条各一例 + 不可达反例

    private fun field(security: Int = 50, investment: Int = 30, valence: Int = 0) = AffectField(security = security, investment = investment, valence = valence)

    private fun born(hits: List<String>, f: AffectField, gains: PersonaGains = PersonaGains(), q: IntentQueueState = IntentQueueState()): List<IntentKind> =
        IntentKernel.birth(q, hits, gains, f, now).intents.map { it.kind }

    @Test
    fun rule1_comfort_needsNegativeHitAndValenceAtMostMinusThree() {
        assertEquals(listOf(IntentKind.WANT_COMFORT), born(listOf("g05"), field(valence = -3)))
        assertEquals("效价 −2 不够（numb 档 g13 一击只到 −2）", emptyList<IntentKind>(), born(listOf("g05"), field(valence = -2)))
        // numb 档 g13 效价只到 −2 ⇒ 无 COMFORT；但 g13 ∧ 投入 30 < 45 ⇒ 想躲
        assertEquals(listOf(IntentKind.WANT_HIDE), born(listOf("g13"), field(valence = -2)))
    }

    @Test
    fun rule2_apologize_needsConflictHitAndInvestmentAtLeast45_else_hide() {
        assertEquals(listOf(IntentKind.WANT_APOLOGIZE), born(listOf("g14"), field(investment = 45)))
        assertEquals("投入 44 ⇒ 想躲而不是想道歉", listOf(IntentKind.WANT_HIDE), born(listOf("g14"), field(investment = 44)))
    }

    @Test
    fun rule3_hide_threeBranches_andUnreachableCounterexample() {
        assertEquals(listOf(IntentKind.WANT_HIDE), born(listOf("g03"), field()))
        assertEquals(listOf(IntentKind.WANT_HIDE), born(listOf("g08"), field(security = 40)))
        assertEquals("被误解但安全感 41 ⇒ 不躲", emptyList<IntentKind>(), born(listOf("g08"), field(security = 41)))
    }

    @Test
    fun rule4_confirm_threeBranches_andUnreachableCounterexample() {
        assertEquals(listOf(IntentKind.WANT_CONFIRM), born(listOf("g27"), field()))
        assertEquals(listOf(IntentKind.WANT_CONFIRM), born(listOf("g25"), field(security = 40)))
        assertEquals(emptyList<IntentKind>(), born(listOf("g25"), field(security = 41)))
        assertEquals(listOf(IntentKind.WANT_CONFIRM), born(listOf("g18"), field(investment = 45)))
        assertEquals("被辜负但投入 44 ⇒ 不确认（效价 0 也不想被哄）", emptyList<IntentKind>(), born(listOf("g18"), field(investment = 44)))
    }

    @Test
    fun rule5_probe_threeBranches_andUnreachableCounterexample() {
        assertEquals(listOf(IntentKind.WANT_PROBE), born(listOf("g22"), field(security = 60)))
        assertEquals("被撩但安全感 61 ⇒ 不试探", emptyList<IntentKind>(), born(listOf("g22"), field(security = 61)))
        assertEquals(listOf(IntentKind.WANT_PROBE), born(listOf("g19"), field()))
        assertEquals(listOf(IntentKind.WANT_PROBE), born(listOf("g02"), field(investment = 45)))
        assertEquals(emptyList<IntentKind>(), born(listOf("g02"), field(investment = 44)))
    }

    @Test
    fun rule6_share_needsPositiveHitAndValenceAtLeastThree_orChange() {
        assertEquals(listOf(IntentKind.WANT_SHARE), born(listOf("g07"), field(valence = 3)))
        assertEquals("效价 2 不够", emptyList<IntentKind>(), born(listOf("g07"), field(valence = 2)))
        assertEquals(listOf(IntentKind.WANT_SHARE), born(listOf("g26"), field()))
    }

    @Test
    fun birth_strengthByHighestHitLevel_40_50_60() {
        val gains = PersonaGains(system = mapOf("g13" to 2, "g14" to 0))
        val both = IntentKernel.birth(IntentQueueState(), listOf("g13", "g14"), gains, field(investment = 50, valence = -5), now).intents
        assertEquals(listOf(IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE), both.map { it.kind })
        assertTrue("命中集合里最高档 = 很敏感 ⇒ 60", both.all { it.strength == 60 })
        val numbOnly = IntentKernel.birth(IntentQueueState(), listOf("g14"), gains, field(investment = 50), now).intents.single()
        assertEquals(40, numbOnly.strength)
        val normal = IntentKernel.birth(IntentQueueState(), listOf("g14"), PersonaGains(), field(investment = 50), now).intents.single()
        assertEquals(50, normal.strength)
        assertEquals(IntentState.BUDDING, normal.state)
        assertEquals(now, normal.bornAt)
        assertEquals(now, normal.lastChangeAt)
        assertFalse(normal.residue)
        assertTrue(normal.id.isNotEmpty())
    }

    @Test
    fun birth_atMostTwoPerAnalysis_inTableOrder() {
        // g13 + 投入 50 + 效价 −5 ⇒ 想被哄 + 想道歉（想躲的分支 g13∧I<45 不成立）
        assertEquals(listOf(IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE), born(listOf("g13"), field(investment = 50, valence = -5)))
        // 再加 g03（想躲必成立）：表序第三条被 2 个上限挡住
        assertEquals(listOf(IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE), born(listOf("g13", "g03"), field(investment = 50, valence = -5)))
    }

    @Test
    fun birth_sameKindAlreadyLive_refreshesInsteadOfDuplicating_andDoesNotCountTowardCap_E39() {
        val existing = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 30, bornAt = now - 2 * hour, lastChangeAt = now - 2 * hour)
        val out = IntentKernel.birth(queue(existing), listOf("g13", "g03"), PersonaGains(), field(investment = 50, valence = -5), now)
        val apology = out.intents.single { it.kind == IntentKind.WANT_APOLOGIZE }
        assertEquals("刷新：strength = max(effective 30, s0 50)", 50, apology.strength)
        assertEquals(now, apology.lastChangeAt)
        assertEquals(IntentState.ACTIVE, apology.state)
        assertEquals(now - 2 * hour, apology.bornAt)
        // 刷新不计入 2 个上限 ⇒ 想被哄 + 想躲照常萌生
        assertEquals(setOf(IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE, IntentKind.WANT_HIDE), out.intents.map { it.kind }.toSet())
        assertEquals(3, out.intents.size)
    }

    @Test
    fun birth_sameKindResolvedWithin24h_isBlocked_E40_E54() {
        val cooling = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 23 * hour)
        val blocked = IntentKernel.birth(queue(cooling), listOf("g13"), PersonaGains(), field(investment = 50, valence = -5), now).intents
        assertEquals("冷却中的 RESOLVED 原样留队 + 只萌生想被哄", listOf(cooling, blocked[1]), blocked)
        assertEquals(IntentKind.WANT_COMFORT, blocked[1].kind)
        assertEquals(IntentState.BUDDING, blocked[1].state)
        // E54：同一分析里刚被层 ② 了结 ⇒ 立刻再触发也被冷却挡住
        val justResolved = IntentKernel.applyStatus(queue(intent(IntentKind.WANT_APOLOGIZE)), mapOf("wantApologize" to "resolved"), now)
        assertEquals(listOf(IntentKind.WANT_APOLOGIZE, IntentKind.WANT_COMFORT), IntentKernel.birth(justResolved, listOf("g13"), PersonaGains(), field(investment = 50, valence = -5), now).intents.map { it.kind })
        // 24h 后清理掉 RESOLVED ⇒ 可再萌生
        val expired = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 24 * hour)
        val pruned = IntentKernel.pruneResolved(queue(expired), now)
        assertEquals(listOf(IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE), born(listOf("g13"), field(investment = 50, valence = -5), q = pruned))
    }

    @Test
    fun birth_sameKindFadedWithin3Days_isBlocked_after3Days_born_otherKindUnaffected() {
        // 内心行换气：想分享 FADED（无残留）24h 后同 kind 命中 ⇒ 不萌生（队列原样）；72h − 1ms 仍挡；73h 后 ⇒ 萌生
        val faded = intent(IntentKind.WANT_SHARE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 24 * hour)
        val share = listOf("g07")
        assertEquals(queue(faded), IntentKernel.birth(queue(faded), share, PersonaGains(), field(valence = 5), now))
        val edge = faded.copy(lastChangeAt = now - 72 * hour + 1)
        assertEquals(queue(edge), IntentKernel.birth(queue(edge), share, PersonaGains(), field(valence = 5), now))
        val old = faded.copy(lastChangeAt = now - 73 * hour)
        val reborn = IntentKernel.birth(queue(old), share, PersonaGains(), field(valence = 5), now)
        assertEquals(listOf(IntentKind.WANT_SHARE, IntentKind.WANT_SHARE), reborn.intents.map { it.kind })
        assertEquals("旧 FADED 留队（由 advance 清）、新条目 BUDDING", IntentState.BUDDING, reborn.intents[1].state)
        assertEquals(now, reborn.intents[1].bornAt)
        // 残留 FADED（负向五种）同样冷却：想躲消退 2 天后 g03 命中 ⇒ 不萌生
        val hideFaded = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, lastChangeAt = now - 2 * day, residue = true)
        assertEquals(queue(hideFaded), IntentKernel.birth(queue(hideFaded), listOf("g03"), PersonaGains(), field(), now))
        // 不同 kind 不受影响：想躲冷却中，想分享照常萌生
        assertEquals(
            listOf(IntentKind.WANT_HIDE, IntentKind.WANT_SHARE),
            IntentKernel.birth(queue(hideFaded), share, PersonaGains(), field(valence = 5), now).intents.map { it.kind },
        )
        // RESOLVED 冷却仍是 24h（E40 不变）：25h 前了结的想分享可再萌生
        val resolved = intent(IntentKind.WANT_SHARE, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 25 * hour)
        assertEquals(2, IntentKernel.birth(queue(resolved), share, PersonaGains(), field(valence = 5), now).intents.size)
    }

    @Test
    fun birth_queueFull_replacesWeakestOnlyIfNewcomerIsStronger_E16() {
        val full = queue(
            intent(IntentKind.WANT_PROBE, strength = 40, bornAt = now - 3 * hour, lastChangeAt = now, id = "weak"),
            intent(IntentKind.WANT_CONFIRM, strength = 50, lastChangeAt = now, id = "mid"),
            intent(IntentKind.WANT_SHARE, strength = 60, lastChangeAt = now, id = "strong"),
        )
        // 新来者 50（想道歉·正常档）> 最弱 40 ⇒ 顶替 weak
        val replaced = IntentKernel.birth(full, listOf("g14"), PersonaGains(), field(investment = 50), now)
        assertEquals(setOf("mid", "strong"), replaced.intents.filter { it.kind != IntentKind.WANT_APOLOGIZE }.map { it.id }.toSet())
        assertEquals(3, replaced.intents.size)
        assertTrue(replaced.intents.any { it.kind == IntentKind.WANT_APOLOGIZE })
        // 新来者 40（numb 档）≤ 最弱 40 ⇒ 丢弃新来者
        val dropped = IntentKernel.birth(full, listOf("g14"), PersonaGains(system = mapOf("g14" to 0)), field(investment = 50), now)
        assertEquals(full, dropped)
    }

    @Test
    fun birth_queueFull_tieOnWeakest_evictsEarliestBorn() {
        val full = queue(
            intent(IntentKind.WANT_PROBE, strength = 40, bornAt = now - hour, lastChangeAt = now, id = "later"),
            intent(IntentKind.WANT_CONFIRM, strength = 40, bornAt = now - 2 * hour, lastChangeAt = now, id = "earliest"),
            intent(IntentKind.WANT_SHARE, strength = 60, lastChangeAt = now, id = "strong"),
        )
        val out = IntentKernel.birth(full, listOf("g14"), PersonaGains(), field(investment = 50), now)
        assertFalse(out.intents.any { it.id == "earliest" })
        assertTrue(out.intents.any { it.id == "later" })
    }

    @Test
    fun birth_fullQueueCountsOnlyLive_resolvedAndFadedDoNotOccupySlots() {
        val q = queue(
            intent(IntentKind.WANT_PROBE, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 2 * hour),
            intent(IntentKind.WANT_CONFIRM, state = IntentState.FADED, strength = 5, residue = true),
            intent(IntentKind.WANT_SHARE, strength = 60, lastChangeAt = now),
        )
        val out = IntentKernel.birth(q, listOf("g14"), PersonaGains(), field(investment = 50), now)
        assertEquals(4, out.intents.size)
    }

    @Test
    fun birth_noHits_isIdentity() {
        val q = queue(intent(IntentKind.WANT_SHARE))
        assertEquals(q, IntentKernel.birth(q, emptyList(), PersonaGains(), field(valence = -50, investment = 90), now))
    }

    // MARK: - pruneResolved

    @Test
    fun pruneResolved_removesOnlyResolvedOlderThan24h() {
        val expired = intent(IntentKind.WANT_APOLOGIZE, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 24 * hour)
        val cooling = intent(IntentKind.WANT_COMFORT, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 24 * hour + 1)
        val faded = intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 5, lastChangeAt = now - 30 * day, residue = true)
        val out = IntentKernel.pruneResolved(queue(expired, cooling, faded), now)
        assertEquals(listOf(cooling, faded), out.intents)
    }

    // MARK: - logLines

    @Test
    fun logLines_threeKinds_verbatim_withWaterLevel() {
        val floor = now - 2 * day
        val oldResolved = intent(IntentKind.WANT_PROBE, state = IntentState.RESOLVED, strength = 0, lastChangeAt = floor, id = "old")
        val before = queue(
            intent(IntentKind.WANT_COMFORT, state = IntentState.EXPRESSED, strength = 25, id = "c"),
            intent(IntentKind.WANT_SHARE, state = IntentState.ACTIVE, strength = 20, id = "s"),
            oldResolved,
        )
        val after = queue(
            intent(IntentKind.WANT_APOLOGIZE, state = IntentState.BUDDING, strength = 50, bornAt = now, lastChangeAt = now, id = "new"),
            intent(IntentKind.WANT_COMFORT, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now, id = "c"),
            intent(IntentKind.WANT_SHARE, state = IntentState.FADED, strength = 8, lastChangeAt = now - hour, residue = true, id = "s"),
            oldResolved,
        )
        val lines = IntentKernel.logLines(before, after, floor, "林悦", "小明", now)
        assertEquals(
            listOf("林悦想向小明道歉（萌生）", "林悦想被小明哄一哄（了结）", "林悦有件事想跟小明分享（消退）"),
            lines.map { it.summary },
        )
        assertTrue(lines.all { it.type == GrowthEventType.RELATIONSHIP_CHANGE && it.timestamp == now })
        assertNotEquals("每条 id 唯一", lines[0].id, lines[1].id)
    }

    @Test
    fun logLines_nullWaterLevel_logsEverythingChangedSinceEpoch_andNoChangeLogsNothing() {
        val resolved = intent(IntentKind.WANT_COMFORT, state = IntentState.RESOLVED, strength = 0, lastChangeAt = now - 10 * day, id = "c")
        assertEquals(listOf("林悦想被小明哄一哄（了结）"), IntentKernel.logLines(queue(resolved), queue(resolved), null, "林悦", "小明", now).map { it.summary })
        val active = intent(IntentKind.WANT_SHARE, id = "s")
        assertTrue(IntentKernel.logLines(queue(active), queue(active), now - day, "林悦", "小明", now).isEmpty())
    }
}
