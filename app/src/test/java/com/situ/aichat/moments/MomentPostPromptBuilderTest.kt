package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.DynamicInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with iOS `generatePostContent` prompt assembly (Services/MomentGenerationActor+Content.swift).
 * Uses sentinel template strings (not the real localized text — that fidelity is covered by the
 * resource extraction) so the assertions pin the **section order, optional-section gating, format-arg
 * substitution, and the growth-derived helpers** — the things a port can break.
 */
class MomentPostPromptBuilderTest {

    /** Sentinel strings: each field a unique marker so order/inclusion is unambiguous. */
    private fun ts() = MomentPromptStrings(
        youAre = "YOUARE:%1\$s",
        personality = "PERS:%1\$s",
        characterSetup = "SETUP:%1\$s",
        gender = "GENDER:%1\$s",
        occupation = "OCC:%1\$s",
        backstory = "BACK:%1\$s",
        speakingStyle = "STYLE:%1\$s",
        catchphrases = "CATCH:%1\$s",
        interests = "INTERESTS:%1\$s",
        hotInterests = "HOT:%1\$s",
        traitHigh = "high %1\$s",
        traitLow = "low %1\$s",
        traits = "TRAITS:%1\$s",
        memoriesHeader = "MEMHDR",
        petStatus = "PET:%1\$s|%2\$s|%3\$s",
        petMention = "PETMENTION",
        userPostsHeader = "USERPOSTSHDR",
        userPostsFooter = "USERPOSTSFOOTER",
        nowWrite = "NOWWRITE",
        requirementsHeader = "REQHDR",
        reqContent = "REQCONTENT",
        reqStyle = "REQSTYLE",
        reqWordCount = "REQWORDS:%1\$s",
        reqNatural = "REQNATURAL",
        reqNoAi = "REQNOAI",
        reqEmoji = "REQEMOJI",
        reqNoRepeat = "REQNOREPEAT",
        outputOnly = "OUTPUTONLY",
        userMessage = "USERMSG",
    )

    private fun char(
        name: String = "小樱",
        personalityDescription: String = "活泼",
        systemPrompt: String = "",
        gender: String = "",
        occupation: String = "",
        backstory: String = "",
        speakingStyle: String = "",
        catchphrases: String = "",
        initialInterests: String = "",
        memorySummary: String = "",
    ) = CharacterEntity(
        uuid = "c1",
        name = name,
        creationDate = 0L,
        systemPrompt = systemPrompt,
        personalityDescription = personalityDescription,
        gender = gender,
        occupation = occupation,
        backstory = backstory,
        speakingStyle = speakingStyle,
        catchphrases = catchphrases,
        initialInterests = initialInterests,
        memorySummary = memorySummary,
    )

    @Test fun `minimal character omits all optional sections`() {
        val out = MomentPostPromptBuilder.build(
            strings = ts(),
            character = char(),
            hotInterestNames = emptyList(),
            personalityTraits = emptyList(),
            recentUserPosts = emptyList(),
            recentOwnContents = "",
            nowContext = "NOWCTX",
            schedulePrompt = "",
            userName = "小明",
        )
        val lines = out.lines()
        assertEquals("YOUARE:小樱", lines[0])
        assertEquals("PERS:活泼", lines[1])
        // No setup/gender/.../traits; then blank, (no memories), blank, (no user posts), now-context.
        assertFalse(out.contains("SETUP:"))
        assertFalse(out.contains("MEMHDR"))
        assertFalse(out.contains("USERPOSTSHDR"))
        assertFalse(out.contains("USERPOSTSFOOTER")) // 无用户动态时背景声明也整段省略
        assertTrue(out.contains("NOWCTX"))
        // Requirements block present; no schedule/gift/no-repeat/tone.
        assertTrue(out.contains("NOWWRITE"))
        assertTrue(out.contains("REQHDR"))
        assertFalse(out.contains("REQNOREPEAT"))
        assertEquals("OUTPUTONLY", lines.last())
        // Default word count substituted.
        assertTrue(out.contains("REQWORDS:50-150"))
        // Default emoji line present (no override).
        assertTrue(out.contains("REQEMOJI"))
    }

    @Test fun `full character emits every section in iOS order`() {
        val out = MomentPostPromptBuilder.build(
            strings = ts(),
            character = char(
                systemPrompt = "S", gender = "女", occupation = "学生", backstory = "B",
                speakingStyle = "轻快", catchphrases = "口头禅", initialInterests = "咖啡", memorySummary = "MEM",
            ),
            hotInterestNames = listOf("music", "art"),
            personalityTraits = listOf("high 外向性", "low 情绪化"),
            recentUserPosts = listOf(RecentUserPost("3分钟前", "用户帖")),
            recentOwnContents = "上一条\n上上条",
            nowContext = "NOWCTX",
            schedulePrompt = "SCHED",
            giftInspiration = "GIFT",
            petStatus = "PET",
            userName = "小明",
        )
        // Assert relative ordering of the key markers (subsequence check).
        val order = listOf(
            "YOUARE:小樱", "PERS:活泼", "SETUP:S", "GENDER:女", "OCC:学生", "BACK:B", "STYLE:轻快",
            "CATCH:口头禅", "INTERESTS:咖啡", "HOT:music, art", "TRAITS:high 外向性, low 情绪化",
            "MEMHDR", "MEM", "PET", "USERPOSTSHDR", "- [3分钟前] 用户帖", "USERPOSTSFOOTER", "NOWCTX", "SCHED", "GIFT",
            "NOWWRITE", "REQHDR", "REQCONTENT", "REQSTYLE", "REQWORDS:50-150", "REQNATURAL", "REQNOAI",
            "REQEMOJI", "REQNOREPEAT", "上一条\n上上条", "OUTPUTONLY",
        )
        assertSubsequence(order, out)
    }

    // 卷四 T2-6 ②（图纸 §4.5 / §2.2）：意图块在 nowContext 之后、schedulePrompt 之前；空 ⇒ 输出与不传逐字节相同。
    @Test fun `卷四 intentBlock sits after nowContext and before schedulePrompt, empty is byte-identical`() {
        fun build(block: String?) = MomentPostPromptBuilder.build(
            strings = ts(), character = char(), hotInterestNames = emptyList(), personalityTraits = emptyList(),
            recentUserPosts = emptyList(), recentOwnContents = "", nowContext = "NOWCTX", schedulePrompt = "SCHED",
            userName = "小明", intentBlock = block ?: "",
        )
        val with = build("INTENT1\nINTENT2")
        assertSubsequence(listOf("NOWCTX", "INTENT1\nINTENT2", "SCHED"), with)
        assertTrue(with.contains("NOWCTX\n\nINTENT1\nINTENT2\n\nSCHED"))
        val without = MomentPostPromptBuilder.build(
            strings = ts(), character = char(), hotInterestNames = emptyList(), personalityTraits = emptyList(),
            recentUserPosts = emptyList(), recentOwnContents = "", nowContext = "NOWCTX", schedulePrompt = "SCHED", userName = "小明",
        )
        assertEquals(without, build(""))
        assertFalse(without.contains("INTENT"))
    }

    @Test fun `overrides replace word count, tone, emoji, and append extra rules`() {
        val out = MomentPostPromptBuilder.build(
            strings = ts(),
            character = char(),
            hotInterestNames = emptyList(),
            personalityTraits = emptyList(),
            recentUserPosts = emptyList(),
            recentOwnContents = "",
            nowContext = "NOWCTX",
            schedulePrompt = "",
            overrides = mapOf(
                MomentsPromptField.WORD_COUNT_RANGE.raw to "80-120",
                MomentsPromptField.TONE_STYLE.raw to "幽默",
                MomentsPromptField.EMOJI_POLICY.raw to "no emoji",
                MomentsPromptField.EXTRA_RULES.raw to "规则一\n规则二",
            ),
            userName = "小明",
        )
        assertTrue(out.contains("REQWORDS:80-120"))
        assertTrue(out.contains("- Tone/style preference: 幽默"))
        assertTrue(out.contains("- no emoji"))
        assertFalse(out.contains("REQEMOJI"))   // overridden emoji policy replaces the default line
        assertTrue(out.contains("- 规则一"))
        assertTrue(out.contains("- 规则二"))
    }

    // ---- growth-derived helpers ----

    @Test fun `hotInterestNames filters heat ge 60, sorts desc, takes top 5`() {
        val dyn = listOf(
            DynamicInterest(name = "a", heat = 70),
            DynamicInterest(name = "b", heat = 50),   // filtered out (<60)
            DynamicInterest(name = "c", heat = 90),
            DynamicInterest(name = "d", heat = 60),
            DynamicInterest(name = "e", heat = 80),
            DynamicInterest(name = "f", heat = 65),
            DynamicInterest(name = "g", heat = 95),
        )
        // >=60: a70,c90,d60,e80,f65,g95 → desc: g95,c90,e80,a70,f65,d60 → top5
        assertEquals(listOf("g", "c", "e", "a", "f"), MomentPostPromptBuilder.hotInterestNames(dyn))
    }

    @Test fun `personalityTraits maps ge70 to high and le30 to low in dimension order`() {
        // [外向性, 情绪化, 冒险性, 温暖度, 幽默感, 独立性, 好奇心, 坦诚度]
        val values = listOf(80, 20, 50, 70, 30, 50, 50, 75)
        val traits = MomentPostPromptBuilder.personalityTraits(ts(), values)
        assertEquals(listOf("high 外向性", "low 情绪化", "high 温暖度", "low 幽默感", "high 坦诚度"), traits)
    }

    @Test fun `personalityTraits empty when all dims neutral`() {
        assertTrue(MomentPostPromptBuilder.personalityTraits(ts(), List(8) { 50 }).isEmpty())
    }

    // MARK: - petStatusBlock（moments-logic-1·14.4b）

    @Test fun `petStatusBlock builds two lines when pet present and not ran away`() {
        val block = MomentPostPromptBuilder.petStatusBlock(ts(), petEnabled = true, petName = "团子", speciesDisplay = "猫", stageDisplay = "成年", isRanAway = false)
        assertEquals("PET:团子|猫|成年\nPETMENTION", block)
    }

    @Test fun `petStatusBlock null when pet system disabled`() {
        assertEquals(null, MomentPostPromptBuilder.petStatusBlock(ts(), petEnabled = false, petName = "团子", speciesDisplay = "猫", stageDisplay = "成年", isRanAway = false))
    }

    @Test fun `petStatusBlock null when ran away`() {
        // 离家出走 → 省略（1:1 iOS pet.neglectPhase != .ranAway 守卫）。
        assertEquals(null, MomentPostPromptBuilder.petStatusBlock(ts(), petEnabled = true, petName = "团子", speciesDisplay = "猫", stageDisplay = "成年", isRanAway = true))
    }

    @Test fun `petStatusBlock null when no pet`() {
        assertEquals(null, MomentPostPromptBuilder.petStatusBlock(ts(), petEnabled = true, petName = null, speciesDisplay = null, stageDisplay = null, isRanAway = false))
    }

    @Test fun `build injects pet status between memories and user posts`() {
        val text = MomentPostPromptBuilder.build(
            strings = ts(),
            character = char(memorySummary = "一起看过樱花"),
            hotInterestNames = emptyList(),
            personalityTraits = emptyList(),
            recentUserPosts = listOf(RecentUserPost("刚刚", "今天好开心")),
            recentOwnContents = "",
            nowContext = "",
            schedulePrompt = "",
            petStatus = MomentPostPromptBuilder.petStatusBlock(ts(), true, "团子", "猫", "成年", false),
            userName = "小明",
        )
        assertSubsequence(listOf("MEMHDR", "PET:团子|猫|成年", "PETMENTION", "USERPOSTSHDR"), text)
    }

    @Test fun `build omits pet status when null`() {
        val text = MomentPostPromptBuilder.build(
            strings = ts(),
            character = char(memorySummary = "一起看过樱花"),
            hotInterestNames = emptyList(),
            personalityTraits = emptyList(),
            recentUserPosts = emptyList(),
            recentOwnContents = "",
            nowContext = "",
            schedulePrompt = "",
            petStatus = null,
            userName = "小明",
        )
        assertFalse(text.contains("PETMENTION"))
    }

    // ── 契约 §B8：用户近期动态的「（附带图片）」标注（R2 🟡-4 补实现·R3 🟡-4 补这两条锁） ──
    // 措辞在这里**重新打字**、不引常量：引常量的话，改产线文案时测试跟着一起改，等于没测。

    @Test fun `纯图无文案的动态_渲染成一句「一条只有图片的动态」而不是空串`() {
        // 病灶：不带标注时这行是 `- [昨天] `，角色连「用户昨天发过东西」都读不出来。
        assertEquals("（一条只有图片的动态）", renderUserPostLine(RecentUserPost("昨天", "", hasImages = true)))
    }

    @Test fun `有文案又配图的动态_正文后缀「附带图片」`() {
        assertEquals("看海（附带图片）", renderUserPostLine(RecentUserPost("昨天", "看海", hasImages = true)))
    }

    @Test fun `没配图的动态_一个字都不加`() {
        // 反向钉：标注只能在真有图时出现，否则角色会凭空以为每条动态都带图
        assertEquals("看海", renderUserPostLine(RecentUserPost("昨天", "看海", hasImages = false)))
        assertEquals("", renderUserPostLine(RecentUserPost("昨天", "", hasImages = false)))
    }

    @Test fun `装配后纯图动态那一行不为空_回归钉`() {
        // 走完整 build：`hasImages` 有默认值 false 且 MomentGenerationService 是唯一构造点，
        // 哪天有人重构那段 map 回到位置参数写法，标注会静默消失——这条钉住装配后的成品行。
        val out = MomentPostPromptBuilder.build(
            strings = ts(),
            character = char(),
            hotInterestNames = emptyList(),
            personalityTraits = emptyList(),
            recentUserPosts = listOf(RecentUserPost("昨天", "", hasImages = true)),
            recentOwnContents = "",
            nowContext = "NOWCTX",
            schedulePrompt = "",
            userName = "小明",
        )
        assertTrue(out.contains("- [昨天] （一条只有图片的动态）"))
        assertFalse("绝不能留下 `- [昨天] ` 这样的空行", out.lines().any { it == "- [昨天] " })
    }

    /** Assert [markers] appear in [text] in order (each found at/after the previous match). */
    private fun assertSubsequence(markers: List<String>, text: String) {
        var idx = 0
        for (m in markers) {
            val found = text.indexOf(m, idx)
            assertTrue("marker not found in order: <$m>", found >= 0)
            idx = found + m.length
        }
    }
}
