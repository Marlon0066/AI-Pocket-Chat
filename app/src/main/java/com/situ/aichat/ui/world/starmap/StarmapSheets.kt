package com.situ.aichat.ui.world.starmap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.world.WorldSceneColors
import com.situ.aichat.ui.world.WorldSceneColors.ActEnd
import com.situ.aichat.ui.world.WorldSceneColors.ActStart

/** 站点卡入/出场缓动（§4.7·带过冲 1.2·与 WorldSiteSheet.kt:48 同值重申明）。 */
private val StarSheetEasing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f)

private enum class TagKind { Base, Warm, Cool, Tense }

/**
 * 四种底部卡（你 / 人物 / 关系 / 待相识·W10 图纸 §4.7 全值锁死）。共用暖纸容器（18dp 圆角·sheetSurface·右上 ✕ 48dp·
 * slideInVertically 320ms 过冲 / reduceMotion fade 150ms·内容超高滚动 + maxHeight 56% 屏高）；不改 WorldSiteSheet。
 */
@Composable
internal fun BoxScope.StarmapSheets(card: StarmapCard?, reduceMotion: Boolean, onClose: () -> Unit, onJumpToTown: (String) -> Unit) {
    var last by remember { mutableStateOf<StarmapCard?>(null) }
    if (card != null) last = card
    val shown = last
    AnimatedVisibility(
        visible = card != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduceMotion) fadeIn(tween(150, easing = com.situ.aichat.ui.components.AppMotion.EaseInOut))
        else slideInVertically(tween(320, easing = StarSheetEasing), initialOffsetY = { (it * 1.3f).toInt() }),
        exit = if (reduceMotion) fadeOut(tween(150, easing = com.situ.aichat.ui.components.AppMotion.EaseInOut))
        else slideOutVertically(tween(320, easing = StarSheetEasing), targetOffsetY = { (it * 1.3f).toInt() }),
    ) {
        if (shown != null) StarmapCardShell(shown, onClose, onJumpToTown)
    }
}

@Composable
private fun StarmapCardShell(card: StarmapCard, onClose: () -> Unit, onJumpToTown: (String) -> Unit) {
    val youTitle = stringResource(R.string.world_starmap_you) // 🟡-1：paneTitle 与「你」卡标题同源走 R.string
    val title = cardTitle(card, youTitle)
    val closeCd = stringResource(R.string.world_sheet_close)
    val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.56f
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(WorldSceneColors.sheetSurface)
            .semantics { paneTitle = title },
    ) {
        Column(
            Modifier
                .heightIn(max = maxH)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            when (card) {
                is StarmapCard.You -> YouCard(card, youTitle)
                is StarmapCard.Node -> NodeCard(card)
                is StarmapCard.Edge -> EdgeCard(card)
                is StarmapCard.Pending -> PendingCard(card, onJumpToTown)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).size(48.dp).clickableScale(role = Role.Button, onClick = onClose)) {
            Text("✕", color = WorldSceneColors.sheetClose, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center).semantics { contentDescription = closeCd })
        }
    }
}

private fun cardTitle(card: StarmapCard, youTitle: String): String = when (card) {
    is StarmapCard.You -> youTitle
    is StarmapCard.Node -> card.node.name
    is StarmapCard.Edge -> "${card.edge.aName} ✕ ${card.edge.bName}"
    is StarmapCard.Pending -> card.pending.name
}

// ── 你卡 ──

@Composable
private fun YouCard(card: StarmapCard.You, youTitle: String) {
    CardTitle(youTitle)
    CardSub(stringResource(R.string.world_starmap_you_sub))
    Text(stringResource(R.string.world_starmap_you_body), color = WorldSceneColors.sheetBody, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 9.dp))
    TagRow(Modifier.padding(top = 8.dp)) {
        TagPill(stringResource(R.string.world_starmap_tag_around, card.aroundCount), TagKind.Base)
        TagPill(stringResource(R.string.world_starmap_tag_pending, card.pendingCount), TagKind.Base)
    }
}

// ── 人物卡 ──

@Composable
private fun NodeCard(card: StarmapCard.Node) {
    val node = card.node
    CardTitle(node.name)
    CardSub(node.subtitle)
    TagRow(Modifier.padding(top = 8.dp)) {
        TagPill(stringResource(R.string.world_starmap_with_you, stringResource(StarmapStrings.youTierResId(closenessTier(card.youCloseness)))), TagKind.Base)
        node.milestoneTitle?.let { TagPill(it, TagKind.Base) }
    }
    if (card.rows.isNotEmpty()) {
        DashedDivider(Modifier.padding(top = 10.dp))
        Text(stringResource(R.string.world_starmap_rows_header), color = WorldSceneColors.sheetClose, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
        card.rows.forEachIndexed { i, row ->
            if (i > 0) DashedDivider(Modifier)
            Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                CharacterAvatar(row.otherName, row.otherAvatarPath, 22.dp)
                Text(row.otherName, color = WorldSceneColors.sheetTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
                val status = row.types.joinToString("·") + (colorPhraseText(row.colorRaw)?.let { " · $it" } ?: "")
                Text(status, color = WorldSceneColors.sheetBody, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                Text(stringResource(StarmapStrings.trajectoryResId(row.trajectory)), color = WorldSceneColors.sheetClose, fontSize = 11.sp)
            }
        }
    }
    Text(stringResource(R.string.world_starmap_node_foot), color = WorldSceneColors.sheetClose, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
}

// ── 关系卡 ──

@Composable
private fun EdgeCard(card: StarmapCard.Edge) {
    val e = card.edge
    CardTitle("${e.aName} ✕ ${e.bName}")
    CardSub(stringResource(R.string.world_starmap_edge_sub))
    TagRow(Modifier.padding(top = 8.dp)) {
        TagPill(e.types.joinToString(" · "), TagKind.Base)
        TagPill(stringResource(StarmapStrings.trajectoryResId(e.trajectory)), trajKind(e.trajectory))
        if (e.tension >= 40) TagPill(stringResource(R.string.world_starmap_tense), TagKind.Tense)
    }
    if (e.origin.isNotBlank()) {
        Text(e.origin, color = WorldSceneColors.sheetBody, fontSize = 13.sp, lineHeight = (13 * 1.75f).sp, modifier = Modifier.padding(top = 9.dp))
    }
    DashedDivider(Modifier.padding(top = 10.dp))
    DirRow("${e.aName} → ${e.bName}", e.closenessForward, e.colorForward)
    DirRow("${e.bName} → ${e.aName}", e.closenessReverse, e.colorReverse)
    e.recent?.let { r ->
        Box(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(WorldSceneColors.smRecentBg).padding(horizontal = 11.dp, vertical = 8.dp)) {
            Row {
                Text(stringResource(StarmapStrings.relativeDayResId(r.relativeDay)), color = WorldSceneColors.smTagBaseText, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold)
                Text(" · ${r.summary}", color = WorldSceneColors.sheetBody, fontSize = 12.5f.sp, lineHeight = (12.5f * 1.7f).sp)
            }
        }
    }
}

@Composable
private fun DirRow(who: String, closeness: Int, colorRaw: String) {
    val what = stringResource(StarmapStrings.shortTierResId(closenessTier(closeness))) + (colorPhraseText(colorRaw)?.let { " · $it" } ?: "")
    Row(Modifier.padding(top = 4.dp)) {
        Text(who, color = WorldSceneColors.smTagBaseText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("  $what", color = WorldSceneColors.sheetTitle.copy(alpha = 0.78f), fontSize = 13.sp)
    }
}

// ── 待相识卡 ──

@Composable
private fun PendingCard(card: StarmapCard.Pending, onJumpToTown: (String) -> Unit) {
    val p = card.pending
    CardTitle(p.name)
    CardSub("${p.occupation} · ${p.cityName}")
    Text(
        "「${p.stagePhrase}」",
        color = WorldSceneColors.smTagBaseText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    val body = stringResource(R.string.world_starmap_pending_hint, p.oneLiner) +
        (p.referrerName?.let { stringResource(R.string.world_starmap_pending_referral, it) } ?: "")
    Text(body, color = WorldSceneColors.sheetBody, fontSize = 12.5f.sp, lineHeight = (12.5f * 1.75f).sp, modifier = Modifier.padding(top = 8.dp))
    ActionCta(stringResource(R.string.world_starmap_go_city, p.cityName), Modifier.padding(top = 11.dp)) { onJumpToTown(p.cityId) }
}

/** 主样式动作胶囊（135° 渐变 + 白高光叠层·WorldSiteSheet 主样式字节复刻·§4.7·48dp 触达）。 */
@Composable
private fun ActionCta(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clickableScale(role = Role.Button, onClick = onClick)) {
        Box(Modifier.clip(AppShapes.full).background(Brush.linearGradient(listOf(ActStart, ActEnd)))) {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.28f), 0.42f to Color.Transparent)))
            Text(label, color = WorldSceneColors.sheetTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }
    }
}

// ── 共用小件 ──

@Composable private fun CardTitle(t: String) = Text(t, color = WorldSceneColors.sheetTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
@Composable private fun CardSub(t: String) = Text(t, color = WorldSceneColors.sheetClose, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))

@Composable
private fun TagRow(modifier: Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun TagPill(text: String, kind: TagKind) {
    val (bg, fg, border) = when (kind) {
        TagKind.Base -> Triple(WorldSceneColors.smTagBaseBg, WorldSceneColors.smTagBaseText, WorldSceneColors.smTagBaseBorder)
        TagKind.Warm -> Triple(WorldSceneColors.smTagWarmBg, WorldSceneColors.smTagWarmText, WorldSceneColors.smTagWarmBorder)
        TagKind.Cool -> Triple(WorldSceneColors.smTagCoolBg, WorldSceneColors.smTagCoolText, WorldSceneColors.smTagCoolBorder)
        TagKind.Tense -> Triple(WorldSceneColors.smTagTenseBg, WorldSceneColors.smTagTenseText, WorldSceneColors.smTagTenseBorder)
    }
    Text(
        text, color = fg, fontSize = 11.sp,
        modifier = Modifier.clip(AppShapes.full).background(bg).border(1.dp, border, AppShapes.full).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

private fun trajKind(traj: String): TagKind = when (traj) {
    "warming" -> TagKind.Warm
    "cooling" -> TagKind.Cool
    else -> TagKind.Base
}

@Composable
private fun DashedDivider(modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawLine(
                    WorldSceneColors.sheetBody.copy(alpha = 0.22f),
                    androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                    androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                    1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            },
    )
}

/** colorRaw → 色彩短语（表内查资源 / 表外原样 / 空省略·§4.7）。 */
@Composable
private fun colorPhraseText(colorRaw: String): String? = when (val cp = colorPhraseOf(colorRaw)) {
    is ColorPhrase.Keyed -> stringResource(StarmapStrings.colorResId(cp.keySuffix))
    is ColorPhrase.Raw -> cp.text
    ColorPhrase.Omit -> null
}
