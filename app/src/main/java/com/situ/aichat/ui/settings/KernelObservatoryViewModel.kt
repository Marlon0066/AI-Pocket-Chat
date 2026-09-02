package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.affectField
import com.situ.aichat.data.model.intentQueue
import com.situ.aichat.data.model.personalityAnchor
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.prompt.growth.GrowthAnalysisService
import com.situ.aichat.prompt.growth.IntentRules
import com.situ.aichat.prompt.growth.RelationshipBands
import com.situ.aichat.prompt.growth.equilibriumPoint
import com.situ.aichat.prompt.growth.fieldForRead
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.situ.aichat.data.model.relationshipPressure
import java.time.ZoneId
import javax.inject.Inject

/** 观测台里的一个角色（**只读快照**，进屏算一次；[dimensions] 为「维度名 → 当前值」按固定序）。 */
data class KernelObservatoryRow(
    val name: String,
    val relationship: List<Pair<String, Int>>,
    /** 卷二双压：维度名 → (正压, 负压, 是否越矛盾阈)，同序。 */
    val pressure: List<Triple<String, Pair<Int, Int>, Boolean>>,
    val personality: List<Pair<String, Int>>,
    val tensionOverCap: Boolean,
    val opennessOverCap: Boolean,
    val attachmentOverEquilibrium: Boolean,
    val attachmentCap: Int,
    val lastAnalysisDate: Long?,
    val roundsSinceLastAnalysis: Int,
    val totalAnalysisCount: Int,
    val currentPhase: String?,
    val relationshipName: String?,
    val archetypeId: String?,
    val leadInSize: Int,
    val freshSize: Int,
    /** 卷三 §4.5：四场 + 日预算 + 最近命中（只读快照·列里的**参考值**）。 */
    val field: AffectField = AffectField(),
    /** 修缮卷 §3.2：四场按进屏时刻算的**读值**（慢场惰性半衰 + 快场松弛）；屏上显示 `读值(参 参考值)`。 */
    val fieldRead: AffectField = AffectField(),
    /** 命中距今小时数；`hitsAt == 0` ⇒ null（屏上显示「命中：—」）。 */
    val hitsAgoHours: Long? = null,
    /** 卷四 §4.6：意图队列**全部**条目（含非 live）预拼 `kind中文·state中文·effective`；空 ⇒ 屏上「意图：—」。 */
    val intents: List<String> = emptyList(),
)

data class KernelObservatoryState(
    val loading: Boolean = true,
    val rows: List<KernelObservatoryRow> = emptyList(),
)

/**
 * 内核观测台（活人感内核卷零 chunk5）的数据装配——**开发者调试页专用，`BuildConfig.DEBUG` 门控**。
 *
 * 存在的理由：卷零之后的四卷全部要调数值，而「现在每个角色到底停在哪」此前只能靠 Logcat 拼。
 * 本 VM 一次性读全部角色，把关系 8 维 / 性格 8 维 / 三个越顶标记 / 分析节奏元数据 / 下次窗口预估摊平成行。
 *
 * **纯读、不订阅 Flow、不轮询**（进屏算一次；下拉刷新走同一个 [load]）——调试页不该在后台持续占资源。
 * 窗口预估真调一次 [GrowthAnalysisService.collectAnalysisWindow]，它只读 DAO、无副作用。
 */
@HiltViewModel
class KernelObservatoryViewModel @Inject constructor(
    private val characterDao: CharacterDao,
    private val milestoneDao: MilestoneDao,
    private val growthAnalysisService: GrowthAnalysisService,
) : ViewModel() {

    private val _state = MutableStateFlow(KernelObservatoryState())
    val state: StateFlow<KernelObservatoryState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = KernelObservatoryState(loading = true)
            val nowMs = System.currentTimeMillis()
            val rows = characterDao.getAll().map { character ->
                val quality = character.relationshipQuality
                val spectrum = character.personalitySpectrum
                val metadata = character.growthMetadata
                val milestone = milestoneDao.getForCharacter(character.uuid).lastOrNull()
                val attachmentCap = equilibriumPoint(milestone?.relationshipName)
                val window = runCatching {
                    growthAnalysisService.collectAnalysisWindow(character.uuid, metadata.lastAnalysisDate)
                }.getOrNull()
                KernelObservatoryRow(
                    name = character.name,
                    relationship = RELATIONSHIP_NAMES.zip(quality.values),
                    pressure = character.relationshipPressure.let { p ->
                        RELATIONSHIP_NAMES.mapIndexed { i, dimName ->
                            val min = RelationshipBands.PRESSURE_CONTRADICTION_MIN
                            Triple(dimName, p.pos[i] to p.neg[i], p.pos[i] >= min && p.neg[i] >= min)
                        }
                    },
                    personality = PERSONALITY_NAMES.zip(spectrum.values),
                    tensionOverCap = quality.tension > RelationshipBands.TENSION_INTERPLAY_CAP,
                    // 卷三 K-4：天花板 = anchor.openness + 20；锚点列为空仍按 70（与规则 3 同判）。
                    opennessOverCap = spectrum.openness > if (character.personalityAnchorJSON.isEmpty()) {
                        RelationshipBands.OPENNESS_INTERPLAY_CAP
                    } else {
                        character.personalityAnchor.openness + RelationshipBands.SPRING_BAND
                    },
                    attachmentOverEquilibrium = quality.attachment > attachmentCap,
                    attachmentCap = attachmentCap,
                    lastAnalysisDate = metadata.lastAnalysisDate,
                    roundsSinceLastAnalysis = metadata.roundsSinceLastAnalysis,
                    totalAnalysisCount = metadata.totalAnalysisCount,
                    currentPhase = metadata.currentPhase,
                    relationshipName = milestone?.relationshipName,
                    archetypeId = character.relationshipArchetypeId,
                    leadInSize = window?.leadIn?.size ?: 0,
                    freshSize = window?.fresh?.size ?: 0,
                    field = character.affectField,
                    fieldRead = fieldForRead(character.affectField, nowMs, ZoneId.systemDefault()),
                    hitsAgoHours = character.affectField.hitsAt.takeIf { it > 0L }?.let { (nowMs - it) / 3_600_000L },
                    intents = character.intentQueue.intents.map { i ->
                        "${kindLabel(i.kind)}·${stateLabel(i.state)}·${IntentRules.effectiveStrength(i, nowMs)}"
                    },
                )
            }
            _state.value = KernelObservatoryState(loading = false, rows = rows)
        }
    }

    private companion object {
        val RELATIONSHIP_NAMES = RelationshipQuality.DIMENSION_NAMES
        val PERSONALITY_NAMES = PersonalitySpectrum.DIMENSION_NAMES

        /** 卷四 §4.6 观测台用中文（debug-only 硬编码；与 zh 资源 persona_cond_c0N 的「想…」同词）。 */
        fun kindLabel(kind: IntentKind): String = when (kind) {
            IntentKind.WANT_COMFORT -> "想被哄"
            IntentKind.WANT_APOLOGIZE -> "想道歉"
            IntentKind.WANT_PROBE -> "想试探"
            IntentKind.WANT_HIDE -> "想躲"
            IntentKind.WANT_SHARE -> "想分享"
            IntentKind.WANT_CONFIRM -> "想确认"
        }

        fun stateLabel(state: IntentState): String = when (state) {
            IntentState.BUDDING -> "萌生"
            IntentState.ACTIVE -> "活跃"
            IntentState.EXPRESSED -> "已表达"
            IntentState.RESOLVED -> "已了结"
            IntentState.FADED -> "消退"
        }
    }
}
