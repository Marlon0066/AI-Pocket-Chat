package com.situ.aichat.world.live

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.world.WorldSeeds
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 来访到达开场（W12 图纸 §2/§3/§4.4·契约 §12「邀请来访抵达→快聊开场」）：邀请来访的角色到达用户所在城时，
 * 一句模板开场白**落进你俩真实会话**（`getOrCreateForCharacter` 唯一源·纯文本 assistant 消息·**零 LLM**）。
 *
 * **幂等（E15）**：开场消息 uuid 由 [travelKey]（= `ownerId:departAt`·同 `landVisitEvent` 派生分量）确定性派生
 * `world:visitopener:<travelKey>`——多次结算/生成返程那趟**绝不双插**（已存在即整体跳过·不重复 +未读）。变体三选一
 * 按 [travelKey] 确定性（§7 T1-3）。纯文本、不含任何【】段标题标记（§6 强耦合零碰）。
 */
@Singleton
class WorldVisitGreeter @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
) {

    /**
     * 落一句到达开场（图纸 §3）：确保会话（幂等）→ 开场 uuid 已存在则整体跳过（E15）→ 否则落纯文本 assistant 消息
     * （timestamp = [arriveAtMs]）+ `applyMaterialization`（未读 +1·会话末条更新·同通知物化语义·非在看）。
     */
    suspend fun greetArrival(characterUuid: String, characterName: String, travelKey: String, arriveAtMs: Long) {
        val conversationUuid = conversationRepo.getOrCreateForCharacter(characterUuid, characterName)
        // 见面闸（卷一 A5·J6）：会话正在线下见面 → **本次开场白跳过、不补发**（人就在对面，「待会儿去找你」
        // 当场穿帮）。位置在幂等 uuid 检查**之前**：未插入 = 不占用幂等位，下次旅行照常开场。
        if (OfflineMeetingGate.inMeeting(conversationRepo.get(conversationUuid))) return
        val openerUuid = UUID.nameUUIDFromBytes("world:visitopener:$travelKey".toByteArray()).toString()
        if (messageRepo.get(openerUuid) != null) return // 幂等·不双插（E15）
        val text = OPENERS[variantOf(travelKey)]
        messageRepo.upsert(
            MessageEntity(
                messageUUID = openerUuid,
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = text,
                timestamp = arriveAtMs,
            ),
        )
        conversationRepo.applyMaterialization(conversationUuid, text.take(60), arriveAtMs, markReadNow = false) // 对齐恢复路径截 60 惯例（R1 🔵-3）
    }

    companion object {
        /** 三变体确定性选择（同 [travelKey] 恒同·§7 T1-3）：`|fnv1a64(travelKey)| % 3`。 */
        internal fun variantOf(travelKey: String): Int = Math.floorMod(WorldSeeds.fnv1a64(travelKey), OPENERS.size.toLong()).toInt()

        /** 到达开场三变体（zh-rCN 逐字·§9 锁死·纯文本无【】标记·§6）。 */
        internal val OPENERS = listOf(
            "我到啦！一路还挺顺——待会儿去找你。",
            "我到啦，刚放下行李。你们这座城比我想的还好看。",
            "到啦到啦！先歇口气，回头见面聊。",
        )
    }
}
