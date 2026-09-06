package com.situ.aichat.offline

import com.situ.aichat.offline.OfflineNarrativePreset.DetailLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineNarrativePreset` tests (P10.2b-1): detail-level resolution, custom-factory rules, and
 * `buildPrompt`/`meetingPlaceClause` structure — sentinel-based, reverse-derived from iOS
 * `OfflineNarrativePreset` (incl. the iOS `\`-continuation that joins rule 8 directly to "9.").
 */
class OfflineNarrativePresetTest {

    // ── DetailLevel resolution ──

    @Test fun detail_level_from_raw_with_plain_fallback() {
        assertEquals(DetailLevel.PLAIN, DetailLevel.fromRaw("plain"))
        assertEquals(DetailLevel.NORMAL, DetailLevel.fromRaw("normal"))
        assertEquals(DetailLevel.DETAILED, DetailLevel.fromRaw("detailed"))
        assertEquals(DetailLevel.CUSTOM, DetailLevel.fromRaw("custom"))
        assertEquals(DetailLevel.PLAIN, DetailLevel.fromRaw("garbage")) // iOS default "plain"
    }

    @Test fun resolve_maps_level_to_preset() {
        assertEquals(DetailLevel.PLAIN, OfflineNarrativePreset.resolve(DetailLevel.PLAIN, "", "", "").level)
        assertEquals(DetailLevel.DETAILED, OfflineNarrativePreset.resolve(DetailLevel.DETAILED, "", "", "").level)
        assertEquals(DetailLevel.CUSTOM, OfflineNarrativePreset.resolve(DetailLevel.CUSTOM, "s", "d", "e").level)
    }

    // ── custom() factory ──

    @Test fun custom_with_style_clears_builtin_style_rules() {
        val p = OfflineNarrativePreset.custom(style = "说人话就行", directive = "指令1\n指令2\n", emotion = "底色A")
        assertEquals("", p.rule12)
        assertEquals("", p.rule13)
        assertEquals("说人话就行", p.extraStyleRules)
        assertEquals(listOf("指令1", "指令2"), p.narrativeTechniquePool)
        assertEquals(listOf("底色A"), p.emotionalRegisterPool)
        // rule8 falls back to normal.
        assertEquals(OfflineNarrativePreset.NORMAL.rule8, p.rule8)
    }

    @Test fun custom_without_style_falls_back_to_normal_rules() {
        val p = OfflineNarrativePreset.custom(style = "   ", directive = "", emotion = "")
        assertEquals(OfflineNarrativePreset.NORMAL.rule12, p.rule12)
        assertEquals(OfflineNarrativePreset.NORMAL.rule13, p.rule13)
        assertEquals(OfflineNarrativePreset.NORMAL.extraStyleRules, p.extraStyleRules)
        assertTrue(p.narrativeTechniquePool.isEmpty())
    }

    // ── buildPrompt structure ──

    private fun prompt(seed: String?, directive: String?) =
        OfflineNarrativePreset.buildPrompt(
            currentTimeText = "2026-06-03 15:30",
            userName = "小美", meetingLocation = null, meetingActivity = null,
            tensionSeed = seed, perTurnDirective = directive,
            preset = OfflineNarrativePreset.PLAIN,
        )

    @Test fun build_prompt_has_header_tags_and_rules() {
        val p = prompt(seed = null, directive = null)
        assertTrue(p.startsWith("【当前处于线下见面模式】"))
        assertTrue(p.contains("使用以下 9 种标签包裹所有内容"))
        assertTrue(p.contains("11. 每次回复第一个内容块永远不是 [对话]"))
        // iOS `\` continuation: rule 8 is immediately followed by "9." with no line break.
        assertTrue(p.contains("${OfflineNarrativePreset.PLAIN.rule8}9. 每次回复 4-6 个内容块"))
        // extraStyleRules follows rule 16 and numbers as 17.
        assertTrue(p.contains("17. 写作风格：用最日常的语气写"))
    }

    // ── 2026-08-31 人设优先、机器退位（微图纸 §4-A/§6）──

    @Test fun build_prompt_rule16_is_persona_first_no_forced_hooks() {
        // 三档统一：新规则 16 逐字（从微图纸规格反推，非照抄实现）。
        val expected = "16. 节奏由角色人设和当下情境决定：有话直说的角色可以把话说完，慢热的角色可以欲言又止；" +
            "不需要刻意制造悬念或在每轮留下钩子，允许什么都没发生、纯粹放松的相处。"
        for (preset in listOf(OfflineNarrativePreset.PLAIN, OfflineNarrativePreset.NORMAL, OfflineNarrativePreset.DETAILED)) {
            val p = OfflineNarrativePreset.buildPrompt(
                currentTimeText = "x",
                userName = "小美", meetingLocation = null, meetingActivity = null,
                tensionSeed = null, perTurnDirective = null,
                preset = preset,
            )
            assertTrue(p.contains(expected))
            // 旧规则 16「强制欲言又止」与旧规则 17「微小的可是/意外」独有句不再出现（全库独有句已 grep 核）。
            assertFalse(p.contains("必须留下至少一个"))
            assertFalse(p.contains("每轮都强行制造波折"))
            assertFalse(p.contains("生活的毛边感"))
        }
    }

    @Test fun build_prompt_normal_style_rule_renumbered_to_17() {
        // §4-E：规则 17 撤除后 NORMAL 风格规则编号 18→17（正文逐字不动），紧跟规则 16 无断号（R1 🔵-2 补锁）。
        val p = OfflineNarrativePreset.buildPrompt(
            currentTimeText = "x",
            userName = "小美", meetingLocation = null, meetingActivity = null,
            tensionSeed = null, perTurnDirective = null,
            preset = OfflineNarrativePreset.NORMAL,
        )
        assertTrue(p.contains("\n17. 写作风格：像朋友在讲今天发生了什么"))
        assertFalse(p.contains("\n18. "))
    }

    @Test fun builtin_pools_persona_directives_retired() {
        // NORMAL：行为类导演指令池整池退役；情绪底色池本就为空。
        assertTrue(OfflineNarrativePreset.NORMAL.narrativeTechniquePool.isEmpty())
        assertTrue(OfflineNarrativePreset.NORMAL.emotionalRegisterPool.isEmpty())
        // DETAILED：情绪底色池退役；技法池砍 4 留 6（只剩纯写作技法）。
        assertTrue(OfflineNarrativePreset.DETAILED.emotionalRegisterPool.isEmpty())
        val techniques = OfflineNarrativePreset.DETAILED.narrativeTechniquePool
        assertEquals(6, techniques.size)
        for (kept in listOf("焦距切换", "感官替换", "节奏变速", "细节锚点", "五感递进", "动作隐喻")) {
            assertTrue(techniques.any { it.startsWith(kept) })
        }
        for (removed in listOf("留白叙事", "对话潜台词", "反转收束", "沉默叙事", "底色")) {
            assertFalse(techniques.any { it.startsWith(removed) })
        }
    }

    @Test fun build_prompt_always_carries_real_time_line() {
        val p = prompt(seed = null, directive = null)
        assertTrue(p.contains("当前真实时间：2026-06-03 15:30"))
        assertTrue(p.contains("14. 结合当前的真实时间来推进线下场景"))
    }

    @Test fun build_prompt_appends_seed_then_directive_in_order() {
        val p = prompt(seed = "她今天有心事", directive = "【本轮叙事指令】\n· 补一个情绪")
        // 种子约束 = 三档统一常量（微图纸 §4-B 逐字；「前 3 轮不说破」定节奏版已废弃）。
        assertTrue(
            p.contains(
                "【今日场景种子】\n她今天有心事\n这件事不需要立刻摊开说——如果对话自然聊到了就可以提起，没聊到也不用硬塞。让它像真实的心事一样，在合适的时候自然浮出来。",
            ),
        )
        // 叙事指令块现在是最后一块（节拍状态块已随 G 件退役·图纸 2026-09-06 见面窗口与节拍卡七件）。
        assertTrue(p.endsWith("【本轮叙事指令】\n· 补一个情绪"))
        assertTrue(p.indexOf("【今日场景种子】") < p.indexOf("【本轮叙事指令】"))
    }

    /** G 件（图纸 2026-09-06 七件 §4.2/§9）：线下提示词任何位置都不再出现节拍字样。 */
    @Test fun build_prompt_has_no_scene_progress_anywhere() {
        for (preset in listOf(OfflineNarrativePreset.PLAIN, OfflineNarrativePreset.NORMAL, OfflineNarrativePreset.DETAILED)) {
            val p = OfflineNarrativePreset.buildPrompt(
                currentTimeText = "x",
                userName = "小美", meetingLocation = "公园", meetingActivity = "散步",
                tensionSeed = "有心事", perTurnDirective = "【本轮叙事指令】\n· 补一个情绪",
                preset = preset,
            )
            assertFalse(p.contains("【节拍状态】"))
            assertFalse(p.contains("allow_end"))
        }
    }

    /** 规则 15 新文本逐字（§4.2·重新打字）：告别判据改为「场景自然推进 + 至少 3 轮」。 */
    @Test fun build_prompt_rule15_end_condition_is_scene_driven() {
        val expected = "15. 当见面场景走到告别时，先完整输出告别段落（至少 1 个 [场景] 或 [环境] + 1 个 [动作] + 1 个 [对话]），" +
            "然后调用 end_offline_meeting 工具。是否该告别由场景自然推进决定：见面至少进行了 3 轮、并且已经说到告别、" +
            "天晚了或准备离开时才收束。如果你的模型不支持工具调用，请在回复末尾附上 [offline_end] 标记"
        assertTrue(prompt(seed = null, directive = null).contains(expected))
    }

    @Test fun build_prompt_omits_optional_blocks_when_absent() {
        val p = prompt(seed = null, directive = null)
        assertFalse(p.contains("【今日场景种子】"))
        assertFalse(p.contains("【本轮叙事指令】"))
    }

    // ── B5 人设一致性 + 线上线下连续性（§3.7 逐字·所有档位一致） ──

    @Test fun build_prompt_inserts_consistency_blocks_verbatim_before_seed() {
        val p = prompt(seed = "她今天有心事", directive = null)
        // 两块逐字（块首各带一个空行·§3.7/§9 禁改）。
        assertTrue(
            p.contains(
                "\n\n【人设一致性】\n[情绪][内心][动作] 必须贴合角色人设与你们当前的关系阶段：外向自来熟的角色不会无端紧张，内向慢热的角色不会突然过分热络；「紧张」「害羞」这类情绪只有在人设或当下情境真正支撑时才出现，不要默认套用。\n\n【线上线下连续性】\n这次见面是你们平时线上聊天的自然延续。你记得系统提示里的共同记忆与过往见面回忆，对话中可以自然地提起（比如「上次你说…」「上回来这儿…」），但不要生硬堆砌回忆，更不要表现得像第一次认识。",
            ),
        )
        // 顺序：一致性块在 base 规则之后、seedBlock 之前。
        assertTrue(p.indexOf("11. 每次回复第一个内容块永远不是 [对话]") < p.indexOf("【人设一致性】"))
        assertTrue(p.indexOf("【人设一致性】") < p.indexOf("【今日场景种子】"))
    }

    @Test fun build_prompt_consistency_blocks_present_all_tiers_even_without_optional() {
        // 所有档位一致（DETAILED 档也含）+ 无可选块时仍注入。
        val p = OfflineNarrativePreset.buildPrompt(
            currentTimeText = "x",
            userName = "小美", meetingLocation = null, meetingActivity = null,
            tensionSeed = null, perTurnDirective = null,
            preset = OfflineNarrativePreset.DETAILED,
        )
        assertTrue(p.contains("【人设一致性】"))
        assertTrue(p.contains("【线上线下连续性】"))
    }

    // ── 场景感小批（2026-09-06 图纸 §7 T1-1）：首句钉见面地点 / 位置天气块整块退役 ──

    /** M6 三形态 = 说明书第三行（第一行模式标记、第二行真实时间）。 */
    private fun thirdLine(p: String): String = p.split("\n")[2]

    private fun promptAt(location: String?, activity: String?, seed: String? = null) =
        OfflineNarrativePreset.buildPrompt(
            currentTimeText = "2026-06-03 15:30",
            userName = "小美", meetingLocation = location, meetingActivity = activity,
            tensionSeed = seed, perTurnDirective = null,
            preset = OfflineNarrativePreset.PLAIN,
        )

    @Test fun 地点分句_有地点有活动() {
        assertEquals(
            "，这次是在公园，散步；中途换了地方，以对话里最近一个 [场景] 标签为准",
            OfflineNarrativePreset.meetingPlaceClause("公园", "散步"),
        )
    }

    @Test fun 地点分句_有地点无活动() {
        assertEquals(
            "，这次是在公园；中途换了地方，以对话里最近一个 [场景] 标签为准",
            OfflineNarrativePreset.meetingPlaceClause("公园", ""),
        )
        // 活动 null 与空串同义。
        assertEquals(
            OfflineNarrativePreset.meetingPlaceClause("公园", ""),
            OfflineNarrativePreset.meetingPlaceClause("公园", null),
        )
    }

    @Test fun 地点分句_地点空或全空格一律空串() {
        assertEquals("", OfflineNarrativePreset.meetingPlaceClause("", "散步"))
        assertEquals("", OfflineNarrativePreset.meetingPlaceClause("  ", null))
        assertEquals("", OfflineNarrativePreset.meetingPlaceClause(null, null))
    }

    @Test fun 首句_有地点有活动为M6第一形态() {
        assertEquals(
            "你现在和小美面对面在一起，这次是在公园，散步；中途换了地方，以对话里最近一个 [场景] 标签为准。请用沉浸式叙事风格输出内容。",
            thirdLine(promptAt("公园", "散步")),
        )
    }

    @Test fun 首句_有地点无活动为M6第二形态() {
        assertEquals(
            "你现在和小美面对面在一起，这次是在公园；中途换了地方，以对话里最近一个 [场景] 标签为准。请用沉浸式叙事风格输出内容。",
            thirdLine(promptAt("公园", "")),
        )
    }

    @Test fun 首句_无地点为M6第三形态且位置天气块整块消失() {
        val p = promptAt(null, null)
        assertEquals("你现在和小美面对面在一起。请用沉浸式叙事风格输出内容。", thirdLine(p))
        assertFalse(p.contains("这次是在"))
        assertFalse(p.contains("[场景] 标签为准"))
        // V3：【双方位置和天气】块（含「自然决定见面地点」「你住在」）永久消失。
        assertFalse(p.contains("【双方位置和天气】"))
        assertFalse(p.contains("自然决定见面地点"))
        assertFalse(p.contains("你住在"))
        assertFalse(p.contains("用户住在"))
        // 首句直呼真名，不写「用户」二字。
        assertFalse(p.contains("你现在和用户面对面"))
    }

    @Test fun rule13不再点名已删的位置天气块() {
        // V4：NORMAL / DETAILED 改引「系统若给了真实天气」；PLAIN 本就不引用，逐字不变。
        for (rule13 in listOf(OfflineNarrativePreset.NORMAL.rule13, OfflineNarrativePreset.DETAILED.rule13)) {
            assertTrue(rule13.contains("系统若给了真实天气"))
            assertFalse(rule13.contains("【双方位置和天气】"))
        }
        assertEquals("13. [环境] 简单写一下周围的情况就行", OfflineNarrativePreset.PLAIN.rule13)
    }

    @Test fun 地点与心事种子可并存() {
        val p = promptAt("公园", "散步", seed = "她今天有心事")
        assertTrue(thirdLine(p).contains("这次是在公园，散步"))
        assertTrue(p.contains("【今日场景种子】\n她今天有心事"))
    }
}
