package com.situ.aichat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.notification.NotificationLearningService
import com.situ.aichat.redpacket.RedPacketAcceptanceDecisionService
import com.situ.aichat.redpacket.RedPacketError
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.redpacket.RedPacketSendOutcome
import com.situ.aichat.redpacket.RedPacketService
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.StreakManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 聊天内「礼物 / 红包」钱路协作者（ChatViewModel 刀9 抽出·只搬不改·字节级保真）。
 *
 * 承载送目录礼物 / DIY 手作礼物 / 发红包 / 拆红包，加礼物卡气泡取 DIY 图、取记录、红包状态响应式观察。
 * 钱操作经 [GiftSendService] / [RedPacketService] 原子完成（内部 CurrencyService 单事务）；送礼 / 发包成功后触发常规 AI 回复。
 * ChatViewModel 持本协作者并保留薄委托方法转发（公开 API 不变，ChatScreen 直接调 VM）。
 */
internal class InChatGiftRedPacketController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val conversationUuid: String,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val notificationLearningService: NotificationLearningService,
    private val giftDao: GiftDao,
    private val giftSendService: GiftSendService,
    private val redPacketService: RedPacketService,
    private val redPacketDecisionService: RedPacketAcceptanceDecisionService,
    private val redPacketExpirationScanService: RedPacketExpirationScanService,
    private val redPacketDao: RedPacketDao,
    /** 送礼后触发 AI 回复的入口（C1 输入排契约 §3.2-4：= [AssistantTurnController.enqueueExternalTurn] 入合并等待窗）。 */
    private val enqueueTurn: () -> Unit,
) {

    /**
     * 聊天内送目录礼物（9.2d d-3，1:1 iOS InChatGiftSheetView 的 onSendGift）：原子扣币 + 建 record + 插 giftCard 消息
     * （[GiftSendService.sendInChat] 已完成），成功后**立即触发一次常规 AI 回复**（礼物以 .giftCard 进聊天流，AI 走常规
     * 对话流，共享 PromptBuilder/giftHistory）。返回 outcome 让 sheet 按 case 显示动画/Toast。
     */
    suspend fun sendGiftInChat(item: GiftItem): GiftSendService.InChatSendOutcome {
        val convo = conversationRepo.get(conversationUuid) ?: return GiftSendService.InChatSendOutcome.SpendFailed
        val outcome = giftSendService.sendInChat(item, convo.characterUuid, conversationUuid)
        if (outcome is GiftSendService.InChatSendOutcome.Success) replyAfterGift(convo.characterUuid, item.name)
        return outcome
    }

    /**
     * 聊天内送 DIY 手作礼物（1:1 iOS DIYGiftCreationView 的 onSend → sendUserDIYInChat）。先把可选图片落盘
     * （[ContentImageStore]，1024px JPEG）取 path，再 [GiftSendService.sendUserDIYInChat] 原子完成扣币/建 record/插消息/
     * 写 growthLog；成功后触发常规 AI 回复。返回 outcome 供 sheet 按 case 处理。
     */
    suspend fun sendDiyGift(title: String, content: String, imageUri: Uri?, cost: Int): GiftSendService.InChatSendOutcome {
        val convo = conversationRepo.get(conversationUuid) ?: return GiftSendService.InChatSendOutcome.SpendFailed
        val imagePath = imageUri?.let { ContentImageStore.save(appContext, it) }
        val outcome = giftSendService.sendUserDIYInChat(title, content, imagePath, cost, convo.characterUuid, conversationUuid)
        if (outcome is GiftSendService.InChatSendOutcome.Success) {
            val giftName = title.trim().ifEmpty { "手作礼物" }
            replyAfterGift(convo.characterUuid, giftName)
        }
        return outcome
    }

    /**
     * 送礼成功后触发常规 AI 回复（仿文字/表情受理尾段：火花/学习记账 + 忙碌检测）。无 API 不报错只跳过回复（礼物已送）。
     * C1（输入排契约 §3.2-4）：回合改经 [enqueueTurn] 入合并等待窗（三点态打断 + 停手统一作答），不再直跑引擎。
     */
    private suspend fun replyAfterGift(characterUuid: String, giftName: String) {
        apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return
        val character = characterRepo.get(characterUuid) ?: return
        val now = System.currentTimeMillis()
        val chatCharacter = StreakManager.recordChat(character, now)
        // P12.6 D1b：火花续期只改 streakCount/lastChatDate 两列（仅此 VM 写、且 VM 串行），列级 UPDATE 不再整行覆盖。
        if (chatCharacter !== character) {
            characterRepo.updateStreak(character.uuid, chatCharacter.streakCount, chatCharacter.lastChatDate ?: now)
        }
        // 相识天数图纸 §4.1：首条消息落「第一次聊天时间」（SQL 只往早改；老角色由冷启补账改成真最早）。
        if (character.firstMessageDate == null) characterRepo.markFirstMessageDate(character.uuid, now)
        notificationLearningService.recordUserResponse(character.uuid, now)
        // 忙碌延迟回复功能已删除（2026-07-11 用户拍板）：忙碌时段照常即时回复。
        // 残留忙碌态清理（R3#0）随回合移入窗到期路径（AssistantTurnController.launchWindowTurn），语义不变。
        enqueueTurn()
    }

    /** 聊天礼物卡气泡：按 recordUuid 取 DIY 上传图（用于本地气泡渲染，**永不进 LLM**）。非 DIY/无图返 null。 */
    suspend fun loadGiftDiyImage(recordUuid: String): Bitmap? {
        val record = giftDao.getByUuid(recordUuid) ?: return null
        return ContentImageStore.load(record.diyImagePath)
    }

    /**
     * 聊天内用户发红包给当前角色（1:1 iOS ChatViewModel+Send 用户发红包路径）：[RedPacketService.sendFromUser] 原子扣币 +
     * 建 pending Record + 插 RED_PACKET 消息；成功后**异步**（不阻塞、不走主聊天 dispatcher）触发角色 AI 收/拒决策
     * （[RedPacketAcceptanceDecisionService.decideAndApply]，独立 LLM）。VM 被清/被杀时决策中断 → 红包留 pending →
     * 24h 过期扫描兜底退回用户（= iOS Task 中断走 expired 兜底）。返回 outcome 供发送 sheet 显示拆开动画 / 错误。
     */
    suspend fun sendRedPacketInChat(amount: Int, blessing: String, festivalId: String? = null): RedPacketSendOutcome {
        val convo = conversationRepo.get(conversationUuid) ?: return RedPacketSendOutcome.Failed("会话不存在")
        val character = characterRepo.get(convo.characterUuid) ?: return RedPacketSendOutcome.Failed("角色不存在")
        val outcome = try {
            redPacketService.sendFromUser(
                toCharacterUuid = character.uuid,
                toCharacterName = character.name,
                amount = amount,
                blessing = blessing,
                festivalId = festivalId,
                conversationUuid = conversationUuid,
            )
        } catch (e: RedPacketError.InsufficientBalance) {
            return RedPacketSendOutcome.InsufficientBalance(e.need, e.have)
        } catch (e: RedPacketError) {
            return RedPacketSendOutcome.Failed(e.message ?: "发送失败")
        }
        conversationRepo.recordLastMessage(conversationUuid, "🧧 红包", "user", outcome.message.timestamp)
        scope.launch { redPacketDecisionService.decideAndApply(outcome.record.uuid) }
        return RedPacketSendOutcome.Success
    }

    /** 红包气泡：按 recordUuid 响应式观察 Record 状态（pending/accepted/rejected/expired），气泡随状态机自动刷新（对齐 iOS `.task(id:recordUUID)`）。 */
    fun observeRedPacketRecord(recordUuid: String) = redPacketDao.observeByUuid(recordUuid)

    /**
     * 用户拆开角色发来的红包（1:1 iOS RedPacketDetailView handleOpen → acceptRedPacket）：转账到用户钱包（accept 内原子）
     * + 取消 22h 预警精确闹钟。已被并发解决（如刚过期）则记日志不报错。
     */
    suspend fun openRedPacket(recordUuid: String) {
        try {
            redPacketService.acceptRedPacket(recordUuid)
            redPacketExpirationScanService.cancelWarningAlarm(recordUuid)
        } catch (e: RedPacketError) {
            Log.w("ChatViewModel", "拆开红包失败 $recordUuid: ${e.message}")
        }
    }

    /** 聊天礼物卡气泡点击：按 recordUuid 取 GiftRecord（DIY 详情底片用）。 */
    suspend fun giftRecord(recordUuid: String): GiftRecordEntity? = giftDao.getByUuid(recordUuid)
}
