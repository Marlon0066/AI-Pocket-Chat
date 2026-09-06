package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatSheetsState
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.chat.QuoteTextOnlyHintState
import com.situ.aichat.ui.chat.rememberChatInputPanelState
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-4 琉璃「+」变形面板（图纸 2026-09-05 卷二B §7）：六入口的显隐规则（见面态 / vision 门）、
 * 点一格必先收面板、以及带引用时的三处拦截之一（「表情」）。
 *
 * 面板状态机是**借来的**（`ChatInputPanelState`·PLUS_PANEL 机制零碰），所以这里用**真状态机**驱动，
 * 看 `panelOpen` 真的翻回 false，而不是 mock 掉 `dismiss` 只验「调用过」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliPlusPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewModel = mockk<ChatViewModel>(relaxed = true)
    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val sheets = ChatSheetsState()
    private val quoteHint = QuoteTextOnlyHintState()
    private var panelOpen: () -> Boolean = { false }
    private var dismissAll: () -> Unit = {}

    private fun setPanel(
        isOfflineMode: Boolean = false,
        chatModelHasVision: Boolean = true,
        replyTarget: MessageEntity? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                val focusRequester = remember { FocusRequester() }
                val inputPanel = rememberChatInputPanelState(
                    keyboard = LocalSoftwareKeyboardController.current,
                    focusManager = LocalFocusManager.current,
                    fieldFocus = focusRequester,
                    minHeightPx = MIN_PANEL_PX,
                    maxHeightPx = MAX_PANEL_PX,
                )
                panelOpen = { inputPanel.panelOpen }
                dismissAll = { inputPanel.dismiss(reduceMotion = true) }
                // 无键盘时开面板 → 走兜底高度（PLUS_PANEL 机制·本测不碰它，只要区域够高能摆下格子）。
                remember { inputPanel.openPanel(currentImePx = 0, fallbackPx = FALLBACK_PANEL_PX) }
                Box(Modifier.fillMaxSize()) {
                    LiuliPlusPanel(
                        viewModel = viewModel,
                        sheets = sheets,
                        inputPanel = inputPanel,
                        replyTarget = replyTarget,
                        quoteHint = quoteHint,
                        isOfflineMode = isOfflineMode,
                        chatModelHasVision = chatModelHasVision,
                        reduceMotion = true,
                        regionPx = { FALLBACK_PANEL_PX },
                    )
                }
            }
        }
    }

    @Test fun onlineWithVision_showsAllSixEntries() {
        setPanel()
        listOf("送礼", "红包", "表情", "照片", "见面", "约见面").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun withoutVision_hidesPhotoOnly() {
        setPanel(chatModelHasVision = false)
        compose.onNodeWithText("照片").assertDoesNotExist()
        listOf("送礼", "红包", "表情", "见面", "约见面").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun offlineMode_keepsOnlyStickerAndPhoto() {
        // 见面期隐藏 送礼 / 红包 / 见面 / 约见面（2026-06-21 用户拍板）。
        setPanel(isOfflineMode = true)
        listOf("送礼", "红包", "见面", "约见面").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("表情").assertIsDisplayed()
        compose.onNodeWithText("照片").assertIsDisplayed()
    }

    @Test fun tappingSticker_dismissesPanel_andOpensPicker() {
        setPanel()
        assertTrue("前提：面板是开着的", panelOpen())
        compose.onNodeWithText("表情").performClick()
        compose.waitForIdle()
        assertFalse("点一格必先收面板", panelOpen())
        assertTrue("表情面板被请出来", sheets.showPicker)
    }

    @Test fun tappingStickerWithQuote_showsHintInstead() {
        // 引用一期 E·拦截③：带引用时不开表情面板，只弹提示。
        setPanel(replyTarget = MessageEntity(messageUUID = "q", conversationUuid = "c", roleRaw = "assistant", content = "在", timestamp = 1L))
        compose.onNodeWithText("表情").performClick()
        compose.waitForIdle()
        assertFalse("带引用绝不开表情面板", sheets.showPicker)
        assertTrue("改弹「引用时只能发文字」提示", quoteHint.visible)
        assertEquals("面板照样收起", false, panelOpen())
    }

    @Test fun panelBox_isRegionMinusPanelTop_andSitsPanelBottomAboveTheNavBar() {
        // 复核 R1 🟡-3（图纸 §3.2 公式勘误 / §4.4「面板顶 = 三片行底 + 6」）：托盘自带 inputBottom 离屏底，
        // 面板高必须是 regionPx − panelTop（= regionPx − 6dp），旧式 regionPx − (6 + 12) 会让面板顶离三片行 18dp。
        // Robolectric 无导航栏、1px = 1dp：面板底 = 根底 − panelBottom；首格顶 = 面板顶 + 16（§3.2 内 padding top）。
        setPanel()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val firstTile = compose.onNodeWithText("送礼").getUnclippedBoundsInRoot()
        val panelBottom = root.bottom - LiuliChatGeometry.panelBottom
        val expectedHeight = FALLBACK_PANEL_PX.dp - LiuliChatGeometry.panelTop
        val expectedTileTop = panelBottom - expectedHeight + 16.dp
        assertEquals("首格顶 = 根底 − 12 − (regionPx − 6) + 16", expectedTileTop.value, firstTile.top.value, 0.5f)
    }

    @Test fun tappingGift_opensGiftSheet_notOthers() {
        setPanel()
        compose.onNodeWithText("送礼").performClick()
        compose.waitForIdle()
        assertTrue(sheets.showGiftSheet)
        assertFalse(sheets.showRedPacketSheet)
        assertFalse(sheets.showPicker)
        dismissAll()
    }

    private companion object {
        const val MIN_PANEL_PX = 180
        const val MAX_PANEL_PX = 900
        const val FALLBACK_PANEL_PX = 780
    }
}
