package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.util.AudioStore
import com.situ.aichat.util.ContentImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一回合的多模态附件预取（自 [AssistantTurnEngine] 抽出·只搬不改——引擎已在拆分账本挂红号，
 * 新功能只许加接线不许加逻辑体）。
 *
 * 两件事共用同一条纪律：**读盘 + base64 全在 [Dispatchers.Default] 上**。回合编排跑在
 * viewModelScope(Main)，一条语音几十 KB 尚可忍，一张 1568px 图 base64 后约 270KB，
 * 多张叠加足以在中低端机上掉帧。
 */
internal object TurnMediaAttachments {

    /**
     * 一次请求最多随图重发的**用户图片张数**（拍板①）。更早的图退化为
     * 「发送了一张图片：{摘要}」语义占位。
     *
     * 取 3 的理由：足够覆盖「刚发的一组照片」这种真实对话单元，又把每轮图片开销钉死在常数上——
     * 一张图各家计费约 1k–2.4k token，500 条窗口里全量重发会随聊天时长线性烧钱。
     * （RikkaHub / Cherry Studio / LobeChat 都是全量重发；我们敢不这么做，是因为有它们没有的
     * `mediaMemorySummary`，旧图退场后语义并不断链。）
     */
    const val MAX_ATTACHED_IMAGES = 3

    /**
     * 窗口内「用户语音消息」的音频（messageUUID → 裸 base64 WAV）。
     * 仅 user 语音；助手 TTS 语音不喂回模型（1:1 iOS preEncodedMedia 只 pre-encode role==.user）。
     * 配置不支持音频输入 → 空表，语音消息走端侧转写的纯文本。
     */
    suspend fun audio(history: List<MessageEntity>, config: ApiConfigValues): Map<String, String> {
        if (!config.audioInputEnabled) return emptyMap()
        return withContext(Dispatchers.Default) {
            history.filter { it.roleRaw == "user" && it.isVoiceMessage && it.audioRelativePath != null }
                .mapNotNull { msg ->
                    AudioStore.load(msg.audioRelativePath)?.let { msg.messageUUID to PromptBuilder.encodeWavBase64(it) }
                }
                .toMap()
        }
    }

    /**
     * **最近 [MAX_ATTACHED_IMAGES] 张**「会真的进本轮提示词」的用户图片（messageUUID → data URI）。
     * 配置不支持视觉 → 空表；没被选中的历史图由 PromptBuilderHistory 退化为语义占位。
     *
     * 两条与朴素写法不同、但必要的纪律：
     * 1. **候选集要与提示词窗口口径一致**（[promptEligible]）。装配侧 `PromptBuilderWindow` 在线上模式
     *    会整片剔除见面消息，而这里拿到的是未过滤的 500 条全量历史——照直取「最近 3 张」会让见面里发的
     *    照片吃掉全部名额，结果是：那 3 张压根不进提示词，而窗口内本可挂的线上照片却退成了占位，
     *    还白编码几百 KB。
     * 2. **读不到的文件不占名额**：先 take 后 mapNotNull 的写法下，最近 3 张里若有 2 张文件已丢失
     *    （备份未带图 / 用户清理），实际只挂 1 张且更早的可用图不补位——与「最近 3 张」的承诺不符。
     *    这里改成逐张尝试、凑满为止。
     */
    suspend fun images(
        history: List<MessageEntity>,
        config: ApiConfigValues,
        inOfflineMode: Boolean,
        currentOfflineSessionId: String?,
    ): Map<String, String> {
        if (!config.visionEnabled) return emptyMap()
        return withContext(Dispatchers.Default) {
            val picked = LinkedHashMap<String, String>()
            for (msg in selectImageCandidates(history, inOfflineMode, currentOfflineSessionId)) {
                if (picked.size >= MAX_ATTACHED_IMAGES) break
                ContentImageStore.loadAsDataUri(msg.imageRelativePath)?.let { picked[msg.messageUUID] = it }
            }
            picked
        }
    }

    /**
     * 候选图片消息，**从新到旧**（纯函数·便于单测，与产线共用同一份谓词——不许在测试里复制一份）。
     * 只含用户侧、有图、且会进本轮提示词窗口的消息。
     */
    internal fun selectImageCandidates(
        history: List<MessageEntity>,
        inOfflineMode: Boolean,
        currentOfflineSessionId: String?,
    ): List<MessageEntity> = history.asReversed().filter { msg ->
        msg.roleRaw == "user" &&
            msg.imageRelativePath != null &&
            promptEligible(msg, inOfflineMode, currentOfflineSessionId)
    }

    /**
     * 本轮合并等待窗覆盖的用户消息 uuid（= **上一条 assistant 之后**的全部 user 消息·纯函数）。
     *
     * 媒体降级重试的提示文案据此判断「被剥掉的图是不是这一轮发的」，而不是拿窗口里任意一张历史图说事：
     * 合并等待窗会把「先发一张图、再补一句话」并成一轮，此时若照直找「最后一条带图的 user 消息」，
     * 会挑到上一轮甚至更早的那张，提示语就指错了对象。
     *
     * 边界（从规格反推，非实现描述）：上一条就是 assistant → 空集（本轮本就无图可剥，该退回语音文案）；
     * 历史为空 → 空集；线下模式下 `roleRaw` 语义不变，故同样成立。
     *
     * 放这里而不是留在 [AssistantTurnEngine]：引擎是 REDLINES §8 点名的三大户之一，**只许 +接线不许 +逻辑体**。
     */
    internal fun turnUserMessageUuids(history: List<MessageEntity>): Set<String> =
        history.asReversed()
            .takeWhile { it.roleRaw != "assistant" }
            .filter { it.roleRaw == "user" }
            .map { it.messageUUID }
            .toSet()

    /**
     * 该消息会不会进本轮提示词窗口——镜像 `PromptBuilderWindow` 的线上/线下分流谓词。
     *
     * 只镜像「见面消息分流」这一层；装配侧还会按短期记忆长度做轮数截断，那部分依赖本函数拿不到的设置，
     * 故仍可能出现「候选在窗口外」的残差（后果仅是白编码一张图，不会错挂）。见面分流是量级最大的一层，
     * 也是唯一会**整片**吃掉名额的一层。
     */
    private fun promptEligible(
        msg: MessageEntity,
        inOfflineMode: Boolean,
        currentOfflineSessionId: String?,
    ): Boolean = if (inOfflineMode) {
        !msg.isOfflineMode || (currentOfflineSessionId != null && msg.offlineSessionId == currentOfflineSessionId)
    } else {
        !msg.isOfflineMode
    }
}
