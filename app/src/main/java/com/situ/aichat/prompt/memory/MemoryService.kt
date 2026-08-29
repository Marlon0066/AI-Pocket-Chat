package com.situ.aichat.prompt.memory

import android.text.format.DateFormat
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageContentSentinels
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.prompt.messageLlmSafeText
import com.situ.aichat.sticker.BuiltInStickerCatalog
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.util.ThinkTagStripper
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1:1 port of iOS `MemoryService` (Services/MemoryService.swift) — 滚动 LLM 摘要记忆。
 *
 * 纯工具部分（模板/宏/压缩策略/渲染/时间格式/触发判定）放在 [companion object]，对齐 iOS 的 `nonisolated static`；
 * 需要查库的部分（窗口外收集、窗口截断、游标推进、未总结轮数统计）是注入了 DAO 的实例方法，对齐 iOS 接受 `modelContext` 的静态函数。
 *
 * ⚠️ 紧耦合：[DEFAULT_EXTRACTION_PROMPT] 产出的格式（`【长期事实】/【近期经历】` + `[YYYY-MM-DD]`）被
 * `DirtyMessageDetector`（防复读）与 PromptBuilder 注入禁令段硬编码引用，改任一格式必须三处同步（spec M05 §1.3）。
 *
 * [renderMemoryContent] 是摘要 `formatMessages` 与向量嵌入（[VectorMemoryService]）共用的**唯一**渲染入口，
 * 保证同一条消息在不同路径下渲染成相同文本（spec M05 §4#8）。
 */
@Singleton
class MemoryService @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contextLog: ContextLogService,
) {

    // MARK: - 摘要生成（滚动 LLM 压缩）

    /**
     * 生成/更新角色记忆摘要。空消息 → 返回已有记忆。`temperature=0.4`；DeepSeek 偶发空响应 → 等 200ms 重试 1 次，
     * 双空返回 ""（由 [MemorySummaryCoordinator] 抛 EmptyResponse 进入 5 分钟短冷却，而非 1 小时硬冷却）。
     *
     * 截断防线（记忆护栏 G2）：finish_reason=="length"（输出被模型输出上限掐断）视同空响应——半份记忆的尾部
     * 恰是最新的「近期经历」，绝不许过闸写入；重试 1 次仍截断则返 ""，走同一条短冷却。
     */
    suspend fun generateMemorySummary(
        existingMemory: String,
        newMessages: List<MessageEntity>,
        config: ApiConfigValues,
        maxLength: Int,
        customPrompt: String = "",
        characterName: String = "",
        userName: String = "",
        progressiveCompressionEnabled: Boolean = false,
        extraMaterial: String = "",
    ): String {
        if (newMessages.isEmpty()) return existingMemory

        // 第三人称指名兜底（2026-07-14·D-4）：无昵称 userName 空 → 回退「用户」（对齐 StructuredMemoryService:72 先例）、
        // characterName 防御性空 → 回退「角色」。单点计算后同时喂给 formatMessages 标签与 applyExtractionMacros 的
        // {{char}}/{{user}} 宏——标签与宏必然同名，绝无「标签是名字、宏是空」的错位。不设「禁『用户』二字」硬闸（D-4）。
        val safeUser = userName.ifBlank { "用户" }
        val safeChar = characterName.ifBlank { "角色" }
        // 消化素材（记忆改造一期·图纸 §3.6）：非聊天素材经 {{聊天记录}} 宏与聊天记录一并进模板，用户自定义模板无需感知新宏。
        var conversationText = formatMessages(newMessages, userLabel = safeUser, charLabel = safeChar)
        if (extraMaterial.isNotBlank()) conversationText += "\n\n" + extraMaterial
        val now = formatTimestamp(System.currentTimeMillis())
        val template = customPrompt.ifEmpty { DEFAULT_EXTRACTION_PROMPT }
        val compressionMode = resolveCompressionMode(
            hasCustomPrompt = customPrompt.isNotEmpty(),
            progressiveEnabled = progressiveCompressionEnabled,
        )
        val systemPrompt = applyExtractionMacros(
            template,
            conversationText = conversationText,
            existingMemory = existingMemory,
            now = now,
            maxLength = maxLength,
            characterName = safeChar,
            userName = safeUser,
            currentLength = cjkLength(existingMemory),
            compressionMode = compressionMode,
        )
        val messages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = "请提取并合并记忆。"),
        )

        for (attempt in 1..2) {
            var finishReason: String? = null
            val raw = contextLog.completion(
                source = LogSource.MEMORY_SUMMARY,
                characterName = characterName,
                config = config,
                messages = messages,
                temperature = 0.4,
                onFinishReason = { finishReason = it },
            )
            // 返回剥净思考标签的 candidate 而非 raw：非流式 LlmClient.completion 直接返回 message.content
            // 不剥内联 <think>（只有流式路径经 ThinkTagParser），返回 raw 会把开源模型的思考文本固化进
            // memorySummary——污染后续注入、虚增 cjkLength 误触发超长护栏。iOS 原版此处返回 raw 属同源缺陷，不对齐。
            val candidate = strippingThinkingTags(raw).trim()
            // 合并双守卫：截断（记忆护栏 G2·finish_reason 判据取 LlmClient.isLengthTruncated 单源）视同空响应；
            // 非截断且剥净非空才采纳（返回 candidate 而非 raw·think 剥离专项）。
            if (!LlmClient.isLengthTruncated(finishReason) && candidate.isNotEmpty()) return candidate
            if (attempt < 2) delay(200)
        }
        return ""
    }

    /**
     * 记忆超长自救（批1 1-4·防无限增长）：把整份记忆压缩到 [maxLength] 字以内的一次性 LLM 调用。
     * 由 [MemorySummaryCoordinator] 在新记忆超软目标时调用；返回 trim 后文本（仍可能超长，由调用方硬校验）。
     * 截断防线（记忆护栏 G2）：finish_reason=="length" 返 ""——调用方的垃圾短输出闸自然不采纳半份结果。
     */
    suspend fun compressMemory(
        memory: String,
        config: ApiConfigValues,
        maxLength: Int,
        characterName: String,
    ): String {
        val messages = listOf(
            ChatMessageDto(role = "system", content = COMPRESS_PROMPT.replace("{{最大字数}}", maxLength.toString())),
            ChatMessageDto(role = "user", content = memory),
        )
        var finishReason: String? = null
        val raw = contextLog.completion(
            source = LogSource.MEMORY_SUMMARY,
            characterName = characterName,
            config = config,
            messages = messages,
            temperature = 0.2,
            onFinishReason = { finishReason = it },
        )
        if (LlmClient.isLengthTruncated(finishReason)) return ""
        return strippingThinkingTags(raw).trim()
    }

    // MARK: - 窗口外未总结消息收集

    /**
     * 收集该角色跨所有对话中"短期记忆窗口之外"的未总结消息（对齐 iOS collectMessagesOutsideWindow）。
     * 当前对话排除窗口内（已作为短期记忆发出）；其它对话取游标之后的全部未总结消息。
     *
     * 游标谓词已下推进 SQL（批1修复）：旧实现取「全会话最旧 500 条」再在 Kotlin 侧过滤游标，会话超 500 条后
     * 最旧 500 条恒在游标之前 → 收集恒空 → 摘要永久停摆。现在每次取「游标之后最旧 500 条」，积压分轮消化。
     * 线下叙事消息由 DAO 谓词隔离（专项见面记忆管线负责，不进常规摘要）。
     */
    suspend fun collectMessagesOutsideWindow(
        characterUuid: String,
        currentConversationUuid: String,
        shortTermLength: Int,
    ): List<MessageEntity> {
        val windowCutoff = shortTermWindowCutoffMillis(currentConversationUuid, shortTermLength)
        val conversations = conversationDao.getByCharacter(characterUuid)
        val result = ArrayList<MessageEntity>()
        for (conv in conversations) {
            val isCurrent = conv.uuid == currentConversationUuid
            val messages = messageDao.summarizableMessages(conv.uuid, conv.lastSummarizedMessageDate, SUMMARY_FETCH_LIMIT)
            for (msg in messages) {
                if (isCurrent) {
                    if (windowCutoff == null) continue
                    if (msg.timestamp >= windowCutoff) continue
                }
                result.add(msg)
            }
        }
        return result.sortedBy { it.timestamp }
    }

    /** 第 [shortTermLength] 近的非空 user 消息时间戳；不足 N 条 → null（全部在窗口内，不总结）。 */
    suspend fun shortTermWindowCutoffMillis(conversationUuid: String, shortTermLength: Int): Long? {
        val timestamps = messageDao.recentUserTimestamps(conversationUuid, shortTermLength)
        if (timestamps.size < shortTermLength) return null
        return timestamps.last() // DESC 取回，最后一个 = 第 N 近（最旧）
    }

    /**
     * 标记总结完成（批1修复）：按「实际喂入摘要的批次」推进各会话游标到该会话最后一条喂入消息的时间戳。
     * 旧实现推进到「窗口起点前最后一条 /（其它会话）全会话最新一条」——批次被 [SUMMARY_FETCH_LIMIT] 截断时
     * 会把未喂入的中段消息永久跳过（游标过冲）。列级 UPDATE 防陈旧快照覆写并发列。
     * 未贡献消息的会话游标不动；喂入批里被 formatMessages 过滤的脏消息/卡片也计入推进（它们永远不可总结）。
     */
    suspend fun markSummarized(fedMessages: List<MessageEntity>) {
        fedMessages.groupBy { it.conversationUuid }.forEach { (convUuid, msgs) ->
            conversationDao.updateSummaryCursor(convUuid, msgs.maxOf { it.timestamp })
        }
    }

    /**
     * 当前对话基准窗口外、尚未总结的轮数（user 消息数）。用于动态扩展短期记忆窗口，消除记忆真空。
     * 只统计当前对话（其它对话不影响当前窗口大小）。对齐 iOS countUnsummarizedRoundsOutsideBaseWindow。
     */
    suspend fun countUnsummarizedRoundsOutsideBaseWindow(
        currentConversation: ConversationEntity,
        baseShortTermLength: Int,
    ): Int {
        val windowCutoff = shortTermWindowCutoffMillis(currentConversation.uuid, baseShortTermLength) ?: return 0
        return messageDao.countUnsummarizedUserRounds(
            conversationUuid = currentConversation.uuid,
            windowCutoff = windowCutoff,
            summaryCutoff = currentConversation.lastSummarizedMessageDate,
        )
    }

    companion object {
        private const val SUMMARY_FETCH_LIMIT = 500
        private const val ROLE_USER = "user"

        /** 字数：codePoint 计数，近似 Swift `String.count`（中文 BMP 两端一致，emoji/组合字符更准）。见 spec M05 §4#4。 */
        fun cjkLength(s: String): Int = s.codePointCount(0, s.length)

        /** 轮数 = user 消息数。 */
        fun countRounds(messages: List<MessageEntity>): Int = messages.count { it.roleRaw == ROLE_USER }

        // MARK: - 消息格式化 / 渲染（摘要 + 向量嵌入共用的唯一入口）

        /**
         * 将消息数组格式化为用于 LLM 分析的文本。统一剥离脏消息 + 邀约卡片，避免污染下游 LLM 形成自我强化毒循环
         * （spec M05 §1.3 / §4#8）。格式 `[时间] 用户|角色：内容`。
         *
         * 第三人称指名（2026-07-14·D-3）：[userLabel]/[charLabel] 默认 `用户`/`角色`——**不传时输出与旧版字节一致**，
         * 保护其余 6 个消费者（对账/成长/关系/前情/见面/场景）。仅滚动摘要（[generateMemorySummary]）与结构化提取
         * （[StructuredMemoryService]）两处传真实名字，让对话记录说话人直接渲染成名字（`小明：`/`夏晴子：`）。
         */
        fun formatMessages(
            messages: List<MessageEntity>,
            userLabel: String = "用户",
            charLabel: String = "角色",
        ): String {
            return messages
                .mapNotNull { msg ->
                    val kind = MessageKind.fromRaw(msg.messageKindRaw)
                    if (DirtyMessageDetector.isDirty(msg.content, kind)) return@mapNotNull null
                    // 结构化卡脱敏单源：礼物/红包卡→无金额·通话/线下事件→丢弃，杜绝原始 JSON/amount 固化进长期记忆
                    // （formatMessages 被记忆摘要/结构化记忆/成长/关系分析共用，一处收口全覆盖）。
                    val safe = messageLlmSafeText(msg, userLabel, charLabel) ?: return@mapNotNull null
                    val time = formatTimestamp(msg.timestamp)
                    val role = if (msg.roleRaw == ROLE_USER) userLabel else charLabel
                    // 图片语义已在 messageLlmSafeText 内完成（单源上移·见 MessageLlmSafeText 的 PLAIN_TEXT 分支），
                    // 这里只补表情包语义——两处都做会把「发送了一张图片：…」再当正文套一层。
                    "[$time] $role：${renderStickerSemantics(safe)}"
                }
                .joinToString("\n")
        }

        /**
         * 唯一渲染入口（对齐 iOS `renderMemoryContent`）：图片/表情包语义化，避免原始标签进入记忆/向量。
         * 任何代码都不应复制这段逻辑，否则同一条消息会在不同路径下渲染成不同文本，产生不同向量。
         */
        fun renderMemoryContent(content: String, mediaMemorySummary: String, hasImageAttachment: Boolean): String {
            if (hasImageAttachment) return renderImageSemantics(content, mediaMemorySummary)
            return renderStickerSemantics(content)
        }

        /**
         * 图片消息 → 有语义的文本（不带图时的**唯一**表示法）。
         * 有摘要就带上摘要（`mediaMemorySummary` = 图片理解产物），没有则只说「发送了一张图片」。
         * 与 [MessageContentSentinels.IMAGE_PLACEHOLDER] 强耦合：正文若就是那个占位则整体替换，
         * 否则保留用户配文并把图片作为附注挂上。
         */
        fun renderImageSemantics(content: String, mediaMemorySummary: String): String {
            val trimmedContent = content.trim()
            val trimmedMedia = mediaMemorySummary.trim()
            val contentIsPlaceholderOnly =
                trimmedContent.isEmpty() || trimmedContent == MessageContentSentinels.IMAGE_PLACEHOLDER
            if (trimmedMedia.isEmpty()) {
                if (contentIsPlaceholderOnly) return "发送了一张图片"
                return "$trimmedContent（并发送了一张图片）"
            }
            if (contentIsPlaceholderOnly) return "发送了一张图片：$trimmedMedia"
            return "$trimmedContent（图片内容：$trimmedMedia）"
        }

        /** 表情包标签转语义，避免原始标签进入记忆摘要/向量（1:1 iOS MemoryService.swift:464-470）。 */
        fun renderStickerSemantics(content: String): String {
            val trimmedContent = content.trim()
            if (StickerTagParser.isStickerOnly(trimmedContent)) {
                val ids = StickerTagParser.extractStickerIds(trimmedContent)
                val name = BuiltInStickerCatalog.byId[ids.firstOrNull() ?: ""]?.name ?: "表情包"
                return "发送了表情包「$name」"
            }
            return StickerTagParser.replaceStickerTagsForDisplay(trimmedContent)
        }

        /** 时间格式（对齐 iOS `DateFormatters.threadSafeDateYMDHM` 的 "yMdHm" 骨架）。 */
        fun formatTimestamp(millis: Long): String {
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMdHm")
            return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
        }

        // MARK: - 触发判定（纯函数，对齐 iOS memorySummaryTriggerDecision）

        /**
         * 触发顺序：① 失败短冷却 300s → ② 用户可调下限 [interval] → ③ 双轨（从未成功直接触发；
         * 否则成功冷却 1800s OR 窗口外累积 ≥ 30 条 user 消息 任一满足）。
         */
        fun summaryTriggerDecision(
            outsideRoundCount: Int,
            interval: Int,
            lastSuccessDate: Long?,
            lastFailureDate: Long?,
            now: Long,
        ): SummaryTriggerDecision {
            val failureCooldownMs = 300_000L     // 5 分钟
            val successCooldownMs = 1_800_000L   // 30 分钟
            val countTrack = 30                  // 30 条 user 消息

            if (lastFailureDate != null) {
                val elapsed = now - lastFailureDate
                if (elapsed < failureCooldownMs) {
                    return SummaryTriggerDecision.SkipFailureCooldown(((failureCooldownMs - elapsed) / 1000).toInt())
                }
            }

            if (outsideRoundCount < interval) return SummaryTriggerDecision.SkipBelowInterval

            if (lastSuccessDate == null) return SummaryTriggerDecision.Trigger

            val elapsed = now - lastSuccessDate
            val timeReady = elapsed >= successCooldownMs
            val countReady = outsideRoundCount >= countTrack
            return if (timeReady || countReady) {
                SummaryTriggerDecision.Trigger
            } else {
                SummaryTriggerDecision.SkipDualCooldown((elapsed / 1000).toInt())
            }
        }

        // MARK: - 提取 prompt 模板 + 宏 + 四级渐进压缩策略

        /**
         * 裁定「压缩策略」模式（2026-06-20，纯函数便于单测）：用户自定义了提取 prompt → [CompressionMode.NONE]
         * （开关让位，不塞预设话术）；否则按智能渐进压缩开关 [progressiveEnabled]：开→PROGRESSIVE / 关→FIXED_LIMIT。
         */
        fun resolveCompressionMode(hasCustomPrompt: Boolean, progressiveEnabled: Boolean): CompressionMode = when {
            hasCustomPrompt -> CompressionMode.NONE
            progressiveEnabled -> CompressionMode.PROGRESSIVE
            else -> CompressionMode.FIXED_LIMIT
        }

        /**
         * 对记忆提取提示词做宏替换。[compressionMode] 决定 {{压缩策略}} 宏内容（2026-06-20）；{{当前时间}} 宏
         * 保留替换以兼容用户自定义模板，默认模板已不含此宏（2026-06-20 移除规则1「当前时间」，改由消息时间戳标日期）。
         */
        fun applyExtractionMacros(
            template: String,
            conversationText: String,
            existingMemory: String,
            now: String,
            maxLength: Int,
            characterName: String,
            userName: String,
            currentLength: Int,
            compressionMode: CompressionMode,
        ): String {
            return template
                .replace("{{聊天记录}}", conversationText)
                .replace("{{已有记忆}}", existingMemory)
                .replace("{{当前时间}}", now)
                .replace("{{最大字数}}", maxLength.toString())
                .replace("{{当前字数}}", currentLength.toString())
                .replace("{{压缩策略}}", compressionStrategy(currentLength, maxLength, compressionMode))
                .replace("{{char}}", characterName)
                .replace("{{user}}", userName)
        }

        /**
         * 根据压缩模式 + 当前记忆字数生成「压缩策略」提示（2026-06-20 扩为三模式）：
         * - [CompressionMode.NONE]：留空（用户自定义提取 prompt 时让位，不往模板塞预设话术）。
         * - [CompressionMode.FIXED_LIMIT]：一句硬字数要求（智能渐进压缩关），如何取舍交给 LLM。
         * - [CompressionMode.PROGRESSIVE]：四级渐进话术（智能渐进压缩开，对齐 iOS compressionStrategy）。
         */
        private fun compressionStrategy(currentLength: Int, maxLength: Int, mode: CompressionMode): String {
            when (mode) {
                CompressionMode.NONE -> return ""
                CompressionMode.FIXED_LIMIT -> return "请将合并后的记忆控制在上限字数以内，如何精简取舍由你自行判断。"
                CompressionMode.PROGRESSIVE -> Unit // 落到下方四级渐进逻辑
            }
            if (maxLength <= 0) return "请完整记录所有重要信息。"
            if (currentLength == 0) return "这是首次记忆提取，请完整记录所有重要信息。"

            val ratio = currentLength.toDouble() / maxLength.toDouble()
            return when {
                ratio <= 0.5 ->
                    "当前记忆较短，空间充裕。两个区域都可以保留尽可能多的细节，自然记录即可。"
                ratio <= 0.75 ->
                    "空间已过半。「长期事实」区保持完整不动；「近期经历」区对较早的条目可适当缩短表述，但每天至少保留一条。"
                ratio <= 0.9 ->
                    "空间较紧张。「长期事实」区只精简表述，不删条目；「近期经历」区压缩每条长度（长句缩短句），合并连续多天的同类事件（如「[2026-04-05~04-07] 连续讨论工作」）。关键原则：宁可每条都短，也不要整天删掉。"
                else ->
                    "空间已接近上限（${maxLength}字）。「长期事实」区合并相似条目、精简措辞，但核心事实必须保留；「近期经历」区每天仅保留最核心的一句话，合并相似日期段。字数上限是硬性要求，必须严格控制在${maxLength}字以内。"
            }
        }

        /**
         * 剥 `<think>`/`<thinking>`（转发 [ThinkTagStripper]·单源，三条规则含孤闭合连前文删，见其 KDoc）。
         * 摘要（[generateMemorySummary]/[compressMemory]）返回值经它剥净后才落库；
         * 结构化记忆解析([StructuredMemoryService])用它清理开源模型混入的思考标签。
         * 剥空 = 纯思考响应，调用方须走失败/重试路径，绝不落库。
         */
        fun strippingThinkingTags(s: String): String = ThinkTagStripper.strip(s)

        /**
         * 默认记忆提取提示词模板（硬编码中文，对齐 iOS MemoryService.defaultExtractionPrompt）。
         *
         * ⚠️ 改动区块标题字样或日期格式时，必须同步 `DirtyMessageDetector.matchesMemoryFormatRepeat`
         * 与 PromptBuilder 的「Memory output format (strict)」注入禁令段，否则防复读防线失效（spec M05 §1.3）。
         */
        /**
         * 记忆压缩提示词（批1 1-4）：格式保持中立——不硬编码具体区域标题（本模板同时服务常规记忆与见面记忆，
         * 二者格式不同），只要求保留原有标题/日期/条目结构，与 [DEFAULT_EXTRACTION_PROMPT] 的耦合警告不冲突。
         */
        val COMPRESS_PROMPT: String = """
            你是记忆压缩助手。用户消息是一份角色记忆档案。请把它压缩到{{最大字数}}字以内：
            - 保持原有的区域标题、日期标记与条目格式不变
            - 合并相似条目、精简每条措辞（长句缩短句）
            - 宁可每条都短，也不要丢弃事实、日期或承诺
            只输出压缩后的完整记忆，不要任何解释或前后缀。
            """.trimIndent()

        val DEFAULT_EXTRACTION_PROMPT: String = """
            你是一个记忆提取助手。下面是{{char}}和{{user}}的对话记录，请从中提取关键记忆信息，分为「长期事实」和「近期经历」两个区域输出。（对话记录里说话人已用真实名字标注：{{user}}是对方，{{char}}是你正在为其保存记忆的角色。）

            ## 已有记忆
            {{已有记忆}}

            ## 最近对话内容
            {{聊天记录}}

            ## 输出格式（必须严格遵守）
            输出必须包含以下两个区域标题，各占一行：

            【长期事实】
            - {{user}}的稳定信息：喜好、职业、家庭、习惯、性格特点等
            - 每条一行，不需要日期标记
            - 这些信息长期有效，只在{{user}}明确改变时才更新

            【近期经历】
            - 具体的对话事件和互动经历
            - 每条一行，必须带日期标记，格式为「[YYYY-MM-DD] 内容」
            - 按日期从旧到新排列

            ## 规则
            1. 用简洁的第三人称记录，两个人一律用名字指代：对方写「{{user}}」，角色写「{{char}}」；不要用「用户」「角色」这类代称。
            2. 如果有已有记忆，将新记忆与旧记忆合并，去除重复；若已有记忆里还留着「用户」「角色」这类旧代称，合并时一并改写成对应的名字（{{user}} / {{char}}）。
            3. {{user}}明确改变了偏好时，更新「长期事实」区对应条目。
            4. 总字数严格控制在{{最大字数}}字以内（这是硬性上限，必须遵守）。
            5. 当前已有记忆约{{当前字数}}字，上限为{{最大字数}}字。{{压缩策略}}
            6. 约定的处理：进行中的约定（答应了还没做的事）由系统的约定清单单独管理，不要写进「长期事实」；已经兑现或已经取消的约定可以作为经历写进「近期经历」。

            ## 记忆连续性要求（针对「近期经历」区）
            - 在字数上限允许的前提下，尽可能覆盖每个有过对话的日期
            - 如果某天只是日常闲聊，用一句话概括即可，例如「[2026-04-08] 日常闲聊，聊了天气和晚饭」
            - 空间不足时，优先压缩每条的详细度（长句缩短句），而非删掉整天
            - 可合并连续多天的同类记忆（如「[2026-04-05~04-07] 连续讨论工作项目进展」）

            请输出合并后的完整记忆（必须包含「【长期事实】」和「【近期经历】」两个区域标题）：
            """.trimIndent()
    }
}

/**
 * 「压缩策略」话术模式（2026-06-20·智能渐进压缩）：
 * - [PROGRESSIVE]：四级渐进话术（开关开），随字数比例逐级收紧、合并日期、舍不得整天删。
 * - [FIXED_LIMIT]：一句硬字数要求（开关关），如何取舍交给 LLM。
 * - [NONE]：留空（用户自定义提取 prompt 时让位，不往模板塞预设话术）。
 * 三态都不在代码层截断存储——仅切换发给 LLM 的提取话术。
 */
enum class CompressionMode { PROGRESSIVE, FIXED_LIMIT, NONE }

/** 记忆总结触发判定结果（对齐 iOS MemorySummaryTriggerDecision）。 */
sealed interface SummaryTriggerDecision {
    data object Trigger : SummaryTriggerDecision

    /** 失败短冷却：5 分钟内不重试。 */
    data class SkipFailureCooldown(val remainingSeconds: Int) : SummaryTriggerDecision

    /** 用户可调下限未到（默认 10 轮）。 */
    data object SkipBelowInterval : SummaryTriggerDecision

    /** 双轨冷却：30 分钟时间轨未到 + 30 条消息数轨未到。 */
    data class SkipDualCooldown(val elapsedSeconds: Int) : SummaryTriggerDecision
}
