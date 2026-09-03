package com.situ.aichat.ui.story

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 推进区三模式渲染矩阵 T2（图纸 2026-08-06「已存走向」§7 T2-4·边界 E1/E2/E4）。
 *
 * 期望从 §4.2 的槽位表独立反推——每格先问「这一模式下用户该看见什么、不该看见什么」：
 * | 槽位 | NATURAL_FLOW | NEXT_CHAPTER | BY_DIRECTION |
 * | 走向卡 | 无 | 无 | 有 |
 * | 输入卡 | 有 | 有 | **无**（走向卡顶替入口） |
 * | 主胶囊 | 让故事自然发展 | 继续写下一章 | 按走向继续写 |
 *
 * 三模式的**点击恒走同一个 onFlowClick**（禁新开生成路径）也在此钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryContinueZoneModeUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    private fun cardTitle() = app.getString(R.string.story_continue_direction_title)
    private fun inputHint() = app.getString(R.string.story_continue_director_hint)
    private fun flowPill() = app.getString(R.string.story_continue_flow)
    private fun nextPill() = app.getString(R.string.story_continue_next_chapter)
    private fun byDirectionPill() = app.getString(R.string.story_continue_by_direction)

    private var flowClicks = 0
    private var writeClicks = 0

    private fun setZone(mode: ContinueZoneMode, directionText: String?) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                StoryContinueZone(
                    isDark = true,
                    breatheTrigger = 0,
                    finaleProgress = null,
                    mode = mode,
                    directionText = directionText,
                    draftBeats = null,
                    draftUserEdited = false,
                    onWriteClick = { writeClicks++ },
                    onFlowClick = { flowClicks++ },
                    onFinaleClick = {},
                    onCancelFinaleClick = {},
                )
            }
        }
    }

    private fun assertAbsent(text: String, why: String) = assertTrue(
        why,
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty(),
    )

    // ── E1 态 A（现状逐字节不变）──

    @Test
    fun NATURAL_FLOW_无走向卡_输入卡在_胶囊说让故事自然发展() {
        setZone(ContinueZoneMode.NATURAL_FLOW, directionText = null)

        assertAbsent(cardTitle(), "没答过走向时不该冒出走向卡")
        compose.onNodeWithText(inputHint()).assertIsDisplayed()
        compose.onNodeWithText(flowPill()).assertIsDisplayed()
        assertAbsent(nextPill(), "态 A 不该出现「继续写下一章」")
        assertAbsent(byDirectionPill(), "态 A 不该出现「按走向继续写」")
    }

    // ── E4 选项点选 / E5 哨兵残留 ──

    @Test
    fun NEXT_CHAPTER_无走向卡_输入卡仍在_胶囊说继续写下一章() {
        setZone(ContinueZoneMode.NEXT_CHAPTER, directionText = null)

        assertAbsent(cardTitle(), "点选项 / 哨兵残留都不出走向卡（选择区已有「已选择：」行）")
        compose.onNodeWithText(inputHint()).assertIsDisplayed()
        compose.onNodeWithText(nextPill()).assertIsDisplayed()
        assertAbsent(flowPill(), "方向早定了，再说「让故事自然发展」就是说反话")
    }

    // ── E2 态 B ──

    @Test
    fun BY_DIRECTION_走向卡在_输入卡让位_胶囊说按走向继续写() {
        setZone(ContinueZoneMode.BY_DIRECTION, directionText = "让她在温泉旅馆偶遇两人")

        compose.onNodeWithText(cardTitle()).assertIsDisplayed()
        compose.onNodeWithText("让她在温泉旅馆偶遇两人").assertIsDisplayed()
        assertAbsent(inputHint(), "态 B 下输入卡整卡隐藏（D-2·走向卡顶替入口）")
        compose.onNodeWithText(byDirectionPill()).assertIsDisplayed()
        assertAbsent(flowPill(), "态 B 不该出现「让故事自然发展」")
        assertAbsent(nextPill(), "态 B 不该出现「继续写下一章」")
    }

    /** 态 B 下走向卡就是编辑入口：点它走 onWriteClick（开导演台），不是 onFlowClick。 */
    @Test
    fun BY_DIRECTION_点走向卡走onWriteClick() {
        flowClicks = 0
        writeClicks = 0
        setZone(ContinueZoneMode.BY_DIRECTION, directionText = "改一改这条走向")

        compose.onNodeWithText("改一改这条走向").performClick()

        assertEquals("点卡 = 开导演台", 1, writeClicks)
        assertEquals("点卡不该触发生成", 0, flowClicks)
    }

    // ── §4.3 机制锁：三模式点击恒走同一个 onFlowClick ──

    // §4.3 机制锁：三模式点击恒走同一个 onFlowClick（= forceContinue），禁新开生成路径。
    // 三格拆成三条用例——compose rule 每条用例一份，一条里 setContent 只能调一次。

    private fun assertPillGoesToFlowClick(mode: ContinueZoneMode, directionText: String?, pill: String) {
        setZone(mode, directionText)
        compose.onNodeWithText(pill).performClick()
        assertEquals("$mode 的主胶囊必须走 onFlowClick", 1, flowClicks)
        assertEquals("$mode 的主胶囊不该开导演台", 0, writeClicks)
    }

    @Test
    fun NATURAL_FLOW_主胶囊点击走onFlowClick() =
        assertPillGoesToFlowClick(ContinueZoneMode.NATURAL_FLOW, null, flowPill())

    @Test
    fun NEXT_CHAPTER_主胶囊点击走onFlowClick() =
        assertPillGoesToFlowClick(ContinueZoneMode.NEXT_CHAPTER, null, nextPill())

    @Test
    fun BY_DIRECTION_主胶囊点击走onFlowClick() =
        assertPillGoesToFlowClick(ContinueZoneMode.BY_DIRECTION, "已存走向", byDirectionPill())
}
