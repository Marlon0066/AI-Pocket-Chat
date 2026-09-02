package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.dynamicInterests
import com.situ.aichat.data.model.growthLog
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.intentQueue
import com.situ.aichat.data.model.personaGains
import com.situ.aichat.data.model.personalityAnchor
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relaxOverlap
import com.situ.aichat.data.model.setNetKeepingNeg
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.model.syncedTo
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** 兴趣名兜底截断长度：提示词已要求 AI 返回简短名（≤8字），此为防长句意外落库的保险丝（放宽自旧值 8）。 */
private const val INTEREST_NAME_MAX_LEN = 16

/**
 * 1:1 port of iOS `Services/GrowthAnalysisCoordinator.swift`：包装 [GrowthAnalysisService]，负责
 * 关系淡化 → LLM 分析 → 软上限缩放写回 → 维度跷跷板 → 兴趣冷却 → 生命阶段检测 → 成长日志 → 元数据更新。
 * 卷三起分析通道套上场内核（[AffectKernel.withFieldLocked]·图纸 §3.4 新序）：意向位移 → 日预算 → 落用 → 跷跷板/泄压 →
 * 弹簧/带守卫 → 命中落场；关系维的一切系统侧位移只经 `setNetKeepingNeg`（只动 pos·R1-1）。
 *
 * **持久化偏差（可逆）**：iOS @Model 逐函数原地写、SwiftData 自动存盘（种子化/淡化在 LLM 调用前就落库）；
 * 这里把工作态解码到局部变量、全部完成后**一次性写回**（与 [com.situ.aichat.prompt.memory.StructuredMemoryCoordinator]
 * 一致，缩小丢更新窗口）。代价：LLM 失败时本轮种子化/淡化不落库 → 下次分析重算（幂等：decay 用 max(floor,
 * 原值+delta) 从原值重算，种子按名去重）→ 收敛到同一终态，仅失败连发期间 prompt 显示未淡化值。
 */
@Singleton
class GrowthAnalysisCoordinator @Inject constructor(
    private val service: GrowthAnalysisService,
    private val characterDao: CharacterDao,
    private val milestoneDao: MilestoneDao,
    private val characterWriteLock: CharacterWriteLock,
    private val settingsRepo: SettingsRepository,
    private val throttleStore: MaintenanceThrottleStore,
    /** 卷三：场内核。分析通道在 [CharacterWriteLock] 内再进它的 per-uuid Mutex（锁序恒 写锁 → 内核锁）。 */
    private val affectKernel: AffectKernel,
    /** 卷四：意图内核。分析通道在场锁内再进它的 per-uuid Mutex（锁序恒 写锁 → 场锁 → 意图锁·K-16，永不反向）。 */
    private val intentKernel: IntentKernel,
    /** 修缮卷 J11：注入时钟（生产 = 系统钟·`di/ClockModule`），长程模拟测试才钉得住时刻；`now`（LLM 前）与 `writeNow`（LLM 后）两次取。 */
    private val clock: Clock,
) {

    /**
     * 闲置角色关系淡化扫（14.7b，1:1 iOS `AppBootstrapService.checkAndApplyRelationshipDecay`）。
     * iOS 每 24h 启动维护遍历**所有**角色应用关系淡化，与「聊天后分析前淡化」是两条独立调用点；安卓此前只有
     * 聊天驱动的 [analyzeAndPersist] 路（无消息即抛 NoMessages 返回）→ **从不/极少聊天的角色永不淡化**。本扫补齐：
     * 不调 LLM，纯按 lastChatDate 应用既有 [applyRelationshipDecay] 规则、逐角色列级写回。
     *
     * 节流 24h（[MaintenanceThrottleStore]）+ growthSystemEnabled 门控（关则**不**记 markRun，下次启动再判，对齐 iOS
     * 守卫早返回不写 lastCheck）。每角色在 [CharacterWriteLock] 内 fresh 读后改写，与聊天分析 / 计数器递增不打架。
     */
    suspend fun runIdleRelationshipDecay(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (!throttleStore.isDue(MaintenanceThrottleStore.KEY_RELATIONSHIP_DECAY, MaintenanceThrottleStore.DAY_MS, now)) {
            return
        }
        if (!settingsRepo.getAppSettings().growthSystemEnabled) return // 不 markRun（对齐 iOS guard 早返回）
        val characters = characterDao.getAll()
        var decayedCount = 0
        for (character in characters) {
            if (decayOneCharacter(character.uuid, now, zone)) decayedCount++
        }
        throttleStore.markRun(MaintenanceThrottleStore.KEY_RELATIONSHIP_DECAY, now)
        if (decayedCount > 0) Log.i("GrowthAnalysis", "闲置淡化扫：$decayedCount 个角色关系维度自然衰减")
    }

    /**
     * 对单角色应用关系淡化并列级写回；**维度真衰减**才返回 true（R1 🔵-4：仅重叠泄放 / relaxedAt 刷新的写不计入观测行）。锁内 fresh 读 → 复用 [applyRelationshipDecay]（与聊天分析前
     * 用的同一规则/同一幂等）→ 仅写关系/元数据/日志 3 列。无变更（在宽限期/同日已淡化/已触底）则不写、返回 false。
     */
    private suspend fun decayOneCharacter(characterUuid: String, now: Long, zone: ZoneId): Boolean =
        characterWriteLock.withCharacterLock(characterUuid) {
            val character = characterDao.getByUuid(characterUuid) ?: return@withCharacterLock false
            val currentRelationship = milestoneDao.getForCharacter(characterUuid).lastOrNull()?.relationshipName
            val beforeQuality = character.relationshipQuality
            val beforeMetadata = character.growthMetadata
            val growthLog = character.growthLog.toMutableList()
            val (quality, metadata) = applyRelationshipDecay(
                beforeQuality, beforeMetadata, character.lastChatDate, currentRelationship,
                character.relationshipArchetypeId, growthLog, now, zone,
            )
            // 卷二表1 ⑥：淡化是净额下降 ⇒ 经 applyNetDelta 落成**负压**，而不是把正压抹掉。
            // 有意为之——久不聊天累积的是「疏远压」，久别重逢后关系恢复得更慢一点，这正是活人感（W3）。
            // 修缮卷 J2：再泄重叠（净额恒等）——判据加上压强本身有没有变，否则泄放永远写不下去。
            val beforePressure = character.relationshipPressure
            val pressure = beforePressure.syncedTo(quality).relaxOverlap(now)
            if (quality == beforeQuality && metadata == beforeMetadata && pressure == beforePressure) return@withCharacterLock false
            characterDao.updateRelationshipDecay(
                uuid = characterUuid,
                relationshipQuality = GrowthJson.encode(pressure.toQuality()),
                growthMetadata = GrowthJson.encode(metadata),
                growthLog = GrowthJson.encodeGrowthLog(growthLog),
                relationshipPressure = GrowthJson.encode(pressure),
            )
            quality != beforeQuality
        }

    /**
     * 执行成长分析并将结果一次性写回角色。失败抛出（调用方静默处理）。
     * **P12.6 D1**：整个「读-LLM-写」在 [CharacterWriteLock] 内串行（锁内重读最新角色），写回改用列级 UPDATE
     * （只写性格/关系/兴趣/成长元数据/成长日志 5 列），消除与计数器递增 / 其它分析的并发覆盖。锁内含 LLM 调用，
     * 同角色计数器会等这几秒——单用户本地 App 可接受、且更贴 iOS 全串行语义；链式关系评估在本函数返回（释放锁）后进行，无嵌套。
     */
    suspend fun analyzeAndPersist(
        characterUuid: String,
        config: ApiConfigValues,
        userName: String,
        settings: AppSettings,
    ): GrowthAnalysisResult = characterWriteLock.withCharacterLock(characterUuid) {
        val character = characterDao.getByUuid(characterUuid) ?: throw GrowthAnalysisError.NoMessages
        // 卷零 §3.4：取材从「最近 200 条」改「上次分析之后的全部 + 之前 4 轮前置」。
        // fresh 为空 = 上次分析后一句话都没聊 ⇒ 复用 NoMessages 早退，不空跑 LLM（调用方本就静默吞它）。
        val window = service.collectAnalysisWindow(characterUuid, character.growthMetadata.lastAnalysisDate)
        if (window.fresh.isEmpty()) throw GrowthAnalysisError.NoMessages
        val messages = window.all

        val now = clock.millis()
        val milestones = milestoneDao.getForCharacter(characterUuid) // 升序 = iOS sortedMilestones
        val currentRelationship = milestones.lastOrNull()?.relationshipName
        val lastMilestoneEstablished = milestones.lastOrNull()?.establishedDate

        // 工作态（各解码一次）
        var spectrum = character.personalitySpectrum
        var quality = character.relationshipQuality
        var pressure = character.relationshipPressure   // 卷二：空列走访问器兜底播种（pos = 净额, neg = 0）
        val interests = character.dynamicInterests.toMutableList()
        var metadata = character.growthMetadata
        val growthLog = character.growthLog.toMutableList()

        // 首次分析：从 initialInterests 种子化动态兴趣
        if (metadata.totalAnalysisCount == 0) {
            seedInitialInterests(character.initialInterests, interests, now)
        }

        // LLM 分析前先执行关系淡化，让淡化后的维度值进入分析提示词
        val decayed = applyRelationshipDecay(quality, metadata, character.lastChatDate, currentRelationship, character.relationshipArchetypeId, growthLog, now)
        quality = decayed.first
        metadata = decayed.second
        // 卷二：分析前这次淡化与 ⑥ 同语义 —— 净额下降落成**负压**（先同步，后面 LLM 那步才好在它上面加两股力）。
        // 修缮卷 J2：接着泄重叠（净额恒等·在 LLM 前算、随 updateGrowthAnalysis 落库；LLM 失败则下次按 relaxedAt 重算，幂等）。
        pressure = pressure.syncedTo(quality).relaxOverlap(now)

        // LLM 分析（失败上抛；本轮种子化/淡化未落库 → 见类注释偏差说明）
        val result = service.analyzeGrowth(
            messages = messages,
            characterName = character.name,
            spectrum = spectrum,
            quality = quality,
            interests = interests,
            config = config,
            userName = userName,
            scheduleSystemEnabled = settings.scheduleSystemEnabled,
            characterUuid = characterUuid,
            nowMillis = now,
            leadInMessageCount = window.leadIn.size,
            customGainLabels = character.personaGains.custom.map { it.label },
            // 卷四 §3.5：层 ② 意图段（锁内 fresh 读到的队列·双名第三人称）；无 live 意图 ⇒ ""（提示词逐字节同卷三）。
            intentSection = IntentStatusParsing.section(character.intentQueue.intents, character.name, userName.ifEmpty { "用户" }, now),
        )

        // 修缮卷 🔵-10：LLM 往返可能跨过午夜，落库时刻改用 LLM **之后**的时钟（rollDay / 预算 / 时间戳全按它）；
        // `now`（LLM 前）只供淡化、提示词与 relaxOverlap 用。
        val writeNow = clock.millis()

        // 卷三 §3.4 步骤 2–11：场内核 Mutex 内（锁序 CharacterWriteLock → AffectKernel.Mutex，永不反向）。
        // field0 已 rollDay + 松弛到 writeNow（无脉冲）；块返回新场，内核在块外写 updateAffectField 恰 1 次。
        // 卷四 §3.6：场锁内再进意图锁（→ IntentKernel.Mutex·K-16），只在卷三序上**插入** (a)(c)(d) 三处、其余步骤一字不动；
        // 写序由块嵌套决定：updateGrowthAnalysis → updateIntentQueue → updateAffectField（分析通道恒 3 写·§3.8）。
        affectKernel.withFieldLocked(characterUuid, writeNow) { field0 ->
            var fieldOut = field0
            intentKernel.withQueueLocked(characterUuid, writeNow) { qLoaded ->
                // 3. 本通道起点快照（带守卫与 bandUp 用）
                val spectrum0 = spectrum
                val quality0 = quality

                // (a) 层 ② 判定落队列；本次新了结的意图记关系正压（K-6：水位 = lastAnalysisDate ⇒ 恰记一次，零新字段）
                val queue1 = IntentKernel.applyStatus(qLoaded, result.intentStatus, writeNow)
                val bonus = IntArray(RelationshipQuality.DIMENSION_KEYS.size)
                for (credited in queue1.intents) {
                    if (credited.state != IntentState.RESOLVED || credited.lastChangeAt <= (metadata.lastAnalysisDate ?: 0L)) continue
                    val (dim, n) = IntentRules.resolveBonus(credited.kind)
                    bonus[RelationshipQuality.DIMENSION_KEYS.indexOf(dim)] += n
                }

                // 4. 意向位移（全为整数）：a 性格走 saturate（K-2）· b 关系走卷二四步（零碰）+ 了结正压进 rΔ 池（K-7）· c 事件投影进场 · d 场扩散到 16 维
                val pDelta = personalityIntent(result.personalityChanges)
                val (q1, p1) = applyRelationshipChanges(result.relationshipChanges, quality, pressure)
                val rDelta = q1.values.indices.map { i -> (q1.values[i] - quality.values[i]) + bonus[i] }
                val fDelta = AffectKernel.project(result.gainHits, result.customHits, character.personaGains)
                val dDelta = AffectKernel.diffuse(fDelta)

                // 5. 日位移预算（K-3：只管这 16 维的三组位移，场不进池）
                val budget = scaleToBudget(pDelta, rDelta, dDelta, field0.budgetUsed)

                // 6. 落用：性格 = 意向 + 扩散；关系一律经 setNetKeepingNeg（R1-1：系统侧调整只动 pos）；
                //    场按投影落、不缩放——修缮卷 J3：慢场另受「每场每日 |Δ| ≤ FIELD_DAY_CAP」日帽（快场一天内自己回落，无帽）
                for (d in pDelta.indices) {
                    spectrum = spectrum.setValue(d, spectrum.values[d] + budget.personality[d] + budget.diffusion[d])
                }
                pressure = p1
                for (i in rDelta.indices) {
                    pressure = pressure.setNetKeepingNeg(i, quality.values[i] + budget.relationship[i] + budget.diffusion[pDelta.size + i])
                }
                quality = pressure.toQuality()
                val capS = RelationshipBands.FIELD_DAY_CAP - field0.slowDayUsed[0]
                val capI = RelationshipBands.FIELD_DAY_CAP - field0.slowDayUsed[1]
                val dS = fDelta.security.coerceIn(-capS, capS)
                val dI = fDelta.investment.coerceIn(-capI, capI)
                var field = field0.copy(
                    security = (field0.security + dS).coerceIn(0, 100),
                    investment = (field0.investment + dI).coerceIn(0, 100),
                    valence = (field0.valence + fDelta.valence).coerceIn(-100, 100),
                    arousal = (field0.arousal + fDelta.arousal).coerceIn(0, 100),
                    budgetUsed = (field0.budgetUsed + budget.used).coerceIn(0, RelationshipBands.DAILY_BUDGET),
                    slowDayUsed = listOf(field0.slowDayUsed[0] + abs(dS), field0.slowDayUsed[1] + abs(dI)),
                )

                // (c) 萌生：本次命中 + 落用后的场（K-2 确定性规则·一次最多 2 个·同类冷却 / 刷新 / 队列 3 淘汰）
                val queue2 = IntentKernel.birth(queue1, result.gainHits, character.personaGains, field, writeNow)

                // 7. 既有：兴趣 → 跷跷板（saturate 版·天花板 = anchor+20，空锚点列保留 70·K-4）→ 泄压
                //    → 压强同步（取代旧的整体「按目标净额补增量」那一句·K-6：只对联动/泄压改过的维、只动 pos）
                applyNewInterests(result.newInterests, interests, writeNow)
                applyInterestHeatChanges(result.interestHeatChanges, interests, writeNow)
                val qualityBeforeInterplay = quality
                val interplay = applyDimensionInterplay(quality, spectrum, opennessCapFor(character))
                quality = interplay.first
                spectrum = interplay.second
                // 修缮卷 D-7：本次有负向命中（投影表效价 < 0 的系统项 / 专属项 neg）⇒ 张力不恒定泄压，否则吵完架照掉 2
                val hasNegativeHit = result.gainHits.any { it in AffectCoefficients.NEGATIVE_HIT_KEYS } || result.customHits.any { !it.positive }
                if (!hasNegativeHit) quality = applyTensionRelief(quality)
                for (i in quality.values.indices) {
                    if (quality.values[i] != qualityBeforeInterplay.values[i]) pressure = pressure.setNetKeepingNeg(i, quality.values[i])
                }

                // 8. 弹簧 + 带守卫（仅锚点列非空·K-4；界内钳住、界外只许往里·K-5）
                if (character.personalityAnchorJSON.isNotEmpty()) {
                    val anchor = character.personalityAnchor
                    for (d in spectrum0.values.indices) {
                        val x = spectrum.values[d] + springStep(spectrum.values[d], anchor.values[d])
                        spectrum = spectrum.setValue(d, guardBand(before = spectrum0.values[d], after = x, anchor = anchor.values[d]))
                    }
                }

                // 9. 既有：冷却 → 阶段 → 日志 → (d) 意图行 / 清理 → 元数据
                coolDownStaleInterests(interests, settings.interestCooldownDays, writeNow)
                metadata = detectPhaseTransition(quality, metadata, lastMilestoneEstablished, character.lastChatDate, growthLog, writeNow)
                appendGrowthLog(result.events, result.narrative, growthLog, settings.growthLogMaxCount, writeNow)
                // (d) 意图 growthLog 行只在分析通道写（K-21）；RESOLVED 过 24h 才清理（K-8 先记账后清理）；
                //     性格复盘已砍（修缮卷·用户 2026-09-02 拍板）——`reviewRoundsAccrued` 不再累加，字段只随列透传兼容旧 JSON。
                growthLog.addAll(IntentKernel.logLines(qLoaded, queue2, metadata.lastAnalysisDate, character.name, userName.ifEmpty { "用户" }, writeNow))
                trimLog(growthLog, settings.growthLogMaxCount)
                val queue3 = IntentKernel.pruneResolved(queue2, writeNow)
                metadata = updateMetadata(metadata, writeNow)

                // 10. 命中落场（K-12：供算子 c07–c09 / c12 在 24h 内求值）
                val bandUp = anyDimBandRoseUp(quality0, quality)
                field = field.copy(
                    hits = (result.gainHits + listOfNotNull(AffectField.BAND_UP.takeIf { bandUp })).take(AffectField.MAX_HITS),
                    hitsAt = writeNow,
                    updatedAt = writeNow,
                )

                // 11. 一次性列级写回（P12.6 D1：只写自己这 6 列，不整行覆盖）；场交还内核（块外 1 次 updateAffectField）
                characterDao.updateGrowthAnalysis(
                    uuid = characterUuid,
                    personalitySpectrum = GrowthJson.encode(spectrum),
                    relationshipQuality = GrowthJson.encode(pressure.toQuality()),
                    dynamicInterests = GrowthJson.encodeDynamicInterests(interests),
                    growthMetadata = GrowthJson.encode(metadata),
                    growthLog = GrowthJson.encodeGrowthLog(growthLog),
                    relationshipPressure = GrowthJson.encode(pressure),
                )
                fieldOut = field
                queue3
            }
            fieldOut
        }
        // 观测点（真机验直接看；对齐 M05 向量层的 Logcat 风格）
        Log.i("GrowthAnalysis", "✓ ${character.name}: 性格${result.personalityChanges.size}项 关系${result.relationshipChanges.size}项 新兴趣${result.newInterests.size}个 命中${result.gainHits.size + result.customHits.size}项 丢弃${result.droppedHits} 阶段=${metadata.currentPhase} 总分析#${metadata.totalAnalysisCount} | ${result.narrative}")
        result
    }

    // MARK: - 种子化初始兴趣

    /** 首次分析前，把角色的 initialInterests 转为动态兴趣（按填写顺序分层热度，去重）。名取前 16 字兜底。 */
    internal fun seedInitialInterests(initialInterests: String, interests: MutableList<DynamicInterest>, now: Long) {
        if (initialInterests.isEmpty()) return
        val names = initialInterests.split(Regex("[,，、]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(INTEREST_NAME_MAX_LEN) }
        if (names.isEmpty()) return

        val existingNames = interests.map { it.name.lowercase() }.toSet()
        val newNames = names.filter { it.lowercase() !in existingNames }
        for ((index, name) in newNames.withIndex()) {
            val heat = when (index) {
                0, 1 -> 70   // 最先想到的，核心爱好
                2, 3 -> 60   // 比较重要
                else -> 50   // 补充填写
            }
            interests.add(DynamicInterest(name = name, heat = heat, discoveredDate = now, lastMentionedDate = now, isFromInitial = true))
        }
    }

    // MARK: - 应用变化

    /**
     * 卷三 §3.4 步骤 4a（K-2）：LLM 直报的性格增量改走 [saturate]（±10 ⇒ ≤ ±6），按 DIMENSION_KEYS 序给出 8 维意向位移，
     * 缺席 = 0、非法 key 忽略。落用在步骤 6（先过日预算、再与扩散一起加）——取代旧 `applyPersonalityChanges` 的 `scaledDelta` 直落。
     */
    private fun personalityIntent(changes: Map<String, Int>): List<Int> =
        PersonalitySpectrum.DIMENSION_KEYS.map { key -> saturate((changes[key] ?: 0).toDouble()) }

    /**
     * 规则 3 天花板（卷三 K-4）：锚点列为空 ⇒ 固定 [RelationshipBands.OPENNESS_INTERPLAY_CAP]（Y-1 兜底让 anchor == current，
     * 天花板若跟现值走就是 1b 棘轮复活）；否则 `anchor.openness + SPRING_BAND`（总图纸 §3.5「卷三以 anchor+20 取代止血值」）。
     */
    private fun opennessCapFor(character: CharacterEntity): Int =
        if (character.personalityAnchorJSON.isEmpty()) RelationshipBands.OPENNESS_INTERPLAY_CAP
        else character.personalityAnchor.openness + RelationshipBands.SPRING_BAND

    /** 添加新发现的兴趣（去重，名取前 16 字兜底——提示词已要求 AI 返回简短名，这里仅防长句意外落库）。 */
    internal fun applyNewInterests(newInterests: List<GrowthAnalysisResult.NewInterest>, interests: MutableList<DynamicInterest>, now: Long) {
        if (newInterests.isEmpty()) return
        val existingNames = interests.map { it.name.lowercase() }.toSet()
        for (item in newInterests) {
            if (item.name.lowercase() in existingNames) continue
            interests.add(DynamicInterest(name = item.name.take(INTEREST_NAME_MAX_LEN), heat = item.initialHeat, discoveredDate = now, lastMentionedDate = now, isFromInitial = false))
        }
    }

    /** 更新已有兴趣的热度（增量 + 非线性缩放，更新 lastMentionedDate）。 */
    private fun applyInterestHeatChanges(changes: Map<String, Int>, interests: MutableList<DynamicInterest>, now: Long) {
        if (changes.isEmpty()) return
        for (i in interests.indices) {
            val delta = changes[interests[i].name] ?: continue
            val adjusted = scaledDelta(interests[i].heat, delta)
            interests[i] = interests[i].copy(
                heat = (interests[i].heat + adjusted).coerceIn(0, 100),
                lastMentionedDate = now,
            )
        }
    }

    /** 冷却长时间未提及的兴趣（分层固定衰减，不经 scaledDelta），热度 ≤0 删除。 */
    private fun coolDownStaleInterests(interests: MutableList<DynamicInterest>, cooldownDays: Int, now: Long) {
        val cooldownMillis = cooldownDays.toLong() * 86_400_000L
        for (i in interests.indices) {
            val elapsed = now - interests[i].lastMentionedDate
            if (elapsed > cooldownMillis) {
                val decay = when {
                    interests[i].heat >= 80 -> 3   // 深度爱好，不容易忘
                    interests[i].heat >= 50 -> 5   // 普通兴趣，正常衰减
                    else -> 8                      // 浅层兴趣，快速冷却
                }
                interests[i] = interests[i].copy(heat = maxOf(0, interests[i].heat - decay))
            }
        }
        interests.removeAll { it.heat <= 0 }
    }

    // MARK: - 生命阶段检测

    private fun detectPhaseTransition(
        quality: RelationshipQuality,
        metadata: GrowthAnalysisMetadata,
        lastMilestoneEstablished: Long?,
        lastChatDate: Long?,
        growthLog: MutableList<GrowthLogEntry>,
        now: Long,
    ): GrowthAnalysisMetadata {
        val oldPhase = metadata.currentPhase
        val nowInstant = Instant.ofEpochMilli(now)

        val recentMilestone = lastMilestoneEstablished?.let {
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(it), nowInstant) < 14
        } ?: false
        val daysSinceLastChat = lastChatDate?.let {
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(it), nowInstant).toInt()
        } ?: 999

        // 按优先级检测（越特殊越先）
        val newPhase: String? = when {
            recentMilestone || (quality.funValue >= 70 && quality.tension < 30) -> "honeymoon"
            quality.tension >= 40 && quality.closeness >= 50 -> "adjustment"
            (quality.funValue < 40 && quality.familiarity >= 70) || daysSinceLastChat >= 5 -> "fatigue"
            quality.familiarity >= 65 && quality.trust >= 65 && quality.tension < 30 -> "stability"
            (oldPhase == "adjustment" || oldPhase == "fatigue") && quality.tension < 30 && quality.closeness >= 50 -> "breakthrough"
            else -> {
                // 突破期 14 天时限：到期后按趣味性过渡到蜜月/稳定，否则维持
                if (oldPhase == "breakthrough") {
                    val entered = metadata.phaseEnteredDate
                    if (entered != null && ChronoUnit.DAYS.between(Instant.ofEpochMilli(entered), nowInstant) >= 14) {
                        if (quality.funValue >= 60) "honeymoon" else "stability"
                    } else {
                        oldPhase
                    }
                } else {
                    oldPhase
                }
            }
        }

        if (newPhase == oldPhase) return metadata

        if (newPhase != null) {
            val phaseName = when (newPhase) {
                "honeymoon" -> "蜜月期"
                "adjustment" -> "磨合期"
                "stability" -> "稳定期"
                "fatigue" -> "倦怠期"
                "breakthrough" -> "突破期"
                else -> newPhase
            }
            growthLog.add(GrowthLogEntry(timestamp = now, type = GrowthEventType.RELATIONSHIP_CHANGE, summary = "关系进入$phaseName"))
            trimLog(growthLog, 100)
        }
        return metadata.copy(currentPhase = newPhase, phaseEnteredDate = now)
    }

    /** 追加成长日志并裁剪到上限。无 event 但有 narrative 也记一条 majorEvent。 */
    private fun appendGrowthLog(
        events: List<GrowthAnalysisResult.GrowthEvent>,
        narrative: String,
        growthLog: MutableList<GrowthLogEntry>,
        maxCount: Int,
        now: Long,
    ) {
        for (event in events) {
            growthLog.add(GrowthLogEntry(timestamp = now, type = event.type, summary = event.summary))
        }
        if (events.isEmpty() && narrative.isNotEmpty()) {
            growthLog.add(GrowthLogEntry(timestamp = now, type = GrowthEventType.MAJOR_EVENT, summary = narrative))
        }
        trimLog(growthLog, maxCount)
    }

    // MARK: - 关系淡化（不聊天时维度自然衰减）

    private fun applyRelationshipDecay(
        quality: RelationshipQuality,
        metadata: GrowthAnalysisMetadata,
        lastChatDate: Long?,
        currentRelationship: String?,
        relationshipArchetypeId: String?,
        growthLog: MutableList<GrowthLogEntry>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<RelationshipQuality, GrowthAnalysisMetadata> {
        val lastChat = lastChatDate ?: return quality to metadata

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val lastChatDay = Instant.ofEpochMilli(lastChat).atZone(zone).toLocalDate()
        val rawInactiveDays = ChronoUnit.DAYS.between(lastChatDay, today).toInt()
        val inactiveDays = rawInactiveDays.coerceIn(0, 30) // 封顶 30 防极端

        if (inactiveDays <= 3) return quality to metadata // 3 天宽限期

        // 同一天不重复淡化
        val lastDecay = metadata.lastDecayAppliedDate
        if (lastDecay != null && Instant.ofEpochMilli(lastDecay).atZone(zone).toLocalDate() == today) {
            return quality to metadata
        }

        // 本次应衰减天数（非总天数，而是距上次淡化的增量；首次补齐宽限后所有天数）
        val newDecayDays: Int = if (lastDecay != null) {
            val lastDecayDay = Instant.ofEpochMilli(lastDecay).atZone(zone).toLocalDate()
            ChronoUnit.DAYS.between(lastDecayDay, today).toInt().coerceAtLeast(0)
        } else {
            inactiveDays - 3
        }
        if (newDecayDays <= 0) return quality to metadata

        val eqPoint = equilibriumPoint(currentRelationship)
        val dynamicFloor = maxOf(10, eqPoint - 10) // 平衡点下浮 10，最低 10（未识别原型走此 legacy 地板）
        val archetype = relationshipArchetypeId?.let { RelationshipArchetype.byId(it) }
        val q = computeDecayedQuality(quality, inactiveDays, newDecayDays, dynamicFloor, archetype)
        if (q == quality) return quality to metadata

        growthLog.add(GrowthLogEntry(timestamp = now, type = GrowthEventType.RELATIONSHIP_CHANGE, summary = "因${inactiveDays}天未互动，关系维度自然衰减"))
        trimLog(growthLog, 100)
        Log.i("GrowthAnalysis", "关系淡化: ${inactiveDays}天未互动 (本次衰减${newDecayDays}天, floor=${archetype?.id ?: dynamicFloor})")

        val todayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return q to metadata.copy(lastDecayAppliedDate = todayStartMillis)
    }

    /** 更新成长分析元数据。 */
    private fun updateMetadata(metadata: GrowthAnalysisMetadata, now: Long): GrowthAnalysisMetadata =
        metadata.copy(lastAnalysisDate = now, roundsSinceLastAnalysis = 0, totalAnalysisCount = metadata.totalAnalysisCount + 1)

    private fun trimLog(log: MutableList<GrowthLogEntry>, maxCount: Int) {
        if (log.size > maxCount) {
            val kept = log.takeLast(maxCount)
            log.clear()
            log.addAll(kept)
        }
    }
}
