package com.situ.aichat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文日志分类映射单测（批 D）。**核心 = 守门 iOS 归类 bug 的修复 + 防回归**：
 * 断言「每个 [LogSource] 恰属一个具体类目」（无孤儿、无重复），并钉死 iOS 原版漏掉的三个 source 的新归属。
 * 断言从规格 §3.5 反推，不照搬实现。
 */
class LogCategoryTest {

    /** ★ 不变量：每个已知来源恰好命中 1 个具体类目（无孤儿=避开 iOS bug；无重复=不会一条日志串两个 tab）。 */
    @Test
    fun everySourceMapsToExactlyOneSourceFilterCategory() {
        for (source in LogSource.ALL) {
            val cats = LogCategory.sourceFilterCategoriesFor(source)
            assertEquals("source 「$source」应恰属 1 个具体类目，实际命中 ${cats.map { it.name }}", 1, cats.size)
        }
    }

    /** ★ 回归钉子：iOS 原版漏映射的 source，本移植的修正归属（第三个「节拍状态」随节拍卡 2026-09-06 整体退役）。 */
    @Test
    fun iosOrphanedSourcesNowCovered() {
        assertEquals(listOf(LogCategory.GIFT), LogCategory.sourceFilterCategoriesFor(LogSource.PROACTIVE_GIFT))
        assertEquals(listOf(LogCategory.GIFT), LogCategory.sourceFilterCategoriesFor(LogSource.GIFT_REACTION))
    }

    /** 故事类目只含「故事生成」（G 件退役后不再有第二项）。 */
    @Test
    fun storyCategoryContainsOnlyGeneration() {
        assertEquals(listOf(LogSource.STORY_GENERATION), LogCategory.STORY.sources)
    }

    /** 安卓特有路径的归属（忙碌/恢复回复→对话；月薪/红包决策→分析；好感→礼物；见面记忆→记忆；宠物日记→日记）。 */
    @Test
    fun androidExtraSourcesRouting() {
        assertEquals(LogCategory.CHAT, LogCategory.sourceFilterCategoriesFor(LogSource.BUSY_REPLY).single())
        assertEquals(LogCategory.CHAT, LogCategory.sourceFilterCategoriesFor(LogSource.RECOVERY_REPLY).single())
        assertEquals(LogCategory.ANALYSIS, LogCategory.sourceFilterCategoriesFor(LogSource.SALARY_INFERENCE).single())
        assertEquals(LogCategory.ANALYSIS, LogCategory.sourceFilterCategoriesFor(LogSource.RED_PACKET_DECISION).single())
        assertEquals(LogCategory.GIFT, LogCategory.sourceFilterCategoriesFor(LogSource.AFFINITY_SENSE).single())
        assertEquals(LogCategory.MEMORY, LogCategory.sourceFilterCategoriesFor(LogSource.OFFLINE_MEETING_MEMORY).single())
        assertEquals(LogCategory.DIARY, LogCategory.sourceFilterCategoriesFor(LogSource.PET_DIARY).single())
    }

    /**
     * W12 决策 43③：世界系统四来源全部归入新「世界」类目——小报自「分析」迁入 + 偷听/风物志/初遇。
     * （从规格反推：§4 决策 43③「世界日志单开类目·世界小报迁入·两新来源同归」+ 图纸 §2 补第三来源初遇。）
     */
    @Test
    fun worldSourcesRouteToWorldCategory() {
        assertEquals(LogCategory.WORLD, LogCategory.sourceFilterCategoriesFor(LogSource.WORLD_BULLETIN).single())
        assertEquals(LogCategory.WORLD, LogCategory.sourceFilterCategoriesFor(LogSource.WORLD_EAVESDROP).single())
        assertEquals(LogCategory.WORLD, LogCategory.sourceFilterCategoriesFor(LogSource.WORLD_LORE).single())
        assertEquals(LogCategory.WORLD, LogCategory.sourceFilterCategoriesFor(LogSource.WORLD_FIRST_MEET).single())
    }

    /** ALL / FAILED 是特殊筛选（非按 source），sources 空、isSourceFilter=false。 */
    @Test
    fun allAndFailedAreSpecialNonSourceFilters() {
        assertFalse(LogCategory.ALL.isSourceFilter)
        assertFalse(LogCategory.FAILED.isSourceFilter)
        assertTrue(LogCategory.ALL.sources.isEmpty())
        assertTrue(LogCategory.FAILED.sources.isEmpty())
        // 其余全是 source 过滤类目
        val sourceFilters = LogCategory.entries.filter { it.isSourceFilter }
        assertTrue(LogCategory.CHAT in sourceFilters)
        assertTrue(LogCategory.GIFT in sourceFilters)
    }

    /** [LogSource.ALL] 无重复（枚举源完整性）。 */
    @Test
    fun logSourceAllHasNoDuplicates() {
        assertEquals(LogSource.ALL.size, LogSource.ALL.toSet().size)
    }

    /** 类目里列的每个 source 都在 [LogSource.ALL] 里（防类目引用未登记的串）。 */
    @Test
    fun everyCategorySourceIsAKnownLogSource() {
        val known = LogSource.ALL.toSet()
        for (category in LogCategory.entries) {
            for (source in category.sources) {
                assertTrue("类目 ${category.name} 引用了未登记来源「$source」", source in known)
            }
        }
    }
}
