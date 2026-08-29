package com.situ.aichat.chat.image

import android.util.Log
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ChatContentPart
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.ThinkTagStripper
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图片理解摘要（1:1 iOS `ChatViewModel+Send.generateImageMemorySummaryIfNeeded`）。
 *
 * 用户发图落库后异步跑一次：用 [ApiFunction.IMAGE_UNDERSTANDING] 路由到的配置看一眼图，
 * 产出一两句中文描述写进 `MessageEntity.mediaMemorySummary`。**这份摘要是整条图片链路的语义骨干**——
 * 它同时喂给：
 * - 不带图时的语义占位（`renderImageSemantics` → 日记 / 日程 / 主动通知 / 故事 / 见面记忆五条旁路）；
 * - 超出「最近 N 张」窗口后退场的历史图（拍板①，让旧图在上下文里仍有语义而不烧 token）；
 * - 长期记忆与向量嵌入（`MemoryService.formatMessages` / `VectorMemoryService`）。
 *
 * 失败一律落 [FALLBACK_SUMMARY]（空串）：消费端认得空摘要、会自然产出「发送了一张图片」——
 * 拿不到真描述时这就是正确表示，绝不能塞一句会被再套一层的话（见该常量说明）。
 * 路由配置无视觉能力时直接兜底，不浪费一次调用。
 */
@Singleton
class ImageMemorySummaryService @Inject constructor(
    private val apiConfigRepo: ApiConfigRepository,
    private val messageDao: MessageDao,
    private val contextLog: ContextLogService,
) {

    /**
     * 为一条已落库的图片消息生成摘要并回填。调用方 fire-and-forget（不阻塞发送与回复）。
     * @return 实际写入的摘要文本（便于测试断言；调用方通常忽略）。
     */
    suspend fun summarize(messageUuid: String, imagePath: String, characterName: String): String {
        val summary = generate(imagePath, characterName)
        messageDao.updateMediaMemorySummary(messageUuid, summary)
        return summary
    }

    private suspend fun generate(imagePath: String, characterName: String): String {
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.IMAGE_UNDERSTANDING)
            ?: return FALLBACK_SUMMARY
        if (!config.visionEnabled) return FALLBACK_SUMMARY
        val dataUri = ContentImageStore.loadAsDataUri(imagePath) ?: return FALLBACK_SUMMARY
        return try {
            val raw = contextLog.completion(
                source = LogSource.IMAGE_UNDERSTANDING,
                characterName = characterName,
                config = config,
                messages = listOf(
                    ChatMessageDto(role = ROLE_SYSTEM, content = SYSTEM_PROMPT),
                    ChatMessageDto(
                        role = ROLE_USER,
                        contentParts = listOf(
                            ChatContentPart.Text(USER_PROMPT),
                            ChatContentPart.ImageUrl(dataUri),
                        ),
                    ),
                ),
                temperature = 0.3,
            )
            ThinkTagStripper.strip(raw).trim().takeIf { it.isNotEmpty() } ?: FALLBACK_SUMMARY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "图片理解失败，退兜底摘要: ${e.message}")
            FALLBACK_SUMMARY
        }
    }

    companion object {
        private const val TAG = "ImageMemorySummary"
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"

        /**
         * 生成失败时的兜底 = **空串**，而不是一句话。
         *
         * iOS 原版这里写的是「用户发送了一张图片」，照搬会出两个问题：
         * ① 消费端 `renderImageSemantics` 对「正文=占位」的情形产出「发送了一张图片：{摘要}」，
         *    把整句摘要套进去就成了「发送了一张图片：用户发送了一张图片」——叠字，且会永久写进
         *    长期记忆与向量库；
         * ② 「用户」是本项目已全面消灭的通用码（人称指名统一），不该从这个口子放回来。
         *
         * 空串本身就是消费端认得的「没有摘要」，它会自然产出「发送了一张图片」——正是想要的那句。
         */
        const val FALLBACK_SUMMARY = ""

        // 只描述、不评价、不揣测意图——这段文本会进长期记忆，带上模型的主观发挥会污染人设。
        private const val SYSTEM_PROMPT =
            "你是图片描述助手。用一到两句中文客观描述图片里有什么（人物、场景、物品、氛围）。" +
                "不要评价、不要揣测发图人的意图、不要加称呼或问候，直接给描述。"
        private const val USER_PROMPT = "描述这张图片。"
    }
}
