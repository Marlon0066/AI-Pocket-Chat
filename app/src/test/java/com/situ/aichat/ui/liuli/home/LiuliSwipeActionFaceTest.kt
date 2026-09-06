package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.chat.SwipeAction
import com.situ.aichat.ui.chat.SwipeActionsRow
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：左滑玻璃圆钮动作面（图纸 2026-09-06 卷四 §8 C5 · A-12 · §5 E20）。
 *
 * 钉：`actionFace` **不传时逐字节走暖陶原面**（加法零回归）；传了才换成琉璃的圆钮面；
 * 两条 a11y `customActions` 在两种面下都还在（机制共用一份·不因换脸掉）；面上的钮回调恰一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSwipeActionFaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableMapOf<String, Int>()

    private fun show(withFace: Boolean, isPinned: Boolean = false) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    val colors = AppTheme.colors
                    val pin = SwipeAction(
                        label = if (isPinned) "取消置顶" else "置顶",
                        icon = Icons.Filled.PushPin,
                        containerColor = if (withFace) Color.Transparent else colors.accent.container,
                        contentColor = if (withFace) colors.accent.text else colors.accent.onContainer,
                        onClick = { taps["pin"] = (taps["pin"] ?: 0) + 1 },
                    )
                    val delete = SwipeAction(
                        label = "删除",
                        icon = Icons.Filled.Delete,
                        containerColor = if (withFace) Color.Transparent else colors.status.errorContainer,
                        contentColor = colors.status.onError,
                        onClick = { taps["delete"] = (taps["delete"] ?: 0) + 1 },
                    )
                    SwipeActionsRow(
                        onRowClick = { taps["row"] = (taps["row"] ?: 0) + 1 },
                        leadingActions = listOf(pin),
                        trailingActions = listOf(delete),
                        actionFace = if (withFace) {
                            { action, modifier, onClick -> LiuliSwipeActionFace(action, modifier, onClick) }
                        } else {
                            null
                        },
                    ) {
                        Box(Modifier.fillMaxWidth().height(64.dp)) { Text("小满") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** 动作面单独量：圆钮的 cd = 动作 label，点它回调恰一次（面在行里时被内容层盖着，要滑开才点得到 → 装机批）。 */
    private fun showFaceAlone(label: String) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliSwipeActionFace(
                        action = SwipeAction(
                            label = label,
                            icon = Icons.Filled.PushPin,
                            containerColor = Color.Transparent,
                            contentColor = AppTheme.colors.accent.text,
                            onClick = {},
                        ),
                        modifier = Modifier.width(76.dp).height(64.dp),
                        onClick = { taps["face"] = (taps["face"] ?: 0) + 1 },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 琉璃面把动作画成圆钮且钮回调恰一次() {
        showFaceAlone("置顶")
        // 圆钮的 contentDescription = 动作 label（`LiuliCircleButton` 自带）；面上另有一行同名小字。
        compose.onNodeWithContentDescription("置顶").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["face"])
        compose.onNodeWithText("置顶").assertExists()
    }

    /** 复核 R1 🟡-5：点击面 = 整个面（含小字），不只圆钮——与暖陶 `ActionButton` 同。 */
    @Test fun 点面上的小字也算点到动作() {
        showFaceAlone("删除")
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, taps["face"])
    }

    @Test fun 不传face时走暖陶原面没有圆钮语义() {
        show(withFace = false)
        // 暖陶 `ActionButton` 把 label 挂在 Icon 的 contentDescription 上、没有 Button role 的圆钮节点，
        // 但文案照样在（两种面都用同一批 SwipeAction）。
        compose.onNodeWithText("置顶").assertExists()
        compose.onNodeWithText("删除").assertExists()
    }

    /** E20：换脸不动机制——两条 `customActions`（置顶 / 删除）在琉璃面下仍挂在行上。 */
    @Test fun 换脸后a11y两条自定义动作仍在() {
        show(withFace = true)
        val node = compose.onNodeWithText("小满").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("置顶", "删除"), actions.map { it.label })
        actions[0].action()
        compose.waitForIdle()
        assertEquals(1, taps["pin"])
    }

    /** pin / unpin 文案随 `isPinned` 走（文案由调用方给·本件只显示）。 */
    @Test fun 置顶文案随状态切换() {
        showFaceAlone("取消置顶")
        compose.onNodeWithContentDescription("取消置顶").assertExists()
        compose.onNodeWithContentDescription("置顶").assertDoesNotExist()
    }
}
