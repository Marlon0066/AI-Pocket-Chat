package com.situ.aichat.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 引用一期 E「引用时只能发文字」提示条的行为测试（图纸 §3.5/§4·边界 B11–B14）。
 *
 * 提示是叠在输入托盘上的**瞬态**元素，逻辑全在 [rememberQuoteTextOnlyHint] 的两条自动收场里；
 * 这里用真组合驱动它 + 驱动 [VoiceRecordButton] 的拦截闸，把「什么时候出、什么时候必须消、
 * 没有引用时绝不打扰」钉死。
 *
 * `@Config(qualifiers = "zh-rCN-w411dp-h891dp")`：断言用中文物料（生产主语言），
 * 且 Robolectric 默认屏只有 320×470，托盘元素会被推出可视区导致节点查找假绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class QuoteTextOnlyHintTest {

    @get:Rule
    val compose = createComposeRule()

    /** 提示文案在测试里**重新打字**一遍 + 与资源值互相印证（双保险 pin·PITFALLS §1e）。 */
    private val hintText = "引用时只能发文字，取消引用后再发"

    private fun quotedMessage() = MessageEntity(
        messageUUID = "q1",
        conversationUuid = "c1",
        roleRaw = "assistant",
        content = "晚上七点老地方",
        timestamp = 1_000L,
        messageKindRaw = MessageKind.PLAIN_TEXT.raw,
    )

    private fun hintNodes() = compose.onAllNodesWithText(hintText).fetchSemanticsNodes()

    @Test
    fun `文案资源与断言字面量一致`() {
        assertEquals(hintText, RuntimeEnvironment.getApplication().getString(R.string.chat_quote_text_only_hint))
    }

    // ---- 出现 / 消失 ----

    @Test
    fun `触发后提示出现`() {
        lateinit var hint: QuoteTextOnlyHintState
        compose.setContent {
            hint = rememberQuoteTextOnlyHint(quotedMessage())
            QuoteTextOnlyHint(hint.visible, reduceMotion = true, wallpaperFrosted = null, wallpaperDark = false)
        }
        assertTrue("初始态不该有提示", hintNodes().isEmpty())

        compose.runOnUiThread { hint.trigger() }
        compose.waitForIdle()

        assertTrue(hint.visible)
        assertEquals("提示条应上屏且只有一条", 1, hintNodes().size)
    }

    @Test
    fun `引用被取消时提示立即消失`() {
        // B12（点引用卡 ✕）与 B13（引用被这次发送消费掉）走的是同一条通路：replyTarget 变 null。
        var target by mutableStateOf<MessageEntity?>(quotedMessage())
        lateinit var hint: QuoteTextOnlyHintState
        compose.setContent {
            hint = rememberQuoteTextOnlyHint(target)
            QuoteTextOnlyHint(hint.visible, reduceMotion = true, wallpaperFrosted = null, wallpaperDark = false)
        }
        compose.runOnUiThread { hint.trigger() }
        compose.waitForIdle()
        assertEquals(1, hintNodes().size)

        compose.runOnUiThread { target = null }
        compose.waitForIdle()

        assertFalse("引用没了提示必须立即跟着消失", hint.visible)
        assertTrue(hintNodes().isEmpty())
    }

    @Test
    fun `重复触发不叠第二条且计时重启`() {
        // B11：3 秒内再点一次 → 令牌自增（倒计时从头再来），屏上仍只有一条。
        lateinit var hint: QuoteTextOnlyHintState
        compose.setContent {
            hint = rememberQuoteTextOnlyHint(quotedMessage())
            QuoteTextOnlyHint(hint.visible, reduceMotion = true, wallpaperFrosted = null, wallpaperDark = false)
        }
        compose.runOnUiThread { hint.trigger() }
        compose.waitForIdle()
        val firstToken = hint.token

        compose.runOnUiThread { hint.trigger() }
        compose.waitForIdle()

        assertEquals("重复触发必须重启计时（令牌自增）", firstToken + 1, hint.token)
        assertEquals("绝不叠出第二条", 1, hintNodes().size)
        assertTrue(hint.visible)
    }

    @Test
    fun `停留时长锁 3000ms`() {
        // 计时本体是 LaunchedEffect 里的 delay：compose 的 mainClock 只驱动帧、推到 3.4 秒不到点，
        // ShadowLooper 推真钟也不到点（两条路本批都实测过，别再试第三次）。故这里只把**时长常量**钉死，
        // 「到点真会自己消」由装机取证（图纸 §7 装机项②）。
        assertEquals(3000L, QUOTE_HINT_DURATION_MS)
    }

    // ---- 语音键的拦截闸（拦在权限之前·B14 反向守卫） ----

    @Test
    fun `带引用时按住说话被拦且不开始录音`() {
        var blockedCount = 0
        var startCount = 0
        compose.setContent {
            VoiceRecordButton(
                hasMicPermission = false, // 拦截排在权限分支之前：注定被拒的操作不去要麦克风权限
                onRequestPermission = { throw AssertionError("被拦时不该申请麦克风权限") },
                blocked = true,
                onBlocked = { blockedCount++ },
                onStartRecording = { startCount++ },
                onDrag = {},
                onFinish = {},
                recording = false,
                cancelling = false,
                reduceMotion = true,
            )
        }
        compose.onRoot().performTouchInput { down(center) }
        compose.waitForIdle()

        assertEquals("按下即应回一次 onBlocked", 1, blockedCount)
        assertEquals("绝不能开始录音", 0, startCount)
    }

    @Test
    fun `没有引用时按住说话一切照旧`() {
        // B14 的正向反证：同一次按下，blocked=false 时 onBlocked 零发、权限分支照常接管。
        var blockedCount = 0
        var permissionAsked = 0
        compose.setContent {
            VoiceRecordButton(
                hasMicPermission = false,
                onRequestPermission = { permissionAsked++ },
                blocked = false,
                onBlocked = { blockedCount++ },
                onStartRecording = {},
                onDrag = {},
                onFinish = {},
                recording = false,
                cancelling = false,
                reduceMotion = true,
            )
        }
        compose.onRoot().performTouchInput { down(center) }
        compose.waitForIdle()

        assertEquals("没有引用就绝不弹提示", 0, blockedCount)
        assertEquals("拦截闸放行后，权限分支必须照常跑到", 1, permissionAsked)
    }
}
