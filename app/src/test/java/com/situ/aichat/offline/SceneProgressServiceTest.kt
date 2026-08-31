package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SceneProgressService` tests (P10.2c-2): the beat-state system prompt (structure / exact colons /
 * `\`-continuation joins), `shouldTriggerUpdate`, and `forceFieldValue`. 2026-08-31 人设优先微图纸起：
 * 卡片只做场记——「可以发生的事」「建议新张力」字段、张力自愈与心事种子锁 allow_end 已退役（见
 * prompt_scribe_only_no_plot_machinery）。
 */
class SceneProgressServiceTest {

    private fun prompt(
        chatLog: String = "[2026-06-04 18:30] 用户：你来了\n[2026-06-04 18:31] 角色：嗯",
        characterName: String = "小琳",
        userName: String = "阿哲",
        locationHint: String = "江边咖啡馆",
    ) = SceneProgressService.buildSystemPrompt(chatLog, characterName, userName, locationHint)

    // ── buildSystemPrompt: 结构 + 精确冒号 + 续行合并 ──

    @Test fun prompt_intro_is_single_continuation_joined_line() {
        val p = prompt()
        assertTrue(
            p.startsWith(
                "你在维护一段线下见面的\"节拍状态\"。读完下面的对话后，按固定格式输出 Markdown，不要解释，不要加前言，不要用代码块。格式如下（每行一个字段）：",
            ),
        )
    }

    @Test fun prompt_field_labels_use_half_width_colons() {
        val p = prompt()
        assertTrue(p.contains("\nallow_end: true|false\n"))
        assertTrue(p.contains("\n地点: <最新地点，没变就填初始地点>\n"))
        assertTrue(p.contains("\n已发生的关键节点:\n"))
        assertTrue(p.contains("\n当前情绪基调: <一句话>\n"))
        assertTrue(p.contains("\n未解决的张力: <一句话，若无则填\"无\">\n"))
    }

    @Test fun prompt_section_labels_use_full_width_colons() {
        val p = prompt()
        assertTrue(p.contains("\n规则：\n"))
        assertTrue(p.contains("\n角色名：小琳\n"))
        assertTrue(p.contains("\n用户名：阿哲\n"))
        assertTrue(p.contains("初始地点提示：江边咖啡馆"))
    }

    @Test fun prompt_rules_renumbered_1_to_3_and_rule1_continuation_joined() {
        val p = prompt()
        // rule 1: 进入 immediately followed by 可自然收束 (no line break at iOS `\`)，尾随即换行（无 seed 尾巴）。
        assertTrue(p.contains("已经进入可自然收束的阶段（说到告别、天晚了、准备离开）时才允许 true。\n2. 已发生的关键节点"))
        // rule 3 收尾后直接空行接初始地点提示（旧规则 4/5 已退役）。
        assertTrue(p.contains("3. 整段输出不超过 500 字。\n\n初始地点提示：江边咖啡馆"))
    }

    @Test fun prompt_scribe_only_no_plot_machinery() {
        // 2026-08-31 人设优先微图纸 §4-C/D：卡片只做场记——机器递剧情/张力永动/心事种子锁 三件全退役。
        val p = prompt()
        assertFalse(p.contains("可以发生的事"))
        assertFalse(p.contains("建议新张力"))
        assertFalse(p.contains("隐藏心事种子"))
        assertFalse(p.contains("隐藏心事如果还没浮出水面"))
    }

    @Test fun prompt_chatlog_appended_after_record_header() {
        val p = prompt(chatLog = "[t] 用户：测试日志")
        assertTrue(p.contains("## 线下对话记录\n[t] 用户：测试日志"))
        assertTrue(p.endsWith("[t] 用户：测试日志"))
    }

    @Test fun prompt_empty_user_name_falls_back_to_default() {
        assertTrue(prompt(userName = "").contains("\n用户名：用户\n"))
    }

    // ── shouldTriggerUpdate（≥15 user 差 + 3min 防抖） ──

    @Test fun trigger_false_when_turn_diff_below_threshold() {
        assertFalse(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 14, lastTriggerCount = 0, lastUpdateAt = null, now = 0L))
    }

    @Test fun trigger_true_at_threshold_with_no_prior_update() {
        assertTrue(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 15, lastTriggerCount = 0, lastUpdateAt = null, now = 0L))
    }

    @Test fun trigger_false_when_within_debounce_window() {
        // diff 充足但距上次更新仅 100s（<180s）→ 不触发
        assertFalse(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 30, lastTriggerCount = 0, lastUpdateAt = 100_000L, now = 200_000L))
    }

    @Test fun trigger_true_after_debounce_elapsed() {
        // 距上次更新刚好 180s → 不再防抖 → 触发
        assertTrue(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 30, lastTriggerCount = 0, lastUpdateAt = 0L, now = 180_000L))
    }

    @Test fun trigger_uses_diff_from_last_trigger_count() {
        // 15 − 5 = 10 < 15 → 不触发（差值而非绝对值，跨重启不漏）
        assertFalse(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 15, lastTriggerCount = 5, lastUpdateAt = null, now = 0L))
        assertTrue(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 20, lastTriggerCount = 5, lastUpdateAt = null, now = 0L))
    }

    // MARK: - R5#0 forceFieldValue（写改端容错·与 extractFieldValue 解析端同套·消除「再待一会儿」静默失效）

    @Test fun forceField_canonicalLine_rewrittenToFalse() {
        val state = "节拍\nallow_end: true\n未解决的张力: 无"
        val out = SceneProgressService.forceFieldValue(state, "allow_end", "false")
        assertEquals("节拍\nallow_end: false\n未解决的张力: 无", out)
    }

    @Test fun forceField_tolerates_missingSpace() {
        // LLM 漏空格 `allow_end:true` —— 旧字面 replace 改不动；新逻辑须命中并规范化为 `allow_end: false`。
        val out = SceneProgressService.forceFieldValue("allow_end:true", "allow_end", "false")
        assertEquals("allow_end: false", out)
    }

    @Test fun forceField_tolerates_fullWidthColon_andSpaces() {
        val out = SceneProgressService.forceFieldValue("allow_end ： true", "allow_end", "false")
        assertEquals("allow_end: false", out)
    }

    @Test fun forceField_tolerates_uppercaseValue() {
        // 值大小写不影响命中（按字段名定位行，整行重写）。
        val out = SceneProgressService.forceFieldValue("allow_end: True", "allow_end", "false")
        assertEquals("allow_end: false", out)
    }

    @Test fun forceField_preservesLeadingIndent() {
        val out = SceneProgressService.forceFieldValue("  allow_end: true", "allow_end", "false")
        assertEquals("  allow_end: false", out)
    }

    @Test fun forceField_absentField_returnedUnchanged() {
        // 无该字段行 → 原样返回（不无中生有）。
        val state = "节拍\n未解决的张力: 某事"
        assertEquals(state, SceneProgressService.forceFieldValue(state, "allow_end", "false"))
    }

    @Test fun forceField_onlyFirstMatchingLine_rewritten() {
        // 只重写首个命中行（节拍状态每字段单行）。
        val out = SceneProgressService.forceFieldValue("allow_end: true\nallow_end: true", "allow_end", "false")
        assertEquals("allow_end: false\nallow_end: true", out)
    }
}
