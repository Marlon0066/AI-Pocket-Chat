package com.situ.aichat.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.util.ContentImageStore

/**
 * 聊天屏的图片相关瞬态（查看器开关 + 发图入口显隐）。
 *
 * 收成一个 holder 而不是散在屏里：`ChatScreen` 已在 800 行绝对红线上（CLAUDE.md §2），
 * 本卷的图片接线不该由它继续背。瞬态不入 saveable——与沉浸菜单同口径，转屏即关。
 */
@Composable
internal fun rememberChatImageState(viewModel: ChatViewModel): ChatImageState {
    val hasVision by viewModel.image.hasVision.collectAsStateWithLifecycle()
    val holder = remember { ChatImageState() }
    holder.chatModelHasVision = hasVision
    return holder
}

/** 见 [rememberChatImageState]。 */
@Stable
internal class ChatImageState {
    /** 非空 = 全屏查看器打开中，值为原图路径。 */
    var viewerImagePath by mutableStateOf<String?>(null)
    /** 「聊天对话」模型是否看得懂图——决定「+」面板出不出「照片」格。 */
    var chatModelHasVision by mutableStateOf(false)
}

/** 查看器宿主：非空即开（把开关判断留在这，屏内只剩一行调用）。 */
@Composable
internal fun ChatImageViewerHost(state: ChatImageState) {
    state.viewerImagePath?.let { path ->
        ChatImageViewer(imagePath = path, onDismiss = { state.viewerImagePath = null })
    }
}

/**
 * 全屏图片查看器（契约 §B7·mockup 已过审）：恒黑底沉浸（不随主题——看照片时周围越暗越好），
 * 双指缩放 / 拖动 / 双击 2× / 单击退出。
 *
 * 手势用 Compose foundation 的 [detectTransformGestures] + [detectTapGestures] **自绘**，
 * 不引第三方缩放库（铁律 #1：绝不为视觉引入第三方 UI 库）。
 * 读**原图**（1568px 档）而非气泡用的缩略图——放大看细节是这个屏存在的理由。
 */
@Composable
internal fun ChatImageViewer(imagePath: String, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, imagePath) {
        value = ContentImageStore.load(imagePath, ContentImageStore.CHAT_MAX_EDGE)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Dialog(
        onDismissRequest = onDismiss,
        // 恒黑底沉浸要铺到系统栏后面才成立——只给 usePlatformDefaultWidth 会在状态栏/导航栏位置留主题色带
        // （本仓先例：RedPacketDetailDialog 两个参数都给）。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VIEWER_BACKDROP)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            // 双击在 1× 与 2× 之间来回；缩回 1× 时同时归位平移，免得图卡在屏幕外。
                            if (scale > 1f) reset() else scale = DOUBLE_TAP_SCALE
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            // 已经缩回原大小就不该还能拖走（否则图会漂出屏幕且没有回位手势）。
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            }
        }
    }
}

/** 恒黑底：查看器有意不跟随主题（浅色主题下把照片放在白底上会互相干扰）。 */
private val VIEWER_BACKDROP = Color(0xFF0A0908)
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2f
