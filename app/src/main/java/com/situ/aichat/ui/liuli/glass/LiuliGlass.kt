package com.situ.aichat.ui.liuli.glass

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.liuli.designsystem.LocalGlassTier

/** 实时模糊的 API 门：`RenderEffect` 需 Android 12（API 31）。 */
val realtimeBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 解析实际生效档位：没有实时模糊能力时**强制着色**（染色 88% 才能在无模糊下把身后内容压住）。
 * 纯函数便于 T1（[GlassTierTest]）。
 */
internal fun GlassTier.effective(blurSupported: Boolean): GlassTier =
    if (blurSupported) this else GlassTier.TINTED

/**
 * 玻璃片的两档（契约 §4.1·用户 2026-09-06 拍板「圆钮甲」）：
 * - [Panel]：底栏 / 顶栏 / 弹层壳这类大件——8dp 软影 + 平染。
 * - [Button]：40–44dp 的小钮（圆钮 / 麦克风 / 药丸钮）——**影按元素缩到 2dp**、顶半区再亮一层「透镜」、
 *   边缘内侧 0.5dp 白 rim + 外侧发丝减淡。大件的 8dp 影铺在小圆上会成一团灰晕（用户 09-06 指出）。
 */
enum class LiuliGlassStyle { Panel, Button }

/** 玻璃五要素落值（契约 §4.1 逐值·唯一调参点）。 */
object LiuliGlassSpec {
    val blurRadius = 24.dp
    const val SATURATION = 1.5f
    val shadowElevation = 8.dp
    /** 按钮档（[LiuliGlassStyle.Button]）：影 2dp · 顶半区透镜提亮 · 内侧 rim · 外侧发丝减淡。 */
    val buttonShadowElevation = 2.dp
    val buttonLensLight = Color.White.copy(alpha = 0.45f)
    val buttonLensDark = Color.White.copy(alpha = 0.10f)
    const val BUTTON_LENS_STOP = 0.6f
    val buttonRimLight = Color.White.copy(alpha = 0.95f)
    val buttonRimDark = Color.White.copy(alpha = 0.20f)

    val tintLight = Color.White
    val tintLightTinted = Color(0xFFF6F7FA)
    val tintDark = Color(0xFF16191F)
    const val TINT_LIGHT_CLEAR = 0.60f
    const val TINT_LIGHT_TINTED = 0.88f
    const val TINT_DARK_CLEAR = 0.52f
    const val TINT_DARK_TINTED = 0.86f

    val specularLight = Color.White.copy(alpha = 0.80f)
    val specularDark = Color.White.copy(alpha = 0.16f)
    val hairlineLight = Color(0xFF111318).copy(alpha = 0.09f)
    val hairlineDark = Color.White.copy(alpha = 0.10f)
    val hairlineWidth = 0.5.dp
    /** 按钮档外发丝：同一墨色减淡到 6%（派生自 [hairlineLight]·改色只改一处）。 */
    val buttonHairlineLight = hairlineLight.copy(alpha = 0.06f)

    fun tint(dark: Boolean, tier: GlassTier): Pair<Color, Float> = when {
        dark && tier == GlassTier.CLEAR -> tintDark to TINT_DARK_CLEAR
        dark -> tintDark to TINT_DARK_TINTED
        tier == GlassTier.CLEAR -> tintLight to TINT_LIGHT_CLEAR
        else -> tintLightTinted to TINT_LIGHT_TINTED
    }
}

/**
 * 琉璃玻璃片（契约 §4.1 五要素）：① 身后内容实时模糊（取自 [LocalBackdrop] 宿主 layer 的对应切片）②染色
 * ③饱和 ×1.5 ④顶沿 1px 迎光 + 0.5dp 发丝 ⑤软影。不在 [BackdropHost] 里、或 API < 31 时退化为纯染色（着色档）。
 *
 * 顺序：影（形状外）→ 独立渲染层 + 按 [shape] 裁 → 模糊切片 → 染色 → 内容 → 迎光 / 发丝。
 *
 * [tier] 传 null（默认）= 跟随用户在外观页选的档（[LocalGlassTier]）；显式传值只给试验台 / 特殊场景用。
 */
fun Modifier.liuliGlass(
    shape: Shape,
    dark: Boolean,
    tier: GlassTier? = null,
    blurEnabled: Boolean = realtimeBlurSupported,
    style: LiuliGlassStyle = LiuliGlassStyle.Panel,
): Modifier = composed {
    val backdrop = LocalBackdrop.current
    // 不指定档位就跟随用户偏好（外观页「透明度」→ LocalGlassTier）。
    val resolvedTier = tier ?: LocalGlassTier.current
    val blurLayer = rememberGraphicsLayer()
    var posInRoot by remember { mutableStateOf(Offset.Zero) }
    // 有宿主且平台支持才走模糊；否则 host 为 null → 下方一次判空即可（避免「canBlur && backdrop != null」的恒真告警）。
    val host = if (blurEnabled && realtimeBlurSupported) backdrop else null
    val effectiveTier = resolvedTier.effective(blurSupported = host != null)
    val (tintColor, tintAlpha) = LiuliGlassSpec.tint(dark, effectiveTier)
    val specular = if (dark) LiuliGlassSpec.specularDark else LiuliGlassSpec.specularLight
    val isButton = style == LiuliGlassStyle.Button
    val hairline = when {
        dark -> LiuliGlassSpec.hairlineDark
        isButton -> LiuliGlassSpec.buttonHairlineLight
        else -> LiuliGlassSpec.hairlineLight
    }
    val lens = if (dark) LiuliGlassSpec.buttonLensDark else LiuliGlassSpec.buttonLensLight
    val rim = if (dark) LiuliGlassSpec.buttonRimDark else LiuliGlassSpec.buttonRimLight
    val satFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(LiuliGlassSpec.SATURATION) })
    }

    this
        .shadow(if (isButton) LiuliGlassSpec.buttonShadowElevation else LiuliGlassSpec.shadowElevation, shape, clip = false)
        .onGloballyPositioned { posInRoot = it.positionInRoot() }
        // 独立渲染层 + 形状裁切：模糊切片、染色、迎光都被裁在 shape 内。
        .graphicsLayer {
            this.shape = shape
            clip = true
        }
        .drawWithContent {
            if (host != null) {
                // 读 tick：内容层每有新帧，本片重画（读而不写·不成环）。
                @Suppress("UNUSED_VARIABLE")
                val frame = host.tick
                val off = posInRoot - host.hostOrigin
                val radiusPx = LiuliGlassSpec.blurRadius.toPx()
                blurLayer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                blurLayer.colorFilter = satFilter
                blurLayer.record(IntSize(size.width.toInt(), size.height.toInt())) {
                    translate(-off.x, -off.y) { drawLayer(host.layer) }
                }
                drawLayer(blurLayer)
            }
            drawRect(color = tintColor, alpha = tintAlpha)
            if (isButton) {
                // 透镜：顶半区再亮一层（白 45% → 0 到 60% 高处），小钮才有「比纸面亮一层」的玻璃感。
                drawRect(
                    brush = Brush.verticalGradient(0f to lens, LiuliGlassSpec.BUTTON_LENS_STOP to Color.Transparent, startY = 0f, endY = size.height),
                )
            }
            drawContent()
            // 顶沿迎光：1px 硬线（形状之外的部分已被裁掉）。
            val onePx = 1f
            drawRect(color = specular, topLeft = Offset.Zero, size = size.copy(height = onePx))
            // 发丝：沿形状描 1dp 宽、裁后可见 0.5dp。
            val hair = LiuliGlassSpec.hairlineWidth.toPx() * 2
            drawOutline(shape.createOutline(size, layoutDirection, this), color = hairline, style = Stroke(hair))
            if (isButton) {
                // 内侧 0.5dp 白 rim 贴着发丝内缘：发丝裁后占 0…0.5dp，rim 轮廓内缩 0.75dp、笔宽 0.5dp → 覆盖 0.5…1.0dp，
                // 恰好相邻不叠（独立复核 🔵-1：内缩 0.5dp 时两者叠半）。白内边 + 淡外边 = 小钮的「倒角」。
                val inset = LiuliGlassSpec.hairlineWidth.toPx() * 1.5f
                val inner = shape.createOutline(Size(size.width - inset * 2, size.height - inset * 2), layoutDirection, this)
                translate(inset, inset) { drawOutline(inner, color = rim, style = Stroke(LiuliGlassSpec.hairlineWidth.toPx())) }
            }
        }
}
