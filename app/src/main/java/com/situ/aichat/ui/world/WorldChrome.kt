package com.situ.aichat.ui.world

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.world.planet.HomeMarkerVisual

/**
 * 星球屏 Compose 覆盖层 chrome（W9a 图纸 §4.2·色值单源 [WorldSceneColors]·demo 字面量）。
 *
 * 玻璃 chip = demo 冷调 indigo tint（[WorldSceneColors.glassChip]）+ 顶部内高光 1dp + [AppShapes].full。
 * 说明：demo 的 backdrop-filter 实时模糊「星空」在 SurfaceView 上不可行（GlassBackdrop 需静态糊图且色偏暖），
 * 故采用锁定的半透 tint 保证白字可读（图纸 §9 chip 色值一字不改）——见图纸 §11 施工日志登记。
 */

/** 玻璃 chip 外壳：clip(shape) + 冷调 tint + 顶部内高光 1dp（demo:L18-20）。 */
@Composable
fun WorldGlassChip(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.full,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(WorldSceneColors.glassChip)
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(WorldSceneColors.glassHighlight, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            },
        content = content,
    )
}

/**
 * 顶栏方案 A 的大区切换器参数（W15 图纸 §4.1）：标题卡尾部箭头 + 原地向下展开十大区列表。仅大陆场景传非空；
 * 星球/小镇/室内/星图传 null → 标题卡不可点、无箭头、语义保持现状。
 */
class WorldTopBarSwitcher(
    val chips: List<WorldRegionChip>,
    val currentId: String,
    val expanded: Boolean,
    val onToggle: () -> Unit,
    val onSelect: (String) -> Unit,
)

/**
 * 左上：返回钮（48dp 圆 chip）+ 10dp + 标题卡（主 [title] / 副 [subtitle]）·demo:L21-22。W9b 参数化：星球场景
 * 传 world_title/world_subtitle（默认值）；大陆场景传大区名 / flavor（§4.3）。**W15 方案 A（图纸 §4.1）**：
 * 标题卡与返回钮等高（48dp minHeight）、超长单行省略；传 [switcher] 时尾部加下拉箭头，点标题卡原地向下展开
 * 十大区列表（当前项金填充），右上大区胶囊由此合并进标题卡。
 */
@Composable
fun WorldTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.world_title),
    subtitle: String = stringResource(R.string.world_subtitle),
    switcher: WorldTopBarSwitcher? = null,
) {
    val chevron by animateFloatAsState(
        targetValue = if (switcher?.expanded == true) 180f else 0f,
        animationSpec = AppMotion.smoothSpring(),
        label = "chevron",
    )
    // 语义/标签（stringResource = ReadOnlyComposable·条件分支安全）：与被删收起态 chip 逐字等价（§4.1）。
    val currentChip = switcher?.chips?.firstOrNull { it.id == switcher.currentId }
    val switcherLabel = when {
        currentChip == null -> ""
        currentChip.isHome -> stringResource(R.string.world_region_home_title, currentChip.name)
        else -> currentChip.name
    }
    val switcherA11y = stringResource(R.string.world_switcher_a11y, switcherLabel)
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorldGlassChip(
                modifier = Modifier
                    .size(48.dp)
                    .clickableScale(role = Role.Button, onClick = onBack),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = WorldSceneColors.onGlass,
                    modifier = Modifier.align(Alignment.Center).size(20.dp),
                )
            }
            val titleCardMod = if (switcher != null) {
                Modifier
                    .padding(start = 10.dp).weight(1f, fill = false).heightIn(min = 48.dp)
                    .clickableScale(role = Role.Button, onClick = switcher.onToggle)
                    .clearAndSetSemantics { contentDescription = switcherA11y; stateDescription = switcherLabel }
            } else {
                Modifier.padding(start = 10.dp).weight(1f, fill = false).heightIn(min = 48.dp)
            }
            WorldGlassChip(modifier = titleCardMod) {
                Row(
                    Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            title,
                            color = WorldSceneColors.onGlass,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            color = WorldSceneColors.onGlass.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            letterSpacing = 0.04.em,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (switcher != null) {
                        Icon(
                            AppActionIcons.ChevronDown,
                            contentDescription = null,
                            tint = WorldSceneColors.onGlass.copy(alpha = 0.85f),
                            modifier = Modifier.padding(start = 6.dp).size(18.dp).graphicsLayer { rotationZ = chevron },
                        )
                    }
                }
            }
        }
        if (switcher != null) {
            AnimatedVisibility(
                visible = switcher.expanded,
                enter = expandVertically(animationSpec = AppMotion.smoothSpring()) + fadeIn(animationSpec = AppMotion.smoothSpring()),
                exit = shrinkVertically(animationSpec = AppMotion.smoothSpring()) + fadeOut(animationSpec = AppMotion.smoothSpring()),
            ) {
                Column(
                    Modifier
                        .padding(start = 58.dp, top = 8.dp) // 58 = 返回钮 48 + 间距 10（列表左缘对齐标题卡左缘）
                        .fillMaxHeight(0.6f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    for (chip in switcher.chips) {
                        val selected = chip.id == switcher.currentId
                        WorldRegionMenuItem(chip, selected) { switcher.onSelect(chip.id) }
                    }
                }
            }
        }
    }
}

/** 大区列表一项（W15 §4.1·= 原 RegionSwitcherItem 只搬不改·选中项金填充 + sheetTitle 色·未选玻璃 chip）。 */
@Composable
private fun WorldRegionMenuItem(chip: WorldRegionChip, selected: Boolean, onSelect: () -> Unit) {
    val label = chipLabel(chip)
    val base = Modifier
        .sizeIn(minHeight = 48.dp)
        .clickableScale(role = Role.Button, onClick = onSelect)
    if (selected) {
        Box(base.clip(AppShapes.full).background(WorldSceneColors.switcherActive)) {
            Text(
                label, color = WorldSceneColors.sheetTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp).align(Alignment.Center),
            )
        }
    } else {
        WorldGlassChip(modifier = base) {
            Text(
                label, color = WorldSceneColors.onGlass, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp).align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun chipLabel(chip: WorldRegionChip): String =
    if (chip.isHome) stringResource(R.string.world_region_home_title, chip.name) else chip.name

/** 底部中央提示 chip（demo:L38·去「滚轮」）。 */
@Composable
fun WorldHintChip(modifier: Modifier = Modifier) {
    WorldGlassChip(modifier) {
        Text(
            stringResource(R.string.world_hint),
            color = WorldSceneColors.onGlass,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Planet 场景右上：进关系星图入口 chip（✦ + 星图·W10 §4.6·48dp 触达·a11y=打开关系星图）。 */
@Composable
fun WorldStarmapEntryChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a11y = stringResource(R.string.world_starmap_entry_a11y)
    WorldGlassChip(
        modifier = modifier
            .clickableScale(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = a11y },
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✦", color = WorldSceneColors.onGlass, fontSize = 14.sp)
            Text(stringResource(R.string.world_starmap_entry), color = WorldSceneColors.onGlass, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** GL 兜底文案（demo:L45 同义）：设备不支持世界渲染时居中显示（返回钮仍在）。 */
@Composable
fun BoxScope.WorldFallback() {
    Text(
        stringResource(R.string.world_unsupported),
        color = WorldSceneColors.onGlass.copy(alpha = 0.8f),
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
    )
}

/**
 * 家乡标记（demo:L25-32·**W15.3 收缩为纯正面态**——透视幽灵环因与拖拽方向天然镜像已废弃，背面指引改
 * [WorldHomeChevron]）：金点 + 光晕 + 脉冲环（2.4s ease-out 无限扩散）+ 标签；跨地平线由 [visual]
 * front/label 双通道连续淡出（与雪佛龙交叉渐变·恒不硬切）。标签恒占位只变透明度 → 锚点几何稳定。
 * 位置由调用方 [modifier] 定（投影屏幕坐标）；[reduceMotion] → 无脉冲环。点击（front≥0.5 才可点）→
 * [onEnter] 进大陆。a11y「在星球上」。
 */
@Composable
internal fun WorldHomeMarker(
    cityName: String,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    visual: HomeMarkerVisual = HomeMarkerVisual(front = 1f, label = 1f, ghost = 0f),
    onEnter: (() -> Unit)? = null,
) {
    val a11y = stringResource(R.string.world_home_marker_a11y, cityName)
    val enterLabel = stringResource(R.string.world_enter_continent)
    val labelShadow = with(LocalDensity.current) {
        Shadow(WorldSceneColors.background.copy(alpha = 0.9f), Offset(0f, 1.dp.toPx()), 6.dp.toPx())
    }
    // W15.3：不带 clickable（Compose 叠层会在命中测试里遮蔽 GLView·吞掉「按住家标记拖球」）——
    // 触摸点击由 PlanetGLView.onTapListener 统一路由，这里只保留 a11y 语义动作。
    val clickModifier = if (onEnter != null && visual.front >= 0.5f) {
        modifier.clearAndSetSemantics { contentDescription = a11y; role = Role.Button; onClick(enterLabel) { onEnter(); true } }
    } else {
        modifier.clearAndSetSemantics { contentDescription = a11y }
    }
    Column(
        clickModifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), // 48dp 最小触达（§4.5）·内容居中锚定使金点仍钉投影点。
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!reduceMotion && visual.front > 0.01f) {
                val transition = rememberInfiniteTransition(label = "homePulse")
                val frac by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2400, easing = AppMotion.EaseOut), RepeatMode.Restart),
                    label = "pulse",
                )
                Box(
                    Modifier
                        .size(26.dp)
                        .graphicsLayer {
                            val s = 0.4f + 1.1f * frac
                            scaleX = s; scaleY = s
                            alpha = 0.95f * (1f - frac) * visual.front
                        }
                        .border(1.5.dp, WorldSceneColors.gold, CircleShape),
                )
            }
            // 光晕 + 实心金点 · alpha = front（跨地平线淡出）
            Box(
                Modifier.size(26.dp).graphicsLayer { alpha = visual.front }.drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(WorldSceneColors.gold.copy(alpha = 0.8f), Color.Transparent),
                            radius = size.minDimension / 2f,
                        ),
                    )
                },
            )
            Box(Modifier.size(8.dp).clip(CircleShape).graphicsLayer { alpha = visual.front }.background(WorldSceneColors.gold))
        }
        Text(
            stringResource(R.string.world_home_marker, cityName),
            style = TextStyle(color = WorldSceneColors.onGlass, fontSize = 11.sp, shadow = labelShadow),
            modifier = Modifier.graphicsLayer { alpha = visual.label }, // 恒占位只变透明度=锚点稳定
        )
    }
}

/**
 * 家的边缘指路雪佛龙（W15.3·取代透视幽灵环）：家在背面时钉在屏缘/球缘、指向家所在方向的金色「〉」。
 * 语义与抓取拖拽自洽——往箭头那侧把球面拉过来，家就从那条边升起。点击 → [onSpinHome] 星球自动转回家
 * 正面（既有 cinematic）。[angleRad] = 指向屏外的方向角；[alpha] 与正面标记交叉渐变。a11y「在星球背面」+
 * 点按动作「转到家的正面」。
 */
@Composable
internal fun WorldHomeChevron(
    cityName: String,
    angleRad: Float,
    alpha: Float,
    onSpinHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a11y = stringResource(R.string.world_home_marker_back_a11y, cityName)
    val spinLabel = stringResource(R.string.world_spin_home)
    // 触摸点击走 PlanetGLView.onTapListener 路由（同标记·防叠层吞拖动）；此处只留 a11y 语义。
    Box(
        modifier
            .size(48.dp) // 最小触达
            .graphicsLayer { this.alpha = alpha; rotationZ = angleRad * 180f / Math.PI.toFloat() }
            .clearAndSetSemantics { contentDescription = a11y; role = Role.Button; onClick(spinLabel) { onSpinHome(); true } },
        contentAlignment = Alignment.Center,
    ) {
        // 「〉」双折线（本地 +x = 指向屏外）·金 90% + 深底细描边保对比
        Box(
            Modifier.size(20.dp).drawBehind {
                val w = size.width
                val h = size.height
                val stroke = Stroke(width = 2.5.dp.toPx())
                val path = Path().apply {
                    moveTo(w * 0.30f, h * 0.12f)
                    lineTo(w * 0.78f, h * 0.50f)
                    lineTo(w * 0.30f, h * 0.88f)
                }
                drawPath(path, WorldSceneColors.background.copy(alpha = 0.8f), style = Stroke(width = 4.5.dp.toPx()))
                drawPath(path, WorldSceneColors.gold.copy(alpha = 0.9f), style = stroke)
            },
        )
    }
}
