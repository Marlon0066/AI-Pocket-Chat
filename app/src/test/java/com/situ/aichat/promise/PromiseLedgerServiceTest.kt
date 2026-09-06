package com.situ.aichat.promise

import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OpenLoopDueWorker
import androidx.work.ExistingWorkPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * 承诺账本写入口（记忆改造一期·部件①/②·图纸 §3.2 / T2-1/2/3）。断言从图纸 §3.2/§5 独立反推（MockK 假掉三仓库）：
 * - register 落库字段 / 去重（E5）/ 金额拒绝（E4）
 * - 惦记桥：未来 due 新建 loop + 排 worker（uniqueName/KEEP/delay）；等值 open loop 存在只关联不新建
 * - applyReconciliation 置状态+evidence+resolve 关联 loop；loop 非 open no-op（E16）；仍 open 才写（陈旧防护）
 * - registerFromMeeting due=null 不建 loop；过期 due 不建 loop（E14）
 */
class PromiseLedgerServiceTest {

    private val now = 1_000_000_000L
    private val future = now + 5L * 24 * 60 * 60 * 1000
    private val past = now - 3L * 24 * 60 * 60 * 1000

    private fun fixture(): Triple<PromiseRepository, OpenLoopRepository, BackgroundScheduler> {
        val promiseRepo = mockk<PromiseRepository>(relaxed = true)
        val loopRepo = mockk<OpenLoopRepository>(relaxed = true)
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)
        coEvery { promiseRepo.openByCharacter(any()) } returns emptyList()
        coEvery { loopRepo.openLoopsForCharacter(any()) } returns emptyList()
        return Triple(promiseRepo, loopRepo, scheduler)
    }

    private fun service(t: Triple<PromiseRepository, OpenLoopRepository, BackgroundScheduler>) =
        PromiseLedgerService(t.first, t.second, t.third, mockk(relaxed = true)) // 第 4 参 OfflineMeetingMemoryDao（回填用·本测不涉及）

    // ── T2-1 register ──

    @Test fun register_persistsCorrectFields() = runBlocking {
        val t = fixture()
        val p = service(t).register("c1", "conv1", "  周末一起去看画展  ", null, PromiseSource.CHAT, "", now)
        assertNotNull(p)
        assertEquals("周末一起去看画展", p!!.content) // trim
        assertEquals("c1", p.characterUuid)
        assertEquals("conv1", p.conversationUuid)
        assertEquals(PromiseStatus.OPEN, p.statusRaw)
        assertEquals(PromiseSource.CHAT, p.sourceRaw)
        assertNull(p.dueAtMillis)
        assertNull(p.openLoopUuid)
        assertEquals(now, p.createdAtMillis)
        assertEquals(now, p.updatedAtMillis)
        coVerify(exactly = 1) { t.first.upsert(match { it.uuid == p.uuid && it.content == "周末一起去看画展" }) }
    }

    @Test fun register_deduplicatesByWhitespaceInsensitiveEquality_e5() = runBlocking {
        val t = fixture()
        coEvery { t.first.openByCharacter("c1") } returns listOf(
            PromiseEntity(uuid = "old", characterUuid = "c1", content = "一起看展", createdAtMillis = 0, updatedAtMillis = 0),
        )
        val p = service(t).register("c1", "conv1", "一起 看展", null, PromiseSource.CHAT, "", now) // 去空白后等值
        assertNull("去空白等值 → 去重跳过", p)
        coVerify(exactly = 0) { t.first.upsert(any()) }
    }

    @Test fun register_rejectsMoneyPromise_e4() = runBlocking {
        val t = fixture()
        listOf("发 500 元红包", "给你转账", "送你 200 块钱的礼物", "给你 50 金币", "补你 ¥30").forEach { c ->
            assertNull("金额守卫应拒绝：$c", service(t).register("c1", "conv1", c, null, PromiseSource.CHAT, "", now))
        }
        coVerify(exactly = 0) { t.first.upsert(any()) }
    }

    // ── T2-2 惦记桥 + applyReconciliation ──

    @Test fun register_futureDue_createsLoopAndSchedulesWorker() = runBlocking {
        val t = fixture()
        val p = service(t).register("c1", "conv1", "下周一起爬山", future, PromiseSource.CHAT, "", now)
        assertNotNull(p!!.openLoopUuid)
        // 新建 open loop（promise_char·dueAt=future）。
        coVerify(exactly = 1) {
            t.second.upsert(
                match {
                    it.uuid == p.openLoopUuid && it.typeRaw == OpenLoopType.PROMISE_CHAR &&
                        it.dueAt == future && it.statusRaw == OpenLoopStatus.OPEN && it.characterUuid == "c1"
                },
            )
        }
        // 排到点 worker：uniqueName(loopUuid) / KEEP / delay=due−now。
        verify(exactly = 1) {
            t.third.scheduleOneShot(
                uniqueName = OpenLoopDueWorker.uniqueName(p.openLoopUuid!!),
                workerClass = OpenLoopDueWorker::class.java,
                initialDelay = Duration.ofMillis(future - now),
                requireNetwork = true,
                existingPolicy = ExistingWorkPolicy.KEEP,
                inputData = any(),
            )
        }
    }

    @Test fun register_futureDue_reusesExistingEquivalentLoop_noNewLoopNoWorker() = runBlocking {
        val t = fixture()
        val existing = OpenLoopEntity(
            uuid = "loopX", conversationUuid = "conv1", characterUuid = "c1", content = "下周 一起 爬山",
            typeRaw = OpenLoopType.PROMISE_CHAR, dueAt = future, statusRaw = OpenLoopStatus.OPEN, createdAt = now,
        )
        coEvery { t.second.openLoopsForCharacter("c1") } returns listOf(existing)
        val p = service(t).register("c1", "conv1", "下周一起爬山", future, PromiseSource.CHAT, "", now) // 去空白等值 existing
        assertEquals("loopX", p!!.openLoopUuid) // 只关联
        coVerify(exactly = 0) { t.second.upsert(any()) } // 不新建 loop
        verify(exactly = 0) { t.third.scheduleOneShot(any(), OpenLoopDueWorker::class.java, any(), any(), any(), any()) } // 不排 worker
    }

    @Test fun applyReconciliation_setsStatusEvidence_andResolvesLinkedOpenLoop() = runBlocking {
        val t = fixture()
        val current = PromiseEntity(
            uuid = "p1", characterUuid = "c1", content = "帮忙改简历", statusRaw = PromiseStatus.OPEN,
            openLoopUuid = "loop1", createdAtMillis = 0, updatedAtMillis = 0,
        )
        val loop = OpenLoopEntity(
            uuid = "loop1", conversationUuid = "conv1", characterUuid = "c1", content = "帮忙改简历",
            typeRaw = OpenLoopType.PROMISE_CHAR, statusRaw = OpenLoopStatus.OPEN, createdAt = 0,
        )
        coEvery { t.first.byUuid("p1") } returns current
        coEvery { t.second.byUuid("loop1") } returns loop
        val verified = PromiseReconciliation.Verified(
            changes = listOf(PromiseReconciliation.VerifiedChange("p1", PromiseStatus.FULFILLED, "我改好啦")),
            newPromises = emptyList(),
        )
        service(t).applyReconciliation("c1", "conv1", verified, now)

        coVerify(exactly = 1) {
            t.first.upsert(
                match {
                    it.uuid == "p1" && it.statusRaw == PromiseStatus.FULFILLED && it.resolvedAtMillis == now &&
                        it.resolutionEvidence == "我改好啦" && it.updatedAtMillis == now
                },
            )
        }
        coVerify(exactly = 1) { t.second.markResolved(loop, now) }
    }

    // ── applyChange 直接调用（2026-09-06 约定工具调用化 §2.2·聊天内 resolve_promise 与攒批对账共用同一落库路） ──

    @Test fun applyChange_writesAndResolvesLoop_returnsTrue() = runBlocking {
        val t = fixture()
        val current = PromiseEntity(
            uuid = "p1", characterUuid = "c1", content = "帮忙改简历", statusRaw = PromiseStatus.OPEN,
            openLoopUuid = "loop1", createdAtMillis = 0, updatedAtMillis = 0,
        )
        val loop = OpenLoopEntity(
            uuid = "loop1", conversationUuid = "conv1", characterUuid = "c1", content = "帮忙改简历",
            typeRaw = OpenLoopType.PROMISE_CHAR, statusRaw = OpenLoopStatus.OPEN, createdAt = 0,
        )
        coEvery { t.first.byUuid("p1") } returns current
        coEvery { t.second.byUuid("loop1") } returns loop

        val ok = service(t).applyChange(
            PromiseReconciliation.VerifiedChange("p1", PromiseStatus.FULFILLED, "我改好啦"),
            now,
        )

        assertTrue(ok)
        coVerify(exactly = 1) {
            t.first.upsert(
                match {
                    it.uuid == "p1" && it.statusRaw == PromiseStatus.FULFILLED && it.resolvedAtMillis == now &&
                        it.resolutionEvidence == "我改好啦" && it.updatedAtMillis == now
                },
            )
        }
        coVerify(exactly = 1) { t.second.markResolved(loop, now) }
    }

    @Test fun applyChange_targetMissingOrNotOpen_returnsFalse_zeroWrite() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("gone") } returns null
        assertFalse(service(t).applyChange(PromiseReconciliation.VerifiedChange("gone", PromiseStatus.FULFILLED, "证据"), now))

        val t2 = fixture()
        coEvery { t2.first.byUuid("p1") } returns PromiseEntity(
            uuid = "p1", characterUuid = "c1", content = "帮忙改简历", statusRaw = PromiseStatus.CANCELLED,
            createdAtMillis = 0, updatedAtMillis = 0,
        )
        assertFalse(
            "编号过期 / 已被并发了结 → 重读非 open → 零写 false",
            service(t2).applyChange(PromiseReconciliation.VerifiedChange("p1", PromiseStatus.FULFILLED, "证据"), now),
        )
        coVerify(exactly = 0) { t.first.upsert(any()) }
        coVerify(exactly = 0) { t2.first.upsert(any()) }
    }

    @Test fun applyReconciliation_linkedLoopNotOpen_noOp_e16() = runBlocking {
        val t = fixture()
        val current = PromiseEntity(
            uuid = "p1", characterUuid = "c1", content = "帮忙改简历", statusRaw = PromiseStatus.OPEN,
            openLoopUuid = "loop1", createdAtMillis = 0, updatedAtMillis = 0,
        )
        val resolvedLoop = OpenLoopEntity(
            uuid = "loop1", conversationUuid = "conv1", characterUuid = "c1", content = "帮忙改简历",
            typeRaw = OpenLoopType.PROMISE_CHAR, statusRaw = OpenLoopStatus.RESOLVED, createdAt = 0,
        )
        coEvery { t.first.byUuid("p1") } returns current
        coEvery { t.second.byUuid("loop1") } returns resolvedLoop
        service(t).applyReconciliation(
            "c1", "conv1",
            PromiseReconciliation.Verified(
                listOf(PromiseReconciliation.VerifiedChange("p1", PromiseStatus.FULFILLED, "证据")),
                emptyList(),
            ),
            now,
        )
        coVerify(exactly = 1) { t.first.upsert(any()) } // promise 仍更新
        coVerify(exactly = 0) { t.second.markResolved(any(), any()) } // loop 非 open → no-op
    }

    @Test fun applyReconciliation_promiseNoLongerOpen_skipsWrite_staleGuard() = runBlocking {
        val t = fixture()
        val alreadyResolved = PromiseEntity(
            uuid = "p1", characterUuid = "c1", content = "帮忙改简历", statusRaw = PromiseStatus.FULFILLED,
            createdAtMillis = 0, updatedAtMillis = 0,
        )
        coEvery { t.first.byUuid("p1") } returns alreadyResolved
        service(t).applyReconciliation(
            "c1", "conv1",
            PromiseReconciliation.Verified(
                listOf(PromiseReconciliation.VerifiedChange("p1", PromiseStatus.CANCELLED, "证据")),
                emptyList(),
            ),
            now,
        )
        coVerify(exactly = 0) { t.first.upsert(any()) } // 非 open → 不写（陈旧防护）
    }

    @Test fun applyReconciliation_newPromise_registersAsChat() = runBlocking {
        val t = fixture()
        service(t).applyReconciliation(
            "c1", "conv1",
            PromiseReconciliation.Verified(
                changes = emptyList(),
                newPromises = listOf(PromiseReconciliation.VerifiedNew("周末去露营", future, "说好周末露营")),
            ),
            now,
        )
        coVerify(exactly = 1) {
            t.first.upsert(match { it.content == "周末去露营" && it.sourceRaw == PromiseSource.CHAT && it.dueAtMillis == future })
        }
    }

    // ── T2-3 registerFromMeeting + 过期 due ──

    @Test fun registerFromMeeting_eachDueNull_noLoop() = runBlocking {
        val t = fixture()
        service(t).registerFromMeeting("c1", "conv1", "sessX", listOf("下次一起做饭", "教我弹吉他"), now)
        coVerify(exactly = 1) {
            t.first.upsert(match { it.content == "下次一起做饭" && it.sourceRaw == PromiseSource.MEETING && it.dueAtMillis == null && it.sourceSessionId == "sessX" })
        }
        coVerify(exactly = 1) {
            t.first.upsert(match { it.content == "教我弹吉他" && it.sourceRaw == PromiseSource.MEETING && it.openLoopUuid == null })
        }
        coVerify(exactly = 0) { t.second.upsert(any()) } // 无日期 → 不建 loop
        verify(exactly = 0) { t.third.scheduleOneShot(any(), OpenLoopDueWorker::class.java, any(), any(), any(), any()) }
    }

    @Test fun register_pastDue_persistsButNoLoop_e14() = runBlocking {
        val t = fixture()
        val p = service(t).register("c1", "conv1", "上周该做的事", past, PromiseSource.CHAT, "", now)
        assertEquals(past, p!!.dueAtMillis) // 照常注册
        assertNull("过期 due 不桥接惦记", p.openLoopUuid)
        coVerify(exactly = 0) { t.second.upsert(any()) }
        verify(exactly = 0) { t.third.scheduleOneShot(any(), OpenLoopDueWorker::class.java, any(), any(), any(), any()) }
    }

    // ── T2-1（记忆改造三期·图纸 §3.3 / §7）resolveManually：手动兜底第四道闸。断言从 §3.3 独立反推 ──

    private fun openPromise(openLoopUuid: String?) = PromiseEntity(
        uuid = "p1", characterUuid = "c1", content = "帮忙改简历", statusRaw = PromiseStatus.OPEN,
        openLoopUuid = openLoopUuid, resolutionEvidence = "", createdAtMillis = 0, updatedAtMillis = 0,
    )

    private fun loop(status: String) = OpenLoopEntity(
        uuid = "loop1", conversationUuid = "conv1", characterUuid = "c1", content = "帮忙改简历",
        typeRaw = OpenLoopType.PROMISE_CHAR, statusRaw = status, createdAt = 0,
    )

    @Test fun resolveManually_open_writesFields_evidenceStaysEmpty_resolvesLinkedLoop() = runBlocking {
        val t = fixture()
        val current = openPromise(openLoopUuid = "loop1")
        val linked = loop(OpenLoopStatus.OPEN)
        coEvery { t.first.byUuid("p1") } returns current
        coEvery { t.second.byUuid("loop1") } returns linked
        val ok = service(t).resolveManually("p1", PromiseStatus.FULFILLED, now)
        assertTrue("open 目标应写入生效", ok)
        coVerify(exactly = 1) {
            t.first.upsert(
                match {
                    it.uuid == "p1" && it.statusRaw == PromiseStatus.FULFILLED && it.resolvedAtMillis == now &&
                        it.updatedAtMillis == now && it.resolutionEvidence == "" // 手动恒不写证据（闭环不变量）
                },
            )
        }
        coVerify(exactly = 1) { t.second.markResolved(linked, now) }
    }

    @Test fun resolveManually_alreadyResolved_returnsFalse_noWrite_e1e2() = runBlocking {
        val t = fixture()
        val already = PromiseEntity(
            uuid = "p1", characterUuid = "c1", content = "帮忙改简历", statusRaw = PromiseStatus.FULFILLED,
            openLoopUuid = "loop1", createdAtMillis = 0, updatedAtMillis = 0,
        )
        coEvery { t.first.byUuid("p1") } returns already
        val ok = service(t).resolveManually("p1", PromiseStatus.CANCELLED, now)
        assertFalse("非 open → 零写返回 false（并发抢先/连点幂等）", ok)
        coVerify(exactly = 0) { t.first.upsert(any()) }
        coVerify(exactly = 0) { t.second.markResolved(any(), any()) }
    }

    @Test fun resolveManually_linkedLoopNotOpen_noMarkResolved_e3() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("p1") } returns openPromise(openLoopUuid = "loop1")
        coEvery { t.second.byUuid("loop1") } returns loop(OpenLoopStatus.RESOLVED)
        val ok = service(t).resolveManually("p1", PromiseStatus.FULFILLED, now)
        assertTrue(ok)
        coVerify(exactly = 1) { t.first.upsert(any()) } // promise 仍写
        coVerify(exactly = 0) { t.second.markResolved(any(), any()) } // loop 非 open → no-op
    }

    @Test fun resolveManually_noOpenLoopUuid_noLoopCalls_e4() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("p1") } returns openPromise(openLoopUuid = null)
        val ok = service(t).resolveManually("p1", PromiseStatus.CANCELLED, now)
        assertTrue(ok)
        coVerify(exactly = 1) { t.first.upsert(match { it.statusRaw == PromiseStatus.CANCELLED && it.resolvedAtMillis == now }) }
        coVerify(exactly = 0) { t.second.byUuid(any()) }
        coVerify(exactly = 0) { t.second.markResolved(any(), any()) }
    }

    @Test fun resolveManually_illegalStatus_returnsFalse_noReadNoWrite_e5() = runBlocking {
        val t = fixture()
        val ok = service(t).resolveManually("p1", PromiseStatus.OPEN, now) // open 非合法目标态（守卫先拦）
        assertFalse(ok)
        coVerify(exactly = 0) { t.first.byUuid(any()) } // 守卫在读库前 → 零读零写
        coVerify(exactly = 0) { t.first.upsert(any()) }
    }

    @Test fun resolveManually_targetNotFound_returnsFalse_noWrite() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("missing") } returns null
        val ok = service(t).resolveManually("missing", PromiseStatus.FULFILLED, now)
        assertFalse(ok)
        coVerify(exactly = 0) { t.first.upsert(any()) }
        coVerify(exactly = 0) { t.second.markResolved(any(), any()) }
    }

    // ── T2-6（记忆改造四期·§3.5-③ / §7）：applyReconciliation dates 补日期。断言从 §3.4/§3.5 独立反推 ──

    private fun openNoDate() = PromiseEntity(
        uuid = "p1", characterUuid = "c1", content = "看画展", statusRaw = PromiseStatus.OPEN,
        dueAtMillis = null, openLoopUuid = null, resolutionEvidence = "", createdAtMillis = 0, updatedAtMillis = 0,
    )

    private fun datesVerified(dueAtMillis: Long) = PromiseReconciliation.Verified(
        changes = emptyList(),
        newPromises = emptyList(),
        dates = listOf(PromiseReconciliation.VerifiedDate("p1", dueAtMillis, "说好那天的原话")),
    )

    @Test fun applyReconciliation_dates_fillsDueAndLinksLoop_evidenceUntouched_e7() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("p1") } returns openNoDate()
        service(t).applyReconciliation("c1", "conv1", datesVerified(future), now)
        // 未来日期 + 无等值 loop → linkOrCreateLoop 新建 loop → 补 due + openLoopUuid + updatedAt；resolutionEvidence 恒不写。
        coVerify(exactly = 1) {
            t.first.upsert(
                match {
                    it.uuid == "p1" && it.dueAtMillis == future && it.openLoopUuid != null &&
                        it.updatedAtMillis == now && it.resolutionEvidence == ""
                },
            )
        }
        coVerify(exactly = 1) { t.second.upsert(any()) } // 新建 loop
    }

    @Test fun applyReconciliation_dates_targetResolved_noOp() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("p1") } returns openNoDate().copy(statusRaw = PromiseStatus.FULFILLED)
        service(t).applyReconciliation("c1", "conv1", datesVerified(future), now)
        coVerify(exactly = 0) { t.first.upsert(any()) } // 非 open → 重读守卫拦
    }

    @Test fun applyReconciliation_dates_targetAlreadyHasDue_noOp() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("p1") } returns openNoDate().copy(dueAtMillis = future)
        service(t).applyReconciliation("c1", "conv1", datesVerified(future + 1), now)
        coVerify(exactly = 0) { t.first.upsert(any()) } // 已有 due → 重读守卫拦（只补空·改期不做）
    }

    @Test fun applyReconciliation_dates_pastDate_writesButNoLoop_e8() = runBlocking {
        val t = fixture()
        coEvery { t.first.byUuid("p1") } returns openNoDate()
        service(t).applyReconciliation("c1", "conv1", datesVerified(past), now)
        coVerify(exactly = 1) { t.first.upsert(match { it.dueAtMillis == past && it.openLoopUuid == null }) } // 照写
        coVerify(exactly = 0) { t.second.upsert(any()) } // 过去日期不建 loop（due ≤ now）
    }

    // ── T2-7（记忆改造四期·§3.5-②）：register 链接重构。有日期分支哨兵 = 既有 register_futureDue* 两例未改仍绿 ──

    @Test fun register_noDue_linksExistingEquivalentLoop_noNewLoopNoWorker_e9() = runBlocking {
        val t = fixture()
        val existing = OpenLoopEntity(
            uuid = "loopX", conversationUuid = "conv1", characterUuid = "c1", content = "下周 一起 爬山",
            typeRaw = OpenLoopType.PROMISE_CHAR, statusRaw = OpenLoopStatus.OPEN, createdAt = now,
        )
        coEvery { t.second.openLoopsForCharacter("c1") } returns listOf(existing)
        val p = service(t).register("c1", "conv1", "下周一起爬山", null, PromiseSource.CHAT, "", now) // 无日期
        assertEquals("无日期也链接等值 open loop（四期新行为）", "loopX", p!!.openLoopUuid)
        coVerify(exactly = 0) { t.second.upsert(any()) } // 不新建 loop
        verify(exactly = 0) { t.third.scheduleOneShot(any(), OpenLoopDueWorker::class.java, any(), any(), any(), any()) } // 不排 worker
    }

    @Test fun register_noDue_noEquivalentLoop_openLoopNull_unchanged_e9() = runBlocking {
        val t = fixture() // openLoopsForCharacter 默认 emptyList
        val p = service(t).register("c1", "conv1", "随口说的一件事", null, PromiseSource.CHAT, "", now)
        assertNull("无等值 loop → openLoopUuid null（与旧行为逐字一致）", p!!.openLoopUuid)
        coVerify(exactly = 0) { t.second.upsert(any()) }
        verify(exactly = 0) { t.third.scheduleOneShot(any(), OpenLoopDueWorker::class.java, any(), any(), any(), any()) }
    }
}
