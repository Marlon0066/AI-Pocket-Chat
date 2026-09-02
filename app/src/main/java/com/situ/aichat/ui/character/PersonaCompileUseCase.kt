package com.situ.aichat.ui.character

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonaCompileMeta
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.personaCompileMeta
import com.situ.aichat.data.model.personaGains
import com.situ.aichat.data.model.personaOperators
import com.situ.aichat.data.model.personalityAnchor
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.model.resetChangedDims
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.prompt.persona.PersonaCompileCoordinator
import com.situ.aichat.prompt.persona.PersonaCompileOutcome
import com.situ.aichat.prompt.persona.operatorKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 编辑屏的人设编译协作者（活人感内核·卷一图纸 §0.5 指定：VM 增量越 +80 行硬顶即把编译调用抽出来）。
 *
 * 四件事，都只是**编排**——判据与写口纪律仍住在 [PersonaCompileCoordinator] 与 DAO 那一侧：
 * ① 手动「生成」后重读角色（[compileAndReload]）；② 新建角色首存的自动编译（[compileForNewCharacter]）；
 * ③ 用户在表单里改过的人设四列落库（[persistUserEdits]）；④ 成长两列只写用户改过的维（[persistGrowthDimensions]）。
 *
 * **修缮卷 J10**：③④ 由 VM 在 `CharacterWriteLock` 内调，入参分「fresh（锁内刚读的库值）」与「snapshot（开屏快照 = 用户看到的那份）」：
 * touched 判据对 snapshot、写值取表单、未动的列 / 维取 fresh——后台分析在编辑期间写的新值不再被开屏快照打回去（F20）。
 * 本类**不取锁**（锁不可重入·§9.4）。
 */
@Singleton
class PersonaCompileUseCase @Inject constructor(
    private val coordinator: PersonaCompileCoordinator,
    private val characterRepo: CharacterRepository,
) {

    /**
     * 跑一次编译并回读角色。**无论成功失败都回读**——失败路径也落了 `lastFailedAt`，UI 要据此显示 D-5 提示条。
     */
    suspend fun compileAndReload(uuid: String): Pair<PersonaCompileOutcome, CharacterEntity?> {
        val outcome = coordinator.compileAndPersist(uuid)
        return outcome to characterRepo.get(uuid)
    }

    /**
     * 新建角色首次保存后的自动编译（D-1 唯一例外·跑在协调器自有 scope，不随导航取消）。
     * [preserveAnchor]（修缮卷 J6）= 用户在创建表单里拖过滑杆 ⇒ 编译保留手拖锚点与现值，只写增益 / 算子。
     */
    fun compileForNewCharacter(uuid: String, preserveAnchor: Boolean) = coordinator.compileForNewCharacter(uuid, preserveAnchor)

    /**
     * 把表单里改过的人设四列落库（图纸 §3.6 · 修缮卷 J10 / J7）。三种改动共用**同一条**四列写口，未变的列取 [fresh] 原串
     * （§9.4 写口唯一）。返回「现值是否应跟着锚点走」（Y-3 判据：拖过锚点 **且** 从未分析过——对 fresh 判）。
     *
     * 用户决定登记（J7·供「重新生成」保留）：手调过档位的系统项进 `manualSystem`（手调成 1 的项在 `system` 里写显式 1 才压得住编译值）；
     * 删掉的算子进 `suppressedOperators` 墓碑（≤40·超出去最旧）。拖锚点会把 `source` 转 `manual`；改增益 / 算子不动 `source`。
     */
    suspend fun persistUserEdits(fresh: CharacterEntity, snapshot: CharacterEntity, state: CharacterEditState): Boolean {
        val anchorTouched = state.personalityAnchor != snapshot.personalityAnchor
        val gainsTouched = state.personaGains != snapshot.personaGains
        val operatorsTouched = state.personaOperators != snapshot.personaOperators
        if (anchorTouched || gainsTouched || operatorsTouched) {
            var meta = fresh.personaCompileMeta
            if (anchorTouched) meta = meta.copy(source = PersonaCompileMeta.SOURCE_MANUAL)
            var gainsJson = fresh.personaGainsJSON
            if (gainsTouched) {
                val before = snapshot.personaGains.system
                val after = state.personaGains.system
                val changed = PersonaVocab.GAIN_KEYS.filter { (after[it] ?: PersonaVocab.LEVEL_NORMAL) != (before[it] ?: PersonaVocab.LEVEL_NORMAL) }
                val manualSystem = (fresh.personaGains.manualSystem + changed).distinct().take(PersonaVocab.GAIN_KEYS.size)
                val system = after + manualSystem.filter { it !in after }.associateWith { PersonaVocab.LEVEL_NORMAL }
                gainsJson = GrowthJson.encode(state.personaGains.copy(system = system, manualSystem = manualSystem))
            }
            var operatorsJson = fresh.personaOperatorsJSON
            if (operatorsTouched) {
                val removed = snapshot.personaOperators.filter { s -> state.personaOperators.none { it.id == s.id } }.map(::operatorKey)
                val suppressed = (meta.suppressedOperators + removed).distinct().takeLast(PersonaCompileMeta.MAX_SUPPRESSED)
                meta = meta.copy(suppressedOperators = suppressed)
                operatorsJson = GrowthJson.encodePersonaOperators(state.personaOperators)
            }
            characterRepo.updatePersonaCompile(
                uuid = fresh.uuid,
                anchor = if (anchorTouched) GrowthJson.encode(state.personalityAnchor) else fresh.personalityAnchorJSON,
                meta = GrowthJson.encode(meta),
                gains = gainsJson,
                operators = operatorsJson,
            )
        }
        return anchorTouched && fresh.growthMetadata.totalAnalysisCount == 0
    }

    /**
     * 成长两列（性格现值 8 维 + 关系 8 维）只写用户改过的维（修缮卷 J10 · F20）：逐维「表单值 ≠ 开屏快照」才取表单值，其余取 [fresh]
     * （后台分析在编辑期间写的新值保留）；关系改过的维双压重置（卷二表1 ⑦·`resetChangedDims` 以 fresh 为基），只改性格时压强列原串透传（P-E15）。
     * 三者与 fresh 全等 ⇒ 不写。[syncCurrentToAnchor] = Y-3：现值跟锚点走（拖锚点 + 从未分析）。
     */
    suspend fun persistGrowthDimensions(fresh: CharacterEntity, snapshot: CharacterEntity, state: CharacterEditState, syncCurrentToAnchor: Boolean) {
        val spectrumToWrite = if (syncCurrentToAnchor) state.personalityAnchor else state.personalitySpectrum
        var spectrumNew = fresh.personalitySpectrum
        for (d in spectrumNew.values.indices) {
            if (spectrumToWrite.values[d] != snapshot.personalitySpectrum.values[d]) spectrumNew = spectrumNew.setValue(d, spectrumToWrite.values[d])
        }
        var qualityNew = fresh.relationshipQuality
        for (d in qualityNew.values.indices) {
            if (state.relationshipQuality.values[d] != snapshot.relationshipQuality.values[d]) qualityNew = qualityNew.setValue(d, state.relationshipQuality.values[d])
        }
        val qualityChanged = qualityNew != fresh.relationshipQuality
        if (spectrumNew == fresh.personalitySpectrum && !qualityChanged) return
        val pressure = fresh.relationshipPressure.resetChangedDims(fresh.relationshipQuality, qualityNew)
        characterRepo.updateGrowthDimensions(
            fresh.uuid,
            GrowthJson.encode(spectrumNew),
            GrowthJson.encode(pressure.toQuality()),
            if (qualityChanged) GrowthJson.encode(pressure) else fresh.relationshipPressureJSON,
        )
    }
}
