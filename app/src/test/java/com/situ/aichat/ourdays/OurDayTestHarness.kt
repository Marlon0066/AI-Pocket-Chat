package com.situ.aichat.ourdays

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.prompt.growth.MutableClock
import com.situ.aichat.work.BackgroundScheduler
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 内存版 [OurDayDao]（T2-1 / T2-2 夹具）：真语义 + 调用计数，替代对 12 条 DAO 方法逐条 coEvery。 */
internal class FakeOurDayDao : OurDayDao {
    val rows = LinkedHashMap<String, OurDayEntity>()
    var upserts = 0
    var factsUpdates = 0
    var noteUpdates = 0
    var attemptUpdates = 0

    private fun range(c: String, from: String, to: String) =
        rows.values.filter { it.characterUuid == c && it.dayKey >= from && it.dayKey <= to }.sortedBy { it.dayKey }
    private inline fun mutate(uuid: String, f: (OurDayEntity) -> OurDayEntity) { rows[uuid]?.let { rows[uuid] = f(it) } }

    override suspend fun upsert(row: OurDayEntity) { upserts++; rows[row.uuid] = row }
    override suspend fun byUuid(uuid: String) = rows[uuid]
    override suspend fun byDay(characterUuid: String, dayKey: String) =
        rows.values.firstOrNull { it.characterUuid == characterUuid && it.dayKey == dayKey }
    override suspend fun dayKeysForCharacter(characterUuid: String) = rows.values.filter { it.characterUuid == characterUuid }.map { it.dayKey }
    override suspend fun daysInRange(characterUuid: String, fromKey: String, toKey: String) = range(characterUuid, fromKey, toKey)
    override fun observeDaysInRange(characterUuid: String, fromKey: String, toKey: String): Flow<List<OurDayEntity>> = flowOf(range(characterUuid, fromKey, toKey))
    override fun observeAllInRange(fromKey: String, toKey: String): Flow<List<OurDayEntity>> =
        flowOf(rows.values.filter { it.dayKey >= fromKey && it.dayKey <= toKey }.sortedWith(compareBy({ it.dayKey }, { it.characterUuid })))
    override suspend fun countForCharacter(characterUuid: String) = rows.values.count { it.characterUuid == characterUuid && !it.deleted }
    override suspend fun updateFacts(uuid: String, factsJson: String, messageCount: Int, callSeconds: Int, hasMeeting: Boolean, hasRelation: Boolean, hasLife: Boolean, now: Long) {
        factsUpdates++
        mutate(uuid) { it.copy(factsJson = factsJson, messageCount = messageCount, callSeconds = callSeconds, hasMeeting = hasMeeting, hasRelation = hasRelation, hasLife = hasLife, updatedAtMillis = now) }
    }
    override suspend fun updateGeneratedNote(uuid: String, note: String, factLine: String, status: String, attempts: Int, now: Long) {
        noteUpdates++
        mutate(uuid) { it.copy(note = note, factLine = factLine, noteStatus = status, noteAttempts = attempts, noteEdited = false, generatedAt = now, updatedAtMillis = now, embedding = null) }
    }
    override suspend fun updateAttempt(uuid: String, status: String, attempts: Int, now: Long) {
        attemptUpdates++
        mutate(uuid) { it.copy(noteStatus = status, noteAttempts = attempts, updatedAtMillis = now) }
    }
    override suspend fun updateUserNote(uuid: String, note: String, factLine: String, now: Long) =
        mutate(uuid) { it.copy(note = note, factLine = factLine, noteEdited = true, noteStatus = "ok", deleted = false, generatedAt = now, updatedAtMillis = now, embedding = null) }
    override suspend fun updateHidden(uuid: String, hidden: Boolean, now: Long) = mutate(uuid) { it.copy(hiddenFromMemory = hidden, embedding = null, updatedAtMillis = now) }
    override suspend fun markDeleted(uuid: String, now: Long) =
        mutate(uuid) { it.copy(deleted = true, note = "", factLine = "", noteStatus = "none", embedding = null, updatedAtMillis = now) }
    override suspend fun getAll() = rows.values.toList()
    override suspend fun deleteByCharacter(characterUuid: String) { rows.values.removeIf { it.characterUuid == characterUuid } }
    // 卷二追加（接口新增 → 夹具必补 override·真语义）
    override suspend fun injectableForCharacter(characterUuid: String) =
        rows.values.filter { it.characterUuid == characterUuid && !it.deleted && !it.hiddenFromMemory && it.factLine.isNotEmpty() }.sortedBy { it.dayKey }
    override suspend fun missingEmbedding(limit: Int) =
        rows.values.filter { it.embedding == null && it.factLine.isNotEmpty() && !it.hiddenFromMemory && !it.deleted }.take(limit)
    override suspend fun updateEmbedding(uuid: String, embedding: ByteArray) = mutate(uuid) { it.copy(embedding = embedding) }
    override suspend fun clearAllEmbeddings(): Int {
        val n = rows.values.count { it.embedding != null }
        rows.replaceAll { _, r -> if (r.embedding != null) r.copy(embedding = null) else r }
        return n
    }
    override suspend fun embeddedForCharacter(characterUuid: String) =
        rows.values.filter { it.characterUuid == characterUuid && it.embedding != null && !it.deleted && !it.hiddenFromMemory }
    // 卷三追加（接口新增 → 夹具必补 override·真语义·谓词逐字照图纸 §3.1 六条 SQL）
    private fun OurDayEntity.toCalendarRow() = OurDayCalendarRow(
        uuid, characterUuid, dayKey, factsJson, messageCount, callSeconds, hasMeeting, hasRelation, hasLife, note, factLine,
        noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, generatedAt, createdAtMillis, updatedAtMillis,
    )
    override fun observeCalendarRange(characterUuid: String, fromKey: String, toKey: String): Flow<List<OurDayCalendarRow>> =
        flowOf(range(characterUuid, fromKey, toKey).map { it.toCalendarRow() })
    override fun observeCalendarRangeAll(fromKey: String, toKey: String): Flow<List<OurDayCalendarRow>> =
        flowOf(rows.values.filter { it.dayKey >= fromKey && it.dayKey <= toKey }.sortedWith(compareBy({ it.dayKey }, { it.characterUuid })).map { it.toCalendarRow() })
    override fun observeCalendarRow(characterUuid: String, dayKey: String): Flow<OurDayCalendarRow?> =
        flowOf(rows.values.firstOrNull { it.characterUuid == characterUuid && it.dayKey == dayKey }?.toCalendarRow())
    override fun observeFirstDayKey(characterUuid: String): Flow<String?> =
        flowOf(rows.values.filter { it.characterUuid == characterUuid && !it.deleted }.minOfOrNull { it.dayKey })
    override fun observeFirstMeetingDayKey(characterUuid: String): Flow<String?> =
        flowOf(rows.values.filter { it.characterUuid == characterUuid && it.hasMeeting && !it.deleted }.minOfOrNull { it.dayKey })
    override fun observeMeetingDayCount(characterUuid: String): Flow<Int> =
        flowOf(rows.values.count { it.characterUuid == characterUuid && it.hasMeeting && !it.deleted })
    override fun observeLatestActiveCharacterUuid(): Flow<String?> = flowOf(
        rows.values.filter { !it.deleted && (it.messageCount > 0 || it.callSeconds > 0 || it.hasMeeting || it.hasRelation || it.hasLife) }
            .sortedWith(compareByDescending<OurDayEntity> { it.dayKey }.thenByDescending { it.updatedAtMillis })
            .firstOrNull()?.characterUuid,
    )
}

/**
 * 协调器测试台（T2-1 / T2-2）：真 [OurDayCoordinator] + 真 [OurDayNoteService] + [FakeOurDayDao]；其余协作者 MockK
 * （角色仓 / 用户资料 / 日程 / 消息 / API 配置 / 排程器 / 源载入），[MutableClock] 拨时间，假 LLM 按 [llmAnswer] 回。
 * 消息用 `conversationUuid == characterUuid` 归属；源载入只喂消息时间戳（各源口径由 T1-2 / T1-3 覆盖）。
 */
internal class OurDayHarness(val zone: ZoneId, startMillis: Long, nickname: String? = null) {
    val clock = MutableClock(startMillis, zone)
    val dao = FakeOurDayDao()
    val messages = mutableListOf<MessageEntity>()
    val characters = mutableListOf<CharacterEntity>()
    val backfillMarks = mutableListOf<Pair<String, Long>>()
    val llmCalls = mutableListOf<Pair<String, List<ChatMessageDto>>>()
    /** 假 LLM（suspend：并发用例可在里面等门·runTest 单线程下不能用 runBlocking 阻塞）。 */
    var llmAnswer: suspend (List<ChatMessageDto>) -> String = { OK_JSON }
    var finishReason: String? = "stop"
    var config: ApiConfigValues? = ApiConfigValues(providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k", baseUrl = "https://x", modelName = "m")
    var extraSources: (String) -> OurDaySources = { OurDaySources.EMPTY }

    val contextLog = mockk<ContextLogService>()
    val backgroundScheduler = mockk<BackgroundScheduler>(relaxed = true)
    private val loader = mockk<OurDaySourceLoader>()
    private val characterRepo = mockk<CharacterRepository>()
    private val userProfileDao = mockk<UserProfileDao>()
    private val scheduleDao = mockk<ScheduleDao>()
    private val messageDao = mockk<MessageDao>()
    private val apiConfigRepo = mockk<ApiConfigRepository>()

    val coordinator: OurDayCoordinator

    init {
        coEvery { loader.load(any()) } answers {
            val uuid = firstArg<String>()
            val extra = extraSources(uuid)
            extra.copy(messageTimestamps = extra.messageTimestamps + messages.filter { it.conversationUuid == uuid && it.content.isNotEmpty() }.map { it.timestamp })
        }
        coEvery { characterRepo.getAll() } answers { characters.toList() }
        coEvery { characterRepo.get(any()) } answers { characters.firstOrNull { it.uuid == firstArg<String>() } }
        coEvery { characterRepo.updateOurDaysBackfilledAt(any(), any()) } answers {
            val uuid = firstArg<String>(); val millis = secondArg<Long>()
            backfillMarks += uuid to millis
            characters.replaceAll { if (it.uuid == uuid) it.copy(ourDaysBackfilledAt = millis) else it }
        }
        coEvery { userProfileDao.get() } answers { nickname?.let { UserProfileEntity(nickname = it) } }
        coEvery { scheduleDao.scheduleFor(any(), any()) } returns null
        coEvery { messageDao.messagesForCharacterInRange(any(), any(), any(), any()) } answers {
            val uuid = arg<String>(0); val start = arg<Long>(1); val end = arg<Long>(2); val limit = arg<Int>(3)
            messages.filter { it.conversationUuid == uuid && it.timestamp >= start && it.timestamp < end }.sortedBy { it.timestamp }.take(limit)
        }
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.OUR_DAYS) } answers { config }
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val msgs = arg<List<ChatMessageDto>>(3)
            llmCalls += arg<String>(1) to msgs
            arg<((String?) -> Unit)?>(8)?.invoke(finishReason)
            llmAnswer(msgs)
        }
        coordinator = OurDayCoordinator(
            context = RuntimeEnvironment.getApplication(), loader = loader, noteService = OurDayNoteService(contextLog, clock),
            ourDayDao = dao, characterRepo = characterRepo, userProfileDao = userProfileDao, scheduleDao = scheduleDao,
            messageDao = messageDao, apiConfigRepo = apiConfigRepo, clock = clock, backgroundScheduler = backgroundScheduler,
        )
    }

    fun at(date: LocalDate, h: Int, m: Int = 0): Long = date.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    fun addCharacter(uuid: String, name: String = "林晚", creationDate: Long = 0L, backfilledAt: Long? = null): CharacterEntity {
        val c = CharacterEntity(uuid = uuid, name = name, creationDate = creationDate, personalityDescription = "温柔话少", ourDaysBackfilledAt = backfilledAt)
        characters += c
        return c
    }

    fun chat(uuid: String, millis: Long, content: String = "聊两句", role: String = "user", kind: String = "plain_text") {
        messages += MessageEntity(messageUUID = "m${messages.size}", conversationUuid = uuid, roleRaw = role, content = content, timestamp = millis, messageKindRaw = kind)
    }

    fun rowsOf(uuid: String) = dao.rows.values.filter { it.characterUuid == uuid }.sortedBy { it.dayKey }

    /** 跟着协调器的自排续跑：有剩余就再跑，直到本轮候选耗尽（模拟 `our_days_continue`）。 */
    suspend fun catchUpUntilDone(): List<OurDayCoordinator.CatchUpResult> {
        val results = mutableListOf<OurDayCoordinator.CatchUpResult>()
        do { val r = coordinator.catchUp(); results += r } while (r.hasMore)
        return results
    }

    companion object {
        const val OK_NOTE = "今天和小明聊了考试的事，她说数学考砸了，我安慰了很久。晚上她发来一张夕阳的照片，我们说好周末去看海。"
        const val OK_JSON = """{"note": "$OK_NOTE", "factLine": "林晚和小明聊了考试，约好周末去看海"}"""
    }
}
