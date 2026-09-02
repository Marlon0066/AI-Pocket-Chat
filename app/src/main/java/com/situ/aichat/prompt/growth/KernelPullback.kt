package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.affectField
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.model.shrinkPositive
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.repository.CharacterWriteLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 老角色一次性拉回扫（活人感内核卷零 chunk3·图纸 §3.3 · **修缮卷 J9 改按角色标记**）。
 *
 * **为什么需要**：卷零 chunk2 才给两个跑飞棘轮装上封顶与泄压（[applyDimensionInterplay] 的 H1/H2 +
 * [applyTensionRelief] 的 H3）。止血只管住「从今往后」——**已经漂上去的存量角色**（张力/依恋/坦诚被
 * 无源棘轮推到接近满值）不会自己下来，后面四卷全部建在这个歪地基上。本扫把它们拉回一半。
 *
 * **有意只拉一半**（`(cur + cap) / 2` 整除·用户拍板）：这些数值里混着**真实相处攒下的分**与**棘轮
 * 刷出来的分**，无从区分；一次性清零等于抹掉用户几个月的相处史，拉到中点是「削掉明显虚高、保住底子」。
 *
 * **只覆盖三维**（图纸 Z-4）：`tension` / `attachment`（关系）+ `openness`（性格）——正是两个跑飞环的
 * 产物；修缮卷 D-10 加门：坦诚维只在 `trust ≥ `[RelationshipBands.OPENNESS_INTERPLAY_TRUST_MIN]（规则 3 才推得动它）时拉。
 *
 * **幂等 = 按角色标记**（修缮卷 J9·取代 SharedPreferences 全局戳）：`AffectField.pullbackDone` 随 `affectFieldJSON` 走、随备份走
 * ——老备份导入到已跑过的机器也会被处理恰一次（E36）；`creationDate ≥ `[PULLBACK_CUTOFF_MS]（卷零止血落地之后建的）永不拉回。
 * 单角色两步写（维度 → 标记）中途被杀 ⇒ 标记未写 ⇒ 下次冷启重跑该角色（拉回幂等：已在界内不再动），再打标记。
 *
 * **不写 growthLog**（图纸 Z-7）：① 复用既有的 [CharacterDao.updateGrowthDimensions] 写口（总图纸 §9.5
 * 禁止新增第 7 条关系质感 UPDATE 语句），而它不写日志列；② 成长日志是「角色经历了什么」的叙事，
 * 系统校正不属于角色经历。观测走 Logcat（观测行恒打·含 N == 0）。锁序：写锁 → 场锁（[AffectKernel.withFieldLocked]）。
 */
@Singleton
class KernelPullback @Inject constructor(
    private val characterDao: CharacterDao,
    private val milestoneDao: MilestoneDao,
    private val characterWriteLock: CharacterWriteLock,
    private val affectKernel: AffectKernel,
) {

    /** 三维各自被拉回了几个角色（仅用于 Logcat 观测）。 */
    private data class Tally(var tension: Int = 0, var attachment: Int = 0, var openness: Int = 0)

    /**
     * 跑一次拉回扫：遍历全部角色，跳过「卷零之后建的」与「已标记」的；其余逐角色独立进写锁处理并打标记。
     * 单角色异常吞掉继续下一个（照 [RelationshipArchetypeCalibrator.recalibrateAll] 口径），整批不中断。
     *
     * 排在启动维护块**最前**（早于原型校准与闲置淡化）：拉回若把某维降到该角色原型地板以下，
     * 紧随其后的校准会把它抬回地板——**这是正确行为**（地板是原型的设计下限）。
     */
    suspend fun runOnceIfNeeded() {
        val characters = characterDao.getAll()
        val tally = Tally()
        var processed = 0
        var changedCount = 0
        for (character in characters) {
            if (character.creationDate >= PULLBACK_CUTOFF_MS || character.affectField.pullbackDone) continue
            processed++
            runCatching {
                if (pullBackOneCharacter(character.uuid, tally)) changedCount++
            }.onFailure { Log.w(TAG, "卷零拉回角色 ${character.uuid} 失败，跳过", it) }
        }
        // 观测点**无条件**打（R1 复核 🔵-2 / 修缮卷 §3.10）：验收人要能区分「跑过且无人越顶」与「压根没跑」。
        Log.i(TAG, "卷零拉回：处理 $processed 个角色，$changedCount 个回拉（tension=${tally.tension} attachment=${tally.attachment} openness=${tally.openness}）")
    }

    /** 对单角色算三维拉回并列级写回，再打 `pullbackDone` 标记；有变更返回 true。锁内 fresh 读（PITFALLS §1a）。 */
    private suspend fun pullBackOneCharacter(characterUuid: String, tally: Tally): Boolean =
        characterWriteLock.withCharacterLock(characterUuid) {
            val character = characterDao.getByUuid(characterUuid) ?: return@withCharacterLock false
            if (character.affectField.pullbackDone) return@withCharacterLock false
            val currentRelationship = milestoneDao.getForCharacter(characterUuid).lastOrNull()?.relationshipName
            val beforeQuality = character.relationshipQuality
            val beforeSpectrum = character.personalitySpectrum
            val rKeys = RelationshipQuality.DIMENSION_KEYS
            var quality = beforeQuality
            var spectrum = beforeSpectrum

            // 张力上限 = 卷零封顶值；依恋上限 = 当前名分的自然平衡点（复用既有口径，不发明新数·图纸 Z-5）。
            val newTension = pulledBackValue(beforeQuality.tension, RelationshipBands.TENSION_INTERPLAY_CAP)
            if (newTension != beforeQuality.tension) {
                quality = quality.setValue(rKeys.indexOf("tension"), newTension)
                tally.tension++
            }
            val newAttachment = pulledBackValue(beforeQuality.attachment, equilibriumPoint(currentRelationship))
            if (newAttachment != beforeQuality.attachment) {
                quality = quality.setValue(rKeys.indexOf("attachment"), newAttachment)
                tally.attachment++
            }
            // 修缮卷 D-10：坦诚只在信任 ≥ 70 时才可能被规则 3 推高——信任不够的高坦诚是用户手拖 / 编译给的本性，不拉。
            val newOpenness = if (beforeQuality.trust >= RelationshipBands.OPENNESS_INTERPLAY_TRUST_MIN) {
                pulledBackValue(beforeSpectrum.openness, RelationshipBands.OPENNESS_INTERPLAY_CAP)
            } else {
                beforeSpectrum.openness
            }
            if (newOpenness != beforeSpectrum.openness) {
                spectrum = spectrum.setValue(PersonalitySpectrum.DIMENSION_KEYS.indexOf("openness"), newOpenness)
                tally.openness++
            }

            val changed = quality != beforeQuality || spectrum != beforeSpectrum
            if (changed) {
                // 卷二表1 ⑧ / P-1：拉回走**专用口径** shrinkPositive——只把正压减到目标净额，负压一个字节不动。
                // ⚠️ 绝不可用 applyNetDelta：那会给 tension 加一大笔负压，把「系统撤销棘轮虚高」伪造成
                // 「又想又不敢」的真矛盾，直接污染卷二 §4 的矛盾判定（一个被拉回的老角色会立刻凑出 pos≈97/neg≈19）。
                var pressure = character.relationshipPressure
                if (newTension != beforeQuality.tension) pressure = pressure.shrinkPositive(rKeys.indexOf("tension"), newTension)
                if (newAttachment != beforeQuality.attachment) pressure = pressure.shrinkPositive(rKeys.indexOf("attachment"), newAttachment)
                characterDao.updateGrowthDimensions(
                    uuid = characterUuid,
                    personalitySpectrumJSON = GrowthJson.encode(spectrum),
                    relationshipQualityJSON = GrowthJson.encode(pressure.toQuality()),
                    relationshipPressureJSON = GrowthJson.encode(pressure),
                )
            }
            // 标记按角色落在场列（J9）：写锁内再进场锁（锁序 写锁 → 场锁·§3.12）；列级盲写、恒 1 次。
            affectKernel.withFieldLocked(characterUuid, System.currentTimeMillis()) { it.copy(pullbackDone = true) }
            changed
        }

    internal companion object {
        private const val TAG = "GrowthAnalysis"

        /** 2026-09-01 00:00 Asia/Shanghai（`date -r 1788192000` 已核）：卷零止血落地后建的角色从未经历跑飞棘轮，永不拉回（§3.11 锁定）。 */
        const val PULLBACK_CUTOFF_MS = 1788192000000L
    }
}

/**
 * 拉回后的新值：**仅当 `current > cap`** 才拉到「当前值与上限的中点」，否则一动不动
 * （界内不碰 = 拉回天然幂等，重跑无害）。
 *
 * 公式 `(current + cap) / 2` 是**整数除法**（图纸 §9.2 锁定，不许改成四舍五入）。
 */
internal fun pulledBackValue(current: Int, cap: Int): Int =
    if (current > cap) (current + cap) / 2 else current
