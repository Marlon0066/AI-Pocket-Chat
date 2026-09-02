package com.situ.aichat.prompt

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CharacterEconomicChatState
import com.situ.aichat.data.model.MomentChatContext
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.pet.OtherPetInfo
import com.situ.aichat.tts.provider.MiniMaxVoiceTagsCapability
import java.time.Instant

/**
 * 系统提示词装配（自 [PromptBuilder] 抽出 · 文件瘦身）：按 scene 过滤启用模块、按 sortOrder 排序、
 * 分前置/后置区逐模块取内容（经 [PromptBuilder] 的 buildModuleContent）拼成系统提示 + 后置条目列表；
 * 全模块禁用时给最小兜底；MiniMax 语气标签教学（前门 6 条件全满足才注入）追在 suffix 末尾。
 *
 * 后置区返回 [SuffixModuleEntry]（内容 + 模块引用），**发射顺序与上下文日志分段由 [PromptBuilder.buildMessages]
 * 在发射点决定**（布局审计第一招 2026-07-11：非线下时 timeAwareness/currentMoment 被钉到物理最末位）。
 *
 * 由 [PromptBuilder.buildMessages] 装配第 1 步调用（同包顶层函数·无需限定）；回调 [PromptBuilder] 的
 * promptMacros / buildModuleContent / buildMiniMaxVoiceTagsHint 及嵌套类型
 * BuildContext / AssistantDeliveryMode / ConversationTimeSnapshot 经 `PromptBuilder.` 限定。
 */

/**
 * 后置区条目：模块产出的内容 + 模块引用（[module] null = 非模块的追加教学，如 MiniMax 语气标签）。
 * 分段统计在发射点按需现算（见 [PromptBuilder.buildMessages]），避免无日志时白算 token 估算。
 */
data class SuffixModuleEntry(
    val content: String,
    val module: PromptModule?,
) {
    val systemModuleType: SystemModuleType? get() = module?.systemModuleType
}

fun buildSystemPromptWithSuffixes(
    character: CharacterEntity,
    milestones: List<MilestoneEntity> = emptyList(),
    todaySchedule: CharacterDailyScheduleEntity? = null,
    todayScheduleEvents: List<ScheduleEventEntity> = emptyList(),
    calendarUpcomingEvents: String? = null,
    momentChatContext: MomentChatContext? = null,
    economicState: CharacterEconomicChatState? = null,
    giftHistory: String? = null,
    userProfile: UserProfileEntity?,
    appSettings: AppSettings,
    structuredMemory: StructuredMemory,
    retrievedMemorySnippets: List<String>,
    /** 线下见面【总结】注入文本（梦剧场 B 部·§3.6·透传进 [PromptBuilder.BuildContext] → {{见面记忆}} 宏，
     *  消费端经相框包装 [buildOfflineMeetingMemoryContent]·2026-07-11 前置改造）。默认 "" = 不注入。 */
    offlineMeetingMemoryText: String = "",
    /** W5 世界联动上下文（null=不注入·照 retrievedMemorySnippets 透传路径进 [PromptBuilder.BuildContext]）。 */
    worldContext: String? = null,
    /** 活人感一期 P2 该角色 open 惦记的事（空=不注入·透传进 [PromptBuilder.BuildContext]，注入选择/格式化在消费侧）。 */
    openLoops: List<com.situ.aichat.data.local.entity.OpenLoopEntity> = emptyList(),
    /** 记忆改造一期·部件① 该角色注入候选约定（空=不注入·透传进 [PromptBuilder.BuildContext]，选择/渲染在消费侧）。 */
    promises: List<com.situ.aichat.data.local.entity.PromiseEntity> = emptyList(),
    /** 「我们的日子」卷二（图纸 §3.3）：三者原样透传进 [PromptBuilder.BuildContext]（筛选 / 渲染在消费侧 `buildOurDaysContent`）。 */
    ourDays: List<com.situ.aichat.data.local.entity.OurDayEntity> = emptyList(),
    ourDaysTurnText: String = "",
    windowEarliestMillis: Long? = null,
    assistantDeliveryMode: PromptBuilder.AssistantDeliveryMode,
    toolCallingEnabled: Boolean,
    miniMaxVoiceTagsCapability: MiniMaxVoiceTagsCapability? = null,
    customStickers: List<CustomStickerEntity> = emptyList(),
    disabledStickers: Set<String> = emptySet(),
    pet: CharacterPetEntity? = null,
    otherPets: List<OtherPetInfo> = emptyList(),
    petRecentPurchaseNames: List<String> = emptyList(),
    timeSnapshot: PromptBuilder.ConversationTimeSnapshot,
    scene: PromptScene,
    /** 延迟生成路标记（进程恢复补生成=true）：时间锚间隔行退回中性措辞。 */
    delayedGeneration: Boolean = false,
    extraMacros: Map<String, String>,
    now: Instant,
    strings: PromptStrings,
    /** 批 D 上下文日志：非 null 时把各非空**前置**模块作为分段收进（后置分段改由调用方在发射点收集=物理序）。 */
    segmentSink: MutableList<ContextSegment>? = null,
    /** WB4 世界书「·前 / ·后」锚点文本（调用方已做宏解析；空 = 无注入）：夹在 CHARACTER_IDENTITY 模块两侧
     *  （= 酒馆 position 0/1「角色定义前后」）；身份模块缺席或空内容时兜底 = 前桶置于提示词最前、后桶收尾。 */
    worldInfoBefore: String = "",
    worldInfoAfter: String = "",
    /** 卷三 D2：透传进 [PromptBuilder.BuildContext.recentCharacterLines]（空 = 无自述·旧行为）。 */
    recentCharacterLines: List<String> = emptyList(),
): Pair<String, List<SuffixModuleEntry>> {
    val macros = PromptBuilder.promptMacros(character, userProfile, strings)
    val ctx = PromptBuilder.BuildContext(
        character = character,
        milestones = milestones,
        todaySchedule = todaySchedule,
        todayScheduleEvents = todayScheduleEvents,
        calendarUpcomingEvents = calendarUpcomingEvents,
        userProfile = userProfile,
        appSettings = appSettings,
        structuredMemory = structuredMemory,
        retrievedMemorySnippets = retrievedMemorySnippets,
        offlineMeetingMemoryText = offlineMeetingMemoryText,
        worldContext = worldContext,
        openLoops = openLoops,
        promises = promises,
        ourDays = ourDays,
        ourDaysTurnText = ourDaysTurnText,
        windowEarliestMillis = windowEarliestMillis,
        assistantDeliveryMode = assistantDeliveryMode,
        toolCallingEnabled = toolCallingEnabled,
        miniMaxVoiceTagsCapability = miniMaxVoiceTagsCapability,
        momentChatContext = momentChatContext,
        economicState = economicState,
        giftHistory = giftHistory,
        customStickers = customStickers,
        disabledStickers = disabledStickers,
        pet = pet,
        otherPets = otherPets,
        petRecentPurchaseNames = petRecentPurchaseNames,
        macros = macros,
        resolvedUserName = macros["{{user}}"] ?: strings.s(R.string.pb_user_fallback),
        resolvedCharacterName = macros["{{char}}"] ?: character.name,
        timeSnapshot = timeSnapshot,
        scene = scene,
        delayedGeneration = delayedGeneration,
        extraMacros = extraMacros,
        now = now,
        strings = strings,
        recentCharacterLines = recentCharacterLines,
    )

    val modules = PromptModuleService.effectiveModules(
        characterUuid = character.uuid,
        globalJson = appSettings.promptModulesJSON,
        characterJson = appSettings.characterPromptModulesJSON,
    )
    // 方案 V2：按 scene 过滤（enabledScenes==null → 全场景）
    val enabledModules = modules
        .filter { it.isEnabled }
        .filter { (it.enabledScenes ?: PromptScene.entries.toSet()).contains(scene) }
        .sortedBy { it.sortOrder }

    val prefixModules = enabledModules.filter { it.position == PromptModulePosition.PREFIX }
    val suffixModules = enabledModules.filter { it.position == PromptModulePosition.SUFFIX }

    val prefixParts = mutableListOf<String>()
    var worldInfoAnchored = false
    for (module in prefixModules) {
        val content = PromptBuilder.buildModuleContent(module, ctx)
        if (content.isNotEmpty()) {
            val isIdentityAnchor = !worldInfoAnchored &&
                module.systemModuleType == SystemModuleType.CHARACTER_IDENTITY
            if (isIdentityAnchor && worldInfoBefore.isNotBlank()) prefixParts.add(worldInfoBefore)
            prefixParts.add(content)
            segmentSink?.add(PromptBuilder.moduleSegment(module, content, ContextSegment.POSITION_PREFIX))
            if (isIdentityAnchor && worldInfoAfter.isNotBlank()) prefixParts.add(worldInfoAfter)
            if (isIdentityAnchor) worldInfoAnchored = true
        }
    }
    // 身份模块缺席/空内容的兜底：前桶置顶、后桶收尾（§2.2 映射的降级语义）。
    if (!worldInfoAnchored) {
        if (worldInfoBefore.isNotBlank()) prefixParts.add(0, worldInfoBefore)
        if (worldInfoAfter.isNotBlank()) prefixParts.add(worldInfoAfter)
    }

    // 安全兜底：所有模块都禁用 → 最小提示词
    if (prefixParts.isEmpty() && suffixModules.isEmpty()) {
        val fallback = "You are “${character.name}”. Reply in this character’s identity and tone, in the current app language."
        return Pair(fallback, emptyList())
    }

    val systemPrompt = prefixParts.joinToString("\n\n")

    val suffixEntries = mutableListOf<SuffixModuleEntry>()
    for (module in suffixModules) {
        val content = PromptBuilder.buildModuleContent(module, ctx)
        if (content.isNotEmpty()) {
            suffixEntries.add(SuffixModuleEntry(content, module))
        }
    }
    // P10.1c MiniMax speech-2.8 语气标签教学（前门，1:1 iOS）。只在 6 条件全满足时追加，
    // suffix 位置让教学贴近聊天历史、对 LLM 影响最大。后门（ReplyParser.stripMiniMaxVoiceTags）
    // 仍无条件清洗文字路径，前门漏判也不会让标签漏进文字气泡。
    // 插入点 = 末尾「时间感知/此刻状态」连续段**之前**（现在卡语义 2026-07-11）：教学归规则类,
    // 不打断现在卡的末位连续段;该教学仅语音通话场景触发,线下见面不受影响。
    if (ctx.miniMaxVoiceTagsCapability?.shouldInjectTagsHint == true) {
        val insertAt = suffixEntries.indexOfLast {
            it.systemModuleType != SystemModuleType.TIME_AWARENESS &&
                it.systemModuleType != SystemModuleType.CURRENT_MOMENT
        } + 1
        suffixEntries.add(insertAt, SuffixModuleEntry(PromptBuilder.buildMiniMaxVoiceTagsHint(ctx.strings), module = null))
    }

    return Pair(systemPrompt, suffixEntries)
}
