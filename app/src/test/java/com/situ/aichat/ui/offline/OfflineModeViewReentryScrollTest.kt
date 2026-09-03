package com.situ.aichat.ui.offline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 线下见面剧场「重进落底」行为测试（微图纸 2026-08-26·Robolectric + Compose UI 测试）——
 * 驱动**生产 [OfflineModeView]** 复现重进场景：首帧 Room 流尚未吐值（空列表，只剩恒存的
 * offline_header 装饰项），消息随后灌入。修复前首次定位在空帧被消耗，key 锚点追着装饰项
 * 漂到视觉顶部且 isNearBottom 守卫误判「用户已上翻」→ 永卡顶部（PITFALLS
 * 「LazyList 首项之前插入新项锚住原首项」的反转列表变体）；修复后首次定位只认内容项。
 *
 * 消息时间戳一律置于进屏之前 → 揭示动画走「已播放直显」路径（[OfflineBlockReveal] 首帧冻结
 * 不动画），测试确定性、无动画时钟依赖。断言正反配对（Robolectric 假绿陷阱防线）：
 * 最新一条可见（正向证据）+ 顶部装饰未组合（反向证据，卡顶时它恰好可见）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineModeViewReentryScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private fun msg(i: Int, ts: Long) = MessageEntity(
        messageUUID = "off-$i",
        conversationUuid = "conv",
        roleRaw = if (i % 2 == 0) "user" else "assistant",
        content = "见面消息第${i}句，足够长的一段话保证内容块有真实渲染高度。",
        timestamp = ts,
        isOfflineMode = true,
        offlineSessionId = "s1",
    )

    private fun setTheater(messages: List<MessageEntity>) {
        compose.setContent {
            OfflineModeView(
                offlineMessages = messages,
                isWaitingForContent = false,
                characterName = "角色",
                characterAvatarPath = null,
                userName = "我",
                userAvatarPath = null,
                themeColorHex = null,
                chatWallpaperPath = null,
                entryAnimationsEnabled = true,
                // 卷三 V5：剧场语音三参（本测试只验重进滚动锚定，语音路径不参与 → 空实现）。
                playingVoiceId = null,
                voiceProgress = { 0f },
                onVoiceToggle = {},
                onEndMeeting = {},
                onContinueMeeting = {},
                modifier = Modifier.fillMaxWidth().height(400.dp),
            )
        }
    }

    @Test
    fun reentry_emptyFirstFrame_thenMessagesArrive_landsAtBottom() {
        val past = System.currentTimeMillis() - 60_000L
        val messages = mutableStateListOf<MessageEntity>()
        setTheater(messages)
        compose.waitForIdle() // 重进首帧：流未吐值，列表仅剩顶部装饰项

        repeat(30) { messages.add(msg(it, past + it * 1000L)) }
        compose.waitForIdle()

        // 落底：最新一条完整可见；顶部装饰（30 条内容之外）未被组合 = 视口没跟着装饰项漂到顶。
        compose.onNodeWithText("见面消息第29句", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("线下见面").assertCountEquals(0)
    }

    @Test
    fun firstEntry_messagesAlreadyPresent_landsAtBottom() {
        val past = System.currentTimeMillis() - 60_000L
        setTheater(List(30) { msg(it, past + it * 1000L) })
        compose.waitForIdle()

        compose.onNodeWithText("见面消息第29句", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("线下见面").assertCountEquals(0)
    }
}
