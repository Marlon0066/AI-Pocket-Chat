package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.DynamicInterest
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兴趣名兜底截断长度（[INTEREST_NAME_MAX_LEN] = 16，放宽自旧值 8）行为锁。
 *
 * 背景：资料页「兴趣热度」出现被砍成半句的残名（AI 有时无视「简短」指令返回整句话，旧兜底硬砍前 8 字）。
 * 已双管齐下：① 强化并把长度约束移到分析提示词结尾提高遵守率；② 兜底上限放宽到 16。
 * 本测钉住兜底行为——两条兴趣写入路径（初始种子 / AI 新发现）都在 16 字处截断，正常简短名零改动。
 *
 * 提示词遵守率（AI 是否真返回简短名）非单测可验，挂真机+key 观感批。
 */
class InterestNameCapTest {

    // seed / applyNewInterests 不触碰任何构造依赖，全部 relaxed mock 即可实例化。
    private fun coordinator() = GrowthAnalysisCoordinator(
        service = mockk(relaxed = true),
        characterDao = mockk(relaxed = true),
        milestoneDao = mockk(relaxed = true),
        characterWriteLock = mockk(relaxed = true),
        settingsRepo = mockk(relaxed = true),
        throttleStore = mockk(relaxed = true),
        affectKernel = mockk(relaxed = true),
        intentKernel = mockk(relaxed = true),
        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.systemDefault()),
    )

    private val now = 1_700_000_000_000L

    @Test
    fun `新发现兴趣名超长时截断为 16 字`() {
        val interests = mutableListOf<DynamicInterest>()
        val longName = "自慰并把自己的感受详细写下来分享给对方" // 19 字，远超 16 字上限
        coordinator().applyNewInterests(
            listOf(GrowthAnalysisResult.NewInterest(name = longName, initialHeat = 40)),
            interests,
            now,
        )
        assertEquals(1, interests.size)
        assertEquals(16, interests[0].name.length)
        assertEquals(longName.take(16), interests[0].name)
    }

    @Test
    fun `新发现兴趣名短于上限时原样保留`() {
        val interests = mutableListOf<DynamicInterest>()
        coordinator().applyNewInterests(
            listOf(GrowthAnalysisResult.NewInterest(name = "手冲咖啡", initialHeat = 40)),
            interests,
            now,
        )
        assertEquals("手冲咖啡", interests[0].name)
    }

    @Test
    fun `初始兴趣种子逐项按 16 字截断且保序去重`() {
        val interests = mutableListOf<DynamicInterest>()
        // 首项超长(18字)截断为16；次项正常保留；第三项与首项截断后不同故保留。
        val raw = "一二三四五六七八九十一二三四五六七八，烘焙，日本动漫"
        coordinator().seedInitialInterests(raw, interests, now)

        assertEquals(3, interests.size)
        assertTrue(interests.all { it.name.length <= 16 })
        assertEquals("一二三四五六七八九十一二三四五六", interests[0].name)
        assertEquals("烘焙", interests[1].name)
        assertEquals("日本动漫", interests[2].name)
    }
}
