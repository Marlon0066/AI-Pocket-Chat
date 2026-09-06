package com.situ.aichat.ui.diary

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.diary.DiaryCommentService
import com.situ.aichat.prompt.diary.DiaryGenerationCoordinator
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.StringListJson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [ComposeDiaryViewModel] J1 逻辑双修 + J5 素材芯片 T2（图纸 §4-J1/§4-J5）：
 *  ① 进程死亡 SavedStateHandle 镜像恢复（恢复优先于编辑 DB 加载）；② 编辑 dirty 五字段精判；③ setter 落值同步写 handle；
 *  ④ 素材芯片三查询（聊天/见面/礼物·有据/无据·真 Robolectric context 取真文案）+ 起笔句置入。
 * MockK 假 repo/dao·Robolectric 驱动 init 的 viewModelScope + 提供真 Context；素材查询逻辑经 internal computeMaterialChips 直测（避 IO 竞态）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeDiaryViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private val appContext = RuntimeEnvironment.getApplication()

    private val diaryRepo: DiaryRepository = mockk(relaxed = true)
    private val settingsRepo: SettingsRepository = mockk(relaxed = true)
    private val commentService: DiaryCommentService = mockk(relaxed = true)
    private val generationCoordinator: DiaryGenerationCoordinator = mockk(relaxed = true)
    private val conversationRepo: ConversationRepository = mockk(relaxed = true)
    private val meetingRepo: OfflineMeetingMemoryRepository = mockk(relaxed = true)
    private val giftDao: GiftDao = mockk(relaxed = true)
    private val characterRepo: CharacterRepository = mockk(relaxed = true)
    private val voiceRecorder: com.situ.aichat.stt.VoiceMessageRecorder = mockk(relaxed = true)
    private val sttEngine: com.situ.aichat.stt.SttEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        // 素材芯片三查询默认「无据」，各测按需覆盖（init 的 IO 装配也吃这些默认·不崩不挂）。
        every { conversationRepo.observeActive() } returns flowOf(emptyList())
        coEvery { meetingRepo.meetingsOnDay(any(), any()) } returns emptyList()
        coEvery { giftDao.userReceivedGiftBetween(any(), any()) } returns null
        coEvery { characterRepo.get(any()) } returns null
    }

    @After
    fun tearDown() = unmockkObject(ContentImageStore)

    private fun buildVm(handle: SavedStateHandle): ComposeDiaryViewModel = ComposeDiaryViewModel(
        context = appContext,
        savedStateHandle = handle,
        diaryRepository = diaryRepo,
        settingsRepo = settingsRepo,
        commentService = commentService,
        generationCoordinator = generationCoordinator,
        conversationRepository = conversationRepo,
        offlineMeetingMemoryRepository = meetingRepo,
        giftDao = giftDao,
        characterRepository = characterRepo,
        voiceRecorder = voiceRecorder,
        sttEngine = sttEngine,
    )

    private fun entry(
        uuid: String = "e1",
        content: String = "DB 原文",
        moodEmoji: String? = "😊",
        moodText: String? = "开心",
        images: List<String> = listOf("/img1.jpg"),
        visibility: DiaryVisibility = DiaryVisibility.OPEN_TO_AI,
        authorCharacterUuid: String? = null,
        authorNameSnapshot: String? = null,
        digestedAtMillis: Long? = null,
    ) = DiaryEntryEntity(
        uuid = uuid,
        content = content,
        timestamp = 123_456L,
        imagePathsJson = StringListJson.encode(images),
        moodEmoji = moodEmoji,
        moodText = moodText,
        visibilityRaw = visibility.raw,
        authorCharacterUuid = authorCharacterUuid,
        authorNameSnapshot = authorNameSnapshot,
        digestedAtMillis = digestedAtMillis,
    )

    private fun character(name: String): CharacterEntity = mockk(relaxed = true) { every { this@mockk.name } returns name }

    // ---- ① 进程恢复镜像 ----

    @Test
    fun `handle 预填内容_init 回灌 state`() {
        val handle = SavedStateHandle(
            mapOf(
                ComposeDiaryViewModel.KEY_CONTENT to "草稿正文",
                ComposeDiaryViewModel.KEY_MOOD_EMOJI to "🥰",
                ComposeDiaryViewModel.KEY_MOOD_TEXT to "幸福",
                ComposeDiaryViewModel.KEY_IMAGES to StringListJson.encode(listOf("/a.jpg", "/b.jpg")),
                ComposeDiaryViewModel.KEY_VISIBILITY to DiaryVisibility.PRIVATE.raw,
            ),
        )
        val vm = buildVm(handle)
        val s = vm.state.value
        assertEquals("草稿正文", s.content)
        assertEquals("🥰", s.moodEmoji)
        assertEquals("幸福", s.moodText)
        assertEquals(listOf("/a.jpg", "/b.jpg"), s.images)
        assertEquals(DiaryVisibility.PRIVATE, s.visibility)
    }

    @Test
    fun `handle 空_editUuid 非空_走 DB 加载`() {
        coEvery { diaryRepo.getEntry("e1") } returns entry()
        val handle = SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "e1"))
        val vm = buildVm(handle)
        idle()
        val s = vm.state.value
        assertTrue(s.isEdit)
        assertEquals("DB 原文", s.content)
        assertEquals("😊", s.moodEmoji)
        assertEquals(listOf("/img1.jpg"), s.images)
    }

    @Test
    fun `新建仅加图未打字_同 handle 重建_图回灌且孤儿可清`() {
        // 🟡-1：先加图、一个字没打 → 进程死亡 → 同一 handle 重建。全组镜像使 KEY_CONTENT（空串但非 null）在场，
        // init 恢复触发 → 图回灌 + sessionSavedPaths 复原 → discard 仍能删到该孤儿路径（孤儿清理链跨进程不脱钩）。
        mockkObject(ContentImageStore)
        coEvery { ContentImageStore.saveAll(any(), any()) } returns listOf("/orphan.jpg")
        every { ContentImageStore.delete(any<List<String>>()) } returns Unit
        val handle = SavedStateHandle()
        val vm1 = buildVm(handle)
        vm1.addImages(listOf(mockk<Uri>(relaxed = true)))
        idle()
        val vm2 = buildVm(handle)
        idle()
        assertEquals(listOf("/orphan.jpg"), vm2.state.value.images)
        assertEquals("", vm2.state.value.content)
        vm2.discard()
        verify { ContentImageStore.delete(match<List<String>> { it.contains("/orphan.jpg") }) }
    }

    @Test
    fun `新建仅选心情未打字_同 handle 重建_心情回灌且正文空`() {
        // 🟡-1：先选心情、没打字 → 同一 handle 重建 → 心情回灌且正文仍为空串（不被误置）。
        val handle = SavedStateHandle()
        val vm1 = buildVm(handle)
        vm1.toggleMood("😊", "开心")
        idle()
        val vm2 = buildVm(handle)
        idle()
        assertEquals("😊", vm2.state.value.moodEmoji)
        assertEquals("", vm2.state.value.content)
    }

    @Test
    fun `进程恢复优先于 DB 加载_state 保持镜像值`() {
        coEvery { diaryRepo.getEntry("e1") } returns entry(content = "DB 原文")
        val handle = SavedStateHandle(
            mapOf(
                ComposeDiaryViewModel.ARG_UUID to "e1",
                ComposeDiaryViewModel.KEY_CONTENT to "改过的草稿",
            ),
        )
        val vm = buildVm(handle)
        idle()
        assertEquals("改过的草稿", vm.state.value.content) // 未被 DB 原文覆盖
        assertTrue(vm.hasUnsavedChanges) // 快照=DB 原文 vs 当前=改过的草稿 → dirty
    }

    @Test
    fun `编辑恢复_timestamp 从 DB 补_日期头不变今天`() {
        // 🟡-3：编辑旧日记(timestamp=123_456L)→进程死亡→恢复。镜像不带 timestamp，须由 DB 补回原条目日期，
        // 否则日期头/邮票显示今天。content 仍以镜像为准(不被 DB 覆盖)。
        coEvery { diaryRepo.getEntry("e1") } returns entry(content = "DB 原文")
        val handle = SavedStateHandle(
            mapOf(
                ComposeDiaryViewModel.ARG_UUID to "e1",
                ComposeDiaryViewModel.KEY_CONTENT to "改过的草稿",
            ),
        )
        val vm = buildVm(handle)
        idle()
        assertEquals("改过的草稿", vm.state.value.content)
        assertEquals(123_456L, vm.state.value.timestamp)
    }

    // ---- ② 编辑 dirty 精判 ----

    @Test
    fun `编辑未改_hasUnsavedChanges 为 false`() {
        coEvery { diaryRepo.getEntry("e1") } returns entry()
        val vm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "e1")))
        idle()
        assertFalse(vm.hasUnsavedChanges)
    }

    @Test
    fun `编辑改 content_hasUnsavedChanges 为 true`() {
        coEvery { diaryRepo.getEntry("e1") } returns entry()
        val vm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "e1")))
        idle()
        vm.setContent("DB 原文，加了一句")
        assertTrue(vm.hasUnsavedChanges)
    }

    @Test
    fun `编辑改 mood_hasUnsavedChanges 为 true`() {
        coEvery { diaryRepo.getEntry("e1") } returns entry(moodEmoji = "😊", moodText = "开心")
        val vm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "e1")))
        idle()
        vm.toggleMood("😢", "难过")
        assertTrue(vm.hasUnsavedChanges)
    }

    @Test
    fun `编辑删图_hasUnsavedChanges 为 true`() {
        coEvery { diaryRepo.getEntry("e1") } returns entry(images = listOf("/img1.jpg"))
        val vm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "e1")))
        idle()
        vm.removeImage("/img1.jpg")
        assertTrue(vm.hasUnsavedChanges)
    }

    // ---- ③ setter 写 handle ----

    @Test
    fun `setContent_setVisibility_toggleMood 同步写 handle`() {
        val handle = SavedStateHandle()
        val vm = buildVm(handle)
        vm.setContent("你好")
        assertEquals("你好", handle.get<String>(ComposeDiaryViewModel.KEY_CONTENT))
        vm.setVisibility(DiaryVisibility.PRIVATE)
        assertEquals(DiaryVisibility.PRIVATE.raw, handle.get<String>(ComposeDiaryViewModel.KEY_VISIBILITY))
        vm.toggleMood("🎉", "兴奋")
        assertEquals("🎉", handle.get<String>(ComposeDiaryViewModel.KEY_MOOD_EMOJI))
        assertEquals("兴奋", handle.get<String>(ComposeDiaryViewModel.KEY_MOOD_TEXT))
    }

    @Test
    fun `addImages 同步写 KEY_IMAGES 与 KEY_SESSION_PATHS`() {
        mockkObject(ContentImageStore)
        coEvery { ContentImageStore.saveAll(any(), any()) } returns listOf("/saved.jpg")
        val handle = SavedStateHandle()
        val vm = buildVm(handle)
        vm.addImages(listOf(mockk<Uri>(relaxed = true)))
        idle()
        assertEquals(listOf("/saved.jpg"), StringListJson.decode(handle.get<String>(ComposeDiaryViewModel.KEY_IMAGES).orEmpty()))
        assertEquals(listOf("/saved.jpg"), StringListJson.decode(handle.get<String>(ComposeDiaryViewModel.KEY_SESSION_PATHS).orEmpty()))
    }

    // ---- ④ J5 素材芯片三查询（有据/无据）+ 置入 ----

    @Test
    fun `素材_聊天有据_产出 chat 芯片`() {
        val conv = ConversationEntity(uuid = "c1", title = "", characterUuid = "ch1", creationDate = 0L, lastMessageDate = System.currentTimeMillis())
        every { conversationRepo.observeActive() } returns flowOf(listOf(conv))
        coEvery { characterRepo.get("ch1") } returns character("夏晴子")
        val vm = buildVm(SavedStateHandle())
        val chips = runBlocking { vm.computeMaterialChips() }
        val chat = chips.firstOrNull { it.kind == MaterialKind.CHAT }
        assertEquals(appContext.getString(R.string.diary_chip_chat, "夏晴子"), chat?.label)
        assertEquals(appContext.getString(R.string.diary_chip_starter_chat, "夏晴子"), chat?.starter)
    }

    @Test
    fun `素材_聊天无据_无 chat 芯片`() {
        val stale = ConversationEntity(uuid = "c1", title = "", characterUuid = "ch1", creationDate = 0L, lastMessageDate = 0L)
        every { conversationRepo.observeActive() } returns flowOf(listOf(stale))
        coEvery { characterRepo.get("ch1") } returns character("夏晴子")
        val vm = buildVm(SavedStateHandle())
        val chips = runBlocking { vm.computeMaterialChips() }
        assertTrue(chips.none { it.kind == MaterialKind.CHAT })
    }

    @Test
    fun `素材_见面有据_产出 meeting 芯片带地点`() {
        val meeting = mockk<OfflineMeetingMemoryEntity>(relaxed = true) {
            every { startedAtMillis } returns 1L
            every { location } returns "公园"
            every { characterUuid } returns "ch1"
        }
        coEvery { meetingRepo.meetingsOnDay(any(), any()) } returns listOf(meeting)
        coEvery { characterRepo.get("ch1") } returns character("林澈")
        val vm = buildVm(SavedStateHandle())
        val chips = runBlocking { vm.computeMaterialChips() }
        val m = chips.firstOrNull { it.kind == MaterialKind.MEETING }
        assertEquals(appContext.getString(R.string.diary_chip_meeting, "公园"), m?.label)
    }

    @Test
    fun `素材_见面无据_无 meeting 芯片`() {
        coEvery { meetingRepo.meetingsOnDay(any(), any()) } returns emptyList()
        val vm = buildVm(SavedStateHandle())
        val chips = runBlocking { vm.computeMaterialChips() }
        assertTrue(chips.none { it.kind == MaterialKind.MEETING })
    }

    @Test
    fun `素材_礼物有据_产出 gift 芯片`() {
        coEvery { giftDao.userReceivedGiftBetween(any(), any()) } returns mockk<GiftRecordEntity>(relaxed = true)
        val vm = buildVm(SavedStateHandle())
        val chips = runBlocking { vm.computeMaterialChips() }
        val g = chips.firstOrNull { it.kind == MaterialKind.GIFT }
        assertEquals(appContext.getString(R.string.diary_chip_gift), g?.label)
    }

    @Test
    fun `素材_礼物无据_无 gift 芯片`() {
        coEvery { giftDao.userReceivedGiftBetween(any(), any()) } returns null
        val vm = buildVm(SavedStateHandle())
        val chips = runBlocking { vm.computeMaterialChips() }
        assertTrue(chips.none { it.kind == MaterialKind.GIFT })
    }

    @Test
    fun `素材_点击芯片_起笔句置入正文`() {
        val vm = buildVm(SavedStateHandle())
        val starter = appContext.getString(R.string.diary_chip_starter_chat, "夏晴子")
        vm.setContent(starter + "\n") // UI 侧句尾带换行
        assertEquals(starter + "\n", vm.state.value.content)
        assertNull(vm.state.value.moodEmoji) // 仅置正文·不动其它字段
    }

    // ---- ⑤ save() 落库字段（图纸 2026-09-05 日记编辑丢作者根治·§7 T2-1/T2-2/T2-3）----

    @Test
    fun `编辑交换日记保存_作者与消化标记原样留存`() {
        // E1/E14/E15：编辑「TA 的信」保存后，作者 uuid / 作者名快照 / 消化标记 / 触发类型 / 时间戳 / uuid 必须
        // 与原行逐字段相同——它们一旦被清空，信就「改姓」成用户日记：信封位复活 → 角色重复写信、署名丢失、
        // 用户留言再也等不到 TA 回应。
        val letter = entry(
            content = "TA 写的信",
            authorCharacterUuid = "c1",
            authorNameSnapshot = "小柚",
            digestedAtMillis = 999L,
        ).copy(triggerTypeRaw = "exchange")
        coEvery { diaryRepo.getEntry("e1") } returns letter
        val vm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "e1")))
        idle()
        vm.setContent("我改过的信")
        vm.save(asDraft = false) {}
        idle()
        coVerify {
            diaryRepo.upsert(
                match {
                    it.uuid == "e1" &&
                        it.content == "我改过的信" &&
                        it.authorCharacterUuid == "c1" &&
                        it.authorNameSnapshot == "小柚" &&
                        it.digestedAtMillis == 999L &&
                        it.triggerTypeRaw == "exchange" &&
                        it.timestamp == 123_456L
                },
            )
        }
    }

    @Test
    fun `编辑自动日记保存_礼物位与自动位原样留存`() {
        // E2/B2：编辑自己的一篇「收到礼物顺势而发」的自动日记 → isAutoGenerated / triggerTypeRaw / relatedGiftId
        // 三个非本页字段照旧保留（改动前已保留，改动后一个不许变）。
        val auto = entry(content = "礼物日记").copy(
            isAutoGenerated = true,
            triggerTypeRaw = "gift_received",
            relatedGiftId = "g1",
        )
        coEvery { diaryRepo.getEntry("e1") } returns auto
        val vm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "e1")))
        idle()
        vm.setContent("改过的礼物日记")
        vm.save(asDraft = false) {}
        idle()
        coVerify {
            diaryRepo.upsert(
                match {
                    it.content == "改过的礼物日记" &&
                        it.isAutoGenerated &&
                        it.triggerTypeRaw == "gift_received" &&
                        it.relatedGiftId == "g1" &&
                        !it.isPetDiary
                },
            )
        }
    }

    @Test
    fun `新建日记保存_默认位与旧行为逐字段一致`() {
        // E3/E18/B1：新建路径（s.uuid == null）落库实体的默认位与改动前逐字段相同——不许被 copy 改造顺手带偏。
        val vm = buildVm(SavedStateHandle())
        vm.setContent("我的新日记")
        vm.save(asDraft = false) {}
        idle()
        coVerify {
            diaryRepo.upsert(
                match {
                    it.content == "我的新日记" &&
                        !it.isAutoGenerated &&
                        !it.isPetDiary &&
                        it.petSpeciesRaw == null &&
                        it.triggerTypeRaw == "auto_draft" &&
                        it.relatedGiftId == null &&
                        it.authorCharacterUuid == null &&
                        it.authorNameSnapshot == null &&
                        it.digestedAtMillis == null
                },
            )
        }
    }

    // ---- ⑥ isExchangeLetter（图纸 2026-09-05 日记编辑丢作者根治·§7 T2-6）----

    @Test
    fun `编辑交换日记_isExchangeLetter 为真_含进程恢复分支`() {
        // E8/E9/E10：「TA 的信」= 作者非空 → 隐藏「AI 帮我写」（它生成的是用户视角日记，一点即整封替换）。
        // 该派生量每次 VM 构造都从 DB 现推，故进程死亡恢复分支也必须补上——否则恢复回来的编辑页 AI 入口复现。
        coEvery { diaryRepo.getEntry("letter") } returns entry(uuid = "letter", authorCharacterUuid = "c1")
        coEvery { diaryRepo.getEntry("mine") } returns entry(uuid = "mine")

        val letterVm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "letter")))
        idle()
        assertTrue(letterVm.state.value.isExchangeLetter)

        val mineVm = buildVm(SavedStateHandle(mapOf(ComposeDiaryViewModel.ARG_UUID to "mine")))
        idle()
        assertFalse(mineVm.state.value.isExchangeLetter)

        // 进程死亡恢复：六键镜像预填 + editUuid 指向那封信 → 编辑内容按镜像恢复，身份仍从 DB 推回 true。
        val restored = buildVm(
            SavedStateHandle(
                mapOf(
                    ComposeDiaryViewModel.ARG_UUID to "letter",
                    ComposeDiaryViewModel.KEY_CONTENT to "改到一半的信",
                ),
            ),
        )
        idle()
        assertTrue(restored.state.value.isExchangeLetter)
        assertEquals("改到一半的信", restored.state.value.content)
    }
}
