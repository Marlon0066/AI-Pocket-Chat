package com.situ.aichat.relationship

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.Notifier
import com.situ.aichat.offline.OfflineMeetingGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 关系里程碑庆祝编排（P1-33·拍板 A·安卓超越 iOS 零通知）：开关（默认开）→ 纯函数决策
 * （[milestoneCelebrationDecision]：仅 aiAutomatic+首次达到+高于历史最高）→ 前台 = 应用内 Toast
 * （聊天/通话任意屏可见·关系评估几乎总在前台聊天时触发=默认态），后台 = MILESTONE 渠道系统通知
 * 深链该角色资料页（固定 id 替换式=「批量合并」降级裁定：单次评估永远单角色，跨角色近同时升级近空集）。
 * **不显金币数**（拍板）；开关关 = 全静默 = iOS 自动路径原生行为。失败不得影响关系写回（调用方 runCatching）。
 */
@Singleton
class MilestoneCelebrationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val conversationDao: ConversationDao,
) {

    /** P1-44 单槽：最后一条系统庆祝通知归属的角色 uuid。设备本地通知栏状态，自管 prefs 不进应用备份
     *  （进 AppSettings 会随备份换机带脏槽=错误归属）。 */
    private val prefs by lazy { context.getSharedPreferences("milestone_celebration", Context.MODE_PRIVATE) }

    suspend fun onMilestoneAchieved(
        characterUuid: String,
        characterName: String,
        historyNames: List<String>,
        newName: String,
        triggerTypeRaw: String,
    ) {
        if (!settingsRepo.getAppSettings().milestoneNotificationEnabled) return
        if (!milestoneCelebrationDecision(historyNames, newName, triggerTypeRaw)) {
            Log.i(TAG, "抑制（phase-only/已见/降级/未知名/非自动）：$characterName → $newName")
            return
        }
        // 见面闸（卷一 B5·J7）：该角色正在与用户线下见面 → 本次庆祝跳过（Toast 盖不住恒暗剧场、
        // 通知同样穿帮）。**数据照记**（关系名分本体已落库，资料页可见），不做「攒到见面后补庆祝」。
        if (OfflineMeetingGate.characterInMeeting(conversationDao, characterUuid)) {
            Log.i(TAG, "里程碑庆祝跳过：见面进行中（数据已落）：$characterName → $newName")
            return
        }
        // 前台判定须在主线程读 ProcessLifecycleOwner（无现成全局 isForeground 状态）。
        val foreground = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (foreground) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.milestone_toast, characterName, newName),
                    Toast.LENGTH_LONG,
                ).show()
            }
            Log.i(TAG, "✓ 前台 toast：$characterName → $newName")
        } else {
            val posted = Notifier.postMilestone(
                context,
                characterUuid = characterUuid,
                title = characterName,
                body = context.getString(R.string.notif_milestone_body, newName),
            )
            // P1-44：仅真发出才记槽——权限被收回期间另一角色升级若无条件覆盖槽位，会让真正可见的
            // 前一角色庆祝失去匹配保护（删它不撤=残影复活）。
            if (posted) prefs.edit().putString(KEY_LAST_CELEBRATED, characterUuid).apply()
            Log.i(TAG, "✓ 系统通知：$characterName → $newName（posted=$posted）")
        }
    }

    /**
     * 删角色撤里程碑庆祝（P1-44 拍板=单槽匹配才撤）：MILESTONE_NOTIFICATION_ID 为全角色共享固定 id
     * （P1-33 替换式设计），盲撤会误伤他角色未读庆祝 → 仅「最后庆祝者==被删角色」才撤并清槽。
     * 槽位陈旧（用户已划掉通知）→ cancel no-op + 清槽，无害。由 CharacterDeletionCleaner 调用。
     */
    fun purgeForCharacter(characterUuid: String) {
        if (shouldPurgeMilestone(prefs.getString(KEY_LAST_CELEBRATED, null), characterUuid)) {
            NotificationManagerCompat.from(context).cancel(Notifier.MILESTONE_NOTIFICATION_ID)
            prefs.edit().remove(KEY_LAST_CELEBRATED).apply()
        }
    }

    companion object {
        private const val TAG = "MilestoneNotify"
        private const val KEY_LAST_CELEBRATED = "last_celebrated_character"

        /** 纯函数（internal 供单测）：槽位非空且==被删角色才撤。 */
        internal fun shouldPurgeMilestone(slotUuid: String?, deletedUuid: String): Boolean =
            slotUuid != null && slotUuid == deletedUuid
    }
}
