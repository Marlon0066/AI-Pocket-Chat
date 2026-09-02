package com.situ.aichat.gift

import android.util.Log
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色主动送礼维护编排（1:1 iOS `AppBootstrapService.runProactiveGiftMaintenance`）。App 回前台时遍历所有角色，逐只
 * **串行**执行（避免 LLM 并发触发 API rate limit）：扫 5 类触发 → 同日总闸门 → 月上限 → 候选过滤 → LLM 决策 → 执行送礼。
 *
 * **守卫**：`currencySystemEnabled && characterProactiveGiftEnabled` 任一关 → 整块跳过（不依赖 scheduleSystemEnabled，
 * 主动送礼是纯货币能力）。**幂等**全靠执行器的幂等 key（`proactive_gift_{uuid}_{yyyyMMdd}_{type}_{meta}`）+ 同日闸门 +
 * last*Date，回前台反复调安全。重入锁防多次 ON_RESUME 叠跑。LLM 决策复用 CHAT 路由（同 iOS）。
 *
 * **接线顺序**：在 [com.situ.aichat.ui.AppViewModel] 回前台**经济维护之后**调用——角色须先发薪（9.1b）钱包有月薪，
 * 经济档位/预算才非零，主动送礼才真能触发。
 */
@Singleton
class ProactiveGiftMaintenanceService @Inject constructor(
    private val characterRepo: CharacterRepository,
    private val currencyDao: CurrencyDao,
    private val userProfileDao: UserProfileDao,
    private val scheduler: ProactiveGiftScheduler,
    private val candidateFilter: ProactiveGiftCandidateFilter,
    private val llmService: ProactiveGiftLLMService,
    private val executor: ProactiveGiftExecutor,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
) {
    private val running = AtomicBoolean(false)

    suspend fun runMaintenance(zone: ZoneId = ZoneId.systemDefault()) {
        if (!running.compareAndSet(false, true)) return
        try {
            val settings = settingsRepo.getAppSettings()
            if (!settings.currencySystemEnabled || !settings.characterProactiveGiftEnabled) {
                Log.d(TAG, "主动送礼维护跳过·货币或主动送礼开关关闭")
                return
            }
            val characters = characterRepo.getAll()
            if (characters.isEmpty()) return

            val profile = userProfileDao.get()
            val userBirthday = profile?.birthday
            // LLM 决策走 CHAT 路由（未配置 API → config 为 null，decide 内部直接走 rule 兜底）
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT)

            for (character in characters) {
                // 每角色取 fresh now（=iOS 循环内 Date()，LLM await 期间真实时间会推进）
                val now = System.currentTimeMillis()
                val ctx = scheduler.buildContext(character, userBirthday, now, userName = profile?.nickname?.trim().orEmpty()) // 卷四 K-20：意图句用真名
                val trigger = ctx.topTrigger
                if (!ctx.hasAnyCandidate || trigger == null) {
                    Log.d(TAG, "主动送礼跳过·无触发 ${character.name}")
                    continue
                }

                // 同日总闸门：今天已主动送过且 topTrigger 不豁免 → 跳过（生日/纪念日/节日豁免，重复由执行器幂等 key 兜底）
                val lastGift = currencyDao.getCharacterWallet(character.uuid)?.lastProactiveGiftDate
                if (lastGift != null && isSameLocalDay(lastGift, now, zone) &&
                    !ProactiveGiftScheduler.shouldBypassDailyGate(trigger.type)
                ) {
                    Log.d(TAG, "主动送礼跳过·今日已送 ${character.name}")
                    continue
                }

                if (scheduler.hasReachedMonthlyLimit(character.uuid, now)) {
                    Log.d(TAG, "主动送礼跳过·月上限 ${character.name}")
                    continue
                }

                val candidates = candidateFilter.filterCandidates(ctx, trigger, now)
                if (candidates.isEmpty()) {
                    Log.d(TAG, "主动送礼跳过·无候选礼物 ${character.name} 触发=${trigger.type.raw}")
                    continue
                }

                val decision = llmService.decide(ctx, trigger, candidates, character, config)
                val result = executor.execute(decision, trigger, character, now)
                Log.d(TAG, "主动送礼 ${character.name} 触发=${trigger.type.raw} → $result")
            }
            Log.d(TAG, "主动送礼维护完成：${characters.size} 个角色")
        } finally {
            running.set(false)
        }
    }

    private companion object {
        const val TAG = "ProactiveGiftMaint"

        /** 两时间戳是否同一自然日（设备时区；= iOS `Calendar.current.isDateInToday`，中国无夏令时）。 */
        fun isSameLocalDay(a: Long, b: Long, zone: ZoneId): Boolean =
            Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    }
}
