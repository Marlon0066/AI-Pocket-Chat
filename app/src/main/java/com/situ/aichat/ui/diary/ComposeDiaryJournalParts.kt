package com.situ.aichat.ui.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlin.math.sqrt

/**
 * 日记编写页「手帐质感」二期（契约 [FABLE5_DIARY_JOURNAL_COMPOSE_PROPOSAL.md]·图纸 J2–J4）的自绘器物件——
 * 撕票线 / 拍立得 / 心情邮票三件从 [ComposeDiaryComponents] 抽出（控 UI 行数·全 Canvas/drawBehind 零第三方）。
 * 横线楷体纸面（drawBehind 横线）仍在 [DiaryPaper] 就地（与书写区 Box 同层）。
 *
 * 「间距」口径统一 = 边到边净间隙（gap）：撕票圆点直径 2dp + 净间隙 6dp（pitch 8dp）；胶带细纹线宽 2dp + 净间隙 5dp
 * （周期 7dp·与 mockup line 119 `0 3px, transparent 3px 7px` 的 7px 周期同口径）。
 */

// ---- J2 撕票齿孔线 ----

/** 撕票齿孔线单源尺寸（全部 dp·drawBehind 内经 density 换算，禁写死 px）。 */
private val TEAR_HEIGHT = 14.dp

/** 两端半圆缺口直径（= 行高·radius 7dp 圆心落在左右边缘、半圆出血在外）。 */
private val TEAR_NOTCH_DIAMETER = 14.dp

/** 中线圆点直径。 */
private val TEAR_DOT_DIAMETER = 2.dp

/** 圆点净间隙（边到边·pitch = 直径 + 间隙 = 8dp）。 */
private val TEAR_DOT_GAP = 6.dp

/**
 * D6 撕票齿孔线（图纸 §4-J2）：整宽 14dp 高——中线一排圆点（直径 2dp·净间隙 6dp·`text.primary` 浅 16%/深 14%）
 * 铺满整宽后，两端各盖一枚 `surface.base` 半圆缺口（直径 14dp·圆心在左右边缘出血 7dp）擦掉端头圆点，
 * 视觉上像票根沿此撕下。取代编写页 [DiaryDashedDivider]（阅读页/时间线仍用旧虚线·本卷零碰）。
 */
@Composable
internal fun TearLine(modifier: Modifier = Modifier, notchColor: Color = AppTheme.colors.surface.base) {
    val colors = AppTheme.colors
    val dotColor = colors.text.primary.copy(alpha = if (colors.isDark) 0.14f else 0.16f)
    Box(
        modifier
            .fillMaxWidth()
            .height(TEAR_HEIGHT)
            .drawBehind {
                val cy = size.height / 2f
                val dotRadius = TEAR_DOT_DIAMETER.toPx() / 2f
                val pitch = TEAR_DOT_DIAMETER.toPx() + TEAR_DOT_GAP.toPx()
                // 中线圆点铺满整宽（端头交给缺口擦除）。
                var x = 0f
                while (x <= size.width) {
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(x, cy))
                    x += pitch
                }
                // 两端半圆缺口：surface.base 实圆盖住端头圆点，圆心落在左右边缘（半圆出血在框外）。
                val notchRadius = TEAR_NOTCH_DIAMETER.toPx() / 2f
                drawCircle(color = notchColor, radius = notchRadius, center = Offset(0f, cy))
                drawCircle(color = notchColor, radius = notchRadius, center = Offset(size.width, cy))
            },
    )
}

// ---- J3 拍立得 + 纸胶带 ----

private val POLAROID_IMAGE_SIZE = 88.dp
private val POLAROID_IMAGE_CORNER = 2.dp
private val POLAROID_FRAME_CORNER = 3.dp
private val TAPE_WIDTH = 52.dp
private val TAPE_HEIGHT = 16.dp
private val TAPE_HATCH_WIDTH = 2.dp
private val TAPE_HATCH_GAP = 5.dp
private val TAPE_OFFSET_Y = (-7).dp
private const val TAPE_ROTATION = -5f
private const val TAPE_ALPHA = 0.55f
private const val TAPE_HATCH_ALPHA = 0.25f

/**
 * D2 拍立得单张（图纸 §4-J3）：`appCardSurface` 白框（3dp 圆角·rest 软影）+ 内 padding 上/左/右 6dp·下 18dp +
 * 88dp 图（2dp 圆角）+ 顶边 [TapeStrip] 纸胶带 + topEnd 删除钮；整卡按 [path] hashCode 定恒定微旋转
 * `((hash mod 5)−2)×1.25°`（±2.5° 内·同图恒同角·禁运行时随机）。消费签名不变（[ImageThumbs] 平铺调用）。
 */
@Composable
internal fun PolaroidPhoto(path: String, onRemove: (String) -> Unit) {
    val colors = AppTheme.colors
    val angle = ((path.hashCode().mod(5)) - 2) * 1.25f
    Box(modifier = Modifier.rotate(angle), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .appCardSurface(cornerRadius = POLAROID_FRAME_CORNER)
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 18.dp),
        ) {
            DiaryThumbnail(path, modifier = Modifier.size(POLAROID_IMAGE_SIZE), corner = POLAROID_IMAGE_CORNER)
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.surface.scrim.copy(alpha = 0.5f))
                    .clickable { onRemove(path) }
                    .align(Alignment.TopEnd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppActionIcons.Close,
                    contentDescription = stringResource(R.string.diary_compose_remove_image),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        TapeStrip(path = path, modifier = Modifier.offset(y = TAPE_OFFSET_Y).zIndex(1f))
    }
}

/**
 * 纸胶带（图纸 §4-J3）：52×16dp 半透明莫兰迪底（`[calm, sad, shy, joy][hash mod 4]`@0.55）+ 45° 白细纹
 * （线宽 2dp·净间隙 5dp→周期 7dp·`Color.White`@25%），顶边中点上移 7dp、`rotate(-5°)`（相对拍立得再斜一点）。
 */
@Composable
private fun TapeStrip(path: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val palette = listOf(colors.emotion.calm, colors.emotion.sad, colors.emotion.shy, colors.emotion.joy)
    val base = palette[path.hashCode().mod(4)].copy(alpha = TAPE_ALPHA)
    val hatch = Color.White.copy(alpha = TAPE_HATCH_ALPHA)
    Box(
        modifier = modifier
            .rotate(TAPE_ROTATION)
            .size(width = TAPE_WIDTH, height = TAPE_HEIGHT)
            .clip(RectangleShape)
            .background(base)
            .drawBehind {
                val stroke = TAPE_HATCH_WIDTH.toPx()
                // 45° 平行线：x 截距步距 = 垂直周期(线宽+净间隙) × √2（45° 精确换算·非近似），
                // 使相邻线的垂直净间隙恰为 5dp。线从 (c,0) 斜到 (c+h,h)，clip 到胶带矩形。
                val period = (TAPE_HATCH_WIDTH.toPx() + TAPE_HATCH_GAP.toPx()) * sqrt(2f)
                val h = size.height
                var c = -h
                while (c <= size.width) {
                    drawLine(color = hatch, start = Offset(c, 0f), end = Offset(c + h, h), strokeWidth = stroke)
                    c += period
                }
            },
    )
}

// ---- J4 心情邮票 ----

private val STAMP_CORNER = 4.dp
private val STAMP_PERF_STROKE = 1.5.dp
private val STAMP_EMOJI_SIZE = 22.sp
private const val STAMP_ROTATION = 4f
private const val STAMP_TINT_ALPHA = 0.22f
private const val STAMP_PERF_ALPHA = 0.70f
private const val STAMP_SCALE_FROM = 1.15f

/**
 * D3 心情邮票（图纸 §4-J4）：选中心情后票据日期头右侧盖一枚邮票——齿孔虚线描边（圆角 4dp·stroke 1.5dp·dash[6,4]·
 * `diaryMoodBand`@70%）+ `diaryMoodTint`@0.22 底（叠在洇染 wash 之上）+ emoji 22sp + 下缘 `M·d` 小字（tnum·
 * `text.primary`——secondary 在心情邮票底上普遍跌破 4.5·见图纸 §11 D-J4·同 [DiaryMoodPalette] 房规）。整枚 `rotate(4°)`
 * + 盖章动效（1.15→1 lively·出现同帧 `haptics.light()`·`reduceMotion`→snapTo(1)）。moodText 不再显示在头部
 * （信息在 MoodRow 选中态仍全量可见·有意精简·图纸 §4-J4）。取消心情 = 调用侧随 emoji=null 移除本件（无退场动画）。
 *
 * R1 🔵-1：盖章 + 触觉只在**会话内用户切换心情**时播。[selectTick] 仅随用户点 MoodPill 前进（编辑预置心情/进程
 * 恢复/AI 回填恒为 0）——`tick==0` 的出现（开页/恢复带着已有心情）静置落位、不盖章不震（避免「没做任何操作页面
 * 自己震一下」违背触觉因果礼仪）；`tick≥1` 的心情变化（真人操作）才盖章。切回初值心情不再重播属可接受损耗。
 */
@Composable
internal fun MoodStamp(emoji: String, timestamp: Long, reduceMotion: Boolean, selectTick: Int) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val perfColor = (diaryMoodBand(emoji) ?: colors.accent.primary).copy(alpha = STAMP_PERF_ALPHA)
    val fillColor = (diaryMoodTint(emoji) ?: colors.accent.container).copy(alpha = STAMP_TINT_ALPHA)
    // 初值随入场语境：tick==0（开页/恢复预置心情）起于 1f 免一帧闪；tick≥1（真人切换·含取消后重选）起于放大值再落。
    val scale = remember { Animatable(if (selectTick == 0) 1f else STAMP_SCALE_FROM) }
    LaunchedEffect(selectTick) {
        if (selectTick == 0 || reduceMotion) {
            scale.snapTo(1f)
        } else {
            scale.snapTo(STAMP_SCALE_FROM)
            haptics.light()
            scale.animateTo(1f, AppMotion.livelySpring())
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = STAMP_ROTATION }
            .drawBehind {
                val r = CornerRadius(STAMP_CORNER.toPx())
                drawRoundRect(color = fillColor, cornerRadius = r)
                // 齿孔虚线描边：内缩半线宽（drawBehind 不 clip·防外半被裁）。
                val sw = STAMP_PERF_STROKE.toPx()
                drawRoundRect(
                    color = perfColor,
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(size.width - sw, size.height - sw),
                    cornerRadius = r,
                    style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(emoji, fontSize = STAMP_EMOJI_SIZE, lineHeight = STAMP_EMOJI_SIZE)
        Text(
            formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_stamp)),
            style = AppTheme.typography.caption.copy(fontFeatureSettings = "tnum"),
            color = colors.text.primary,
        )
    }
}
