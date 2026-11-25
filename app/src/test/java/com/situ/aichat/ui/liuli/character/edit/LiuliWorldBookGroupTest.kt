package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.local.entity.WorldBookEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T2-9：世界观组无状态内容——原住民锁 / 已入世可点开选书并弹离世切换确认 / 单选 / 多选 / 停用书 / 全局书提示。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliWorldBookGroupTest {

    @get:Rule val compose = createComposeRule()

    private var opens = 0
    private var sheet by mutableStateOf(false)
    private val soles = mutableListOf<String?>()
    private val toggles = mutableListOf<Pair<String, Boolean>>()
    private val switches = mutableListOf<Pair<String, Boolean>>()

    private fun book(uuid: String, name: String, entries: Int = 3, enabled: Boolean = true, global: Boolean = false) =
        WorldBookSummary(book = WorldBookEntity(uuid = uuid, name = name, enabled = enabled, isGlobal = global), entryCount = entries, boundCount = 0)

    private val qing = book("q", "青云录", 4)
    private val chang = book("c", "长安", 2, enabled = false)

    private fun show(
        bound: List<WorldBookSummary> = emptyList(),
        joined: Boolean = false,
        native: Boolean = false,
        global: List<WorldBookEntity> = emptyList(),
    ) {
        compose.setContent {
            LiuliEditTestHost {
                Column {
                    LiuliWorldBookGroupContent(
                        boundBooks = bound, joinedWorld = joined, nativeOrigin = native,
                        selectableBooks = listOf(qing, chang), globalBooks = global,
                        sheetVisible = sheet, onOpen = { opens++; sheet = true }, onDismissSheet = { sheet = false },
                        onSelectSole = { soles += it }, onToggle = { u, b -> toggles += u to b },
                        onSwitchLeaveAndBind = { u, sole -> switches += u to sole },
                        onManageBooks = {},
                    )
                }
            }
        }
    }

    @Test fun 原住民卡锁死不可点() {
        show(native = true, joined = true)
        compose.onNodeWithText("原住民角色 · 不可绑定").assertHasNoClickAction()
    }

    @Test fun 已加入世界_可点开选书_点书弹出离世确认_确认回调切换() {
        show(joined = true)
        compose.onNodeWithText("当前在世界里 · 点一下选书并离开世界").performClick()
        assertEquals(1, opens)
        compose.onNodeWithText("选一本世界观").assertExists()
        compose.onNodeWithText("青云录").performClick()
        // 未确认前不直接落库
        assertEquals(emptyList<Pair<String, Boolean>>(), switches)
        compose.onNodeWithText("换用世界观并离开世界？").assertExists()
        compose.onNodeWithText("离开世界并换用").performClick()
        assertEquals(listOf("q" to true), switches)
        compose.onNodeWithText("选一本世界观").assertDoesNotExist()
    }

    @Test fun 未绑单本多本() {
        show()
        compose.onNodeWithText("未设定 · 点一下选一本世界观").assertExists()
    }

    @Test fun 单本显书名与条数_多本显等N本() {
        show(bound = listOf(qing))
        compose.onNodeWithText("青云录").assertExists()
        compose.onNodeWithText("4 条设定").assertExists()
    }

    @Test fun 多本显等N本() {
        show(bound = listOf(qing, chang))
        compose.onNodeWithText("青云录 等 1 本").assertExists()
    }

    @Test fun 单选点书回调并关弹层() {
        show()
        compose.onNodeWithText("未设定 · 点一下选一本世界观").performClick()
        assertEquals(1, opens)
        compose.onNodeWithText("选一本世界观").assertExists()
        compose.onNodeWithText("青云录").performClick()
        assertEquals(listOf<String?>("q"), soles)
        compose.onNodeWithText("选一本世界观").assertDoesNotExist()
    }

    @Test fun 单选点已选的不使用也回调并关弹层_同暖陶() {
        show()
        compose.onNodeWithText("未设定 · 点一下选一本世界观").performClick()
        compose.onNodeWithText("不使用").performClick()
        assertEquals(listOf<String?>(null), soles)
        compose.onNodeWithText("选一本世界观").assertDoesNotExist()
    }

    @Test fun 多选切换回调toggle且停用书仍可点() {
        show(bound = listOf(qing, chang))
        compose.onNodeWithText("青云录 等 1 本").performClick()
        compose.onNodeWithText("不使用").assertDoesNotExist()
        compose.onNode(isToggleable() and hasText("长安")).performClick()
        compose.onNode(isToggleable() and hasText("青云录")).performClick()
        assertEquals(listOf("c" to false, "q" to false), toggles)
        compose.onNodeWithText("2 条设定 · 已停用").assertExists()
    }

    @Test fun 叠加入口进多选() {
        show(bound = listOf(qing))
        compose.onNodeWithText("青云录").performClick()
        compose.onNodeWithText("叠加多本（进阶）").performClick()
        compose.onNode(isToggleable() and hasText("长安")).performClick()
        assertEquals(listOf("c" to true), toggles)
    }

    @Test fun 全局书提示只在有启用全局书时出现() {
        show(global = listOf(WorldBookEntity(uuid = "g", name = "通用常识", isGlobal = true)))
        compose.onNodeWithText("未设定 · 点一下选一本世界观").performClick()
        compose.onNodeWithText("「通用常识」 已对所有角色生效，无需选择。").assertExists()
    }

    @Test fun 无全局书不出提示() {
        show()
        compose.onNodeWithText("未设定 · 点一下选一本世界观").performClick()
        compose.onNodeWithText("已对所有角色生效", substring = true).assertDoesNotExist()
    }
}
