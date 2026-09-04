package com.situ.aichat.prompt

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.FutureMeetingChangeJson
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.MessageContentSentinels
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.data.model.SystemEventType
import com.situ.aichat.data.model.buildRedPacketLLMRepresentation
import com.situ.aichat.data.model.systemEventTargetIsAssistant
import com.situ.aichat.data.remote.llm.ChatContentPart
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.QuotedMessageRef
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.offline.OfflineMarkerEndPayload
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.sticker.StickerService
import java.time.Instant
import java.time.ZoneId

/**
 * 对话历史合并（自 [PromptBuilder] 抽出 · 文件瘦身，**行为零改 / 逐字不变**）：把窗口内消息按 user/assistant/pet
 * 角色归并成 [ChatMessageDto] 列表——结构化卡片（礼物/红包）转 llmRepresentation 永不漏原始 JSON/金额、引用上下文
 * 注入、表情包标签↔语义双向转换、系统事件按动作执行者归属、宠物消息独立 user 段、语音消息多模态音频段（P13.4b）。
 *
 * 由 [PromptBuilder.buildMessages] 装配第 3 步调用（同包顶层函数·无需限定）；回调 [PromptBuilder] 的
 * ROLE_USER/ROLE_ASSISTANT/ROLE_SYSTEM / buildAudioPrompt / AUDIO_FORMAT_WAV / encodeWavBase64 经 `PromptBuilder.` 限定。
 */

/**
 * 带图消息挂 image 段时的 text 段：有配文用配文；纯图片（正文=[MessageContentSentinels.IMAGE_PLACEHOLDER]）
 * 则用与「不带图语义占位」同一句措辞，避免把 `[图片]` 这个内部哨兵直接喂给模型。
 */
private fun imageMessageCaption(normalizedContent: String): String {
    val caption = normalizedContent.trim()
    return if (caption.isEmpty() || caption == MessageContentSentinels.IMAGE_PLACEHOLDER) {
        MemoryService.renderImageSemantics(caption, "")
    } else {
        caption
    }
}

internal fun appendConversationMessages(
    recentMessages: List<MessageEntity>,
    chatMessages: MutableList<ChatMessageDto>,
    character: CharacterEntity,
    /** 宠物名（M11，1:1 iOS `character?.pet?.name`）；null=无宠物，格式化时回退 "宠物"。 */
    petName: String?,
    userProfile: UserProfileEntity?,
    isCurrentlyInOfflineMode: Boolean,
    strings: PromptStrings,
    customStickers: List<CustomStickerEntity> = emptyList(),
    allowStickers: Boolean = true,
    audioInputEnabled: Boolean = false,
    /** messageUUID → 已预编码的裸 base64 WAV（编码在调用方的 IO 线程完成，不压主线程）。 */
    audioAttachments: Map<String, String> = emptyMap(),
    /** 路由配置是否支持视觉；false → 图片消息一律走语义占位文本（用户仍可正常发图·拍板②）。 */
    visionEnabled: Boolean = false,
    /**
     * messageUUID → 图片 data URI（`data:image/jpeg;base64,…`），调用方 IO 线程预读编码。
     * **只含最近若干张**（拍板①）——不在表里的历史图自动退化为「发送了一张图片：{摘要}」语义占位，
     * 语义不断链而 token 不随聊天时长线性膨胀。
     */
    imageAttachments: Map<String, String> = emptyMap(),
    /** 当前真实时间（用于历史时间分割线）；null=沉浸 / 线下模式不插分割线（由调用方门控）。 */
    now: Instant? = null,
    /** 记忆改造二期·部件④ 见面时间线注记（图纸 §3.1）：该角色全部见面档案行（注记端自筛跨度 + 上限 5）；
     *  空 / now=null → 零注记。与分割线共用 now 门控（见面 / 通话 / 忙碌场景 now=null → 不产注记）。 */
    meetingTimeline: List<OfflineMeetingMemoryEntity> = emptyList(),
    /** 引用一期（图纸 §3.1）：quotedMessageUUID → 被引用消息的时间戳 + 原始正文，调用方经
     *  `MessageRepository.quotedRefs` 预取一次。缺席（含默认空表）→ 引用行走无锚形态 + 回退落库快照，
     *  行为与接线前逐字相同，故不接预取的路径零改动。 */
    quotedRefs: Map<String, QuotedMessageRef> = emptyMap(),
) {
    var pendingAssistant = mutableListOf<String>()
    var pendingUser = mutableListOf<String>()
    var pendingPet = mutableListOf<String>()

    // 红包系统事件文案指名（图纸一 R1 承接·人称=角色「你」+ 用户名·空昵称回退 pb_user_fallback）：函数级解析一次，供下方 system-event 分支。
    val resolvedUserName = userProfile?.nickname?.takeIf { it.isNotEmpty() } ?: strings.s(R.string.pb_user_fallback)

    fun flushUser() {
        if (pendingUser.isEmpty()) return
        chatMessages.add(ChatMessageDto(role = PromptBuilder.ROLE_USER, content = pendingUser.joinToString("\n\n")))
        pendingUser = mutableListOf()
    }
    fun flushAssistant() {
        if (pendingAssistant.isEmpty()) return
        chatMessages.add(ChatMessageDto(role = PromptBuilder.ROLE_ASSISTANT, content = pendingAssistant.joinToString("\n\n")))
        pendingAssistant = mutableListOf()
    }
    fun flushPet() {
        if (pendingPet.isEmpty()) return
        chatMessages.add(ChatMessageDto(role = PromptBuilder.ROLE_USER, content = pendingPet.joinToString("\n")))
        pendingPet = mutableListOf()
    }

    // 时间分割线（Fable-5 时间感知）：相邻消息间隔够久 / 跨天处插一条独立 system 旁白，把按 role 合并时
    // 丢弃的逐条时刻还给 LLM。now=null（沉浸 / 线下模式，由调用方门控）时整体关闭。详见 [HistoryTimeDivider]。
    val dividerZone = ZoneId.systemDefault()
    var previousTimestamp: Long? = null
    val historyStartIndex = chatMessages.size  // A1：清理悬空分割线时不越界到本函数之前的前置内容。

    // 场边界注记（时间感知三期 §3.3）：主循环前预扫一遍时间戳，算出全部「新的一场」的下标
    //（判据单源 = [TimeAnchorFormatter.gapTier] >= MOST_OF_DAY；null 与 FEW_HOURS 都算同一场），
    // 只给**最后 2 个**加长版换算注记——20 轮历史横跨 10 天会有 10 个边界，全插会淹没提示词，
    // 而模型主要接着最后一场往下演。now=null（非在线聊天）时整体跳过，恒空。
    val regroundingIndices: Set<Int> = if (now == null) {
        emptySet()
    } else {
        val boundaries = mutableListOf<Int>()
        for (i in 1 until recentMessages.size) {
            val tier = TimeAnchorFormatter.gapTier(
                recentMessages[i - 1].timestamp,
                recentMessages[i].timestamp,
                dividerZone,
            )
            if (tier != null && tier >= TimeAnchorFormatter.GapTier.MOST_OF_DAY) boundaries.add(i)
        }
        boundaries.takeLast(2).toSet()
    }

    // 见面时间线注记（记忆改造二期·部件④·§3.1-C）：now 非空 + 有档案行 + 有历史时，先算好本窗口跨度内的候选见面
    // （selectEligible 自筛 kind/跨度/上限 5）；遍历时在相邻两条消息的时间缝里逐条升序发（发射后移除，每行只发一次）。
    val meetingAnnotations: MutableList<OfflineMeetingMemoryEntity> =
        if (now != null && meetingTimeline.isNotEmpty() && recentMessages.isNotEmpty()) {
            MeetingTimelineAnnotation.selectEligible(
                meetingTimeline,
                firstTs = recentMessages.first().timestamp,
                lastTs = recentMessages.last().timestamp,
            ).toMutableList()
        } else {
            mutableListOf()
        }

    // K8（2026-07-12 性能线程专项）：uuid→短别名反查表循环外建一次——原先每条 assistant 历史都重建整表
    //（O(消息数×表情数)）。输出与逐条建表版逐字节同（StickerServiceTest 等价锁）。
    val uuidToAlias = StickerService.buildUuidToAliasMap(customStickers)

    for ((messageIndex, message) in recentMessages.withIndex()) {
        if (now != null) {
            // §3.1-C：注记在前、分割线在后。取落在 (previousTimestamp, message.timestamp) 开区间的见面（首条 null 跳过），
            // 按升序逐条 flush 各 bucket 后发 system 注记行；每行发射后从待发集合移除。
            val prevTs = previousTimestamp
            if (prevTs != null && meetingAnnotations.isNotEmpty()) {
                val annotationIterator = meetingAnnotations.iterator()
                while (annotationIterator.hasNext()) {
                    val row = annotationIterator.next()
                    if (row.startedAtMillis > prevTs && row.startedAtMillis < message.timestamp) {
                        flushAssistant()
                        flushUser()
                        flushPet()
                        chatMessages.add(
                            ChatMessageDto(
                                role = PromptBuilder.ROLE_SYSTEM,
                                content = MeetingTimelineAnnotation.lineFor(row, now, dividerZone),
                            ),
                        )
                        annotationIterator.remove()
                    }
                }
            }
            HistoryTimeDivider.lineFor(
                message.timestamp,
                previousTimestamp,
                now,
                dividerZone,
                withRegrounding = messageIndex in regroundingIndices,
            )?.let { divider ->
                flushAssistant()
                flushUser()
                flushPet()
                chatMessages.add(ChatMessageDto(role = PromptBuilder.ROLE_SYSTEM, content = divider))
            }
            previousTimestamp = message.timestamp
        }
        // TODO(M16): typedContent.llmRepresentation 替换结构化卡片；当前其余皆 plainText → 用原文。
        var normalizedContent = message.content
        // M09 礼物卡（P9.2b）：GIFT_CARD 消息 JSON → llmRepresentation 系统记录，永不暴露原始 JSON/金币给 LLM。
        // 批3 3-10：解析失败置空→整条跳过——旧实现 ?.let 失败时 normalizedContent 保持原始 JSON（含 cost）直漏 LLM；
        // 与 FUTURE_MEETING 两卡、messageLlmSafeText 的「宁缺勿漏」失败语义统一。
        if (message.messageKindRaw == MessageKind.GIFT_CARD.raw) {
            normalizedContent = GiftCardJson.parse(message.content)?.llmRepresentation(character.name, resolvedUserName) ?: ""
        }
        // M10 红包卡（P9.3a）：RED_PACKET 消息 JSON → llmRepresentation，**永不露 amount**（节日/祝福段，神秘感）。
        // 批3 3-10：解析失败同上整条跳过（原始 JSON 含 amount 绝不入 prompt）。
        if (message.messageKindRaw == MessageKind.RED_PACKET.raw) {
            normalizedContent = RedPacketJson.parse(message.content)?.let {
                val festivalName = it.festivalId?.let { id -> FestivalCalendar.festivalById(id)?.name }
                it.llmRepresentation(festivalName)
            } ?: ""
        }
        // 未来约定见面确认卡（结构化卡·assistant role）：JSON → llmRepresentation 系统记录，**绝不把约定原文 JSON
        //（含 invitation/tensionHint/appointmentUuid）喂给 LLM**，与 [MessageLlmSafeText.messageLlmSafeText] 同口径
        // （只露 时间/地点/活动）。解析失败置空 → 整条跳过（结构化卡无明文语义，宁缺勿漏原文）。本路径与 MessageLlmSafeText
        // 各自内联脱敏，**新增结构化卡须两处同步**（单源化为欠账）。
        if (message.messageKindRaw == MessageKind.FUTURE_MEETING_PROPOSAL_CARD.raw) {
            normalizedContent = FutureMeetingProposalJson.parse(message.content)?.llmRepresentation(resolvedUserName) ?: ""
        }
        // 变更确认卡（结构化卡·同上脱敏口径）：JSON → llmRepresentation「[系统记录：和用户确认是否{改期/取消}…]」，
        // 绝不把原文 JSON（含 appointmentUuid/newScheduledAtMillis）喂 LLM。与 MessageLlmSafeText 同口径·两处同步。
        if (message.messageKindRaw == MessageKind.FUTURE_MEETING_CHANGE_CARD.raw) {
            normalizedContent = FutureMeetingChangeJson.parse(message.content)?.llmRepresentation(resolvedUserName) ?: ""
        }
        // 线下邀约卡（留痕改造 2026-08-31）：JSON → 带实时 responded 状态的系统记录行；invitation/tensionHint/
        // hiddenTension/原始 JSON 绝不进 LLM（与 FUTURE_MEETING 两卡同「宁缺勿漏」口径：解析失败/非邀约型 → 整条跳过）。
        if (message.messageKindRaw == MessageKind.OFFLINE_INVITE_CARD.raw) {
            normalizedContent = OfflineInviteJson.parse(message.content)?.llmRepresentation(character.name, resolvedUserName) ?: ""
        }
        // 线下离场标记（留痕改造）：普通聊天窗口保留并改写为一行系统记录（时长+回到线上）；标记原文
        //（含【重要】指令段）不进普通聊天 prompt。解析失败 → 整条跳过。
        if (message.messageKindRaw == MessageKind.OFFLINE_MARKER_END.raw) {
            normalizedContent = OfflineMarkerEndPayload.parse(message.content)?.llmRepresentation() ?: ""
        }

        // 引用消息：user 消息前注入引用上下文（引用一期·措辞与截断规格单源 [PromptQuoteLine]）。
        // 正文用**预取到的原始 content**（含 `[sticker:xxx]`），原消息已删则回退落库的显示串；时间锚与
        // 分割线共用 now 门控（now=null 的线下/通话/忙碌场景 → 无锚形态）。**本步必须排在下面的表情标签
        // 转语义之前**：引用到的表情靠那一步自动变成 `[非语言情绪：…]`（图纸 §0.2 决策三）。
        if (message.roleRaw == PromptBuilder.ROLE_USER && !message.quotedContent.isNullOrEmpty()) {
            val ref = message.quotedMessageUUID?.let { quotedRefs[it] }
            val quoteLine = PromptQuoteLine.build(
                userName = resolvedUserName,
                quotedContent = ref?.rawContent ?: message.quotedContent,
                quotedSenderRole = message.quotedSenderRole,
                quotedTimestampMillis = ref?.timestampMillis,
                now = now,
                zone = dividerZone,
            )
            normalizedContent = "$quoteLine\n$normalizedContent"
        }
        // 用户消息中的表情包标签转 AI 可理解的语义（1:1 iOS PromptBuilder.swift:566-568，无条件）。
        if (message.roleRaw == PromptBuilder.ROLE_USER) {
            normalizedContent = StickerService.convertStickerTagsToDescription(normalizedContent, customStickers)
        }

        // 系统事件消息（红包 accepted/rejected/expired，P9.3a · 1:1 iOS targetRoleForSystemEvent）：
        // 红包事件用角色第一人称视角文案；按「动作执行者」归属 user/assistant bucket，让文案主语与 role 对齐
        // （user 发→角色收/拒→assistant；character 发→用户做→user；过期归发起方）。老 case（relationshipChange 等，
        // 当前未用）→ [系统记录：emoji+title] 兜底归 user。解析失败保守归 user（与 iOS 老行为一致）。
        if (message.roleRaw == PromptBuilder.ROLE_SYSTEM) {
            val event = SystemEventJson.parse(message.content)
            if (event != null) {
                val typed = SystemEventType.fromRaw(event.eventType)
                normalizedContent = if (typed != null && typed.isRedPacketEvent) {
                    buildRedPacketLLMRepresentation(event, typed, resolvedUserName)
                } else {
                    "[系统记录：${event.emoji}${event.title}]"
                }
            } else if (message.messageKindRaw == MessageKind.SYSTEM_EVENT_CARD.raw) {
                // 批3 3-10：系统事件卡解析失败 → 整条跳过（旧行为原始 JSON 直接进 user 桶喂 LLM）。
                // 仅限定 SYSTEM_EVENT_CARD——其它 system 消息（若有明文语义）保持原样。
                continue
            }
            if (normalizedContent.isEmpty()) continue
            if (event != null && systemEventTargetIsAssistant(event)) {
                flushUser()
                flushPet()
                pendingAssistant.add(normalizedContent)
            } else {
                flushAssistant()
                flushPet()
                pendingUser.add(normalizedContent)
            }
            continue
        }

        if (message.roleRaw == PromptBuilder.ROLE_ASSISTANT) {
            if (normalizedContent.isEmpty()) continue

            // 宠物消息独立成 user role chat，前缀 [宠物·X 说]，不污染人设
            if (message.isPetMessage) {
                flushUser()
                flushAssistant()
                // 1:1 iOS PromptBuilder.swift:606 `character?.pet?.name ?? "宠物"`
                val resolvedPetName = petName ?: "宠物"
                pendingPet.add("[宠物·$resolvedPetName 说]: $normalizedContent")
                continue
            }

            normalizedContent = ReplyParser.decontaminateAssistantContent(normalizedContent)
            if (normalizedContent.isEmpty()) continue
            if (!isCurrentlyInOfflineMode && !message.isOfflineMode) {
                normalizedContent = ReplyParser.stripAssistantParentheticalNarration(normalizedContent)
                if (normalizedContent.isEmpty()) continue
            }
            // 开关开：UUID→短别名（防 AI 照抄 UUID）；开关关：全剥（防 few-shot 模仿）。1:1 iOS:628-633。
            if (allowStickers) {
                normalizedContent = StickerService.convertUUIDToAlias(normalizedContent, uuidToAlias)
            } else {
                normalizedContent = StickerService.stripAllStickerTags(normalizedContent)
                if (normalizedContent.isEmpty()) continue
            }
            flushUser()
            flushPet()
            pendingAssistant.add(normalizedContent)
        } else {
            flushAssistant()
            flushPet()
            // P13.4b 多模态：用户语音消息 + 路由配置支持音频输入 + 有音频字节 → 独立多模态消息
            //（文字段 = buildAudioPrompt 包装的转写参考 + input_audio 段），1:1 iOS PromptBuilder gate-1（:642-648）。
            // 否则（音频能力关 / 非语音 / 无字节）→ 纯文本累积（含 iOS gate-2「音频关 → 转写文字降级」）。
            val audioBase64 = audioAttachments[message.messageUUID]
            val imageDataUri = imageAttachments[message.messageUUID]
            val hasImage = message.imageRelativePath != null
            if (audioInputEnabled && message.isVoiceMessage && audioBase64 != null) {
                flushUser() // 多模态消息独立成条（不与前面纯文本合并），对齐 iOS flushPendingUser 后再 append。
                val audioPrompt = PromptBuilder.buildAudioPrompt(
                    transcript = normalizedContent,
                    promptPrefix = strings.s(R.string.pb_voice_msg_audio_prompt),
                    noTranscriptMarker = strings.s(R.string.pb_voice_msg_no_transcript),
                )
                chatMessages.add(
                    ChatMessageDto(
                        role = PromptBuilder.ROLE_USER,
                        contentParts = listOf(
                            ChatContentPart.Text(audioPrompt),
                            ChatContentPart.InputAudio(base64 = audioBase64, format = PromptBuilder.AUDIO_FORMAT_WAV),
                        ),
                    ),
                )
            } else if (visionEnabled && hasImage && imageDataUri != null) {
                // 图片多模态：与音频同构——独立成条，text 段在前、image 段在后（OpenAI 兼容口径）。
                // text 用用户配文；纯图片（正文=占位）则用与不带图时**同一句**语义文案，模型两种情况下读到的措辞一致。
                flushUser()
                chatMessages.add(
                    ChatMessageDto(
                        role = PromptBuilder.ROLE_USER,
                        contentParts = listOf(
                            ChatContentPart.Text(imageMessageCaption(normalizedContent)),
                            ChatContentPart.ImageUrl(imageDataUri),
                        ),
                    ),
                )
            } else {
                // 不挂图的三种情形（视觉关 / 超出最近 N 张窗口 / 文件读不到）→ 语义占位，绝不留裸 `[图片]`。
                val text = if (hasImage) {
                    MemoryService.renderImageSemantics(normalizedContent, message.mediaMemorySummary)
                } else {
                    normalizedContent
                }
                // 批3 3-10：解析失败置空的结构化卡整条跳过（user 侧礼物/红包卡），不给桶里塞空串。
                if (text.isNotEmpty()) pendingUser.add(text)
            }
        }
    }

    flushAssistant()
    flushUser()
    flushPet()

    // A1：清掉末尾「悬空」时间分割线——其后消息被 normalize 剥空/跳过时，分割线会指向不存在的消息。
    // 末尾的分割线一定无后继内容（合法分割线后必跟其标记的消息），故安全移除；连续多条一并清。
    if (now != null) {
        while (chatMessages.size > historyStartIndex) {
            val last = chatMessages.last()
            val text = last.content
            if (last.role == PromptBuilder.ROLE_SYSTEM && text != null && HistoryTimeDivider.isDivider(text)) {
                chatMessages.removeAt(chatMessages.lastIndex)
            } else {
                break
            }
        }
    }
}
