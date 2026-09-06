package com.situ.aichat.ui.moments

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.moments.MomentApiMissingFlag
import com.situ.aichat.pet.EggNestService
import com.situ.aichat.pet.EggNestState
import com.situ.aichat.ui.world.planet.PlanetMath
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.atlas.WorldAtlas
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 圈子枢纽派生状态（对齐 iOS `MomentsView` 的 hero + 网格卡片所需数据）。 */
data class MomentsHubState(
    val unreadCount: Int = 0,
    /** Hero 头像墙：从最近动态提取的不重复角色作者，≤5（W11 R1 🔵-1·demo 五头像=规格）。 */
    val heroAvatars: List<CharacterEntity> = emptyList(),
    /** Hero 底部预览：最近 2 条动态（作者名 + 正文由 UI 层格式化，含本地化「我」/「AI」）。 */
    val previewPosts: List<MomentPostEntity> = emptyList(),
    val charactersByUuid: Map<String, CharacterEntity> = emptyMap(),
    /** 日记网格卡预览：最新一篇非草稿日记（对齐 iOS `recentDiaryEntriesDescriptor` 的 `!isDraft`）。 */
    val latestDiary: DiaryEntryEntity? = null,
    /** 日记未读角标计数（diary-1）：评论 timestamp > lastViewedDiaryDate 的条数（对齐 iOS diaryUnreadCount）。 */
    val diaryUnreadCount: Int = 0,
    /** 故事网格卡：最新一本故事（updatedAt DESC 取首）。UI 经 [storyHubStatus] 派生「第N话 · 标题」副标题。null=无故事。 */
    val latestStory: StoryEntity? = null,
    /** 宠物横条卡：最需照顾的一只（[pickNeediestPet]）。UI 经 [com.situ.aichat.ui.pet.PetMoodType] 派生状态文案。null=无宠物。 */
    val petGlance: CharacterPetEntity? = null,
    /** 宠物条总数（图纸 D-2：头行报总数·0=空态）。**不受家内站位 MAX_PETS=3 限**——这是全量。 */
    val petCount: Int = 0,
    /** 宠物条精灵排：adoptedDate 升序前 [PET_STRIP_SPRITE_MAX] 只。 */
    val petSprites: List<CharacterPetEntity> = emptyList(),
    /** 有宠物且无一「需要你」→ 尾行显「都好着呢」。 */
    val petAllWell: Boolean = false,
)

/**
 * 动态页世界卡渲染态（W11 图纸 §3）：星球种子/派生 seedOff（喂 PlanetCardTextureView）+ 信息条活文案串。
 * `worldCard == null` = 世界尚未建成（卡显渐变+星点+quiet 文案·星球位空着·就绪后 300ms 淡入·§4.2）。
 */
data class WorldCardUi(
    val seed: Long,
    val seedOff: Float,
    val infoLine: String,
    // W12.5（§4.4）：单可点宠物段（needs-attention 宠物 或「蛋要孵出来了」·二选一）——文本 + 目标 uuid（→ petDetail）；
    // 均 null = 无可点段（quiet 或纯人数段）。infoLine 仍为全段拼接单串（整卡 a11y 保持全文）。
    val petTapText: String? = null,
    val petTapUuid: String? = null,
)

/**
 * 圈子枢纽（M06 7.2.7，对齐 iOS `MomentsView`）的 ViewModel。响应式合成 hero 卡（未读数 + 角色头像墙 + 最新
 * 2 帖预览）与日记 / 故事 / 宠物三张**实时状态卡**所需数据（契约 FABLE5_MOMENTS_HUB_REDESIGN_PROPOSAL.md §3）。
 */
@HiltViewModel
class MomentsHubViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    momentRepo: MomentRepository,
    characterRepo: CharacterRepository,
    diaryRepository: DiaryRepository,
    settingsRepository: SettingsRepository,
    storyRepo: StoryRepository,
    petRepo: PetRepository,
    worldNativeDao: WorldNativeDao,
    eggNestService: EggNestService, // W12.5：世界卡信息条「蛋要孵出来了」段源（决策 42④）
    private val bootstrap: WorldBootstrap,
) : ViewModel() {

    val state: StateFlow<MomentsHubState> = run {
        val base = combine(
            momentRepo.observeFeed(HERO_LIMIT),
            characterRepo.observeAll(),
            momentRepo.observeUnreadNotificationCount(),
            diaryRepository.observeAllWithComments(),
            settingsRepository.appSettings,
        ) { posts, characters, unread, diaries, settings ->
            val byUuid = characters.associateBy { it.uuid }
            // diary-1：未读 = 评论 timestamp 严格大于 lastViewedDiaryDate 的条数（对齐 iOS DiaryComment 计数，
            // 0L 默认=从未看过→全部计未读）。observeAllWithComments 已带全部评论，无需新查询。
            val lastViewed = settings.lastViewedDiaryDate
            val diaryUnread = diaries.sumOf { d -> d.comments.count { it.timestamp > lastViewed } }
            val heroAvatars = buildList {
                val seen = HashSet<String>()
                for (p in posts) {
                    if (MomentAuthorType.fromRaw(p.post.authorTypeRaw) != MomentAuthorType.CHARACTER) continue
                    val uuid = p.post.characterUuid ?: continue
                    if (!seen.add(uuid)) continue
                    val character = byUuid[uuid] ?: continue
                    add(character)
                    if (size >= HERO_AVATAR_MAX) break
                }
            }
            MomentsHubState(
                unreadCount = unread,
                heroAvatars = heroAvatars,
                previewPosts = posts.take(HERO_PREVIEW_MAX).map { it.post },
                charactersByUuid = byUuid,
                latestDiary = diaries.firstOrNull { !it.entry.isDraft }?.entry,
                diaryUnreadCount = diaryUnread,
            )
        }
        // Kotlin combine 典型重载至多 5 路（base 已占满）→ 故事 / 宠物单独合一组，再与 base 合并补全（契约 §5）。
        val storyPet = combine(
            // 图纸卷二 §3.2：上游换「最近一本的轻列投影 + LIMIT 1」（原为全表宽行取 firstOrNull）。
            storyRepo.observeLatestStoryLite(),
            petRepo.observeAll(),
        ) { story, pets -> story to petStripGlance(pets) }
        combine(base, storyPet) { hub, sp ->
            val g = sp.second
            hub.copy(
                latestStory = sp.first,
                petGlance = g.neediest,
                petCount = g.count,
                petSprites = g.sprites,
                petAllWell = g.allWell,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MomentsHubState())
    }

    /** 星球身份（seed/seedOff）：bootstrap 建成后一次性填（null = 建成前）。 */
    private val _worldIdentity = MutableStateFlow<WorldIdentity?>(null)

    /**
     * 动态页世界卡（§3）：星球身份 × 活信息条源（characters/natives/pet Flow）实时重算。任一源变即更新（E9）；
     * 身份未就绪 → null（卡显渐变 + quiet 文案·星球位空·就绪后淡入）。
     */
    val worldCard: StateFlow<WorldCardUi?> = combine(
        _worldIdentity,
        characterRepo.observeAll(),
        worldNativeDao.observeAll(),
        petRepo.observeAll(),
        eggNestService.observeState(),
    ) { identity, characters, natives, pets, eggState ->
        if (identity == null) return@combine null
        val joined = characters.count { it.joinedWorld }
        val pending = natives.count { it.discovered && it.recruitedCharacterUuid == null }
        val neediest = pickNeediestPet(pets)
        val segments = WorldCardInfo.buildSegments(joined, pending, neediest, eggHatchable = eggState is EggNestState.Hatchable)
        // 单可点段（§4.4）：PetNeeds 优先 → 宠 uuid；否则 EggHatchable → 之约角色 uuid；否则无可点段。
        val tapSeg = segments.firstOrNull { it is InfoSegment.PetNeeds || it is InfoSegment.EggHatchable }
        val petTapUuid = when (tapSeg) {
            is InfoSegment.PetNeeds -> neediest?.characterUuid
            InfoSegment.EggHatchable -> (eggState as? EggNestState.Hatchable)?.characterUuid
            else -> null
        }
        WorldCardUi(
            identity.seed, identity.seedOff, renderInfoLine(segments),
            petTapText = tapSeg?.let { renderSegment(it) }, petTapUuid = petTapUuid,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // 静默建世（决策 41·§3）：幂等、纯本地、与世界屏首启同一口（已有世界绝不重建）；建成后派生 seed/seedOff
        // （口径照 WorldViewModel.kt:143-149·家乡城 → 单位球向量 → PlanetMath.deriveSeedOff）。
        viewModelScope.launch(Dispatchers.IO) {
            val worldState = bootstrap.ensureCreated(System.currentTimeMillis())
            val atlas = WorldAtlas.of(worldState.seed)
            val home = atlas.cityById(worldState.userHomeCityId)
            val seedOff = PlanetMath.deriveSeedOff(
                worldState.seed,
                PlanetMath.homeUnitVector(home?.x ?: 0, home?.y ?: 0),
            )
            _worldIdentity.value = WorldIdentity(worldState.seed, seedOff)
        }
    }

    /** 信息条段列表 → 展示串（段间「 · 」·全空 → quiet·§3/§4.4）。 */
    private fun renderInfoLine(segments: List<InfoSegment>): String {
        if (segments.isEmpty()) return context.getString(R.string.world_card_info_quiet)
        return segments.joinToString(SEGMENT_SEPARATOR) { renderSegment(it) }
    }

    /** 单段 → 展示文本（petTapText 与 infoLine 共用此单源·保二者字节一致·§4.4）。 */
    private fun renderSegment(seg: InfoSegment): String = when (seg) {
        is InfoSegment.Around -> context.getString(R.string.world_starmap_tag_around, seg.count)
        is InfoSegment.Pending -> context.getString(R.string.world_starmap_tag_pending, seg.count)
        is InfoSegment.PetNeeds -> seg.name + context.getString(petNeedRes(seg.kind))
        InfoSegment.EggHatchable -> context.getString(R.string.world_card_egg_hatchable)
    }

    /** 「需要你」态 → pet_hub_* 资源（与 PetStatusLine 同源·§3）。 */
    private fun petNeedRes(kind: PetNeedKind): Int = when (kind) {
        PetNeedKind.RAN_AWAY -> R.string.pet_hub_runaway
        PetNeedKind.SICK -> R.string.pet_hub_sick
        PetNeedKind.HUNGRY -> R.string.pet_hub_hungry
        PetNeedKind.SAD -> R.string.pet_hub_sad
    }

    private data class WorldIdentity(val seed: Long, val seedOff: Float)

    private val _apiMissing = MutableStateFlow(MomentApiMissingFlag.get(context))
    val apiMissing: StateFlow<Boolean> = _apiMissing.asStateFlow()

    fun refreshApiMissing() {
        _apiMissing.value = MomentApiMissingFlag.get(context)
    }

    private companion object {
        const val HERO_LIMIT = 20
        const val HERO_AVATAR_MAX = 5
        const val HERO_PREVIEW_MAX = 2
        const val SEGMENT_SEPARATOR = " · "
    }
}
