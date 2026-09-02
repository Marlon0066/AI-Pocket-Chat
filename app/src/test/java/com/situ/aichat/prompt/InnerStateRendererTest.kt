package com.situ.aichat.prompt

import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 活人感内核·卷三《场内核与渲染收编》T1-5（图纸 §7.2 · E12 / E13 / E31）：内心行的候选、优先级、预算与算子求值。
 * 内心行换气（微图纸 2026-09-02）后：慢场句要过「跨档 3 天内」资格门（`slowBandsAt`），既有慢场句用例一律给 [slowFresh]；
 * 既有 `now` 在任何时区都落变体 0（epochDay 19675 / 19676 ⇒ 6558 % 3 = 0），旧期望逐字不动。
 *
 * 断言从图纸 §4.1 的装配规则与物料**独立反推**（句子在此重新打字为字面量·PITFALLS §1e 双保险）：
 * - 无内容 ⇒ `""`（不输出空前缀）；矛盾恒第一；算子句 = `条件短语，你动作短语。`；场句按偏离分取最大者
 * - 单句 > 74 跳过（超长用户名·E31）；含前缀总长 > 80 时跳过放不下的那句、继续试下一候选（E13）
 * - 命中 24h 过期 ⇒ c07 失效；c10 深夜 + 激活 25 触发 / 26 不触发 / 白天不触发；c01–c06 恒 false
 */
class InnerStateRendererTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L
    private val calm = RelationshipPressure.fromQuality(RelationshipQuality())

    /** 慢场两格都在 1h 前跨过档 ⇒ 安全感 / 投入度句有资格（内心行换气资格门）。 */
    private val slowFresh = listOf(now - 3_600_000L, now - 3_600_000L)

    /** 依恋 正80/负75 ⇒ 卷二矛盾判定命中依恋维。 */
    private val attachmentTorn = calm.copy(
        pos = calm.pos.toMutableList().also { it[7] = 80 },
        neg = calm.neg.toMutableList().also { it[7] = 75 },
    )

    private fun render(
        field: AffectField = AffectField(),
        pressure: RelationshipPressure = calm,
        operators: List<PersonaOperator> = emptyList(),
        userName: String = "小明",
        hour: Int = 15,
        intents: List<CharacterIntent> = emptyList(),
    ) = InnerStateRenderer.render(field, pressure, operators, userName, hour, now, intents)

    private fun op(condition: String, action: String, enabled: Boolean = true) =
        PersonaOperator(id = condition + action, condition = condition, action = action, enabled = enabled)

    // MARK: - E12 无内容

    @Test
    fun defaultField_noContradiction_noOperator_rendersNothing() {
        assertEquals("", render())
    }

    // MARK: - 优先级与装配

    @Test
    fun contradictionDim_noShortSentence_onlyFieldSentence_J12() {
        // 修缮卷 J12：矛盾短句从内心行去掉（Growth 段仍出同维长句）；pressure 形参保留但不再读
        val out = render(field = AffectField(valence = -70), pressure = attachmentTorn)
        assertEquals("此刻你心里：心里堵着一股闷气，没什么耐心。", out)
        assertEquals("只有矛盾 ⇒ 整行不出", "", render(pressure = attachmentTorn))
    }

    @Test
    fun operatorSentence_isConditionCommaYouActionPeriod() {
        val field = AffectField(hits = listOf("g04"), hitsAt = now - 3_600_000L)
        val out = render(field = field, operators = listOf(op("c07", "a05")))
        assertEquals("此刻你心里：刚被小明夸了，你嘴上会否认，行动上在意。", out)
    }

    @Test
    fun threeCandidates_allFit_inPriorityOrder() {
        // 修缮卷序：意图 › 算子 › 场（矛盾不再占位）
        val field = AffectField(valence = -70, hits = listOf("g04"), hitsAt = now)
        val out = render(field = field, pressure = attachmentTorn, operators = listOf(op("c07", "a05")), intents = listOf(intent(IntentKind.WANT_HIDE)))
        assertEquals(
            "此刻你心里：你现在只想躲一躲，不太想跟小明多说。刚被小明夸了，你嘴上会否认，行动上在意。心里堵着一股闷气，没什么耐心。",
            out,
        )
        assertTrue(out.length <= InnerStateRenderer.MAX_TOTAL_CHARS)
    }

    @Test
    fun firstEnabledOperatorWhoseConditionHolds_wins() {
        val field = AffectField(valence = -50, hits = listOf("g05"), hitsAt = now)
        val operators = listOf(op("c07", "a01"), op("c08", "a02", enabled = false), op("c11", "a06"), op("c08", "a03"))
        // c07 不成立（无 g04）、c08 第一条被禁用、c11 成立（效价 −50 ≤ −40）⇒ 取 c11→a06；同时场句「闷气」
        assertEquals("此刻你心里：现在情绪很差，你会先冷一下再回。心里堵着一股闷气，没什么耐心。", render(field = field, operators = operators))
    }

    // MARK: - 场状态句的选择

    @Test
    fun fieldSentence_picksMaxDeviation_valenceOverSecurity_arousalOverSecurity() {
        assertEquals("此刻你心里：心里堵着一股闷气，没什么耐心。", render(field = AffectField(valence = -70)))
        assertEquals("此刻你心里：心里有点闷。", render(field = AffectField(valence = -30)))
        assertEquals("此刻你心里：心情不错。", render(field = AffectField(valence = 30)))
        assertEquals("此刻你心里：心里亮堂，看什么都顺眼。", render(field = AffectField(valence = 50)))
        // a=5（分 0.5）优先于 s=20（分 0.33）
        assertEquals("此刻你心里：整个人提不起劲，只想窝着。", render(field = AffectField(arousal = 5, security = 20, slowBandsAt = slowFresh)))
        assertEquals("此刻你心里：劲头有点收不住，话比平时多。", render(field = AffectField(arousal = 80)))
        assertEquals("此刻你心里：对这段关系没什么底，容易多想。", render(field = AffectField(security = 10, slowBandsAt = slowFresh)))
        assertEquals("此刻你心里：在小明面前很踏实。", render(field = AffectField(security = 90, slowBandsAt = slowFresh)))
        assertEquals("此刻你心里：把小明的事看得很重。", render(field = AffectField(investment = 90, slowBandsAt = slowFresh)))
        assertEquals("此刻你心里：对这段关系没太上心。", render(field = AffectField(investment = 5, slowBandsAt = slowFresh)))
        // 阈值边缘：|v| = 19 不算偏离；a = 11 / 74、s = 31 / 79、i = 79 / 11 都不算（慢场给资格仍不算 ⇒ 是阈值在挡、不是资格门）
        assertEquals("", render(field = AffectField(valence = 19, arousal = 74, security = 79, investment = 79, slowBandsAt = slowFresh)))
        assertEquals("", render(field = AffectField(valence = -19, arousal = 11, security = 31, investment = 11, slowBandsAt = slowFresh)))
    }

    @Test
    fun tie_breaksByValenceFirst() {
        // v=−20（0.2）与 s=24（0.2）同分 ⇒ v 胜
        assertEquals("此刻你心里：心里有点闷。", render(field = AffectField(valence = -20, security = 24, slowBandsAt = slowFresh)))
    }

    // MARK: - E13 / E31 长度预算

    @Test
    fun overlongUserName_skipsThatSentence_keepsOthers() {
        // 想道歉句 = 「你想跟」3 + 名 + 「道个歉，话到嘴边又咽了回去。」14 ⇒ 名 ≥ 58 字才越 74；用 70 字名把它顶出去，场句照常
        val longName = "张".repeat(70)
        val out = render(field = AffectField(valence = -70), userName = longName, intents = listOf(intent(IntentKind.WANT_APOLOGIZE)))
        assertEquals("此刻你心里：心里堵着一股闷气，没什么耐心。", out)
        // 对照：57 字名（句长 74，恰不越）仍被选入
        val edgeName = "张".repeat(57)
        assertTrue(render(userName = edgeName, intents = listOf(intent(IntentKind.WANT_APOLOGIZE))).startsWith("此刻你心里：你想跟${edgeName}道个歉"))
    }

    @Test
    fun totalOverEighty_skipsTheOneThatDoesNotFit_thenTriesNext() {
        // 24 字名：想道歉句 41 字（6+41=47 装得下）、算子句 42 字（47+42=89 > 80 跳过）、场句 15 字（47+15=62 装得下）
        val name = "李".repeat(24)
        val field = AffectField(valence = -70, hits = listOf("g04"), hitsAt = now)
        val out = render(field = field, operators = listOf(op("c07", "a05")), userName = name, intents = listOf(intent(IntentKind.WANT_APOLOGIZE)))
        assertEquals("此刻你心里：你想跟${name}道个歉，话到嘴边又咽了回去。心里堵着一股闷气，没什么耐心。", out)
        assertTrue(out.length <= 80)
        assertFalse(out.contains("夸了"))
    }

    // MARK: - 算子条件求值

    @Test
    fun hitConditions_expireAfter24Hours() {
        val fresh = AffectField(hits = listOf("g04"), hitsAt = now - 24L * 3_600_000L)
        val stale = AffectField(hits = listOf("g04"), hitsAt = now - 24L * 3_600_000L - 1)
        assertTrue(InnerStateRenderer.isConditionActive("c07", fresh, 15, now))
        assertFalse(InnerStateRenderer.isConditionActive("c07", stale, 15, now))
        assertEquals("", render(field = stale, operators = listOf(op("c07", "a05"))))
        assertFalse("从未命中（hitsAt=0）不成立", InnerStateRenderer.isConditionActive("c07", AffectField(hits = listOf("g04")), 15, now))
    }

    @Test
    fun hitConditions_c08_c09_c12_lookAtTheirOwnKeys() {
        val f = AffectField(hits = listOf("g05", "g02", AffectField.BAND_UP), hitsAt = now)
        assertTrue(InnerStateRenderer.isConditionActive("c08", f, 15, now))
        assertTrue(InnerStateRenderer.isConditionActive("c09", f, 15, now))
        assertTrue(InnerStateRenderer.isConditionActive("c12", f, 15, now))
        assertFalse(InnerStateRenderer.isConditionActive("c07", f, 15, now))
        assertEquals("此刻你心里：你们的关系刚往前走了一步，你更想找人说话。", render(field = f, operators = listOf(op("c12", "a04"))))
    }

    @Test
    fun c10_lateNightAndLowArousal() {
        assertTrue(InnerStateRenderer.isConditionActive("c10", AffectField(arousal = 25), 2, now))
        assertTrue(InnerStateRenderer.isConditionActive("c10", AffectField(arousal = 25), 23, now))
        assertFalse(InnerStateRenderer.isConditionActive("c10", AffectField(arousal = 26), 2, now))
        assertFalse(InnerStateRenderer.isConditionActive("c10", AffectField(arousal = 25), 12, now))
        assertFalse(InnerStateRenderer.isConditionActive("c10", AffectField(arousal = 25), 6, now))
        assertEquals("此刻你心里：这会儿夜深了，就你一个人，你话会变少。", render(field = AffectField(arousal = 25), operators = listOf(op("c10", "a03")), hour = 2))
    }

    @Test
    fun c11_valenceAtOrBelowMinusForty() {
        assertTrue(InnerStateRenderer.isConditionActive("c11", AffectField(valence = -40), 15, now))
        assertFalse(InnerStateRenderer.isConditionActive("c11", AffectField(valence = -39), 15, now))
    }

    @Test
    fun intentConditions_c01ToC06_neverActive() {
        val f = AffectField(valence = -100, arousal = 0, hits = listOf("g04", "g05", "g02", AffectField.BAND_UP), hitsAt = now)
        for (c in listOf("c01", "c02", "c03", "c04", "c05", "c06", "c99", "")) {
            assertFalse(c, InnerStateRenderer.isConditionActive(c, f, 2, now))
        }
    }

    // MARK: - 卷四 T1-6（图纸 §4.4 · E43 / E44）：意图第 2 位 + 算子 c01–c06 接意图队列

    private fun intent(kind: IntentKind, state: IntentState = IntentState.ACTIVE, strength: Int = 50, residue: Boolean = false) =
        CharacterIntent(id = kind.key, kind = kind, state = state, strength = strength, bornAt = now - 3_600_000L, lastChangeAt = now - 3_600_000L, residue = residue)

    /** 信任 正80/负75 ⇒ 卷二矛盾判定命中信任维（矛盾短句 17 字）。 */
    private val trustTorn = calm.copy(
        pos = calm.pos.toMutableList().also { it[1] = 80 },
        neg = calm.neg.toMutableList().also { it[1] = 75 },
    )

    @Test
    fun sameSourceOperator_isSkipped_E20() {
        // 修缮卷 E20：选中的意图是想道歉 ⇒ c01（道歉）算子与意图句同源，跳过；矛盾（信任）也不再出短句 ⇒ 只剩意图句
        val out = render(pressure = trustTorn, operators = listOf(op("c01", "a01")), intents = listOf(intent(IntentKind.WANT_APOLOGIZE)))
        assertEquals("此刻你心里：你想跟小明道个歉，话到嘴边又咽了回去。", out)
    }

    @Test
    fun differentSourceOperator_stillRenders_whenStrongerIntentIsChosen_E20() {
        // 想被哄 60 > 想道歉 50 ⇒ 意图句取想被哄；c01（道歉）与选中意图不同源 ⇒ 算子句照出
        val out = render(
            operators = listOf(op("c01", "a01")),
            intents = listOf(intent(IntentKind.WANT_APOLOGIZE, strength = 50), intent(IntentKind.WANT_COMFORT, strength = 60)),
        )
        assertEquals("此刻你心里：你有点想让小明哄哄你，又拉不下脸开口。想跟小明道歉的时候，你不会直说，会绕着表达。", out)
    }

    @Test
    fun strongestLiveKind_agreesWithChatCandidate() {
        // R1 D-2 裁决后同源单点：三组队列逐一钉「选中 kind 的活跃句 == chatCandidate」（防两处再次分叉）
        val sets = listOf(
            listOf(intent(IntentKind.WANT_APOLOGIZE, strength = 50), intent(IntentKind.WANT_COMFORT, strength = 60)),
            listOf(intent(IntentKind.WANT_PROBE, strength = 50), intent(IntentKind.WANT_COMFORT, strength = 50)),   // 同分取声明序：被哄 < 试探
            listOf(intent(IntentKind.WANT_SHARE, strength = 40), intent(IntentKind.WANT_HIDE, state = IntentState.RESOLVED, strength = 0)),
        )
        for (intents in sets) {
            val kind = IntentExitRenderer.strongestLiveKind(intents, now)!!
            assertEquals(IntentScripts.active(kind, "小明"), IntentExitRenderer.chatCandidate(intents, "小明", now))
        }
        assertEquals("残留 / 无 live ⇒ null", null, IntentExitRenderer.strongestLiveKind(listOf(intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, residue = true)), now))
    }

    @Test
    fun c02_sameSourceAsLiveComfort_isSkipped_c11StillRenders_E20() {
        // c02 与选中的想被哄同源 ⇒ 跳过；下一条成立的 c11（效价 −50 ≤ −40）照出；场句随后
        val out = render(field = AffectField(valence = -50), operators = listOf(op("c02", "a10"), op("c11", "a06")), intents = listOf(intent(IntentKind.WANT_COMFORT)))
        assertEquals("此刻你心里：你有点想让小明哄哄你，又拉不下脸开口。现在情绪很差，你会先冷一下再回。心里堵着一股闷气，没什么耐心。", out)
        assertFalse(out.contains("说反话"))
        // 只有 c02 ⇒ 无算子句
        assertEquals("此刻你心里：你有点想让小明哄哄你，又拉不下脸开口。", render(operators = listOf(op("c02", "a10")), intents = listOf(intent(IntentKind.WANT_COMFORT))))
    }

    @Test
    fun intentConditions_c01ToC06_mapToKinds_K4F3_andOnlyLiveCounts() {
        val f = AffectField()
        val pairs = listOf(
            "c01" to IntentKind.WANT_APOLOGIZE, "c02" to IntentKind.WANT_COMFORT, "c03" to IntentKind.WANT_PROBE,
            "c04" to IntentKind.WANT_HIDE, "c05" to IntentKind.WANT_SHARE, "c06" to IntentKind.WANT_CONFIRM,
        )
        for ((c, kind) in pairs) {
            assertTrue(c, InnerStateRenderer.isConditionActive(c, f, 15, now, listOf(intent(kind))))
            val others = IntentKind.entries.filter { it != kind }.map { intent(it) }
            assertFalse("$c 只认 $kind", InnerStateRenderer.isConditionActive(c, f, 15, now, others))
        }
        // 非 live（RESOLVED / 强度 <15）不算；空队列恒 false（既有例已钉）
        assertFalse(InnerStateRenderer.isConditionActive("c01", f, 15, now, listOf(intent(IntentKind.WANT_APOLOGIZE, state = IntentState.RESOLVED, strength = 0))))
        assertFalse(InnerStateRenderer.isConditionActive("c01", f, 15, now, listOf(intent(IntentKind.WANT_APOLOGIZE, strength = 10))))
        assertFalse(InnerStateRenderer.isConditionActive("c01", f, 15, now, emptyList()))
    }

    @Test
    fun intentSentence_expressedVariant_andResidueOnlyWhenNoLive() {
        assertEquals(
            "此刻你心里：你已经道过歉了，还在琢磨小明是不是真的不介意。",
            render(intents = listOf(intent(IntentKind.WANT_APOLOGIZE, state = IntentState.EXPRESSED, strength = 25))),
        )
        assertEquals(
            "此刻你心里：之前那件事其实没过去，你只是没再提。",
            render(intents = listOf(intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, residue = true))),
        )
    }

    @Test
    fun overlongUserName_skipsIntentSentence_butNamelessResidueStillFits_E43() {
        val longName = "张".repeat(60)
        // 想道歉句 = 17 字 + 60 字名 = 77 > 74 ⇒ 跳过，场句照进
        val out = render(field = AffectField(valence = -70), userName = longName, intents = listOf(intent(IntentKind.WANT_APOLOGIZE)))
        assertEquals("此刻你心里：心里堵着一股闷气，没什么耐心。", out)
        // 残留句不含名字 ⇒ 60 字名也照进
        val residue = render(userName = longName, intents = listOf(intent(IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, residue = true)))
        assertEquals("此刻你心里：之前那件事其实没过去，你只是没再提。", residue)
    }

    @Test
    fun totalOverEighty_skipsOperator_fieldSentenceStillFits_E44() {
        // 40 字名：意图 57（6+57=63 进）、算子 c07 58（63+58=121 > 80 跳）、场句 15（78 进）
        val name = "李".repeat(40)
        val out = render(
            field = AffectField(valence = -70, hits = listOf("g04"), hitsAt = now), operators = listOf(op("c07", "a05")),
            userName = name, intents = listOf(intent(IntentKind.WANT_APOLOGIZE)),
        )
        assertEquals("此刻你心里：你想跟${name}道个歉，话到嘴边又咽了回去。心里堵着一股闷气，没什么耐心。", out)
        assertTrue(out.length <= 80)
        assertFalse(out.contains("夸了"))
    }

    // MARK: - 内心行换气（微图纸 2026-09-02 §5）：慢场句资格门 + 台词按本地日轮换 + 30 句场句变体逐字

    @Test
    fun slowSentence_onlyWithinThreeDaysOfCrossing_thenValenceTakesOver() {
        // 安全感 100（偏离分 1.0）但跨档已 4 天 ⇒ 慢场句失去资格 ⇒ 场句取效价 25（0.25）⇒ 变体 0「心情不错。」
        val stale = AffectField(security = 100, valence = 25, slowBands = listOf(2, 1), slowBandsAt = listOf(now - 4 * day, 0L))
        assertEquals("此刻你心里：心情不错。", render(field = stale))
        // 跨档 2 天 ⇒ 仍在 3 天内 ⇒ 安全感句胜（1.0 > 0.25）
        assertEquals("此刻你心里：在小明面前很踏实。", render(field = stale.copy(slowBandsAt = listOf(now - 2 * day, 0L))))
        // 恰 72h 仍算（≤）；72h + 1ms 不算
        assertEquals("此刻你心里：在小明面前很踏实。", render(field = stale.copy(slowBandsAt = listOf(now - 3 * day, 0L))))
        assertEquals("此刻你心里：心情不错。", render(field = stale.copy(slowBandsAt = listOf(now - 3 * day - 1, 0L))))
        // 快场句资格不受门影响：效价 0 时资格过期的安全感 100 ⇒ 整行不出（不会退回旧口径）
        assertEquals("", render(field = stale.copy(valence = 0)))
    }

    @Test
    fun legacyColumn_slowBandsAtZero_neverRendersSlowSentence() {
        // 老列 slowBandsAt = [0,0]：安全感 / 投入度再极端也不出慢场句；效价 / 激活默认 ⇒ 整行不出
        assertEquals("", render(field = AffectField(security = 100, investment = 100)))
        assertEquals("", render(field = AffectField(security = 0, investment = 0)))
        // 只有投入度那格有时刻 ⇒ 只投入度句有资格
        assertEquals("此刻你心里：把小明的事看得很重。", render(field = AffectField(security = 100, investment = 100, slowBandsAt = listOf(0L, now - day))))
        // R1 A-3：未来时刻（时钟回拨 / 坏列）不算「刚跨档」⇒ 不出慢场句（否则会一直挂到时钟追上，「只出 3 天」失守）
        assertEquals("", render(field = AffectField(security = 100, slowBandsAt = listOf(now + day, 0L))))
        // 跨档时刻恰为 now ⇒ 算
        assertEquals("此刻你心里：在小明面前很踏实。", render(field = AffectField(security = 100, slowBandsAt = listOf(now, 0L))))
    }

    @Test
    fun scriptsRotate_byLocalDate_threeVariantsAcrossThreeWindows_operatorNeverRotates() {
        // 同一场三种日期渲染出三个变体：UTC + 三个正午钉 (epochDay/3)%3 = 0 / 1 / 2（20000 / 20003 / 20006 手算）
        val utc = ZoneId.of("UTC")
        fun nowAt(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(utc).toInstant().toEpochMilli()
        val field = AffectField(valence = 30)
        val expected = listOf(
            20_000L to "此刻你心里：你想跟小明道个歉，话到嘴边又咽了回去。心情不错。",
            20_003L to "此刻你心里：你知道该跟小明说声对不起，一直没找到开口的时机。今天心情挺好。",
            20_006L to "此刻你心里：有句道歉你欠着小明，翻来覆去没说出口。这会儿心里挺舒坦。",
        )
        for ((epochDay, want) in expected) {
            val t = nowAt(epochDay)
            val apology = CharacterIntent(id = "a", kind = IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 50, bornAt = t - 3_600_000L, lastChangeAt = t - 3_600_000L)
            assertEquals("epochDay=$epochDay", want, InnerStateRenderer.render(field, calm, emptyList(), "小明", 15, t, listOf(apology), utc))
        }
        // 残留句同样轮换（变体 2）
        val t = nowAt(20_006L)
        val residue = CharacterIntent(id = "r", kind = IntentKind.WANT_HIDE, state = IntentState.FADED, strength = 8, bornAt = t - 3 * day, lastChangeAt = t - day, residue = true)
        assertEquals("此刻你心里：你嘴上不提了，心里那道坎还在。这会儿心里挺舒坦。", InnerStateRenderer.render(field, calm, emptyList(), "小明", 15, t, listOf(residue), utc))
        // 算子句不轮换（条件短语 / 动作短语无变体）
        val opField = AffectField(hits = listOf("g04"), hitsAt = t)
        assertEquals("此刻你心里：刚被小明夸了，你嘴上会否认，行动上在意。", InnerStateRenderer.render(opField, calm, listOf(op("c07", "a05")), "小明", 15, t, emptyList(), utc))
        // 同一 epoch 毫秒换时区可能换日 ⇒ 变体跟 zone 走（UTC 第三日 20:00 = 东八区第四日 04:00）
        val edge = LocalDate.ofEpochDay(20_003L).atTime(20, 0).atZone(utc).toInstant().toEpochMilli()
        assertEquals("此刻你心里：今天心情挺好。", InnerStateRenderer.render(field, calm, emptyList(), "小明", 20, edge, emptyList(), utc))
        assertEquals("此刻你心里：这会儿心里挺舒坦。", InnerStateRenderer.render(field, calm, emptyList(), "小明", 4, edge, emptyList(), ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun fieldSentenceVariants_thirtyVerbatim_outOfRangeFallsBackToOriginal() {
        fun fs(field: AffectField, variant: Int) = InnerStateRenderer.fieldSentence(field, "小明", now, variant)
        val table = listOf(
            AffectField(valence = 50) to listOf("心里亮堂，看什么都顺眼。", "今天心情很好，说话都带笑。", "这会儿心里敞亮，什么都好说。"),
            AffectField(valence = 30) to listOf("心情不错。", "今天心情挺好。", "这会儿心里挺舒坦。"),
            AffectField(valence = -30) to listOf("心里有点闷。", "这会儿有点提不起兴致。", "心里有点堵，说不上为什么。"),
            AffectField(valence = -70) to listOf("心里堵着一股闷气，没什么耐心。", "这会儿心情很差，容易不耐烦。", "心里憋着火，一句话不对就想呛人。"),
            AffectField(arousal = 5) to listOf("整个人提不起劲，只想窝着。", "累了，回话都懒得多打几个字。", "这会儿没什么精神，能躺着就不坐着。"),
            AffectField(arousal = 80) to listOf("劲头有点收不住，话比平时多。", "这会儿兴奋得很，话一句接一句。", "今天精神头足，容易说多。"),
            AffectField(security = 10, slowBandsAt = slowFresh) to listOf("对这段关系没什么底，容易多想。", "这段关系你心里没底，一点风吹草动就多想。", "你不太确定小明的心思，容易往坏处想。"),
            AffectField(investment = 5, slowBandsAt = slowFresh) to listOf("对这段关系没太上心。", "你对这段关系没投入太多心思。", "跟小明的事，你没怎么放在心上。"),
            AffectField(security = 90, slowBandsAt = slowFresh) to listOf("在小明面前很踏实。", "跟小明在一起你不用设防。", "在小明这儿你心里很稳。"),
            AffectField(investment = 90, slowBandsAt = slowFresh) to listOf("把小明的事看得很重。", "小明的事你会放在心上惦记。", "你在小明身上花的心思比自己都多。"),
        )
        for ((field, variants) in table) {
            for (v in 0..2) assertEquals("$field 变体 $v", variants[v], fs(field, v))
            assertEquals("越界回原文", variants[0], fs(field, 3))
            assertEquals("三变体两两不同", 3, variants.toSet().size)
        }
    }

    @Test
    fun noIntents_emptyListAndDefaultParamRenderSame_contradictionOmitted() {
        val field = AffectField(valence = -70, hits = listOf("g04"), hitsAt = now)
        val without = InnerStateRenderer.render(field, attachmentTorn, listOf(op("c07", "a05")), "小明", 15, now)
        val withEmpty = InnerStateRenderer.render(field, attachmentTorn, listOf(op("c07", "a05")), "小明", 15, now, emptyList())
        assertEquals(without, withEmpty)
        assertEquals("此刻你心里：刚被小明夸了，你嘴上会否认，行动上在意。心里堵着一股闷气，没什么耐心。", withEmpty)
    }
}
