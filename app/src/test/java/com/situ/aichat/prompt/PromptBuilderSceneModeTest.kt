package com.situ.aichat.prompt

import android.content.res.Configuration
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * 两语境模型（2026-07-12 图纸 v2·§3-A1/A2/B1/B2·§7 T2-1…T2-11）装配矩阵行为测试：
 * 线下见面换核心规则专版（用户 offlineContent > 内置线下版）、短信腔四件线下退场、moduleScene 二值化
 * （语音/忙碌按在线聊天位）、effectiveScene 场景语义（脏状态回落 / 恢复链路健康线下）。
 * 断言从图纸规格独立反推：核心规则实文（「在 APP 里」/「纯文字聊天软件」/「面对面待在一起」）、模块标题
 * （【聊天格式】/【情绪表达】/【核心规则】）、末尾模式标记（【当前处于线下见面/普通聊天模式】）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderSceneModeTest {

    // 分割线内部用 ZoneId.systemDefault()——钉死 Asia/Shanghai 保证 T2-3 跨天分割线断言确定性（照 PromptBuilderTimeDividerTest）。
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())
    private fun enStrings(): PromptStrings {
        val base = RuntimeEnvironment.getApplication()
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return PromptStrings(base.createConfigurationContext(cfg))
    }

    private fun character() = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun plainHistory(): List<MessageEntity> = listOf(
        MessageEntity(messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user", content = "在干嘛", timestamp = fixedNow.toEpochMilli() - 60_000),
        MessageEntity(messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = "刚忙完~", timestamp = fixedNow.toEpochMilli() - 30_000),
    )

    private fun offlineHistory(): List<MessageEntity> =
        plainHistory().map { it.copy(isOfflineMode = true, offlineSessionId = "sess1") }

    private fun offlineConv() = ConversationEntity(
        uuid = "conv1", title = "会话", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = true, currentOfflineSessionId = "sess1",
    )

    /** 脏状态：flag=true 但 sessionId 空白（isOfflineModeHealthy=false）。 */
    private fun dirtyConv() = ConversationEntity(
        uuid = "conv1", title = "会话", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = true, currentOfflineSessionId = "   ",
    )

    private fun allText(msgs: List<ChatMessageDto>) = msgs.joinToString("\n") { it.content.orEmpty() }
    private fun firstSystem(msgs: List<ChatMessageDto>) = msgs.first { it.role == "system" }.content.orEmpty()

    /** 全 22 系统模块默认，仅 CORE_RULES 按 transform 定制。 */
    private fun jsonWithCore(transform: (PromptModule) -> PromptModule): String =
        PromptModuleService.encodeModules(
            PromptModuleService.defaultModules().map {
                if (it.systemModuleType == SystemModuleType.CORE_RULES) transform(it) else it
            },
        )

    private fun buildOnline(
        appSettings: AppSettings,
        strings: PromptStrings = strings(),
        scene: PromptScene = PromptScene.ONLINE_CHAT,
        history: List<MessageEntity> = plainHistory(),
        conv: ConversationEntity? = null,
        profile: UserProfileEntity? = null,
    ) = PromptBuilder.buildMessages(
        character = character(), conversation = conv, sortedMessages = history, userProfile = profile,
        appSettings = appSettings, strings = strings, now = fixedNow, scene = scene,
    )

    private fun buildOffline(
        appSettings: AppSettings = AppSettings(),
        strings: PromptStrings = strings(),
        scene: PromptScene = PromptScene.OFFLINE_MEETING,
        conv: ConversationEntity = offlineConv(),
        history: List<MessageEntity> = offlineHistory(),
        profile: UserProfileEntity? = null,
    ) = PromptBuilder.buildMessages(
        character = character(), conversation = conv, sortedMessages = history, userProfile = profile,
        appSettings = appSettings, strings = strings, now = fixedNow, scene = scene,
    )

    /** 入场标记消息（公园 / 散步 / 心事种子）——本场第一条。 */
    private fun startMarker(seed: String? = "她有心事", ts: Long = fixedNow.toEpochMilli() - 600_000) = MessageEntity(
        messageUUID = "mk1", conversationUuid = "conv1", roleRaw = "assistant",
        content = OfflineMarkerStartPayload("公园", "散步", "下午3:30", seed).makeContent(),
        timestamp = ts, isOfflineMode = true, offlineSessionId = "sess1",
        messageKindRaw = MessageKind.OFFLINE_MARKER_START.raw,
    )

    /**
     * 本场叙事消息（user / assistant 交替），用于把入场标记挤出短期窗口。
     * [filler] = 每条正文里多塞的字数（见面块 2026-09-06 起按 CJK 字符预算保留，条数多但字少不再触发截断）。
     */
    private fun offlineNarratives(count: Int, startTs: Long, filler: Int = 0): List<MessageEntity> = (0 until count).map { i ->
        val pad = "啊".repeat(filler)
        MessageEntity(
            messageUUID = "on$i", conversationUuid = "conv1",
            roleRaw = if (i % 2 == 0) "user" else "assistant",
            content = if (i % 2 == 0) "嗯嗯$pad" else "[对话]走这边$pad[/对话]",
            timestamp = startTs + i * 1_000L, isOfflineMode = true, offlineSessionId = "sess1",
        )
    }

    // MARK: - T2-1 普通装配

    @Test
    fun t2_1_online_hasPlainCoreAndSmsModules_noOfflineLeak() {
        // 关掉「主动发起线下见面」邀约守卫（其文案含「线下见面」，与本场景无关）→ 隔离场景切换行为。
        val msgs = buildOnline(AppSettings(characterCanInitiateOfflineMeeting = false))
        val all = allText(msgs)
        assertTrue("核心规则普通版身份句", all.contains("在 APP 里"))
        assertTrue("核心规则普通版禁描写(r4)", all.contains("纯文字聊天软件"))
        assertTrue("短信腔·聊天格式在场", all.contains("【聊天格式】"))
        assertTrue("短信腔·情绪表达在场", all.contains("【情绪表达】"))
        assertFalse("不泄线下核心规则身份句", all.contains("面对面待在一起"))
        assertFalse("不泄沉浸式叙事（线下预设/风格守卫皆未注入）", all.contains("沉浸式叙事"))
        assertFalse("末尾无线下模式标记", all.contains("【当前处于线下见面模式】"))
        // 注：不断言「线下见面」缺席——【约定未来见面】守卫（future_meeting）不受本开关约束恒注入其文案，
        // 与场景切换正交（PromptBuilder :546-547）；线下泄漏由上面「面对面待在一起」精确否定式钉死。
    }

    // MARK: - T2-2 线下装配

    @Test
    fun t2_2_offline_swapsCoreRules_dropsSmsModules() {
        val msgs = buildOffline()
        val all = allText(msgs)
        assertTrue("首条 system 含线下身份句", firstSystem(msgs).contains("面对面待在一起"))
        assertTrue("线下保留 r1", all.contains("记住聊过的细节"))
        assertTrue("线下保留 r3", all.contains("不输出任何系统/审核/政策类元话语"))
        assertTrue("末尾线下模式标记在场", all.contains("【当前处于线下见面模式】"))
        assertFalse("删纯文字禁描写(r4)", all.contains("纯文字聊天软件"))
        assertFalse("删孤立引号禁令(r5)", all.contains("孤立的引号"))
        assertFalse("聊天格式退场", all.contains("【聊天格式】"))
        assertFalse("回复风格退场", all.contains("【回复风格】"))
        assertFalse("情绪表达退场（模块标题）", all.contains("【情绪表达】"))
        // 注：不断言「[mood:」缺席——线下沉浸预设规则 3 明文点名禁 [mood:]（F8·OfflineNarrativePreset:229），
        // 该禁令文案本就含子串「[mood:」；情绪表达模块退场由上面【情绪表达】标题缺席钉死。
    }

    // MARK: - 场景感小批（2026-09-06 图纸 §7 T2-1）：线上「不在一起 / 你也看不到{名字}」

    @Test
    fun 线上核心规则_身份句与r4r4b四处均用用户昵称() {
        // 图纸 §4-M1/M2/M3 物料在此「重新打字」为字面量（不引实现常量·PITFALLS 1e）。
        val all = allText(
            buildOnline(
                AppSettings(characterCanInitiateOfflineMeeting = false),
                profile = UserProfileEntity(nickname = "小美"),
            ),
        )
        assertTrue("l1 补『你们此刻不在一起』", all.contains("在 APP 里和小美发消息聊天——你们此刻不在一起，各在各的地方，只靠手机上的消息联系。"))
        assertTrue("r4 名字位 = 昵称", all.contains("这是纯文字聊天软件，小美看不到你："))
        assertTrue("r4b 前两处名字位", all.contains("- 你也看不到小美：小美在哪、在干嘛、什么表情、身边有什么，"))
        assertTrue("r4b 第三处名字位", all.contains("你只能从小美打出来的字里知道；"))
        assertTrue("r4b 第四处名字位", all.contains("想知道就直接问，别替小美脑补着写。"))
        assertFalse("r4 不再写「对方」", all.contains("对方看不到你"))
        // 「禁 ta」范围 = 核心规则块本身（§11 D-1）：全文级别的 contains("ta") 会被成长模块既有
        // 「你还在观察ta」「你对ta没有依恋」与邀约 schema 的 "tension_hint" 命中，那些是本批零碰的存量文案。
        val coreRules = all.substring(all.indexOf("【核心规则】"), all.indexOf("- 只发聊天正文"))
        assertFalse("核心规则块内不写「ta」（用户拍板①）", coreRules.contains("ta"))
        assertFalse("核心规则块内不写「TA」", coreRules.contains("TA"))
        // 顺序：r4b 紧跟 r4、r5 在 r4b 之后（条目序 title/l1/l2/r1/r2/r3/r4/r4b/r5）。
        assertTrue("r4b 排在 r4 之后", all.indexOf("小美看不到你") < all.indexOf("- 你也看不到小美"))
        assertTrue("r5 排在 r4b 之后", all.indexOf("- 你也看不到小美") < all.indexOf("- 只发聊天正文"))
    }

    @Test
    fun 线上核心规则_昵称为空时四处名字位落回用户() {
        val all = allText(buildOnline(AppSettings(characterCanInitiateOfflineMeeting = false), profile = null))
        assertTrue("r4 落回「用户」", all.contains("这是纯文字聊天软件，用户看不到你："))
        assertTrue("r4b 落回「用户」", all.contains("- 你也看不到用户：用户在哪、在干嘛"))
    }

    @Test
    fun 线上核心规则_英文资源同步补两句() {
        val all = allText(
            buildOnline(AppSettings(characterCanInitiateOfflineMeeting = false), strings = enStrings()),
        )
        assertTrue("en l1 补句", all.contains("the two of you are not together right now"))
        assertTrue("en r4 名字位", all.contains("and User cannot see you:"))
        assertTrue("en r4b", all.contains("- You can't see User either: where User is, what User is doing"))
    }

    // MARK: - 场景感小批（2026-09-06 图纸 §7 T2-1 ③④）：线下末位说明书钉见面地点

    @Test
    fun 线下末位说明书首句带地点与种子块() {
        val marker = startMarker()
        val msgs = buildOffline(
            history = listOf(marker) + offlineNarratives(2, marker.timestamp + 1_000L),
            profile = UserProfileEntity(nickname = "小美"),
        )
        // 说明书恒为**物理最末**一条 system（B5）。
        val last = msgs.last()
        assertTrue("末条是 system", last.role == "system")
        val lastText = last.content.orEmpty()
        assertTrue("末条是线下说明书", lastText.startsWith("【当前处于线下见面模式】"))
        assertTrue(
            "首句 = M6 第一形态",
            lastText.contains("你现在和小美面对面在一起，这次是在公园，散步；中途换了地方，以对话里最近一个 [场景] 标签为准。请用沉浸式叙事风格输出内容。"),
        )
        assertTrue("种子块在", lastText.contains("【今日场景种子】\n她有心事"))
        // 长见面用例「标记已被截掉」的正向对照：短历史时标记确实注入在窗口里（PITFALLS 1e 全否定断言配正向证据）。
        assertTrue("短历史时入场标记在窗口内", msgs.any { it.content.orEmpty().contains("【线下见面开始") })
        val all = allText(msgs)
        assertFalse("线下不注入 r4b", all.contains("你也看不到"))
        assertFalse("线下不注入 l1 补句", all.contains("你们此刻不在一起"))
        assertFalse("位置天气块已整块退役", all.contains("【双方位置和天气】"))
        assertFalse("不再让模型自行决定地点", all.contains("自然决定见面地点"))
    }

    @Test
    fun 长见面_入场标记被窗口挤掉后地点与种子仍在() {
        // 见面块按 CJK 字符预算保留（图纸 2026-09-06 见面窗口与节拍卡七件 §3.B/F）：预算 20,000 字、最少保 8 条。
        // 130 条各 ~400 字 = 52,000 字 → 由新到旧只留得下 ~50 条，最旧的入场标记必被挤出窗口。
        val marker = startMarker()
        val msgs = buildOffline(
            history = listOf(marker) + offlineNarratives(130, marker.timestamp + 1_000L, filler = 400),
            profile = UserProfileEntity(nickname = "小美"),
        )
        // 判别力前提：标记确实已不在注入的历史里（否则本例退化成上一条）。
        assertTrue(
            "入场标记已被窗口截掉",
            msgs.none { it.content.orEmpty().contains("【线下见面开始") },
        )
        val lastText = msgs.last().content.orEmpty()
        assertTrue(
            "地点仍钉在首句（V5）",
            lastText.contains("你现在和小美面对面在一起，这次是在公园，散步；中途换了地方，以对话里最近一个 [场景] 标签为准。"),
        )
        assertTrue("心事种子仍在（V5）", lastText.contains("【今日场景种子】\n她有心事"))
    }

    @Test
    fun 线下无入场标记时首句退回无地点形态() {
        val lastText = buildOffline(profile = UserProfileEntity(nickname = "小美")).last().content.orEmpty()
        assertTrue("M6 第三形态", lastText.contains("你现在和小美面对面在一起。请用沉浸式叙事风格输出内容。"))
        assertFalse("无地点分句", lastText.contains("这次是在"))
        assertFalse("无种子块", lastText.contains("【今日场景种子】"))
    }

    @Test
    fun 线下昵称为空时首句落回用户() {
        val lastText = buildOffline().last().content.orEmpty()
        assertTrue(lastText.contains("你现在和用户面对面在一起。"))
    }

    // MARK: - T2-3 脏状态 → 普通装配（V-3）

    @Test
    fun t2_3_dirtyState_fallsBackToOnline_dividerRestored() {
        // 脏状态（flag=true·sessionId 空白）+ 传 OFFLINE_MEETING：isOfflineModeHealthy=false → effectiveScene 回落
        // ONLINE_CHAT、moduleScene=ONLINE_CHAT。用**普通**跨天消息（线下消息在非线下装配会被 PromptBuilderWindow:41
        // 整体剔除，无法留存），跨夜触发时间分割线——这正是 V-3 的判别信号（若误当线下会门控关掉分割线）。
        val crossDay = listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user", content = "在吗",
                timestamp = sh(2026, 6, 25, 14, 56)),
            MessageEntity(messageUUID = "u2", conversationUuid = "conv1", roleRaw = "user", content = "你看看几点了",
                timestamp = sh(2026, 6, 26, 0, 15)),
        )
        val msgs = PromptBuilder.buildMessages(
            character = character(), conversation = dirtyConv(), sortedMessages = crossDay, userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = shInst(2026, 6, 26, 0, 17),
            scene = PromptScene.OFFLINE_MEETING,
        )
        val all = allText(msgs)
        assertTrue("脏状态按普通版核心规则", all.contains("纯文字聊天软件"))
        assertTrue("脏状态短信腔在场", all.contains("【聊天格式】"))
        assertTrue("V-3 时间分割线恢复注入（effectiveScene=ONLINE_CHAT 门控打开）", all.contains("【时间 · "))
        assertFalse("不用线下核心规则", all.contains("面对面待在一起"))
        assertFalse("末尾非线下模式", all.contains("【当前处于线下见面模式】"))
    }

    // MARK: - T2-4 恢复链路（健康线下 + 传 ONLINE_CHAT）→ 全线下装配（V-4）

    @Test
    fun t2_4_recovery_healthyOfflineWithOnlineScene_fullOffline() {
        val msgs = buildOffline(scene = PromptScene.ONLINE_CHAT) // 健康线下会话 + 硬编码 ONLINE_CHAT
        val all = allText(msgs)
        assertTrue("恢复链路仍走线下核心规则", firstSystem(msgs).contains("面对面待在一起"))
        assertTrue("末尾线下模式标记", all.contains("【当前处于线下见面模式】"))
        assertFalse("短信腔退场", all.contains("【聊天格式】"))
        assertFalse("普通核心规则不出现", all.contains("纯文字聊天软件"))
    }

    // MARK: - T2-5 主 content 自定义：普通用之，线下不受影响（E4）

    @Test
    fun t2_5_customMainContent_onlyOnline_offlineUsesBuiltIn() {
        val json = jsonWithCore { it.copy(content = "自定义主文案XYZ") }
        val online = allText(buildOnline(AppSettings(promptModulesJSON = json)))
        assertTrue("普通聊天用自定义主文案", online.contains("自定义主文案XYZ"))

        val offline = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertFalse("线下不含自定义主文案", offline.contains("自定义主文案XYZ"))
        assertTrue("线下用内置线下身份句", offline.contains("面对面待在一起"))
    }

    // MARK: - T2-6 语音装配（scene=VOICE_CALL·非线下）→ 二值化按在线位（B-2）

    @Test
    fun t2_6_voiceCall_binarizesToOnline_keepsSmsModulesAndPlainCore() {
        val all = allText(buildOnline(AppSettings(), scene = PromptScene.VOICE_CALL))
        assertTrue("语音保留聊天格式", all.contains("【聊天格式】"))
        assertTrue("语音保留情绪表达", all.contains("【情绪表达】"))
        assertTrue("语音用核心规则普通版", all.contains("纯文字聊天软件"))
        assertFalse("语音不走线下核心规则", all.contains("面对面待在一起"))
    }

    // MARK: - T2-7 CORE_RULES enabledScenes={ONLINE_CHAT} → 线下无核心规则（过滤先于分流·E5）

    @Test
    fun t2_7_coreRulesScopedOnline_offlineHasNoCoreRules() {
        val json = jsonWithCore { it.copy(enabledScenes = setOf(PromptScene.ONLINE_CHAT)) }
        val offline = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertFalse("线下核心规则被开关过滤掉→无标题", offline.contains("【核心规则】"))
        assertFalse("线下也无线下身份句（整模块不注入）", offline.contains("面对面待在一起"))

        val online = allText(buildOnline(AppSettings(promptModulesJSON = json)))
        assertTrue("在线仍注入核心规则", online.contains("【核心规则】"))
    }

    // MARK: - T2-8 en 资源（E11）

    @Test
    fun t2_8_englishOfflineCoreRules() {
        val all = allText(buildOffline(strings = enStrings()))
        assertTrue("英文线下身份句", all.contains("face to face right now"))
    }

    // MARK: - T2-9 offlineContent 非空 → 用用户线下文案 + 宏解析（E13）

    @Test
    fun t2_9_offlineContentUsed_macroResolved() {
        val json = jsonWithCore { it.copy(offlineContent = "线下测试专版{{user}}") }
        val all = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertTrue("线下用用户 offlineContent", all.contains("线下测试专版"))
        assertFalse("宏已解析（无残留 {{user}}）", all.contains("{{user}}"))
        assertFalse("不落回内置线下版", all.contains("面对面待在一起"))
    }

    // MARK: - T2-10 offlineContent 空 + content 自定义 → 线下用内置版（不串版·E14）

    @Test
    fun t2_10_emptyOfflineContent_customMain_offlineUsesBuiltIn() {
        val json = jsonWithCore { it.copy(content = "主自定义ABC", offlineContent = "") }
        val all = allText(buildOffline(AppSettings(promptModulesJSON = json)))
        assertTrue("线下落内置专版", all.contains("面对面待在一起"))
        assertFalse("线下不串主自定义", all.contains("主自定义ABC"))
    }

    // MARK: - T2-11 自定义模块场景过滤二值化（E15/V-5 两向钉）

    @Test
    fun t2_11_customModuleSceneBinarization_inVoice() {
        val voiceOnly = PromptModule(
            id = "cust-voice", name = "语音专属", content = "自定义语音标记X", sortOrder = 100,
            position = PromptModulePosition.PREFIX, enabledScenes = setOf(PromptScene.VOICE_CALL),
        )
        val onlineOnly = PromptModule(
            id = "cust-online", name = "在线专属", content = "自定义在线标记Y", sortOrder = 101,
            position = PromptModulePosition.PREFIX, enabledScenes = setOf(PromptScene.ONLINE_CHAT),
        )
        val json = PromptModuleService.encodeModules(PromptModuleService.defaultModules() + listOf(voiceOnly, onlineOnly))
        val all = allText(buildOnline(AppSettings(promptModulesJSON = json), scene = PromptScene.VOICE_CALL))
        assertFalse("仅 VOICE_CALL 的模块二值化后不注入（moduleScene=ONLINE_CHAT）", all.contains("自定义语音标记X"))
        assertTrue("仅 ONLINE_CHAT 的模块语音场景注入", all.contains("自定义在线标记Y"))
    }

    // MARK: - 时间工具（Asia/Shanghai 固定，禁真时钟）

    private fun sh(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = shInst(y, mo, d, h, mi).toEpochMilli()
    private fun shInst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()
}
