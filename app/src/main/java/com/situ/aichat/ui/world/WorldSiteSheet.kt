package com.situ.aichat.ui.world

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.world.continent.ContinentSite
import com.situ.aichat.ui.world.WorldSceneColors.ActEnd
import com.situ.aichat.ui.world.WorldSceneColors.ActStart

/** 站点卡入/出场缓动（demo:L38 字面量·带过冲 1.2）。 */
private val SheetEasing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f)

/**
 * 底部站点卡（W9b 图纸 §4.6·demo:L35-46 全值）：暖纸面 18dp 圆角·标题 16sp SemiBold + 正文 13sp/行高 22sp·
 * 右上 ✕ 48dp 触达。入/出场 translateY 130%→0（320ms 过冲）·reduceMotion 无位移仅淡入。
 *
 * **W9d 加法**（§4.7·取代 W9c 单 `action`）：[actions]（≤2·`label to onClick`·默认空 = 9b 行为字节不变）——
 * 首个 = 主样式渐变胶囊（与 9c 字节等价）；第二个 = 幽灵胶囊（透明底 + #BE8A76 描边·主钮右 10dp）。
 *
 * **战役 B 🟡-1 修正**（复核 R1·§4.3 幽灵按钮）：[firstActionGhost]=true 时首个动作也走幽灵渲染——用于
 * 「仅送 TA 离开一个动作」的自建居民卡，让破坏性动作不冒充主 CTA（默认 false = 既有全部调用点零改）。
 */
@Composable
internal fun WorldSiteSheet(
    site: ContinentSite?,
    reduceMotion: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    firstActionGhost: Boolean = false,
) {
    var lastShown by remember { mutableStateOf<Triple<ContinentSite, List<Pair<String, () -> Unit>>, Boolean>?>(null) }
    if (site != null) lastShown = Triple(site, actions, firstActionGhost)
    val shown = lastShown

    AnimatedVisibility(
        visible = site != null,
        modifier = modifier,
        enter = if (reduceMotion) {
            fadeIn(tween(150, easing = AppMotion.EaseInOut))
        } else {
            slideInVertically(tween(320, easing = SheetEasing), initialOffsetY = { (it * 1.3f).toInt() })
        },
        exit = if (reduceMotion) {
            fadeOut(tween(150, easing = AppMotion.EaseInOut))
        } else {
            slideOutVertically(tween(320, easing = SheetEasing), targetOffsetY = { (it * 1.3f).toInt() })
        },
    ) {
        if (shown != null) SheetCard(shown.first, shown.second, shown.third, onClose)
    }
}

@Composable
private fun SheetCard(
    site: ContinentSite,
    actions: List<Pair<String, () -> Unit>>,
    firstActionGhost: Boolean,
    onClose: () -> Unit,
) {
    val closeCd = stringResource(R.string.world_sheet_close)
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(WorldSceneColors.sheetSurface)
            .semantics { paneTitle = site.name },
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                site.name,
                color = WorldSceneColors.sheetTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                site.body,
                color = WorldSceneColors.sheetBody,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 4.dp, end = 32.dp),
            )
            if (actions.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp)) {
                    ActionButton(actions[0].first, actions[0].second, ghost = firstActionGhost)
                    actions.getOrNull(1)?.let {
                        Box(Modifier.width(10.dp))
                        ActionButton(it.first, it.second, ghost = true)
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickableScale(role = Role.Button, onClick = onClose),
        ) {
            Text(
                "✕",
                color = WorldSceneColors.sheetClose,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = closeCd },
            )
        }
    }
}

/**
 * 站点卡动作胶囊（§4.6/§4.7）：[ghost]=false = 主样式（135° 渐变 #C99A86→#BE8A76·文字 #2E2925·白高光叠层·
 * demo 大陆 sheet .act 字节等价）；[ghost]=true = 幽灵胶囊（透明底 + 描边 1.5dp #BE8A76·文字 #8A5A48）。
 * 均 13sp SemiBold·内边距 18/8·clickableScale + 48dp 触达。
 */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit, ghost: Boolean) {
    Box(
        Modifier
            .sizeIn(minHeight = 48.dp)
            .clickableScale(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        val body: @Composable () -> Unit = {
            Text(
                label,
                color = if (ghost) WorldSceneColors.ghostText else WorldSceneColors.sheetTitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        if (ghost) {
            Box(Modifier.clip(AppShapes.full).border(1.5.dp, WorldSceneColors.ghostStroke, AppShapes.full)) { body() }
        } else {
            Box(Modifier.clip(AppShapes.full).background(Brush.linearGradient(listOf(ActStart, ActEnd)))) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.28f), 0.42f to Color.Transparent)),
                )
                body()
            }
        }
    }
}
