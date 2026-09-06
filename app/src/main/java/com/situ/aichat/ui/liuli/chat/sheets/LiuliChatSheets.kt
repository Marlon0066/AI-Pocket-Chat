package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.offline.OfflineReturnPolicy
import com.situ.aichat.ui.chat.ChatSheetsState
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.redpacket.RedPacketDetailDialog

/**
 * 琉璃版聊天屏弹层分派器（图纸 2026-09-05 卷二C C6b · A-16 · 照抄源 F21
 * `ui/chat/ChatScreenSheets.kt:75-179`）。
 *
 * 与暖陶 `ChatScreenSheets` **逐分支同构**：同一个 [ChatSheetsState]（`ui/chat` 的状态管家共用不改·
 * 存活语义一字不动）、同一批 VM 入口、同样的 `remember(uuid)` key、同样的声明序；只把每个分支里的
 * 那张脸换成琉璃件。
 *
 * 唯一留原样的是红包详情 [RedPacketDetailDialog]（Q-C1 用户拍板「留原样只换关闭钮」→ 复核时收敛为
 * **连关闭钮也不换**：它住 `ui/redpacket/` 锁死区，钱路零 diff 优先；登记为有意不做）。
 */
@Composable
internal fun LiuliChatSheets(
    sheets: ChatSheetsState,
    viewModel: ChatViewModel,
    characterName: String,
    avatarPath: String?,
    coinBalance: Int,
    customStickers: List<CustomStickerEntity>,
    offlineRecoveryVisible: Boolean,
    onOpenStickerManagement: () -> Unit,
) {
    if (sheets.showPicker) {
        LiuliStickerPickerSheet(
            customStickers = customStickers,
            onSelect = { viewModel.sendStickerMessage(it) },
            onManage = {
                sheets.showPicker = false
                onOpenStickerManagement()
            },
            onDismiss = { sheets.showPicker = false },
        )
    }
    if (sheets.showGiftSheet) {
        LiuliGiftSheet(
            characterName = characterName,
            avatarPath = avatarPath,
            balance = coinBalance,
            onSendGift = { viewModel.sendGiftInChat(it) },
            onSendDiy = { title, content, uri, cost -> viewModel.sendDiyGift(title, content, uri, cost) },
            onDismiss = { sheets.showGiftSheet = false },
        )
    }
    if (sheets.showRedPacketSheet) {
        LiuliRedPacketComposerSheet(
            characterName = characterName,
            balance = coinBalance,
            onSend = { amount, blessing, festivalId -> viewModel.sendRedPacketInChat(amount, blessing, festivalId) },
            onDismiss = { sheets.showRedPacketSheet = false },
        )
    }
    if (sheets.showManualMeetingSheet) {
        LiuliOfflineManualMeetingSheet(
            onStart = { location, activity -> viewModel.startManualOfflineMeeting(location, activity) },
            // offline-2：未提交就取消 / 下滑关闭 → 通知 AI（1:1 iOS `.sheet onCancel`）。
            onCancel = { viewModel.handleMeetingCancelHint() },
            onDismiss = { sheets.showManualMeetingSheet = false },
        )
    }
    if (sheets.showFutureMeetingSheet) {
        LiuliFutureMeetingFormSheet(
            title = "约个见面",
            confirmLabel = "约定！",
            showPlaceActivity = true,
            onConfirm = { millis, gran, loc, act -> viewModel.startFutureMeeting(millis, gran, loc, act) },
            onDismiss = { sheets.showFutureMeetingSheet = false },
        )
    }
    // 8c 改期：确认卡「换个时间」→ 观察目标约定真理源以预填当前时间；已不存在（删 / 取消）则不弹。
    sheets.rescheduleAppointmentUuid?.let { uuid ->
        val appt by remember(uuid) { viewModel.observeAppointment(uuid) }.collectAsStateWithLifecycle(null)
        appt?.let { a ->
            LiuliFutureMeetingFormSheet(
                title = "换个时间",
                confirmLabel = "改到这天",
                showPlaceActivity = false,
                initialMillis = a.scheduledAt,
                initialGranularity = MeetingTimeGranularity.fromRaw(a.timeGranularity),
                onConfirm = { millis, gran, _, _ -> viewModel.rescheduleAppointment(uuid, millis, gran) },
                onDismiss = { sheets.rescheduleAppointmentUuid = null },
            )
        }
    }
    sheets.diyDetailRecord?.let { record ->
        LiuliDiyGiftDetailSheet(record = record, onDismiss = { sheets.diyDetailRecord = null })
    }
    sheets.redPacketDetail?.let { data ->
        RedPacketDetailDialog(
            data = data,
            recordFlow = viewModel.observeRedPacketRecord(data.recordUUID),
            characterName = characterName,
            characterAvatarPath = avatarPath,
            onOpen = { viewModel.openRedPacket(data.recordUUID) },
            onDismiss = { sheets.redPacketDetail = null },
        )
    }
    // M16 线下异常恢复弹窗（见面中途 App 被杀 / 最后线下消息 >10min）：结束 = finalize(USER_ABORTED)。
    // D3（2026-07-07 拍板）：继续 = 插「归来」hint + 触发一拍带时间衔接；超长离开（>3h）文案引导结束。
    if (offlineRecoveryVisible) {
        val awayMs by viewModel.offlineRecoveryAwayMs.collectAsStateWithLifecycle()
        val longAbsence = awayMs?.let(OfflineReturnPolicy::isLongAbsence) == true
        LiuliDialog(
            onDismissRequest = { viewModel.dismissOfflineRecoveryPrompt() },
            title = "继续上次的见面？",
            body = if (longAbsence) {
                "离开挺久了，这次见面建议先告一段落——回忆会替你们收好。当然，也可以让 TA 陪你再待一会儿。"
            } else {
                "上次的线下见面好像被中断了。要继续这次见面，还是结束它？"
            },
            confirmText = "继续见面",
            onConfirm = { viewModel.continueMeetingFromRecovery() },
            dismissText = "结束见面",
            onDismiss = { viewModel.endMeetingFromRecovery() },
        )
    }
}
