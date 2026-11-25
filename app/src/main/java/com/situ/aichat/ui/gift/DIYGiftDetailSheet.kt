package com.situ.aichat.ui.gift

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.DateFormatters

/**
 * DIY 手作礼物详情底片（9.2d d-3，1:1 iOS `DIYGiftDetailView`，只读）。
 *
 * 由聊天气泡（d-4）或收礼盒（d-5）点击 DIY 礼物触发。展示完整 [GiftRecordEntity.diyTitle] / [GiftRecordEntity.diyContent]
 * / 上传图（按 [GiftRecordEntity.diyImagePath] 懒加载，**不进 LLM**）。气泡只显示截断 80 字，看全文需点开这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DIYGiftDetailSheet(
    record: GiftRecordEntity,
    onDismiss: () -> Unit,
) {
    val title = record.diyTitle.trim().ifEmpty { "手作礼物" }
    val content = record.diyContent.trim()
    val image by produceState<Bitmap?>(initialValue = null, record.diyImagePath) {
        value = ContentImageStore.load(record.diyImagePath)
    }

    AppSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题段：手作图标 + 标题 + 手作 pill + 价格
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(28.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(50), color = androidx.compose.ui.graphics.Color(0xFFE23B3B)) {
                                Text("手作", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
                            Text("${record.pricePaid}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("金币", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            image?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.a11y_diy_attached_image),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )
            }

            // 内容段
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("内容", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = content.ifEmpty { "（无内容）" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (content.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    )
                }
            }

            // 底部时间
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppActionIcons.Clock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(
                    // gift-2：含小时/分钟粒度的相对时间（1:1 iOS RelativeDateTimeFormatter .short），不再把刚做好的 DIY 显示成「今天」。
                    DateFormatters.relativeTimeSpanShort(record.timestamp, System.currentTimeMillis()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
