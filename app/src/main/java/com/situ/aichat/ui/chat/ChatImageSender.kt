package com.situ.aichat.ui.chat

import android.content.Context
import android.net.Uri
import com.situ.aichat.R
import com.situ.aichat.chat.image.ImageMemorySummaryService
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageContentSentinels
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.offline.outgoingOfflineSessionId
import com.situ.aichat.util.ContentImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 发图落库链（自 [AssistantTurnController] 抽出——加进去会把控制器顶过 600 行硬上限）。
 *
 * 一次「选完即发」（拍板③）做三件事：逐张落盘（EXIF 摆正 + 长边 1568 + 512 缩略图）→ **每张一条消息**
 * （`PLAIN_TEXT` + 正文哨兵 + 侧车路径，照 iOS 口径不新增 MessageKind）→ 交回控制器受理入窗。
 *
 * 落库与受理仍走控制器的既有实现（[storeUserMessage] / [acceptStoredUserMessage] 注入），
 * 与文字/表情/语音三路**同一条路**：单事务 NonCancellable、见面标记、嵌入/火花/通知反馈、合并等待窗。
 */
internal class ChatImageSender(
    private val appContext: Context,
    private val conversationUuid: String,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val imageMemorySummaryService: ImageMemorySummaryService,
    private val errorFlow: MutableStateFlow<String?>,
    /** 控制器的单事务落库（消息 + 会话预览/保鲜）。 */
    private val storeUserMessage: suspend (MessageEntity, String, Boolean) -> Unit,
    /** 控制器的受理尾段（嵌入 + 火花 + 通知反馈 + 入合并等待窗）。 */
    private val acceptStoredUserMessage: suspend (MessageEntity, String) -> Unit,
    /** 摘要落库后按 uuid 重取最新实体并嵌入（图片向量必须等摘要，见循环里的说明）。 */
    private val embedImageMessage: suspend (String) -> Unit,
) {

    suspend fun send(scope: CoroutineScope, uris: List<Uri>) {
        val convo = conversationRepo.get(conversationUuid) ?: return
        val characterName = characterRepo.get(convo.characterUuid)?.name.orEmpty()
        val offlineSessionId = outgoingOfflineSessionId(convo.isInOfflineMode, convo.currentOfflineSessionId)
        var lastStored: MessageEntity? = null
        val pendingSummaries = mutableListOf<Pair<String, String>>() // uuid → 原图路径

        for (uri in uris) {
            val stored = ContentImageStore.saveWithThumbnail(appContext, uri)
            if (stored == null) {
                // 落盘失败（URI 失效 / 解不了码 / 空间不足）：明确告知并跳过这一张，其余照发。
                errorFlow.value = appContext.getString(R.string.chat_image_save_failed)
                continue
            }
            val message = MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = MessageContentSentinels.IMAGE_PLACEHOLDER,
                timestamp = System.currentTimeMillis(),
                imageRelativePath = stored.path,
                imageThumbnailRelativePath = stored.thumbnailPath,
                isOfflineMode = offlineSessionId != null,
                offlineSessionId = offlineSessionId,
            )
            storeUserMessage(message, MessageContentSentinels.IMAGE_PLACEHOLDER, offlineSessionId != null)
            lastStored = message
            pendingSummaries += message.messageUUID to stored.path
        }

        // 图片理解摘要 fire-and-forget，但**串行**跑：一次选 9 张若各起一个协程，就是 9 个并发视觉请求、
        // 每个带几百 KB 的 base64，瞬时内存与配额都不好看。摘要只喂「不带图时的语义占位」与长期记忆，
        // 晚几秒毫无影响，没有并发的必要。
        if (pendingSummaries.isNotEmpty()) {
            scope.launch {
                for ((uuid, path) in pendingSummaries) {
                    runCatching { imageMemorySummaryService.summarize(uuid, path, characterName) }
                        .onFailure { android.util.Log.w(TAG, "图片摘要失败(不影响主流程): ${it.message}") }
                    // 摘要落库后**才**嵌入这一条（VectorMemoryService 对「有图且无摘要」有意推迟）：
                    // 这样每条图片消息恰好嵌一次、且嵌的是带描述那版；同批多图也每条都嵌，
                    // 不再是「受理只跑最后一张 → 最后那张永远最差」。
                    // ⚠️ 这里走的是 `embedImageMessageAfterSummary`（**跳过推迟闸**）而不是 `embedMessageIfNeeded`：
                    // 摘要失败的兜底写的就是空串，而推迟谓词判的正是 isBlank——用后者的话，
                    // 契约 §B5 那三条兜底路径上这句 100% 空转，消息要等下次冷启动回填才进索引（R3 🟡-7）。
                    runCatching { embedImageMessage(uuid) }
                        .onFailure { android.util.Log.w(TAG, "图片嵌入失败(不影响主流程): ${it.message}") }
                }
            }
        }

        // 受理只做一次（多张 = 一个发送动作）：入窗一次，由合并等待窗并成一轮回复。
        lastStored?.let { acceptStoredUserMessage(it, MessageContentSentinels.IMAGE_PLACEHOLDER) }
    }

    private companion object {
        const val TAG = "ChatImageSender"
    }
}
