package com.situ.aichat.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.offline.OfflineReturnPolicy
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.gift.DIYGiftDetailSheet
import com.situ.aichat.ui.gift.InChatGiftSheet
import com.situ.aichat.ui.meeting.FutureMeetingFormSheet
import com.situ.aichat.ui.offline.OfflineManualMeetingSheet
import com.situ.aichat.ui.redpacket.RedPacketComposerSheet
import com.situ.aichat.ui.redpacket.RedPacketDetailDialog
import com.situ.aichat.ui.sticker.StickerPickerSheet

/**
 * 聊天屏全部 sheet / 对话框的开合状态管家（审计 S1·自 [ChatScreen] 只搬不改抽出）。
 *
 * 这些弹层物理上原嵌在 bottomBar Column 里，但 Dialog / ModalBottomSheet 渲染在独立 window、与树位置无关——
 * 抽出后 ChatScreen 只负责「写开关」（面板 tile / 消息卡回调），[ChatScreenSheets] 负责「读开关 + 渲染 + 复位」。
 *
 * 存活语义：红包（E1#0 拍板）+ 三个表单类弹窗与改期目标（B2 拍板 2026-07-02）跨重建存活——填一半转屏/切深色
 * 不再弹窗消失、输入全丢（表单**内部字段**在各 sheet 文件同步升 saveable）。有意不存活（登记）：贴纸选择器
 * （无输入）、DIY/红包详情查看器（详情/确认类非表单·复杂对象须按 id 重解析，收益不成比例）。
 */
internal class ChatSheetsState {
    var showPicker by mutableStateOf(false)
    var showGiftSheet by mutableStateOf(false)
    var showRedPacketSheet by mutableStateOf(false)
    var showManualMeetingSheet by mutableStateOf(false)
    var showFutureMeetingSheet by mutableStateOf(false) // 「+」菜单「约见面」手动约未来见面表单
    var rescheduleAppointmentUuid by mutableStateOf<String?>(null) // 确认卡「换个时间」目标约定（8c 改期 sheet 消费）
    var diyDetailRecord by mutableStateOf<GiftRecordEntity?>(null)
    var redPacketDetail by mutableStateOf<RedPacketData?>(null)

    companion object {
        /** null uuid 以空串占位（listSaver 元素不可空；真 uuid 恒非空）。 */
        fun saver(): Saver<ChatSheetsState, Any> = listSaver(
            save = {
                listOf(
                    it.showRedPacketSheet, it.showGiftSheet, it.showManualMeetingSheet,
                    it.showFutureMeetingSheet, it.rescheduleAppointmentUuid ?: "",
                )
            },
            restore = { saved ->
                ChatSheetsState().apply {
                    showRedPacketSheet = saved[0] as Boolean
                    showGiftSheet = saved[1] as Boolean
                    showManualMeetingSheet = saved[2] as Boolean
                    showFutureMeetingSheet = saved[3] as Boolean
                    rescheduleAppointmentUuid = (saved[4] as String).takeIf { it.isNotEmpty() }
                }
            },
        )
    }
}

@Composable
internal fun rememberChatSheetsState(): ChatSheetsState =
    rememberSaveable(saver = ChatSheetsState.saver()) { ChatSheetsState() }

/** 聊天屏弹层渲染层（审计 S1·各块自 ChatScreen L692-794 逐字搬入，仅状态引用改经 [sheets]）。 */
@Composable
internal fun ChatScreenSheets(
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
        StickerPickerSheet(
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
        InChatGiftSheet(
            characterName = characterName,
            avatarPath = avatarPath,
            balance = coinBalance,
            onSendGift = { viewModel.sendGiftInChat(it) },
            onSendDiy = { title, content, uri, cost -> viewModel.sendDiyGift(title, content, uri, cost) },
            onDismiss = { sheets.showGiftSheet = false },
        )
    }
    if (sheets.showRedPacketSheet) {
        RedPacketComposerSheet(
            characterName = characterName,
            balance = coinBalance,
            onSend = { amount, blessing, festivalId -> viewModel.sendRedPacketInChat(amount, blessing, festivalId) },
            onDismiss = { sheets.showRedPacketSheet = false },
        )
    }
    if (sheets.showManualMeetingSheet) {
        OfflineManualMeetingSheet(
            onStart = { location, activity -> viewModel.startManualOfflineMeeting(location, activity) },
            // offline-2：未提交就取消/下滑关闭 → 通知 AI（1:1 iOS .sheet onCancel → handleMeetingCancelHint）。
            onCancel = { viewModel.handleMeetingCancelHint() },
            onDismiss = { sheets.showManualMeetingSheet = false },
        )
    }
    // 8c「约见面」：手动约未来见面（用户自填·跳确认闸门直 confirmed），与「见面」（马上见）区分。
    if (sheets.showFutureMeetingSheet) {
        FutureMeetingFormSheet(
            title = "约个见面",
            confirmLabel = "约定！",
            showPlaceActivity = true,
            onConfirm = { millis, gran, loc, act -> viewModel.startFutureMeeting(millis, gran, loc, act) },
            onDismiss = { sheets.showFutureMeetingSheet = false },
        )
    }
    // 8c 改期：确认卡「换个时间」→ 观察目标约定真理源以预填当前时间；已不存在（删/取消）则不弹。
    sheets.rescheduleAppointmentUuid?.let { uuid ->
        val appt by remember(uuid) { viewModel.observeAppointment(uuid) }.collectAsStateWithLifecycle(null)
        appt?.let { a ->
            FutureMeetingFormSheet(
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
        DIYGiftDetailSheet(record = record, onDismiss = { sheets.diyDetailRecord = null })
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
    // M16 线下异常恢复弹窗（见面中途 App 被杀/最后线下消息 >10min）：结束=finalize(USER_ABORTED)。
    // D3（2026-07-07 拍板）：继续=插「归来」hint+触发一拍带时间衔接（取代旧「仅关」）；超长离开（>3h）文案引导结束。
    if (offlineRecoveryVisible) {
        val awayMs by viewModel.offlineRecoveryAwayMs.collectAsStateWithLifecycle()
        val longAbsence = awayMs?.let(OfflineReturnPolicy::isLongAbsence) == true
        AppDialog(
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
