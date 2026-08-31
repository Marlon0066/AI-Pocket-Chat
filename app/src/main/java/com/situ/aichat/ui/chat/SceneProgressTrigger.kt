package com.situ.aichat.ui.chat

import android.util.Log
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.offline.SceneProgressService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 线下节拍状态触发协作者（审计 S3·自 [ChatViewModel] 只搬不改抽出，模式同 [MemoryAnalysisTrigger] 家族）。
 *
 * 流结束后由引擎回调 [incrementRoundAndCheck]，仅线下生效（源自 iOS incrementSceneProgressRoundAndCheck +
 * checkAndTriggerSceneProgressUpdate + performSceneProgressUpdate）：从 DB 重数本 session user 消息差，
 * ≥15 且距上次 ≥3min → 异步 LLM 生成节拍状态 → 落库（2026-08-31 人设优先微图纸：张力自愈已退役、
 * 心事种子不再传入生成端）。失败设冷却不更新 triggerCount（冷却后重试这批）。
 * in-memory 触发状态随 VM 生命周期（scope=viewModelScope），对齐 iOS offlineUserTurnCount/lastSceneProgress*。
 */
internal class SceneProgressTrigger(
    private val scope: CoroutineScope,
    private val conversationUuid: String,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val characterRepo: CharacterRepository,
    private val userProfileDao: UserProfileDao,
    private val apiConfigRepo: ApiConfigRepository,
    private val contextLog: ContextLogService,
) {
    private var lastSceneProgressTriggerCount = 0
    private var lastSceneProgressUpdate: Long? = null
    private var isUpdatingSceneProgress = false

    fun incrementRoundAndCheck() {
        scope.launch {
            val convo = conversationRepo.get(conversationUuid) ?: return@launch
            if (!convo.isInOfflineMode) return@launch
            val sessionId = convo.currentOfflineSessionId
            if (sessionId.isNullOrEmpty() || isUpdatingSceneProgress) return@launch
            // 占坑须紧接判定（check 与 set 间无挂起点；scope=Main.immediate → 原子）：否则两回合
            // 都过 offlineSessionMessages/resolveConfigValues 挂起点后并发生成（修 LOW 竞态）。后续挂起工作全包进 try/finally。
            isUpdatingSceneProgress = true
            try {
                val sessionMessages = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
                val userCount = sessionMessages.count { it.roleRaw == "user" && it.content.isNotEmpty() }
                val now = System.currentTimeMillis()
                if (!SceneProgressService.shouldTriggerUpdate(userCount, lastSceneProgressTriggerCount, lastSceneProgressUpdate, now)) {
                    return@launch
                }
                // 节拍状态可单独分配 API（未分配回退当前激活）。
                val config = apiConfigRepo.resolveConfigValues(ApiFunction.SCENE_PROGRESS)
                    ?: apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return@launch

                val startPayload = sessionMessages.firstOrNull { it.messageKindRaw == MessageKind.OFFLINE_MARKER_START.raw }
                    ?.let { OfflineMarkerStartPayload.parse(it.content) }
                val locationHint = startPayload?.location?.takeIf { it.isNotEmpty() } ?: "某个地方"
                val character = characterRepo.get(convo.characterUuid)
                val userName = userProfileDao.get()?.nickname ?: ""
                val raw = SceneProgressService.generateProgress(
                    messages = sessionMessages,
                    characterName = character?.name ?: "",
                    userName = userName,
                    locationHint = locationHint,
                    config = config,
                    contextLog = contextLog,
                )
                if (raw.isBlank()) {
                    // 剥净思考标签后为空 = 纯思考响应：视同失败——设 3min 冷却、不推进计数、不空写覆盖旧节拍状态，冷却后重试这批。
                    lastSceneProgressUpdate = System.currentTimeMillis()
                    Log.w(TAG, "节拍状态生成为空（剥净思考后），冷却后重试")
                    return@launch
                }
                conversationRepo.updateSceneProgress(conversationUuid, raw)
                lastSceneProgressTriggerCount = userCount
                lastSceneProgressUpdate = now
                Log.d(TAG, "节拍状态已更新 userCount=$userCount")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败也设 3min 冷却（避免每条新消息重试）；不更新 triggerCount，冷却后重试这批（1:1 iOS）。
                lastSceneProgressUpdate = System.currentTimeMillis()
                Log.w(TAG, "节拍状态更新失败: ${e.message}")
            } finally {
                isUpdatingSceneProgress = false
            }
        }
    }

    private companion object {
        const val TAG = "SceneProgressTrigger"
    }
}
