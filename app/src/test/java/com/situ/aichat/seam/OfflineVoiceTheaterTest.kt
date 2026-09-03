package com.situ.aichat.seam

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.offline.OfflineModeView
import com.situ.aichat.util.DateFormatters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卷三 V5 剧场语音回听 T2（图纸 `docs/handoff/2026-08-27-线上线下衔接卷三-剧场收尾.md` §7 T2-1·契约
 * FABLE5_MEETING_SEAM_PROPOSAL §5②/E1）：直接驱动**生产** [OfflineModeView]，证「见面中发的语音在剧场里
 * 是一颗可回听的药丸 + 楷体转写随行」，而不再只剩一行转写文字。
 *
 * 断言走整泡合并 a11y 句（`a11y_bubble_voice` = 「{用户名}在{时刻}发送了语音消息：{转写}」·E8）——它同时钉住
 * 「药丸在场」「转写已剥贴纸标签」两件事；点击该节点验回调收到的正是这条消息（播放机制全程在 VM 层·本卷零碰）。
 *
 * ⚠️ 屏幕尺寸必须显式给（`w411dp-h891dp`）：Robolectric 默认 320×470dp 会把内容挤出可视区，
 * `assertIsDisplayed` 静默不命中 → 假绿（踩坑总账 reference-robolectric-screen-size-fake-green）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OfflineVoiceTheaterTest {

    @get:Rule
    val compose = createComposeRule()

    private val userName = "小司"
    private val stamp = 1_756_000_000_000L

    private fun voice(uuid: String, content: String, seconds: Double = 4.0) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "conv-1",
        roleRaw = "user",
        content = content,
        timestamp = stamp,
        isVoiceMessage = true,
        audioRelativePath = "voice/$uuid.m4a",
        audioDuration = seconds,
        isOfflineMode = true,
        offlineSessionId = "sess-1",
    )

    private fun text(uuid: String, content: String) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "conv-1",
        roleRaw = "user",
        content = content,
        timestamp = stamp,
        isOfflineMode = true,
        offlineSessionId = "sess-1",
    )

    /** 整泡朗读句 = 生产 `a11y_bubble_voice` 的同参装配（英文默认资源：「{名}, {时刻}, voice message: {转写}」）。 */
    private fun a11ySentence(transcript: String) =
        "$userName, ${DateFormatters.shortTime(stamp)}, voice message: $transcript"

    private fun setTheater(
        messages: List<MessageEntity>,
        playingVoiceId: String? = null,
        onVoiceToggle: (MessageEntity) -> Unit = {},
    ) {
        compose.setContent {
            OfflineModeView(
                offlineMessages = messages,
                isWaitingForContent = false,
                characterName = "阿澈",
                characterAvatarPath = null,
                userName = userName,
                userAvatarPath = null,
                themeColorHex = "C99A86",
                entryAnimationsEnabled = false,
                playingVoiceId = playingVoiceId,
                voiceProgress = { 0f },
                onVoiceToggle = onVoiceToggle,
                onEndMeeting = {},
                onContinueMeeting = {},
                modifier = Modifier,
            )
        }
    }

    @Test
    fun `剧场内语音消息渲染成可回听药丸并带转写行`() {
        setTheater(listOf(voice("v1", "今天这杯手冲有点酸")))

        compose.onNodeWithContentDescription(a11ySentence("今天这杯手冲有点酸")).assertIsDisplayed()
        // 楷体转写行外置常显（药丸内部的「点击展开转写」在 onStage 时不渲染·J5）。
        compose.onNodeWithText("今天这杯手冲有点酸").assertIsDisplayed()
        compose.onNodeWithText("转文字").assertDoesNotExist()
    }

    @Test
    fun `点药丸把这条消息交回 onVoiceToggle`() {
        val toggled = mutableListOf<String>()
        val msg = voice("v1", "路口那家店还开着")
        setTheater(listOf(msg), onVoiceToggle = { toggled += it.messageUUID })

        compose.onNodeWithContentDescription(a11ySentence("路口那家店还开着")).performClick()

        assertEquals(listOf("v1"), toggled)
    }

    @Test
    fun `正在播放的那条才拿到真进度其余照常渲染`() {
        setTheater(listOf(voice("v1", "第一条"), voice("v2", "第二条")), playingVoiceId = "v2")

        compose.onNodeWithContentDescription(a11ySentence("第一条")).assertIsDisplayed()
        compose.onNodeWithContentDescription(a11ySentence("第二条")).assertIsDisplayed()
    }

    @Test
    fun `非语音的用户消息不出药丸（回归）`() {
        setTheater(listOf(text("t1", "我先到了")))

        compose.onNodeWithContentDescription(a11ySentence("我先到了")).assertDoesNotExist()
        // 仍按既有用户行为块渲染（台词块把文本包成「…」，故按子串断言）。
        compose.onNodeWithText("我先到了", substring = true).assertIsDisplayed()
    }

    @Test
    fun `转写里的贴纸标签被剥净（E7）`() {
        setTheater(listOf(voice("v1", "[sticker:hug]等你好久了[sticker:c_wink]")))

        compose.onNodeWithContentDescription(a11ySentence("等你好久了")).assertIsDisplayed()
        compose.onNodeWithText("等你好久了").assertIsDisplayed()
    }

    @Test
    fun `空转写不崩且药丸照常在场（E10）`() {
        setTheater(listOf(voice("v1", "")))

        compose.onNodeWithContentDescription(a11ySentence("")).assertIsDisplayed()
    }
}
