package com.situ.aichat.ui.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 线下见面「梦剧场」舞台 token 单源（契约 FABLE5_MEETING_THEATER_PROPOSAL.md §4 / 图纸
 * `docs/handoff/2026-07-04-线下见面梦剧场.md` §4.1）。见面屏**恒暗**（不随 App 明暗主题）：一座暖色舞台，
 * 壁纸是布景、文字是字幕、台词是聚光灯。屏内**禁再直引** `MaterialTheme.colorScheme`——用彩、字色、字体、
 * 幕布档一律走本对象。深色暖中性底色取设计语言 §1.2（Espresso `#14110E` / Coffee `#1C1916` / Bark `#242019`）。
 */
object OfflineTheater {

    // ── 幕布与舞台 ──
    /** 幕布暗化色（暖黑·非纯黑·= Palette.Espresso）。 */
    val curtain = Color(0xFF14110E)
    /** 无壁纸舞台竖向渐变（espresso 族过渡值·契约 §3）。 */
    val stageTop = Color(0xFF191512)
    val stageBottom = Color(0xFF221C16)

    /** 幕布三挡 alpha（顶/中/底）·基准档。 */
    val curtainBase = floatArrayOf(0.50f, 0.38f, 0.55f)

    /**
     * 幕布 alpha 亮度自适应（[com.situ.aichat.util.WallpaperBlur.averageLuminance]·BT.601·0..1）·
     * **四档显式值**（R1 拍板 TODO-1·§4.9 v2 对比度按档最不利底脚本互证·2026-07-04）：
     * - null（无亮度/无壁纸）→ 基准副本 [0.50, 0.38, 0.55]
     * - lum > 0.80 → [0.74, 0.62, 0.79]（极亮档·近白壁纸才吃重幕布）
     * - lum > 0.55 → [0.64, 0.52, 0.69]（亮档）
     * - lum <= 0.30 → [0.42, 0.30, 0.47]（暗档）
     * - 否则（0.30 < lum <= 0.55）→ 基准副本 [0.50, 0.38, 0.55]
     * 纯函数（每档返回新数组，防调用方改到共享 [curtainBase]）·不再用 ±delta/上下限算术式·T1。
     */
    fun curtainAlphas(luminance: Float?): FloatArray {
        val lum = luminance ?: return curtainBase.copyOf()
        return when {
            lum > 0.80f -> floatArrayOf(0.74f, 0.62f, 0.79f)
            lum > 0.55f -> floatArrayOf(0.64f, 0.52f, 0.69f)
            lum <= 0.30f -> floatArrayOf(0.42f, 0.30f, 0.47f)
            else -> curtainBase.copyOf()
        }
    }

    // ── 舞台字色 ──
    /** 台词 100%（聚光灯层·最亮）。 */
    val textBright = Color(0xFFF5EFEA)
    /** 叙述旁白。 */
    val textBody = Color(0xFFF0E9E1).copy(alpha = 0.92f)
    /** 环境/独白/心绪签/场景标题。 */
    val textDim = Color(0xFFF0E9E1).copy(alpha = 0.76f)
    /** 装饰线/✦/虚线/名字（纯装饰）。 */
    val textFaint = Color(0xFFF0E9E1).copy(alpha = 0.45f)

    // ── 心绪签 ──
    /** 心绪签/胶囊垫底（= Coffee 85%·故事阅读器 islandScrim 深档同源教训值）。 */
    val scrimPill = Color(0xFF1C1916).copy(alpha = 0.85f)
    /** 心绪签高光描边（玻璃语法）。 */
    val pillStroke = Color(0xFFFFFFFF).copy(alpha = 0.22f)

    // ── 剧场语音（卷三 §4.2·剧场内可回听药丸） ──
    /** 剧场语音波形未播段（暖白 28%·装饰不承载语义）。 */
    val waveIdle = Color(0xFFF0E9E1).copy(alpha = 0.28f)
    /** 剧场语音播放键圆底 alpha（强调色 16%）。 */
    const val voicePlayCircleAlpha = 0.16f
    /** 剧场语音转写小字：楷体 12sp / 行高 18。 */
    val voiceTranscript: TextStyle = AppTypography.kaiQuote.copy(fontSize = 12.sp, lineHeight = 18.sp)

    // ── 强调色 ──
    /** 角色未设主题色时的默认强调色 = 陶土玫浅档（O3 拍板·替 teal #14B8A6）。 */
    val defaultAccent = Color(0xFFC99A86)

    /** 舞台调和：朝暖白混 35%（压饱和/提明度·避免高饱和在暗场上振动）·T1。 */
    fun harmonize(accent: Color): Color = lerp(accent, textBright, 0.35f)

    /**
     * 字幕微影：仅照片类背景（壁纸/角色专属图）加；粒子/纯色舞台不加（底已可控）。
     * offset (0,1dp)·blur 3dp·暖黑 45%——字幕级兜底。
     */
    @Composable
    fun rememberStageTextShadow(): Shadow {
        val density = LocalDensity.current
        return remember(density) {
            Shadow(
                color = curtain.copy(alpha = 0.45f),
                offset = Offset(0f, with(density) { 1.dp.toPx() }),
                blurRadius = with(density) { 3.dp.toPx() },
            )
        }
    }

    /**
     * 无壁纸时的 chrome 玻璃源：4×8px 竖向 stageTop→stageBottom 渐变小图（remember 缓存），供 [com.situ.aichat.ui.designsystem.GlassBackdrop]
     * 放大铺满作模糊底——无壁纸也有恒玻璃 chrome（§4.8）。
     */
    @Composable
    fun rememberStageBackdrop(): ImageBitmap {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        return remember {
            val w = 4
            val h = 8
            val image = ImageBitmap(w, h)
            val canvas = Canvas(image)
            CanvasDrawScope().draw(density, layoutDirection, canvas, Size(w.toFloat(), h.toFloat())) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(stageTop, stageBottom),
                        startY = 0f,
                        endY = h.toFloat(),
                    ),
                )
            }
            image
        }
    }

    // ── 舞台字体（基于 AppTypography 派生·唯一出处·楷体旁白系 + 思源黑台词系） ──
    /** 叙述旁白：楷体 15sp / 行高 26。 */
    val narration: TextStyle = AppTypography.kaiBody.copy(fontSize = 15.sp, lineHeight = 26.sp)
    /** 环境描写：楷体 14sp / 行高 24。 */
    val environment: TextStyle = AppTypography.kaiQuote.copy(lineHeight = 24.sp)
    /** 内心独白：楷体 14sp / 行高 22。 */
    val monologue: TextStyle = AppTypography.kaiQuote.copy(lineHeight = 22.sp)
    /** 心绪签：楷体 13sp / 行高 18。 */
    val moodPill: TextStyle = AppTypography.kaiQuote.copy(fontSize = 13.sp, lineHeight = 18.sp)
    /** 角色/用户台词：思源黑 16sp / 24 / W520。 */
    val dialogue: TextStyle = AppTypography.bodyEmphasis
    /** 场景标题：13sp / letterSpacing 1.5sp / W520。 */
    val sceneHeader: TextStyle = AppTypography.label.copy(fontSize = 13.sp, letterSpacing = 1.5.sp)
    /** 时间流逝：12sp / letterSpacing 2sp。 */
    val timeSkip: TextStyle = AppTypography.caption.copy(fontSize = 12.sp, letterSpacing = 2.sp)
}

/**
 * 解析 6 位 hex 颜色字符串，无效（null / 空 / 非 6 位 / 解析失败）→ null。
 */
internal fun parseHexColorOrNull(hex: String?): Color? {
    val raw = hex ?: return null
    val cleaned = raw.trim().replace("#", "")
    if (cleaned.isEmpty() || cleaned.length != 6) return null
    val value = cleaned.toLongOrNull(radix = 16) ?: return null
    val r = ((value shr 16) and 0xFF) / 255f
    val g = ((value shr 8) and 0xFF) / 255f
    val b = (value and 0xFF) / 255f
    return Color(red = r, green = g, blue = b)
}

/**
 * 从角色的 hex 主题色解析 Color，无效 → 舞台默认强调色（陶土玫浅档·[OfflineTheater.defaultAccent]）。
 * 屏内用彩处一律 `OfflineTheater.harmonize(parseOfflineThemeColor(hex))`（舞台调和）。
 */
fun parseOfflineThemeColor(hex: String?): Color = parseHexColorOrNull(hex) ?: OfflineTheater.defaultAccent
