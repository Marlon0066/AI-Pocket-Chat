package com.situ.aichat.ourdays

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.local.entity.OurDayNoteStatus
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OurDayCatchUpWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「我们的日子」沉淀协调器（总图纸 §3.7 表面 · 卷一图纸 §3.3 算法锁定）：`our_days` 行的**唯一自动写者**。
 *
 * - [catchUp]：全角色（按 `creationDate` 升序）逐角色在 per-uuid [Mutex] 内：活动索引 − 今天 − 未来 → 候选窗（标记 null = 全史回填；
 *   非 null = 近 7 天）→ 候选按日键**降序** → 每页 `processDay`（事实层落库 + 手记一次调用两产物）；30 页 / 轮跨角色总计、页间 300ms，
 *   有剩余自排 60s 续跑；候选耗尽才置回填标记（Z-5 Z-6 V-4 V-5）。
 * - [regenerate] / [refreshFacts] / [saveUserNote] / [setHidden] / [markDeleted]：卷三经此写行（同一把锁·E22）。
 * - 锁纪律：只持自己的 per-uuid Mutex，**不进**角色写锁 / 内核锁（Z-11·§9.5 grep 钉）；时钟只用注入的 [Clock]（§9.4）。
 * - Logcat 只打计数观测行（§9.5），手记正文 / 事实行绝不进日志。
 */
@Singleton
class OurDayCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: OurDaySourceLoader,
    private val noteService: OurDayNoteService,
    private val ourDayDao: OurDayDao,
    private val characterRepo: CharacterRepository,
    private val userProfileDao: UserProfileDao,
    private val scheduleDao: ScheduleDao,
    private val messageDao: MessageDao,
    private val apiConfigRepo: ApiConfigRepository,
    private val clock: Clock,
    private val backgroundScheduler: BackgroundScheduler,
) {
    data class CatchUpResult(val written: Int, val failed: Int, val hasMore: Boolean)

    /** 回填进度（进程内·卷三横幅）：置位后**移除**该角色条目。 */
    data class BackfillProgress(val done: Int, val total: Int)

    private val perCharacterLocks = ConcurrentHashMap<String, Mutex>()
    private val _backfillProgress = MutableStateFlow<Map<String, BackfillProgress>>(emptyMap())
    val backfillProgress: StateFlow<Map<String, BackfillProgress>> = _backfillProgress

    /** [DEFERRED]：该页需要 LLM 但本轮预算已用完——事实层已落库、手记留待续跑（R1 🔵-2）。 */
    private enum class PageResult { WRITTEN, FAILED, SKIPPED, DEFERRED }

    private fun lockFor(uuid: String): Mutex = perCharacterLocks.getOrPut(uuid) { Mutex() }

    suspend fun catchUp(): CatchUpResult {
        val now = clock.millis()
        val zone = clock.zone
        val today = OurDayKey.dayKey(now, zone)
        val characters = characterRepo.getAll().sortedBy { it.creationDate }
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.OUR_DAYS)
        if (config == null) {
            OurDayApiMissingFlag.set(context, true)
            logCatchUp(characters.size, candidates = 0, written = 0, failed = 0, remaining = 0, backfilled = 0)
            return CatchUpResult(0, 0, false)
        }
        OurDayApiMissingFlag.set(context, false)
        val nickname = userProfileDao.get()?.nickname?.trim().orEmpty()
        val windowStart = OurDayKey.keyOf(OurDayKey.parse(today)!!.minusDays(CATCH_UP_WINDOW_DAYS))

        var budget = PAGE_BUDGET
        var written = 0
        var failed = 0
        var hasMore = false
        var candidateTotal = 0
        var processed = 0
        var backfilled = 0
        for (character in characters) {
            val uuid = character.uuid
            lockFor(uuid).withLock {
                val sources = loader.load(uuid)
                val active = OurDayActivityIndex.activeDays(sources, zone).filter { it < today }
                val isBackfill = character.ourDaysBackfilledAt == null
                val window = if (isBackfill) active else active.filter { it >= windowStart }
                val existing = existingRows(uuid, window)
                val candidates = window.filter { key -> existing[key].let { it == null || isAutoCandidate(it) } }.sortedDescending()
                candidateTotal += candidates.size
                var done = active.size - candidates.size
                if (isBackfill) publishProgress(uuid, BackfillProgress(done, active.size))
                var exhausted = true
                for (dayKey in candidates) {
                    val fresh = characterRepo.get(uuid)
                    if (fresh == null) { exhausted = false; break } // E3：角色已删 ⇒ 跳过剩余候选
                    // R1 🔵-2（O-1）：预算只管真调用 LLM 的页——没进 LLM 的候选（无互动 / 守卫拦下）不计页、不等 300ms；
                    // 预算用完时遇到需要 LLM 的页 ⇒ DEFERRED（事实已落库、手记留待续跑），本轮到此为止。
                    val page = processDay(fresh, nickname, dayKey, sources, config, zone, allowLlm = budget > 0)
                    if (page == PageResult.DEFERRED) { hasMore = true; exhausted = false; break }
                    processed++
                    if (isBackfill) publishProgress(uuid, BackfillProgress(++done, active.size))
                    when (page) {
                        PageResult.WRITTEN -> written++
                        PageResult.FAILED -> failed++
                        PageResult.SKIPPED -> continue
                        PageResult.DEFERRED -> Unit // 已在上方 break
                    }
                    budget--
                    delay(PAGE_DELAY_MS)
                }
                if (isBackfill && exhausted) {
                    characterRepo.updateOurDaysBackfilledAt(uuid, now)
                    backfilled++
                    _backfillProgress.update { it - uuid }
                } else if (isBackfill && characterRepo.get(uuid) == null) {
                    _backfillProgress.update { it - uuid } // 角色已删：进度条目随之消失
                }
            }
            if (hasMore) break
        }
        logCatchUp(characters.size, candidateTotal, written, failed, remaining = candidateTotal - processed, backfilled = backfilled)
        if (hasMore) {
            backgroundScheduler.scheduleOneShot(
                uniqueName = OurDayCatchUpWorker.UNIQUE_CONTINUE,
                workerClass = OurDayCatchUpWorker::class.java,
                initialDelay = Duration.ofSeconds(CONTINUE_DELAY_SECONDS),
                requireNetwork = true,
                existingPolicy = ExistingWorkPolicy.REPLACE,
            )
        }
        return CatchUpResult(written, failed, hasMore)
    }

    /** 卷三「重写」（D-9 / V-6）：忽略 noteStatus 与 noteEdited；deleted 先复位；成功清 attempts；手动重写不计自动 attempts。 */
    suspend fun regenerate(characterUuid: String, dayKey: String): Boolean = lockFor(characterUuid).withLock {
        val row = ourDayDao.byDay(characterUuid, dayKey) ?: return false
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.OUR_DAYS) ?: return false
        val character = characterRepo.get(characterUuid) ?: return false
        val zone = clock.zone
        if (row.deleted) ourDayDao.upsert(row.copy(deleted = false))
        val nickname = userProfileDao.get()?.nickname?.trim().orEmpty()
        val sources = loader.load(characterUuid)
        val (facts, dayMessages) = buildFacts(characterUuid, dayKey, sources, zone)
        writeFacts(row.uuid, facts)
        val result = noteService.generate(character, nickname, dayKey, facts, dayMessages, config)
        val writeNow = clock.millis()
        if (result != null) {
            ourDayDao.updateGeneratedNote(row.uuid, result.note, result.factLine, OurDayNoteStatus.OK, 0, writeNow)
            true
        } else {
            ourDayDao.updateAttempt(row.uuid, OurDayNoteStatus.NONE, 0, writeNow)
            false
        }
    }

    /** 卷三日页打开（过去日）：事实重算；行存在 ⇒ 只更事实；不存在且有互动 ⇒ 建行（无手记）。今天恒不写（§9.4）。 */
    suspend fun refreshFacts(characterUuid: String, dayKey: String): OurDayEntity? = lockFor(characterUuid).withLock {
        val zone = clock.zone
        if (dayKey >= OurDayKey.dayKey(clock.millis(), zone)) return ourDayDao.byDay(characterUuid, dayKey)
        if (characterRepo.get(characterUuid) == null) return ourDayDao.byDay(characterUuid, dayKey)
        val sources = loader.load(characterUuid)
        val (facts, _) = buildFacts(characterUuid, dayKey, sources, zone)
        val row = ourDayDao.byDay(characterUuid, dayKey)
        when {
            row != null -> writeFacts(row.uuid, facts)
            facts.hasActivity -> ourDayDao.upsert(newRow(characterUuid, dayKey, facts))
        }
        ourDayDao.byDay(characterUuid, dayKey)
    }

    suspend fun saveUserNote(characterUuid: String, dayKey: String, note: String, factLine: String) = lockFor(characterUuid).withLock {
        val row = ourDayDao.byDay(characterUuid, dayKey) ?: return@withLock
        ourDayDao.updateUserNote(row.uuid, note, factLine, clock.millis())
    }

    suspend fun setHidden(characterUuid: String, dayKey: String, hidden: Boolean) = lockFor(characterUuid).withLock {
        val row = ourDayDao.byDay(characterUuid, dayKey) ?: return@withLock
        ourDayDao.updateHidden(row.uuid, hidden, clock.millis())
    }

    suspend fun markDeleted(characterUuid: String, dayKey: String) = lockFor(characterUuid).withLock {
        val row = ourDayDao.byDay(characterUuid, dayKey) ?: return@withLock
        ourDayDao.markDeleted(row.uuid, clock.millis())
    }

    // MARK: - 内部

    /** 一页（图纸 §3.3 processDay）：事实层先落库（空日无行·Z-3），再看手记守卫，最后一次 LLM 调用。 */
    private suspend fun processDay(
        character: CharacterEntity,
        nickname: String,
        dayKey: String,
        sources: OurDaySources,
        config: ApiConfigValues,
        zone: ZoneId,
        allowLlm: Boolean,
    ): PageResult {
        val uuid = character.uuid
        val (facts, dayMessages) = buildFacts(uuid, dayKey, sources, zone)
        val existing = ourDayDao.byDay(uuid, dayKey)
        if (!facts.hasActivity) {
            if (existing != null) writeFacts(existing.uuid, facts)
            return PageResult.SKIPPED
        }
        val row = if (existing == null) {
            newRow(uuid, dayKey, facts).also { ourDayDao.upsert(it) }
        } else {
            writeFacts(existing.uuid, facts)
            existing
        }
        if (row.deleted || row.noteEdited || row.noteStatus == OurDayNoteStatus.OK || row.noteAttempts >= MAX_AUTO_ATTEMPTS) {
            return PageResult.SKIPPED
        }
        if (!allowLlm) return PageResult.DEFERRED
        val result = noteService.generate(character, nickname, dayKey, facts, dayMessages, config)
        val writeNow = clock.millis() // LLM 后取时刻（活人感修缮卷 writeNow 先例）
        if (result != null) {
            ourDayDao.updateGeneratedNote(row.uuid, result.note, result.factLine, OurDayNoteStatus.OK, row.noteAttempts + 1, writeNow)
            return PageResult.WRITTEN
        }
        val attempts = row.noteAttempts + 1
        val status = if (attempts >= MAX_AUTO_ATTEMPTS) OurDayNoteStatus.FAILED else OurDayNoteStatus.NONE
        ourDayDao.updateAttempt(row.uuid, status, attempts, writeNow)
        return PageResult.FAILED
    }

    /** 当天消息（`[start, end)`·DAO 半开 ⇒ end 传 dayEnd）+ 当日日程 → 事实；同一批消息同时喂提示词（不重复查询）。 */
    private suspend fun buildFacts(uuid: String, dayKey: String, sources: OurDaySources, zone: ZoneId): Pair<OurDayFacts, List<MessageEntity>> {
        val bounds = OurDayKey.dayBounds(dayKey, zone)
        val dayMessages = messageDao.messagesForCharacterInRange(uuid, bounds.first, bounds.last + 1, MESSAGE_LIMIT)
        val events = scheduleDao.scheduleFor(uuid, bounds.first)?.let { scheduleDao.eventsForSchedule(it.uuid) } ?: emptyList()
        return OurDayFactsBuilder.build(sources, dayMessages, events, dayKey, zone) to dayMessages
    }

    private fun newRow(characterUuid: String, dayKey: String, facts: OurDayFacts): OurDayEntity {
        val now = clock.millis()
        return OurDayEntity(
            uuid = UUID.randomUUID().toString(), characterUuid = characterUuid, dayKey = dayKey,
            factsJson = OurDayFactsJson.encode(facts), messageCount = facts.messageCount, callSeconds = facts.callSeconds,
            hasMeeting = facts.hasMeeting, hasRelation = facts.hasRelation, hasLife = facts.hasLife,
            createdAtMillis = now, updatedAtMillis = now,
        )
    }

    private suspend fun writeFacts(rowUuid: String, facts: OurDayFacts) {
        ourDayDao.updateFacts(
            rowUuid, OurDayFactsJson.encode(facts), facts.messageCount, facts.callSeconds,
            facts.hasMeeting, facts.hasRelation, facts.hasLife, clock.millis(),
        )
    }

    /** 候选窗内既有行（一次 daysInRange 覆盖 min..max）。 */
    private suspend fun existingRows(uuid: String, window: List<String>): Map<String, OurDayEntity> {
        if (window.isEmpty()) return emptyMap()
        return ourDayDao.daysInRange(uuid, window.min(), window.max()).associateBy { it.dayKey }
    }

    private fun isAutoCandidate(row: OurDayEntity): Boolean =
        row.noteStatus == OurDayNoteStatus.NONE && row.noteAttempts < MAX_AUTO_ATTEMPTS && !row.deleted && !row.noteEdited

    private fun publishProgress(uuid: String, progress: BackfillProgress) {
        _backfillProgress.update { it + (uuid to progress) }
    }

    /** 观测行（总图纸 §3.8 锁定格式·只打计数）。 */
    private fun logCatchUp(characters: Int, candidates: Int, written: Int, failed: Int, remaining: Int, backfilled: Int) {
        Log.i(TAG, "OurDays: catchUp 角色=$characters 候选=$candidates 写成=$written 失败=$failed 剩余=$remaining 回填置位=$backfilled")
    }

    companion object {
        const val TAG = "OurDays"
        /** Z-6：每轮最多 30 页（跨角色总计）。 */
        const val PAGE_BUDGET = 30
        /** Z-6：页间 300ms。 */
        const val PAGE_DELAY_MS = 300L
        /** Z-6：有剩余 ⇒ 60s 后续跑。 */
        const val CONTINUE_DELAY_SECONDS = 60L
        /** Z-5：标记置位后每日只扫 [今天 − 7, 今天)。 */
        const val CATCH_UP_WINDOW_DAYS = 7L
        /** Z-7：自动生成失败 3 次转 failed。 */
        const val MAX_AUTO_ATTEMPTS = 3
        /** E18：当天消息最多取 2000 条（计数 / 素材共用）。 */
        const val MESSAGE_LIMIT = 2000
    }
}
