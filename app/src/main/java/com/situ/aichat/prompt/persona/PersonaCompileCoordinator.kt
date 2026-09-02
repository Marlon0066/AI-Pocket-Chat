package com.situ.aichat.prompt.persona

import android.content.Context
import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaCompileMeta
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.personaGains
import com.situ.aichat.data.model.personaCompileMeta
import com.situ.aichat.data.model.personaOperators
import com.situ.aichat.data.model.personalityAnchor
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** 一次编译的结果（给 UI 用；数值本身已落库，UI 重读角色即可）。 */
sealed interface PersonaCompileOutcome {
    /**
     * 编译成功并已落库。[syncedCurrentSpectrum] = 是否顺带把现值也同步成了锚点
     * （Y-3 判据：仅 `totalAnalysisCount == 0` 的未分析角色）。
     */
    data class Success(
        val meta: PersonaCompileMeta,
        val anchor: PersonalitySpectrum,
        val syncedCurrentSpectrum: Boolean,
    ) : PersonaCompileOutcome

    /** 编译失败（D-5）：**数值一个字节没动**，只在 meta 里记了一笔失败时间。 */
    data class Failed(val reason: String) : PersonaCompileOutcome
}

/**
 * 人设编译的落库协调器（活人感内核·卷一图纸 §3.5 · 修缮卷 §3.8 修订）：「锁外读快照 → 锁外编译（LLM）→ 锁内 fresh 读 → 合并落库」。
 *
 * **并发口径**（修缮卷 B🔵-5：LLM 出锁、锁内 fresh 读）：LLM 调用在**锁外**（不拖长 [CharacterWriteLock] 数十秒），
 * 只在写回时进锁并 **fresh 读**——失败路径三列用 fresh 原串回传、成功路径的合并基于 fresh（编辑页保存段可能在 LLM 期间改过）。
 * **锁不可重入**：本类内禁止再调任何自取锁的函数。
 *
 * **写口纪律**（§9.4）：四新列只经 [CharacterDao.updatePersonaCompile]（失败路径把另三列的当前值原样回传，
 * **不新增第二条 UPDATE**）；现值列只经既有 [CharacterDao.updateGrowthDimensions]，且它同时写关系质感两列，
 * 故关系质感入参**必须是锁内 fresh 读到的当前值**（传旧快照会把并发写的关系分覆盖掉·卷零 F3 同类教训）。
 *
 * **保留口径**（修缮卷 J6 / J7）：编译产物可整体替换，**用户创作与用户决定必须保留**——手写专属项（[mergeManualCustoms]）、
 * 手调过档位的系统项（[mergeGains]·`manualSystem`）、关掉 / 删掉的算子（[mergeOperators]·`suppressedOperators` 墓碑 + 沿用既有 `enabled`）；
 * 新建角色拖了滑杆 ⇒ `preserveAnchor`：锚点与现值不被覆盖、`source = manual`，增益 / 算子照写。
 *
 * **Y-3 判据**：锚点↔现值同步写当且仅当 `!preserveAnchor && growthMetadata.totalAnalysisCount == 0`。已相处过的角色
 * 点「重新生成」只更新本性，现值一个字节不动——那是用户攒下来的关系史。
 */
@Singleton
class PersonaCompileCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: PersonaCompileService,
    private val characterDao: CharacterDao,
    private val characterWriteLock: CharacterWriteLock,
    private val apiConfigRepo: ApiConfigRepository,
    private val clock: Clock,
) {

    /** 新建角色自动编译的宿主 scope（VM 随导航销毁，不能用 viewModelScope）。 */
    private val compileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 编译一个角色并落库。返回 [PersonaCompileOutcome]；**不抛**（编译异常一律转 [PersonaCompileOutcome.Failed]，
     * 由 UI 显示 D-5 提示条，绝不让断网穿透成闪退）。
     *
     * 人设为空 ⇒ 直接 Failed，**零 LLM 调用**（Y-E1）。角色不存在 / 无可用 API 配置同理。
     */
    suspend fun compileAndPersist(characterUuid: String, preserveAnchor: Boolean = false): PersonaCompileOutcome {
        // 1. 锁外：快照只供组提示词与算 hash（人设文本）；空人设 / 无配置零写零调用。
        val snapshot = characterDao.getByUuid(characterUuid) ?: return PersonaCompileOutcome.Failed("角色不存在")
        if (snapshot.personalityDescription.isBlank()) return PersonaCompileOutcome.Failed("人设为空")
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.PERSONA_COMPILE)
            ?: return PersonaCompileOutcome.Failed("没有可用的 API 配置")
        val result = runCatching {
            service.compile(
                input = PersonaCompileInput(
                    name = snapshot.name,
                    personalityDescription = snapshot.personalityDescription,
                    occupation = snapshot.occupation,
                    backstory = snapshot.backstory,
                    speakingStyle = snapshot.speakingStyle,
                    catchphrases = snapshot.catchphrases,
                ),
                config = config,
                systemGainLabels = systemGainLabels(),
            )
        }

        // 2. 锁内：fresh 读后写回（LLM 期间编辑页可能保存过——合并与失败回传都以 fresh 为基）。
        return characterWriteLock.withCharacterLock(characterUuid) {
            val fresh = characterDao.getByUuid(characterUuid)
                ?: return@withCharacterLock PersonaCompileOutcome.Failed("角色不存在")
            val compiled = result.getOrElse { e ->
                // D-5 失败路径：数值四列与 personalitySpectrumJSON 一个字节不动，只更新 lastFailedAt（三列用 fresh 原串）。
                characterDao.updatePersonaCompile(
                    uuid = characterUuid,
                    anchor = fresh.personalityAnchorJSON,
                    meta = GrowthJson.encode(fresh.personaCompileMeta.copy(lastFailedAt = clock.millis())),
                    gains = fresh.personaGainsJSON,
                    operators = fresh.personaOperatorsJSON,
                )
                Log.i(TAG, "✗ ${fresh.name}: 编译失败（${e.message}），数值保持原样")
                return@withCharacterLock PersonaCompileOutcome.Failed(e.message ?: "编译失败")
            }

            // 缺席的维度保持 50（图纸 §3.4「不强填」）⇒ 以中性为底，只落 LLM 真给出的维度；preserveAnchor ⇒ 锚点列原样（J6）。
            val anchor = if (preserveAnchor) {
                fresh.personalityAnchor
            } else {
                compiled.anchors.entries.fold(PersonalitySpectrum.NEUTRAL) { acc, (key, value) ->
                    val index = PersonalitySpectrum.DIMENSION_KEYS.indexOf(key)
                    if (index >= 0) acc.setValue(index, value) else acc
                }
            }
            val meta = fresh.personaCompileMeta.copy(   // suppressedOperators 随 fresh 走
                source = if (preserveAnchor) PersonaCompileMeta.SOURCE_MANUAL else PersonaCompileMeta.SOURCE_COMPILED,
                compiledAt = clock.millis(),
                personaHash = service.personaHash(snapshot.personalityDescription),
                lastFailedAt = 0L,
                droppedCount = compiled.droppedCount,
                anchorBasis = if (preserveAnchor) fresh.personaCompileMeta.anchorBasis else compiled.basis,
            )
            val mergedGains = mergeGains(existing = fresh.personaGains, compiled = compiled.gains)
            val mergedOperators = mergeOperators(
                existing = fresh.personaOperators, compiled = compiled.operators,
                suppressed = fresh.personaCompileMeta.suppressedOperators.toSet(),
            )
            characterDao.updatePersonaCompile(
                uuid = characterUuid,
                anchor = if (preserveAnchor) fresh.personalityAnchorJSON else GrowthJson.encode(anchor),
                meta = GrowthJson.encode(meta),
                gains = GrowthJson.encode(mergedGains),
                operators = GrowthJson.encodePersonaOperators(mergedOperators),
            )

            // Y-3：未分析过的角色，本性即现在——同步写现值。已相处过的绝不碰（C4）；手拖过锚点的新角色也不碰（J6）。
            val syncCurrent = !preserveAnchor && fresh.growthMetadata.totalAnalysisCount == 0
            if (syncCurrent) {
                characterDao.updateGrowthDimensions(
                    uuid = characterUuid,
                    personalitySpectrumJSON = GrowthJson.encode(anchor),
                    // 该 UPDATE 同时写关系质感列，入参必须是锁内 fresh 读到的当前值。
                    // 卷二表1 ⑨：关系值与压强列**双双原样透传**（读什么写什么）——本写口只为同步性格现值，
                    // 它碰关系两列纯粹因为该 UPDATE 一条管三列，语义上一个字节都不该动。
                    relationshipQualityJSON = GrowthJson.encode(fresh.relationshipQuality),
                    relationshipPressureJSON = fresh.relationshipPressureJSON,
                )
            }

            Log.i(
                TAG,
                "✓ ${fresh.name}: 锚点 ${compiled.anchors.size} 维${if (preserveAnchor) "（保留手拖）" else ""} / " +
                    "增益 ${compiled.gains.system.size + compiled.gains.custom.size} 项 / 算子 ${compiled.operators.size} 条 / 丢弃 ${compiled.droppedCount} 条",
            )
            if (compiled.notes.isNotEmpty()) Log.i(TAG, "  ${fresh.name} 编译总结：${compiled.notes}")
            PersonaCompileOutcome.Success(meta = meta, anchor = anchor, syncedCurrentSpectrum = syncCurrent)
        }
    }

    /**
     * 新建角色**首次保存**后自动编译一次（图纸 D-1 的唯一例外 / Y-E21）。人设为空则整个跳过。
     *
     * 跑在服务自有 [compileScope]：save 完即导航清场，`viewModelScope` 会把这次数十秒的 LLM 调用直接取消
     * （照 `CharacterEconomyMaintenanceService.runForNewCharacter` 先例）。失败静默——用户进编辑页会看到
     * D-5 提示条，可手动重来。
     */
    fun compileForNewCharacter(characterUuid: String, preserveAnchor: Boolean) {
        compileScope.launch {
            runCatching { compileAndPersist(characterUuid, preserveAnchor) }
                .onFailure { Log.w(TAG, "新建角色自动编译异常：${it.message}") }
        }
    }

    /** 27 项系统增益的当前语言标签（供编译产物的专属项查重·Y-E8）。 */
    private fun systemGainLabels(): Set<String> =
        PersonaVocab.GAINS.values.mapTo(mutableSetOf()) { context.getString(it) }

    private companion object {
        const val TAG = "PersonaCompile"
    }
}

/**
 * 把用户手写的专属项并回编译结果（R1 复核 🔴-1 · 图纸 Y-E9「`origin == "manual"` 优先保留」）。
 *
 * **为什么必须合并**：`parseCompileResponse` 只认 LLM 这一次吐出来的东西，产出的 `custom` 全是
 * `ORIGIN_COMPILED`。若直接整体替换，用户手写的「被叫全名」这类条目会在每次「重新生成」时**静默消失**
 * ——而 D-2 提醒条恰恰在主动催用户重新生成，所以这条路一定会被走到。
 *
 * **口径**：`manual` 项**全部保留且排在前**（用户创作 > 编译产物）；`compiled` 项按顺序补位到
 * [PersonaGains.MAX_CUSTOM] 为止；label 与已保留项重名的 `compiled` 项丢弃（比较口径与解析端一致 =
 * 去空白 + 全小写）。`system` 档位是编译产物，照旧整体替换（Y-E26：用户主动点重新生成 = 明示意图）。
 */
internal fun mergeManualCustoms(existing: PersonaGains, compiled: PersonaGains): PersonaGains {
    val manual = existing.custom.filter { it.origin == CustomGain.ORIGIN_MANUAL }
    if (manual.isEmpty()) return compiled
    val seen = manual.mapTo(mutableSetOf()) { it.label.trim().lowercase() }
    val kept = manual.toMutableList()
    for (item in compiled.custom) {
        if (kept.size >= PersonaGains.MAX_CUSTOM) break
        if (!seen.add(item.label.trim().lowercase())) continue
        kept += item
    }
    return compiled.copy(custom = kept)
}

/**
 * 增益合并（修缮卷 J7）：custom 照 [mergeManualCustoms]；`system` = 编译档位 + 用户手调过的项（`existing.manualSystem`）
 * 以 **existing 里的值**压住编译值（手调成 1 的项在 `existing.system` 里也存了显式 1——`PersonaCompileUseCase.persistUserEdits` 负责写进去）；
 * `manualSystem` 原样继承。
 */
internal fun mergeGains(existing: PersonaGains, compiled: PersonaGains): PersonaGains {
    val custom = mergeManualCustoms(existing, compiled).custom
    val manualKeys = existing.manualSystem.toSet()
    val system = compiled.system + existing.system.filterKeys { it in manualKeys }
    return PersonaGains(system = system, custom = custom, manualSystem = existing.manualSystem)
}

/** 算子键 = `"cNN|aNN"`（墓碑与沿用 id / enabled 的比对口径）。 */
internal fun operatorKey(op: PersonaOperator): String = "${op.condition}|${op.action}"

/**
 * 算子合并（修缮卷 J7）：编译结果为准（顺序 = compiled 序），但 (a) 用户删过的（[suppressed] 墓碑）不复活；
 * (b) 同键既有条目**整条**沿用（今日字段只有 id/condition/action/enabled ⇒ 等价「沿用 id 与 enabled」；
 *     日后给算子加字段时须改为只拷 id/enabled，否则编译新值会被静默丢弃·R1 🔵-5）。
 */
internal fun mergeOperators(existing: List<PersonaOperator>, compiled: List<PersonaOperator>, suppressed: Set<String>): List<PersonaOperator> =
    compiled.filterNot { operatorKey(it) in suppressed }
        .map { c -> existing.firstOrNull { operatorKey(it) == operatorKey(c) } ?: c }
