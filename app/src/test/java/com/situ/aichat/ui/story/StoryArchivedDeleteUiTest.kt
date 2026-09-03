package com.situ.aichat.ui.story

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 归档长按删除交互 T2（2026-08-04 卷·Robolectric compose）：共用菜单/弹窗回调接线 +
 * 书架档案分区长按→菜单→删除请求全链，及「点击开档案不被长按挤掉」回归钉。
 * 断言经资源解析（locale 无关，照 EmbedderStatusRowTest 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryArchivedDeleteUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    /**
     * M3 清零卷一起，删除确认弹窗换成 [com.situ.aichat.ui.designsystem.AppDialog]，其幽灵取消钮吃
     * `LocalAppHaptics`（无默认值，不注入即抛）——这里补供假触觉，产品码不动。
     */
    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { block() }
        }
    }

    private fun archivedStory(id: String = "s1", title: String = "山中书") =
        StoryEntity(id = id, title = title, status = StoryStatus.COMPLETED)

    // ---- 共用菜单 ----

    @Test
    fun 菜单_展开显示删除行_点击发删除回调() {
        var deleted = false
        var dismissed = false
        compose.setContent {
            StoryArchivedCardMenu(expanded = true, onDismiss = { dismissed = true }, onDelete = { deleted = true })
        }
        compose.onNodeWithText(app.getString(R.string.story_menu_delete)).assertIsDisplayed().performClick()
        assertTrue(deleted)
        assertEquals(false, dismissed)
    }

    // ---- 共用确认弹窗 ----

    @Test
    fun 弹窗_正文点名书名_确认回调() {
        var confirmed = false
        content {
            StoryArchivedDeleteDialog(story = archivedStory(title = "山中书"), onConfirm = { confirmed = true }, onDismiss = {})
        }
        compose.onNodeWithText(app.getString(R.string.story_hub_delete_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.story_archived_delete_body, "山中书")).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.action_delete)).performClick()
        assertTrue(confirmed)
    }

    @Test
    fun 弹窗_取消回调_不触发确认() {
        var confirmed = false
        var dismissed = false
        content {
            StoryArchivedDeleteDialog(story = archivedStory(), onConfirm = { confirmed = true }, onDismiss = { dismissed = true })
        }
        compose.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        assertTrue(dismissed)
        assertEquals(false, confirmed)
    }

    // ---- 书架档案分区接线 ----

    @Test
    fun 档案分区_长按卡发长按回调_不触发点击() {
        var longPressedId: String? = null
        var openedId: String? = null
        compose.setContent {
            StoryArchiveSection(
                archived = listOf(archivedStory()),
                onOpen = { openedId = it },
                onViewAll = {},
                menuStoryId = null,
                onCardLongPress = { longPressedId = it },
                onMenuDismiss = {},
                onDeleteRequest = {},
            )
        }
        compose.onNodeWithText("山中书").performTouchInput { longClick() }
        assertEquals("s1", longPressedId)
        assertNull(openedId)
    }

    @Test
    fun 档案分区_点击卡仍开结局档案_回归钉() {
        var openedId: String? = null
        compose.setContent {
            StoryArchiveSection(
                archived = listOf(archivedStory()),
                onOpen = { openedId = it },
                onViewAll = {},
                menuStoryId = null,
                onCardLongPress = {},
                onMenuDismiss = {},
                onDeleteRequest = {},
            )
        }
        compose.onNodeWithText("山中书").performClick()
        assertEquals("s1", openedId)
    }

    @Test
    fun 档案分区_菜单展开_点删除行发删除请求() {
        var deleteRequestedId: String? = null
        compose.setContent {
            StoryArchiveSection(
                archived = listOf(archivedStory()),
                onOpen = {},
                onViewAll = {},
                menuStoryId = "s1",
                onCardLongPress = {},
                onMenuDismiss = {},
                onDeleteRequest = { deleteRequestedId = it },
            )
        }
        compose.onNodeWithText(app.getString(R.string.story_menu_delete)).assertIsDisplayed().performClick()
        assertEquals("s1", deleteRequestedId)
    }
}
