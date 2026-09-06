package com.situ.aichat.ui.liuli.chat

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatImmersiveMenuState
import com.situ.aichat.ui.chat.ImmersiveMenuAction
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.chat.immersiveMenuActions
import com.situ.aichat.ui.chat.messageCanBeQuoted
import com.situ.aichat.ui.chat.messageCopyText
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 琉璃长按沉浸菜单（图纸 2026-09-05 卷二B §4.7 · 契约 §5.3 · A-1）：替换暖陶 `ChatImmersiveMenuOverlay`。
 *
 * **编舞与机制逐条照抄**（图纸 §9 ④ 机制锁）：三驱动（压暗 320ms EaseOutQuint / 卷帘 + 级联 150+16n
 * EaseInOut / 收起 220ms EaseInOut·RM 下卷帘级联瞬现）、`BackHandler` 与 `ON_STOP` 即关、点空白收场、
 * 冻结快照 + scrim 0.2 + 被按泡按圆角裁清晰快照原位浮起、动作清单与文案单源。
 *
 * **A-1 有意保留 PixelCopy 冻结快照、只换壳**：冻结画面是 TELEGRAM_MOTION §3 的行为契约（菜单期间 AI 递送
 * 不扰动画面、收场与实况交叉淡化），且「被按泡原位清晰浮起」靠的就是那张快照——`BackdropHost` 的实时模糊
 * 只录内容层、也冻不住。两种做法的可见结果相同（磨砂 + 压暗 + 玻璃卡）。
 *
 * 琉璃的分叉（§3.3 分叉 3）：卡换玻璃 + 顶行五个表情回应 + 泡形裁剪圆角 16 → 18 + 屏边距 6 → 12；
 * `screenH` 扣掉键盘（键盘开着长按时菜单钳在键盘上方·E18）。
 */
@Composable
internal fun LiuliImmersiveMenuOverlay(
    state: ChatImmersiveMenuState,
    actions: MessageRowActions,
    reduceMotion: Boolean,
    /** 表情回应（纯瞬态·A-8）：点一枚 → 徽章在那条泡上弹一下 + 菜单收场。 */
    onReact: (MessageEntity, String) -> Unit,
    /**
     * 本覆盖层底部被占的高度（键盘或「+」面板 + 导航栏·E18），菜单钳在它之上；布局 lambda 里取值、组合期绝不读 ime。
     * 调用方须**含导航栏**（复核 R1 🟡-4：只传面板区高会少算一条导航栏）。
     */
    bottomObstructionPx: () -> Int,
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

    // 进出场三驱动（按目标消息 key = 换目标重置）。表情行算第 0 项参与级联 ⇒ 项数 +1。
    val cascadeCount = entries.size + 1
    val overlayProgress = remember(message.messageUUID) { Animatable(0f) }
    val menuAppear = remember(message.messageUUID) { Animatable(0f) }
    val closeShift = remember(message.messageUUID) { Animatable(0f) }
    LaunchedEffect(message.messageUUID) {
        // reduceMotion：纯透明度进出保留（效果轴），菜单卷帘 / 级联瞬现（空间 + 级联降级）。
        launch { overlayProgress.animateTo(1f, tween(SCRIM_FADE_MS, easing = AppMotion.EaseOutQuint)) }
        if (reduceMotion) {
            menuAppear.snapTo(1f)
        } else {
            menuAppear.animateTo(
                1f,
                tween(MENU_APPEAR_BASE_MS + MENU_APPEAR_PER_ITEM_MS * cascadeCount, easing = AppMotion.EaseInOut),
            )
        }
    }
    val scope = rememberCoroutineScope()
    val close: () -> Unit = close@{
        if (!state.beginClose()) return@close
        scope.launch {
            if (!reduceMotion) {
                launch { closeShift.animateTo(1f, tween(MENU_CLOSE_MS, easing = AppMotion.EaseInOut)) }
            } else {
                closeShift.snapTo(1f)
            }
            overlayProgress.animateTo(0f, tween(SCRIM_FADE_MS, easing = AppMotion.EaseOutQuint))
            state.dismissNow()
        }
    }
    BackHandler { close() }
    // 退后台 / 转屏立即关（瞬态菜单不持久化 = Telegram onPause 即关）。
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
        // 冻结画面 + 毛玻璃 + 压暗，整组随 overlayProgress 淡入出。
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = overlayProgress.value }) {
            state.frosted?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Box(Modifier.fillMaxSize().background(AppTheme.colors.surface.scrim.copy(alpha = SCRIM_ALPHA)))
            // 被按气泡原位清晰浮起（零位移零缩放·纯层级对比）：清晰快照按**琉璃泡**圆角裁剪重画。
            state.snapshot?.let { snap ->
                val corner = with(LocalDensity.current) { LiuliBubbleClipCorner.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val path = Path().apply { addRoundRect(RoundRect(state.bubbleBounds, CornerRadius(corner))) }
                    clipPath(path) {
                        drawImage(image = snap, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    }
                }
            }
        }

        LiuliImmersiveMenuCard(
            entries = entries,
            bubbleBounds = state.bubbleBounds,
            alignEnd = message.roleRaw == "user",
            appear = { menuAppear.value },
            closeShift = { closeShift.value },
            enabled = !state.closing,
            bottomObstructionPx = bottomObstructionPx,
            onReact = { emoji ->
                onReact(message, emoji)
                close()
            },
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

/** 顶行五个表情回应（契约 §5.3 锁定顺序·图纸 §9 ① 一个字不许改）。 */
internal val LiuliMenuReactions: List<String> = listOf("❤️", "😂", "😮", "🥺", "👍")

// ── Telegram 考古参数：与暖陶 `ChatImmersiveMenu.kt:114-148` **逐值同**（那边是 private 故此处重打；
//    改任一侧必须同步另一侧）。琉璃自己的几何在 `LiuliChatGeometry`（menuWidth / menuMargin / menuBubbleGap）。
internal const val SCRIM_ALPHA = 0.2f
internal const val SCRIM_FADE_MS = 320
internal const val MENU_CLOSE_MS = 220
internal const val MENU_APPEAR_BASE_MS = 150
internal const val MENU_APPEAR_PER_ITEM_MS = 16

/**
 * 项级联波长（Telegram `AndroidUtilities.cascade` 的 waveLength）：窗口 = wave / n、起点按序错峰；
 * **wave ≥ n 时窗口钳到 1 = 全项同步**（错峰消失）。琉璃的 n 比暖陶多 1（表情行算第 0 项），
 * 故 3f 下最少也有 3 项动作 + 表情行 = 4，恰好还在有波浪的一侧。**改动作项数时必须回来重算它。**
 */
internal const val CASCADE_WAVE = 3f
internal const val A11Y_FOCUS_DELAY_MS = 420L
internal val MenuItemRise = 6.dp
internal val MenuCloseRise = 5.dp
internal val MenuItemHeight = 48.dp
internal val MenuHairline = 0.75.dp

/** 被按气泡的裁剪圆角：暖陶 16 → 琉璃泡 18（= `LiuliShapes.bubble`·图纸 A-1）。 */
private val LiuliBubbleClipCorner = 18.dp
