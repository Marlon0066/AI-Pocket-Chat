package com.situ.aichat.ui.liuli.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.chat.ChatListViewModel
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import com.situ.aichat.util.DateFormatters
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-4：琉璃聊天列表的长相与回调（图纸 2026-09-06 卷三 §7 T2-4 · §4.3 A · E6）。
 *
 * 无 VM——直接驱动 [LiuliChatListContent]，把「一个大脑喂两张脸」这件事测干净：行文案（名 / 预览带
 * 「你: 」/ 相对时间）、置顶节与两节顺序、未读丸的 3 与 100、状态点 + 日程状态字、空态三件 + CTA、
 * 长按 → 快速回复、点行 → 进会话。
 *
 * 相对时间串在这里**重新打字**成字面量（不读资源、不读实现），断言从规格独立反推（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliChatListContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_757_000_000_000L
    private val relStrings = DateFormatters.RelativeTimeStrings(
        justNow = "刚刚",
        minutesAgo = "%d 分钟前",
        hoursAgo = "%d 小时前",
        yesterday = "昨天",
    )

    private var opened: String? = null
    private var quickReplied: String? = null
    private var ctaTaps = 0

    private fun row(
        uuid: String,
        name: String,
        preview: String,
        role: String = "assistant",
        pinned: Boolean = false,
        unread: Int = 0,
    ) = ChatListViewModel.Row(
        conversation = ConversationEntity(
            uuid = uuid,
            title = name,
            characterUuid = "c-$uuid",
            creationDate = now - 60_000,
            isPinned = pinned,
            lastMessageDate = now - 60_000,
            lastMessagePreview = preview,
            lastMessageRole = role,
            cachedUnreadCount = unread,
        ),
        character = null,
    )

    private fun show(rows: List<ChatListViewModel.Row>, scheduleStatus: Map<String, String> = emptyMap()) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliChatListContent(
                        rows = rows,
                        query = "",
                        isSearching = false,
                        scheduleStatus = scheduleStatus,
                        nowMillis = now,
                        relStrings = relStrings,
                        onQueryChange = {},
                        onOpenChat = { opened = it.conversation.uuid },
                        onTogglePin = {},
                        onRequestDelete = {},
                        onQuickReply = { quickReplied = it.conversation.uuid },
                        onNewConversation = { ctaTaps++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 行文案是名字加预览加相对时间() {
        show(listOf(row("a", "小满", "晚安", role = "user")))
        compose.onNodeWithText("小满").assertIsDisplayed()
        compose.onNodeWithText("你: 晚安").assertIsDisplayed()
        compose.onNodeWithText("1 分钟前").assertIsDisplayed()
    }

    @Test fun AI消息的预览不带你字前缀() {
        show(listOf(row("a", "小满", "睡了吗")))
        compose.onNodeWithText("睡了吗").assertIsDisplayed()
    }

    @Test fun 置顶节在前且带节标题() {
        show(listOf(row("a", "阿泠", "在", pinned = true), row("b", "小满", "嗯")))
        val pinnedTop = compose.onNodeWithText("阿泠").fetchSemanticsNode().positionInRoot.y
        val normalTop = compose.onNodeWithText("小满").fetchSemanticsNode().positionInRoot.y
        assertEquals("置顶那条必须排在普通那条之前", true, pinnedTop < normalTop)
        // 「置顶」这个词屏上有两处：节标题，以及未置顶行左滑露出的动作标签——只认排在置顶行**之上**的那处。
        val above = compose.onAllNodesWithText("置顶").fetchSemanticsNodes().any { it.positionInRoot.y < pinnedTop }
        assertEquals("置顶节标题必须在置顶行之上", true, above)
    }

    @Test fun 未读丸显三与九九加() {
        show(listOf(row("a", "小满", "嗯", unread = 3), row("b", "阿泠", "在", unread = 100)))
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("99+").assertIsDisplayed()
    }

    @Test fun 有日程状态时名字后跟状态字() {
        show(listOf(row("a", "小满", "嗯")), scheduleStatus = mapOf("c-a" to "在写代码"))
        compose.onNodeWithText("在写代码").assertIsDisplayed()
    }

    @Test fun 空态三件齐全且CTA回调恰一次() {
        show(emptyList())
        compose.onNodeWithText("还没有对话").assertIsDisplayed()
        compose.onNodeWithText("开始一段新的聊天吧").assertIsDisplayed()
        compose.onNodeWithText("新建对话").performClick()
        compose.waitForIdle()
        assertEquals(1, ctaTaps)
    }

    @Test fun 点行进会话() {
        show(listOf(row("a", "小满", "嗯")))
        compose.onNodeWithText("小满").performClick()
        compose.waitForIdle()
        assertEquals("a", opened)
    }

    @Test fun 长按行是快速回复不是进会话() {
        show(listOf(row("a", "小满", "嗯")))
        compose.onNodeWithText("小满").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("a", quickReplied)
        assertEquals("长按绝不顺带进会话", null, opened)
    }

    @Test fun 右上加号也走新建对话() {
        show(listOf(row("a", "小满", "嗯")))
        compose.onNodeWithContentDescription("新建对话").performClick()
        compose.waitForIdle()
        assertEquals(1, ctaTaps)
    }
}
