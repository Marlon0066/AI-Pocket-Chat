package com.situ.aichat.ui.moments

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.world.WorldGlassChip
import com.situ.aichat.ui.world.WorldSceneColors
import com.situ.aichat.ui.world.WorldSceneColors.CardGradBottom
import com.situ.aichat.ui.world.WorldSceneColors.CardGradMid
import com.situ.aichat.ui.world.WorldSceneColors.CardGradTop
import com.situ.aichat.ui.world.WorldSceneColors.CardShadow
import com.situ.aichat.ui.world.WorldSceneColors.CardText
import com.situ.aichat.ui.world.WorldSceneColors.CardVioletColor
import com.situ.aichat.ui.world.WorldSceneColors.GoldGlow
import com.situ.aichat.ui.world.WorldSceneColors.HaloColorCore
import com.situ.aichat.ui.world.WorldSceneColors.HaloColorMid
import com.situ.aichat.ui.world.WorldSceneColors.MilkyC1
import com.situ.aichat.ui.world.WorldSceneColors.MilkyC2
import com.situ.aichat.ui.world.WorldSceneColors.MilkyC3
import com.situ.aichat.ui.world.gl.PlanetCardTextureView
import kotlin.random.Random

private const val STAR_COUNT = 14 // demo:L125

private data class CardStar(val fx: Float, val fy: Float, val sizeDp: Dp, val phaseMillis: Int)

/**
 * 动态页世界卡（W11 图纸 §4·契约 §16 方案①）：≈332×300 窗景·活星球缓缓自转·左上「世界」题字·
 * 底部半透信息条活文案·整卡点进世界。渐变底/紫雾/银河带/halo/星点 = Compose 自绘背景层（就绪前 & GL 失败
 * 兜底）；星球窗景 = [PlanetCardTextureView]（W9a 星球栈零改·就绪 300ms 淡入·GL 失败隐藏不崩）。卡内恒暗
 * 不随主题；a11y 决策 40 基线（整卡合并 Role.Button + contentDescription·48dp 天然满足）。
 */
@Composable
fun WorldHeroCard(
    worldCard: WorldCardUi?,
    onOpenWorld: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPet: (String) -> Unit = {}, // W12.5（§4.4）：信息条宠物段独立可点 → petDetail（不触发整卡 onOpenWorld）
) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    // staticMode = 省电模式快照（照 StoryReaderScreen.kt:98 既有 composable 惯例·§11 记：不含 thermal，见施工日志）。
    val staticMode = remember {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }
    val frozen = reduceMotion || staticMode

    val title = stringResource(R.string.world_title)
    val subtitle = stringResource(R.string.world_subtitle)
    val infoText = worldCard?.infoLine ?: stringResource(R.string.world_card_info_quiet)
    val a11y = "$title，$subtitle，$infoText"

    val stars = remember(worldCard?.seed) {
        val rnd = Random(worldCard?.seed ?: 42L) // 无 seed → Random(42)（§4.1）
        List(STAR_COUNT) {
            CardStar(
                fx = rnd.nextFloat(),
                fy = rnd.nextFloat(),
                sizeDp = if (rnd.nextFloat() < 0.30f) 2.dp else 1.4.dp, // 30% 2dp 否则 1.4dp（§4.1）
                phaseMillis = (rnd.nextFloat() * 4000f).toInt(),        // 相位差 0..4s（§4.1）
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(332f / 300f)
            .clip(RoundedCornerShape(22.dp)) // §4.1 圆角 22dp·clip 裁窗景溢出
            .background(Brush.verticalGradient(0f to CardGradTop, 0.55f to CardGradMid, 1f to CardGradBottom))
            .clickableScale(role = Role.Button) { onOpenWorld() }
            .semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        val cardHeight = maxHeight
        CardBackdrop()            // 紫雾 + halo（§4.1）
        MilkyBand()               // 银河带·blur 10dp（§4.1·API<31 直出=D-1）
        StarField(stars, frozen)  // 星点 14（§4.1）
        PlanetWindow(worldCard, reduceMotion, staticMode, cardHeight) // 星球窗景（§4.2）
        CardHead(title, subtitle) // 头部（§4.3）
        CardInfoBar(infoText, worldCard?.petTapText, worldCard?.petTapUuid, onOpenPet, frozen) // 信息条（§4.4）
    }
}

/** 紫雾（径向·中心 72%/108%·radius 0.9w）+ halo（径向·中心 50%/56%·直径 78%w）·§4.1。 */
@Composable
private fun BoxScope.CardBackdrop() {
    Canvas(Modifier.matchParentSize()) {
        drawRect(
            Brush.radialGradient(
                0f to CardVioletColor, 0.55f to Color.Transparent,
                center = Offset(size.width * 0.72f, size.height * 1.08f),
                radius = size.width * 0.9f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                0f to HaloColorCore, 0.40f to HaloColorCore, 0.55f to HaloColorMid,
                0.66f to Color.Transparent, 1f to Color.Transparent,
                center = Offset(size.width * 0.5f, size.height * 0.56f),
                radius = size.width * 0.39f, // 直径 78%w
            ),
        )
    }
}

/** 银河带：矩形 top16%/高70dp/宽170%(左-35%)/旋转-13°·水平渐变·blur 10dp（§4.1）。 */
@Composable
private fun BoxScope.MilkyBand() {
    val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(10.dp) else Modifier // D-1
    Canvas(Modifier.matchParentSize().then(blurMod)) {
        val w = size.width
        val h = size.height
        rotate(degrees = -13f, pivot = Offset(w / 2f, h / 2f)) {
            val left = -0.35f * w
            val bandW = 1.70f * w
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent, 0.30f to MilkyC1, 0.50f to MilkyC2, 0.70f to MilkyC3, 1f to Color.Transparent,
                    startX = left, endX = left + bandW,
                ),
                topLeft = Offset(left, 0.16f * h),
                size = Size(bandW, 70.dp.toPx()),
            )
        }
    }
}

/** 星点 14：位置 x∈0..100%/y∈0..55%·闪烁 alpha .85↔.20 全周期4.5s=tween(2250)+Reverse·frozen→恒.5（§4.1）。 */
@Composable
private fun BoxScope.StarField(stars: List<CardStar>, frozen: Boolean) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val w = maxWidth
        val h = maxHeight
        val transition = rememberInfiniteTransition(label = "cardStars")
        stars.forEach { star ->
            val twinkle = transition.animateFloat(
                initialValue = 0.85f,
                targetValue = 0.20f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2250),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(star.phaseMillis),
                ),
                label = "twinkle",
            )
            Box(
                Modifier
                    .offset(x = w * star.fx, y = h * 0.55f * star.fy)
                    .size(star.sizeDp)
                    .graphicsLayer { alpha = if (frozen) 0.5f else twinkle.value }
                    .clip(CircleShape)
                    .background(CardText),
            )
        }
    }
}

/** 星球窗景：卡内下移 9% 卡高·就绪(worldCard 非空)300ms 淡入·GL 失败隐藏（§4.2·E1/E2）。 */
@Composable
private fun BoxScope.PlanetWindow(
    worldCard: WorldCardUi?,
    reduceMotion: Boolean,
    staticMode: Boolean,
    cardHeight: Dp,
) {
    var glFailed by remember { mutableStateOf(false) }
    val show = worldCard != null && !glFailed
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = AppMotion.EaseInOut),
        label = "planetFade",
    )
    if (worldCard != null && !glFailed) {
        val seed = worldCard.seed
        val seedOff = worldCard.seedOff
        AndroidView(
            factory = { ctx -> PlanetCardTextureView(ctx, seed, seedOff, onGlError = { glFailed = true }) },
            modifier = Modifier
                .fillMaxSize()
                .offset(y = cardHeight * 0.09f)
                .graphicsLayer { this.alpha = alpha },
            update = { it.setRenderFlags(reduceMotion, staticMode) },
            onRelease = { it.release() },
        )
    }
}

/** 头部（§4.3）：上14/左16/右14·标题 20sp SemiBold + 副标 11sp .85 letterSpacing .04em·右上 › 20sp .75。 */
@Composable
private fun BoxScope.CardHead(title: String, subtitle: String) {
    val density = LocalDensity.current
    val titleShadow = Shadow(CardShadow, Offset(0f, with(density) { 1.dp.toPx() }), with(density) { 8.dp.toPx() })
    val subShadow = Shadow(CardShadow, Offset(0f, with(density) { 1.dp.toPx() }), with(density) { 6.dp.toPx() })
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 14.dp, start = 16.dp, end = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(color = CardText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, shadow = titleShadow),
            )
            Text(
                subtitle,
                style = TextStyle(color = CardText.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 0.04.em, shadow = subShadow),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "›",
            style = TextStyle(color = CardText.copy(alpha = 0.75f), fontSize = 20.sp),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 信息条（§4.4）：左/右/下10dp·AppShapes.full·WorldGlassChip 配方·前导金点+呼吸+文字 12sp onGlass 单行省略。
 * [petTapText] 非空 = 末段（宠物/蛋）独立可点直达 petDetail（消费点击·不触发整卡）；前缀段照旧 onGlass 单行省略。 */
@Composable
private fun BoxScope.CardInfoBar(
    infoText: String,
    petTapText: String?,
    petTapUuid: String?,
    onOpenPet: (String) -> Unit,
    frozen: Boolean,
) {
    WorldGlassChip(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            .fillMaxWidth(),
        shape = AppShapes.full,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GoldDot(frozen)
            if (petTapText != null && petTapUuid != null) {
                // 末段独立可点 → 前缀（含末尾「 · 」）单行省略 + 金字点线可点段（消费点击）。
                val prefix = infoText.removeSuffix(petTapText)
                if (prefix.isNotEmpty()) {
                    Text(
                        prefix,
                        color = WorldSceneColors.onGlass,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                PetTapSegment(petTapText) { onOpenPet(petTapUuid) }
            } else {
                Text(
                    infoText,
                    color = WorldSceneColors.onGlass,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 可点宠物段（§4.4）：金字 `#EAD9BE` w600 + 点式下划线（`rgba(234,217,190,.6)`·offset≈3dp·drawBehind 自绘·
 * Compose 无原生 dotted）+ 消费点击 → petDetail；cd = 段文本（整卡 mergeDescendants 已含全 infoLine）。 */
@Composable
private fun PetTapSegment(text: String, onClick: () -> Unit) {
    val underline = WorldSceneColors.pcardStatus.copy(alpha = 0.6f)
    Text(
        text,
        color = WorldSceneColors.pcardStatus,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx() // 点线落文字框底≈基线下 3dp（offset·§4.4·drawBehind 不裁）
                drawLine(
                    color = underline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 2.5.dp.toPx()), 0f),
                )
            }
            .semantics { contentDescription = text },
    )
}

/** 前导金点：5dp gold + 径向光晕(半径6dp)·呼吸 alpha 1↔.45 全周期2.6s=tween(1300)+Reverse·frozen→恒1（§4.4）。 */
@Composable
private fun GoldDot(frozen: Boolean) {
    val transition = rememberInfiniteTransition(label = "goldDot")
    val breath = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(animation = tween(1300), repeatMode = RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(Modifier.size(12.dp).graphicsLayer { alpha = if (frozen) 1f else breath.value }) {
        val c = center
        drawCircle(
            brush = Brush.radialGradient(0f to GoldGlow, 1f to Color.Transparent, center = c, radius = 6.dp.toPx()),
            radius = 6.dp.toPx(),
            center = c,
        )
        drawCircle(WorldSceneColors.gold, radius = 2.5.dp.toPx(), center = c)
    }
}
