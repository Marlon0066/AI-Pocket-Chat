package com.situ.aichat.ui.liuli.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.contacts.ContactsViewModel
import com.situ.aichat.ui.contacts.RecentEvent
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-6：琉璃联系人的长相与语义（图纸 2026-09-06 卷三 §7 T2-6 · §4.3 B / §4.4 · E6 / E7）。
 *
 * 无 VM——直接驱动 [LiuliContactsContent]。重点钉三件容易在换脸时掉的东西：**a11y 那一句合并 cd 与三个
 * customActions**（暖陶 F5 逐字）、**头像点 = 资料页且不顺带进会话**、副行三级降级与火苗的显隐门。
 *
 * 火苗用真的 `StreakManager`（`streakCount` + 今天的 `lastChatDate`），不是自己造一个数——换脸不许换算法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliContactsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    private var opened: String? = null
    private var profiled: String? = null
    private var edited: String? = null
    private var deleted: String? = null
    private var created = 0
    private var cancelledShare = 0
    private var queries = mutableListOf<String>()

    private fun character(
        uuid: String,
        name: String,
        occupation: String = "",
        streak: Int = 0,
    ) = CharacterEntity(
        uuid = uuid,
        name = name,
        creationDate = now,
        occupation = occupation,
        streakCount = streak,
        lastChatDate = if (streak > 0) now else null,
    )

    private fun row(
        uuid: String = "a",
        name: String = "小满",
        relationship: String? = null,
        occupation: String = "",
        streak: Int = 0,
        recentEvent: RecentEvent? = null,
    ) = ContactsViewModel.Row(
        character = character(uuid, name, occupation, streak),
        relationshipDisplay = relationship,
        recentEvent = recentEvent,
    )

    private fun show(
        rows: List<ContactsViewModel.Row>,
        query: String = "",
        shareMode: Boolean = false,
        fallbackUuids: Set<String> = emptySet(),
    ) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliContactsContent(
                        rows = rows,
                        query = query,
                        shareMode = shareMode,
                        fallbackUuids = fallbackUuids,
                        nowMillis = now,
                        onQueryChange = { queries += it },
                        onCancelShare = { cancelledShare++ },
                        onCreateCharacter = { created++ },
                        onOpenRow = { opened = it.character.uuid },
                        onOpenProfile = { profiled = it.character.uuid },
                        onEdit = { edited = it.character.uuid },
                        onRequestDelete = { deleted = it.character.uuid },
                        onLongPress = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 无里程碑显初识有里程碑显称谓() {
        show(listOf(row(uuid = "a", name = "小满"), row(uuid = "b", name = "阿泠", relationship = "恋人")))
        compose.onNodeWithText("初识").assertIsDisplayed()
        compose.onNodeWithText("恋人").assertIsDisplayed()
    }

    @Test fun 副行三级降级各走各的() {
        show(
            listOf(
                row(uuid = "a", name = "甲", recentEvent = RecentEvent.Milestone("恋人", now - 86_400_000L), occupation = "画师"),
                row(uuid = "b", name = "乙", occupation = "画师"),
                row(uuid = "c", name = "丙"),
            ),
        )
        // 甲：有纪事 → 纪事压过职业；乙：无纪事有职业 → 职业；丙：都没有 → 神秘占位。
        compose.onNodeWithText("成为恋人", substring = true).assertIsDisplayed()
        compose.onNodeWithText("画师").assertIsDisplayed()
        compose.onNodeWithText("TA的职业很神秘").assertIsDisplayed()
    }

    @Test fun 火苗只在连续天数大于零时出现() {
        show(listOf(row(uuid = "a", name = "小满", streak = 7), row(uuid = "b", name = "阿泠", streak = 0)))
        compose.onNodeWithText("7").assertIsDisplayed()
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test fun 合并成一句cd且带三个自定义动作() {
        show(listOf(row(uuid = "a", name = "小满", relationship = "恋人", occupation = "画师", streak = 3)))
        val node = compose.onNodeWithContentDescription("小满，恋人，画师，连续 3 天").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("查看资料", "编辑", "删除"), actions.map { it.label })
        actions[0].action()
        actions[1].action()
        actions[2].action()
        assertEquals("a", profiled)
        assertEquals("a", edited)
        assertEquals("a", deleted)
        assertEquals("三个自定义动作都不该顺带进会话", null, opened)
    }

    @Test fun 待重生成红点只在兜底集里出现() {
        show(listOf(row(uuid = "a", name = "小满")), fallbackUuids = setOf("a"))
        compose.onNodeWithContentDescription("小满，有见面摘要待重新生成").assertIsDisplayed()
    }

    @Test fun 点头像去资料页而不是进会话() {
        show(listOf(row(uuid = "a", name = "小满")))
        // 头像块对读屏是 clearAndSetSemantics{}（隐身·动作走行级 customActions），只能按坐标点：
        // 行内左起 gutter 20 + 头像 54 的中心 = 47dp。
        val bounds = compose.onNodeWithContentDescription("小满").getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("小满").performTouchInput {
            click(Offset(47.dp.toPx(), (bounds.bottom - bounds.top).toPx() / 2f))
        }
        compose.waitForIdle()
        assertEquals("a", profiled)
        assertEquals("点头像绝不顺带进会话", null, opened)
    }

    @Test fun 点行进会话() {
        show(listOf(row(uuid = "a", name = "小满")))
        compose.onNodeWithContentDescription("小满").performClick()
        compose.waitForIdle()
        assertEquals("a", opened)
        assertEquals("点行不该同时开资料页", null, profiled)
    }

    @Test fun 分享态显分享条且可取消() {
        show(listOf(row()), shareMode = true)
        compose.onNodeWithText("选择要把分享内容发给的角色").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertEquals(1, cancelledShare)
    }

    @Test fun 空搜索结果给清除钮而全空态给新建钮() {
        show(emptyList(), query = "查无此人")
        compose.onNodeWithText("未找到匹配的角色").assertIsDisplayed()
        compose.onNodeWithText("清除搜索").performClick()
        compose.waitForIdle()
        assertEquals(listOf(""), queries)
    }

    @Test fun 全空态三件齐全且CTA回调恰一次() {
        show(emptyList())
        compose.onNodeWithText("还没有联系人").assertIsDisplayed()
        // 文案 2026-09-06 用户拍板改「右上角」（两张脸的「+」都在右上·旧字是 FAB 时代遗留）。
        compose.onNodeWithText("点右上角 + 创建你的第一个 AI 角色").assertIsDisplayed()
        compose.onNodeWithText("新建角色").performClick()
        compose.waitForIdle()
        assertEquals(1, created)
    }
}
