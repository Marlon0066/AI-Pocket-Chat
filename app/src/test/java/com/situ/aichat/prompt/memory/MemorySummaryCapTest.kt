package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 记忆摘要防无限增长 T2（批1 1-4·MockK·CHAT_CORE_HEALTH_PLAN.md）：
 * 规格（拍板 2026-07-02；自愈+泄压阀扩展拍板 2026-07-11·微图纸「记忆护栏自愈泄压与默认5000」G3）——
 * maxLength 是代码层硬护栏：超软目标 1.2× 触发一次压缩自救（A）；仍超硬上限 1.5× → 瘦身存量旧记忆
 * 并重合并一次（B·自愈）；仍超 1.5× 但 ≤2.0× → 泄压阀放行一次（C）；超 2.0× 终败（D）——瘦身稿可用则
 * 先落底（不推进游标）再抛 TooLong。垃圾短压缩输出不得反噬记忆；maxLength ≤ 0 = 关闭上限。
 * 本文件断言从拍板规格独立反推（阈值 3600/4500/6000 由 3000×1.2/1.5/2.0 重新手算，不引用实现常量）。
 */
class MemorySummaryCapTest {

    private val memoryService = mockk<MemoryService>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val coordinator = MemorySummaryCoordinator(memoryService, characterDao)

    private val character = CharacterEntity(uuid = "c1", name = "角色", creationDate = 0L)
    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )
    private val messages = listOf(
        MessageEntity(messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "user", content = "测试消息", timestamp = 1L),
    )

    private fun mem(n: Int) = "记".repeat(n)

    private fun stubGenerate(result: String) {
        coEvery {
            // 记忆改造一期：generateMemorySummary 新增 extraMaterial 参数 → 9 个 any()。
            memoryService.generateMemorySummary(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns result
    }

    private fun stubCompress(result: String) {
        coEvery { memoryService.compressMemory(any(), any(), any(), any()) } returns result
    }

    private fun run(maxLength: Int): Pair<String, Boolean> = runBlocking {
        var marked = false
        val persisted = coordinator.summarizeAndPersist(
            character = character,
            messages = messages,
            config = config,
            maxLength = maxLength,
            markSummarized = { marked = true },
        )
        persisted to marked
    }

    @Test
    fun `软目标以内不触发压缩`() {
        stubGenerate(mem(3000))
        val (persisted, marked) = run(maxLength = 3000)
        assertEquals(3000, persisted.length)
        assertTrue(marked)
        coVerify(exactly = 0) { memoryService.compressMemory(any(), any(), any(), any()) }
    }

    @Test
    fun `超软目标时压缩自救并采纳结果`() {
        stubGenerate(mem(4000))
        stubCompress(mem(2900))
        val (persisted, marked) = run(maxLength = 3000)
        assertEquals("应写回压缩后的记忆", 2900, persisted.length)
        assertTrue(marked)
        coVerify(exactly = 1) { memoryService.compressMemory(mem(4000), any(), 3000, any()) }
        coVerify(exactly = 1) { characterDao.updateMemorySummary("c1", "", mem(2900)) }
    }

    @Test
    fun `泄压阀_自救后处于1_5至2倍之间则放行一次`() {
        // 无旧记忆（自愈跳过）：candidate 6000 → 自救 5000，超硬上限 4500 但 ≤ 泄压阀 6000 → 放行写回。
        stubGenerate(mem(6000))
        stubCompress(mem(5000))
        val (persisted, marked) = run(maxLength = 3000)
        assertEquals("泄压阀放行一次打破死锁", 5000, persisted.length)
        assertTrue("泄压阀写回含新消息，游标正常推进", marked)
        coVerify(exactly = 1) { characterDao.updateMemorySummary("c1", "", mem(5000)) }
    }

    @Test
    fun `超过泄压阀2倍且无可用瘦身稿则拒绝写回保旧记忆`() {
        stubGenerate(mem(8000))
        stubCompress(mem(7000)) // 泄压阀 3000×2.0=6000，仍超；旧记忆为空 → 无瘦身稿可落底
        var marked = false
        try {
            runBlocking {
                coordinator.summarizeAndPersist(
                    character = character,
                    messages = messages,
                    config = config,
                    maxLength = 3000,
                    markSummarized = { marked = true },
                )
            }
            fail("应抛 TooLong")
        } catch (e: MemorySummaryError) {
            assertTrue(e is MemorySummaryError.TooLong)
        }
        assertFalse("拒绝时游标绝不推进", marked)
        coVerify(exactly = 0) { characterDao.updateMemorySummary(any(), any(), any()) }
    }

    // ── 自愈 + 终败落底（G3·存量旧记忆非空路径；字符区分各稿防 eq 撞车）──

    private val oldMemory = "旧".repeat(5000)
    private val characterWithMemory = character.copy(memorySummary = oldMemory)

    @Test
    fun `自愈_瘦身旧底稿重合并达标则写回且游标推进`() {
        // 首合并 6000 → 自救仍 5500（>4500）→ 瘦身旧稿 5000→2000（过闸）→ 重合并 4000（≤4500 且更短）→ 写回。
        // 锁内重读（批3 3-4）走 characterDao.getByUuid → 必须打桩返回带旧记忆的角色，否则 relaxed mock 空记忆顶掉底稿。
        coEvery { characterDao.getByUuid("c1") } returns characterWithMemory
        coEvery {
            memoryService.generateMemorySummary(eq(oldMemory), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "新".repeat(6000)
        coEvery {
            memoryService.generateMemorySummary(eq("瘦".repeat(2000)), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "合".repeat(4000)
        coEvery { memoryService.compressMemory(eq("新".repeat(6000)), any(), any(), any()) } returns "新".repeat(5500)
        coEvery { memoryService.compressMemory(eq(oldMemory), any(), any(), any()) } returns "瘦".repeat(2000)

        var marked = false
        val persisted = runBlocking {
            coordinator.summarizeAndPersist(
                character = characterWithMemory,
                messages = messages,
                config = config,
                maxLength = 3000,
                markSummarized = { marked = true },
            )
        }
        assertEquals("自愈重合并结果写回", "合".repeat(4000), persisted)
        assertTrue("自愈写回含新消息，游标正常推进", marked)
        coVerify(exactly = 1) { characterDao.updateMemorySummary("c1", oldMemory, "合".repeat(4000)) }
    }

    @Test
    fun `终败_全部超限时瘦身稿落底但游标绝不推进`() {
        // 首合并 8000 → 自救 7000（>6000）→ 瘦身 2000（过闸）→ 重合并仍 7000（不更短，不采纳）→ 终败：
        // slim 落底写回 + 不 markSummarized + 抛 TooLong（下轮以瘦底稿重试，消息一条不丢）。
        coEvery { characterDao.getByUuid("c1") } returns characterWithMemory
        coEvery {
            memoryService.generateMemorySummary(eq(oldMemory), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "新".repeat(8000)
        coEvery {
            memoryService.generateMemorySummary(eq("瘦".repeat(2000)), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "合".repeat(7000)
        coEvery { memoryService.compressMemory(eq("新".repeat(8000)), any(), any(), any()) } returns "新".repeat(7000)
        coEvery { memoryService.compressMemory(eq(oldMemory), any(), any(), any()) } returns "瘦".repeat(2000)

        var marked = false
        try {
            runBlocking {
                coordinator.summarizeAndPersist(
                    character = characterWithMemory,
                    messages = messages,
                    config = config,
                    maxLength = 3000,
                    markSummarized = { marked = true },
                )
            }
            fail("应抛 TooLong")
        } catch (e: MemorySummaryError) {
            assertTrue(e is MemorySummaryError.TooLong)
        }
        assertFalse("终败绝不推进游标——下轮重喂同批消息", marked)
        coVerify(exactly = 1) { characterDao.updateMemorySummary("c1", oldMemory, "瘦".repeat(2000)) }
    }

    @Test
    fun `垃圾短压缩输出不采纳_原文在硬上限内则写回原文`() {
        stubGenerate(mem(4000)) // 超软目标 3600，但在硬上限 4500 内
        stubCompress(mem(10))   // < 原文 5%，视为垃圾
        val (persisted, marked) = run(maxLength = 3000)
        assertEquals("垃圾压缩不得反噬，写回原文", 4000, persisted.length)
        assertTrue(marked)
    }

    @Test
    fun `maxLength为0时上限关闭`() {
        stubGenerate(mem(90000))
        val (persisted, _) = run(maxLength = 0)
        assertEquals(90000, persisted.length)
        coVerify(exactly = 0) { memoryService.compressMemory(any(), any(), any(), any()) }
    }

    // ── 校验 2「短稿闸」（图纸 2026-09-05 §3.3 / §7 T2-5·E9–E13）──
    // 规格：默认模板下限 = 1/3 × min(旧长, maxLength)（maxLength ≤ 0 只看旧长）；自定义模板让位仍 1/20。
    // 下方每个下限都按该式**重新手算**为字面量，不引用实现（实现改公式必在此撞墙）。
    // 各例新稿都远低于 1.2×maxLength，压缩链不参与；旧记忆经 getByUuid 显式打桩（relaxed 空记忆会顶掉底稿）。

    /** 按指定旧记忆 / 上限 / 自定义模板跑一次，返回 (写回文本, 游标是否推进)。 */
    private fun runWithOldMemory(oldLength: Int, newLength: Int, maxLength: Int, customPrompt: String = ""): Pair<String, Boolean> {
        val old = "旧".repeat(oldLength)
        coEvery { characterDao.getByUuid("c1") } returns character.copy(memorySummary = old)
        stubGenerate(mem(newLength))
        var marked = false
        val persisted = runBlocking {
            coordinator.summarizeAndPersist(
                character = character.copy(memorySummary = old),
                messages = messages,
                config = config,
                maxLength = maxLength,
                customPrompt = customPrompt,
                markSummarized = { marked = true },
            )
        }
        return persisted to marked
    }

    /** 断言这一档被短稿闸拒收：抛 SuspiciouslyShort + 零写回 + 游标不推进。 */
    private fun assertRejectedAsShort(oldLength: Int, newLength: Int, maxLength: Int, customPrompt: String = "") {
        try {
            val (_, marked) = runWithOldMemory(oldLength, newLength, maxLength, customPrompt)
            fail("应抛 SuspiciouslyShort（marked=$marked）")
        } catch (e: MemorySummaryError) {
            assertTrue("应为 SuspiciouslyShort，实为 $e", e is MemorySummaryError.SuspiciouslyShort)
        }
        coVerify(exactly = 0) { characterDao.updateMemorySummary(any(), any(), any()) }
    }

    @Test
    fun `短稿闸_旧3000上限5000_新稿999低于下限1000_拒收`() {
        // E9：min(3000, 5000)/3 = 1000；999 < 1000。
        assertRejectedAsShort(oldLength = 3000, newLength = 999, maxLength = 5000)
    }

    @Test
    fun `短稿闸_旧3000上限5000_新稿1000正好达下限_放行`() {
        // E10：比较为「严格小于才拒」，等于下限放行。
        val (persisted, marked) = runWithOldMemory(oldLength = 3000, newLength = 1000, maxLength = 5000)
        assertEquals(1000, persisted.length)
        assertTrue("放行例游标正常推进", marked)
        coVerify(exactly = 1) { characterDao.updateMemorySummary("c1", "旧".repeat(3000), mem(1000)) }
    }

    @Test
    fun `短稿闸_上限2000小于旧记忆5000_下限按上限算666_新稿700放行`() {
        // E11 上半：min(5000, 2000)/3 = 666——上限比旧记忆小时，闸随上限走，用户调小上限后不会被自己的旧记忆锁死。
        val (persisted, marked) = runWithOldMemory(oldLength = 5000, newLength = 700, maxLength = 2000)
        assertEquals(700, persisted.length)
        assertTrue(marked)
    }

    @Test
    fun `短稿闸_上限2000小于旧记忆5000_新稿600低于下限666_拒收`() {
        // E11 下半：同一档的另一侧。
        assertRejectedAsShort(oldLength = 5000, newLength = 600, maxLength = 2000)
    }

    @Test
    fun `短稿闸_自定义模板让位_旧3000新稿200仍放行`() {
        // E12 上半：自定义模板下限退回 3000/20 = 150；200 ≥ 150 → 放行（默认模板下 1000 的闸不适用）。
        val (persisted, marked) = runWithOldMemory(oldLength = 3000, newLength = 200, maxLength = 5000, customPrompt = "我的模板")
        assertEquals(200, persisted.length)
        assertTrue(marked)
    }

    @Test
    fun `短稿闸_自定义模板_旧3000新稿100低于下限150_仍拒收`() {
        // E12 下半：让位不等于拆闸，1/20 那道仍在。
        assertRejectedAsShort(oldLength = 3000, newLength = 100, maxLength = 5000, customPrompt = "我的模板")
    }

    @Test
    fun `短稿闸_上限关闭时只看旧长_旧3000新稿900拒收`() {
        // E13：maxLength ≤ 0 → 下限 = 3000/3 = 1000；900 < 1000。
        assertRejectedAsShort(oldLength = 3000, newLength = 900, maxLength = 0)
    }
}
