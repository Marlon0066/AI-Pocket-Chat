package com.situ.aichat.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.WallpaperBlur
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * ③ 长按消息沉浸菜单（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL.md §3·M2·拍板 D4=毛玻璃+压暗、D5=气泡
 * 原味不动）。机制照 Telegram 考古：长按 → 整屏压暗 20%（320ms EaseOutQuint），被按气泡保持清晰「浮」于
 * 暗层之上（零位移零缩放=纯换层），菜单卷帘生长+菜单项级联错峰（150+16×n ms·cascade 波长 4）；收起
 * 220ms 上浮消失、**暗层 320ms 殿后**；点空白/返回键/选中项三路同一收场。
 *
 * 毛玻璃实现（D4·minSdk 29 无 RenderEffect）：长按瞬间 [PixelCopy] 对窗口拍一次快照 → 复用
 * [WallpaperBlur.frost]（降采样+盒模糊·一次性毫秒级）→ 放大铺回。快照同时充当「冻结画面」（Telegram 模糊
 * 档同为冻结位图）：菜单期间 AI 递送/列表变化不扰动画面，收场淡出时与实况交叉淡化。快照失败 → 优雅退
 * 纯压暗档（Telegram 原味 A），气泡浮起层缺席仅损失层级对比。
 *
 * 「只换壳」冻结：菜单 5 项动作的文案/图标/语义色/显示条件与旧 DropdownMenu **逐字一致**（见
 * [immersiveMenuActions]·以 ChatMessageRow 原实现为准）；回调仍走 [MessageRowActions] 零签名变化。
 */

// ---- Telegram 考古参数（出处见契约 §3.1） ----
private const val SCRIM_ALPHA = 0.2f
private const val SCRIM_FADE_MS = 320
private const val MENU_CLOSE_MS = 220
private const val MENU_APPEAR_BASE_MS = 150
private const val MENU_APPEAR_PER_ITEM_MS = 16
/**
 * 项级联波长（Telegram `AndroidUtilities.cascade` 的 waveLength）：窗口=wave/n、起点按序错峰；
 * **wave ≥ n 时窗口钳到 1 = 全项同步**（错峰消失）。2026-09-04 由 4f 调 3f：菜单最大项数随「改成邀约」
 * 删除从 5 掉到 4，4f 恰好踩上退化线——独立复核揪出「删一个菜单项把入场级联一并清零」，用户拍板恢复。
 * 现 n=4（AI 最后一轮的纯文字）：窗口 0.75、起点 0/0.0625/0.125/0.1875 → 首尾差 ≈40ms（原 5 项 4f 时
 * ≈37ms，观感等同）。**n≤3 仍全同步**——2026-09-04 两次收紧（重新生成限最后一轮、引用限纯文字）后
 * 落在这一档的不只是用户消息：**非最后一轮的 AI 消息（长按的大头）也是 3 项**、用户图片消息只有 2 项。
 * 即真机上只有「最后一轮」看得到波浪感，翻历史长按都是整卡同步淡入（复核 R2 🟡-2 如实登记·非缺陷，
 * 但若观感上要救，须再降 wave 或改公式）。**改动作项数时必须回来重算这个常量。**
 */
private const val CASCADE_WAVE = 3f
private const val A11Y_FOCUS_DELAY_MS = 420L
private val MenuItemRise = 6.dp
private val MenuCloseRise = 5.dp
private val MenuMinWidth = 180.dp

/** 菜单卡宽度上限（T4 定稿修：卡片=内容自适应小卡·绝不铺满屏宽——过审 mockup 口径）。 */
private val MenuMaxWidth = 260.dp

/**
 * 快照毛玻璃专用参数（T4 定稿修·调参单点）：壁纸磨砂的默认参数（260px+r16×3）为照片调制，糊整屏聊天
 * 画面会把消息糊成虚无；此处取轻配比——背后气泡轮廓隐约可辨（Telegram 式「仍在聊天里」的沉浸感）。
 */
private const val SNAPSHOT_FROST_EDGE = 540
private const val SNAPSHOT_FROST_RADIUS = 8
private const val SNAPSHOT_FROST_PASSES = 2
private val MenuScreenMargin = 6.dp
private val MenuBubbleGap = 8.dp
private val MenuItemHeight = 48.dp
private val MenuHairline = 0.75.dp

/** 沉浸菜单开合状态（ChatScreen 持有·瞬态不入 saveable=转屏/进程死亡即关，Telegram 同）。 */
@Stable
internal class ChatImmersiveMenuState {
    var target by mutableStateOf<MessageEntity?>(null)
        private set
    var bubbleBounds by mutableStateOf(Rect.Zero)
        private set
    var snapshot by mutableStateOf<ImageBitmap?>(null)
        private set
    var frosted by mutableStateOf<ImageBitmap?>(null)
        private set
    var closing by mutableStateOf(false)
        private set
    /** 目标消息是否落在「重新生成」有效范围内——长按那一刻由行上报（判据单源见 [RegenerableTurn]）。 */
    var canRegenerate by mutableStateOf(false)
        private set

    val isOpen: Boolean get() = target != null

    fun open(
        message: MessageEntity,
        bounds: Rect,
        snapshot: ImageBitmap?,
        frosted: ImageBitmap?,
        canRegenerate: Boolean,
    ) {
        this.bubbleBounds = bounds
        this.snapshot = snapshot
        this.frosted = frosted
        this.closing = false
        this.canRegenerate = canRegenerate
        this.target = message
    }

    /** 进入收场（幂等）；true=本次调用发起收场。 */
    fun beginClose(): Boolean {
        if (target == null || closing) return false
        closing = true
        return true
    }

    fun dismissNow() {
        target = null
        snapshot = null
        frosted = null
        closing = false
        canRegenerate = false
    }
}

/** 菜单动作枚举（壳换、动作面冻结）。 */
internal enum class ImmersiveMenuAction { COPY, SAVE_IMAGE, QUOTE, REGENERATE, DELETE }

/**
 * 动作显示条件——除 2026-09-04 三项用户拍板变更外，自旧 DropdownMenu（ChatMessageRow）**逐字冻结**：
 * 复制/删除=所有可开菜单的消息；重新生成=AI 消息**且落在最后一轮**（[canRegenerate]）；
 * 引用=**正文有话可引的气泡**（[canQuote]·判据单源 [messageCanBeQuoted]）。
 *
 * 2026-09-04 变更：① 原「改成邀约」整条去掉——与「+ 发起见面」表单完全重复、还多一步点接受，进见面已有
 * 「角色自发邀约卡 / + 发起见面 / 约未来见面」三条正门；② 重新生成由「仅 AI」收紧为「AI 且落在最后一轮」；
 * ③ 引用由「所有消息」收紧为「仅纯文字」（理由见 [messageCanBeQuoted]）；
 * 2026-09-04 引用一期又放开语音（转写到位的）与表情——喂 LLM 那侧已接住它们，详见同函数 KDoc。
 */
internal fun immersiveMenuActions(
    isUser: Boolean,
    /** 图片消息（PLAIN_TEXT + 侧车 imageRelativePath）：换掉「复制」、给「保存到相册」（契约 §B7）。 */
    hasImage: Boolean = false,
    /**
     * 本条是否落在「重新生成」的有效范围内（2026-09-04 拍板·治「菜单里有、点了没反应」）：引擎删的是
     * **末尾连续 assistant 段**并重跑整轮（AssistantTurnController.regenerate），长按更早的历史消息点它
     * 只会误删最后一轮；回合进行中点它又被并发门静默挡下。两种情况都不给这一项——菜单里出现=点了一定有效。
     * 判据单源 [RegenerableTurn]（× 非生成中），由 ChatMessageList 逐行算好传入——引擎删的就是它算出的那一段。
     */
    canRegenerate: Boolean = false,
    /**
     * 本条是否可被引用（2026-09-04 拍板·判据单源 [messageCanBeQuoted]）：正文有话可引的气泡才给这一项。
     *
     * **有意不给默认值**（复核 R1 🟡-2）：同函数另两参的默认都是限制性的（`hasImage=false` / `canRegenerate=false`），
     * 而「可引用」的宽松默认会让将来新增的调用点**静默漏掉门控且全套测试仍绿**。去掉默认 = 让编译器逼每个
     * 调用点表态，比补一条 T2 更硬。
     */
    canQuote: Boolean,
): List<ImmersiveMenuAction> = buildList {
    // 图片消息不给「复制」——它的正文是内部哨兵 `[图片]`，复制过去是三个没用的字符。
    if (hasImage) add(ImmersiveMenuAction.SAVE_IMAGE) else add(ImmersiveMenuAction.COPY)
    if (canQuote) add(ImmersiveMenuAction.QUOTE)
    if (!isUser && canRegenerate) add(ImmersiveMenuAction.REGENERATE)
    add(ImmersiveMenuAction.DELETE)
}

/**
 * 「可引用」判据（纯函数·T1·**长按菜单 / 右滑引用 / 读屏动作面三路共用单源**）：**正文有话可引**的气泡
 * 才给引用——纯文字、纯贴纸、文字+贴纸混合、以及**转写已到位**的语音，都算。
 *
 * 排除项与理由（图纸 2026-09-04 §3.3）：
 * - **图片 / 一切非 PLAIN_TEXT 卡片**（日程卡·红包·礼物·邀约·约定·变更·系统事件·通话记录·线下结束）：
 *   图片正文是内部哨兵 `[图片]`、卡片正文是原始 JSON，托盘预览 [ReplyPreview] 直读 `content` 会把它们
 *   照原样显给用户。**多模态引用整体挂起、将来单独立项**（重开时三处口径一并设计：预览脱敏走
 *   [MessagePreviewText]、被引用媒体要不要重新挂进 prompt）。
 * - **占位转写的语音**（STT 未完成 / 失败，正文 = `[语音消息]` / `[Voice Message]`）：占位不是内容，
 *   引用过去零信息。判据单源 [com.situ.aichat.prompt.PromptBuilder.isVoicePlaceholderTranscript]。
 * - **空白正文**：流式占位气泡（`ChatMessageGrouping` 合成·content=""·未落库）引用过去是一条不存在的消息。
 *
 * 2026-09-04 引用一期放开语音 + 表情（此前二者与图片/卡片一起被一刀切禁）：喂 LLM 那侧此时已经接住了它们——
 * 语音的转写本就是正文，表情经引用行注入 → `StickerService.convertStickerTagsToDescription` 变成
 * `[非语言情绪：…]`（图纸 §0.2 决策三），引用它们不再产生怪结果。
 */
internal fun messageCanBeQuoted(message: MessageEntity): Boolean {
    if (MessageKind.fromRaw(message.messageKindRaw) != MessageKind.PLAIN_TEXT) return false
    if (message.imageRelativePath != null) return false
    if (message.content.isBlank()) return false
    // 语音：转写就是正文，可引用；但占位转写没有信息量，不给。
    if (message.isVoiceMessage) return !PromptBuilder.isVoicePlaceholderTranscript(message.content)
    return true
}


/**
 * 复制文案按类型清洗（自 ChatMessageRow **原样搬迁**·口径冻结）：日程卡剥 [#E1] 标签；结构化卡（礼物/
 * 红包/邀约…）取人话预览、绝不把原始 JSON（含刻意隐藏的红包金额）塞进剪贴板；普通文本原样。
 */
internal fun messageCopyText(message: MessageEntity): String {
    val kind = MessageKind.fromRaw(message.messageKindRaw)
    return when {
        kind == MessageKind.SCHEDULE_CARD -> CalendarItemParser.stripCalendarRefs(message.content).trim()
        kind.isStructuredCard -> MessagePreviewText.forMessage(message)
        else -> message.content
    }
}

/** 动作文案（与旧 DropdownMenu **逐字一致**·菜单卡与行级 customActions 共用单源）。 */
internal fun immersiveMenuActionLabel(action: ImmersiveMenuAction): String = when (action) {
    ImmersiveMenuAction.COPY -> "复制"
    ImmersiveMenuAction.SAVE_IMAGE -> "保存到相册"
    ImmersiveMenuAction.QUOTE -> "引用"
    ImmersiveMenuAction.REGENERATE -> "重新生成"
    ImmersiveMenuAction.DELETE -> "删除"
}

/** 菜单项级联进度（Telegram AndroidUtilities.cascade 等价式·波长 [wave]）：窗口=wave/count、起点按序错峰。 */
internal fun cascadeProgress(t: Float, index: Int, count: Int, wave: Float = CASCADE_WAVE): Float {
    if (count <= 0) return 1f
    val window = min(1f, wave / count)
    val start = (index.toFloat() / count) * (1f - window)
    if (window <= 0f) return 1f
    return ((t - start) / window).coerceIn(0f, 1f)
}

/**
 * 菜单定位（纯函数·T1）：水平贴气泡对齐缘（用户=右缘对齐、AI=左缘），钳 [marginPx]；垂直默认气泡下方
 * [gapPx]，放不下翻到上方，仍放不下（超长气泡）钳屏内。
 */
internal fun immersiveMenuOffset(
    bubble: Rect,
    menuW: Int,
    menuH: Int,
    screenW: Int,
    screenH: Int,
    alignEnd: Boolean,
    marginPx: Int,
    gapPx: Int,
): IntOffset {
    val maxX = (screenW - marginPx - menuW).coerceAtLeast(marginPx)
    val x = (if (alignEnd) bubble.right - menuW else bubble.left).roundToInt().coerceIn(marginPx, maxX)
    var y = (bubble.bottom + gapPx).roundToInt()
    if (y + menuH > screenH - gapPx) y = (bubble.top - gapPx - menuH).roundToInt()
    val maxY = (screenH - gapPx - menuH).coerceAtLeast(gapPx)
    return IntOffset(x, y.coerceIn(gapPx, maxY))
}

/**
 * 长按瞬间的「冻结快照 + 一次性毛玻璃」采集：[PixelCopy] 拍窗口（本屏 view 区域）→ [WallpaperBlur.frost]
 * 出小磨砂图。任一步失败返回 null（调用方退纯压暗档）。同时返回 view 在窗口内的偏移（换算气泡坐标用）。
 */
internal suspend fun captureImmersiveBackdrop(view: View): ImmersiveBackdrop? {
    val window = view.context.findWindow() ?: return null
    if (view.width <= 0 || view.height <= 0) return null
    val loc = IntArray(2).also(view::getLocationInWindow)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val ok = suspendCancellableCoroutine { cont ->
        PixelCopy.request(
            window,
            android.graphics.Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height),
            bitmap,
            { result -> cont.resume(result == PixelCopy.SUCCESS) },
            Handler(Looper.getMainLooper()),
        )
    }
    if (!ok) {
        bitmap.recycle()
        return null
    }
    val frosted = withContext(Dispatchers.Default) {
        WallpaperBlur.frost(bitmap, targetEdge = SNAPSHOT_FROST_EDGE, radius = SNAPSHOT_FROST_RADIUS, passes = SNAPSHOT_FROST_PASSES)
    }
    return ImmersiveBackdrop(
        snapshot = bitmap.asImageBitmap(),
        frosted = frosted.asImageBitmap(),
        viewOffsetInWindow = IntOffset(loc[0], loc[1]),
    )
}

internal class ImmersiveBackdrop(
    val snapshot: ImageBitmap,
    val frosted: ImageBitmap,
    val viewOffsetInWindow: IntOffset,
)

private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}

/** 沉浸菜单覆盖层（挂 ChatScreen 外层 Box 最上·盖顶栏/输入栏/系统栏区域=Telegram 整屏 scrim）。 */
@Composable
internal fun ChatImmersiveMenuOverlay(
    state: ChatImmersiveMenuState,
    actions: MessageRowActions,
    reduceMotion: Boolean,
) {
    val message = state.target ?: return
    val entries = remember(message.messageUUID, state.canRegenerate) {
        immersiveMenuActions(
            isUser = message.roleRaw == "user",
            hasImage = message.imageRelativePath != null,
            canRegenerate = state.canRegenerate,
            canQuote = messageCanBeQuoted(message),
        )
    }

    // 进出场三驱动（按目标消息 key=换目标重置）：压暗/毛玻璃 320ms EaseOutQuint；菜单卷帘+级联
    // 150+16n ms；收起 220ms（暗层 320ms 殿后=Telegram 质感关键帧）。
    val overlayProgress = remember(message.messageUUID) { Animatable(0f) }
    val menuAppear = remember(message.messageUUID) { Animatable(0f) }
    val closeShift = remember(message.messageUUID) { Animatable(0f) }
    LaunchedEffect(message.messageUUID) {
        // reduceMotion：纯透明度进出保留（效果轴），菜单卷帘/级联瞬现（空间+级联降级）。
        launch { overlayProgress.animateTo(1f, tween(SCRIM_FADE_MS, easing = AppMotion.EaseOutQuint)) }
        if (reduceMotion) {
            menuAppear.snapTo(1f)
        } else {
            menuAppear.animateTo(
                1f,
                tween(MENU_APPEAR_BASE_MS + MENU_APPEAR_PER_ITEM_MS * entries.size, easing = AppMotion.EaseInOut),
            )
        }
    }
    val scope = rememberCoroutineScope()
    val close: () -> Unit = close@{
        if (!state.beginClose()) return@close
        scope.launch {
            if (!reduceMotion) launch { closeShift.animateTo(1f, tween(MENU_CLOSE_MS, easing = AppMotion.EaseInOut)) } else closeShift.snapTo(1f)
            overlayProgress.animateTo(0f, tween(SCRIM_FADE_MS, easing = AppMotion.EaseOutQuint))
            state.dismissNow()
        }
    }
    BackHandler { close() }
    // 退后台/转屏立即关（瞬态菜单不持久化=Telegram onPause 即关）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.dismissNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val clipboard = LocalClipboard.current
    val paneTitle = stringResource(R.string.a11y_message_menu)
    Box(
        Modifier
            .fillMaxSize()
            .semantics { this.paneTitle = paneTitle }
            // 点空白收场；子项 clickable 先消费，不会误触到这里。
            .pointerInput(Unit) { detectTapGestures(onTap = { close() }) },
    ) {
        // 冻结画面 + 毛玻璃 + 压暗，整组随 overlayProgress 淡入出——开场时它与其下实况逐像素相同处无感衔接，
        // 收场时与（可能已更新的）实况交叉淡化。
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = overlayProgress.value }) {
            state.frosted?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Box(Modifier.fillMaxSize().background(AppTheme.colors.surface.scrim.copy(alpha = SCRIM_ALPHA)))
            // 被按气泡原位清晰浮起（D5=零位移零缩放·纯层级对比）：清晰快照按气泡圆角裁剪重画。
            state.snapshot?.let { snap ->
                val corner = with(LocalDensity.current) { 16.dp.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val path = Path().apply { addRoundRect(RoundRect(state.bubbleBounds, CornerRadius(corner))) }
                    clipPath(path) {
                        drawImage(
                            image = snap,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                    }
                }
            }
        }

        ImmersiveMenuCard(
            entries = entries,
            bubbleBounds = state.bubbleBounds,
            alignEnd = message.roleRaw == "user",
            appear = { menuAppear.value },
            closeShift = { closeShift.value },
            enabled = !state.closing,
            onAction = { action ->
                when (action) {
                    ImmersiveMenuAction.COPY -> scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", messageCopyText(message))))
                    }
                    ImmersiveMenuAction.SAVE_IMAGE -> actions.onSaveImage(message)
                    ImmersiveMenuAction.QUOTE -> actions.onQuote(message)
                    ImmersiveMenuAction.REGENERATE -> actions.onRegenerate()
                    ImmersiveMenuAction.DELETE -> actions.onDelete(message)
                }
                close()
            },
        )
    }
}

/** 菜单卡（自绘壳）：surface.raised 16dp 圆角+发丝边；卷帘 clip 生长、项级联 −6dp→0；收起整体上浮淡出。 */
@Composable
private fun ImmersiveMenuCard(
    entries: List<ImmersiveMenuAction>,
    bubbleBounds: Rect,
    alignEnd: Boolean,
    appear: () -> Float,
    closeShift: () -> Float,
    enabled: Boolean,
    onAction: (ImmersiveMenuAction) -> Unit,
) {
    val density = LocalDensity.current
    val marginPx = with(density) { MenuScreenMargin.roundToPx() }
    val gapPx = with(density) { MenuBubbleGap.roundToPx() }
    val closeRisePx = with(density) { MenuCloseRise.toPx() }
    val itemRisePx = with(density) { MenuItemRise.toPx() }
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(entries) {
        // 读屏焦点送第一项（Telegram show 后 ~420ms·给入场动画让路）。
        kotlinx.coroutines.delay(A11Y_FOCUS_DELAY_MS)
        runCatching { firstItemFocus.requestFocus() }
    }
    Layout(
        content = {
            Column(
                Modifier
                    // T4 定稿修：宽度=内容自适应（最宽项）钳 [180, 260]dp——此前项行 fillMaxWidth 在屏宽约束下
                    // 把卡撑成整屏「床单」（与过审 mockup 走样）；IntrinsicSize.Max 收口回紧凑小卡。
                    .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
                    .width(IntrinsicSize.Max)
                    .graphicsLayer {
                        val close = closeShift()
                        alpha = appear() * (1f - close)
                        translationY = -closeRisePx * close
                    }
                    .drawWithContentClipReveal(appear)
                    .clip(AppShapes.medium)
                    .background(AppTheme.colors.surface.raised)
                    .border(MenuHairline, AppTheme.colors.surface.stroke, AppShapes.medium)
                    .padding(vertical = 8.dp),
            ) {
                entries.forEachIndexed { index, action ->
                    val label = immersiveMenuActionLabel(action)
                    val icon = when (action) {
                        ImmersiveMenuAction.COPY -> Icons.Filled.ContentCopy
                        ImmersiveMenuAction.SAVE_IMAGE -> Icons.Filled.FileDownload
                        ImmersiveMenuAction.QUOTE -> Icons.Filled.FormatQuote
                        ImmersiveMenuAction.REGENERATE -> Icons.Filled.Refresh
                        ImmersiveMenuAction.DELETE -> Icons.Filled.Delete
                    }
                    // 语义色冻结（契约 §3.3）：重新生成=陶土玫品牌动作、删除=error 功能深档、其余中性。
                    val tint = when (action) {
                        ImmersiveMenuAction.REGENERATE -> AppTheme.colors.accent.text
                        ImmersiveMenuAction.DELETE -> AppTheme.colors.status.onError
                        else -> AppTheme.colors.text.primary
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val p = cascadeProgress(appear(), index, entries.size)
                                alpha = p
                                translationY = -itemRisePx * (1f - p)
                            }
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocus).focusable() else Modifier)
                            .clickable(enabled = enabled) { onAction(action) }
                            .heightIn(min = MenuItemHeight)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = tint)
                        Text(
                            label,
                            style = AppTypography.label,
                            color = tint,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val offset = immersiveMenuOffset(
                bubble = bubbleBounds,
                menuW = placeable.width,
                menuH = placeable.height,
                screenW = constraints.maxWidth,
                screenH = constraints.maxHeight,
                alignEnd = alignEnd,
                marginPx = marginPx,
                gapPx = gapPx,
            )
            placeable.place(offset.x, offset.y)
        }
    }
}

/** 卷帘生长（Telegram backScaleY 的裁剪式等价·非 View 缩放）：内容自顶向下按进度显露。 */
private fun Modifier.drawWithContentClipReveal(progress: () -> Float): Modifier = drawWithContent {
    val reveal = progress().coerceIn(0f, 1f)
    clipRect(top = 0f, left = 0f, right = size.width, bottom = size.height * reveal) {
        this@drawWithContent.drawContent()
    }
}
