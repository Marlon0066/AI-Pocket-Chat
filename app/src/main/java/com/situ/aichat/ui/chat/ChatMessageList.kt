package com.situ.aichat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.meeting.MeetingArrivalButton
import com.situ.aichat.ui.meeting.MeetingCountdownChip

/**
 * 常规消息列表分支（审计 S2·自 [ChatScreen] Scaffold 内容区只搬不改抽出）：
 * 「已读」派生 + 窗口化 LazyColumn（入场/情绪/弧线/波形动画记账接线 + 33 参 [MessageRow]）+ 顶部网络横幅 /
 * 约定倒数条（到点变身「出发赴约」）+ 右下回底 FAB。自带全尺寸 [Box]（原分支的 align 语义原位保留；
 * 日历 toast 仍留 ChatScreen 的外层 Box=堆叠次序不变）。动画记账集合（entryScalePlayed / emotionPlayed /
 * 不可见区间表）与滚动件由 ChatScreen 持有下传——它们的生命周期/存活语义（R4/R5）不随本次搬家改变。
 *
 * 列表**底部锚定**（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §2·Telegram 同款）：`reverseLayout=true`，
 * [listItems] 为反转序（index 0 = 最新）；键盘/面板缩放视口时最新气泡物理钉在托盘上沿，无需滚动补偿。
 */
@Composable
internal fun ChatMessageList(
    viewModel: ChatViewModel,
    sheets: ChatSheetsState,
    listState: LazyListState,
    scrollCoordinator: ChatScrollCoordinator,
    listItems: List<ChatRenderItem>,
    messages: List<MessageEntity>,
    dismissKeyboardOnDrag: NestedScrollConnection,
    playingVoiceId: String?,
    voiceProgress: () -> Float,
    reduceMotion: Boolean,
    emotionAnimationEnabled: Boolean,
    animateArrivalsSinceMillis: Long,
    entryScalePlayed: MutableSet<String>,
    emotionPlayed: MutableList<String>,
    emotionHiddenIntervals: List<LongRange>,
    actions: MessageRowActions,
    userScrollEnabled: Boolean,
    deleteArm: State<Long>,
    sendFlight: ChatSendFlightState,
    characterName: String,
    avatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    customStickers: List<CustomStickerEntity>,
    /** 助手回合进行中（VM `isSending`）：期间全列不给「重新生成」——引擎有并发门，点了必静默无效。 */
    isSending: Boolean,
    networkConnected: Boolean,
    networkStatusChanged: Boolean?,
    showScrollDown: Boolean,
    /** 约定记账当场提示在场（图纸 2026-09-06 §4.3）：并入日期胶囊抑制 + 倒数条让路（赴约钮不让）。 */
    promiseHintVisible: Boolean = false,
    wallpaper: ChatWallpaper?,
    /** VU3：当前角色仍缺可用音色 → 失败通话卡长琥珀尾巴（自愈显示门控·透传给 MessageRow）。 */
    voiceSetupNeeded: Boolean = false,
) {
    // （审计 S7 的 actions 稳定对象自本文件上提 ChatScreen——M2 沉浸菜单覆盖层与行共用同一动作面。）
    Box(Modifier.fillMaxSize()) {
        // chat-ui-5：派生「已读」用户消息集合（其后存在 assistant 消息=AI 已回 → 已读 ✓✓）。1:1 iOS 语义但无需建列/迁移：
        // messages 流在 AI 消息落库时重发 → 该 user 气泡回执自动从「送达」翻「已读」。最新一条无后续 assistant=送达 ✓。
        val readUserMessageUuids = remember(messages) {
            val set = HashSet<String>()
            var seenAssistant = false
            for (i in messages.indices.reversed()) {
                when (messages[i].roleRaw) {
                    "assistant" -> seenAssistant = true
                    "user" -> if (seenAssistant) set.add(messages[i].messageUUID)
                }
            }
            set
        }
        // 「重新生成」有效范围（2026-09-04 拍板·判据单源 RegenerableTurn，引擎删的就是它算出的那一段）：
        // 只有最后一轮的 AI 文字消息给这一项——长按更早的历史点它只会误删最后一轮（菜单里有、点了却不是
        // 那条=撒谎）；事件卡（通话记录/见面结束/红包/礼物…）遇到即停，不给也不删。`messages` 本就是可见流。
        val regenerableUuids = remember(messages) { RegenerableTurn.trailingUuids(messages) }
        // M3a/M3b ④握手+飞行：单点读（变化只失效本 lambda 一次，不订阅到每行）。
        val flightPending = sendFlight.pending
        val flightUuid = sendFlight.flightUuid
        // V9 变身抖动根治（2026-07-08·逐帧确诊）：位移动画**默认关闭**（placementSpec=null）——反转列表下
        // 打字气泡变身长高逐帧推挤上方全列，若各行挂独立位移弹簧，弹簧对连续小位移逐帧重锚必然「滞后压缩→
        // 追上回弹」（橡皮筋感=用户所报抖动）；null 时上方各行随气泡的平滑长高逐帧刚性锁步（等效 iOS SwiftUI
        // 单事务动画=位移曲线与长高曲线同一条）。**唯删除例外**：删除是一次性离散位移，无弹簧会瞬跳——
        // 删除由用户点击发起，[deleteArm] 在点击回调里**先于下一帧**同步武装 600ms 弹簧窗（确定性预武装，
        // 事后检测会晚一帧、恰好错过收拢那一帧）。打断丢弃占位等罕见移除瞬时收拢=有意接受。
        var removalSpringWindow by remember { mutableStateOf(false) }
        LaunchedEffect(deleteArm.value) {
            if (deleteArm.value > 0L) {
                removalSpringWindow = true
                delay(600)
                removalSpringWindow = false
            }
        }
        val placementSpec: FiniteAnimationSpec<IntOffset>? =
            if (removalSpringWindow) AppMotion.messageReceiveSpring(IntOffset.VisibilityThreshold) else null
        LazyColumn(
            state = listState,
            // 底部锚定（契约 REVERSE_LIST §2 ①）：index 0=最新钉在底边，视口缩放（键盘/面板）天然钉底。
            // ⚠️ 本 LazyColumn 的四参数（reverseLayout/Arrangement.Top/bottom 16dp/key）被
            // ReversedChatListBehaviorTest 按同配置钉行为——改任一参数须同步该测试（T5 复核 🔵4 互指）。
            reverseLayout = true,
            // 反转默认排布是 Bottom（短内容贴输入栏）；显式锁 Top 保「消息铺不满一屏时贴顶」现状零变
            // （契约 §2.1·foundation 1.9.0 calculateItemsOffsets 双翻转=arrange 坐标即最终视觉坐标）。
            verticalArrangement = Arrangement.Top,
            // M2 沉浸菜单：菜单开着时冻结用户滚动（Telegram stopScroll+禁滚），关闭即恢复。
            userScrollEnabled = userScrollEnabled,
            modifier = Modifier.nestedScroll(dismissKeyboardOnDrag).fillMaxSize().padding(horizontal = 12.dp),
            // chat-ui-9：iOS messageGap=0，间距全部作为每条的「上间距」逐条施加，故列表本身不留统一缝隙。
            // Fable-5 底部呼吸留白：最后一条消息/打字气泡与输入栏之间留 16dp（=spacing xl·明显宽过段间 12dp）。
            // 反转布局下 bottom padding 即 beforeContentPadding（LazyList.kt L233-239·契约 §3#2）：视觉仍在
            // 底部，snapTo(0)/animateTo(0) 落点天然停在留白之上（整体抬 16dp）；打字行同为 index 0 一并生效。
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(
                listItems,
                key = { it.key },
                // P2-lists#3：同类项按消息类型声明 contentType，让 LazyColumn 同类复用。
                // （2026-07-08 V8：时间分隔行退役——气泡自带内嵌时间戳+滚动浮动日期胶囊管翻史定位，
                // 列表成员只余消息一种，「分隔行与气泡动画不同步」一类跳动随之根除。）
                contentType = {
                    when (it) {
                        is ChatRenderItem.Message -> it.entity.messageKindRaw
                    }
                },
            ) { renderItem ->
                when (renderItem) {
                    is ChatRenderItem.Message -> {
                        val msg = renderItem.entity
                        val voicePlaying = playingVoiceId == msg.messageUUID
                        // P1-13：消息入场/位移动效——新到达淡入（用户/AI 各自弹簧=iOS MessageSpring.send/receive）+ 位移弹簧；
                        // 用户气泡叠 0.985→1 缩放（trailing 锚）。占位（isContentRevealed=false）淡入显点；到内容时**同 key 持续**
                        // 不再触发新到达淡入（C3 接气泡内点↔字交叉淡入做平滑变身）。
                        val newArrival = msg.timestamp >= animateArrivalsSinceMillis
                        // M3b ④飞行行（契约 §4.2）：握手匹配期（首帧起=防落地前闪现）+ 飞行期，该行由覆盖层
                        // 代画——自身 alpha 0、入场动画停播（fadeIn/缩放·记账在就位帧已标），落地帧交还。
                        val flightRow = (flightPending != null && flightPending.matches(msg)) ||
                            flightUuid == msg.messageUUID
                        // P1-5：情绪入场门控（=iOS shouldAnimate EmotionAnimationModifier.swift:78-86 + §10#11）：可见期到达 /
                        // 开屏 <1h 历史播；不可见期插入（timestamp 落任一已记录不可见区间）永不播也不标记（防回屏爆串）。
                        val emotionPlay = !reduceMotion && emotionAnimationEnabled &&
                            msg.emotionTag != null && msg.messageUUID !in emotionPlayed &&
                            msg.timestamp > System.currentTimeMillis() - 3_600_000L &&
                            emotionHiddenIntervals.none { msg.timestamp in it }
                        // Chunk 2：礼物/红包卡新到达走「弧线落位」（替代用户气泡 0.985 微缩·避免双重缩放）。
                        val isGiftOrRedPacket = msg.messageKindRaw == MessageKind.GIFT_CARD.raw ||
                            msg.messageKindRaw == MessageKind.RED_PACKET.raw
                        // Chunk 3：语音消息新到达时波形从左到右依次长出（只播一次·复用 entryScalePlayed 记账）。
                        val voiceCascadePlay = newArrival && msg.isVoiceMessage &&
                            msg.messageUUID !in entryScalePlayed
                        val rowModifier = if (reduceMotion) {
                            Modifier
                        } else {
                            Modifier
                                .animateItem(
                                    fadeInSpec = if (newArrival && !flightRow) {
                                        if (msg.roleRaw == "user") AppMotion.messageSendSpring() else AppMotion.messageReceiveSpring()
                                    } else {
                                        null
                                    },
                                    // V9：位移动画走条件 spec（声明处见上）——平时 null=与变身长高刚性锁步（治抖动），
                                    // 删除窗内弹簧=收拢观感保留。
                                    placementSpec = placementSpec,
                                    // 删除问题①根因：animateItem 的「消失淡出」在本表里会把已移除的项留在叠层里按 alpha=1
                                    // 画着不释放 = 半透明/不透明残影（删后不消失、退出再进才没）。fadeOutSpec=null → 移除即
                                    // 释放、不进消失叠层，残影根除；存留消息的收拢由删除窗内的 placementSpec 平滑承担。
                                    fadeOutSpec = null,
                                )
                                .userBubbleEntryScale(
                                    play = newArrival && !flightRow && msg.roleRaw == "user" && !isGiftOrRedPacket &&
                                        msg.messageUUID !in entryScalePlayed,
                                    onPlayed = { entryScalePlayed.add(msg.messageUUID) },
                                )
                                .giftRedPacketArcEntry(
                                    play = newArrival && isGiftOrRedPacket && msg.messageUUID !in entryScalePlayed,
                                    fromUser = msg.roleRaw == "user",
                                    onPlayed = { entryScalePlayed.add(msg.messageUUID) },
                                )
                        }
                        Box(
                            rowModifier
                                .emotionBubbleEntry(
                                    emotionTag = msg.emotionTag,
                                    play = emotionPlay,
                                    onPlayed = { emotionPlayed.add(msg.messageUUID) },
                                )
                                // M3b：飞行期整行由覆盖层代画（含时间戳·落地帧像素一致交还）。
                                .then(if (flightRow) Modifier.alpha(0f) else Modifier),
                        ) {
                            MessageRow(
                                message = msg,
                                topPadding = renderItem.topPadding,
                                characterName = characterName,
                                avatarPath = avatarPath,
                                userName = userName,
                                userAvatarPath = userAvatarPath,
                                customStickers = customStickers,
                                isVoicePlaying = voicePlaying,
                                // 审计 P3：只有播放中那一行拿到真 progress lambda；其余拿零常量（无快照依赖=绝不失效）。
                                voiceProgress = if (voicePlaying) voiceProgress else ZeroProgress,
                                actions = actions,
                                canRegenerate = RegenerableTurn.canRegenerate(msg.messageUUID, regenerableUuids, isSending),
                                deliveryRead = if (msg.roleRaw == "user") msg.messageUUID in readUserMessageUuids else null,
                                voiceCascadePlay = voiceCascadePlay,
                                flightTracking = flightRow,
                                voiceSetupNeeded = voiceSetupNeeded,
                                dividerEntryAnimation = newArrival, // 卷三 V3：离场分隔条落成只在新到达那一刻播
                            )
                        }
                    }
                }
            }
        }

        // 等待期倒数条/赴约钮状态提前收集：浮动日期胶囊要在横幅声明序**之前**（=横幅压其上），且需知晓
        // 「顶部横幅是否在场」并入抑制（防双 TopCenter 重叠）——两个 AnimatedVisibility 块位置不动，仅收集上移。
        val arrivalAppt by viewModel.arrivalAppointment.collectAsStateWithLifecycle()
        val countdownAppt by viewModel.nextCountdownAppointment.collectAsStateWithLifecycle()
        val topBannerVisible = !networkConnected || networkStatusChanged == true ||
            arrivalAppt != null || countdownAppt != null || promiseHintVisible

        // ② 滚动浮动日期胶囊（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL §2·M1）：只认用户拖动/fling，
        // 停滚 500ms 后淡出；最早项可见或顶部横幅在场时熄灭。皮肤随壁纸双态（玻璃药丸/raised 半透）。
        FloatingDateCapsule(
            listState = listState,
            listItems = listItems,
            topBannerVisible = topBannerVisible,
            wallpaperFrosted = wallpaper?.frosted,
            wallpaperDark = wallpaper?.topDark == true,
            hasWallpaper = wallpaper != null,
        )

        // P0-2 网络状态横幅（断开红条常驻 / 恢复绿条 2s）：浮现在消息区顶部，对齐 iOS NetworkStatusBannerView。
        AnimatedVisibility(
            visible = !networkConnected || networkStatusChanged == true,
            modifier = Modifier.align(Alignment.TopCenter),
            // 审计 Y5③：减弱动画 → 直显直隐（else 分支=默认值原样，下同）。
            enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
        ) {
            NetworkStatusBanner(
                connected = networkConnected,
                recovered = networkStatusChanged == true,
                onRecoveredShown = { viewModel.clearNetworkStatusChange() },
            )
        }

        // 等待期倒数小条（Phase 9）/ 到点「出发赴约」按钮（Phase 10·10d·过审 mockup meetup_arrival_button_morph）：
        // 同一顶部槽位——到点优先显变身按钮（点击进沉浸赴约），否则显倒数小条（查看/改期/取消）。
        // （arrivalAppt/countdownAppt 收集已上移至胶囊抑制计算处·M1 仅位置移动。）
        AnimatedVisibility(
            // 约定提示在场时倒数条让路（持久信息·4 秒后回来）；「出发赴约」是关键动作，永不让路。
            visible = (arrivalAppt != null || countdownAppt != null) && !(promiseHintVisible && arrivalAppt == null),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
        ) {
            val arrival = arrivalAppt
            val countdown = countdownAppt
            when {
                arrival != null -> MeetingArrivalButton(onArrive = { viewModel.arriveAtAppointment(arrival.uuid) })
                countdown != null -> MeetingCountdownChip(
                    appt = countdown,
                    characterName = characterName,
                    onReschedule = { sheets.rescheduleAppointmentUuid = countdown.uuid },
                    onCancel = { viewModel.cancelAppointment(countdown.uuid) },
                )
            }
        }

        AnimatedVisibility(
            visible = showScrollDown,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
        ) {
            FloatingActionButton(
                onClick = { if (listItems.isNotEmpty()) scrollCoordinator.stickToBottom(animate = !reduceMotion) },
                shape = CircleShape,
                containerColor = AppTheme.colors.surface.raised,
                contentColor = AppTheme.colors.accent.text,
            ) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.a11y_scroll_to_bottom))
            }
        }
    }
}

/** 非播放行的进度常量（审计 P3）：不触任何快照状态 → 行绝不因播放 tick 失效。 */
private val ZeroProgress: () -> Float = { 0f }
