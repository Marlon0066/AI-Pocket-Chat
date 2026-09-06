package com.situ.aichat.ui.gift

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.util.ContentImageStore
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState

/**
 * 收礼盒（9.2d d-5，1:1 iOS `GiftBoxView`）：收到 / 送出 分段 Tab + 2 列网格。
 *
 * 点卡分流（1:1 iOS GiftDetailContainer）：DIY → 详情底片；角色送的（senderType=character）→ [onOpenReceived]
 * 只读收礼详情；用户送的 → [onOpenReaction] 反应页**回放**（send=false，不重调 LLM）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftBoxScreen(
    onBack: () -> Unit,
    onOpenReaction: (String) -> Unit,
    onOpenReceived: (String) -> Unit,
    viewModel: GiftBoxViewModel = hiltViewModel(),
) {
    val sent by viewModel.sentGifts.collectAsStateWithLifecycle()
    val received by viewModel.receivedGifts.collectAsStateWithLifecycle()
    val charByUuid by viewModel.characterByUuid.collectAsStateWithLifecycle()

    // 默认「收到」tab（1:1 iOS tab = .received）
    var receivedTab by rememberSaveable { mutableStateOf(true) }
    var diyDetailRecord by remember { mutableStateOf<GiftRecordEntity?>(null) }

    val records = if (receivedTab) received else sent

    val gridState = rememberLazyGridState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = "收礼盒",
                onBack = onBack,
                lifted = gridState.canScrollBackward,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = AppSpacing.screenGutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AppSegmentedControl(
                    options = listOf(true, false),
                    selected = receivedTab,
                    onSelect = { receivedTab = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { if (it) "收到" else "送出" },
                )
            }
            if (records.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // gift-3：图标 + 标题 + 双行引导，1:1 iOS GiftBoxView EmptyStateView（received=gift🎁 / sent=shippingbox📦）。
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (receivedTab) "🎁" else "📦", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (receivedTab) "还没有收到礼物" else "还没送过礼物",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (receivedTab) "等角色想你的时候\n会主动送来" else "去礼物店挑一份\n表达你的心意",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(records, key = { it.uuid }) { record ->
                    val counterpartyUuid = if (receivedTab) record.senderCharacterUUID else record.receiverCharacterUUID
                    HistoryCard(
                        record = record,
                        counterparty = charByUuid[counterpartyUuid],
                        onClick = {
                            when {
                                record.isDIY -> diyDetailRecord = record
                                record.senderType == "character" -> onOpenReceived(record.uuid)
                                else -> onOpenReaction(record.uuid)
                            }
                        },
                    )
                }
            }
        }
    }

    diyDetailRecord?.let { record ->
        DIYGiftDetailSheet(record = record, onDismiss = { diyDetailRecord = null })
    }
}

/** 收礼盒历史卡片（1:1 iOS HistoryCard）：缩略图 + 礼物名 + DIY 摘要 + (mood emoji 或 🎁) + 对手方名 + 紧凑日期。 */
@Composable
private fun HistoryCard(
    record: GiftRecordEntity,
    counterparty: CharacterEntity?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 缩略图：DIY 有上传图 → 本地图；否则 GiftImage(record)（DIY 无图走 paintbrush 兜底）
            val diyImage by produceState<Bitmap?>(initialValue = null, record.uuid) {
                value = if (record.isDIY) ContentImageStore.load(record.diyImagePath) else null
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bmp = diyImage
                if (record.isDIY && bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(140.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    GiftImage(record = record, size = 140.dp, cornerRadius = 14.dp, showsShadow = false)
                }
            }

            Text(
                text = giftTitle(record),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (record.isDIY && record.diyContent.isNotEmpty()) {
                Text(
                    text = diyContentPreview(record.diyContent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(record.reactionMoodEmoji.ifEmpty { "🎁" }, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = counterparty?.name ?: "未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = compactGiftDate(record.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 礼物名（1:1 iOS giftTitle）：目录名 → DIY 标题/手作礼物 → 礼物。 */
private fun giftTitle(record: GiftRecordEntity): String {
    GiftCatalog.find(record.giftItemId)?.let { return it.name }
    return if (record.isDIY) record.diyTitle.ifEmpty { "手作礼物" } else "礼物"
}

/** DIY 内容摘要（1:1 iOS diyContentPreview）：前 14 字，超出加「…」。 */
internal fun diyContentPreview(content: String): String {
    val trimmed = content.trim()
    return if (trimmed.length <= 14) trimmed else trimmed.take(14) + "…"
}

/** 紧凑日期标签（1:1 iOS compactDateLabel）：今天/昨天/N天前(<7)/同年 M月d日/否则 yyyy年M月。internal 供单测。 */
internal fun compactGiftDate(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> "今天"
        days == 1L -> "昨天"
        days < 7L -> "${days}天前"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月"
    }
}
