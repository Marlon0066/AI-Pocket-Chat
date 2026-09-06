package com.situ.aichat.ui.moments

import android.os.Looper
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.R
import com.situ.aichat.pet.EggNestService
import com.situ.aichat.pet.EggNestState
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.ui.world.planet.PlanetMath
import com.situ.aichat.world.WorldBootstrap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [MomentsHubViewModel] W11 扩展 T2（图纸 §7 T2-1..5·MockK 假仓库 + Robolectric）：
 * 静默建世幂等 + worldCard seed/派生 seedOff（E1）· 信息条随 natives/characters Flow 实时变（E9）·
 * 空帖 previewPosts 空（E7）· 未读透传（E8）· seedOff 派生口径（照 WorldViewModel.kt:143-149）。
 * WhileSubscribed 需订阅者才开闸 → CoroutineScope(Main)+idle() 驱动（同 SettingsOverviewViewModelTest 惯例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MomentsHubViewModelTest {

    private companion object {
        const val SEED = 424242L // 家乡 = 云野镇（x=600,y=1300·同 WorldViewModelTest）
        const val HOME_X = 600
        const val HOME_Y = 1300
    }

    private val context = RuntimeEnvironment.getApplication()

    // 活数据源（可驱动·喂 worldCard / state 组合）。
    private val characters = MutableStateFlow<List<CharacterEntity>>(emptyList())
    private val natives = MutableStateFlow<List<WorldNativeStateEntity>>(emptyList())
    private val pets = MutableStateFlow<List<CharacterPetEntity>>(emptyList())
    private val eggState = MutableStateFlow<EggNestState>(EggNestState.Empty) // W12.5：世界卡蛋段源
    private val feed = MutableStateFlow<List<MomentPostWithRelations>>(emptyList())
    private val unread = MutableStateFlow(0)
    private val diaries = MutableStateFlow<List<DiaryEntryWithComments>>(emptyList())
    private val settings = MutableStateFlow(AppSettings())
    private val latestStory = MutableStateFlow<StoryEntity?>(null) // 卷二 §3.2：动态页上游 = 最近一本轻列投影

    private val bootstrap = mockk<WorldBootstrap>()

    private fun newVm(): MomentsHubViewModel {
        val momentRepo = mockk<MomentRepository> {
            every { observeFeed(any()) } returns feed
            every { observeUnreadNotificationCount() } returns unread
        }
        val characterRepo = mockk<CharacterRepository> { every { observeAll() } returns characters }
        val diaryRepo = mockk<DiaryRepository> { every { observeAllWithComments() } returns diaries }
        val settingsRepo = mockk<SettingsRepository> { every { appSettings } returns settings }
        val storyRepo = mockk<StoryRepository> { every { observeLatestStoryLite() } returns latestStory }
        val petRepo = mockk<PetRepository> { every { observeAll() } returns pets }
        val worldNativeDao = mockk<WorldNativeDao> { every { observeAll() } returns natives }
        val eggNestService = mockk<EggNestService> { every { observeState(any()) } returns eggState }
        coEvery { bootstrap.ensureCreated(any()) } returns WorldStateEntity(seed = SEED, createdAt = 100L)
        return MomentsHubViewModel(
            context, momentRepo, characterRepo, diaryRepo, settingsRepo, storyRepo, petRepo, worldNativeDao, eggNestService, bootstrap,
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 订阅 state + worldCard（WhileSubscribed 开闸）并驱动主循环，跑完 block 再退订。 */
    private fun <T> withSubscriptions(vm: MomentsHubViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.state.collect {} }
        scope.launch { vm.worldCard.collect {} }
        idle()
        return try { block() } finally { scope.coroutineContext[Job]?.cancel() }
    }

    /** 轮询等待（bootstrap 在 IO 线程建世 → idle 泵主循环让 worldCard 组合器算出）。 */
    private fun await(message: String, condition: () -> Boolean) {
        repeat(400) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun character(uuid: String, joined: Boolean) =
        CharacterEntity(uuid = uuid, name = uuid, creationDate = 0L, joinedWorld = joined)

    private fun discovered(id: String, recruited: String? = null) =
        WorldNativeStateEntity(nativeId = id, discovered = true, recruitedCharacterUuid = recruited)

    @Test
    fun `E1建世幂等_worldCard填seed与派生seedOff`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("worldCard 就绪") { vm.worldCard.value != null }
            val card = vm.worldCard.value!!
            assertEquals(SEED, card.seed)
            val expectedSeedOff = PlanetMath.deriveSeedOff(SEED, PlanetMath.homeUnitVector(HOME_X, HOME_Y))
            assertEquals(expectedSeedOff, card.seedOff, 0f)
        }
        // 静默建世恰一次（幂等由 bootstrap 内部保证·VM 只调一次）。
        coVerify(exactly = 1) { bootstrap.ensureCreated(any()) }
    }

    @Test
    fun `E3全空_信息条quiet文案`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("worldCard 就绪") { vm.worldCard.value != null }
            assertEquals(context.getString(R.string.world_card_info_quiet), vm.worldCard.value!!.infoLine)
        }
    }

    @Test
    fun `E9_natives与characters重发_infoLine实时变`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("worldCard 就绪") { vm.worldCard.value != null }
            // 2 人加入世界 + 1 位待相识（已发现·未招募）。
            characters.value = listOf(character("a", true), character("b", true), character("c", false))
            natives.value = listOf(discovered("n1"))
            val expected1 = context.getString(R.string.world_starmap_tag_around, 2) +
                " · " + context.getString(R.string.world_starmap_tag_pending, 1)
            await("infoLine 反映 2+1") { vm.worldCard.value?.infoLine == expected1 }

            // 再发现一位（待相识 1→2）→ infoLine 即时更新（活数据）。
            natives.value = listOf(discovered("n1"), discovered("n2"))
            val expected2 = context.getString(R.string.world_starmap_tag_around, 2) +
                " · " + context.getString(R.string.world_starmap_tag_pending, 2)
            await("infoLine 反映 2+2") { vm.worldCard.value?.infoLine == expected2 }

            // 已招募的原住民不计入「待相识」。
            natives.value = listOf(discovered("n1"), discovered("n2", recruited = "u9"))
            await("已招募不计") {
                vm.worldCard.value?.infoLine == context.getString(R.string.world_starmap_tag_around, 2) +
                    " · " + context.getString(R.string.world_starmap_tag_pending, 1)
            }
        }
    }

    @Test
    fun `E7空帖_previewPosts空`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("state 就绪") { vm.state.value.previewPosts.isEmpty() && vm.worldCard.value != null }
            assertTrue(vm.state.value.previewPosts.isEmpty())
            assertTrue(vm.state.value.heroAvatars.isEmpty())
        }
    }

    @Test
    fun `E8未读透传`() {
        val vm = newVm()
        withSubscriptions(vm) {
            unread.value = 5
            await("未读=5") { vm.state.value.unreadCount == 5 }
            assertEquals(5, vm.state.value.unreadCount)
            assertNotNull(vm.worldCard.value)
        }
    }

    // ── W12.5 信息条蛋段 + 单可点段（§4.4/§5·决策 42④）──

    @Test
    fun `蛋可孵化_信息条出蛋段_独立可点直达petDetail`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("worldCard 就绪") { vm.worldCard.value != null }
            eggState.value = EggNestState.Hatchable("egg-uuid", "苏晚")
            val eggText = context.getString(R.string.world_card_egg_hatchable)
            await("蛋段出现") { vm.worldCard.value?.petTapText == eggText }
            val card = vm.worldCard.value!!
            assertEquals("egg-uuid", card.petTapUuid)         // 可点段 → 之约角色 uuid
            assertTrue(card.infoLine.contains(eggText))        // 蛋段进 infoLine 全串（a11y）
        }
    }

    @Test
    fun `E9饿宠优先_蛋段不出_可点段=宠物owner`() {
        val vm = newVm()
        val hungryPet = CharacterPetEntity(name = "团子", characterUuid = "owner-1", hunger = 90)
        withSubscriptions(vm) {
            await("worldCard 就绪") { vm.worldCard.value != null }
            pets.value = listOf(hungryPet)
            eggState.value = EggNestState.Hatchable("egg-uuid", "苏晚")
            await("宠物段可点") { vm.worldCard.value?.petTapUuid == "owner-1" }
            val card = vm.worldCard.value!!
            assertTrue(card.petTapText?.contains("团子") == true)                 // 可点段 = 宠物「需要你」
            assertTrue(!card.infoLine.contains(context.getString(R.string.world_card_egg_hatchable))) // 蛋段不出（E9）
        }
    }

    @Test
    fun `E10无宠无蛋_无可点段`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("worldCard 就绪") { vm.worldCard.value != null }
            idle() // 默认 pets 空 + eggState Empty
            val card = vm.worldCard.value!!
            assertTrue(card.petTapText == null && card.petTapUuid == null)
        }
    }

    // ── 查询瘦身卷二 T2-3（图纸 §5 E5）：故事位上游 = observeLatestStoryLite ──

    /** E5：一本书都没有 → 上游发 null → latestStory 为 null（故事卡走既有「还没有书」分支）。 */
    @Test
    fun `E5一本书都没有_latestStory为null`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("state 就绪") { vm.state.value.unreadCount == 0 }
            idle()
            assertNull(vm.state.value.latestStory)
        }
    }

    /** 有书 → 上游那一本原样进 state（轻列投影的保留列足够故事卡显示）。 */
    @Test
    fun `有书_latestStory就是上游那一本`() {
        val vm = newVm()
        val book = StoryEntity(id = "s-1", title = "最近这本", status = StoryStatus.SERIALIZING, cachedLatestChapterNumber = 7)
        withSubscriptions(vm) {
            latestStory.value = book
            await("故事位回流") { vm.state.value.latestStory != null }
            assertEquals("s-1", vm.state.value.latestStory?.id)
            assertEquals("最近这本", vm.state.value.latestStory?.title)
            assertEquals(7, vm.state.value.latestStory?.cachedLatestChapterNumber)
        }
    }

    // ── 宠物条状态（图纸 2026-09-06-宠物总览页复活 V4） ──

    /**
     * 4 只宠物全量进 state —— 本卷缺口的核心断言：家内站位 `MAX_PETS = 3` 只摆前 3 只，
     * 动态页宠物条与总览页必须**不设上限**，否则第 4 只起仍旧够不着（图纸 §0.2）。
     */
    @Test
    fun `四只宠物_petCount为4_精灵排按adoptedDate升序`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("state 就绪") { vm.state.value.unreadCount == 0 }
            // 倒序喂入，验证排序由派生函数负责
            pets.value = (4 downTo 1).map {
                CharacterPetEntity(name = "宠$it", characterUuid = "c$it", adoptedDate = it * 1000L)
            }
            await("宠物位回流") { vm.state.value.petCount == 4 }
            assertEquals(4, vm.state.value.petCount)
            assertEquals(listOf("宠1", "宠2", "宠3", "宠4"), vm.state.value.petSprites.map { it.name })
            assertTrue("默认构造 happiness=80 → 全 HAPPY", vm.state.value.petAllWell)
        }
    }

    /** 六只 → 总数照报 6，精灵排截 5（头行报门后有几只，排面只放得下 5 个）。 */
    @Test
    fun `六只宠物_总数6_精灵排截5`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("state 就绪") { vm.state.value.unreadCount == 0 }
            pets.value = (1..6).map {
                CharacterPetEntity(name = "宠$it", characterUuid = "c$it", adoptedDate = it * 1000L)
            }
            await("宠物位回流") { vm.state.value.petCount == 6 }
            assertEquals(6, vm.state.value.petCount)
            assertEquals(5, vm.state.value.petSprites.size)
        }
    }

    /** 有饿宠 → petAllWell 为假、petGlance 指向那一只（尾行报它）。 */
    @Test
    fun `有饿宠_petAllWell为假_petGlance指向它`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("state 就绪") { vm.state.value.unreadCount == 0 }
            pets.value = listOf(
                CharacterPetEntity(name = "开开", characterUuid = "c1"),
                CharacterPetEntity(name = "饿饿", characterUuid = "c2", hunger = 90),
            )
            await("宠物位回流") { vm.state.value.petCount == 2 }
            assertEquals(false, vm.state.value.petAllWell)
            assertEquals("饿饿", vm.state.value.petGlance?.name)
        }
    }

    /** 无宠物 → 空态三件：count 0 / 精灵排空 / allWell 假（空态不是「都好着呢」）。 */
    @Test
    fun `无宠物_空态三件`() {
        val vm = newVm()
        withSubscriptions(vm) {
            await("state 就绪") { vm.state.value.unreadCount == 0 }
            idle()
            assertEquals(0, vm.state.value.petCount)
            assertTrue(vm.state.value.petSprites.isEmpty())
            assertEquals(false, vm.state.value.petAllWell)
            assertNull(vm.state.value.petGlance)
        }
    }
}
