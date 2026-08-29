package com.situ.aichat.gift

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftContext
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import com.situ.aichat.data.model.RedPacketAmountCatalog
import com.situ.aichat.data.model.growthLog
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.offline.outgoingOfflineSessionId
import com.situ.aichat.redpacket.RedPacketError
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.redpacket.RedPacketService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色主动送礼执行层（1:1 iOS `Services/ProactiveGiftExecutor.swift`，4 层架构的 Sub F）。
 *
 * 把 c-2/c-3/c-4 的产出串起来，真正执行送礼副作用。**礼物路径 11 步原子流程**：
 * shouldSend=false → 只记日志 → action 分派 → 查礼物 + 钱包 → 月上限守卫 → 余额守卫(不够 skip) → 幂等 key 检查 →
 * 找最近活跃 Conversation(无对话 skip) → 扣钱 + 建反向 GiftRecord(senderType=character) → 插 2 条 assistant 消息
 * (giftCard + plainText，时间戳差 2 秒) → 更新 Conversation 冗余字段 → `affinityToUser += calculateAffinityGain`
 * (**无 luxury 折扣**) + `lastProactiveGiftDate=now` → 珍贵(>200)/手作写 growthLog(.giftSent) → 整段 withTransaction 原子落盘。
 *
 * iOS 是 `@MainActor enum`（单 `context.save()` 原子）；安卓改 `@Singleton` class + [AppDatabase.withTransaction]
 * （= iOS 单 save 原子，任一步失败全回滚）。复用底层：[CurrencyService.spendCoinsFromCharacter] / [GiftRecordEntity] /
 * [GiftAffinity.affinityForCharacterGift]（无 luxury 折扣）/ [GrowthLogEntry]。纯辅助（giftContextFor/calculateAffinityGain/
 * growthLogSummary）在 companion 单测；原子编排走 Room，留真机 + 独立复核（钱路径）。
 *
 * **红包路径**（[executeRedPacket]，P9.3a 接通）：调 [RedPacketService.sendFromCharacter]（扣角色钱包 + 建 pending Record +
 * 插 RED_PACKET 消息）+ amount=0 幂等标记流水 + affinity，**不发陪送文案、不写 growthLog**；整段 withTransaction 原子。
 */
@Singleton
class ProactiveGiftExecutor @Inject constructor(
    private val db: AppDatabase,
    private val currencyService: CurrencyService,
    private val currencyDao: CurrencyDao,
    private val giftDao: GiftDao,
    private val scheduler: ProactiveGiftScheduler,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val redPacketService: RedPacketService,
    private val redPacketExpirationScanService: RedPacketExpirationScanService,
    private val characterWriteLock: CharacterWriteLock,
) {

    /** 执行结果（1:1 iOS `ExecuteResult`）。 */
    sealed interface ExecuteResult {
        /** 送礼成功执行 */
        data class Executed(val recordUuid: String, val giftMessageUuid: String, val textMessageUuid: String) : ExecuteResult
        /** 跳过（LLM 说不送 / 余额不足 / 月上限 / 无对话 / 幂等重复 / 红包待 9.3a） */
        data class Skipped(val reason: String) : ExecuteResult
        /** 系统错误（礼物 id 找不到 / 无钱包 / 扣币竞态） */
        data class Failed(val reason: String) : ExecuteResult

        val isExecuted: Boolean get() = this is Executed
    }

    /**
     * 执行决策。失败/跳过都不抛错，调用方只看 [ExecuteResult] 做日志。
     */
    suspend fun execute(
        decision: ProactiveGiftLLMService.Decision,
        trigger: ProactiveGiftTrigger,
        character: CharacterEntity,
        now: Long = System.currentTimeMillis(),
    ): ExecuteResult {
        // 0. shouldSend=false：尊重 LLM 决定，只记日志
        if (!decision.shouldSend) {
            Log.i(TAG, "决定不送给 ${character.name}: ${decision.reason}")
            return ExecuteResult.Skipped(decision.reason)
        }

        // 按 action 分派
        return when (decision.action) {
            ProactiveGiftLLMService.DecisionAction.RED_PACKET -> {
                // 白名单防御（LLM prompt + parseAndValidate 已校验，这是第三层）
                if (!ProactiveGiftLLMService.isRedPacketEligible(trigger.type)) {
                    Log.w(TAG, "LLM 对 ${trigger.type.raw} 返回 red_packet 但白名单拒绝，降级 skipped")
                    return ExecuteResult.Skipped("触发类型不允许红包")
                }
                executeRedPacket(decision, trigger, character, now)
            }
            ProactiveGiftLLMService.DecisionAction.GIFT -> executeGift(decision, trigger, character, now)
        }
    }

    /**
     * 礼物路径 11 步原子流程（整段 withTransaction，任一步失败全回滚；守卫读侧在前、写侧在后）。
     * **P12.6 D1b**：先拿每角色写锁、再开事务（固定加锁序 Mutex→SQLite，与成长/关系/结构化分析一致，杜绝顺序反转死锁）；
     * 第 11 步成长日志写回改列级 @Query UPDATE，不再整行覆盖分析/计数器刚写的其它列。红包路径不写角色行，不进锁。
     */
    private suspend fun executeGift(
        decision: ProactiveGiftLLMService.Decision,
        trigger: ProactiveGiftTrigger,
        character: CharacterEntity,
        now: Long,
    ): ExecuteResult = characterWriteLock.withCharacterLock(character.uuid) {
        db.withTransaction {
            // 1. shouldSend=true 但 giftId/message 缺失 → skip
            val giftId = decision.giftId
            val chatMessage = decision.message
            if (giftId == null || chatMessage == null) {
                Log.i(TAG, "决定不送给 ${character.name}: ${decision.reason}")
                return@withTransaction ExecuteResult.Skipped(decision.reason)
            }

            // 2. 查礼物 + 钱包
            val giftItem = GiftCatalog.find(giftId)
                ?: run {
                    Log.w(TAG, "主动送礼失败·礼物 id 不存在 char=${character.name} giftId=$giftId")
                    return@withTransaction ExecuteResult.Failed("礼物 id 不存在: $giftId")
                }
            val wallet = currencyDao.getCharacterWallet(character.uuid)
                ?: run {
                    Log.w(TAG, "主动送礼失败·角色无钱包 char=${character.name}")
                    return@withTransaction ExecuteResult.Failed("角色无钱包")
                }

            // 3. 月上限硬守卫
            if (scheduler.hasReachedMonthlyLimit(character.uuid, now)) {
                return@withTransaction ExecuteResult.Skipped("月上限已达(10 次)")
            }

            // 4. 余额守卫（方案 a · 不够就 skip，不降级）
            if (wallet.coinBalance < giftItem.price) {
                return@withTransaction ExecuteResult.Skipped("角色余额不足 ${giftItem.price} 购买 ${giftItem.name}")
            }

            // 5. 幂等 key 检查
            val relatedKey = trigger.relatedEntityKey(character.uuid)
            if (currencyDao.transactionExists(relatedKey)) {
                return@withTransaction ExecuteResult.Skipped("幂等防重:该触发今天已送过")
            }

            // 6. 找最近活跃 Conversation
            val conversation = conversationRepo.recentActiveConversationFor(character.uuid)
                ?: return@withTransaction ExecuteResult.Skipped("角色无任何对话,跳过送礼")

            // 6b. 见面闸（卷一 A4·拍板④「送礼红包攒到结束后」）：会话正在线下见面 → 早退 Skipped。
            // 位置**必须在第 7 步扣钱之前**：Skipped 不写幂等流水 → relatedKey 未被占用 → 见面结束后维护线
            // 下一次评估自然补送（与既有余额不足/月上限 Skipped 同型，不建显式队列）。金额/流水/幂等写法零碰。
            if (OfflineMeetingGate.inMeeting(conversation)) {
                return@withTransaction ExecuteResult.Skipped(SKIP_IN_MEETING)
            }

            // 7. 扣钱 + 建 GiftRecord（senderType=character 反向）
            val recordUuid = UUID.randomUUID().toString()
            val spent = currencyService.spendCoinsFromCharacter(
                characterUuid = character.uuid,
                amount = giftItem.price,
                category = CurrencyTransactionCategory.GIFT,
                note = "🎁 主动送礼 · ${giftItem.name}",
                relatedId = relatedKey,
                now = now,
            )
            if (spent == null) {
                // 余额已校验 + 同一事务串行，理论不达；防御性 Failed（无写入 → 干净返回）
                Log.w(TAG, "主动送礼失败·扣币竞态 char=${character.name} price=${giftItem.price}")
                return@withTransaction ExecuteResult.Failed("扣币失败(余额竞态)")
            }

            val record = GiftRecordEntity(
                uuid = recordUuid,
                timestamp = now,
                senderType = "character",
                senderCharacterUUID = character.uuid,
                receiverType = "user",
                giftItemId = giftId,
                pricePaid = giftItem.price,
                isDIY = false,
                context = giftContextFor(trigger.type).raw,
                senderMessage = chatMessage,
            )
            giftDao.insert(record)

            // 8. 插 2 条 Message（role=assistant；senderType=CHARACTER 让 llmRepresentation 输出「<角色名>送出礼物」）
            // 见面期间若目标会话正处线下见面，礼物卡 + 陪送文本须随会话打线下标记（与助手投递同源），否则漏进普通聊天 + 缺席沉浸剧场。
            val offlineSessionId = outgoingOfflineSessionId(conversation.isInOfflineMode, conversation.currentOfflineSessionId)
            val card = GiftCardData(
                type = "gift_card",
                giftItemId = giftId,
                giftRecordId = recordUuid,
                cost = giftItem.price,
                giftName = giftItem.name,
                isHandmade = giftItem.isHandmade,
                senderType = GiftSender.CHARACTER,
            )
            val giftMessageUuid = UUID.randomUUID().toString()
            messageRepo.upsert(
                MessageEntity(
                    messageUUID = giftMessageUuid,
                    conversationUuid = conversation.uuid,
                    roleRaw = "assistant",
                    content = GiftCardJson.encode(card),
                    timestamp = now,
                    messageKindRaw = MessageKind.GIFT_CARD.raw,
                    isOfflineMode = offlineSessionId != null,
                    offlineSessionId = offlineSessionId,
                ),
            )
            val textTimestamp = now + TEXT_MESSAGE_DELAY_MS
            val textMessageUuid = UUID.randomUUID().toString()
            messageRepo.upsert(
                MessageEntity(
                    messageUUID = textMessageUuid,
                    conversationUuid = conversation.uuid,
                    roleRaw = "assistant",
                    content = chatMessage,
                    timestamp = textTimestamp,
                    messageKindRaw = MessageKind.PLAIN_TEXT.raw,
                    isOfflineMode = offlineSessionId != null,
                    offlineSessionId = offlineSessionId,
                ),
            )

            // 9. 更新 Conversation 冗余字段（preview = chatMessage 前 60 字，role=assistant，时间=陪送文案时间）。
            // 见面期：与 finalizeDelivery 同源——AI 正文绝不写进会话列表预览（方案 A·OfflineChatVisibility）；仅刷新活动时间保鲜排序。
            if (offlineSessionId != null) {
                conversationRepo.touchLastMessageDate(conversation.uuid, textTimestamp)
            } else {
                conversationRepo.recordLastMessage(conversation.uuid, chatMessage.take(60), "assistant", textTimestamp)
            }

            // 10. affinityToUser += calculateAffinityGain（无 luxury 折扣）+ lastProactiveGiftDate=now（钱包内 fresh 读，不覆盖余额）
            val affinityGain = calculateAffinityGain(giftItem.price, giftItem.isHandmade)
            currencyService.recordProactiveGiftAffinity(character.uuid, affinityGain, now)

            // 11. 珍贵(>200)或手作写 growthLog(.giftSent)。
            // 锁内 fresh 读角色行（持锁 → 即最新值，且 LLM 决策已在锁外完成），列级 UPDATE 仅写 growthLogJSON 一列——
            // 不整行覆盖分析/心情/计数器刚写的其它列，且把礼物日志增量追加到最新 growthLog 上。对齐 iOS 只存 delta。
            if (giftItem.price > 200 || giftItem.isHandmade) {
                val fresh = characterRepo.get(character.uuid) ?: character
                val entry = GrowthLogEntry(timestamp = now, type = GrowthEventType.GIFT_SENT, summary = growthLogSummary(giftItem, chatMessage))
                characterRepo.updateGrowthLog(character.uuid, GrowthJson.encodeGrowthLog(fresh.growthLog + entry))
            }

            Log.i(TAG, "角色 ${character.name} 主动送出 ${giftItem.name} 价 ${giftItem.price} 金币,触发 ${trigger.type.raw}")
            ExecuteResult.Executed(recordUuid, giftMessageUuid, textMessageUuid)
        }
    }

    /**
     * 红包路径（1:1 iOS `ProactiveGiftExecutor.executeRedPacket`，角色→用户方向）。与礼物路径的关键差异：
     * - 调 [RedPacketService.sendFromCharacter]（扣角色钱包 + 建 pending Record + 插 RED_PACKET 消息）。
     * - **不发陪送文案**（LLM 决策独立上下文，角色想说话靠系统事件下次对话带出）、**不写 growthLog**（红包不算长期记住的礼物）。
     * - affinity 复用 [calculateAffinityGain]`(isHandmade=false)`；幂等 key + 月上限与礼物**共享**（同日同触发的红包和礼物互斥）。
     * - 额外写一条 **amount=0 幂等标记流水**（relatedId=relatedKey）：RedPacketService 内部 spend 流水 relatedId=record.uuid，
     *   而幂等空间用 `proactive_gift_{uuid}_{date}_{type}_{meta}` key，须手动构造一条让 [transactionExists] 检索到（amount=0 会被
     *   CurrencyService spend guard 挡掉，故走 [CurrencyService.recordCharacterTransaction] 直插不改余额）。
     *
     * **有意偏离 iOS（更安全）**：iOS 是两段 save（sendFromCharacter 一次 + 二次 save）；安卓整段 [AppDatabase.withTransaction]
     * 原子（任一步失败全回滚，避免「已发出但幂等标记/affinity 丢失」导致下次重复送）——与 [executeGift] 同模式。
     */
    private suspend fun executeRedPacket(
        decision: ProactiveGiftLLMService.Decision,
        trigger: ProactiveGiftTrigger,
        character: CharacterEntity,
        now: Long,
    ): ExecuteResult {
        // 捕获已发出的 Record 供事务提交后排 22h 预警精确闹钟（不在事务内调 AlarmManager，保钱事务纯净）。
        var sentRecord: RedPacketRecordEntity? = null
        val result = try {
            db.withTransaction {
                // 1. 校验红包字段（parseAndValidate 已校验，这是兜底第二道）
                val amount = decision.redPacketAmount
                if (amount == null || !RedPacketAmountCatalog.isValidAmount(amount)) {
                    Log.w(TAG, "主动红包失败·金额缺失或超范围 char=${character.name} amount=${decision.redPacketAmount}")
                    return@withTransaction ExecuteResult.Failed("红包金额缺失或超范围: ${decision.redPacketAmount}")
                }

                // 2. 查钱包 + 余额守卫（不够就 skip，不降级）
                val wallet = currencyDao.getCharacterWallet(character.uuid)
                    ?: run {
                        Log.w(TAG, "主动红包失败·角色无钱包 char=${character.name}")
                        return@withTransaction ExecuteResult.Failed("角色无钱包")
                    }
                if (wallet.coinBalance < amount) {
                    return@withTransaction ExecuteResult.Skipped("角色余额不足 $amount 金币发红包")
                }

                // 3. 月上限硬守卫（和礼物共享）
                if (scheduler.hasReachedMonthlyLimit(character.uuid, now)) {
                    return@withTransaction ExecuteResult.Skipped("月上限已达(10 次)· 红包和礼物共享")
                }

                // 4. 幂等 key 检查（和礼物共享幂等空间）
                val relatedKey = trigger.relatedEntityKey(character.uuid)
                if (currencyDao.transactionExists(relatedKey)) {
                    return@withTransaction ExecuteResult.Skipped("幂等防重:该触发今天已送过")
                }

                // 5. 找最近活跃 Conversation（无对话 skip）
                val conversation = conversationRepo.recentActiveConversationFor(character.uuid)
                    ?: return@withTransaction ExecuteResult.Skipped("角色无任何对话,跳过发红包")

                // 5b. 见面闸（卷一 A4·同礼物分支）：早退在第 6 步扣钱之前，不写幂等流水 → 结束后维护线补送。
                if (OfflineMeetingGate.inMeeting(conversation)) {
                    return@withTransaction ExecuteResult.Skipped(SKIP_IN_MEETING)
                }

                // 6. 扣钱 + 建 pending Record + 插 RED_PACKET 消息（节日红包带 festivalId）
                val blessing = decision.redPacketBlessing ?: ""
                val festivalId = if (trigger.type == ProactiveGiftTriggerType.FESTIVAL) trigger.metaId else null
                val outcome = redPacketService.sendFromCharacter(
                    characterUuid = character.uuid,
                    amount = amount,
                    blessing = blessing,
                    festivalId = festivalId,
                    conversationUuid = conversation.uuid,
                    now = now,
                )
                sentRecord = outcome.record // 提交后排 22h 预警闹钟用

                // 7. 更新 Conversation 冗余字段（preview="🧧 红包"，role=assistant）
                conversationRepo.recordLastMessage(conversation.uuid, "🧧 红包", "assistant", outcome.message.timestamp)

                // 8. amount=0 幂等标记流水（balanceAfter = sendFromCharacter 扣后余额；直插不改余额）
                val balanceAfter = currencyDao.getCharacterWallet(character.uuid)?.coinBalance ?: wallet.coinBalance
                currencyService.recordCharacterTransaction(
                    characterUuid = character.uuid,
                    kind = CurrencyTransactionKind.SPEND,
                    category = CurrencyTransactionCategory.RED_PACKET,
                    amount = 0,
                    balanceAfter = balanceAfter,
                    note = "幂等标记 · ${trigger.type.raw}",
                    relatedId = relatedKey,
                    now = now,
                )

                // 9. affinityToUser += calculateAffinityGain(isHandmade=false) + lastProactiveGiftDate=now（不改余额）
                currencyService.recordProactiveGiftAffinity(character.uuid, calculateAffinityGain(amount, false), now)

                // 10. 不写 growthLog（红包情感份量走 systemEventCard + 下次对话带出）
                Log.i(TAG, "角色 ${character.name} 主动发出红包 $amount 金币,触发 ${trigger.type.raw}")
                ExecuteResult.Executed(
                    recordUuid = outcome.record.uuid,
                    giftMessageUuid = outcome.message.messageUUID,
                    textMessageUuid = outcome.message.messageUUID, // 红包只有一条消息,复用 id
                )
            }
        } catch (e: RedPacketError) {
            Log.w(TAG, "主动红包失败·sendFromCharacter char=${character.name}: ${e.message}")
            ExecuteResult.Failed("sendFromCharacter 失败: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "主动红包失败·未知错误 char=${character.name}: ${e.message}")
            ExecuteResult.Failed("红包执行未知错误: ${e.message}")
        }
        // 事务提交后排 22h 预警精确闹钟（app 被杀也能弹；用户拆开 / 过期时取消）。
        if (result is ExecuteResult.Executed) {
            sentRecord?.let { redPacketExpirationScanService.scheduleWarningAlarm(it, character.name) }
        }
        return result
    }

    companion object {
        private const val TAG = "ProactiveGiftExecutor"

        /** 见面中早退的 Skipped 文案（卷一 A4·锁定逐字）：不写幂等流水，见面结束后维护线自然补送。 */
        private const val SKIP_IN_MEETING = "见面进行中·顺延（不写幂等流水，结束后维护线补送）"

        /** 陪送文案和礼物卡之间的时间间隔（毫秒），模仿真人「先看到礼物再说话」（iOS textMessageDelaySeconds=2）。 */
        const val TEXT_MESSAGE_DELAY_MS = 2000L

        /** 触发类型 → GiftContext 映射（1:1 iOS `giftContextFor`，落盘到 GiftRecord.context）。 */
        fun giftContextFor(triggerType: ProactiveGiftTriggerType): GiftContext = when (triggerType) {
            ProactiveGiftTriggerType.BIRTHDAY -> GiftContext.BIRTHDAY
            ProactiveGiftTriggerType.ANNIVERSARY -> GiftContext.ANNIVERSARY
            ProactiveGiftTriggerType.FESTIVAL -> GiftContext.FESTIVAL
            ProactiveGiftTriggerType.SENSE_LOW_MOOD -> GiftContext.COMFORT
            ProactiveGiftTriggerType.MISSING_YOU -> GiftContext.RANDOM
        }

        /**
         * affinityToUser 增量（1:1 iOS `calculateAffinityGain`，对齐用户送角色 baseline：price×0.08，手作×1.5，clamp[1,20]，
         * **无 luxury 折扣**）。复用已测的 [GiftAffinity.affinityForCharacterGift]。
         */
        fun calculateAffinityGain(pricePaid: Int, isHandmade: Boolean): Int =
            GiftAffinity.affinityForCharacterGift(pricePaid, isHandmade)

        /**
         * growthLog summary（1:1 iOS `growthLogSummary`）：`主动送给用户 <name>(手作):<message 截 28 字>`。
         * message 超 30 字 → 前 28 字 + "…"（项目约定用 length/take，emoji 与 iOS grapheme count 的极少数差异为既定取舍）。
         */
        fun growthLogSummary(item: GiftItem, message: String): String {
            val handmadeMark = if (item.isHandmade) "(手作)" else ""
            val truncated = if (message.length > 30) message.take(28) + "…" else message
            return "主动送给用户 ${item.name}$handmadeMark:$truncated"
        }
    }
}
