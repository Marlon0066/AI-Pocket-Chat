package com.situ.aichat.pet

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 宠物用品事件触发的聊天气泡服务（1:1 iOS `Services/PetChatBubbleService.swift`）。
 *
 * 用户使用消耗品 / 佩戴装扮后，在宠物所属角色的**最新未归档 conversation** 插一条 `isPetMessage=true` 的
 * .plainText 消息（本地随机文案，不调 LLM）。**节流**：每 conversation 每天最多 [DAILY_LIMIT]=3 条（防「连吃 5 袋
 * 饼干=5 条 spam」）。**失败兜底**：无 conversation / 已超限 → 静默返回 false，不让上层（[PetInventoryService]）失败
 * （聊天气泡只是锦上添花）。
 *
 * Android 适配：iOS @MainActor enum + SwiftData；这里 @Singleton + DAO（同 GiftMomentQueueService 风格），插消息走
 * [MessageDao.upsert]、更会话预览走 [ConversationRepository.recordLastMessage]（仅 lastMessage*，不动未读=iOS）。
 * `now`/`random` 注入便于确定性单测。文案池（[consumableTexts]/[equipTexts]）放 companion 且 internal。
 */
@Singleton
class PetChatBubbleService @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val conversationRepo: ConversationRepository,
) {

    /** 用户使用了一件消耗品后调用 · 触发宠物聊天反馈。成功插入 true；无会话/超限/失败 → false（静默）。 */
    suspend fun notifyConsumableUsed(
        itemName: String,
        characterUuid: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        random: Random = Random.Default,
    ): Boolean = tryInsertBubble(randomConsumableText(itemName, random), characterUuid, now, zone)

    /** 用户佩戴了一件装扮后调用 · 触发宠物聊天反馈。 */
    suspend fun notifyEquipped(
        itemName: String,
        characterUuid: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        random: Random = Random.Default,
    ): Boolean = tryInsertBubble(randomEquipText(itemName, random), characterUuid, now, zone)

    /** 节流 + 选会话 + 插消息 + 更新会话预览（1:1 iOS `tryInsertBubble`）。 */
    private suspend fun tryInsertBubble(
        text: String,
        characterUuid: String,
        now: Long,
        zone: ZoneId,
    ): Boolean {
        if (DAILY_LIMIT <= 0) return false
        val conversation = conversationDao.latestActiveForCharacter(characterUuid) ?: return false
        // 见面闸（卷一 A3）：会话正在线下见面 → 不插宠物气泡（人在对面，宠物独白从「手机那头」冒出来穿帮；
        // 且它恒线上标 + 顶预览，会盖掉见面期的活预览）。喂食/佩戴本体照常生效，只是这次没有聊天反馈。
        if (OfflineMeetingGate.inMeeting(conversation)) return false
        val startOfDay = DateFormatters.startOfDayMillis(now, zone)
        if (messageDao.countPetMessagesSince(conversation.uuid, startOfDay) >= DAILY_LIMIT) return false

        messageDao.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversation.uuid,
                roleRaw = "assistant",
                content = text,
                timestamp = now,
                isPetMessage = true,
            ),
        )
        conversationRepo.recordLastMessage(conversation.uuid, text, "assistant", now)
        return true
    }

    companion object {
        /** 每天每 conversation 最多发的宠物聊天气泡数（1:1 iOS `dailyLimit = 3`）。 */
        const val DAILY_LIMIT: Int = 3

        /** 消耗品使用反馈文案池（5 条，1:1 iOS `randomConsumableText`）。 */
        internal fun consumableTexts(itemName: String): List<String> = listOf(
            "主人喂我吃了${itemName}，好好吃!",
            "今天有${itemName}吃，好幸福呢~",
            "${itemName}真美味，谢谢主人!",
            "嗯嗯嗯!${itemName}是我喜欢的!",
            "吃到${itemName}了，开心~",
        )

        /** 装扮佩戴反馈文案池（5 条，1:1 iOS `randomEquipText`）。 */
        internal fun equipTexts(itemName: String): List<String> = listOf(
            "主人给我戴了${itemName}，好喜欢!",
            "戴上${itemName}是不是更可爱了?",
            "${itemName}真好看，谢谢主人~",
            "嘿嘿，我现在是${itemName}宝宝啦!",
            "戴着${itemName}感觉超棒!",
        )

        internal fun randomConsumableText(itemName: String, random: Random = Random.Default): String =
            consumableTexts(itemName).random(random)

        internal fun randomEquipText(itemName: String, random: Random = Random.Default): String =
            equipTexts(itemName).random(random)
    }
}
