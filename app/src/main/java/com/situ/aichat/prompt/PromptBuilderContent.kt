package com.situ.aichat.prompt

import com.situ.aichat.R
import com.situ.aichat.data.model.currentAge
import com.situ.aichat.openloop.OpenLoopScanService
import com.situ.aichat.promise.PromiseInjectionRenderer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 各系统模块的「正文构建器」（自 [PromptBuilder] 抽出 · 文件瘦身，**行为零改 / 逐字不变**）：核心规则 /
 * 聊天格式 / 回复风格 / 质量控制 / 情绪表达 / 通用指令 / 忙碌回复 等可编辑模块的字面默认文案，以及角色身份 /
 * 用户人设 / 角色记忆 / 时间感知 等数据类模块的整块装配。
 *
 * 由 [PromptBuilder] 的 macroProducers / buildModuleContent / defaultEditableTemplate 装配时调用（同包顶层函数，
 * 无需限定）；回调 [PromptBuilder] 的 applyPromptMacros / BuildContext 经 `PromptBuilder.` 限定。文案走
 * [PromptStrings]（中英 values）或硬编码中文（LLM 读的产品资产·逐字对齐 iOS）。
 */

// 可编辑模块的默认文案 = 字面模板（名字位以 {{char}}/{{user}} 留位时即得"模板"，传真实名字即得装配内容）。
// 收敛为 (strings, 名字) 后既供装配、又供 [defaultEditableTemplate] 给 UI 预填，单一事实源（提示词模块编辑重设计 P1c）。
internal fun buildCoreRulesContent(s: PromptStrings, charName: String, userName: String): String =
    listOf(
        s.s(R.string.pb_core_title),
        s.s(R.string.pb_core_l1, charName, userName),
        s.s(R.string.pb_core_l2),
        s.s(R.string.pb_core_r1),
        s.s(R.string.pb_core_r2, userName),
        s.s(R.string.pb_core_r3),
        s.s(R.string.pb_core_r4, userName),
        s.s(R.string.pb_core_r4b, userName),
        s.s(R.string.pb_core_r5),
    ).joinToString("\n")

/**
 * 线下见面版核心规则（两语境模型 2026-07-12·用户过审文案）：身份句换「面对面相处」、删纯文字
 * 禁描写（r4）与孤立引号（r5）两条冲突禁令；r1/r2/r3 与普通版共享资源键（同一段逻辑只写一处）。
 * r4 / r4b（「隔屏互相看不见」两条事实）面对面时不成立，故不进线下版。
 * 消费点：[PromptBuilder.buildModuleContent] 线下分流（用户 offlineContent 优先）+ 编辑表单线下版预填锚。
 * 不进 defaultEditableTemplate；格式禁令由末尾 OfflineNarrativePreset 独家看守，此处零重复。
 */
internal fun buildOfflineCoreRulesContent(s: PromptStrings, charName: String, userName: String): String =
    listOf(
        s.s(R.string.pb_core_title),
        s.s(R.string.pb_core_off_l1, charName, userName),
        s.s(R.string.pb_core_off_l2),
        s.s(R.string.pb_core_r1),
        s.s(R.string.pb_core_r2, userName),
        s.s(R.string.pb_core_r3),
    ).joinToString("\n")

internal fun buildChatFormatContent(s: PromptStrings): String {
    // 刀3 文案去重（2026-07-11 过审）：旧 l4「无格式修饰/引号/舞台指示」删——与核心规则 r4/r5 三处复读，
    // 该禁令收敛到核心规则一处独家看守（资源键 pb_chatfmt_l4 已随删）。
    return listOf(
        s.s(R.string.pb_chatfmt_title),
        s.s(R.string.pb_chatfmt_l1),
        s.s(R.string.pb_chatfmt_l2),
        s.s(R.string.pb_chatfmt_l3),
    ).joinToString("\n")
}

/** chatFormat 末尾始终追加用户设置的分条引导（软性 Aim）。 */
internal fun appendReplySegmentInstruction(baseContent: String, ctx: PromptBuilder.BuildContext): String =
    baseContent + "\n" + replySegmentInstruction(ctx)

/** 分条引导指令本身（= [PromptMacros.REPLY_SEGMENTS] 宏值）。 */
internal fun replySegmentInstruction(ctx: PromptBuilder.BuildContext): String {
    val range = ctx.appSettings.sanitizedReplySegmentRange
    return ctx.strings.s(R.string.pb_chatfmt_segments, range.first, range.last)
}

internal fun buildCharacterIdentityContent(ctx: PromptBuilder.BuildContext): String {
    val s = ctx.strings
    val c = ctx.character
    val parts = mutableListOf<String>()

    parts.add(s.s(R.string.pb_ident_name, c.name))
    if (c.gender.isNotEmpty()) parts.add(s.s(R.string.pb_ident_gender, c.gender))

    c.currentAge(ctx.now)?.let { age -> if (age > 0) parts.add(s.s(R.string.pb_ident_age, age)) }

    c.birthday?.let { bday ->
        val zodiac = ZodiacCalculator.zodiacSign(bday)
        if (zodiac.isNotEmpty()) parts.add(s.s(R.string.pb_ident_zodiac, zodiac))
    }

    PromptBuilder.applyPromptMacros(c.occupation, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_occupation, it)) }
    PromptBuilder.applyPromptMacros(c.appearanceDescription, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_appearance, it)) }
    PromptBuilder.applyPromptMacros(c.personalityDescription, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_personality, it)) }
    PromptBuilder.applyPromptMacros(c.backstory, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_backstory, it)) }
    PromptBuilder.applyPromptMacros(c.speakingStyle, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_speaking, it)) }
    PromptBuilder.applyPromptMacros(c.catchphrases, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_catchphrases, it)) }
    // 示例段头线下版（场景感小批 2026-09-06）：线下装配时限定句改指末尾标签规则，不再指线上【聊天格式】。
    val examplesHeader = if (ctx.scene == PromptScene.OFFLINE_MEETING) R.string.pb_ident_examples_offline else R.string.pb_ident_examples
    PromptBuilder.applyPromptMacros(c.exampleDialogues, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(examplesHeader, it)) }
    PromptBuilder.applyPromptMacros(c.initialInterests, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_interests, it)) }
    PromptBuilder.applyPromptMacros(c.systemPrompt, ctx.macros).takeIf { it.isNotEmpty() }?.let { parts.add(s.s(R.string.pb_ident_setup, it)) }

    return parts.joinToString("\n")
}

internal fun buildUserPersonaContent(ctx: PromptBuilder.BuildContext): String {
    val profile = ctx.userProfile ?: return ""
    if (profile.nickname.isEmpty()) return ""
    val s = ctx.strings
    val parts = mutableListOf<String>()
    parts.add(s.s(R.string.pb_persona_title))
    parts.add(s.s(R.string.pb_persona_name, ctx.resolvedUserName))
    if (profile.bio.isNotEmpty()) {
        parts.add(s.s(R.string.pb_persona_info, PromptBuilder.applyPromptMacros(profile.bio, ctx.macros)))
    }
    // 城市/天气（iOS 此处文案为硬编码中文，非 localized）。天气数据未接入 → 仅城市行。
    val city = profile.cityName
    if (!city.isNullOrEmpty()) {
        parts.add("${ctx.resolvedUserName}当前所在城市：$city") // TODO(M19): userWeather 拼当前天气/温度区间/趋势
    }
    // 相处偏好（四小件·2026-07-16·空不注入）：用户显式说明「希望 TA 怎么待你」，宏照 bio 纹路解析。
    if (profile.companionPreference.isNotBlank()) {
        parts.add(
            s.s(
                R.string.pb_persona_treatment,
                ctx.resolvedUserName,
                PromptBuilder.applyPromptMacros(profile.companionPreference, ctx.macros),
            ),
        )
    }
    // 用户生日（四小件·2026-07-16）：只给月日不给年份（J1——年份=年龄属敏感信息，想让角色知道可自写进 bio）；
    // 当天祝福交模型看时间锚自发，本卷不做主动祝福机制。
    profile.birthday?.let { millis ->
        parts.add(
            s.s(
                R.string.pb_persona_birthday,
                ctx.resolvedUserName,
                formatBirthdayForPrompt(millis, s.s(R.string.pb_persona_birthday_pattern)),
            ),
        )
    }
    return parts.joinToString("\n")
}

/**
 * 用户生日 → 提示词用的「月日」串（四小件·2026-07-16）。
 *
 * [utcMillis] 存的是 M3 DatePicker 的 `selectedDateMillis` = **UTC 零点**，故按 [ZoneOffset.UTC] 取月日
 * 恒等于用户当初所选的那一天，不受设备时区影响（J2）；[pattern] 走双语资源（zh=`M月d日`/en=`MMMM d`），
 * `Locale.ROOT` 对齐「面向 LLM 的日期格式器统一 ROOT」惯例（PITFALLS 1c）。注意：ROOT 下 `MMMM` 实测输出
 * **缩写**月名（en 侧=「Mar 5」非「March 5」，D-3 实测取证）——确定性不随设备语言漂移的目的已达成，
 * 缩写对 LLM 同样清晰；要全名须换 `Locale.ENGLISH`，属提示词行为变更，勿顺手改。
 */
internal fun formatBirthdayForPrompt(utcMillis: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
        .format(Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC))

internal fun buildCharacterMemoryContent(ctx: PromptBuilder.BuildContext): String {
    val s = ctx.strings
    val sm = ctx.structuredMemory
    val memorySummary = ctx.character.memorySummary
    val snippets = ctx.retrievedMemorySnippets
    val worldContext = ctx.worldContext
    // 第五层：惦记的事（活人感一期 P2·§4.3）——注入选择 + 格式化在此完成（用 ctx.timeSnapshot/now/strings）。
    val openLoopBlock = buildOpenLoopInjectionBlock(ctx)
    // 第 2.5 层：我们的约定（记忆改造一期·部件①·§3.3）——选择/排序/软上限/渲染在此完成（用 ctx.promises/now）。
    val promiseBlock = buildPromiseInjectionBlock(ctx)

    if (!sm.hasAnyData && memorySummary.isEmpty() && snippets.isEmpty() && worldContext.isNullOrBlank() &&
        openLoopBlock.isEmpty() && promiseBlock.isEmpty()
    ) {
        return ""
    }

    val parts = mutableListOf<String>()
    parts.add(s.s(R.string.pb_mem_title, ctx.resolvedCharacterName))

    // 第一层：结构化记忆（第三人称指名·范围 B·2026-07-14）：角色/用户双名字口径，整块与「[角色名的记忆]」标题同调。
    if (sm.hasAnyData) {
        val c = ctx.resolvedCharacterName
        val u = ctx.resolvedUserName
        parts.add(s.s(R.string.pb_mem_keyfacts, c, u))
        if (sm.nicknameFromChar.isNotEmpty()) parts.add(s.s(R.string.pb_mem_call_user, c, u, sm.nicknameFromChar))
        if (sm.nicknameToChar.isNotEmpty()) parts.add(s.s(R.string.pb_mem_user_calls, u, c, sm.nicknameToChar))
        if (sm.insideJoke.isNotEmpty()) parts.add(s.s(R.string.pb_mem_joke, c, u, sm.insideJoke))
        if (sm.deepestChat.isNotEmpty()) parts.add(s.s(R.string.pb_mem_deepest, sm.deepestChat))
        if (sm.impressionOfUser.isNotEmpty()) parts.add(s.s(R.string.pb_mem_impression, c, u, sm.impressionOfUser))
        if (sm.sharedLikes.isNotEmpty()) parts.add(s.s(R.string.pb_mem_shared, c, u, sm.sharedLikes))
        if (sm.learnedPhrase.isNotEmpty()) parts.add(s.s(R.string.pb_mem_learned, c, u, sm.learnedPhrase))
        if (sm.importantPromise.isNotEmpty()) parts.add(s.s(R.string.pb_mem_promise, c, u, sm.importantPromise))
        if (sm.firstConflict.isNotEmpty()) parts.add(s.s(R.string.pb_mem_conflict, c, u, sm.firstConflict))
        if (sm.comfortStyle.isNotEmpty()) parts.add(s.s(R.string.pb_mem_comfort, c, u, sm.comfortStyle))
    }

    // 第二层：LLM 摘要。刀3 文案去重（2026-07-11 过审）：默认分支的使用说明收敛为一份完整指南在前
    // （旧「以上记忆只供你参考…」尾巴段撤销,其独有信息并进 guide/format_ban），防泄漏保命句
    // （pb_mem_format_ban·与 DirtyMessageDetector 配套·句身逐字不动）上移为指南第三行；
    // 自定义注入模板分支保持禁令后置（模板内容用户自控,禁令兜底位置不变）。
    if (memorySummary.isNotEmpty()) {
        val customPrompt = ctx.appSettings.memoryInjectionPrompt
        if (customPrompt.isEmpty()) {
            parts.add(s.s(R.string.pb_mem_past_head))
            // 记忆守则里的用户称呼改用真实用户名（角色直读的提示词·真名更自然）；无昵称回退「对方」
            // （本地化·非全局 pb_user_fallback=「用户」）。与 ① VOICE_CALL_HISTORY_HINT 同口径。
            val memUser = ctx.userProfile?.nickname?.trim()?.takeIf { it.isNotEmpty() }
                ?: s.s(R.string.pb_mem_other_fallback)
            parts.add(s.s(R.string.pb_mem_past_guide, memUser))
            parts.add(s.s(R.string.pb_mem_format_ban))
            parts.add(memorySummary)
        } else {
            var customResult = customPrompt.replace("{{记忆内容}}", memorySummary)
            customResult = PromptBuilder.applyPromptMacros(customResult, ctx.macros)
            parts.add(customResult)
            parts.add(s.s(R.string.pb_mem_format_ban))
        }
    }

    // 第 2.5 层：我们的约定（记忆改造一期·部件①·自带块头 + 指引·additive·§3.3）——LLM 摘要之后、向量检索之前。
    if (promiseBlock.isNotEmpty()) parts.add(promiseBlock)

    // 第三层：向量检索结果
    if (snippets.isNotEmpty()) {
        parts.add(s.s(R.string.pb_mem_snippets_head))
        parts.add(s.s(R.string.pb_mem_snippets_intro))
        for (snippet in snippets) parts.add(snippet)
    }

    // 第四层：世界联动上下文（W5·提炼 + 世界记忆·自带块头·§9 联动闭环·additive）。
    if (!worldContext.isNullOrBlank()) parts.add(worldContext)

    // 第五层：惦记的事（活人感一期 P2·§4.3·自带块头 + 指引·additive·worldContext 之后）。
    if (openLoopBlock.isNotEmpty()) parts.add(openLoopBlock)

    return parts.joinToString("\n")
}

/**
 * 惦记的事注入块（活人感一期 P2·§3.2/§4.3）：从 ctx.openLoops 用
 * [OpenLoopScanService.selectLoopsForInjection]（本地时区·lastAssistantTime=ctx.timeSnapshot）选注入项，
 * 经 [OpenLoopScanService.formatInjectionBlock] 渲染。空 → ""。
 *
 * 记忆改造四期·§3.6-②注入兜底：选注入前先 [OpenLoopScanService.excludeLedgerEchoes] 剔除与约定注入候选
 * （ctx.promises = open + 7 天已结·两态都已在【我们的约定】块呈现）去空白等值的 loop（账本优先·防一事双呈现）。
 */
private fun buildOpenLoopInjectionBlock(ctx: PromptBuilder.BuildContext): String {
    val loops = ctx.openLoops
    if (loops.isEmpty()) return ""
    val selected = OpenLoopScanService.selectLoopsForInjection(
        loops = OpenLoopScanService.excludeLedgerEchoes(ctx.openLoops, ctx.promises.map { it.content }),
        lastAssistantTime = ctx.timeSnapshot.lastAssistantTime,
        now = ctx.now,
        zone = java.time.ZoneId.systemDefault(),
    )
    return OpenLoopScanService.formatInjectionBlock(selected, ctx.now, ctx.strings)
}

/**
 * 【我们的约定】注入块（记忆改造一期·部件①·§3.3）：从 ctx.promises 经 [PromiseInjectionRenderer.render]
 * 完成选择 / 排序 / 软上限 / 年龄标签 / 到期后缀 / 指引行。空 → ""。now/zone 照 [buildOpenLoopInjectionBlock] 用法。
 */
private fun buildPromiseInjectionBlock(ctx: PromptBuilder.BuildContext): String {
    val promises = ctx.promises
    if (promises.isEmpty()) return ""
    return PromiseInjectionRenderer.render(promises, ctx.now.toEpochMilli(), java.time.ZoneId.systemDefault())
}

internal fun buildTimeAwarenessContent(ctx: PromptBuilder.BuildContext): String {
    // 线下见面专版（前后置区审计 🟡-1b·2026-07-13）：只给时刻事实——间隔行/五档是短信框架措辞
    // （"对方隔了约X才回你""重新拿起手机"），与末位见面说明书"面对面"冲突，见面场景整体退场。
    if (ctx.scene == PromptScene.OFFLINE_MEETING) return TimeAnchorFormatter.buildTimeAnchorFactsOnly(ctx.now)
    val snapshot = ctx.timeSnapshot
    // 用户称呼（相识天数图纸 §13·用户拍板 2026-09-03）：块级单源，相识行与方向化间隔行共用同一个称呼——
    // 有昵称叫昵称、空才叫「对方」（与 PromptBuilderGuards / PromptBuilder 的【待见约定】同一条规则）。
    // 取值与 [PromptBuilder.buildPromptMacros] 同款「先 trim 再判空」：备份导入不 trim 昵称
    // （`BackupImportMappers` 原样写回），纯空格昵称会渲染成「你和 是 …」「 隔了约 3 小时才回你」两处豁口。
    val userLabel = ctx.userProfile?.nickname?.trim()?.takeIf { it.isNotEmpty() } ?: TimeAnchorFormatter.USER_LABEL_FALLBACK
    // 相识行（相识天数图纸 §4.2）：字段为空（从没聊过 / 补账前）→ null → 相识行整行缺席。
    val acquaintance = ctx.character.firstMessageDate?.let { first ->
        TimeAnchorFormatter.AcquaintanceFacts(firstMessageDate = first, streakCount = ctx.character.streakCount)
    }
    return TimeAnchorFormatter.buildTimeAnchor(
        now = ctx.now,
        lastAssistantTime = snapshot.lastAssistantTime,
        directionalGapLine = !ctx.delayedGeneration,
        acquaintance = acquaintance,
        userLabel = userLabel,
    )
}

/**
 * 回复风格块（活人感一期 P1）：[textingTone]=true 时在 l1/l2 之后追加「像手机打字那样说话」全局规则 [R.string.pb_style_l3]；
 * false 时输出与旧值逐字节一致（仅 title+l1+l2）。运行时开关值由 [PromptBuilder.buildModuleContent] 经
 * [PromptBuilder.defaultEditableTemplate] 传入（读 `appSettings.textingToneEnabled`）；提示词模块编辑器预填 / 单测
 * 走默认 false（保持既有默认模板逐字节不变，见 [PromptBuilder.defaultEditableTemplate] 说明）。
 */
internal fun buildResponseStyleContent(s: PromptStrings, textingTone: Boolean): String =
    listOfNotNull(
        s.s(R.string.pb_style_title),
        s.s(R.string.pb_style_l1),
        s.s(R.string.pb_style_l2),
        if (textingTone) s.s(R.string.pb_style_l3) else null,
    ).joinToString("\n")

internal fun buildQualityControlContent(s: PromptStrings): String =
    listOf(
        s.s(R.string.pb_quality_title),
        s.s(R.string.pb_quality_l1),
        s.s(R.string.pb_quality_l2),
        s.s(R.string.pb_quality_l3),
        s.s(R.string.pb_quality_l4),
    ).joinToString("\n")

internal fun buildMoodExpressionContent(s: PromptStrings): String =
    listOf(
        s.s(R.string.pb_mood_title),
        s.s(R.string.pb_mood_l1),
        "[mood:😊|green|有点开心] 或 [mood:😰|yellow|有点紧张] 或 [mood:😤|red|真的生气了]",
        s.s(R.string.pb_mood_fmt),
        s.s(R.string.pb_mood_colors),
        s.s(R.string.pb_mood_emoji),
    ).joinToString("\n")

internal fun buildGeneralInstructionsContent(s: PromptStrings): String =
    s.s(R.string.pb_general)

/** 忙碌延迟回复指令模板（iOS defaultEditableContent 为硬编码中文，非 localized）。宏由 resolveLazy 解析。 */
internal fun busyReplyTemplate(): String =
    listOf(
        "你刚才在{{busy_activity}},现在刚忙完看到了{{user}}发来的消息。",
        "请一次性回复{{user}}的所有消息。语气自然,可以提到你刚才在忙什么。",
        "不要逐条回复,而是把所有内容整合成一段自然的回复。",
        "这是{{user}}刚才连续发来的内容:",
        "{{user_pending_messages}}",
    ).joinToString("\n")

/**
 * 见面记忆模块内容 =「相框」出处行 + renderedForInjection 原文（2026-07-11 拍板：读端补出处元信息，
 * 治系统话语「你」=角色 × 日记「你」=对方的指代翻转；写端/资料页卡片零碰）。
 * 空文本 → ""（模块整体不注入，绝不出现孤框）。标题用半角 []（同 pb_mem_title 族），刻意避开【】家族
 * —— 不新增 DirtyMessageDetector 监视负担（REDLINES:【见面 · 】条目零碰）。
 */
internal fun buildOfflineMeetingMemoryContent(ctx: PromptBuilder.BuildContext): String {
    val raw = ctx.offlineMeetingMemoryText
    if (raw.isBlank()) return ""
    return ctx.strings.s(R.string.pb_meeting_memory_frame, ctx.resolvedCharacterName, ctx.resolvedUserName) +
        "\n\n" + raw
}
