package com.situ.aichat.world.cast

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.AssistantOutputGate
import com.situ.aichat.prompt.memory.MemoryService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 初遇确认结果（新角色 uuid + 会话 uuid·供 VM 转 Known 态）。 */
data class MeetConfirm(val characterUuid: String, val conversationUuid: String)

/**
 * 初遇会话（W12 图纸 §2/§3/§4.4/§9·契约 §11「初遇对话走弹窗·聊完成正式会话开头」）：**内存缓冲**对话（开场 1 次 LLM +
 * 回应硬上限 2 次·图纸作者自决①）→ 确认 = **单事务**（嵌套 [WorldRecruitService.recruit] + `getOrCreateForCharacter` +
 * flush 缓冲消息·全落或全无·E8）。
 *
 * **招募绝不被 LLM 阻塞**（E5）：开场/回应失败/断网/无配置一律模板兜底或 null（退确认卡），确认卡照常可用。
 * **有意易失**（E6/E8）：未确认态只在内存（进程死 = 什么都没发生·眼缘不变·重开重来）；开场结果缓存 30min（不重复扣调用）。
 * 缓冲消息落库为**纯文本 assistant/user 消息**、不含任何【】段标题标记（§6 强耦合零碰）。
 */
@Singleton
class WorldFirstMeetService @Inject constructor(
    private val contextLog: ContextLogService,
    private val apiConfigRepo: ApiConfigRepository,
    private val worldDao: WorldDao,
    private val recruitService: WorldRecruitService,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val db: AppDatabase,
) {

    private val mutex = Mutex()
    private val openingCache = HashMap<String, CachedOpening>() // nativeId → 开场（30min·不重复扣·E6）
    private val buffers = HashMap<String, MutableList<Turn>>() // nativeId → 内存缓冲对话（有意易失·E6/E8）

    /** 开始初遇（§3）：复用 30min 缓存开场 或 LLM 生成一次（失败退兜底·E5）；重置缓冲为 [开场]。返回开场文本。 */
    suspend fun startMeet(nativeId: String, name: String, placeName: String, nowMs: Long): String = mutex.withLock {
        val cached = openingCache[nativeId]
        val opening = if (cached != null && nowMs - cached.at < CACHE_MS) {
            cached.text
        } else {
            generateOpening(nativeId, name, placeName).also { openingCache[nativeId] = CachedOpening(it, nowMs) }
        }
        buffers[nativeId] = mutableListOf(Turn("assistant", opening))
        opening
    }

    /** 初遇回应（§3·上限 [MAX_REPLIES]）：缓冲用户消息 → 未达上限则 LLM 续写一句（失败/无配置 → null）。返回回应或 null（退确认卡）。 */
    suspend fun respond(nativeId: String, userText: String): String? = mutex.withLock {
        val buf = buffers[nativeId] ?: return null
        buf.add(Turn("user", userText))
        if (buf.count { it.role == "assistant" } - 1 >= MAX_REPLIES) return null // 达上限（减开场）→ 只出确认卡
        val reply = generateReply(nativeId, buf)
        if (reply != null) buf.add(Turn("assistant", reply))
        reply
    }

    /**
     * 确认认识（§3·E7/E8）：**单事务**——嵌套 [WorldRecruitService.recruit]（事务内复核愿意·双击/并发第二次返 null）+
     * `getOrCreateForCharacter` + flush 缓冲消息 + 会话末条。全落或全无。成功清缓冲/开场缓存。不愿意/无缓冲 → null（UI 幂等）。
     */
    suspend fun confirmMeet(nativeId: String, name: String, nowMs: Long): MeetConfirm? = mutex.withLock {
        val buf = buffers[nativeId] ?: return null
        // 落库前置闸（图纸 2026-09-01 件①）：assistant 行判脏即不落库（user 行照落，落库 kind 恒 PLAIN_TEXT）。
        // 全被丢空 → 跳过 flush 与会话末条，但**仍成局**（已认识这件事与那几句话是否落库无关）。
        val clean = buf.filterNot { it.role == "assistant" && AssistantOutputGate.shouldDiscard(it.text, MessageKind.PLAIN_TEXT, source = "worldMeet") }
        val result = db.withTransaction {
            val charUuid = recruitService.recruit(nativeId, nowMs) ?: return@withTransaction null // 事务内复核愿意
            val convUuid = conversationRepo.getOrCreateForCharacter(charUuid, name)
            clean.forEachIndexed { i, t ->
                messageRepo.upsert(
                    MessageEntity(
                        messageUUID = UUID.nameUUIDFromBytes("world:meet:$charUuid:$i".toByteArray()).toString(),
                        conversationUuid = convUuid, roleRaw = t.role, content = t.text, timestamp = nowMs + i,
                    ),
                )
            }
            clean.lastOrNull()?.let { last ->
                conversationRepo.recordLastMessage(convUuid, last.text, last.role, nowMs + clean.size)
            }
            MeetConfirm(charUuid, convUuid)
        }
        if (result != null) { buffers.remove(nativeId); openingCache.remove(nativeId) }
        result
    }

    /** 放弃（§3·E6·关闭弹窗未确认）：清缓冲；**保留**开场缓存 30min（重开重来不重复扣调用）。 */
    suspend fun abandon(nativeId: String) = mutex.withLock { buffers.remove(nativeId); Unit }

    private suspend fun generateOpening(nativeId: String, name: String, placeName: String): String {
        val def = WorldNativeRoster.byNativeId(nativeId) ?: return fallbackOpener(name)
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) ?: return fallbackOpener(name)
        val cityName = worldDao.getState()?.seed?.let { WorldNativeRoster.cityNameOf(def, it) } ?: "这座城"
        val prompt = "你是「$name」，${def.personality.take(40)}，住在$cityName。你与对方眼缘已足，此刻在${placeName}第一次正式搭话。" +
            "用你的说话风格（${def.speakingStyle}）说一句 20 到 60 字的开场白，自然，不自报 AI 身份，不用引号包裹。"
        return runCatching {
            // 非流式 completion 不剥内联 <think>（只有流式经 ThinkTagParser），落消息表前在此剥净（含 trim）。
            MemoryService.strippingThinkingTags(
                contextLog.completion(LogSource.WORLD_FIRST_MEET, "", config, listOf(ChatMessageDto("user", prompt)), MEET_TEMPERATURE),
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackOpener(name)
    }

    private suspend fun generateReply(nativeId: String, buf: List<Turn>): String? {
        val def = WorldNativeRoster.byNativeId(nativeId) ?: return null
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) ?: return null
        val system = "你是「${def.name}」，${def.personality.take(40)}，说话风格：${def.speakingStyle}。你正在和一个刚认识的人搭话，" +
            "请以你的风格自然地回一句 20 到 60 字，不自报 AI 身份，不用引号包裹。"
        val messages = listOf(ChatMessageDto("system", system)) + buf.map { ChatMessageDto(it.role, it.text) }
        return runCatching {
            MemoryService.strippingThinkingTags(
                contextLog.completion(LogSource.WORLD_FIRST_MEET, "", config, messages, MEET_TEMPERATURE),
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private data class Turn(val role: String, val text: String)
    private data class CachedOpening(val text: String, val at: Long)

    companion object {
        /** 初遇回应硬上限（图纸作者自决①·§9 锁死·之后只出确认卡）。 */
        const val MAX_REPLIES = 2

        /** 初遇兜底开场（§9 逐字·zh·纯文本消息内容·`internal` 便于 T1-4）。 */
        internal fun fallbackOpener(name: String) = "你好呀，我是$name。早就注意到你了——今天总算说上话。"
        private const val CACHE_MS = 30 * 60_000L // 开场缓存 30min·E6
        private const val MEET_TEMPERATURE = 0.85
    }
}
