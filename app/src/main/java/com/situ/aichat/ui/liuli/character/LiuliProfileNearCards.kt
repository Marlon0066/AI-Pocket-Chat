package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.ui.character.MemColor
import com.situ.aichat.ui.character.fmt
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.gift.GiftSymbolMapping
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.data.model.GiftImpressionTag

/**
 * 资料页「近况」段的两张卡（琉璃·图纸 2026-09-06 卷四 §4.3 T3）。排布 / 文案 / 条件 / 数据逐字继承暖陶
 * （`ProfileInfoCards.RelationshipAccountCard` / `ProfileActivityCards.GrowthLogCard`），
 * 只把外壳换成 [LiuliGroup]、字号色号按图纸 §4.4 映射表换档。
 */

/** 成长日志时刻格式（与暖陶 `growthMdHm` 同串·那个是 private；换算函数 `fmt` 是 internal 直接借·§11 D-13）。 */
private val LIULI_GROWTH_MD_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.getDefault())

// 落值 1:1 暖陶：礼物 >3 件的定高滚动窗 / 时间轴图标列宽 / 竖线高与透明度 / 全部日志弹窗高帽。
private val GIFT_SCROLL_MAX = 108.dp
private val TIMELINE_ICON = 16.dp
private val TIMELINE_LINE = 28.dp
private const val TIMELINE_LINE_ALPHA = 0.08f
private val LOG_DIALOG_MAX = 400.dp

/** 卡内一整块内容的容器：一行 [LiuliRowBase]，上下内距 16、不可点。 */
@Composable
private fun CardBlock(content: @Composable RowScope.() -> Unit) =
    LiuliRowBase(
        divider = false,
        minHeight = 0.dp,
        verticalPadding = LiuliPageGeometry.groupPadH,
        verticalAlignment = Alignment.Top,
        content = content,
    )

/** 亲友账卡「TA 眼里的你」：印象标签 + 收到的礼物 + 件数；标签与礼物**都空时整卡不渲染**（同暖陶）。 */
@Composable
internal fun LiuliRelationshipAccountCard(
    characterName: String,
    tags: List<GiftImpressionTag>,
    gifts: List<GiftRecordEntity>,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty() && gifts.isEmpty()) return
    val colors = AppTheme.colors
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_account_title, characterName)) {
        CardBlock {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag -> ImpressionPill(tag.label) }
                    }
                }
                if (gifts.isNotEmpty()) {
                    if (tags.isNotEmpty()) Hairline()
                    Text(
                        stringResource(R.string.profile_account_gifts_subtitle, characterName),
                        style = AppTypography.secondary.copy(fontWeight = FontWeight.W600),
                        color = colors.text.secondary,
                    )
                    // >3 件套定高滚动（3 行可视·1:1 暖陶 108dp），≤3 直接展开。
                    val giftList: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            gifts.forEach { GiftRow(it, nowMillis) }
                        }
                    }
                    if (gifts.size > 3) {
                        Column(Modifier.heightIn(max = GIFT_SCROLL_MAX).verticalScroll(rememberScrollState())) { giftList() }
                    } else {
                        giftList()
                    }
                    Hairline()
                    Text(
                        stringResource(R.string.profile_account_gift_count, characterName, gifts.size),
                        style = AppTypography.listPreview,
                        color = colors.text.secondary,
                    )
                }
            }
        }
    }
}

/** 卡内分隔发丝（暖陶那边 = `AppListDivider(startInset = 0)`）·资料页各卡共用。 */
@Composable
internal fun Hairline() = Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppTheme.colors.surface.stroke))

/** 印象标签丸（暖陶 `ThemePill` 琉璃档：`accent.container` 底 + `accent.onContainer` 字）。 */
@Composable
private fun ImpressionPill(text: String) {
    val colors = AppTheme.colors
    Box(Modifier.clip(LiuliShapes.pill).background(colors.accent.container).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = AppTypography.secondary, color = colors.accent.onContainer, maxLines = 1)
    }
}

/** 一行礼物：图标 + 名 +（手作徽章）+ 相对时间（时间函数与暖陶同源·只借不改）。 */
@Composable
private fun GiftRow(record: GiftRecordEntity, nowMillis: Long) {
    val colors = AppTheme.colors
    val item = GiftCatalog.find(record.giftItemId)
    val name = item?.name ?: if (record.isDIY) {
        record.diyTitle.ifEmpty { stringResource(R.string.profile_gift_handmade_default) }
    } else {
        stringResource(R.string.profile_gift_default)
    }
    val handmade = record.isDIY || item?.isHandmade == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            GiftSymbolMapping.materialIcon(item?.fallbackSymbol ?: "gift.fill"),
            contentDescription = null,
            tint = colors.economy.gold, // 暖陶那边是 private 字面量 #C9892F；琉璃走语义 token（§9 ⑤）

            modifier = Modifier.size(TIMELINE_ICON),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = AppTypography.listPreview,
            color = colors.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (handmade) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(LiuliShapes.small).background(colors.status.errorContainer).padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    stringResource(R.string.profile_gift_handmade_badge),
                    style = AppTypography.caption.copy(fontWeight = FontWeight.W700),
                    color = colors.status.onError,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            GiftHistoryPromptService.relativeGiftTime(record.timestamp, nowMillis),
            style = AppTypography.secondary,
            color = colors.text.secondary,
        )
    }
}

/** 成长日志卡：最近 5 条时间线（空态两行；>5 开弹窗）。「近况」段末条压舱石，保证这一段永不为空。 */
@Composable
internal fun LiuliGrowthLogCard(log: List<GrowthLogEntry>, modifier: Modifier = Modifier) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val colors = AppTheme.colors
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_growthlog_title)) {
        CardBlock {
            Column(Modifier.fillMaxWidth()) {
                if (log.isEmpty()) {
                    Text(
                        stringResource(R.string.profile_growthlog_empty_1),
                        style = AppTypography.listPreview,
                        color = colors.text.secondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.profile_growthlog_empty_2),
                        style = AppTypography.secondary,
                        color = colors.text.tertiary,
                    )
                } else {
                    val recent = remember(log) { log.takeLast(5).reversed() }
                    recent.forEachIndexed { index, entry -> GrowthLogRow(entry, isLast = index == recent.lastIndex) }
                    if (log.size > 5) {
                        LiuliButton(onClick = { showAll = true }, style = LiuliButtonStyle.Text) {
                            Text(stringResource(R.string.profile_growthlog_view_all, log.size))
                        }
                    }
                }
            }
        }
    }
    if (showAll) {
        val all = remember(log) { log.reversed() }
        LiuliDialog(
            onDismissRequest = { showAll = false },
            title = stringResource(R.string.profile_growthlog_title),
            confirmText = stringResource(R.string.action_close),
            onConfirm = { showAll = false },
            content = {
                Column(Modifier.heightIn(max = LOG_DIALOG_MAX).verticalScroll(rememberScrollState())) {
                    all.forEachIndexed { index, entry -> GrowthLogRow(entry, isLast = index == all.lastIndex) }
                }
            },
        )
    }
}

/** 时间轴一行：左图标 + 竖线，右摘要 + 时刻。 */
@Composable
private fun GrowthLogRow(entry: GrowthLogEntry, isLast: Boolean) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.width(TIMELINE_ICON), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                liuliGrowthEventIcon(entry.type),
                contentDescription = null,
                tint = liuliGrowthEventColor(entry.type),
                modifier = Modifier.size(TIMELINE_ICON),
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(TIMELINE_LINE)
                        .background(colors.text.primary.copy(alpha = TIMELINE_LINE_ALPHA)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            Text(entry.summary, style = AppTypography.listPreview, color = colors.text.primary)
            Spacer(Modifier.height(2.dp))
            Text(
                fmt(entry.timestamp, LIULI_GROWTH_MD_HM),
                style = AppTypography.caption,
                color = colors.text.tertiary,
            )
        }
    }
}

/** 成长事件 → 图标 / 色（与暖陶 `growthEventIcon` / `growthEventColorMap` 同表·那两个是 private·§11 D-13）。 */
private fun liuliGrowthEventIcon(type: GrowthEventType): ImageVector = when (type) {
    GrowthEventType.PERSONALITY_SHIFT -> Icons.Filled.Person
    GrowthEventType.RELATIONSHIP_CHANGE -> Icons.Filled.Favorite
    GrowthEventType.INTEREST_DISCOVERED -> Icons.Filled.AutoAwesome
    GrowthEventType.INTEREST_COOLED -> Icons.Filled.AcUnit
    GrowthEventType.MAJOR_EVENT -> Icons.Filled.Star
    GrowthEventType.GIFT_RECEIVED -> Icons.Filled.CardGiftcard
    GrowthEventType.GIFT_SENT -> Icons.AutoMirrored.Filled.Send
}

private fun liuliGrowthEventColor(type: GrowthEventType): Color = when (type) {
    GrowthEventType.PERSONALITY_SHIFT -> MemColor.Purple
    GrowthEventType.RELATIONSHIP_CHANGE -> MemColor.Pink
    GrowthEventType.INTEREST_DISCOVERED -> MemColor.Orange
    GrowthEventType.INTEREST_COOLED -> MemColor.Cyan
    GrowthEventType.MAJOR_EVENT -> MemColor.Yellow
    GrowthEventType.GIFT_RECEIVED -> MemColor.Red
    GrowthEventType.GIFT_SENT -> MemColor.Pink
}
