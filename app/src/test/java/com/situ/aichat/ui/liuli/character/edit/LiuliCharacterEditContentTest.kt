package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.ui.character.CharacterEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-1（琉璃 2.0 卷五 §7）：页壳 + 无状态主体。a = 标题两态 / 保存可用性与回调 / 取消回调。
 * 标题与保存可用规则都在页壳层里算（`isEditing` → 标题；`canSave && !saving` → 保存），故直接喂 VM 同形的量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliCharacterEditContentTest {

    @get:Rule val compose = createComposeRule()

    private var saves = 0
    private var cancels = 0

    private fun show(state: CharacterEditState, isEditing: Boolean = true, saving: Boolean = false) {
        compose.setContent {
            LiuliEditTestHost {
                LiuliCharacterEditPage(
                    isEditing = isEditing,
                    canSave = state.canSave,
                    saving = saving,
                    onCancel = { cancels++ },
                    onSave = { saves++ },
                    focusVoiceSection = false,
                ) { onY -> EditContent(state, isEditing, onY) }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.EditContent(
        state: CharacterEditState,
        isEditing: Boolean,
        onY: (Int) -> Unit,
    ) {
        LiuliCharacterEditContent(
            state = state, isEditing = isEditing, compiling = false, personaNeedsSave = false,
            systemVoices = emptyList(), previewBusy = false, previewError = null, hasModuleOverride = false,
            onUpdate = {}, onPickAvatar = {}, onPickWallpaper = {}, onRemoveWallpaper = {}, onOpenBirthday = {},
            onCompilePersona = {}, onLoadSystemVoices = {}, onPreviewVoice = {}, onSetModuleOverride = {},
            onEditModules = {}, onOpenMeetings = {}, onVoiceSectionY = onY,
            worldGroup = { Text(FAKE_WORLD) },
            worldBookGroup = if (isEditing) { { Text(FAKE_WORLD_BOOK) } } else null,
        )
    }

    @Test fun 编辑态标题是角色信息() {
        show(CharacterEditState(name = "小满"), isEditing = true)
        compose.onNodeWithText("角色信息").assertExists()
        compose.onNodeWithText("新建角色").assertDoesNotExist()
    }

    @Test fun 新建态标题是新建角色() {
        show(CharacterEditState(), isEditing = false)
        compose.onNodeWithText("新建角色").assertExists()
        compose.onNodeWithText("角色信息").assertDoesNotExist()
    }

    @Test fun 名字空时保存不可点() {
        show(CharacterEditState(name = "  "))
        compose.onNodeWithText("保存").assertIsNotEnabled().performClick()
        assertEquals(0, saves)
    }

    @Test fun 保存中不可点() {
        show(CharacterEditState(name = "小满"), saving = true)
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }

    @Test fun 名字非空可保存且回调一次() {
        show(CharacterEditState(name = "小满"))
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        assertEquals(1, saves)
    }

    @Test fun 取消回调一次() {
        show(CharacterEditState(name = "小满"))
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, cancels)
        assertEquals(0, saves)
    }

    // ---- b（c4）：十七项顺序 + 新建态缺四项 ----

    private fun topOf(text: String): Float =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().minOf { it.positionInRoot.y }

    private val editOrder = listOf(
        "聊天壁纸", "基础信息", "关系", "角色设定", "交流风格", "性格光谱", "她吃哪套", "她的固定反应",
        "关系质感", "线下主题色", "高级", "语音", "提示词模块", FAKE_WORLD, FAKE_WORLD_BOOK, "见面回忆",
    )

    @Test fun 编辑态十七项按暖陶顺序() {
        show(
            CharacterEditState(
                name = "小满",
                personaOperators = listOf(PersonaOperator(id = "o1", condition = "c01", action = "a01")),
            ),
            isEditing = true,
        )
        // 第 1 项头像块（名字首字）排在第一个组标题之前；生成行只在编辑态。
        assertTrue(topOf("小") < topOf("聊天壁纸"))
        compose.onNodeWithText("从人设生成").assertExists()
        val tops = editOrder.map { it to topOf(it) }
        tops.zipWithNext().forEach { (a, b) -> assertTrue("${a.first}(${a.second}) 应在 ${b.first}(${b.second}) 之上", a.second < b.second) }
    }

    @Test fun 新建态含世界与世界观但缺三项() {
        compose.setContent {
            LiuliEditTestHost {
                LiuliCharacterEditPage(isEditing = false, canSave = false, saving = false, onCancel = {}, onSave = {}, focusVoiceSection = false) { onY ->
                    LiuliCharacterEditContent(
                        state = CharacterEditState(), isEditing = false, compiling = false, personaNeedsSave = false,
                        systemVoices = emptyList(), previewBusy = false, previewError = null, hasModuleOverride = false,
                        onUpdate = {}, onPickAvatar = {}, onPickWallpaper = {}, onRemoveWallpaper = {}, onOpenBirthday = {},
                        onCompilePersona = {}, onLoadSystemVoices = {}, onPreviewVoice = {}, onSetModuleOverride = {},
                        onEditModules = {}, onOpenMeetings = {}, onVoiceSectionY = onY,
                        worldGroup = { LiuliCharacterWorldCreateGroup(joined = false, onToggle = {}) },
                        worldBookGroup = { Text(FAKE_WORLD_BOOK) },
                    )
                }
            }
        }
        compose.onNodeWithText("从人设生成").assertDoesNotExist()
        compose.onNodeWithText("提示词模块").assertDoesNotExist()
        compose.onNodeWithText(FAKE_WORLD_BOOK).assertExists()
        compose.onNodeWithText("见面回忆").assertDoesNotExist()
        compose.onNodeWithText("保存后 TA 就会住进云野镇。与「世界书」二选一。").assertExists()
        // 关系脚注走 create 句。
        compose.onNodeWithText("关系一旦设定将作为永久记录保存", substring = true).assertExists()
        compose.onNodeWithText("修改关系将新增一条记录", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val FAKE_WORLD = "假世界组"
        const val FAKE_WORLD_BOOK = "假世界观组"
    }
}
