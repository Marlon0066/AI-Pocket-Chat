package com.situ.aichat.ui.chat

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.util.ContentImageStore

/**
 * 聊天图片气泡（契约 FABLE5_IMAGE_MULTIMODAL_PROPOSAL §B7·mockup 已过审）。
 *
 * 与文字气泡的关键差别：**整图即气泡**——不套陶土填充、不留内边距，图片自身就是那块「面」。
 * 只做三件事：圆角裁切（与文字气泡同 [Shape]）、0.5dp 发丝描边（防白底照片与浅色页面融在一起）、
 * 宽高比钳制（3:4 ~ 4:3，超出的长图/宽图 centerCrop，免得一张全景把整屏撑满）。
 *
 * 读**缩略图**而非原图：列表滚动是热路径，512px 足够；全屏查看器才解原图。
 * 文件缺失（跨设备还原备份 / 用户清了数据）→ 安静的占位块，绝不崩、也不留白。
 */
@Composable
internal fun ChatImageBubble(
    imagePath: String?,
    thumbnailPath: String?,
    shape: Shape,
    maxWidth: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    a11yDescription: String? = null,
) {
    val path = thumbnailPath ?: imagePath
    // 三态而非两态：`produceState` 的 producer 在合成之后才启动，若把「还没加载完」与「文件没了」并成
    // 同一个 else 分支，**每个图片气泡至少有一帧显示「图片已失效」**（缓存未命中时还要读盘解码，更久）。
    val state by produceState<ImageLoadState>(initialValue = ImageLoadState.Loading, path) {
        value = ContentImageStore.load(path, ContentImageStore.THUMBNAIL_EDGE)
            ?.let { ImageLoadState.Ready(it) }
            ?: ImageLoadState.Missing
    }

    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .border(0.5.dp, AppTheme.colors.surface.stroke, shape)
            // ⚠️ clearAndSetSemantics 必须排在 clickable **之前**（本仓既有教训：ContactsScreen 复核修）；
            // 它会清掉子树语义，排在后面就把 Button role 与 onClick 标签一起清没了。被清掉的部分在块内补声明
            // （与 GiftCardBubble / RedPacketCardBubble 同款处置）。
            .then(
                a11yDescription?.let { desc ->
                    Modifier.clearAndSetSemantics {
                        contentDescription = desc
                        role = Role.Button
                    }
                } ?: Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        // ⚠️ 用 Crossfade 而不是 `AnimatedVisibility(visible = true)`：后者内部是 updateTransition(visible)，
        // **首帧 initialState == targetState，转场根本不启动**——先前那版写的 fadeIn 一帧都没播过（R2 🟡-3）。
        // Crossfade 接管整个 Loading→Ready 切换：占位块淡出、图淡入，才是契约 §B7 说的那个淡入。
        // reduceMotion 门控是必须的（R3 🟡-6）：空动画版本恰好对开了「移除动画」的用户无害，改成真会播之后，
        // 每条图片气泡滚进视口都来一次 300ms 交叉淡出——正是前庭敏感用户打开该系统开关想避开的东西。
        val reduceMotion = rememberReduceMotion()
        Crossfade(
            targetState = state,
            animationSpec = if (reduceMotion) snap() else tween(AppMotion.SMOOTH_MS),
            label = "chatImage",
        ) { s ->
            when (s) {
                is ImageLoadState.Ready -> {
                    val bmp = s.bitmap
                    val ratio = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(MIN_RATIO, MAX_RATIO)
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .widthIn(max = maxWidth)
                            .aspectRatio(ratio),
                    )
                }
                // 加载中：只给一块安静的底，**不说话**——在正常路径上说「失效」是撒谎。
                ImageLoadState.Loading -> ImagePlaceholderBox(maxWidth = maxWidth, label = null)
                ImageLoadState.Missing -> ImagePlaceholderBox(
                    maxWidth = maxWidth,
                    label = stringResource(R.string.chat_image_missing),
                )
            }
        }
    }
}

@Composable
private fun ImagePlaceholderBox(maxWidth: Dp, label: String?) {
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .aspectRatio(PLACEHOLDER_RATIO)
            .background(AppTheme.colors.surface.sunken),
        contentAlignment = Alignment.Center,
    ) {
        label?.let {
            Text(text = it, style = AppTheme.typography.caption, color = AppTheme.colors.text.tertiary)
        }
    }
}

/** 缩略图加载三态：加载中 / 已就绪 / 文件确实没了。 */
private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: Bitmap) : ImageLoadState
    data object Missing : ImageLoadState
}

private const val MIN_RATIO = 3f / 4f
private const val MAX_RATIO = 4f / 3f
private const val PLACEHOLDER_RATIO = 4f / 3f
