package com.situ.aichat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationDeletionService
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.quickreply.ListQuickReplyService
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 聊天列表页（13.5 chat-ui-11，对齐 iOS `ChatListView`）。
 *
 * - 活跃会话 = 未归档 **且有过消息**（1:1 iOS predicate `lastMessageDate != nil`，过滤从未发过消息的占位/
 *   通知预留会话）；DAO 已按「置顶优先 → 最近活动」排序，UI 再拆 Pinned/普通两段。
 * - 与角色表 join 取头像/角色名；搜索仅按角色名、300ms 防抖（1:1 iOS .searchable + debounce）。
 * - 日程状态行：仅 scheduleSystemEnabled 时，取各角色当天进行中事件的「活动 心情emoji」，每 60s 随节拍刷新。
 */
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val conversationRepo: ConversationRepository,
    characterRepo: CharacterRepository,
    private val deletionService: ConversationDeletionService,
    private val messageRepo: MessageRepository,
    private val quickReplyService: ListQuickReplyService,
    private val scheduleDao: ScheduleDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 一行 = 会话 + 其角色（可能为空，理论上 FK 保证非空，防御回退到 title）。 */
    data class Row(
        val conversation: ConversationEntity,
        val character: CharacterEntity?,
    ) {
        val displayName: String
            get() = character?.name?.takeIf { it.isNotBlank() } ?: conversation.title
    }

    private val charactersByUuid: StateFlow<Map<String, CharacterEntity>> =
        characterRepo.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 活跃行（已过滤无消息会话），未经搜索过滤；置顶优先序由 DAO 保证。 */
    private val activeRows: StateFlow<List<Row>> =
        combine(conversationRepo.observeActive(), charactersByUuid) { convs, byUuid ->
            convs.filter { it.lastMessageDate != null }
                .map { Row(it, byUuid[it.characterUuid]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 防抖后的搜索词（空词立即生效、非空词等 300ms；1:1 iOS debouncedSearchText）。 */
    @OptIn(FlowPreview::class)
    val searchTerm: StateFlow<String> =
        _query
            .debounce { if (it.isEmpty()) 0L else 300L }
            .map { it.trim() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 可见行（按角色名过滤；空词=全部）。Pinned/普通 分段由屏幕侧按 isPinned 拆。 */
    val visibleRows: StateFlow<List<Row>> =
        combine(activeRows, searchTerm) { rows, term ->
            if (term.isEmpty()) rows
            else rows.filter { it.displayName.contains(term, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 选择器（聊天「+」发起聊天）用：全部角色按「最近聊天活跃度」排序（D4）——有活跃会话者按最近消息时间倒序在前，
     * 无会话者保持 [charactersByUuid]（= observeAll 的 newest-first）次序排后。本应用每角色仅一会话，故 associate 去重无碍。
     */
    val pickerCharacters: StateFlow<List<CharacterEntity>> =
        combine(charactersByUuid, conversationRepo.observeActive()) { byUuid, convs ->
            val lastByCharacter = convs
                .filter { it.lastMessageDate != null }
                .associate { it.characterUuid to it.lastMessageDate!! }
            orderCharactersForPicker(byUuid.values.toList()) { lastByCharacter[it.uuid] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 归档会话数（>0 才显示归档入口；COUNT 直查不搬全量实体，K5）。 */
    val archivedCount: StateFlow<Int> =
        conversationRepo.observeArchivedCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val scheduleEnabled: StateFlow<Boolean> =
        settingsRepository.appSettings
            .map { it.scheduleSystemEnabled }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 每 60s 节拍（驱动日程状态按事件边界刷新，= iOS TimeTick）。 */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    /** characterUuid → 当前日程状态串（仅进行中事件）。enabled=false 或无进行中事件 → 不含该角色。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val scheduleStatus: StateFlow<Map<String, String>> =
        combine(
            // 卷一 F5：见面中的会话排除在外 → 该行状态串缺席 = 状态行隐藏（不泄地点、不显过时线上日程）。
            activeRows.map { rows ->
                rows.filterNot { OfflineMeetingGate.inMeeting(it.conversation) }
                    .map { it.conversation.characterUuid }
                    .distinct()
            }.distinctUntilChanged(),
            scheduleEnabled,
            ticker,
        ) { uuids, enabled, _ -> uuids to enabled }
            .mapLatest { (uuids, enabled) ->
                if (!enabled || uuids.isEmpty()) {
                    emptyMap()
                } else {
                    val now = System.currentTimeMillis()
                    val today = DateFormatters.startOfDayMillis(now)
                    // 各角色并发查（IO），避免 N 个串行 DB 往返在大列表时周期性拖慢（对抗复核 M2）。
                    coroutineScope {
                        uuids.map { uuid ->
                            async {
                                val schedule = scheduleDao.scheduleFor(uuid, today) ?: return@async null
                                val events = scheduleDao.eventsForSchedule(schedule.uuid)
                                val status = ChatListScheduleStatus.currentStatus(events, now) ?: return@async null
                                uuid to status
                            }
                        }.awaitAll().filterNotNull().toMap()
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setPinned(conversationUuid: String, pinned: Boolean) {
        viewModelScope.launch { conversationRepo.setPinned(conversationUuid, pinned) }
    }

    fun archive(conversationUuid: String) {
        viewModelScope.launch { conversationRepo.setArchived(conversationUuid, true) }
    }

    /** 删会话：走 app 级 [ConversationDeletionService]（先清磁盘媒体再删库行），不随本页离屏中断。 */
    fun delete(conversationUuid: String) {
        deletionService.delete(conversationUuid)
    }

    /** B5 快捷回复面板：取该会话最近 [limit] 条可见消息做上下文预览（一次性，不常驻订阅）。 */
    suspend fun recentMessages(conversationUuid: String, limit: Int = 3): List<MessageEntity> =
        messageRepo.recentVisibleChronological(conversationUuid, limit)

    /** B5 列表内联快捷回复：不进会话回一句，后台跑一轮 LLM 回复（走 app 级 [ListQuickReplyService]，跨屏存活）。 */
    fun quickReply(conversationUuid: String, text: String) {
        quickReplyService.send(conversationUuid, text)
    }

    /**
     * 选一个角色 → 解析（取或建）其唯一会话 → 回调进会话。与 [com.situ.aichat.ui.contacts.ContactsViewModel] 的
     * openChat 同源（逻辑事实源 = [ConversationRepository.getOrCreateForCharacter] 幂等）。
     */
    fun startConversationWith(character: CharacterEntity, onReady: (conversationUuid: String) -> Unit) {
        viewModelScope.launch {
            onReady(conversationRepo.getOrCreateForCharacter(character.uuid, character.name.trim()))
        }
    }
}

/**
 * 选择器角色排序（纯函数·可单测·见 ChatPickerOrderTest）：有活跃会话的角色排前、按最近消息时间倒序；
 * 无活跃会话的排后、保持输入次序（= observeAll 的 newest-first）。`sortedWith` 稳定 → 同组次序不抖。
 */
internal fun <T> orderCharactersForPicker(
    characters: List<T>,
    lastMessageMillis: (T) -> Long?,
): List<T> =
    characters.sortedWith(
        compareByDescending<T> { lastMessageMillis(it) != null }
            .thenByDescending { lastMessageMillis(it) ?: Long.MIN_VALUE },
    )
