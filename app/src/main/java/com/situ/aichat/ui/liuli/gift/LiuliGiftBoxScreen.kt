package com.situ.aichat.ui.liuli.gift

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.gift.GiftBoxViewModel
import com.situ.aichat.ui.gift.GiftImage
import com.situ.aichat.ui.gift.compactGiftDate
import com.situ.aichat.ui.gift.diyContentPreview
import com.situ.aichat.ui.gift.giftTitle
import com.situ.aichat.ui.liuli.chat.sheets.LiuliDiyGiftDetailSheet
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberGridCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.util.ContentImageStore

/** 收礼盒（琉璃 2.0 脸 · 只读历史，与 `GiftBoxScreen` 共用 [GiftBoxViewModel]）。 */
@Composable
fun LiuliGiftBoxScreen(
    onBack: () -> Unit,
    onOpenReaction: (String) -> Unit,
    onOpenReceived: (String) -> Unit,
    viewModel: GiftBoxViewModel = hiltViewModel(),
) {
    val sent by viewModel.sentGifts.collectAsStateWithLifecycle()
    val received by viewModel.receivedGifts.collectAsStateWithLifecycle()
    val charByUuid by viewModel.characterByUuid.collectAsStateWithLifecycle()

    var receivedTab by rememberSaveable { mutableStateOf(true) }
    var diyDetailRecord by remember { mutableStateOf<GiftRecordEntity?>(null) }

    val records = if (receivedTab) received else sent
    val dark = LocalIsDarkTheme.current
    val gridState = rememberLazyGridState()
    val collapsed = rememberGridCollapsed(gridState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(title = "收礼盒", onBack = onBack, collapsed = collapsed) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = LiuliPageGeometry.gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                top = LiuliPageGeometry.navRow,
                bottom = navBottom + LiuliPageGeometry.pageBottom,
            ),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LiuliLargeTitle("收礼盒")
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LiuliSegmented(
                    options = listOf(true, false),
                    selected = receivedTab,
                    label = { if (it) "收到" else "送出" },
                    onSelect = { receivedTab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (records.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val colors = AppTheme.colors
                    val typography = AppTheme.typography
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(64.dp).clip(CircleShape).background(colors.accent.container),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppActionIcons.Gift, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (receivedTab) "还没有收到礼物" else "还没送过礼物",
                            style = typography.titleSmall,
                            color = colors.text.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (receivedTab) "等角色想你的时候\n会主动送来" else "去礼物店挑一份\n表达你的心意",
                            style = typography.secondary,
                            color = colors.text.secondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(records, key = { it.uuid }) { record ->
                    val counterpartyUuid = if (receivedTab) record.senderCharacterUUID else record.receiverCharacterUUID
                    LiuliHistoryGiftCard(
                        record = record,
                        counterparty = charByUuid[counterpartyUuid],
                        dark = dark,
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
        LiuliDiyGiftDetailSheet(record = record, onDismiss = { diyDetailRecord = null })
    }
}

@Composable
private fun LiuliHistoryGiftCard(
    record: GiftRecordEntity,
    counterparty: CharacterEntity?,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liuliCardMaterial(LiuliShapes.group, dark)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val diyImage by produceState<Bitmap?>(initialValue = null, record.uuid) {
            value = if (record.isDIY) ContentImageStore.load(record.diyImagePath) else null
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
        Text(giftTitle(record), style = typography.label, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (record.isDIY && record.diyContent.isNotEmpty()) {
            Text(diyContentPreview(record.diyContent), style = typography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(record.reactionMoodEmoji.ifEmpty { "🎁" }, style = typography.caption)
            Text(counterparty?.name ?: "未知", style = typography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(compactGiftDate(record.timestamp), style = typography.caption, color = colors.text.tertiary)
    }
}
