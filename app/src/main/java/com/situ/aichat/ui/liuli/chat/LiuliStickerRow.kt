package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.chat.StickerImage
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 琉璃纯贴纸行（图纸 2026-09-05 卷二C §4.3 · A-7）：无底大图 **110dp**（契约 §5.5：暖陶 120 → 琉璃 110）、
 * 圆角 24 裁 + 一道柔影；时间戳挪到贴纸**旁**（用户在左 / AI 在右）小字 `text.tertiary`，与贴纸底对齐。
 *
 * 长按转交沉浸菜单、合并朗读句都照抄暖陶 `StickerStack`（F8）——只换长相。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliStickerStack(
    content: String,
    customStickers: List<CustomStickerEntity>,
    isUser: Boolean,
    timestampMs: Long,
    deliveryRead: Boolean?,
    onLongClick: () -> Unit,
    a11yDescription: String?,
) {
    val ids = remember(content) { StickerTagParser.extractStickerIds(content) }
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(LiuliChatGeometry.stickerCorner)

    val stickers: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            ids.forEach { id ->
                Box(
                    Modifier
                        .shadow(
                            elevation = STICKER_SHADOW,
                            shape = shape,
                            clip = false,
                            ambientColor = colors.text.primary.copy(alpha = STICKER_SHADOW_ALPHA),
                            spotColor = colors.text.primary.copy(alpha = STICKER_SHADOW_ALPHA),
                        )
                        .clip(shape),
                ) {
                    StickerImage(stickerId = id, customStickers = customStickers, size = LiuliChatGeometry.stickerSize)
                }
            }
        }
    }
    val stamp: @Composable () -> Unit = {
        LiuliInlineStamp(
            timestampMs = timestampMs,
            isUser = isUser,
            read = deliveryRead,
            stampColor = colors.text.tertiary,
        )
    }

    Row(
        modifier = Modifier
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.a11y_message_menu),
            )
            .then(a11yDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier),
        horizontalArrangement = Arrangement.spacedBy(SIDE_STAMP_GAP),
        verticalAlignment = Alignment.Bottom,
    ) {
        // 戳在贴纸「外侧」：用户行贴纸靠右 → 戳在左；AI 行贴纸靠左 → 戳在右（对版稿 `.sticker` / `.sticker.in`）。
        if (isUser) {
            stamp()
            stickers()
        } else {
            stickers()
            stamp()
        }
    }
}

/** 落值（A-7 + 对版稿 `.sticker`·孤值即打回）。 */
private val STACK_GAP = 4.dp
private val SIDE_STAMP_GAP = 6.dp
private val STICKER_SHADOW = 4.dp
private const val STICKER_SHADOW_ALPHA = 0.12f
