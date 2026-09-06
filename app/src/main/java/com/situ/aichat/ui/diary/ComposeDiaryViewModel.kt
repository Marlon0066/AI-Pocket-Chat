package com.situ.aichat.ui.diary

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.model.MomentTriggerType
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.remote.llm.LlmError
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.diary.DiaryApiMissingFlag
import com.situ.aichat.prompt.diary.DiaryCommentService
import com.situ.aichat.prompt.diary.DiaryGenerationCoordinator
import com.situ.aichat.prompt.diary.DiaryGuideAnswers
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.VoiceMessageRecorder
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.StringListJson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** 撰写页编辑状态。[moodEmoji]/[moodText] 同步设置；[images]=磁盘路径（已落盘）；编辑模式保留 uuid。 */
data class ComposeDiaryState(
    val isEdit: Boolean = false,
    val uuid: String? = null,
    val content: String = "",
    val moodEmoji: String? = null,
    val moodText: String? = null,
    val images: List<String> = emptyList(),
    val visibility: DiaryVisibility = DiaryVisibility.OPEN_TO_AI,
    val isGenerating: Boolean = false,
    /** U1 编写页票据日期头用（新建=开页时刻·编辑=原条目时间）。纯展示，不参与保存逻辑（save 仍用 existing.timestamp）。 */
    val timestamp: Long = 0L,
    /** 编辑的是「TA 的信」（交换日记）→ 隐藏「AI 帮我写」两个入口（它写的是用户视角日记，一点即整篇覆盖）。新建恒 false。 */
    val isExchangeLetter: Boolean = false,
)

/** J5「今天的素材」芯片种类（装饰圆点色 chat=calm/见面=sad/礼物=shy 由 UI 侧映射）。 */
enum class MaterialKind { CHAT, MEETING, GIFT }

/** J5 空态素材芯片（[label] 展示文案·[starter] 点击置入正文的起笔模板句·均字符串资源·零 LLM）。 */
data class MaterialChip(val kind: MaterialKind, val label: String, val starter: String)

/**
 * 日记撰写/编辑 VM（M07 7.1.5）。1:1 对齐 iOS `ComposeDiaryView`：12 心情、多图≤9、AI 帮写、草稿/发布、可见性。
 * **发布(非草稿)+openToAI → 调度角色评论**（接 7.1.3 `DiaryCommentService`；编辑模式仅「草稿→发布」时触发，对齐 iOS）。
 *
 * 图片即拍即存盘（`ContentImageStore`），路径进 [ComposeDiaryState.images]；放弃/删除时清理孤儿文件
 * （[sessionSavedPaths] 记本次会话落盘的，区分编辑模式的原有图，避免误删 DB 仍引用的原图）。
 */
@HiltViewModel
class ComposeDiaryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val diaryRepository: DiaryRepository,
    private val settingsRepo: SettingsRepository,
    private val commentService: DiaryCommentService,
    private val generationCoordinator: DiaryGenerationCoordinator,
    private val conversationRepository: ConversationRepository,
    private val offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
    private val giftDao: GiftDao,
    private val characterRepository: CharacterRepository,
    private val voiceRecorder: VoiceMessageRecorder,
    private val sttEngine: SttEngine,
) : ViewModel() {

    private val editUuid: String? = savedStateHandle.get<String>(ARG_UUID)?.takeIf { it.isNotEmpty() }

    private val _state = MutableStateFlow(
        ComposeDiaryState(isEdit = editUuid != null, uuid = editUuid, timestamp = System.currentTimeMillis()),
    )
    val state: StateFlow<ComposeDiaryState> = _state.asStateFlow()

    /** AI 帮写失败的一次性事件（P0-15）：未配置 API（可引导去设置）/ 其它失败（含网络）。CONFLATED 一次性、不随旋转重弹。 */
    sealed interface AiDraftError {
        data object NoApi : AiDraftError
        data class Failed(val message: String?) : AiDraftError
    }

    private val _aiDraftError = Channel<AiDraftError>(Channel.CONFLATED)
    val aiDraftError = _aiDraftError.receiveAsFlow()

    /** J6「说一段」一次性 snackbar 文案事件（录音失败/太短/转写失败三态·已解析成串）。CONFLATED 一次性不随旋转重弹。 */
    private val _voiceMessage = Channel<String>(Channel.CONFLATED)
    val voiceMessage = _voiceMessage.receiveAsFlow()

    /** J6 语音落笔协作者（构造注入 recorder+engine·viewModelScope 驱动·追加经 [setContent] 走 J1 镜像；internal 型故属性亦 internal）。 */
    internal val voice = ComposeDiaryVoiceController(
        scope = viewModelScope,
        appContext = context,
        voiceRecorder = voiceRecorder,
        sttEngine = sttEngine,
        currentContent = { _state.value.content },
        setContent = ::setContent,
        emitMessage = { _voiceMessage.trySend(it) },
    )

    /** 本次会话经 picker 落盘的路径（用于放弃时清理；编辑模式原图不在其中，故不会误删）。 */
    private val sessionSavedPaths = mutableSetOf<String>()
    private var originalImages: List<String> = emptyList()

    /** J1②：编辑模式载入时的五字段快照（dirty 精判基准）。新建模式恒 null。 */
    private var originalSnapshot: Snapshot? = null

    /** J1①：本页由进程死亡 SavedStateHandle 镜像恢复而来（true 时 DB 加载只填快照·不覆盖已恢复的 state）。 */
    private var restoredFromDeath = false

    /** J5「今天的素材」芯片（init 一次性查·非热流·仅空态展示）。 */
    private val _materialChips = MutableStateFlow<List<MaterialChip>>(emptyList())
    val materialChips: StateFlow<List<MaterialChip>> = _materialChips.asStateFlow()

    /** J1②：dirty 精判五字段快照（编辑模式 DB 加载时填）。 */
    private data class Snapshot(
        val content: String,
        val moodEmoji: String?,
        val moodText: String?,
        val images: List<String>,
        val visibility: DiaryVisibility,
    )

    init {
        // J1①进程恢复镜像：handle 曾写过 KEY_CONTENT → 整组回灌 state + 恢复 sessionSavedPaths（孤儿清理链跨进程
        // 不脱钩），并置 restoredFromDeath 让下方 DB 加载不覆盖已恢复的 state。
        val mirroredContent = savedStateHandle.get<String>(KEY_CONTENT)
        if (mirroredContent != null) {
            restoredFromDeath = true
            _state.value = _state.value.copy(
                content = mirroredContent,
                moodEmoji = savedStateHandle.get<String>(KEY_MOOD_EMOJI),
                moodText = savedStateHandle.get<String>(KEY_MOOD_TEXT),
                images = StringListJson.decode(savedStateHandle.get<String>(KEY_IMAGES).orEmpty()),
                visibility = DiaryVisibility.fromRaw(
                    savedStateHandle.get<String>(KEY_VISIBILITY) ?: DiaryVisibility.OPEN_TO_AI.raw,
                ),
            )
            sessionSavedPaths.addAll(StringListJson.decode(savedStateHandle.get<String>(KEY_SESSION_PATHS).orEmpty()))
        }
        // 编辑模式 DB 加载仍执行：填 originalSnapshot（dirty 精判）+ originalImages（孤儿清理）；restoredFromDeath 时
        // **不覆盖** state（恢复回来的编辑页照样精判·快照仍按 DB 原值填·§4-J1 次序锁死）。
        if (editUuid != null) {
            viewModelScope.launch {
                diaryRepository.getEntry(editUuid)?.let { e ->
                    originalImages = e.imagePaths
                    originalSnapshot = Snapshot(
                        content = e.content,
                        moodEmoji = e.moodEmoji,
                        moodText = e.moodText,
                        images = e.imagePaths,
                        visibility = DiaryVisibility.fromRaw(e.visibilityRaw),
                    )
                    if (restoredFromDeath) {
                        // 🟡-3：恢复态镜像已回灌全部编辑字段，但 timestamp 是纯展示字段·不在六键镜像内 → 停在 VM
                        // 重建时刻(=今天)。此处仅从 DB 补 timestamp 与 isExchangeLetter 两个**非编辑字段**
                        // (六键镜像里的编辑字段一个不碰)，编辑旧日记进程死亡恢复后日期头/邮票才不会变今天、
                        // 「AI 帮我写」入口也仍按信的身份隐藏。存库无恙(save 用 existing.timestamp)。
                        _state.value = _state.value.copy(
                            timestamp = e.timestamp,
                            isExchangeLetter = e.authorCharacterUuid != null,
                        )
                    } else {
                        _state.value = ComposeDiaryState(
                            isEdit = true,
                            uuid = e.uuid,
                            content = e.content,
                            moodEmoji = e.moodEmoji,
                            moodText = e.moodText,
                            images = e.imagePaths,
                            visibility = DiaryVisibility.fromRaw(e.visibilityRaw),
                            timestamp = e.timestamp,
                            isExchangeLetter = e.authorCharacterUuid != null,
                        )
                    }
                }
            }
        }
        loadMaterialChips()
    }

    /**
     * J5 一次性查「今天的素材」→ [materialChips]（Dispatchers.IO·非热流·永不阻塞首帧）。查询逻辑抽 [computeMaterialChips]
     * 便于 T2 确定性直测（避开 IO 线程竞态）。三查询各自 try/catch 吞并 Log.w，任一失败/空 = 该芯片缺席、不阻塞页面。
     */
    private fun loadMaterialChips() {
        viewModelScope.launch(Dispatchers.IO) { _materialChips.value = computeMaterialChips() }
    }

    /** J5 三查询组装素材芯片（聊天 top1 / 见面 top1 / 礼物 top1·当日 0 点起）。internal 便于 T2 直测。 */
    internal suspend fun computeMaterialChips(): List<MaterialChip> {
        val chips = mutableListOf<MaterialChip>()
        val now = System.currentTimeMillis()
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 聊天：当日有消息的会话取最近 1 条（按 lastMessageDate·ConversationEntity 的「最近消息时间」字段）。
        runCatching {
            conversationRepository.observeActive().first()
                .filter { (it.lastMessageDate ?: 0L) >= todayStart }
                .maxByOrNull { it.lastMessageDate ?: 0L }
                ?.let { conv ->
                    characterRepository.get(conv.characterUuid)?.name?.takeIf { it.isNotBlank() }?.let { name ->
                        chips += MaterialChip(
                            MaterialKind.CHAT,
                            context.getString(R.string.diary_chip_chat, name),
                            context.getString(R.string.diary_chip_starter_chat, name),
                        )
                    }
                }
        }.onFailure { Log.w(TAG, "素材芯片·聊天查询失败", it) }
        // 见面：当日跨角色见面行取最近 1 条（startedAtMillis 最大）；有地点用地点·否则用角色名兜底。
        runCatching {
            offlineMeetingMemoryRepository.meetingsOnDay(todayStart, now)
                .maxByOrNull { it.startedAtMillis }
                ?.let { m ->
                    characterRepository.get(m.characterUuid)?.name?.takeIf { it.isNotBlank() }?.let { name ->
                        val label = if (m.location.isNotBlank()) {
                            context.getString(R.string.diary_chip_meeting, m.location)
                        } else {
                            context.getString(R.string.diary_chip_meeting_generic, name)
                        }
                        chips += MaterialChip(MaterialKind.MEETING, label, context.getString(R.string.diary_chip_starter_meeting, name))
                    }
                }
        }.onFailure { Log.w(TAG, "素材芯片·见面查询失败", it) }
        // 礼物：当日用户收到的最近 1 份（只读·钱路零碰）。
        runCatching {
            giftDao.userReceivedGiftBetween(todayStart, now)?.let {
                chips += MaterialChip(
                    MaterialKind.GIFT,
                    context.getString(R.string.diary_chip_gift),
                    context.getString(R.string.diary_chip_starter_gift),
                )
            }
        }.onFailure { Log.w(TAG, "素材芯片·礼物查询失败", it) }
        return chips
    }

    fun setContent(value: String) {
        _state.value = _state.value.copy(content = value)
        mirrorAll()
    }

    /** 点选心情；再点同一个则取消（对齐 iOS toggle）。 */
    fun toggleMood(emoji: String, text: String) {
        _state.value = if (_state.value.moodEmoji == emoji) {
            _state.value.copy(moodEmoji = null, moodText = null)
        } else {
            _state.value.copy(moodEmoji = emoji, moodText = text)
        }
        mirrorAll()
    }

    fun setVisibility(visibility: DiaryVisibility) {
        _state.value = _state.value.copy(visibility = visibility)
        mirrorAll()
    }

    /** 选图后落盘并追加（总数钳到 9，对齐 iOS prefix(9)）。 */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val saved = ContentImageStore.saveAll(context, uris)
            sessionSavedPaths.addAll(saved)
            _state.value = _state.value.copy(images = (_state.value.images + saved).take(9))
            mirrorAll()
        }
    }

    /** 从列表移除（不立刻删盘——可能是编辑模式原图；删盘推迟到保存/放弃统一处理）。 */
    fun removeImage(path: String) {
        _state.value = _state.value.copy(images = _state.value.images - path)
        mirrorAll()
    }

    /**
     * J1① 六键全组镜像（R1 🟡-1）：任一 setter 触发即写全六键（含 [KEY_CONTENT]）。
     * 原「各 setter 逐键各写各的」会漏掉「先加图/先选心情、一个字没打」的会话——[KEY_CONTENT] 缺席时 init 的
     * `KEY_CONTENT != null` 恢复触发条件不成立 → 整组镜像（含 [KEY_IMAGES]/[KEY_SESSION_PATHS]）被跳过 →
     * 已落盘图从页面消失且 [sessionSavedPaths] 失忆 → 磁盘孤儿图永久失联。改为全组镜像后，任何改动都带上 content
     * （编辑模式此刻 `_state.value.content` 已是 DB 文本；新建则为空串但**非 null**），恢复触发条件不用改就自洽。
     * 编解码口径不变：images/sessionPaths 走 [StringListJson]，visibility 存 raw。
     */
    private fun mirrorAll() {
        savedStateHandle[KEY_CONTENT] = _state.value.content
        savedStateHandle[KEY_MOOD_EMOJI] = _state.value.moodEmoji
        savedStateHandle[KEY_MOOD_TEXT] = _state.value.moodText
        savedStateHandle[KEY_IMAGES] = StringListJson.encode(_state.value.images)
        savedStateHandle[KEY_VISIBILITY] = _state.value.visibility.raw
        savedStateHandle[KEY_SESSION_PATHS] = StringListJson.encode(sessionSavedPaths.toList())
    }

    /**
     * AI 帮写：复用日记生成（当天上下文），填回正文（对齐 iOS generateAIDraft）。
     * R2 心情闭环：已手选心情 → 以「emoji 文案」注入 prompt 心情段；未选 → 用 AI 推断心情回填选中态
     * （文案由 [DIARY_MOODS] 映射，和手选同源）。
     * P0-15：补失败反馈——区分「未配置 API」（服务已置 [DiaryApiMissingFlag]，可引导去设置）与「网络/其它失败」；
     * 并修真 bug——原先网络失败会从 generateDraft 抛出未捕获 → isGenerating 永不复位、转圈卡死，现 try/finally 兜底。
     */
    fun generateAiDraft(guide: DiaryGuideAnswers? = null) {
        if (_state.value.isGenerating) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true)
            try {
                val picked = _state.value
                val moodHint = picked.moodEmoji?.let { e -> listOfNotNull(e, picked.moodText).joinToString(" ") }
                // §B8：把用户**已经贴好**的照片张数一并告诉 AI——先贴 9 张海边照再点「AI 帮我写」时，
                // 至少让它知道有照片，而不是只复述当天聊天记录、写出与照片脱节的正文。
                val draft = generationCoordinator.generateDraftForComposer(
                    dateMillis = System.currentTimeMillis(),
                    moodHint = moodHint,
                    guide = guide,
                    photoCount = picked.images.size,
                )
                if (draft != null) {
                    val current = _state.value
                    val fillMood = current.moodEmoji == null && draft.moodEmoji != null
                    val inferredLabel = if (fillMood) {
                        DIARY_MOODS.firstOrNull { it.emoji == draft.moodEmoji }?.let { context.getString(it.labelRes) }
                    } else {
                        null
                    }
                    _state.value = current.copy(
                        content = draft.content,
                        moodEmoji = if (fillMood) draft.moodEmoji else current.moodEmoji,
                        moodText = if (fillMood) inferredLabel else current.moodText,
                    )
                } else {
                    _aiDraftError.send(
                        if (DiaryApiMissingFlag.get(context)) AiDraftError.NoApi else AiDraftError.Failed(null),
                    )
                }
            } catch (e: LlmError) {
                _aiDraftError.send(AiDraftError.Failed(e.message)) // 网络/超时/HTTP 等带 zh 具体原因
            } catch (e: Exception) {
                _aiDraftError.send(AiDraftError.Failed(null))
            } finally {
                _state.value = _state.value.copy(isGenerating = false)
            }
        }
    }

    val canSave: Boolean get() = _state.value.content.isNotBlank()

    /**
     * 保存（[asDraft]=true 存草稿 / false 发布）。新建插入，编辑覆写（保留 uuid/timestamp/isAutoGenerated）。
     * 发布且 openToAI → 调度评论（编辑模式仅「草稿→发布」触发，对齐 iOS）。
     * 编辑走 `existing.copy`——交换日记的作者 / 快照 / 消化标记等非本页字段一律原样留存。
     */
    fun save(asDraft: Boolean, onDone: () -> Unit) {
        val s = _state.value
        val content = s.content.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            val existing = s.uuid?.let { diaryRepository.getEntry(it) }
            val wasDraft = existing?.isDraft ?: false
            val uuid = existing?.uuid ?: UUID.randomUUID().toString()
            // 编辑 = 在原行上改（copy），只覆盖本页真正编辑的五个字段；uuid / timestamp / isAutoGenerated /
            // isPetDiary / petSpeciesRaw / triggerTypeRaw / relatedGiftId / authorCharacterUuid /
            // authorNameSnapshot / digestedAtMillis 一律原样留存。
            // ⚠️ 绝不改回「重新构造实体逐字段手抄」：那样每新增一列都会在编辑时被静默清空——交换日记的
            // authorCharacterUuid 正是这么丢的（TA 的信编辑后变成用户日记 → 信封位复活 → 角色重复写信）。
            val entry = existing?.copy(
                content = content,
                imagePathsJson = StringListJson.encode(s.images),
                moodEmoji = s.moodEmoji,
                moodText = s.moodText,
                isDraft = asDraft,
                visibilityRaw = s.visibility.raw,
            ) ?: DiaryEntryEntity(
                uuid = uuid,
                content = content,
                timestamp = System.currentTimeMillis(),
                imagePathsJson = StringListJson.encode(s.images),
                moodEmoji = s.moodEmoji,
                moodText = s.moodText,
                isAutoGenerated = false,
                isDraft = asDraft,
                isPetDiary = false,
                petSpeciesRaw = null,
                visibilityRaw = s.visibility.raw,
                triggerTypeRaw = MomentTriggerType.AUTO_DRAFT.raw,
                relatedGiftId = null,
            )
            diaryRepository.upsert(entry)
            cleanupOnSave(finalImages = s.images)

            // 评论触发（1:1 iOS）：新建发布 / 草稿→发布，且 openToAI。
            val publishingNow = !asDraft && s.visibility == DiaryVisibility.OPEN_TO_AI
            val isTransitionToPublish = if (existing != null) wasDraft && publishingNow else publishingNow
            if (isTransitionToPublish) {
                val delay = settingsRepo.getAppSettings().diaryCommentDelay
                commentService.scheduleComments(uuid, delay)
            }
            onDone()
        }
    }

    /** 放弃：删除本次会话新落盘但未保留的孤儿图（编辑原图不动，DB 不变）。 */
    fun discard() {
        ContentImageStore.delete(sessionSavedPaths.toList())
        sessionSavedPaths.clear()
        savedStateHandle[KEY_SESSION_PATHS] = StringListJson.encode(sessionSavedPaths.toList())
    }

    val hasUnsavedChanges: Boolean
        get() {
            val s = _state.value
            return if (s.isEdit) {
                // J1②编辑 dirty 精判：与载入快照五字段任一不等才算改动（没改就不弹「放弃修改？」）。快照未加载完成 /
                // 条目已删（snapshot 仍为 null）→ 退回「content 非空即 dirty」保守兜底，不误吞用户新输入。
                val snap = originalSnapshot ?: return s.content.isNotBlank()
                s.content != snap.content ||
                    s.moodEmoji != snap.moodEmoji ||
                    s.moodText != snap.moodText ||
                    s.images != snap.images ||
                    s.visibility != snap.visibility
            } else {
                s.content.isNotBlank() || s.images.isNotEmpty() || s.moodEmoji != null
            }
        }

    /** 保存时清理：原图中被移除的 + 本会话落盘后又被移除的，统一删盘。 */
    private fun cleanupOnSave(finalImages: List<String>) {
        val finalSet = finalImages.toSet()
        val toDelete = (originalImages + sessionSavedPaths).distinct().filter { it !in finalSet }
        ContentImageStore.delete(toDelete)
        sessionSavedPaths.clear()
        savedStateHandle[KEY_SESSION_PATHS] = StringListJson.encode(sessionSavedPaths.toList())
    }

    /**
     * R4#0 安全网：未保存即离开（系统手势返回 / 进程回收）时清理本会话新落盘的孤儿图（照搬
     * [com.situ.aichat.ui.moments.ComposeMomentViewModel.onCleared]，补齐与朋友圈的对称）。sessionSavedPaths
     * 只含**本会话新增**图，save/discard 都已清空它——故 onCleared 删的恰是「拍/选了图但既没保存也没显式放弃」
     * 的孤儿；编辑模式原图不在其中，绝不误删。
     */
    override fun onCleared() {
        voice.onCleared() // J6：录音器是 @Singleton·VM 销毁必须停在录的录音。
        if (sessionSavedPaths.isNotEmpty()) {
            ContentImageStore.delete(sessionSavedPaths.toList())
            sessionSavedPaths.clear()
        }
    }

    companion object {
        private const val TAG = "ComposeDiaryVM"
        const val ARG_UUID = "uuid"

        // J1①进程死亡镜像键（SavedStateHandle·全为 Bundle 安全 String；images/sessionPaths 走 StringListJson·visibility 存 raw）。
        const val KEY_CONTENT = "compose_content"
        const val KEY_MOOD_EMOJI = "compose_mood_emoji"
        const val KEY_MOOD_TEXT = "compose_mood_text"
        const val KEY_IMAGES = "compose_images"
        const val KEY_VISIBILITY = "compose_visibility"
        const val KEY_SESSION_PATHS = "compose_session_paths"
    }
}
