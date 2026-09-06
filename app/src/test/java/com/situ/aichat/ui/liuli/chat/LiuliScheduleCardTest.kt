package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-6 琉璃日程卡（图纸 2026-09-05 卷二C §7）：`[#E1]` 样本经解析器拆段后——条目段进 236 卡（头带
 * 「今天的安排」+「日程」标签、体是时间列），普通文字段照 `body` 直出在卡外。
 *
 * 解析器本身零碰（红线：`[#E1]` ↔ `CalendarItemParser` 双侧同步），本例只钉**消费端**的呈现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliScheduleCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun setCard(content: String) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliScheduleCard(content = content, onLongClick = {})
            }
        }
    }

    @Test fun singleItem_showsHeaderTagTimeAndTitle() {
        setCard("[#E1] 阳台给薄荷浇水（08:30）")
        compose.onNodeWithText("今天的安排").assertIsDisplayed()
        compose.onNodeWithText("日程").assertIsDisplayed()
        compose.onNodeWithText("08:30").assertIsDisplayed()
        compose.onNodeWithText("阳台给薄荷浇水").assertIsDisplayed()
    }

    @Test fun multipleItems_shareOneCard_asATimeColumn() {
        setCard(
            """
            [#E1] 阳台给薄荷浇水（08:30）
            [#E2] 去图书馆还书（10:00）
            [#E3] 给你写点东西（15:00）
            """.trimIndent(),
        )
        // 头只出现一次 = 三条目合进同一张卡（对版稿 `.card.sched`）。
        compose.onNodeWithText("今天的安排").assertIsDisplayed()
        compose.onNodeWithText("08:30").assertIsDisplayed()
        compose.onNodeWithText("10:00").assertIsDisplayed()
        compose.onNodeWithText("15:00").assertIsDisplayed()
        compose.onNodeWithText("给你写点东西").assertIsDisplayed()
    }

    @Test fun mixedContent_keepsPlainTextOutsideTheCard() {
        setCard("今天大概是这样安排的\n[#E1] 阳台给薄荷浇水（08:30）")
        compose.onNodeWithText("今天大概是这样安排的").assertIsDisplayed()
        compose.onNodeWithText("今天的安排").assertIsDisplayed()
    }

    @Test fun pureText_rendersNoCardAtAll() {
        setCard("今天没什么安排")
        compose.onNodeWithText("今天没什么安排").assertIsDisplayed()
        compose.onNodeWithText("今天的安排").assertDoesNotExist()
        compose.onNodeWithText("日程").assertDoesNotExist()
    }

    // ── 复核 R1 🟡-5：段序与时间列（整卡 combinedClickable 会把文字并成一个节点，量几何要走未合并树） ──

    @Test fun textAfterItems_staysBelowTheCard_inSourceOrder() {
        // 文字段原位直出：条目后的那句话要落在卡**下面**，不能被提到卡前。
        setCard("[#E1] 阳台给薄荷浇水（08:30）\n记得来哦")
        val header = compose.onNodeWithText("今天的安排", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val trailing = compose.onNodeWithText("记得来哦", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("卡后文字（顶 ${trailing.top}）应在卡头（顶 ${header.top}）之下", trailing.top > header.top)
    }

    @Test fun longDateInfo_widensTheTimeColumn_andNeverOverlapsTheTitle() {
        // `dateInfo` 是括号内原样文本，不一定是 HH:mm：比 38dp 列宽长的自然展开（列只设下限 38 / 上限 112），
        // 标题跟着右移不叠。Robolectric 字形度量是假的（约 1dp/字），故用 45 字的长串把「实宽 > 38」逼出来。
        val longInfo = "9月7日 14:00~15:30，提前十分钟到，带上上次说的那本书和伞，回来顺路买两杯咖啡"
        setCard("[#E1] 去看展（$longInfo）")
        val time = compose.onNodeWithText(longInfo, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("去看展", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("时间列应随内容变宽（宽 ${time.right - time.left} > 38dp），而不是钉死 38 折成几行", (time.right - time.left).value > 38f)
        assertTrue("标题左缘 ${title.left} 应在时间右缘 ${time.right} 之右", title.left >= time.right)
    }

    @Test fun blocks_keepSourceOrder_andMergeOnlyConsecutiveItems() {
        val blocks = liuliScheduleBlocks(
            CalendarItemParser.parse("开场\n[#E1] 甲（08:00）\n[#E2] 乙（09:00）\n中间\n[#E3] 丙（10:00）"),
        )
        assertEquals(4, blocks.size)
        assertEquals(LiuliScheduleBlock.Text("开场"), blocks[0])
        assertEquals(listOf("甲", "乙"), (blocks[1] as LiuliScheduleBlock.Items).items.map { it.title })
        assertEquals(LiuliScheduleBlock.Text("中间"), blocks[2])
        assertEquals(listOf("丙"), (blocks[3] as LiuliScheduleBlock.Items).items.map { it.title })
    }
}
