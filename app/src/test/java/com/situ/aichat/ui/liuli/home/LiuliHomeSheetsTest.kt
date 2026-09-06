package com.situ.aichat.ui.liuli.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.home.sheets.LiuliContactActionSheet
import com.situ.aichat.ui.liuli.home.sheets.LiuliNewConversationPickerSheet
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：主页三弹层的琉璃换壳（图纸 2026-09-06 卷四 §8 C5 · A-13 · §5 E21）。
 *
 * 钉：选择器空角色走空态兜底引导（E21）、有角色时「新建角色」行 + 每角色行各回各的；
 * 联系人动作面板三行各恰一次且每次点按打一记 `light()` 触觉（逐字同暖陶）。
 *
 * 快速回复面板（E22 空输入禁用 / 发送后关闭）走真 `ModalBottomSheet` + `suspend loadRecent`，
 * Robolectric 下内容跨真线程到达、`waitForIdle` 吃不住（PITFALLS §1e），改挂装机批（§11 D-18）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliHomeSheetsTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val taps = mutableMapOf<String, Int>()
    private var picked: String? = null

    private fun character(uuid: String, name: String, personality: String = "") = CharacterEntity(
        uuid = uuid,
        name = name,
        creationDate = 0L,
        personalityDescription = personality,
    )

    private fun showPicker(characters: List<CharacterEntity>) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides haptics) {
                    LiuliNewConversationPickerSheet(
                        characters = characters,
                        onPick = { picked = it.uuid },
                        onCreateNew = { taps["createNew"] = (taps["createNew"] ?: 0) + 1 },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 选择器空角色时走空态兜底引导() {
        showPicker(emptyList())
        compose.onNodeWithText("还没有角色").assertExists()
        // 空态里那枚「新建角色」主钮。
        compose.onNodeWithText("新建角色").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["createNew"])
    }

    @Test fun 选择器有角色时新建行与角色行各回各的() {
        showPicker(listOf(character("a", "小满", "爱下雨天"), character("b", "林晚")))
        compose.onNodeWithText("爱下雨天").assertExists()
        compose.onNodeWithText("新建角色").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["createNew"])
        compose.onNodeWithText("林晚").performClick()
        compose.waitForIdle()
        assertEquals("b", picked)
    }

    @Test fun 联系人动作面板三行各恰一次且每次打light触觉() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides haptics) {
                    LiuliContactActionSheet(
                        character = character("a", "小满"),
                        onDismiss = {},
                        onViewProfile = { taps["profile"] = (taps["profile"] ?: 0) + 1 },
                        onEdit = { taps["edit"] = (taps["edit"] ?: 0) + 1 },
                        onDelete = { taps["delete"] = (taps["delete"] ?: 0) + 1 },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("查看资料").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["profile"])
        assertEquals(1, taps["edit"])
        assertEquals(1, taps["delete"])
        verify(exactly = 3) { haptics.light() }
    }
}
