package com.situ.aichat.ui.liuli.chat

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.util.ContentImageStore

/**
 * 琉璃图片泡（图纸 2026-09-05 卷二C §4.2 · A-6）：**整图即气泡**——18dp 圆角、**无尾**、0.5 发丝，
 * 时间戳压在右下一枚小 pill 上。
 *
 * 三态（Loading / Ready / Missing）、宽高比钳制、占位块与 **`clearAndSetSemantics` 必须排在
 * `combinedClickable` 之前**（本仓既有教训）逐字照抄暖陶 `ChatImageBubble`（F7）。
 *
 * 戳底**不走玻璃**：内容层拿不到 `LocalBackdrop`，`liuliGlass` 会退成浅色染色，压在照片上白字读不出来
 * ——故走 [LiuliPalette.imageStampScrim] 实底（A-6 明写）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliImageBubble(
    imagePath: String?,
    thumbnailPath: String?,
    isUser: Boolean,
    maxWidth: Dp,
    timestampMs: Long,
    deliveryRead: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    a11yDescription: String?,
) {
    val path = thumbnailPath ?: imagePath
    val width = minOf(LiuliChatGeometry.imageMaxWidth, maxWidth)
    // 18dp 无尾（A-6）= 气泡形状阶那一枚，尾巴规则不适用于整图。
    val shape = LiuliShapes.bubble
    // 三态而非两态（F7）：并成两态会让每个图片泡至少有一帧显示「图片已失效」。
    val state by produceState<LiuliImageLoadState>(initialValue = LiuliImageLoadState.Loading, path) {
        value = ContentImageStore.load(path, ContentImageStore.THUMBNAIL_EDGE)
            ?.let { LiuliImageLoadState.Ready(it) }
            ?: LiuliImageLoadState.Missing
    }

    Box(
        modifier = Modifier
            .widthIn(max = width)
            .clip(shape)
            .border(IMAGE_HAIRLINE, AppTheme.colors.surface.stroke, shape)
            // ⚠️ 顺序锁（F7）：`clearAndSetSemantics` 必须排在 `combinedClickable` **之前**——它清子树语义，
            // 排后面会把 Button role 与点击标签一起清没。
            .then(
                a11yDescription?.let { desc ->
                    Modifier.clearAndSetSemantics {
                        contentDescription = desc
                        role = Role.Button
                    }
                } ?: Modifier,
            )
            // TODO(图纸未覆盖): §4.2 要「F7 逐字」（暖陶三态都可点），§5 E3 / §7 T2-2 要「点击只在 Ready」——
            // 两处规格冲突。此处按 §9 ④ 机制锁 + §2.3「一个大脑」取暖陶行为（不加门），见 §11 D-3 留复核裁决。
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val reduceMotion = rememberReduceMotion()
        Crossfade(
            targetState = state,
            animationSpec = if (reduceMotion) snap() else tween(AppMotion.SMOOTH_MS),
            label = "liuliChatImage",
        ) { s ->
            when (s) {
                is LiuliImageLoadState.Ready -> {
                    val bmp = s.bitmap
                    val ratio = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(MIN_RATIO, MAX_RATIO)
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.widthIn(max = width).aspectRatio(ratio),
                    )
                }
                // 加载中：只给一块安静的底，**不说话**——在正常路径上说「失效」是撒谎（F7）。
                LiuliImageLoadState.Loading -> LiuliImagePlaceholder(width = width, label = null)
                LiuliImageLoadState.Missing -> LiuliImagePlaceholder(
                    width = width,
                    label = stringResource(R.string.chat_image_missing),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(IMAGE_STAMP_INSET)
                .clip(LiuliShapes.pill)
                .background(LiuliPalette.imageStampScrim)
                .padding(horizontal = IMAGE_STAMP_SIDE, vertical = IMAGE_STAMP_VERTICAL),
        ) {
            LiuliInlineStamp(
                timestampMs = timestampMs,
                isUser = isUser,
                read = deliveryRead,
                stampColor = Palette.White,
                textStyle = ImageStampStyle,
            )
        }
    }
}

@Composable
private fun LiuliImagePlaceholder(width: Dp, label: String?) {
    Box(
        modifier = Modifier
            .widthIn(max = width)
            .aspectRatio(PLACEHOLDER_RATIO)
            .background(AppTheme.colors.surface.sunken),
        contentAlignment = Alignment.Center,
    ) {
        label?.let {
            Text(text = it, style = AppTypography.caption, color = AppTheme.colors.text.tertiary)
        }
    }
}

/** 缩略图加载三态（**重打**暖陶 `ImageLoadState`·那侧是 private·两侧注释互指）。 */
private sealed interface LiuliImageLoadState {
    data object Loading : LiuliImageLoadState
    data class Ready(val bitmap: Bitmap) : LiuliImageLoadState
    data object Missing : LiuliImageLoadState
}

/** 落值（A-6 + 对版稿 `.img` / `.img .t`·比例钳照抄 F7）。 */
private const val MIN_RATIO = 3f / 4f
private const val MAX_RATIO = 4f / 3f
private const val PLACEHOLDER_RATIO = 4f / 3f
private val IMAGE_HAIRLINE = 0.5.dp
private val IMAGE_STAMP_INSET = 6.dp
private val IMAGE_STAMP_SIDE = 7.dp
private val IMAGE_STAMP_VERTICAL = 2.dp
private val ImageStampStyle = AppTypography.captionNumeric.copy(fontSize = 10.5.sp)
