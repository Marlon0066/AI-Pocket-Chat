package com.situ.aichat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 反转列表行为三证（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §6 T2/T3·Robolectric + Compose UI 测试）——
 * 用与生产 [ChatMessageList] 同款的 LazyColumn 配置（`reverseLayout=true` + `Arrangement.Top` +
 * `contentPadding(bottom=16dp)`）在受控视口上直接实证机制本身，不依赖模拟器：
 *
 * 1. **视口缩小钉底**（治本点）：键盘/面板缩小列表视口时，最新项（index 0）物理钉在底边、绝不被裁——
 *    旧顶锚架构在此场景必须靠「事后补滚」，其守卫竞态即遮挡病根（契约 §1）。
 * 2. **头插新项视口冻结 + snapTo(0) 揭示**：锚点无条件按 key 追踪（foundation 1.9.0
 *    `LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved`·契约 §3#1），新消息由协调员
 *    `scrollToItem(0)` 揭示——与旧「append+animateScrollToItem(末项)」同机制，入场手感零变的机制前提。
 * 3. **短内容贴顶**：`Arrangement.Top` 锁住「消息铺不满一屏贴顶」现状（契约 §2.1·零感知差异硬指标）。
 *
 * 断言用未裁剪 bounds 对几何位置精确到 dp（Robolectric 默认 mdpi=1x，dp 即 px，无舍入噪声）。
 *
 * ⚠️ [setReversedList] 的四参数（reverseLayout/Arrangement.Top/bottom 16dp/key）按生产
 * `ChatMessageList` 的 LazyColumn 同配置手抄——改生产任一参数须同步本测试（T5 复核 🔵4 互指）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReversedChatListBehaviorTest {

    @get:Rule
    val compose = createComposeRule()

    private val itemHeight = 40.dp
    private val bottomBreath = 16.dp

    /** 与生产同款配置的最小反转列表：[items] 反转序（index 0 = 最新），行高恒定便于几何断言。 */
    @Suppress("TestFunctionName")
    private fun setReversedList(
        items: List<String>,
        viewportHeight: () -> Dp,
        onState: (LazyListState) -> Unit,
    ) {
        compose.setContent {
            val state = rememberLazyListState()
            onState(state)
            LazyColumn(
                state = state,
                reverseLayout = true,
                verticalArrangement = Arrangement.Top,
                contentPadding = PaddingValues(bottom = bottomBreath),
                modifier = Modifier.fillMaxWidth().height(viewportHeight()),
            ) {
                items(items.size, key = { items[it] }) { index ->
                    Box(Modifier.fillMaxWidth().height(itemHeight).testTag(items[index]))
                }
            }
        }
    }

    @Test
    fun viewportShrink_keepsNewestItemPinnedToBottom_noOcclusion() {
        // 30 条消息远超视口高（40dp×30 > 300dp），初始即在底部（反转列表初始 index 0=最新）。
        val items = List(30) { "m$it" } // m0 = 最新
        var viewport by mutableStateOf(300.dp)
        lateinit var state: LazyListState
        setReversedList(items, { viewport }, { state = it })

        // 初始：最新项钉在底边、其下是 16dp 呼吸留白（契约 §3#2：反转下 bottom padding 仍在视觉底部）。
        val before = compose.onNodeWithTag("m0").getUnclippedBoundsInRoot()
        assertEquals(300.dp - bottomBreath, before.bottom)

        // 键盘弹起等效：视口缩小 120dp（adjustResize 缩视口的机制等效路径——Scaffold 底部区域增高=列表变矮）。
        viewport = 180.dp
        compose.waitForIdle()

        // 治本点：无任何滚动补偿的前提下，最新项仍钉在新底边之上、完整可见（旧顶锚架构此处必被托盘盖住）。
        compose.onNodeWithTag("m0").assertIsDisplayed()
        val after = compose.onNodeWithTag("m0").getUnclippedBoundsInRoot()
        assertEquals(180.dp - bottomBreath, after.bottom)
        assertEquals(0, state.firstVisibleItemIndex) // 锚点纹丝不动
    }

    @Test
    fun prependNewest_freezesViewport_thenSnapToZeroReveals() {
        val items = mutableStateListOf<String>().apply { addAll(List(30) { "m$it" }) }
        lateinit var state: LazyListState
        setReversedList(items, { 300.dp }, { state = it })
        compose.waitForIdle()

        // 新消息落在 index 0（反转序头插）：锚点按 key 追踪冻结（契约 §3#1）——旧最新项 m0 被追到 index 1、
        // 位置纹丝不动；新项排在其下，仅顶部 16dp 探进底部呼吸留白（与旧顶锚 append 时新项探进留白同款），
        // 主体在视口之下待揭示。
        items.add(0, "new")
        compose.waitForIdle()
        assertEquals(1, state.firstVisibleItemIndex) // 视口冻结的锚点证据
        val peeking = compose.onNodeWithTag("new").getUnclippedBoundsInRoot()
        assertEquals(300.dp, peeking.top + bottomBreath) // 顶缘=留白起点（探头 16dp）
        assertTrue(peeking.bottom > 300.dp) // 主体在视口外,未被自动揭示

        // 协调员揭示（生产走 stickToBottom → snapTo(0)/animateTo(0)）：新气泡滑入、钉在底边留白之上。
        compose.runOnUiThread { runBlocking { state.scrollToItem(0) } }
        compose.waitForIdle()
        compose.onNodeWithTag("new").assertIsDisplayed()
        assertEquals(300.dp - bottomBreath, compose.onNodeWithTag("new").getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun shortContent_packsToTop_notBottomHug() {
        // 3 条消息（120dp）远小于视口（300dp）：Arrangement.Top 锁贴顶现状——时间正序自上而下，
        // 最新项下缘停在内容高度处（≈120dp），绝不吸附输入栏（Telegram 式贴底 hug 是登记后手，非现状）。
        val items = List(3) { "m$it" } // m0 = 最新,m2 = 最旧
        setReversedList(items, { 300.dp }, { })
        compose.waitForIdle()

        val oldest = compose.onNodeWithTag("m2").getUnclippedBoundsInRoot()
        val middle = compose.onNodeWithTag("m1").getUnclippedBoundsInRoot()
        val newest = compose.onNodeWithTag("m0").getUnclippedBoundsInRoot()
        assertEquals(0.dp, oldest.top) // 贴顶
        assertTrue(oldest.top < middle.top && middle.top < newest.top) // 时间正序（旧上新下）
        assertEquals(itemHeight * 3, newest.bottom) // 顶部打包,未坠底
    }
}
