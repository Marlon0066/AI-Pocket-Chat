package com.situ.aichat.gift

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AffinitySensePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 心意反馈单测（断言反推 iOS）：currentSenseText 档位选择 + 手作徽章 + 空/坏包回落 fallback、effectivePackage isWellFormed
 * 守卫、isExpired 14 天边界、30 条兜底结构、buildPrompt 关键段。LLM 生成 generatePackageIfNeeded 留真机。
 */
class AffinitySenseTest {

    // 单元素各档 → randomOrNull 必返该元素（确定性）
    private val pkgJson = AffinitySenseService.encode(
        AffinitySensePackage(low = listOf("L"), mid = listOf("M"), high = listOf("H"), handmade = listOf("HM")),
    )
    private val rng = Random(42)

    // MARK: - currentSenseText 档位

    @Test fun sense_tier_selection() {
        assertEquals("L", AffinitySenseService.currentSenseText(pkgJson, gain = 3, isHandmade = false, rng = rng).text)
        assertEquals("M", AffinitySenseService.currentSenseText(pkgJson, gain = 8, isHandmade = false, rng = rng).text)
        assertEquals("H", AffinitySenseService.currentSenseText(pkgJson, gain = 15, isHandmade = false, rng = rng).text)
    }

    @Test fun sense_handmade_badge() {
        val handmade = AffinitySenseService.currentSenseText(pkgJson, gain = 3, isHandmade = true, rng = rng)
        assertEquals("L", handmade.text)
        assertEquals("HM", handmade.handmadeBadge)
        // 非手作无副标签
        assertNull(AffinitySenseService.currentSenseText(pkgJson, gain = 3, isHandmade = false, rng = rng).handmadeBadge)
    }

    @Test fun sense_empty_package_uses_fallback() {
        val low = AffinitySenseService.currentSenseText("", gain = 3, isHandmade = false, rng = rng)
        assertTrue(AffinitySenseFallback.defaultPackage.low.contains(low.text))
        assertNull(low.handmadeBadge)
        // high 档 + 手作 → fallback high + fallback handmade
        val high = AffinitySenseService.currentSenseText("", gain = 18, isHandmade = true, rng = rng)
        assertTrue(AffinitySenseFallback.defaultPackage.high.contains(high.text))
        assertTrue(AffinitySenseFallback.defaultPackage.handmade.contains(high.handmadeBadge))
    }

    // MARK: - effectivePackage 守卫

    @Test fun effective_package_fallback_on_bad_input() {
        assertEquals(AffinitySenseFallback.defaultPackage, AffinitySenseService.effectivePackage(""))
        assertEquals(AffinitySenseFallback.defaultPackage, AffinitySenseService.effectivePackage("{坏的}"))
        // 结构不完整（high 空）→ 不 wellFormed → fallback
        val incomplete = """{"version":1,"low":["a"],"mid":["b"],"high":[],"handmade":["c"]}"""
        assertEquals(AffinitySenseFallback.defaultPackage, AffinitySenseService.effectivePackage(incomplete))
    }

    @Test fun effective_package_uses_valid_decoded() {
        val pkg = AffinitySenseService.effectivePackage(pkgJson)
        assertEquals(listOf("L"), pkg.low)
        assertEquals(listOf("HM"), pkg.handmade)
    }

    // MARK: - isExpired 14 天边界

    @Test fun is_expired_boundaries() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertTrue(AffinitySenseService.isExpired(null, now))                 // 没生成过
        assertTrue(AffinitySenseService.isExpired(now - 14 * day, now))       // 恰 14 天（>=）
        assertFalse(AffinitySenseService.isExpired(now - 13 * day, now))      // 13 天内
        assertFalse(AffinitySenseService.isExpired(now, now))                 // 刚生成
    }

    // MARK: - 30 条兜底结构

    @Test fun fallback_package_structure() {
        val p = AffinitySenseFallback.defaultPackage
        assertEquals(8, p.low.size)
        assertEquals(8, p.mid.size)
        assertEquals(8, p.high.size)
        assertEquals(6, p.handmade.size)
        assertTrue(p.isWellFormed)
    }

    // MARK: - buildPrompt 缩进泄漏（2026-09-18·微图纸 心意文案包提示词缩进泄漏）
    // 旧写法「原始字符串里插值 + 末尾 trimIndent()」先插值后去缩进：角色设定非空（systemSection 自带换行）
    // 或任一插值跨行 → 最小公共缩进归零 → 模板每行带 16 格源码缩进发给模型。

    @Test fun buildPrompt_systemPromptSet_noIndentLeak() {
        val (system, _) = AffinitySenseService.buildPrompt(
            character(personality = "活泼开朗", speakingStyle = "短句", systemPrompt = "你是一只会说话的猫。"),
            relationship = "朋友",
        )
        assertNoTemplateIndentLeak(system)
        assertEquals(
            listOf("你是「小樱」。", "人设：活泼开朗", "角色设定：你是一只会说话的猫。", "说话风格：短句", "与\"我\"（用户）当前的关系：朋友"),
            system.lines().take(5),
        )
        assertEquals(JSON_SKELETON_LINE, system.lines().last())
    }

    @Test fun buildPrompt_multiLinePersona_noIndentLeak_userTextVerbatim() {
        // 用户手写人设自带 2 格缩进的列表：必须原样透传，不能被当成模板缩进一起削掉。
        val (system, _) = AffinitySenseService.buildPrompt(
            character(personality = "嘴硬心软\n  - 怕黑\n  - 嗜甜", speakingStyle = "短句"),
            relationship = "恋人",
        )
        assertNoTemplateIndentLeak(system)
        assertTrue(
            system,
            system.startsWith("你是「小樱」。\n人设：嘴硬心软\n  - 怕黑\n  - 嗜甜\n说话风格：短句\n与\"我\"（用户）当前的关系：恋人\n\n请为你自己"),
        )
        assertEquals(JSON_SKELETON_LINE, system.lines().last())
    }

    /**
     * 回归钉：插值全单行且无角色设定时（旧实现唯一正确的情形），提示词与修前**逐字节相同**。
     * 金标 = 修前实现对同一输入的实跑输出（2026-09-18 取证），不是照抄新实现；金标本身无插值，trimIndent 安全。
     */
    @Test fun buildPrompt_singleLineInputs_byteIdenticalToPreFixGolden() {
        val (system, user) = AffinitySenseService.buildPrompt(
            character(personality = "活泼开朗，嘴硬心软", speakingStyle = "短句，爱用语气词"),
            relationship = "恋人",
        )
        val golden = """
            你是「小樱」。
            人设：活泼开朗，嘴硬心软
            说话风格：短句，爱用语气词
            与"我"（用户）当前的关系：恋人

            请为你自己生成一组"收到我送的礼物时的心意反馈文案"。这些文案会在我送礼后的反应页上显示，
            目的是让我感受到你真实的情绪反应，而不是看到"+10 好感度"这种冰冷的数字。

            生成要求：
            1. 按 3 档情感强度各给 8 条，再给 6 条"手作礼物专属副标签"：
               - low（轻微触动）：礼物普通，反应平淡但还是被打动一点
               - mid（明显开心）：送到心坎，看得出来心情变好
               - high（强烈感动）：非常贵 / 非常惊喜 / 罕见心意
               - handmade（手作副标签）：叠加在手作礼物时显示的短标签
            2. 每条 8-18 字（handmade 副标签 3-8 字）
            3. 用你的性格口吻和说话风格，第一或第三人称皆可（按更自然的选）
            4. 避免笼统词（"好开心"、"谢谢你"），要有画面感或性格特征
            5. 8 条之间要有差异，不要重复同一种表达

            严格以 JSON 输出，不要任何其他文字、不要 markdown：
            {"version":1,"low":["..."×8],"mid":["..."×8],"high":["..."×8],"handmade":["..."×6]}
        """.trimIndent()
        assertEquals(golden, system)
        assertEquals("现在请按上述规则为你自己生成这组文案。", user)
    }

    private fun character(personality: String = "", speakingStyle: String = "", systemPrompt: String = "") =
        CharacterEntity(
            uuid = "c1", name = "小樱", creationDate = 0L,
            personalityDescription = personality, speakingStyle = speakingStyle, systemPrompt = systemPrompt,
        )

    /** 模板里合法的行首缩进只有四条子条目的 3 格；≥4 格即源码缩进泄漏（旧实现泄漏 16 格）。 */
    private fun assertNoTemplateIndentLeak(system: String) {
        val lines = system.lines()
        assertTrue("有行带模板缩进：" + lines.filter { it.startsWith("    ") }, lines.none { it.startsWith("    ") })
        assertEquals(SUB_BULLET_LINES, lines.filter { it.startsWith("   ") })
    }

    private companion object {
        val SUB_BULLET_LINES = listOf(
            "   - low（轻微触动）：礼物普通，反应平淡但还是被打动一点",
            "   - mid（明显开心）：送到心坎，看得出来心情变好",
            "   - high（强烈感动）：非常贵 / 非常惊喜 / 罕见心意",
            "   - handmade（手作副标签）：叠加在手作礼物时显示的短标签",
        )

        /** 与 AffinitySensePackage 字段名 ↔ parsePackage 解码强耦合的 JSON 骨架行，逐字不许动。 */
        const val JSON_SKELETON_LINE = """{"version":1,"low":["..."×8],"mid":["..."×8],"high":["..."×8],"handmade":["..."×6]}"""
    }
}
