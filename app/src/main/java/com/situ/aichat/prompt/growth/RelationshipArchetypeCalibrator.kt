package com.situ.aichat.prompt.growth

import android.content.Context
import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.model.syncedTo
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单角色校准结果（calibrate 返回值 + recalibrateAll 汇总用）。 */
data class CalibrationOutcome(val resolvedId: String?, val qualityChanged: Boolean)

/**
 * 成长原型校准（图纸 docs/handoff/2026-07-11-成长原型校准.md §3.3）棘轮/回拉/写列的**唯一执行者**。
 *
 * 分数写全走 [CharacterWriteLock] + 锁内 fresh 读 + 列级 UPDATE（P12.6 惯例）；棘轮只升不降、天花板仅手动
 * 路径写侧应用、AI 路恒 applyCeilings=false；解析结果（含 null）必落列（清陈旧值防漂移）。
 * 存量重算触发 = 内容指纹（D-14：算法修订号 + 地板表 + **有效词条流摘要**——注释/空行不进指纹，微图纸加固⑤），
 * App versionCode 不参与；启动扫另有 **APK 安装时间戳一级快路**（加固③：未重装 = 设定不可能变，连词表都不读）。
 *
 * [apkLastUpdateTime]：本 APK 的安装/更新时刻（任何重装必变，含同 versionCode 侧载）；返回 0 = 取包信息失败 →
 * 恒走慢路且不写 APK 戳（防 0==0 永久卡快路）。测试经 internal 主构造注入假 provider（照 [RelationshipLexicon.fromRawText] 先例）。
 */
@Singleton
class RelationshipArchetypeCalibrator internal constructor(
    private val characterDao: CharacterDao,
    private val characterWriteLock: CharacterWriteLock,
    private val lexicon: RelationshipLexicon,
    private val throttleStore: MaintenanceThrottleStore,
    private val milestoneDao: MilestoneDao,
    private val apkLastUpdateTime: () -> Long,
) {
    @Inject
    constructor(
        characterDao: CharacterDao,
        characterWriteLock: CharacterWriteLock,
        lexicon: RelationshipLexicon,
        throttleStore: MaintenanceThrottleStore,
        milestoneDao: MilestoneDao,
        @ApplicationContext context: Context,
    ) : this(characterDao, characterWriteLock, lexicon, throttleStore, milestoneDao, apkTimeProviderOf(context))

    /** 进程内启动扫只查一次（设定只可能随新 APK / 新进程变化）。 */
    @Volatile private var startupChecked = false

    /**
     * 自取锁校准（手动 / AI-非持锁入口）。**调用方已持该角色锁时绝不可调本函数**（锁不可重入·0.1#6）——
     * 持锁方改用 [calibrateHoldingLock]。
     */
    suspend fun calibrate(
        uuid: String,
        relationshipName: String?,
        applyFloors: Boolean,
        applyCeilings: Boolean,
    ): CalibrationOutcome = characterWriteLock.withCharacterLock(uuid) {
        calibrateHoldingLock(uuid, relationshipName, applyFloors, applyCeilings)
    }

    /**
     * 调用方持锁时的校准（锁内 fresh 读·PITFALLS §1a）：解析名分→原型→先回拉后抬地板（结果 ∈[地板,天花板]）→
     * 有变化则列级 UPDATE。解析结果无论 id 还是 null 都落列。
     */
    suspend fun calibrateHoldingLock(
        uuid: String,
        relationshipName: String?,
        applyFloors: Boolean,
        applyCeilings: Boolean,
    ): CalibrationOutcome {
        val character = characterDao.getByUuid(uuid) ?: return CalibrationOutcome(null, false)
        val resolvedId = lexicon.resolve(relationshipName)
        val archetype = resolvedId?.let { RelationshipArchetype.byId(it) }
        val oldQuality = character.relationshipQuality
        var newQuality = oldQuality
        if (archetype != null) {
            if (applyCeilings && archetype.ceilings != null) newQuality = pullDownToCeilings(newQuality, archetype.ceilings)
            if (applyFloors) newQuality = ratchetToFloors(newQuality, archetype.floors)
        }
        val qualityChanged = newQuality != oldQuality
        val idChanged = character.relationshipArchetypeId != resolvedId
        when {
            // 卷二表1 ⑤：先压天花板后抬地板的**计算顺序不动**，只把算出来的目标净额经写口翻译成压强
            // ——抬地板 ⇒ 加正压；压天花板 ⇒ 加负压。
            qualityChanged -> {
                val pressure = character.relationshipPressure.syncedTo(newQuality)
                characterDao.updateArchetypeCalibration(
                    uuid, resolvedId, GrowthJson.encode(pressure.toQuality()), GrowthJson.encode(pressure),
                )
            }
            idChanged -> characterDao.updateRelationshipArchetypeId(uuid, resolvedId)
            // 两者皆无变化 → 不写。
        }
        return CalibrationOutcome(resolvedId, qualityChanged)
    }

    /** 全量重扫（导入 / 指纹变触发）：逐角色独立，单角色异常吞掉继续整批。名分零动，分数只托底。 */
    suspend fun recalibrateAll(reason: String) {
        val chars = characterDao.getAll()
        var raised = 0
        var recognized = 0
        for (c in chars) {
            runCatching {
                val name = milestoneDao.getForCharacter(c.uuid).lastOrNull()?.relationshipName
                val outcome = calibrate(c.uuid, name, applyFloors = true, applyCeilings = false)
                if (outcome.qualityChanged) raised++
                if (outcome.resolvedId != null) recognized++
            }.onFailure { Log.w(TAG, "校准角色 ${c.uuid} 失败，跳过", it) }
        }
        Log.i(TAG, "sweep[$reason]: 扫${chars.size}个/抬分${raised}个/识别${recognized}个")
    }

    /**
     * 冷启动全量扫（进程内一次·内容指纹驱动·D-14）：整体在 IO 线程（加固①：词表读取/解析/哈希绝不占主线程）。
     * **快路**（加固③锁定）：APK 安装时间戳 >0 且 == 存戳 → 未重装、设定不可能变 → 直接返回（零文件读取）。
     * 慢路：指纹 ≠ 存戳 → recalibrateAll → **完成后**才写指纹戳（中途进程死 → 戳未写 → 下次重跑·幂等）→
     * 最后写 APK 戳（死于两写之间 → 下次慢路发现指纹相同 → 仅补 APK 戳）。
     */
    suspend fun runStartupSweepIfNeeded() {
        if (startupChecked) return
        startupChecked = true
        withContext(Dispatchers.IO) {
            val apkStamp = apkLastUpdateTime()
            if (apkStamp > 0L &&
                apkStamp.toString() == throttleStore.readTextStamp(MaintenanceThrottleStore.KEY_ARCHETYPE_APK_STAMP)
            ) {
                return@withContext // 快路：未重装
            }
            val fp = calibrationFingerprint()
            if (fp != throttleStore.readTextStamp(MaintenanceThrottleStore.KEY_ARCHETYPE_CALIBRATION_FINGERPRINT)) {
                recalibrateAll("fingerprint-${fp.take(8)}")
                throttleStore.writeTextStamp(MaintenanceThrottleStore.KEY_ARCHETYPE_CALIBRATION_FINGERPRINT, fp)
            }
            if (apkStamp > 0L) {
                throttleStore.writeTextStamp(MaintenanceThrottleStore.KEY_ARCHETYPE_APK_STAMP, apkStamp.toString())
            }
        }
    }

    /** 校准内容指纹（图纸 §3.3 / D-14·加固⑤修订）：SHA-256(算法修订号 + 19 原型地板表 + 有效词条流摘要)。 */
    fun calibrationFingerprint(): String = sha256Hex(calibrationCanonicalInput(lexicon.entriesDigestHex))

    companion object {
        private const val TAG = "ArchetypeCalibration"

        /** APK 安装时间戳 provider（委托构造参数里不能写 lambda 字面量——编译器判可能捕获 this，故经 companion 工厂）。 */
        private fun apkTimeProviderOf(context: Context): () -> Long = {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        }

        /**
         * 算法语义修订号：**归一化 / 匹配 / 棘轮语义变更时必须 +1**（数据变化由指纹自动感知，算法语义变化靠此手动位）。
         * App versionCode/versionName 不参与任何校准触发。
         */
        internal const val CALIBRATION_ALGO_REVISION = 1

        /** 棘轮抬地板（图纸 §3.3）：逐维 `if (v < floor) floor else v`（只升不降；地板 0 天然 no-op）。 */
        internal fun ratchetToFloors(q: RelationshipQuality, floors: IntArray): RelationshipQuality {
            var r = q
            for (i in floors.indices) if (r.values[i] < floors[i]) r = r.setValue(i, floors[i])
            return r
        }

        /** 回拉到天花板（图纸 §3.3）：逐维 `if (ceiling >= 0 && v > ceiling) ceiling else v`。 */
        internal fun pullDownToCeilings(q: RelationshipQuality, ceilings: IntArray): RelationshipQuality {
            var r = q
            for (i in ceilings.indices) {
                val c = ceilings[i]
                if (c >= 0 && r.values[i] > c) r = r.setValue(i, c)
            }
            return r
        }

        /**
         * 指纹规范串（图纸 §3.3 逐字格式·加固⑤修订版）：`algo=N|floors=` + 19 原型按 §3.1 表序拼 `id:f1,…,f8;`
         * + `|lexicon=sha256:` + 有效词条流摘要 hex（流定义见 [RelationshipLexicon.parseLexicon]——注释/空行不进流）。
         * 天花板与水位阈值不进指纹（不影响全量扫结果·D-14）。
         */
        internal fun calibrationCanonicalInput(lexiconEntriesDigestHex: String): String {
            val sb = StringBuilder("algo=").append(CALIBRATION_ALGO_REVISION).append("|floors=")
            for (a in RelationshipArchetype.ALL) {
                sb.append(a.id).append(':').append(a.floors.joinToString(",")).append(';')
            }
            sb.append("|lexicon=sha256:").append(lexiconEntriesDigestHex)
            return sb.toString()
        }

        internal fun sha256Hex(input: String): String =
            bytesToLowerHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))
    }
}
