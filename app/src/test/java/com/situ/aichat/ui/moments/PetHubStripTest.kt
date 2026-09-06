package com.situ.aichat.ui.moments

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2（图纸 2026-09-06-宠物总览页复活 V5）：动态页「宠物」条的**真组合** ——
 * 头行总数 / 精灵排 / 尾行三态文案，以及点整条触发 `onOpenPetHub`。
 *
 * 这是 `momentsPet` 死路由复活链条的**组件级**证据：条能渲染、能点、点了回调真的响。
 * 「条已挂进动态页 + 回调真的 navigate 到 momentsPet」的**端到端**证据走 T4 装机
 * （图纸 §11 D-1 记了为何做不成整屏组合测：`MomentsHubScreen` 内嵌的 `OurDaysStrip`
 * 自取 `hiltViewModel()`，本库无 Hilt 单测基建）。
 *
 * 屏尺寸钉真机档防「元素落可视区外」的假绿（记忆 `reference-robolectric-screen-size-fake-green`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class PetHubStripTest {

    @get:Rule
    val compose = createComposeRule()

    /** 用真 [petStripGlance] 把宠物列表折成 UI 态——**不手搓** state，免得测试与生产口径分叉。 */
    private fun show(pets: List<CharacterPetEntity>): MutableList<Unit> {
        val opened = mutableListOf<Unit>()
        val g = petStripGlance(pets)
        val state = MomentsHubState(
            petGlance = g.neediest,
            petCount = g.count,
            petSprites = g.sprites,
            petAllWell = g.allWell,
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                PetHubStrip(state = state, onClick = { opened += Unit })
            }
        }
        compose.waitForIdle()
        return opened
    }

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `无宠物 头行还没有 尾行邀约句`() {
        show(emptyList())
        awaitText("还没有")
        compose.onNodeWithText("宠物").assertIsDisplayed()
        compose.onNodeWithText("还没有").assertIsDisplayed()
        compose.onNodeWithText("一起养一只宠物吧").assertIsDisplayed()
    }

    @Test
    fun `有饿宠 头行报总数 尾行报那一只`() {
        show(
            listOf(
                CharacterPetEntity(name = "团子", characterUuid = "c1", hunger = 90),
                CharacterPetEntity(name = "雪球", characterUuid = "c2"),
            ),
        )
        awaitText("2 只小家伙")
        compose.onNodeWithText("2 只小家伙").assertIsDisplayed()   // D-2：头行报总数
        compose.onNodeWithText("团子 有点饿了").assertIsDisplayed()  // D-2：尾行报状况
    }

    @Test
    fun `全部健康 尾行都好着呢`() {
        // 默认构造 = 饱食 0 / 净 100 / 心情 80 / 健康 100 → PetMoodType.HAPPY（happiness>=80）
        show(listOf(CharacterPetEntity(name = "阿墨", characterUuid = "c1")))
        awaitText("都好着呢")
        compose.onNodeWithText("1 只小家伙").assertIsDisplayed()
        compose.onNodeWithText("都好着呢").assertIsDisplayed()
    }

    @Test
    fun `第四只也计入总数 不受家内 MAX_PETS 3 限`() {
        // 图纸 §0.2 的缺口正在此：家内站位截 3，本条与总览页必须报全量。
        show((1..4).map { CharacterPetEntity(name = "宠物$it", characterUuid = "c$it") })
        awaitText("4 只小家伙")
        compose.onNodeWithText("4 只小家伙").assertIsDisplayed()
    }

    @Test
    fun `点宠物条 onOpenPetHub 被调用一次`() {
        val opened = show(emptyList())
        awaitText("还没有")
        compose.onNodeWithText("宠物").performClick()
        compose.waitForIdle()
        assertEquals(1, opened.size)
    }
}
