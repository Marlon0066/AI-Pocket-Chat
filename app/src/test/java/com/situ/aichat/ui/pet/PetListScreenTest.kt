package com.situ.aichat.ui.pet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2（图纸 2026-09-06-宠物总览页复活 V6）：`PetListScreen` 三态**真组合**——
 * 已领养卡出「名字 / 和{角色}一起养 / 状态行」、可领养卡出「可以领养了」、未解锁卡出「陪伴 N%」，
 * 点卡片回调带对应 characterUuid。
 *
 * 为什么需要它：本屏在本卷之前是**死路由**（全库零 `navigate("momentsPet")`），从未有过任何自动化覆盖；
 * 且模拟器是占位 key，攒不出「陪伴≥14 天 + 信任 40 + 熟悉 35 + 亲密 30 + 消息≥100」的已领养宠物
 * （记忆 `reference-emulator-placeholder-key`），装机档够不着已领养 / 未解锁两态 → 由本条补偿。
 *
 * 屏尺寸钉真机档防「元素落可视区外」的假绿（记忆 `reference-robolectric-screen-size-fake-green`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class PetListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val characterRepo = mockk<CharacterRepository>()
    private val petRepo = mockk<PetRepository>()
    private val messageDao = mockk<MessageDao>()
    private val vms = mutableListOf<PetListViewModel>()

    @After
    fun tearDown() = vms.forEach { it.viewModelScope.cancel() }

    /** 满足全部领养门槛的角色（陪伴 100 天 + 信任 40 / 熟悉 35 / 亲密 30）。 */
    private fun readyCharacter(uuid: String, name: String) = CharacterEntity(
        uuid = uuid,
        name = name,
        creationDate = System.currentTimeMillis() - 100L * 86_400_000L,
        relationshipQualityJSON = GrowthJson.encode(
            RelationshipQuality(familiarity = 35, trust = 40, closeness = 30),
        ),
    )

    /** 门槛远未达成的角色（今天刚建 + 低关系值）。 */
    private fun freshCharacter(uuid: String, name: String) = CharacterEntity(
        uuid = uuid,
        name = name,
        creationDate = System.currentTimeMillis(),
        relationshipQualityJSON = GrowthJson.encode(
            RelationshipQuality(familiarity = 7, trust = 8, closeness = 3),
        ),
    )

    private fun show(
        characters: List<CharacterEntity>,
        pets: List<CharacterPetEntity>,
        messageCount: Int = 0,
    ): MutableList<String> {
        val opened = mutableListOf<String>()
        every { characterRepo.observeAll() } returns flowOf(characters)
        every { petRepo.observeAll() } returns flowOf(pets)
        coEvery { messageDao.countAllForCharacter(any()) } returns messageCount
        val vm = PetListViewModel(characterRepo, petRepo, messageDao)
        vms += vm
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                PetListScreen(onOpenPet = { opened += it }, onBack = {}, viewModel = vm)
            }
        }
        compose.waitForIdle()
        return opened
    }

    @Test
    fun `无角色 出空态文案`() {
        show(characters = emptyList(), pets = emptyList())
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("先创建角色，培养关系后就能一起领养宠物啦").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("先创建角色，培养关系后就能一起领养宠物啦").assertIsDisplayed()
    }

    @Test
    fun `已领养卡 出名字与归属与状态行 点卡回调带 uuid`() {
        val opened = show(
            characters = listOf(readyCharacter("c1", "林砚")),
            // hunger 90 → PetMoodType.HUNGRY → 状态行「有点饿了」
            pets = listOf(CharacterPetEntity(name = "团子", characterUuid = "c1", hunger = 90)),
            messageCount = 500,
        )
        compose.waitUntil(5_000) { compose.onAllNodesWithText("团子").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("团子").assertIsDisplayed()
        compose.onNodeWithText("和林砚一起养").assertIsDisplayed()
        compose.onNodeWithText("有点饿了").assertIsDisplayed()          // 纯文字 + 圆点，无 emoji（§2.2）
        compose.onNodeWithText("团子").performClick()
        assertEquals(listOf("c1"), opened)
    }

    @Test
    fun `门槛达成无宠物 出可以领养了`() {
        show(
            characters = listOf(readyCharacter("c2", "苏晚")),
            pets = emptyList(),
            messageCount = 500,
        )
        compose.waitUntil(5_000) { compose.onAllNodesWithText("可以领养了").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("可以领养了").assertIsDisplayed()
        compose.onNodeWithText("苏晚").assertIsDisplayed()
    }

    @Test
    fun `门槛未达成 出陪伴百分比而非领养`() {
        show(
            characters = listOf(freshCharacter("c3", "江辞")),
            pets = emptyList(),
            messageCount = 0,
        )
        compose.waitUntil(5_000) { compose.onAllNodesWithText("江辞").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("江辞").assertIsDisplayed()
        compose.onNodeWithText("可以领养了").assertDoesNotExist()
        // 断言从 AdoptionProgress 规格**独立反推**（5 项等权平均·PetAdoption.kt:46-48）：
        //   陪伴 1/14=.0714 + 信任 8/40=.2 + 熟悉 7/35=.2 + 亲密 3/30=.1 + 消息 0/100=0 = .5714
        //   .5714 / 5 = .1143 → ×100 取整 = 11
        compose.onNodeWithText("陪伴 11%").assertIsDisplayed()
    }

    @Test
    fun `第四只宠物同样上屏 本屏无数量上限`() {
        // 图纸 §0.2 的缺口正是「第 4 只起够不着」——家内站位 MAX_PETS=3，本屏必须不设上限。
        val chars = (1..4).map { readyCharacter("c$it", "角色$it") }
        val pets = (1..4).map { CharacterPetEntity(name = "宠物$it", characterUuid = "c$it") }
        val opened = show(characters = chars, pets = pets, messageCount = 500)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("宠物4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("宠物4").assertIsDisplayed()
        compose.onNodeWithText("宠物4").performClick()
        assertEquals(listOf("c4"), opened)
    }
}
