package com.situ.aichat.gift

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftContext
import com.situ.aichat.data.model.GiftRelationshipImpact
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.growthLog
import com.situ.aichat.data.model.moodHistory
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.model.syncedTo
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.economy.CurrencyService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天内送礼编排（1:1 iOS `GiftSendService` 的 sendInChat / sendUserDIYInChat 路径）。
 *
 * 一次性原子完成：扣币 → 建 GiftRecord → 插 giftCard 消息 → 心意值入账（baseline×时机×边际衰减）→ 8 维关系累加 →
 * L3 长期日志（珍贵>200 或手作；DIY 必写）。整段包在 [AppDatabase.withTransaction]（= iOS 单个 modelContext.save()
 * 原子落盘），任一步失败全回滚，杜绝"钱扣了但记录/消息缺"的半写（SPEC §4.4）。礼物店两步反应路径（spendAndCreateRecord
 * + generateReaction LLM）见 9.2b-5。
 */
@Singleton
class GiftSendService @Inject constructor(
    private val db: AppDatabase,
    private val currencyService: CurrencyService,
    private val giftDao: GiftDao,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val characterWriteLock: CharacterWriteLock,
) {

    /** 聊天内送礼结果（1:1 iOS `InChatSendOutcome`）。 */
    sealed interface InChatSendOutcome {
        data class Success(val message: MessageEntity, val record: GiftRecordEntity) : InChatSendOutcome
        data class InsufficientCoins(val need: Int, val have: Int) : InChatSendOutcome
        data object SpendFailed : InChatSendOutcome
    }

    /** 聊天内送目录礼物。UI 拿到 Success 后立即触发一次常规 AI 回复（礼物以 giftCard 消息进聊天流，AI 反馈走常规对话）。 */
    suspend fun sendInChat(
        item: GiftItem,
        characterUuid: String,
        conversationUuid: String,
        now: Long = System.currentTimeMillis(),
    ): InChatSendOutcome = characterWriteLock.withCharacterLock(characterUuid) {
        // P12.6 D1b：先拿每角色写锁、再开事务（固定加锁序 Mutex→SQLite，与成长/关系/结构化分析一致，杜绝顺序反转死锁）。
        // 锁内读 character 即最新（无并发写者持锁），关系/成长写回改列级 UPDATE，不再整行覆盖分析刚写的列。
        db.withTransaction {
            val character = characterRepo.get(characterUuid) ?: run {
                Log.w(TAG, "聊天送礼失败·角色不存在 char=$characterUuid item=${item.id}")
                return@withTransaction InChatSendOutcome.SpendFailed
            }
            val balance = currencyService.userCoinBalance(now)
            if (balance < item.price) {
                Log.w(TAG, "聊天送礼·余额不足 char=${character.name} item=${item.id} need=${item.price} have=$balance")
                return@withTransaction InChatSendOutcome.InsufficientCoins(item.price, balance)
            }

            val recordUuid = UUID.randomUUID().toString()
            // 心意值：baseline × 时机 × 边际衰减（record 尚未插入，decay 查询自然不含本次）
            val baseline = GiftAffinity.baseline(item)
            val timing = GiftTimingBonusService.multiplier(character.birthday, character.moodHistory, now)
            val decay = GiftMarginalDecayService.multiplier(item, characterUuid, recordUuid, giftDao, now)
            val affinityGain = GiftMarginalDecayService.applyMultiplier(baseline, timing * decay)
            val impact = GiftRelationshipImpactService.compute(item, affinityGain)

            val record = GiftRecordEntity(
                uuid = recordUuid, timestamp = now,
                senderType = "user", receiverType = "character", receiverCharacterUUID = characterUuid,
                giftItemId = item.id, pricePaid = item.price, isDIY = false,
                affinityGain = affinityGain, relationshipImpactJSON = impact.toJson(),
            )

            // 扣币（关联 record.uuid 做幂等/审计）；余额已校验，null 仅极端竞态 → 回滚
            if (
                currencyService.spendCoinsFromUser(
                    amount = item.price, category = CurrencyTransactionCategory.GIFT,
                    note = "送给${character.name}的${item.name}", relatedId = recordUuid, now = now,
                ) == null
            ) {
                Log.w(TAG, "聊天送礼·扣币失败(竞态) char=${character.name} item=${item.id} price=${item.price}")
                return@withTransaction InChatSendOutcome.SpendFailed
            }
            giftDao.insert(record)

            // giftCard 消息（senderType=USER → llmRepresentation 输出"用户送出礼物"）
            val card = GiftCardData(
                type = "gift_card", giftItemId = item.id, giftRecordId = recordUuid,
                cost = item.price, giftName = item.name, isHandmade = item.isHandmade, senderType = GiftSender.USER,
            )
            val message = giftCardMessage(card, conversationUuid, now)
            messageRepo.upsert(message)
            conversationRepo.recordLastMessage(conversationUuid, conversationPreview(item.name), "user", now)

            // 心意值入账 + 8 维关系累加 + L3 长期日志（珍贵或手作）
            currencyService.addAffinityFromUser(characterUuid, affinityGain, now)
            applyRelationshipAndGrowth(
                character = character, impact = impact,
                growthEntry = if (item.price > 200 || item.isHandmade) {
                    GrowthLogEntry(timestamp = now, type = GrowthEventType.GIFT_RECEIVED, summary = growthLogSummary(item))
                } else {
                    null
                },
            )

            Log.i(TAG, "聊天送礼成功 char=${character.name} item=${item.id} price=${item.price} gain=$affinityGain record=$recordUuid")
            InChatSendOutcome.Success(message, record)
        }
    }

    /**
     * 聊天内用户 DIY 手作礼物（1:1 iOS `sendUserDIYInChat`）。与 [sendInChat] 差异：先 makeUserDIY 造 stub；record 填
     * isDIY/diyTitle/diyContent/diyImagePath；cardData 带 diyTitle（空→null）+ diyContent（截 80）；**无论 cost 都写
     * growthLog**（DIY 本就要被长期记住，summary 用"（用户 DIY · tier）"区别预置手作）。[diyImagePath] 由 UI 落盘后传入。
     */
    suspend fun sendUserDIYInChat(
        title: String,
        content: String,
        diyImagePath: String?,
        cost: Int,
        characterUuid: String,
        conversationUuid: String,
        now: Long = System.currentTimeMillis(),
    ): InChatSendOutcome = characterWriteLock.withCharacterLock(characterUuid) {
        // P12.6 D1b：Mutex→SQLite 固定序（见 sendInChat 注释）；关系 + 成长日志列级写回。
        db.withTransaction {
            val character = characterRepo.get(characterUuid) ?: run {
                Log.w(TAG, "DIY 送礼失败·角色不存在 char=$characterUuid")
                return@withTransaction InChatSendOutcome.SpendFailed
            }
            val item = GiftCatalog.makeUserDIY(title, content, cost)
            val balance = currencyService.userCoinBalance(now)
            if (balance < item.price) {
                Log.w(TAG, "DIY 送礼·余额不足 char=${character.name} need=${item.price} have=$balance")
                return@withTransaction InChatSendOutcome.InsufficientCoins(item.price, balance)
            }

            val cleanedTitle = title.trim()
            val cleanedContent = content.trim()
            val recordUuid = UUID.randomUUID().toString()

            val baseline = GiftAffinity.baseline(item)
            val timing = GiftTimingBonusService.multiplier(character.birthday, character.moodHistory, now)
            val decay = GiftMarginalDecayService.multiplier(item, characterUuid, recordUuid, giftDao, now)
            val affinityGain = GiftMarginalDecayService.applyMultiplier(baseline, timing * decay)
            val impact = GiftRelationshipImpactService.compute(item, affinityGain)

            val record = GiftRecordEntity(
                uuid = recordUuid, timestamp = now,
                senderType = "user", receiverType = "character", receiverCharacterUUID = characterUuid,
                giftItemId = item.id, pricePaid = item.price, isDIY = true,
                diyTitle = cleanedTitle, diyContent = cleanedContent, diyImagePath = diyImagePath,
                affinityGain = affinityGain, relationshipImpactJSON = impact.toJson(),
            )

            if (
                currencyService.spendCoinsFromUser(
                    amount = item.price, category = CurrencyTransactionCategory.GIFT,
                    note = "送给${character.name}的${item.name}", relatedId = recordUuid, now = now,
                ) == null
            ) {
                Log.w(TAG, "DIY 送礼·扣币失败(竞态) char=${character.name} price=${item.price}")
                return@withTransaction InChatSendOutcome.SpendFailed
            }
            giftDao.insert(record)

            val card = GiftCardData(
                type = "gift_card", giftItemId = item.id, giftRecordId = recordUuid,
                cost = item.price, giftName = item.name, isHandmade = item.isHandmade, senderType = GiftSender.USER,
                diyTitle = cleanedTitle.ifEmpty { null },
                diyContent = if (cleanedContent.isEmpty()) {
                    null
                } else if (cleanedContent.length > 80) {
                    cleanedContent.take(80) + "…"
                } else {
                    cleanedContent
                },
            )
            val message = giftCardMessage(card, conversationUuid, now)
            messageRepo.upsert(message)
            conversationRepo.recordLastMessage(conversationUuid, conversationPreview(item.name), "user", now)

            currencyService.addAffinityFromUser(characterUuid, affinityGain, now)
            applyRelationshipAndGrowth(
                character = character, impact = impact,
                growthEntry = GrowthLogEntry(
                    timestamp = now, type = GrowthEventType.GIFT_RECEIVED,
                    summary = diyGrowthLogSummary(item, cleanedTitle),
                ),
            )

            Log.i(TAG, "DIY 送礼成功 char=${character.name} price=${item.price} gain=$affinityGain record=$recordUuid")
            InChatSendOutcome.Success(message, record)
        }
    }

    // MARK: 礼物店两步路径——第一步扣币建 record（第二步反应见 GiftReactionService）

    /** 礼物店送礼结果（iOS `spendAndCreateRecord` 的 throw 语义改为枚举返回，UI 可 switch 穷尽三态）。 */
    sealed interface ShopSpendOutcome {
        data class Success(val record: GiftRecordEntity) : ShopSpendOutcome
        data class InsufficientCoins(val need: Int, val have: Int) : ShopSpendOutcome
        data object SpendFailed : ShopSpendOutcome
    }

    /**
     * 礼物店第一步：扣币 + 建 GiftRecord（立即完成，**不生成反应、不进聊天流、不入账心意/关系、不写 L3 growthLog**）。
     *
     * 1:1 iOS `spendAndCreateRecord`——拆两步让扣款即时反映余额 UI，反应异步 loading 不阻塞扣币确认。心意值 / 8 维关系 /
     * reactionText 全由第二步 [GiftReactionService.generateReaction] 落地。**礼物店路径与 iOS 一样不写 growthLog**
     * （只有聊天内送礼 [sendInChat]/[sendUserDIYInChat] 写 L3），故 record 初始 affinityGain=0 / 反应字段空，待第二步填。
     *
     * @param context 送礼场景（礼物店默认随手一份；反应 prompt 据此加场景行）。
     */
    suspend fun spendAndCreateRecord(
        item: GiftItem,
        characterUuid: String,
        context: GiftContext = GiftContext.RANDOM,
        now: Long = System.currentTimeMillis(),
    ): ShopSpendOutcome = db.withTransaction {
        val character = characterRepo.get(characterUuid) ?: run {
            Log.w(TAG, "礼物店扣币失败·角色不存在 char=$characterUuid item=${item.id}")
            return@withTransaction ShopSpendOutcome.SpendFailed
        }
        val balance = currencyService.userCoinBalance(now)
        if (balance < item.price) {
            Log.w(TAG, "礼物店·余额不足 char=${character.name} item=${item.id} need=${item.price} have=$balance")
            return@withTransaction ShopSpendOutcome.InsufficientCoins(item.price, balance)
        }

        val recordUuid = UUID.randomUUID().toString()
        val record = GiftRecordEntity(
            uuid = recordUuid, timestamp = now,
            senderType = "user", receiverType = "character", receiverCharacterUUID = characterUuid,
            giftItemId = item.id, pricePaid = item.price, isDIY = false, context = context.raw,
        )
        // 扣币（关联 record.uuid 幂等/审计）；余额已校验，null 仅极端竞态 → 整段事务回滚
        if (
            currencyService.spendCoinsFromUser(
                amount = item.price, category = CurrencyTransactionCategory.GIFT,
                note = "送给${character.name}的${item.name}", relatedId = recordUuid, now = now,
            ) == null
        ) {
            Log.w(TAG, "礼物店·扣币失败(竞态) char=${character.name} item=${item.id} price=${item.price}")
            return@withTransaction ShopSpendOutcome.SpendFailed
        }
        giftDao.insert(record)
        Log.i(TAG, "礼物店扣币成功 char=${character.name} item=${item.id} price=${item.price} record=$recordUuid")
        ShopSpendOutcome.Success(record)
    }

    private fun giftCardMessage(card: GiftCardData, conversationUuid: String, now: Long) = MessageEntity(
        messageUUID = UUID.randomUUID().toString(),
        conversationUuid = conversationUuid,
        roleRaw = "user",
        content = GiftCardJson.encode(card),
        timestamp = now,
        messageKindRaw = MessageKind.GIFT_CARD.raw,
    )

    /** 聊天列表预览（安卓：礼物名带括号，iOS 无显式 preview）。 */
    private fun conversationPreview(giftName: String) = "[礼物]$giftName"

    /**
     * 8 维关系累加（clamp[0,100]）+ 可选 L3 日志，列级写回角色（P12.6 D1b）。
     *
     * [character] 由调用方在 [CharacterWriteLock] 内、事务起始读得（无并发写者持锁 → 即最新值），故直接对其
     * `relationshipQuality`/`growthLog` 施加增量即对齐 iOS 单线程逐属性改 @Model。写回改列级 @Query UPDATE：
     * 无成长日志（聊天送礼非珍贵）写 relationshipQuality + relationshipPressure 两列，有则连 growthLog 三列一起写，
     * **不整行覆盖**分析/计数器刚写的其它列。
     */
    private suspend fun applyRelationshipAndGrowth(
        character: CharacterEntity,
        impact: GiftRelationshipImpact,
        growthEntry: GrowthLogEntry?,
    ) {
        // 卷二表1 ②③：impact 的计算（GiftRelationshipImpactService.compute/apply）**本体零碰**，
        // 只把算出来的目标净额经写口翻译成压强——impact 恒 ≥0 ⇒ 实际只加正压；某维为负则按符号进负压。
        val pressure = character.relationshipPressure
            .syncedTo(GiftRelationshipImpactService.apply(impact, character.relationshipQuality))
        val newRelationship = GrowthJson.encode(pressure.toQuality())
        if (growthEntry != null) {
            characterRepo.updateRelationshipQualityAndGrowthLog(
                character.uuid,
                newRelationship,
                GrowthJson.encodeGrowthLog(character.growthLog + growthEntry),
                GrowthJson.encode(pressure),
            )
        } else {
            characterRepo.updateRelationshipQuality(character.uuid, newRelationship, GrowthJson.encode(pressure))
        }
    }

    companion object {
        private const val TAG = "GiftSendService"

        /**
         * `.giftReceived` summary（1:1 iOS `growthLogSummary`）。手作优先于金额分档：
         * - 非手作：`收到用户的礼物：<name>（<tier>）`
         * - 手作：`收到用户的礼物：<name>（手作 · <tier>）`
         */
        fun growthLogSummary(item: GiftItem): String {
            val tier = GiftCardData.tier(item.price)
            return if (item.isHandmade) {
                "收到用户的礼物：${item.name}（手作 · $tier）"
            } else {
                "收到用户的礼物：${item.name}（$tier）"
            }
        }

        /**
         * 用户 DIY 的 summary（1:1 iOS `diyGrowthLogSummary`）：`收到用户的礼物：<title 或 手作礼物>（用户 DIY · <tier>）`。
         * 用"（用户 DIY · tier）"区别预置手作的"（手作 · tier）"，让后续 LLM 感知"用户真的自己做了东西"。
         */
        fun diyGrowthLogSummary(item: GiftItem, title: String): String {
            val tier = GiftCardData.tier(item.price)
            val displayName = title.trim().ifEmpty { "手作礼物" }
            return "收到用户的礼物：$displayName（用户 DIY · $tier）"
        }
    }
}
