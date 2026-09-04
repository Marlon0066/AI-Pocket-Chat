package com.situ.aichat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppPanelIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.GlassBackdrop
import com.situ.aichat.ui.designsystem.GlassDivider
import com.situ.aichat.ui.designsystem.OnGlass
import com.situ.aichat.ui.offline.OfflineImmersiveInputView
import com.situ.aichat.ui.offline.OfflineTheater
import com.situ.aichat.ui.offline.parseOfflineThemeColor

/** C3·P2 右键变身时长（毫秒·契约 §2 动效分解表：140ms 交叉缩放·效果轴 tween 无过冲）。 */
private const val PRIMARY_MORPH_MS = 140

/** C4·P1 底对齐 2dp 视觉校正（输入排契约 §2）：44dp 胶囊在 48dp 高的 Bottom 行内上移 2dp——单行与改前逐像素一致、多行圆底正对胶囊底缘。 */
private val InputCapsuleBottomNudge = (-2).dp

/** C7·P4 「+」裸图标视觉尺寸（契约 §2 方案 A：去 40dp 底后自默认 24dp 微升平衡分量·触达区仍 48dp）。 */
private val PlusBareIconSize = 26.dp

/**
 * 一次选图上限（与朋友圈 / 日记九宫格同口径）。选完**逐张成一条消息**，
 * 由既有的发送合并等待窗自然并成一轮回复。
 */
private const val MAX_CHAT_IMAGES_PER_PICK = 9

/**
 * 聊天输入托盘 + 「+」功能面板宿主 + 弹层管家调用（审计刀C·自 [ChatScreen] bottomBar 抽出）：
 * 日历确认卡（队首）→ 沉浸输入分支 / 常规托盘（引用预览 + 语音草稿条 ↔ [「+」钮 · 输入胶囊 · 主行动钮]
 * × 录音中段）→ 面板区（高度 = max(实时键盘, 面板锁定)·布局 lambda 读 ime = 审计 P4 机制原样）→
 * [ChatScreenSheets]。右键=两态（C3·输入排契约 §3.3：有字发送/空麦克风·140ms 交叉缩放变身·「停止」
 * 形态已随合并等待窗行为退役）。键盘↔面板无缝切换契约（FABLE5_CHAT_PLUS_PANEL §3/§5）与玻璃五要素配方
 * （FABLE5_CHAT_WALLPAPER §4）机制零动；`input` 经 [onInputChange] 回写（状态归 ChatScreen·saveable 语义不变）。
 */
@Composable
internal fun ChatBottomBar(
    viewModel: ChatViewModel,
    sheets: ChatSheetsState,
    inputPanel: ChatInputPanelState,
    micPermission: MicPermissionState,
    inputFieldFocus: FocusRequester,
    panelFallbackPx: Int,
    input: String,
    onInputChange: (String) -> Unit,
    sendFlight: ChatSendFlightState,
    sendFlightGates: () -> Boolean,
    chatWallpaper: ChatWallpaper?,
    pendingCalendarActions: List<CalendarAction>,
    characterName: String,
    avatarPath: String?,
    customStickers: List<CustomStickerEntity>,
    coinBalance: Int,
    isOfflineMode: Boolean,
    offlineImmersiveInputEnabled: Boolean,
    offlineThemeColorHex: String?,
    replyTarget: MessageEntity?,
    voiceDraft: VoiceDraftState?,
    playingVoiceId: String?,
    voiceRecording: Boolean,
    voiceRecordingLevel: Float,
    voiceRecordingDurationMs: Long,
    voiceRecordingCancelling: Boolean,
    offlineRecoveryVisible: Boolean,
    /** 「聊天对话」模型是否看得懂图——决定「+」面板出不出「照片」格。 */
    chatModelHasVision: Boolean,
    onOpenStickerManagement: () -> Unit,
    reduceMotion: Boolean,
) {
    val haptics = LocalAppHaptics.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    // 引用一期 E（图纸 §3.5/§4）：带引用时点语音 / 照片 / 表情 → 亮一条「只能发文字」提示。纯瞬态局部状态，
    // 三个触发点都在本组合内；引用一没了（点 ✕ 或被发送消费）提示立即跟着消失。
    val quoteHint = rememberQuoteTextOnlyHint(replyTarget)
    // 壁纸全屏沉浸重构②：导航栏间距旧由骨架垫付提供，现 NavHost 去 consume → 本栏自管 navigationBarsPadding。
    // 键盘起时面板区高 imePanelPx=ime.exclude(navigationBars)=ime−navBar，叠本栏 navBar 间距 = ime，托盘仍贴键盘（数学恒等）。
    Column(Modifier.navigationBarsPadding()) {
        // chunk3 底部玻璃（契约 §4）：有壁纸→托盘透明、控件悬浮玻璃，按底部那块壁纸亮度自适应；无壁纸→原样。
        // A4 剧场态 chrome（§4.8·2026-07-06 全屏恒暗舞台修订）：见面态 chrome 恒深玻璃——玻璃源**恒取**舞台源
        // （不再优先亮壁纸磨砂：舞台已铺满托盘背后，亮磨砂与暗幕布割裂）、底部强制深向；非见面态逐字不动。
        val offlineStageBackdrop = OfflineTheater.rememberStageBackdrop()
        val wpFrosted = if (isOfflineMode) offlineStageBackdrop else chatWallpaper?.frosted
        val wpBottomDark = if (isOfflineMode) true else (chatWallpaper?.bottomDark == true)
        val onGlassBottom = if (wpBottomDark) OnGlass.PrimaryOnDark else OnGlass.SecondaryOnLightBottom // 审计 T1：换单源（值逐位同）
        // P5.3b 日历操作确认卡片（队首），在输入栏上方弹出。
        pendingCalendarActions.firstOrNull()?.let { action ->
            CalendarConfirmCard(
                characterName = characterName,
                action = action,
                onConfirm = { viewModel.confirmPendingCalendarAction() },
                onCancel = { viewModel.cancelPendingCalendarAction() },
            )
        }
        if (isOfflineMode && offlineImmersiveInputEnabled) {
            // M16 沉浸输入：见面中且已开启 → 四步标签输入替换普通输入栏（发送走常规 send → 用户行为块）。
            // §4.8 沉浸输入玻璃托盘：外包深玻璃（无壁纸也有 stageBackdrop 玻璃源→恒玻璃 chrome）·顶缘迎光描边与消息区分隔。
            GlassBackdrop(
                blurred = wpFrosted,
                dark = wpBottomDark,
                shape = AppShapes.inputTray,
                divider = GlassDivider.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    replyTarget?.let { target ->
                        ReplyPreview(
                            senderLabel = if (target.roleRaw == "user") "你" else characterName,
                            content = target.content,
                            onClear = { viewModel.clearReplyTarget() },
                        )
                    }
                    OfflineImmersiveInputView(
                        onSend = { viewModel.send(it) },
                        // §4.1/§4.6：传舞台调和色（与 OfflineModeView 同口径·屏内用彩一律 harmonize）。
                        themeColor = OfflineTheater.harmonize(parseOfflineThemeColor(offlineThemeColorHex)),
                    )
                }
            }
        } else {
            // Fable-5 输入托盘（契约 §3.3）：悬浮 raised 托盘（顶角 28dp·浅档极浅投影/深档顶缘 1px 描边）承载
            // 引用预览 + [+ 圆钮 · 无边框 sunken 胶囊输入区 · 44dp 陶土玫渐变主行动钮]；草稿/录音态在同托盘内换中段。
            Surface(
                color = if (wpFrosted != null) Color.Transparent else AppTheme.colors.surface.raised,
                shape = AppShapes.inputTray,
                shadowElevation = if (wpFrosted != null || AppTheme.colors.isDark) 0.dp else 3.dp,
            ) {
                Column {
                    if (wpFrosted == null && AppTheme.colors.isDark) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.surface.stroke))
                    }
                    // D-5：提示条挂引用卡**正上方**（两条同 padding 上下贴合成一组）。
                    QuoteTextOnlyHint(
                        visible = quoteHint.visible,
                        reduceMotion = reduceMotion,
                        wallpaperFrosted = wpFrosted,
                        wallpaperDark = wpBottomDark,
                    )
                    replyTarget?.let { target ->
                        MaybeTrayGlass(wpFrosted, wpBottomDark) {
                            ReplyPreview(
                                senderLabel = if (target.roleRaw == "user") "你" else characterName,
                                content = target.content,
                                onClear = { viewModel.clearReplyTarget() },
                            )
                        }
                    }
                    if (voiceDraft != null) {
                        // P13.4b 录好待发 → 草稿条替换输入栏（▶试听 / 取消 / 发送）。录制已结束、无活动手势，可整条替换。
                        MaybeTrayGlass(wpFrosted, wpBottomDark) {
                            VoiceDraftBar(
                                draft = voiceDraft,
                                isPlaying = playingVoiceId == voiceDraft.id,
                                onPlay = { viewModel.toggleVoiceDraftPlayback() },
                                onCancel = { viewModel.cancelVoiceDraft() },
                                onSend = {
                                    haptics.light() // 发送=light（契约 §2）
                                    viewModel.sendVoiceDraft()
                                },
                                onRetryTranscription = { viewModel.retryVoiceTranscription() },
                            )
                        }
                    } else {
                        // C4·P1 底对齐（输入排契约 §2）：外层/内层 Row 均 Bottom——胶囊长到多行时两侧钮贴住
                        // 胶囊底缘「站住不漂」（对照微信/Telegram）。2dp 视觉校正落在胶囊侧（见 ChatInputField
                        // 调用处 offset）：单行时内层 Row 被 48dp「+」钮撑高、44dp 胶囊沉底比现状低 2dp，
                        // 上移 2dp 后单行与改前逐像素一致、多行 44dp 圆底缘正对胶囊底缘。
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            // P13.4b 录音中：只换**中段**（+菜单+输入框 ↔ 录音浮层），右侧语音键放在 Row 固定末位、跨录音态不卸载——
                            // 否则手势 owner 随 voiceRecording 翻真被重组销毁，松手/上滑取消都收不到（致命：只能录到 60s 上限）。
                            // C5：录音浮层收齐 44dp 与胶囊同高——录音态同吃 C4 的 2dp 底对齐校正（几何与胶囊完全一致）。
                            Box(Modifier.weight(1f).offset(y = if (voiceRecording) InputCapsuleBottomNudge else 0.dp)) {
                                // 录音键盘钉位（2026-07-08·Telegram 同款）：录音时输入行**保持组合只隐身**（alpha 0）——
                                // 旧法把 ChatInputField 换出组合会丢焦点 → IME 自收 → 整条托盘（含手指正按着的麦克风键）
                                // 掉到屏底。焦点不丢=键盘原地不动，录音浮层叠画在胶囊位置上（几何同 C5）。
                                Row(
                                    verticalAlignment = Alignment.Bottom, // C4 底对齐（同外层 Row·注释见上）
                                    modifier = Modifier.alpha(if (voiceRecording) 0f else 1f),
                                ) {
                                    // chat-ui-13：次要操作收进「+」下拉菜单（1:1 iOS 加号菜单：送礼/红包/见面[线下隐]/语音通话/表情）。
                                    // chat「+」：开/关功能面板（契约 §5）。面板开时「+」转 45°成「×」+ 底色/图标转陶土（reduceMotion 直切）。
                                    val plusRotation by animateFloatAsState(
                                        targetValue = if (inputPanel.panelOpen) 45f else 0f,
                                        animationSpec = if (reduceMotion) snap() else tween(220),
                                        label = "plusRotation",
                                    )
                                    IconButton(onClick = {
                                        if (inputPanel.panelOpen) inputPanel.requestKeyboard()
                                        else inputPanel.openPanel(imeInsets.exclude(navBarInsets).getBottom(density), panelFallbackPx) // 事件时读（P4）
                                    }) {
                                        // C7·P4 方案 A 裸图标（输入排契约 §2·用户终选）：去 40dp sunken 底——胶囊成输入排唯一
                                        // 底色件更透气；图标 26dp 微升平衡失去的分量；48dp 触达区=IconButton 默认不变。
                                        // 开态 45° 转「×」+ tint 转 accent.text（不加底=最轻）；有壁纸=裸图标直接落玻璃托盘、
                                        // tint 走 OnGlass 亮度自适应（现机制·开合同色只转角）。
                                        Icon(
                                            Icons.Filled.Add,
                                            // 审计 Y3②：cd 随面板开合——读屏可预期双击效果（开=打开功能面板/合=关闭）。
                                            contentDescription = if (inputPanel.panelOpen) stringResource(R.string.a11y_close_panel) else stringResource(R.string.a11y_open_panel),
                                            tint = when {
                                                wpFrosted != null -> onGlassBottom
                                                inputPanel.panelOpen -> AppTheme.colors.accent.text
                                                else -> AppTheme.colors.text.secondary
                                            },
                                            modifier = Modifier.size(PlusBareIconSize).rotate(plusRotation),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    ChatInputField(
                                        value = input,
                                        onValueChange = onInputChange,
                                        modifier = Modifier
                                            .weight(1f)
                                            .offset(y = InputCapsuleBottomNudge) // C4 底对齐 2dp 校正（注释见外层 Row）
                                            .focusRequester(inputFieldFocus)
                                            .onFocusChanged { if (it.isFocused) inputPanel.onFieldFocused() }
                                            // M3b ④飞行起点：输入胶囊实时窗口边界（普通字段写入·零订阅开销）。
                                            .onGloballyPositioned { sendFlight.inputBounds = it.boundsInWindow() },
                                        wallpaperFrosted = wpFrosted,
                                        wallpaperDark = wpBottomDark,
                                        hidePlaceholder = sendFlight.busy,
                                        reduceMotion = reduceMotion, // C8 聚焦微提亮门控
                                    )
                                } // end 中段内层 Row（+菜单 + 输入框·录音时隐身不卸载）
                                if (voiceRecording) {
                                    // 浮层底对齐=胶囊位置（几何同 C5）；空点击拦截防误触底下隐身的「+」/输入框。
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {},
                                            ),
                                    ) {
                                        MaybeTrayGlass(wpFrosted, wpBottomDark, InputCapsuleCorner) { // T2：与输入胶囊同源
                                            VoiceRecordingOverlay(
                                                level = voiceRecordingLevel,
                                                durationMs = voiceRecordingDurationMs,
                                                cancelling = voiceRecordingCancelling,
                                            )
                                        }
                                    }
                                }
                            } // end 中段 weight Box（录音浮层叠画于输入区上）
                            Spacer(Modifier.width(8.dp))
                            // C3 右键两态（输入排契约 §3.3/§3.2-9·「停止」形态整体退役=想换说法直接说话打断[C1 行为]）：
                            // 有字=发送、空=麦克风（iOS showsSendButton 本义：empty→waveform, text→arrow）。
                            // 手势 owner 铁律不破：录音只能从空输入的麦克风态开始、录音期间 input 不变 →
                            // targetState 恒 false，AnimatedContent 绝不换装卸载 VoiceRecordButton。
                            // P2 变身：140ms 交叉缩放（效果轴 tween 无过冲·只动本钮绘制层）；reduceMotion 直切。
                            AnimatedContent(
                                targetState = input.isNotBlank(),
                                transitionSpec = {
                                    if (reduceMotion) {
                                        EnterTransition.None togetherWith ExitTransition.None
                                    } else {
                                        (fadeIn(tween(PRIMARY_MORPH_MS)) + scaleIn(tween(PRIMARY_MORPH_MS), initialScale = 0.7f)) togetherWith
                                            (fadeOut(tween(PRIMARY_MORPH_MS)) + scaleOut(tween(PRIMARY_MORPH_MS), targetScale = 0.7f))
                                    }
                                },
                                label = "chatPrimaryActionMorph",
                            ) { showSend ->
                                if (showSend) {
                                    ChatPrimaryActionButton(
                                        icon = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.a11y_send),
                                        onClick = {
                                            // 复核修（LOW）：仅发送被接受才清输入框——空文本拒绝时保留现状。
                                            // M3a ④押后清空握手（D7·契约 §4.3）：发送被受理后——闸链全开=清空押到
                                            // 目标气泡就位/200ms 超时（无缝感灵魂）；任一闸关=tryBegin 立即 commit（=现状）。
                                            val text = input
                                            if (viewModel.send(text)) {
                                                haptics.light() // chat-ui-8：发送轻触觉（≈ iOS light）
                                                sendFlight.tryBegin(text, sendFlightGates()) { onInputChange("") }
                                            }
                                        },
                                    )
                                } else {
                                    VoiceRecordButton(
                                        hasMicPermission = micPermission.granted,
                                        onRequestPermission = micPermission.request,
                                        // 引用一期 E·拦截①：带引用时按住说话不录，只弹提示（D-1「意图那一刻」）。
                                        blocked = replyTarget != null,
                                        onBlocked = { quoteHint.trigger() },
                                        onStartRecording = {
                                            haptics.medium() // 录音开始=medium（契约 §2·≈ iOS medium impact）
                                            viewModel.startVoiceRecording()
                                        },
                                        onDrag = { viewModel.updateVoiceRecordingDrag(it) },
                                        onFinish = { viewModel.finishVoiceRecording() },
                                        // C6 按压反馈（纯视觉·graphicsLayer）：随 VM 录音/取消态驱动 scale。
                                        recording = voiceRecording,
                                        cancelling = voiceRecordingCancelling,
                                        reduceMotion = reduceMotion,
                                    )
                                }
                            }
                        }
                    } // end 草稿条/输入行互斥分支
                } // end 托盘内 Column
            } // end 输入托盘 Surface
        } // end 普通输入栏分支（非沉浸输入）
        // chat「+」面板宿主（契约 §6）：底部区域高度 = max(实时键盘高度, 面板锁定高度)——替代旧 imePadding，
        // 键盘与面板轮流坐此区、输入托盘锚定不掉底；键盘态绘空(系统键盘占屏)、面板态绘功能面板。
        // 发图入口（拍板③「选完即发」）：系统 Photo Picker 多选（与朋友圈/日记同源·无存储权限·无 GMS 依赖），
        // 选完直接逐张成消息发出，不设预览确认页。
        val photoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_CHAT_IMAGES_PER_PICK),
        ) { uris -> if (uris.isNotEmpty()) viewModel.sendImages(uris) }

        val chatPanelItems = buildList {
            // 两排网格（方案 A·左对齐·见 ChatFunctionPanel）：第一排 送礼/红包/表情，第二排 见面/约见面。
            // 通话已移到顶栏右上角（仅见面外显示）。见面期间隐藏 送礼/红包/见面/约见面（礼物·红包属金路、见面里发会漏进普通
            // 聊天且打断沉浸·2026-06-21 用户拍板；「发起见面」本就不该在见面中再发）→ 此时面板只剩「表情」（已随会话打线下标记）。
            if (!isOfflineMode) {
                add(ChatPanelItem(AppPanelIcons.Gift, "送礼") { inputPanel.dismiss(reduceMotion); sheets.showGiftSheet = true })
                add(ChatPanelItem(AppPanelIcons.RedPacket, "红包") { inputPanel.dismiss(reduceMotion); sheets.showRedPacketSheet = true })
            }
            // 引用一期 E·拦截③：带引用时点「表情」不开表情面板，直接弹提示——与「照片」同层级拦，
            // 让用户先挑完表情再拒绝＝白挑一次（图纸 §4.3 对 D-1 的细化，已登记）。
            add(
                ChatPanelItem(AppPanelIcons.Sticker, "表情") {
                    inputPanel.dismiss(reduceMotion)
                    if (replyTarget != null) {
                        quoteHint.trigger()
                        return@ChatPanelItem
                    }
                    sheets.showPicker = true
                },
            )
            // 「照片」按**聊天对话模型的视觉能力**显隐（用户 2026-08-29 拍板修订原「入口常开」）：
            // 纯文本模型收到图只会回一句读不懂图的话，与其事后降级不如根本不给按钮。
            // 不随见面态隐藏——见面里给对方看张照片是合理的沉浸内容（与送礼/红包属金路、
            // 「见面」会打断沉浸的理由都不同）。
            if (chatModelHasVision) {
                add(
                    ChatPanelItem(AppPanelIcons.Photo, "照片") {
                        inputPanel.dismiss(reduceMotion)
                        // 引用一期 E·拦截②：带引用时不拉起系统选图器，只弹提示。
                        if (replyTarget != null) {
                            quoteHint.trigger()
                            return@ChatPanelItem
                        }
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }
            if (!isOfflineMode) {
                add(ChatPanelItem(AppPanelIcons.Meet, "见面") { inputPanel.dismiss(reduceMotion); sheets.showManualMeetingSheet = true })
                add(ChatPanelItem(AppPanelIcons.FutureMeet, "约见面") { inputPanel.dismiss(reduceMotion); sheets.showFutureMeetingSheet = true })
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                // 审计 P4：面板区高度在布局 lambda 里读 ime——键盘动画帧只重排此区、不重组（机制不变）。
                .layout { measurable, constraints ->
                    val h = inputPanel.regionPx(imeInsets.exclude(navBarInsets).getBottom(this))
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                },
        ) {
            if (inputPanel.panelOpen) {
                if (wpFrosted != null) {
                    // C2c 壁纸态：面板同输入托盘的毛玻璃（§4 五要素·顶缘迎光描边与输入区分隔），浮起 tile 立其上。
                    GlassBackdrop(
                        blurred = wpFrosted,
                        dark = wpBottomDark,
                        divider = GlassDivider.Top,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ChatFunctionPanel(items = chatPanelItems, modifier = Modifier.align(Alignment.TopCenter), labelColor = onGlassBottom)
                    }
                } else {
                    // 无壁纸：实底。
                    Box(Modifier.fillMaxSize().background(AppTheme.colors.surface.base)) {
                        ChatFunctionPanel(items = chatPanelItems, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
        // 审计 S1：10 个 sheet/dialog 整体搬 ChatScreenSheets.kt（Dialog/ModalBottomSheet 渲染于独立
        // window、与树位置无关）；调用点留原位 = 组合顺序/时序逐位不变。
        ChatScreenSheets(
            sheets = sheets,
            viewModel = viewModel,
            characterName = characterName,
            avatarPath = avatarPath,
            coinBalance = coinBalance,
            customStickers = customStickers,
            offlineRecoveryVisible = offlineRecoveryVisible,
            onOpenStickerManagement = onOpenStickerManagement,
        )
    }
}
