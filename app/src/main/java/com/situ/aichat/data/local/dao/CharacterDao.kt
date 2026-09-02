package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY creationDate DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE uuid = :uuid")
    fun observeByUuid(uuid: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): CharacterEntity?

    @Query("SELECT * FROM characters ORDER BY creationDate DESC")
    suspend fun getAll(): List<CharacterEntity>

    /** 已「加入世界」的正式角色（世界系统 W4 社交关系参与者·契约 §8.A/§6）。 */
    @Query("SELECT * FROM characters WHERE joinedWorld = 1")
    suspend fun getInWorld(): List<CharacterEntity>

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int

    /** 入世角色数（性能采集规模数 `worldResidents`·图纸 §3.2·M17 判定「N≥10 且 castOf>40ms」的 N）。 */
    @Query("SELECT COUNT(*) FROM characters WHERE joinedWorld = 1")
    suspend fun countInWorld(): Int

    /** 角色数（响应式·「我」页主角卡陪伴统计·PROFILE 契约 §9.3）。 */
    @Query("SELECT COUNT(*) FROM characters")
    fun observeCount(): Flow<Int>

    /** 最早角色创建时间（响应式·「一起走过 N 天」起点·无角色=null·PROFILE 契约 §9.3）。creationDate 有索引。 */
    @Query("SELECT MIN(creationDate) FROM characters")
    fun observeEarliestCreationDate(): Flow<Long?>

    /** 全部被引用的聊天壁纸绝对路径（孤儿扫描用·chunk1b 孤儿清理）。 */
    @Query("SELECT chatWallpaperPath FROM characters WHERE chatWallpaperPath IS NOT NULL AND chatWallpaperPath != ''")
    suspend fun allChatWallpaperPaths(): List<String>

    @Upsert
    suspend fun upsert(character: CharacterEntity)

    @Update
    suspend fun update(character: CharacterEntity)

    /**
     * 定向写回主记忆摘要（previous+current 两列）。替代整行 copy()+@Upsert：见面摘要重试链与常规记忆摘要可能
     * 退出见面后并发跑，整行写会用陈旧快照覆盖刚写入的 offlineMeetingMemorySummary（D1 数据丢失）。改 targeted
     * UPDATE 后两路写不同列、互不覆盖（= iOS 逐属性写 @Model 语义）。
     */
    @Query("UPDATE characters SET previousMemorySummary = :previous, memorySummary = :summary WHERE uuid = :uuid")
    suspend fun updateMemorySummary(uuid: String, previous: String, summary: String)

    /** 朋友圈消化水位线推进（记忆改造一期·图纸 §3.5-B·定向列 UPDATE·消化作业成功后写·照 [updateMemorySummary] 定向列惯例）。 */
    @Query("UPDATE characters SET momentsDigestedUntilMillis = :value WHERE uuid = :uuid")
    suspend fun updateMomentsDigestWatermark(uuid: String, value: Long)

    /** 「我们的日子」一次性回填完成标记（卷一图纸 §2.2·总图纸 §3.2·唯一写口·列级盲写·照 [updateMomentsDigestWatermark]）。 */
    @Query("UPDATE characters SET ourDaysBackfilledAt = :millis WHERE uuid = :uuid")
    suspend fun updateOurDaysBackfilledAt(uuid: String, millis: Long)

    // MARK: - 列级定向写（P12.6 D1）：各分析/计数器只写自己那几列，配合 CharacterWriteLock 消除并发覆盖。

    /** 成长分析一次性回写（性格/关系/兴趣/成长元数据/成长日志 5 列）。 */
    @Query(
        "UPDATE characters SET personalitySpectrumJSON = :personalitySpectrum, " +
            "relationshipQualityJSON = :relationshipQuality, dynamicInterestsJSON = :dynamicInterests, " +
            "growthMetadataJSON = :growthMetadata, growthLogJSON = :growthLog, " +
            "relationshipPressureJSON = :relationshipPressure WHERE uuid = :uuid",
    )
    suspend fun updateGrowthAnalysis(
        uuid: String,
        personalitySpectrum: String,
        relationshipQuality: String,
        dynamicInterests: String,
        growthMetadata: String,
        growthLog: String,
        relationshipPressure: String,
    )

    /** 成长轮次计数器递增（仅 growthMetadataJSON 一列）。 */
    @Query("UPDATE characters SET growthMetadataJSON = :growthMetadata WHERE uuid = :uuid")
    suspend fun updateGrowthMetadata(uuid: String, growthMetadata: String)

    /** 关系评估「有变化」回写（成长日志 + 关系计数 + 评估时间；心意文案戳清空待重生成）。 */
    @Query(
        "UPDATE characters SET growthLogJSON = :growthLog, affinitySensePackageGeneratedAt = NULL, " +
            "relationshipMessageCount = :messageCount, lastRelationshipAnalysisDate = :analysisDate WHERE uuid = :uuid",
    )
    suspend fun updateRelationshipChanged(uuid: String, growthLog: String, messageCount: Int, analysisDate: Long)

    /** 关系评估「无变化」回写（仅关系计数 + 评估时间两列）。 */
    @Query("UPDATE characters SET relationshipMessageCount = :messageCount, lastRelationshipAnalysisDate = :analysisDate WHERE uuid = :uuid")
    suspend fun updateRelationshipUnchanged(uuid: String, messageCount: Int, analysisDate: Long)

    /** 关系消息计数（每轮递增；锁内重读后写新值）。 */
    @Query("UPDATE characters SET relationshipMessageCount = :count WHERE uuid = :uuid")
    suspend fun updateRelationshipMessageCount(uuid: String, count: Int)

    /** 结构化记忆成功提取回写（previous + current + 元数据 3 列）。 */
    @Query(
        "UPDATE characters SET previousStructuredMemoryJSON = :previous, structuredMemoryJSON = :current, " +
            "structuredMemoryMetadataJSON = :metadata WHERE uuid = :uuid",
    )
    suspend fun updateStructuredMemory(uuid: String, previous: String, current: String, metadata: String)

    /** 结构化记忆元数据（计数器递增 / 丢弃路径只更新 lastExtractionDate，仅一列）。 */
    @Query("UPDATE characters SET structuredMemoryMetadataJSON = :metadata WHERE uuid = :uuid")
    suspend fun updateStructuredMemoryMetadata(uuid: String, metadata: String)

    // MARK: - 列级定向写（P12.6 D1b 二期）：礼物/情感/恢复/聊天的剩余整行写改列级，只写自己那几列。
    // 礼物路径（关系/成长是读-改-写）须在 CharacterWriteLock 内、锁内 fresh 读后调；情感/心情/火花为
    // 列集互不重叠的盲写或单写点，列级 UPDATE 即原子，无须进锁（见各 service 注释）。

    /** 关系质感 8 维回写（净额 + 双压两列**同一条语句**落地，绝不拆开写；礼物店反应 + 聊天送礼非珍贵路径）。 */
    @Query(
        "UPDATE characters SET relationshipQualityJSON = :relationshipQuality, " +
            "relationshipPressureJSON = :relationshipPressure WHERE uuid = :uuid",
    )
    suspend fun updateRelationshipQuality(uuid: String, relationshipQuality: String, relationshipPressure: String)

    /** 成长原型校准（图纸 §3.4）：原型 id + 关系质感双列一条 UPDATE（棘轮抬分/回拉·锁内列级写）。 */
    @Query(
        "UPDATE characters SET relationshipArchetypeId = :archetypeId, relationshipQualityJSON = :relationshipQuality, " +
            "relationshipPressureJSON = :relationshipPressure WHERE uuid = :uuid",
    )
    suspend fun updateArchetypeCalibration(uuid: String, archetypeId: String?, relationshipQuality: String, relationshipPressure: String)

    /** 成长原型校准：仅回写原型 id（分数无变化时清/更新陈旧 id·含置 null）。 */
    @Query("UPDATE characters SET relationshipArchetypeId = :archetypeId WHERE uuid = :uuid")
    suspend fun updateRelationshipArchetypeId(uuid: String, archetypeId: String?)

    /** 聊天送礼回写关系质感 + 成长日志（珍贵/手作/DIY 路径，两列；1:1 iOS GiftSendService 同一 save）。 */
    @Query(
        "UPDATE characters SET relationshipQualityJSON = :relationshipQuality, growthLogJSON = :growthLog, " +
            "relationshipPressureJSON = :relationshipPressure WHERE uuid = :uuid",
    )
    suspend fun updateRelationshipQualityAndGrowthLog(uuid: String, relationshipQuality: String, growthLog: String, relationshipPressure: String)

    /** 角色主动送礼写成长日志（仅 growthLogJSON 一列；1:1 iOS ProactiveGiftExecutor 珍贵/手作分支）。 */
    @Query("UPDATE characters SET growthLogJSON = :growthLog WHERE uuid = :uuid")
    suspend fun updateGrowthLog(uuid: String, growthLog: String)

    /**
     * 关系淡化专用列级回写（14.7b 闲置淡化扫；只写关系质感 / 成长元数据 / 成长日志 3 列，不碰性格 / 兴趣）。
     * 须在 [CharacterWriteLock] 内、锁内 fresh 读后调（读-改-写）。
     */
    @Query(
        "UPDATE characters SET relationshipQualityJSON = :relationshipQuality, " +
            "growthMetadataJSON = :growthMetadata, growthLogJSON = :growthLog, " +
            "relationshipPressureJSON = :relationshipPressure WHERE uuid = :uuid",
    )
    suspend fun updateRelationshipDecay(
        uuid: String,
        relationshipQuality: String,
        growthMetadata: String,
        growthLog: String,
        relationshipPressure: String,
    )

    /** 心意反馈文案包回写（包 JSON + 生成时间两列；AffinitySenseService 盲写新包）。 */
    @Query("UPDATE characters SET affinitySensePackageJSON = :packageJson, affinitySensePackageGeneratedAt = :generatedAt WHERE uuid = :uuid")
    suspend fun updateAffinitySensePackage(uuid: String, packageJson: String, generatedAt: Long)

    /**
     * 心意反馈文案包失效（仅 affinitySensePackageGeneratedAt 一列）。编辑保存时人设变更置 null 强刷——
     * AffinitySenseService 的 isExpired(null)=true，下次送礼按新人设重生成（1:1 iOS save() .edit 的
     * `affinitySensePackageGeneratedAt = nil`）。列级 UPDATE 不复活成长/关系等并发列（D1c 同款）。
     */
    @Query("UPDATE characters SET affinitySensePackageGeneratedAt = :generatedAt WHERE uuid = :uuid")
    suspend fun updateAffinitySenseGeneratedAt(uuid: String, generatedAt: Long?)

    /** 最近心情三列回写（顶栏情绪；聊天回复 + 未答恢复，分析不写这三列）。 */
    @Query("UPDATE characters SET lastMoodEmoji = :emoji, lastMoodText = :text, lastMoodColorName = :colorName WHERE uuid = :uuid")
    suspend fun updateMood(uuid: String, emoji: String, text: String, colorName: String)

    /**
     * 情绪历史列回写（成长系统·角色级 moodHistory）。唯一写者 =
     * [com.situ.aichat.data.repository.CharacterRepository.appendMoodHistory]；单列 UPDATE 与成长/关系/计数器写的
     * 列集不相交，配合仓库轻锁的「读-改-写」保证并发追加不丢。
     */
    @Query("UPDATE characters SET moodHistoryJSON = :moodHistoryJSON WHERE uuid = :uuid")
    suspend fun updateMoodHistory(uuid: String, moodHistoryJSON: String)

    /** 火花计数 + 最近聊天日两列回写（用户发消息时，仅 ChatViewModel 写、VM 串行）。 */
    @Query("UPDATE characters SET streakCount = :streakCount, lastChatDate = :lastChatDate WHERE uuid = :uuid")
    suspend fun updateStreak(uuid: String, streakCount: Int, lastChatDate: Long)

    /** 「第一次聊天时间」只往早改：为空、或现值比 [ts] 晚才写。首条消息写口与冷启 / 恢复后补账共用；单列原子 UPDATE 无需写锁。返回受影响行数（0 / 1）。 */
    @Query("UPDATE characters SET firstMessageDate = :ts WHERE uuid = :uuid AND (firstMessageDate IS NULL OR firstMessageDate > :ts)")
    suspend fun markFirstMessageDate(uuid: String, ts: Long): Int

    /**
     * 编辑角色资料保存：列级写回「表单可编辑」的 20 个 profile 列（P12.6 D1c）。1:1 iOS save() 逐属性改 @Model——
     * 只写用户能改的资料列，不再整行 @Update 把成长/关系/心情/火花/结构化记忆/见面摘要等并发列从开屏旧快照
     * 复活回旧值（D1 同类丢更新）。这些列只由编辑表单/建角色/备份导入写，与后台分析的列互不重叠，列级 UPDATE
     * 即原子，无须进每角色锁。
     */
    @Query(
        "UPDATE characters SET name = :name, avatarPath = :avatarPath, systemPrompt = :systemPrompt, " +
            "personalityDescription = :personalityDescription, gender = :gender, birthday = :birthday, " +
            "ageModeRaw = :ageModeRaw, fixedAge = :fixedAge, appearanceDescription = :appearanceDescription, " +
            "occupation = :occupation, backstory = :backstory, speakingStyle = :speakingStyle, " +
            "catchphrases = :catchphrases, exampleDialogues = :exampleDialogues, initialInterests = :initialInterests, " +
            "voiceIdentifier = :voiceIdentifier, remoteVoiceID = :remoteVoiceID, ttsEmotionRaw = :ttsEmotionRaw, " +
            "ttsSpeed = :ttsSpeed, ttsPitch = :ttsPitch, offlineThemeColorHex = :offlineThemeColorHex, " +
            "chatWallpaperPath = :chatWallpaperPath WHERE uuid = :uuid",
    )
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
    )

    /**
     * 14.1e 编辑页保存「8 维性格光谱 + 8 维关系质感」两 JSON 列（列级写，独立于 [updateEditableProfile] 的
     * 「不写成长列」契约）。仅在用户实际改了滑块时由 VM 调用——未改则不写，避免把开屏快照覆盖掉编辑期间后台
     * 成长分析刚写入的新值（比 iOS save() 无条件回写更安全；iOS 同窗口会丢分析）。
     */
    @Query(
        "UPDATE characters SET personalitySpectrumJSON = :personalitySpectrumJSON, " +
            "relationshipQualityJSON = :relationshipQualityJSON, " +
            "relationshipPressureJSON = :relationshipPressureJSON WHERE uuid = :uuid",
    )
    suspend fun updateGrowthDimensions(
        uuid: String,
        personalitySpectrumJSON: String,
        relationshipQualityJSON: String,
        relationshipPressureJSON: String,
    )

    /**
     * 人设编译四列一次性列级写（活人感内核·卷一图纸 §3.5）。须在
     * [com.situ.aichat.data.repository.CharacterWriteLock] 内、**锁内 fresh 读后**调。
     *
     * 四新列的**唯一写口**（编译成功 / 编译失败 / 用户手改三条路共用）：
     * 失败路径只想改 meta 一列时，把另外三列的当前值原样回传即可——**绝不新增第二条 UPDATE 语句**
     * （图纸 §9.4 写口唯一）。现值列 `personalitySpectrumJSON` 不在本语句内，它只经既有
     * [updateGrowthDimensions]（图纸 Y-3：仅 totalAnalysisCount == 0 时同步）。
     */
    @Query(
        "UPDATE characters SET personalityAnchorJSON = :anchor, personaCompileMetaJSON = :meta, " +
            "personaGainsJSON = :gains, personaOperatorsJSON = :operators WHERE uuid = :uuid",
    )
    suspend fun updatePersonaCompile(uuid: String, anchor: String, meta: String, gains: String, operators: String)

    // MARK: - 四场列（活人感内核·卷三图纸 §3.2）：只碰 affectFieldJSON 一列，与关系 6 条 UPDATE 零交集（不是第 7 条）。

    /** 卷三：场列单读（每轮 tick 用·不读整行）。 */
    @Query("SELECT affectFieldJSON FROM characters WHERE uuid = :uuid")
    suspend fun getAffectFieldJson(uuid: String): String?

    /** 卷三：场列盲写（I-3 列集零重叠 ⇒ 列级 UPDATE 即原子；**不进 CharacterWriteLock**·T-6）。唯二写者都在 AffectKernel 的 Mutex 内。 */
    @Query("UPDATE characters SET affectFieldJSON = :json WHERE uuid = :uuid")
    suspend fun updateAffectField(uuid: String, json: String)

    // MARK: - 意图队列列（活人感内核·卷四图纸 §3.2）：只碰 intentQueueJSON 一列，与关系 6 条 UPDATE 零交集（不是第 7 条）。

    /** 卷四：意图列单读（每轮 tick 用·不读整行）。 */
    @Query("SELECT intentQueueJSON FROM characters WHERE uuid = :uuid")
    suspend fun getIntentQueueJson(uuid: String): String?

    /** 卷四：意图列盲写（I-3 列集零重叠 ⇒ 列级 UPDATE 即原子；**不进 CharacterWriteLock**）。唯一写者 = IntentKernel（三条写路都在其 Mutex 内）。 */
    @Query("UPDATE characters SET intentQueueJSON = :json WHERE uuid = :uuid")
    suspend fun updateIntentQueue(uuid: String, json: String)

    // MARK: - 世界成员（W13 图纸 §3.1）：加入/离开只写 joinedWorld+worldJoinedAt 两列，搬家只写 worldHomeCityId 一列。
    // 定点 UPDATE 而非整行 @Update——避免用编辑页开屏的陈旧 copy 覆盖后台并发写的成长/关系/心情列（钱路审计教训）。

    /** 加入/离开世界（join：joined=true, joinedAt=nowMs；leave：joined=false, joinedAt=null·住址保留）。 */
    @Query("UPDATE characters SET joinedWorld = :joined, worldJoinedAt = :joinedAt WHERE uuid = :uuid")
    suspend fun updateWorldMembership(uuid: String, joined: Boolean, joinedAt: Long?)

    /** 搬家（仅 worldHomeCityId 一列）。 */
    @Query("UPDATE characters SET worldHomeCityId = :cityId WHERE uuid = :uuid")
    suspend fun updateWorldHomeCity(uuid: String, cityId: String)

    @Delete
    suspend fun delete(character: CharacterEntity)

    @Query("DELETE FROM characters WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
