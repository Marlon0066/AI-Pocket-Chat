package com.situ.aichat.ui.liuli.chat.sheets

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.DateFormatters

/** 玻璃内衬块的圆角与底（§4.11 A 甲：详情类只读段落一律走 14 圆角内衬，不再是暖陶的 surfaceContainer 卡）。 */
private val INLAY_SHAPE = RoundedCornerShape(14.dp)
private const val INLAY_ALPHA = 0.62f

/**
 * 琉璃版 DIY 手作礼物详情（图纸 2026-09-05 卷二C C6b · A-16 · 照抄源 F26 末段
 * `ui/gift/DIYGiftDetailSheet.kt:49-121`）。
 *
 * **只换渲染皮**：标题兜底 `ifEmpty{"手作礼物"}`、[ContentImageStore] 懒加载图（**不进 LLM**）、
 * 「内容」段的 `ifEmpty{"（无内容）"}`、底部 [DateFormatters].relativeTimeSpanShort 逐字照抄。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliDiyGiftDetailSheet(record: GiftRecordEntity, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val title = record.diyTitle.trim().ifEmpty { "手作礼物" }
    val content = record.diyContent.trim()
    val image by produceState<Bitmap?>(initialValue = null, record.diyImagePath) {
        value = ContentImageStore.load(record.diyImagePath)
    }

    LiuliSheetShell(onDismissRequest = onDismiss, title = title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 头卡：手作图标 + 标题 + 「手作」标 + 价格（暖陶的红 pill 是 GiftColors 字面量 → 琉璃走 economy 金）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(INLAY_SHAPE)
                    .background(colors.surface.raised.copy(alpha = INLAY_ALPHA))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text(title, style = AppTypography.label, color = onGlass.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("手作", style = AppTypography.caption, color = colors.economy.gold)
                        Text("${record.pricePaid} 金币", style = AppTypography.amount, color = colors.economy.gold)
                    }
                }
            }

            image?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.a11y_diy_attached_image),
                    modifier = Modifier.fillMaxWidth().clip(INLAY_SHAPE),
                    contentScale = ContentScale.Fit,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("内容", style = AppTypography.snackbarBody, color = onGlass.secondary)
                Text(
                    text = content.ifEmpty { "（无内容）" },
                    style = AppTypography.listPreview,
                    color = if (content.isEmpty()) onGlass.secondary else onGlass.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(INLAY_SHAPE)
                        .background(colors.surface.raised.copy(alpha = INLAY_ALPHA))
                        .padding(14.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = onGlass.secondary, modifier = Modifier.size(14.dp))
                Text(
                    DateFormatters.relativeTimeSpanShort(record.timestamp, System.currentTimeMillis()),
                    style = AppTypography.snackbarBody,
                    color = onGlass.secondary,
                )
            }
        }
    }
}
