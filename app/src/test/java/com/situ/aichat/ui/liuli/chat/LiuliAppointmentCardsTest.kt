package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.FutureMeetingChangeData
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-7 琉璃约见面 / 改期卡（图纸 2026-09-05 卷二C §7 · E8）：确认卡三态的钮 / 回执存在性与各回调恰一次，
 * 改期卡 `isCancel` 两分支的文案与**安全默认**（取消分支主钮是「保留约定」）。
 *
 * 期望值从 F11 的状态机规格独立反推：proposed → 三钮；confirmed / honored / missed → 「已约定」；
 * cancelled → 「先不约了」；status=null 退消息快照兜底（declined 快照 → 婉拒，其余 → 已约定）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliAppointmentCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var accepts = 0
    private var reschedules = 0
    private var declines = 0
    private var applies = 0
    private var keeps = 0

    private fun proposal(responded: String? = null) = FutureMeetingProposalData(
        appointmentUuid = "a1",
        whenDisplay = "周六 15:00",
        location = "老地方",
        activity = "喝茶",
        invitation = "带你去看那家茶叶铺",
        responded = responded,
    )

    private fun setProposal(status: MeetingStatus?, responded: String? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliAppointmentProposalCard(
                    data = proposal(responded),
                    status = status,
                    characterName = "云野",
                    onAccept = { accepts++ },
                    onReschedule = { reschedules++ },
                    onDecline = { declines++ },
                )
            }
        }
    }

    private fun setChange(kind: String, responded: String? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliAppointmentChangeCard(
                    data = FutureMeetingChangeData(
                        appointmentUuid = "a1",
                        changeKind = kind,
                        oldWhenDisplay = "周六 15:00",
                        newWhenDisplay = "周六 16:00",
                        reason = "临时有点事",
                        responded = responded,
                    ),
                    characterName = "云野",
                    onApply = { applies++ },
                    onKeep = { keeps++ },
                )
            }
        }
    }

    // ── 态解析纯函数（重打值对表） ──────────────────────────────────────────────

    @Test fun stateResolution_followsTruthSource() {
        assertEquals(LiuliProposalCardState.PENDING, liuliResolveProposalState(MeetingStatus.PROPOSED, null))
        assertEquals(LiuliProposalCardState.AGREED, liuliResolveProposalState(MeetingStatus.CONFIRMED, null))
        assertEquals(LiuliProposalCardState.AGREED, liuliResolveProposalState(MeetingStatus.HONORED, null))
        assertEquals(LiuliProposalCardState.AGREED, liuliResolveProposalState(MeetingStatus.MISSED, null))
        assertEquals(LiuliProposalCardState.DECLINED, liuliResolveProposalState(MeetingStatus.CANCELLED, null))
    }

    @Test fun stateResolution_fallsBackToSnapshot_whenAppointmentGone() {
        assertEquals(
            LiuliProposalCardState.DECLINED,
            liuliResolveProposalState(null, FutureMeetingProposalData.RESPONDED_DECLINED),
        )
        assertEquals(LiuliProposalCardState.AGREED, liuliResolveProposalState(null, null))
    }

    // ── 确认卡三态 ────────────────────────────────────────────────────────────

    @Test fun pending_showsThreeActions_andTitleWithName() {
        setProposal(MeetingStatus.PROPOSED)
        compose.onNodeWithText("云野想和你约个时间").assertIsDisplayed()
        compose.onNodeWithText("周六 15:00").assertIsDisplayed()
        compose.onNodeWithText("好呀").assertIsDisplayed()
        compose.onNodeWithText("换个时间").assertIsDisplayed()
        compose.onNodeWithText("先不约").assertIsDisplayed()
    }

    @Test fun pending_eachActionFiresExactlyOnce() {
        setProposal(MeetingStatus.PROPOSED)
        compose.onNodeWithText("好呀").performClick()
        compose.onNodeWithText("换个时间").performClick()
        compose.onNodeWithText("先不约").performClick()
        assertEquals(1, accepts)
        assertEquals(1, reschedules)
        assertEquals(1, declines)
    }

    @Test fun agreed_replacesButtonsWithReceipt() {
        setProposal(MeetingStatus.CONFIRMED)
        compose.onNodeWithText("已约定").assertIsDisplayed()
        compose.onNodeWithText("好呀").assertDoesNotExist()
    }

    @Test fun declined_showsDeclinedReceipt() {
        setProposal(MeetingStatus.CANCELLED)
        compose.onNodeWithText("先不约了").assertIsDisplayed()
        compose.onNodeWithText("换个时间").assertDoesNotExist()
    }

    // ── 改期 / 取消两分支 ──────────────────────────────────────────────────────

    @Test fun reschedule_showsOldAndNewTime_withApplyAsPrimary() {
        setChange(FutureMeetingChangeData.KIND_RESCHEDULE)
        compose.onNodeWithText("云野想把约定改个时间").assertIsDisplayed()
        compose.onNodeWithText("周六 15:00").assertIsDisplayed()
        compose.onNodeWithText("周六 16:00").assertIsDisplayed()
        compose.onNodeWithText("好，改").performClick()
        assertEquals(1, applies)
        compose.onNodeWithText("还是原来的").performClick()
        assertEquals(1, keeps)
    }

    /** 安全默认（F11）：取消分支里「保留约定」才是主行动，「取消约定」是弱化文字钮。 */
    @Test fun cancel_keepsAppointmentByDefault() {
        setChange(FutureMeetingChangeData.KIND_CANCEL)
        compose.onNodeWithText("云野想取消这次约定").assertIsDisplayed()
        compose.onNodeWithText("保留约定").assertIsDisplayed()
        compose.onNodeWithText("取消约定").assertIsDisplayed()
        compose.onNodeWithText("好，改").assertDoesNotExist()
        compose.onNodeWithText("保留约定").performClick()
        assertEquals(1, keeps)
    }

    @Test fun changeReceipts_coverAllThreeOutcomes() {
        setChange(FutureMeetingChangeData.KIND_RESCHEDULE, FutureMeetingChangeData.RESPONDED_APPLIED)
        compose.onNodeWithText("已改期 · 周六 16:00").assertIsDisplayed()
        compose.onNodeWithText("好，改").assertDoesNotExist()
    }

    @Test fun cancelApplied_showsCancelledReceipt() {
        setChange(FutureMeetingChangeData.KIND_CANCEL, FutureMeetingChangeData.RESPONDED_APPLIED)
        compose.onNodeWithText("约定已取消").assertIsDisplayed()
    }

    @Test fun kept_showsKeptReceipt() {
        setChange(FutureMeetingChangeData.KIND_RESCHEDULE, FutureMeetingChangeData.RESPONDED_KEPT)
        compose.onNodeWithText("仍按原约定").assertIsDisplayed()
    }
}
