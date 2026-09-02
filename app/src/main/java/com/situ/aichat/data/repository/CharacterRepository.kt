package com.situ.aichat.data.repository

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.prompt.growth.RelationshipArchetypeCalibrator
import com.situ.aichat.data.model.appendMoodEntry
import com.situ.aichat.world.cast.WorldResidentService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD for the character aggregate, mirroring how iOS `CharacterDetailView.save()` persists an
 * `AICharacter` (Models/AICharacter.swift) together with its initial `RelationshipMilestone`.
 *
 * The `CharacterEntity` row carries every stored property of the iOS model. Two iOS relations are
 * deliberately deferred: `pet` (CharacterPet → P8) and `wallet` (CharacterWallet → P9). The
 * `conversations` relation is owned by [ConversationRepository] (FK CASCADE), and the
 * `relationshipMilestones` relation is handled here via [MilestoneDao] (FK CASCADE), exactly as iOS
 * couples milestone creation to character save.
 *
 * iOS edit-save side effects now live with their owning layer: persona-change invalidation of the
 * affinity-sense package is a column-level write here ([updateAffinitySenseGeneratedAt]); occupation-change
 * salary re-inference resets the wallet via [com.situ.aichat.economy.CurrencyService.clearSalaryInferred].
 * The edit form persists profile columns via [updateEditableProfile] (D1c column-level write, not full-row
 * [update]) and appends relationship changes via [recordRelationship].
 */
@Singleton
class CharacterRepository @Inject constructor(
    private val dao: CharacterDao,
    private val milestoneDao: MilestoneDao,
    // 删角色需在单事务内先清该角色的世界痕迹（关系边/事件/在途/世界事件提及/原住民招募指针·均无 FK）再删角色。
    private val db: AppDatabase,
    // 战役 B（O2·图纸 §3.3）：删已招募的用户自建居民时连坐删其 def+state 行（取代官方的 resetRecruitment）。
    private val residentService: WorldResidentService,
    // 成长原型校准（图纸 §3.3 入口①）：recordRelationship 抬分到原型地板的唯一执行者。
    private val archetypeCalibrator: RelationshipArchetypeCalibrator,
) {
    // MARK: - Reads

    fun observeAll(): Flow<List<CharacterEntity>> = dao.observeAll()
    fun observe(uuid: String): Flow<CharacterEntity?> = dao.observeByUuid(uuid)
    suspend fun get(uuid: String): CharacterEntity? = dao.getByUuid(uuid)
    suspend fun count(): Int = dao.count()

    /** All characters (one-shot). Used by diary settings' per-character comment selector (P7.1.5). */
    suspend fun getAll(): List<CharacterEntity> = dao.getAll()

    // MARK: - Create

    /**
     * Quick-create used by the minimal chat-list flow. Generates a fresh uuid + creationDate and
     * persists a character with only the essentials; all other fields take their iOS defaults
     * (encoded as [CharacterEntity] property defaults). Optionally records an initial relationship.
     */
    suspend fun create(
        name: String,
        systemPrompt: String = "",
        personalityDescription: String = "",
        initialRelationshipName: String = "",
    ): String = insert(
        CharacterEntity(
            uuid = UUID.randomUUID().toString(),
            name = name,
            systemPrompt = systemPrompt,
            personalityDescription = personalityDescription,
            creationDate = System.currentTimeMillis(),
        ),
        initialRelationshipName = initialRelationshipName,
    )

    /**
     * Full create from a caller-built entity (the P2.2 create form / backup restore build the row
     * with all identity, voice, location and growth fields). Mirrors iOS `save()` `.create`:
     * inserts the character, then — if an initial relationship name is given — appends the
     * "初始设定" milestone. Returns the character uuid.
     */
    suspend fun insert(
        entity: CharacterEntity,
        initialRelationshipName: String = "",
        relationshipSlidersTouched: Boolean = false,
    ): String {
        dao.upsert(entity)
        recordRelationship(
            entity.uuid,
            initialRelationshipName,
            reason = "初始设定",
            relationshipSlidersTouched = relationshipSlidersTouched,
        )
        return entity.uuid
    }

    // MARK: - Update

    /** Full-field overwrite of an existing character row (edit form). */
    suspend fun update(entity: CharacterEntity) = dao.update(entity)

    /** Insert-or-update; used by the chat path for incremental field writes (mood, memory, …). */
    suspend fun upsert(entity: CharacterEntity) = dao.upsert(entity)

    // MARK: - 列级定向写（P12.6 D1）：每轮计数器只写自己那列；须在 [CharacterWriteLock] 内调用。
    /** 成长轮次计数器写回（仅 growthMetadataJSON）。 */
    suspend fun updateGrowthMetadata(uuid: String, growthMetadata: String) =
        dao.updateGrowthMetadata(uuid, growthMetadata)

    /** 结构化记忆元数据写回（仅 structuredMemoryMetadataJSON）。 */
    suspend fun updateStructuredMemoryMetadata(uuid: String, metadata: String) =
        dao.updateStructuredMemoryMetadata(uuid, metadata)

    /** 关系消息计数写回（仅 relationshipMessageCount）。 */
    suspend fun updateRelationshipMessageCount(uuid: String, count: Int) =
        dao.updateRelationshipMessageCount(uuid, count)

    /** 「我们的日子」一次性回填完成标记（卷一图纸 §2.2·列级盲写·不进 CharacterWriteLock·唯一调用方 OurDayCoordinator）。 */
    suspend fun updateOurDaysBackfilledAt(uuid: String, millis: Long) =
        dao.updateOurDaysBackfilledAt(uuid, millis)

    // MARK: - 列级定向写（P12.6 D1b 二期）：礼物/情感/恢复/聊天剩余整行写改列级。
    // 礼物的关系/成长是读-改-写，须在 [CharacterWriteLock] 内、锁内 fresh 读后调；其余为盲写/单写点列级即可。

    /** 关系质感回写（净额 + 压强两列恒同条 UPDATE·卷二 §3.2 J-2）。 */
    suspend fun updateRelationshipQuality(uuid: String, relationshipQuality: String, relationshipPressure: String) =
        dao.updateRelationshipQuality(uuid, relationshipQuality, relationshipPressure)

    /** 关系质感 + 成长日志回写（+ 压强列，卷二 §3.2 J-2）。 */
    suspend fun updateRelationshipQualityAndGrowthLog(
        uuid: String,
        relationshipQuality: String,
        growthLog: String,
        relationshipPressure: String,
    ) = dao.updateRelationshipQualityAndGrowthLog(uuid, relationshipQuality, growthLog, relationshipPressure)

    /** 成长日志回写（仅 growthLogJSON）。 */
    suspend fun updateGrowthLog(uuid: String, growthLog: String) =
        dao.updateGrowthLog(uuid, growthLog)

    /** 心意反馈文案包回写（包 JSON + 生成时间两列）。 */
    suspend fun updateAffinitySensePackage(uuid: String, packageJson: String, generatedAt: Long) =
        dao.updateAffinitySensePackage(uuid, packageJson, generatedAt)

    /**
     * 心意反馈文案包失效戳写回（仅 affinitySensePackageGeneratedAt 一列）。编辑保存时人设字段变更置 null 强刷，
     * 下次送礼 [com.situ.aichat.gift.AffinitySenseService] 按新人设重生成（1:1 iOS save() .edit 的
     * `affinitySensePackageGeneratedAt = nil`）。
     */
    suspend fun updateAffinitySenseGeneratedAt(uuid: String, generatedAt: Long?) =
        dao.updateAffinitySenseGeneratedAt(uuid, generatedAt)

    /** 最近心情三列回写。 */
    suspend fun updateMood(uuid: String, emoji: String, text: String, colorName: String) =
        dao.updateMood(uuid, emoji, text, colorName)

    /**
     * 情绪历史追加专用轻锁——仅串行「读-改-写 moodHistoryJSON」这一步（纯 CPU + 单列 UPDATE，微秒级，无 IO/LLM 等待）。
     *
     * **为何不复用 [CharacterWriteLock]**：那把每角色锁会在后台成长/关系分析期间被长占（锁内含 LLM 调用、数秒），
     * 心情归档若去抢它，会把前台「发消息」拖在后台分析后面。而 moodHistoryJSON 是孤儿列、[appendMoodHistory] 是唯一写者，
     * 单列 UPDATE 与成长/关系/计数器写的列集互不相交，独立轻锁即足以保证「并发追加不丢」。
     */
    private val moodHistoryAppendLock = Mutex()

    /**
     * 追加一条情绪历史到角色级 moodHistory（成长系统）。锁内 fresh 读现列 → 追加 → 超 [maxCount] 截断（保留最新）→ 列级写回。
     *
     * 由聊天 / 未答恢复两处「解析到心情」后调用，复活：情绪低落送礼 ×1.5 加成、角色主动暖心送礼、善解人意印象标签
     * （此前全工程无任何写入方，moodHistory 恒空，三处消费者静默失效）。[entry] 的 timestamp 必须由调用方填真实时刻
     * （默认 0L 会让 24h 窗口的消费者永远落空）。
     */
    suspend fun appendMoodHistory(uuid: String, entry: MoodHistoryEntry, maxCount: Int) {
        moodHistoryAppendLock.withLock {
            val current = dao.getByUuid(uuid) ?: return@withLock
            val updated = appendMoodEntry(GrowthJson.decodeMoodHistory(current.moodHistoryJSON), entry, maxCount)
            dao.updateMoodHistory(uuid, GrowthJson.encodeMoodHistory(updated))
        }
    }

    /** 火花计数 + 最近聊天日两列回写。 */
    suspend fun updateStreak(uuid: String, streakCount: Int, lastChatDate: Long) =
        dao.updateStreak(uuid, streakCount, lastChatDate)

    /** 「第一次聊天时间」只往早改（相识天数图纸 §4.1·守卫在 SQL 里）；返回受影响行数（0 / 1）。 */
    suspend fun markFirstMessageDate(uuid: String, ts: Long): Int = dao.markFirstMessageDate(uuid, ts)

    /**
     * 编辑保存：列级写回 20 个表单可编辑列（P12.6 D1c），不再整行覆盖成长/关系/心情/记忆列。
     * 与 [update]（建角色/备份导入的正当全字段写）区分：编辑表单只该改 profile 列。
     */
    suspend fun updateEditableProfile(
        uuid: String,
        name: String,
        avatarPath: String?,
        systemPrompt: String,
        personalityDescription: String,
        gender: String,
        birthday: Long?,
        ageModeRaw: String,
        fixedAge: Int,
        appearanceDescription: String,
        occupation: String,
        backstory: String,
        speakingStyle: String,
        catchphrases: String,
        exampleDialogues: String,
        initialInterests: String,
        voiceIdentifier: String,
        remoteVoiceID: String,
        ttsEmotionRaw: String,
        ttsSpeed: Double,
        ttsPitch: Int,
        offlineThemeColorHex: String?,
        chatWallpaperPath: String?,
    ) = dao.updateEditableProfile(
        uuid, name, avatarPath, systemPrompt, personalityDescription, gender, birthday, ageModeRaw, fixedAge,
        appearanceDescription, occupation, backstory, speakingStyle, catchphrases, exampleDialogues,
        initialInterests, voiceIdentifier, remoteVoiceID, ttsEmotionRaw, ttsSpeed, ttsPitch, offlineThemeColorHex,
        chatWallpaperPath,
    )

    /** 14.1e 编辑页保存 8+8 维成长光谱两 JSON 列（+ 压强列，卷二 §3.2 J-2；仅用户改了滑块时调，见 DAO 注释）。 */
    suspend fun updateGrowthDimensions(
        uuid: String,
        personalitySpectrumJSON: String,
        relationshipQualityJSON: String,
        relationshipPressureJSON: String,
    ) = dao.updateGrowthDimensions(uuid, personalitySpectrumJSON, relationshipQualityJSON, relationshipPressureJSON)

    /**
     * 人设编译四列一次性列级写（活人感内核·卷一图纸 §3.5）。UI 层经此透传，不直碰 DAO。
     * 未变的列**原样回传**——四新列只此一条写口，绝不另开语句（图纸 §9.4）。
     */
    suspend fun updatePersonaCompile(uuid: String, anchor: String, meta: String, gains: String, operators: String) =
        dao.updatePersonaCompile(uuid, anchor, meta, gains, operators)

    // MARK: - Delete

    /**
     * 删角色 = **单事务内先清世界痕迹再删角色**（W1 图纸 §3 / W5 图纸 §3.4 决策 27 彻底遗忘）：关系边（两向）/
     * 关系事件 / 在途旅行 / 世界事件提及 / 原住民招募指针（缘分归零）/ **世界记忆（本人 + 他人记忆中提及行）** /
     * **开机小报（全清·正文可能含其名·下次结算重生成）**——这些是**混合域无 FK** 表，无法靠 Room CASCADE 清，故
     * 手动清；再 [CharacterDao.deleteByUuid] 让 FK CASCADE 继续清会话/消息/里程碑（既有行为不变）。整段一事务：
     * 中途进程死 = 全回滚，无半删状态。**不相关行原样保留**（各 WHERE 只命中该 uuid·小报例外=全清后重生成）。钱路零碰。
     *
     * **战役 B（O2·图纸 §3.3）**：招募指针命中的原住民若为用户自建居民 → [WorldResidentService.onCharacterDeleted]
     * 事务内删其 def+state 行（**取代**官方的 resetRecruitment「回城缘分归零」·官方行为仅对官方保留=彻底消失不回城）；
     * 事务提交后 [WorldResidentService.finalizeEviction] 删头像文件 + 刷花名册（地图/星图/设置计数即少一位）。
     */
    suspend fun delete(uuid: String) {
        val eviction = db.withTransaction {
            db.worldSocialDao().deleteEdgesFor(uuid)
            db.worldSocialDao().deleteEventsFor(uuid)
            db.worldDao().deleteTravel(uuid)
            db.worldDao().deleteEventsInvolving(uuid)
            // O2：用户居民 → 删 def+state 行（须在 resetRecruitment 前·否则指针被清、getByRecruitedUuid 查不到）。
            val ev = residentService.onCharacterDeleted(uuid)
            db.worldNativeDao().resetRecruitment(uuid) // 官方原住民缘分归零（用户居民 state 行已删 = no-op）
            db.worldMemoryDao().deleteInvolving(uuid) // W5：本人记忆 + 他人记忆提及该 id 的行一并清（决策 27）
            db.worldBulletinDao().deleteAllBulletins() // W5：小报正文可能含其名·全清、下次结算重生成
            db.offlineMeetingMemoryDao().deleteByCharacter(uuid) // 梦剧场 B 部：见面回忆表无 FK·手动清（图纸 §3.2·E15）
            db.openLoopDao().deleteByCharacter(uuid) // 活人感一期 P2：惦记的事表无 FK·手动清（图纸 §3.2·E11）
            db.promiseDao().deleteByCharacter(uuid) // 记忆改造一期：承诺账本无 FK·手动清（图纸 §3.1·E15）
            db.ourDayDao().deleteByCharacter(uuid) // 我们的日子·卷一：our_days 无 FK·手动清（总图纸 §3.9）
            dao.deleteByUuid(uuid) // FK CASCADE 继续清会话/消息/里程碑（既有行为不变）
            ev
        }
        // 事务提交后：删头像文件 + 刷花名册（O2 命中时·§3.3）。
        eviction?.let { residentService.finalizeEviction(it) }
    }

    suspend fun delete(entity: CharacterEntity) = delete(entity.uuid)

    // MARK: - Relationship milestones

    fun observeMilestones(characterUuid: String): Flow<List<MilestoneEntity>> =
        milestoneDao.observeForCharacter(characterUuid)

    /** 全量里程碑流（联系人列表用：一次拿全·调用方按角色分组取最新关系称谓）。 */
    fun observeAllMilestones(): Flow<List<MilestoneEntity>> =
        milestoneDao.observeAll()

    /** Snapshot of a character's milestones, ascending by date (iOS `AICharacter.sortedMilestones`). */
    suspend fun getMilestones(characterUuid: String): List<MilestoneEntity> =
        milestoneDao.getForCharacter(characterUuid)

    /** Latest relationship name (iOS `AICharacter.currentRelationship` = last milestone by date). */
    suspend fun currentRelationship(characterUuid: String): String? =
        milestoneDao.getForCharacter(characterUuid).lastOrNull()?.relationshipName

    /**
     * Append a relationship milestone, but only when [name] is non-blank and differs from the
     * current relationship — mirroring iOS save() which never overwrites and skips no-op changes.
     * Returns true if a milestone was actually appended.
     */
    suspend fun recordRelationship(
        characterUuid: String,
        name: String,
        reason: String = "关系调整",
        triggerTypeRaw: String = "userAdvance",
        relationshipSlidersTouched: Boolean = false,
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == currentRelationship(characterUuid)) return false
        milestoneDao.upsert(
            MilestoneEntity(
                uuid = UUID.randomUUID().toString(),
                characterUuid = characterUuid,
                relationshipName = trimmed,
                establishedDate = System.currentTimeMillis(),
                reason = reason,
                triggerTypeRaw = triggerTypeRaw,
            ),
        )
        // 成长原型校准（图纸 §3.3 入口①）：手调滑块=圣旨 → 跳过抬分/回拉；未动滑块 → 抬到原型地板 + 手动路才回拉天花板。
        // 本函数不持角色锁 → 用自取锁的 calibrate（锁不可重入·0.1#6）。
        archetypeCalibrator.calibrate(
            characterUuid,
            trimmed,
            applyFloors = !relationshipSlidersTouched,
            applyCeilings = !relationshipSlidersTouched,
        )
        return true
    }
}
