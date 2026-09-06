package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.util.DateFormatters
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-11 琉璃消息行分派器（图纸 2026-09-05 卷二C §7 · E12 · A-11）：
 * ① 两道隐身闸（脏消息 / 见面期细节）→ 整行零节点
 * ② 每种 `MessageKind` 落到对应的琉璃件（判据用**各件独有的文案 / cd**，不靠 testTag）
 * ③ 前置返回三件不进「左右对齐 + 气泡」那一行。
 *
 * 「暖陶 `MessageRow` 已卸下」那一条是**源码级**断言（复核用 `grep` 核 `ui/liuli` 下零 import），
 * 渲染层证不了「没 import 谁」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliMessageRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private val noopActions = MessageRowActions(
        onVoiceToggle = {},
        onOpenImage = {},
        onSaveImage = {},
        onQuote = {},
        onDelete = {},
        onOpenMenu = { _, _, _ -> },
        onFlightBubblePositioned = { _, _ -> },
        onRegenerate = {},
        loadDiyImage = { null },
        onOpenDiyDetail = {},
        observeRedPacket = { flowOf(null) },
        onRedPacketClick = {},
        onAcceptInvite = {},
        onDeclineInvite = {},
        onEndMeeting = {},
        onContinueMeeting = {},
        onReviewOffline = {},
        observeAppointment = { flowOf(null) },
        onAppointmentAccept = {},
        onAppointmentDecline = {},
        onAppointmentReschedule = {},
        onAppointmentChangeApply = {},
        onAppointmentChangeKeep = {},
        onVoiceCascadePlayed = {},
        onOpenVoiceSetup = {},
    )

    private fun msg(
        content: String,
        kind: MessageKind = MessageKind.PLAIN_TEXT,
        role: String = "assistant",
        isOfflineMode: Boolean = false,
        isPartOfVoiceCall: Boolean = false,
        offlineSessionId: String? = null,
    ) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c",
        roleRaw = role,
        content = content,
        timestamp = 1_756_000_000_000L,
        messageKindRaw = kind.raw,
        isContentRevealed = true,
        isOfflineMode = isOfflineMode,
        isPartOfVoiceCall = isPartOfVoiceCall,
        offlineSessionId = offlineSessionId,
    )

    private fun setRow(message: MessageEntity) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliMessageRow(
                    message = message,
                    topPadding = 0.dp,
                    characterName = "云野",
                    avatarPath = null,
                    userName = "我",
                    userAvatarPath = null,
                    customStickers = emptyList(),
                    isVoicePlaying = false,
                    voiceProgress = { 0f },
                    actions = noopActions,
                    canRegenerate = false,
                    deliveryRead = null,
                    tail = true,
                    flightTracking = false,
                    reaction = LiuliReactionState(),
                    reduceMotion = true,
                    fold = LiuliFoldState(),
                )
            }
        }
    }

    /** 语义树里除根节点外是否空空如也（= 整行零渲染）。 */
    private fun assertRowIsEmpty() {
        val dump = compose.onRoot().printToString(maxDepth = 6)
        assertEquals("整行应零渲染，实际：\n$dump", 0, compose.onRoot().fetchSemanticsNode().children.size)
    }

    // ── 两道隐身闸 ────────────────────────────────────────────────────────────

    @Test fun offlineDetail_rendersNothing() {
        // 见面期间产生的普通消息（isOfflineMode=true）不进日常聊天（离场标记除外）。
        setRow(msg("我们沿着江边走", isOfflineMode = true))
        assertRowIsEmpty()
    }

    @Test fun voiceCallTranscript_rendersNothing() {
        setRow(msg("通话里说的一句", isPartOfVoiceCall = true))
        assertRowIsEmpty()
    }

    @Test fun systemHint_rendersNothing() {
        setRow(msg("（用户打开了发起见面界面又取消了）", kind = MessageKind.SYSTEM_HINT))
        assertRowIsEmpty()
    }

    @Test fun dirtyMessage_rendersNothing() {
        // 复读记忆注入格式 = 脏消息判据（`DirtyMessageDetector`·零碰·样本取自其自有测试）。
        setRow(msg("【长期事实】\n- 喜欢猫\n【近期经历】\n- [2026-06-10] 去了公园"))
        assertRowIsEmpty()
    }

    // ── 分派：各件独有文案 ────────────────────────────────────────────────────

    @Test fun plainText_goesToLiuliBubble() {
        setRow(msg("今天的风刚好"))
        compose.onNodeWithText("今天的风刚好").assertIsDisplayed()
    }

    @Test fun scheduleCard_goesToLiuliScheduleCard() {
        setRow(msg("[#E1] 阳台给薄荷浇水（08:30）", kind = MessageKind.SCHEDULE_CARD))
        compose.onNodeWithText("今天的安排").assertIsDisplayed()
        compose.onNodeWithText("日程").assertIsDisplayed()
    }

    @Test fun redPacket_goesToLiuliRedPacketCard() {
        setRow(
            msg(
                """{"type":"red_packet","recordUUID":"r1","amount":88,"blessingText":"请你吃糖"}""",
                kind = MessageKind.RED_PACKET,
            ),
        )
        compose.onNodeWithContentDescription("红包，请你吃糖，点击拆开 🧧").assertIsDisplayed()
    }

    @Test fun giftCard_goesToLiuliGiftCard() {
        setRow(
            msg(
                """{"type":"gift_card","giftItemId":"g1","giftRecordId":"r1","cost":120,"giftName":"桂花糕","isHandmade":false}""",
                kind = MessageKind.GIFT_CARD,
            ),
        )
        compose.onNodeWithContentDescription("收到礼物 桂花糕，心意 120 金币").assertIsDisplayed()
    }

    @Test fun callRecord_goesToLiuliCallRecordCard() {
        setRow(
            msg(
                """{"type":"call_record","duration":725,"startTime":"2026-09-05T21:50:00Z","transcript":[]}""",
                kind = MessageKind.CALL_RECORD_CARD,
            ),
        )
        compose.onNodeWithText("语音通话").assertIsDisplayed()
        compose.onNodeWithText("查看通话记录").assertIsDisplayed()
    }

    @Test fun systemEvent_goesToLiuliSystemLine() {
        setRow(
            msg(
                """{"type":"system_event","eventType":"red_packet_accepted","title":"你收下了云野的红包","emoji":"🧧","timestamp":""}""",
                kind = MessageKind.SYSTEM_EVENT_CARD,
            ),
        )
        compose.onNodeWithText("你收下了云野的红包").assertIsDisplayed()
    }

    @Test fun offlineMarkerEnd_goesToLiuliDivider_withReviewWhenSessionKnown() {
        setRow(
            msg(
                "【线下见面结束 | 时长：32 分钟 | 时间：晚上】\n见得尽兴。现在恢复正常线上聊天模式。",
                kind = MessageKind.OFFLINE_MARKER_END,
                isOfflineMode = true,
                offlineSessionId = "s1",
            ),
        )
        compose.onNodeWithText("线下见面结束 · 32 分钟").assertIsDisplayed()
        compose.onNodeWithText("· 回顾").assertIsDisplayed()
    }

    @Test fun offlineInvite_goesToLiuliOfflineInviteCard() {
        setRow(
            msg(
                """{"type":"offline_invite","location":"江边","activity":"去江边走走"}""",
                kind = MessageKind.OFFLINE_INVITE_CARD,
            ),
        )
        compose.onNodeWithText("☕ 云野 想和你一起").assertIsDisplayed()
        compose.onNodeWithText("好呀").assertIsDisplayed()
    }

    @Test fun futureMeetingProposal_goesToLiuliAppointmentCard() {
        setRow(
            msg(
                """{"type":"future_meeting_proposal","appointmentUuid":"a1","whenDisplay":"周六 15:00"}""",
                kind = MessageKind.FUTURE_MEETING_PROPOSAL_CARD,
            ),
        )
        compose.onNodeWithText("云野想和你约个时间").assertIsDisplayed()
    }

    @Test fun stickerOnly_goesToLiuliStickerStack() {
        setRow(msg("[sticker:happy]"))
        // 纯贴纸行的独有特征 = 旁戳（贴纸本体在 Robolectric 里解不出真图）。
        compose.onNodeWithText(DateFormatters.hourMinute(1_756_000_000_000L)).assertIsDisplayed()
    }
}
