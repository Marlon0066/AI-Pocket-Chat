package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fable-5「白瓷药丸」材质（分段控件 / 选择标签选中态·对版稿
 * `fable5_artifacts/mockups/segmented_control_porcelain_mockup.html` 用户过审 2026-09-03）。
 *
 * **一句话**：陶盘里的一枚瓷片——凹槽有顶沿内阴影**真的凹下去**，选中药丸有釉面渐变 / 陶土暖边 / 软影
 * **真的浮起来**。取代旧「浅陶土 container 平填充」（`#F0DDD3` 对 `#F1ECE4` 亮度几乎相同 → 观感是「槽里
 * 一块淡印子」而非「一块东西压在槽上」）。
 *
 * **消费方**：[porcelainTrack] = [AppSegmentedControl] 凹槽轨；[porcelainThumb] = 该控件滑动药丸 +
 * [AppChoiceChip] 选中胶囊。两者恒配 [AppShapes.full] 胶囊（形状不开放形参——月光沿与影的圆角半径
 * 按 `height / 2` 求，非胶囊会画歪）。
 *
 * **越出设计语言 v2 的三处（用户 2026-09-03 拍板 = 语言进化·仅限本族不推广）**：
 * ① v2 §1「sunken 表面永远无影」→ 凹槽上顶沿内阴影；
 * ② v2 §3「釉只上 raised hero 与主钮」→ 药丸上釉面渐变 + 软影（强度 ≤ [AppElevation] rest 档一半）；
 * ③ v2「深色无投影」→ 深色档药丸留 1dp 接触影（落在更深的凹槽里，看得见）。
 *
 * **前提**：两个 Modifier 都假定 **宽 ≥ 高**（胶囊而非竖条）——月光沿按 `min(w,h)/2` 求圆角并带 `width > height`
 * 守卫，宽 < 高时不画（不会戳出弧外）。分段控件段数过多导致段宽 < 高时，本件不适用（另裁可滚动变体）。
 *
 * **实现取舍（诚实登记·复核 R1 补全至六条）**：
 * - 内阴影用**顶沿纵向渐变**而非 native `setShadowLayer` + EVEN_ODD 反向路径——渐变零风险（不吃硬件加速对
 *   复杂 path + maskFilter 的支持差异），弧段被 [clipPath] 裁后暗带正好贴弧上缘。**形近但非等价**：按 CSS
 *   inset shadow 的高斯覆盖积分对比，线性渐变的总墨量偏重约 38%（浅档 0.2025 vs 规范 0.1463）——这是渐变
 *   代替高斯的固有差，alpha 保持对版稿锁定值不做补偿（实机四主题走查观感与对版稿一致）。
 * - 药丸按压走 `pressed: Boolean` 两档而非逐帧插值——[drawWithCache] 的 `BlurMaskFilter` 属重对象
 *   （范式同 [appCardSurface]「绝不逐帧新建」），布尔档全程只重建 2 次；按压的连续感由调用方的 scale
 *   弹簧承担，180ms 内影子跳档在 0.975 微缩下不可辨。
 * - 暖边浅档统一 `accent.primary @32%`（对版稿青花浅为 30%——2% 差值在 0.5dp 发丝上不可辨，不为主题分叉）。
 * - **影与内阴影的墨色恒 [AppElevation.shadowInk] 暖墨**，不随主题走冷墨（对版稿青花档用 `#1E2A3C` 冷墨）——
 *   遵 [AppElevation] 「影子是环境光的缺席，不随主题 accent 变」的既有规矩；实测叠加后差 ~2/255 不可辨。
 * - **按压过渡走 [AppMotion.calmSpring] 而非对版稿的 180ms 贝塞尔**——项目动效走弹簧档不走 tween 是既有惯例
 *   （收敛 ~317ms vs 180ms）；幅度仅 0.975 微缩，观感差异在可接受区间。调用方若要改档须同步图纸。
 * - **药丸影可溢出轨外**：[porcelainTrack] **不裁子内容**（对版稿 `.seg` 同样无 `overflow:hidden`），
 *   裁剪由调用方下放到标签行——否则 4dp 内缩装不下 σ≈4dp 的外扩软影，「浮起」会被裁成「贴底」。
 */
object AppPorcelain {

    // ── 凹槽顶沿内阴影（光恒来自正上方 → 只暗上沿）·**几何深浅分叉**（对版稿四主题本就不同档）──
    /** 浅档内阴影偏移 + 模糊 = 暗带总高 4.5dp。 */
    val trackInsetY = 1.5.dp
    val trackInsetBlur = 3.dp
    /** 深档更紧更短（暗带 3dp）——深底上同样的墨量会把槽压得过黑。 */
    val trackInsetYDark = 1.dp
    val trackInsetBlurDark = 2.dp
    const val TRACK_INSET_ALPHA_LIGHT = 0.09f
    const val TRACK_INSET_ALPHA_DARK = 0.38f
    /** 浅档凹槽内衬发丝（深档改走 `surface.stroke` 描边）。 */
    const val TRACK_HAIRLINE_ALPHA_LIGHT = 0.03f

    // ── 药丸接触影（紧贴一层·浮起的第一证据）──
    val thumbContactY = 1.dp
    val thumbContactBlur = 2.dp
    const val THUMB_CONTACT_ALPHA_LIGHT = 0.10f
    const val THUMB_CONTACT_ALPHA_DARK = 0.42f

    // ── 药丸软影（外扩一层·浅档专有；深档无投影只留接触影）──
    val thumbSoftY = 2.dp
    val thumbSoftBlur = 6.dp
    const val THUMB_SOFT_ALPHA = 0.07f

    // ── 按压：软影收掉、接触影变短变淡（药丸被按进槽里）──
    val thumbPressY = 1.dp
    /** 深档按下时影不下坠（y=0）——贴合感更强。 */
    val thumbPressYDark = 0.dp
    val thumbPressBlur = 1.dp
    const val THUMB_PRESS_ALPHA_LIGHT = 0.08f
    const val THUMB_PRESS_ALPHA_DARK = 0.30f

    // ── 药丸描边与顶边高光 ──
    /** 陶土暖边：把白瓷与主色系起来，也给药丸在浅槽里一道清楚轮廓（浅档 accent.primary）。 */
    const val THUMB_RIM_ALPHA_LIGHT = 0.32f
    /** 深档改走 text.primary 极低 alpha（陶土边在暗底上会脏）。 */
    const val THUMB_RIM_ALPHA_DARK = 0.09f
    /** 深色档「月光沿」顶边内高光。**高于** [AppElevation.MOONLINE_ALPHA]（5%）——药丸远小于卡片，
     * 5% 在 48dp 高的小件上不可见；12% 为对版稿实测值，故另立常量不复用。 */
    const val THUMB_MOONLINE_ALPHA = 0.12f
    val moonlineWidth = 1.dp

    // ── 动效（滑行本身恒走 AppMotion.calmSpring 不在此列）──
    /** 按压：药丸下沉（取代旧「整格文字缩 0.96」）。 */
    const val PRESS_SCALE = 0.975f
    /** 按压时段文字的轻缩（旧值 0.96 → 药丸已承担主要反馈，文字退让）。 */
    const val PRESS_LABEL_SCALE = 0.97f
    /** 滑行拉长：沿运动方向拉 6.5% / 压扁 3.5%，像一滴水滑过去。 */
    const val SQUASH_X = 0.065f
    const val SQUASH_Y = 0.035f
    /** squash keyframes：0 →(峰)→ 0，全程与 calm 弹簧的可感行程对齐。 */
    const val SQUASH_DURATION_MS = 360
    const val SQUASH_PEAK_MS = 137

    // ══ 釉烧陶土（主行动钮专用·[Modifier.glazedClay] 与 [AppButton] Primary 档消费·2026-09-05 拍板①②）══

    /** 面三段纵向渐变的中段止点（对版稿 `46%`）——顶段短、腰段长，重心压在下半。 */
    const val GLAZE_MID_STOP = 0.46f
    /** 底段派生比例：`gradientEnd` 向影墨（深档向纯黑）混过去这么多，得「积釉压深」的第三段。 */
    const val GLAZE_BOTTOM_MIX_LIGHT = 0.10f
    const val GLAZE_BOTTOM_MIX_DARK = 0.12f

    /** 顶沿釉光 / 底沿积釉的带高（各一道 1dp 发丝带·代替 CSS 的 `inset 0 ±1px 0`）。 */
    val glazeEdgeHeight = 1.dp
    const val GLAZE_TOP_ALPHA_LIGHT = 0.42f
    const val GLAZE_TOP_ALPHA_DARK = 0.20f
    /** 底沿墨：浅档走 [AppAccentColors.deepEnd] 深陶，深档走纯黑（深陶在暗底上看不出来）。 */
    const val GLAZE_BOTTOM_ALPHA_LIGHT = 0.30f
    const val GLAZE_BOTTOM_ALPHA_DARK = 0.32f

    // ── 外影：浅档双层（接触 + 软），深档单层 ──
    val glazeContactY = 1.dp
    val glazeContactBlur = 2.dp
    const val GLAZE_CONTACT_ALPHA = 0.16f
    val glazeSoftY = 3.dp
    val glazeSoftBlur = 8.dp
    const val GLAZE_SOFT_ALPHA = 0.18f
    /** 深档单层影（v2「深色无投影」在此半步保留·处置同 [porcelainThumb]）。 */
    val glazeShadowYDark = 2.dp
    val glazeShadowBlurDark = 6.dp
    const val GLAZE_SHADOW_ALPHA_DARK = 0.42f

    // ── 按下：影全撤、顶沿翻成内陷暗带、底沿翻白（光从下面漏进来）──
    val glazePressInsetHeight = 3.dp
    const val GLAZE_PRESS_INSET_ALPHA = 0.34f
    const val GLAZE_PRESS_BOTTOM_ALPHA = 0.16f
    /** 按下位移（由调用方 `graphicsLayer` 消费·配 [AppButton] 既有 0.97 缩放，不另立弹簧）。 */
    val glazePressOffsetY = 1.dp

    /** 釉面颗粒 alpha。**另立常量不复用** [AppGrain.LIGHT_ALPHA]（1.5%）——那一档是给整屏底垫定的，
     * 按钮面积小得多，1.5% 在 40dp 高的胶囊上完全不可感；6% 为对版稿 `.16` overlay 的等效近似。 */
    const val GLAZE_GRAIN_ALPHA = 0.06f

    /** 压印字：字下 1dp 处的硬边亮线（`blurRadius = 0f`·等效 CSS `text-shadow: 0 1px 0`）。 */
    val glazeEmbossOffsetY = 1.dp
    const val GLAZE_EMBOSS_ALPHA_LIGHT = 0.26f
    const val GLAZE_EMBOSS_ALPHA_DARK = 0.30f
}

/**
 * 凹槽轨（[AppColors.surface] sunken 底 + 顶沿内阴影 + 发丝内衬）。
 *
 * 绘制顺序同 [appCardSurface] 范式：`drawWithCache`（底 + 内阴影，自画圆角）→ `border`（描边）——
 * 底绝不后于描边画，否则盖住边。
 *
 * **不裁子内容**（无链尾 `clip`）：药丸的外扩软影 σ≈4dp 必须溢出轨外才撑得起「浮在槽上」，而药丸内缩仅
 * 4dp。调用方须自行把 `clip(AppShapes.full)` 下放到**标签行**（只裁文字不裁药丸）——对版稿同此结构
 * （`.seg` 无 `overflow:hidden`，裁剪在 `.cell` 上）。
 */
@Composable
fun Modifier.porcelainTrack(): Modifier {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val sunken = colors.surface.sunken
    val hairline = if (isDark) {
        colors.surface.stroke
    } else {
        AppElevation.shadowInk.copy(alpha = AppPorcelain.TRACK_HAIRLINE_ALPHA_LIGHT)
    }
    val insetColor = (if (isDark) Color.Black else AppElevation.shadowInk).copy(
        alpha = if (isDark) AppPorcelain.TRACK_INSET_ALPHA_DARK else AppPorcelain.TRACK_INSET_ALPHA_LIGHT,
    )
    val insetY = if (isDark) AppPorcelain.trackInsetYDark else AppPorcelain.trackInsetY
    val insetBlur = if (isDark) AppPorcelain.trackInsetBlurDark else AppPorcelain.trackInsetBlur
    return this
        .drawWithCache {
            val radius = minOf(size.width, size.height) / 2f
            val bandHeight = (insetY + insetBlur).toPx()
            val insetBrush = Brush.verticalGradient(
                colors = listOf(insetColor, Color.Transparent),
                startY = 0f,
                endY = bandHeight,
            )
            val capsule = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius, radius)))
            }
            onDrawBehind {
                drawRoundRect(color = sunken, cornerRadius = CornerRadius(radius, radius))
                // 顶沿暗带裁进胶囊内——两端弧上缘自然跟随（见 KDoc 实现取舍）。
                clipPath(capsule) {
                    drawRect(brush = insetBrush, size = Size(size.width, bandHeight))
                }
            }
        }
        .border(AppElevation.hairlineWidth, hairline, AppShapes.full)
}

/**
 * 白瓷药丸（釉面纵向渐变 + 陶土暖边 + 软影 + 深档月光沿）。
 *
 * @param pressed 手指按住时 true——软影收掉、接触影变短变淡（配调用方 [AppPorcelain.PRESS_SCALE] 缩放）。
 * @param raised false = 禁用态：去掉全部投影只留描边（禁用的东西不该还浮着）。
 */
@Composable
fun Modifier.porcelainThumb(pressed: Boolean = false, raised: Boolean = true): Modifier {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val glazeTop = colors.surface.glaze
    val glazeBottom = colors.surface.glazeShade
    val rim = if (isDark) {
        colors.text.primary.copy(alpha = AppPorcelain.THUMB_RIM_ALPHA_DARK)
    } else {
        colors.accent.primary.copy(alpha = AppPorcelain.THUMB_RIM_ALPHA_LIGHT)
    }
    val moonline = colors.text.primary.copy(alpha = AppPorcelain.THUMB_MOONLINE_ALPHA)
    return this
        .drawWithCache {
            val radius = minOf(size.width, size.height) / 2f
            // 影层 Paint 在 cache 域一次构建（BlurMaskFilter 属重对象·范式同 appCardSurface）。
            fun paintOf(color: Color, alpha: Float, blur: Dp) =
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.copy(alpha = alpha).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(
                        blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
            val ink = if (isDark) Color.Black else AppElevation.shadowInk
            val shadowPaints: List<Pair<android.graphics.Paint, Float>> = when {
                !raised -> emptyList()
                pressed -> listOf(
                    paintOf(
                        ink,
                        if (isDark) AppPorcelain.THUMB_PRESS_ALPHA_DARK else AppPorcelain.THUMB_PRESS_ALPHA_LIGHT,
                        AppPorcelain.thumbPressBlur,
                    ) to (if (isDark) AppPorcelain.thumbPressYDark else AppPorcelain.thumbPressY).toPx(),
                )
                else -> buildList {
                    add(
                        paintOf(
                            ink,
                            if (isDark) AppPorcelain.THUMB_CONTACT_ALPHA_DARK else AppPorcelain.THUMB_CONTACT_ALPHA_LIGHT,
                            AppPorcelain.thumbContactBlur,
                        ) to AppPorcelain.thumbContactY.toPx(),
                    )
                    // 外扩软影只在浅档（深底上看不见·v2「深色无投影」在此半步保留）。
                    if (!isDark) {
                        add(
                            paintOf(ink, AppPorcelain.THUMB_SOFT_ALPHA, AppPorcelain.thumbSoftBlur)
                                to AppPorcelain.thumbSoftY.toPx(),
                        )
                    }
                }
            }
            val glaze = Brush.verticalGradient(listOf(glazeTop, glazeBottom))
            val moonWidth = AppPorcelain.moonlineWidth.toPx()
            onDrawBehind {
                shadowPaints.forEach { (paint, dy) ->
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.translate(0f, dy)
                        native.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
                        native.restore()
                    }
                }
                drawRoundRect(brush = glaze, cornerRadius = CornerRadius(radius, radius))
                // 禁用态一并去掉月光沿（对版稿 `.disabled .face` 只留暖边）；宽 < 高时不画（否则线反向戳出弧外）。
                if (isDark && raised && size.width > size.height) {
                    // 月光沿：顶边内高光，两端从圆角起笔（不出弧）——深色里没有影子可用，靠这一线立层级。
                    drawLine(
                        color = moonline,
                        start = Offset(radius, moonWidth / 2f),
                        end = Offset(size.width - radius, moonWidth / 2f),
                        strokeWidth = moonWidth,
                    )
                }
            }
        }
        .border(AppElevation.hairlineWidth, rim, AppShapes.full)
        .clip(AppShapes.full)
}

/**
 * 釉烧陶土（主行动钮的面·对版稿 `fable5_artifacts/mockups/form_bar_save_button_sketch.html` 方案①
 * 用户过审 2026-09-05）。取代旧「一道 135° 线性渐变」——同一块陶土，现在**烧过釉**了：
 * 顶沿一道釉光、底沿一圈积釉、面上三段渐变把重心压向下半、釉里有细颗粒。
 *
 * **消费方**：[AppButton] 的 [AppButtonStyle.Primary] 档，**仅此一档**（Tonal 浅陶填充 / Text 透明 /
 * Warning 深琥珀实底各有已过审语义，不上釉）。改这一处 = 全 App 121 个主钮同时换脸。
 *
 * **面色回归 token，不照抄对版稿手调值**：对版稿 CSS 的 `#D0A28E / #C18D78 / #B5806A` 是在浏览器里目测调的，
 * 比既有 token 亮约 3%。这里回归 [AppAccentColors] 的 `gradientStart`（顶）/ `gradientEnd`（腰）+ 一枚
 * **派生底段**（`lerp(gradientEnd, 影墨, 10%)`；深档向纯黑混 12%）——理由同 `AppMomentIcons` 先例：
 * mockup 的 CSS 是近似值，家族 token 一致优先，否则主题切换（青花等）时这三个字面量当场穿帮。
 *
 * **越出设计语言 v2 的两处（用户 2026-09-05 拍板 = 语言进化·已回填 `FABLE5_DESIGN_LANGUAGE.md`）**：
 * ① v2「Primary = 一道线性渐变」→ 三段纵向渐变 + 两道沿 + 两层外影；
 * ② [AppGrain] 军规「只垫 surface 底层」→ 本件把 6% grain 叠在**按钮面**上（仍在文字/图标**之下**）。
 *
 * **实现取舍（诚实登记·风格同 [porcelainThumb]）**：
 * - 两道沿用**边缘纵向渐变**而非 native inset 阴影 —— 同 [porcelainTrack] 的取舍（渐变零风险、不吃硬件加速
 *   对 path + maskFilter 的支持差异），1dp 带高下渐变与高斯的差异不可辨。
 * - 按下态用「**内陷顶沿**」（顶部 3dp 暗带）代替 CSS 的 `inset 0 1px 3px` —— 同上；配调用方的
 *   `translationY = ` [AppPorcelain.glazePressOffsetY] 与既有 0.97 缩放，合起来是「按进去了」。
 * - 影层 `BlurMaskFilter` Paint **只在 [drawWithCache] 域构建**，绝不逐帧新建（重对象·范式同 [porcelainThumb]）；
 *   按下 / 禁用只是换一份 Paint 列表（两档布尔，不逐帧插值）。
 * - **禁用态本件只负责去影**，不重复降透明 —— 整体 40% 由 [AppButton] 既有 `.alpha(0.4f)` 承担
 *   （语义同 `porcelainThumb(raised = false)`：禁用的东西不该还浮着）。
 * - 圆角取 `min(w, h) / 2`：胶囊与正圆都适配（同 [porcelainThumb]）。
 *
 * @param pressed 手指按住时 true —— 撤掉全部外影，改画内陷顶沿 + 翻白底沿。
 * @param enabled false = 禁用态：不画任何影（透明度由调用方管）。
 */
@Composable
fun Modifier.glazedClay(pressed: Boolean = false, enabled: Boolean = true): Modifier {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val faceTop = colors.accent.gradientStart
    val faceMid = colors.accent.gradientEnd
    val faceBottom = lerp(
        colors.accent.gradientEnd,
        if (isDark) Color.Black else AppElevation.shadowInk,
        if (isDark) AppPorcelain.GLAZE_BOTTOM_MIX_DARK else AppPorcelain.GLAZE_BOTTOM_MIX_LIGHT,
    )
    val topGlaze = Color.White.copy(
        alpha = if (isDark) AppPorcelain.GLAZE_TOP_ALPHA_DARK else AppPorcelain.GLAZE_TOP_ALPHA_LIGHT,
    )
    val bottomPool = if (isDark) {
        Color.Black.copy(alpha = AppPorcelain.GLAZE_BOTTOM_ALPHA_DARK)
    } else {
        colors.accent.deepEnd.copy(alpha = AppPorcelain.GLAZE_BOTTOM_ALPHA_LIGHT)
    }
    val pressInset = colors.accent.deepEnd.copy(alpha = AppPorcelain.GLAZE_PRESS_INSET_ALPHA)
    val pressBottom = Color.White.copy(alpha = AppPorcelain.GLAZE_PRESS_BOTTOM_ALPHA)
    return this
        .drawWithCache {
            val radius = minOf(size.width, size.height) / 2f
            // 影层 Paint 在 cache 域一次构建（BlurMaskFilter 属重对象·范式同 porcelainThumb）。
            fun paintOf(color: Color, alpha: Float, blur: Dp) =
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.copy(alpha = alpha).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(
                        blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
            val shadowPaints: List<Pair<android.graphics.Paint, Float>> = when {
                !enabled || pressed -> emptyList()
                isDark -> listOf(
                    paintOf(
                        Color.Black, AppPorcelain.GLAZE_SHADOW_ALPHA_DARK, AppPorcelain.glazeShadowBlurDark,
                    ) to AppPorcelain.glazeShadowYDark.toPx(),
                )
                else -> listOf(
                    paintOf(
                        AppElevation.shadowInk, AppPorcelain.GLAZE_CONTACT_ALPHA, AppPorcelain.glazeContactBlur,
                    ) to AppPorcelain.glazeContactY.toPx(),
                    paintOf(
                        AppElevation.shadowInk, AppPorcelain.GLAZE_SOFT_ALPHA, AppPorcelain.glazeSoftBlur,
                    ) to AppPorcelain.glazeSoftY.toPx(),
                )
            }
            val face = Brush.verticalGradient(
                0f to faceTop,
                AppPorcelain.GLAZE_MID_STOP to faceMid,
                1f to faceBottom,
                startY = 0f,
                endY = size.height,
            )
            val edgeHeight = AppPorcelain.glazeEdgeHeight.toPx()
            // 顶沿：常态是釉光（白→透明），按下翻成内陷暗带（深陶→透明·更高更重）。
            val topBandHeight = if (pressed) AppPorcelain.glazePressInsetHeight.toPx() else edgeHeight
            val topBrush = Brush.verticalGradient(
                colors = listOf(if (pressed) pressInset else topGlaze, Color.Transparent),
                startY = 0f,
                endY = topBandHeight,
            )
            // 底沿：常态积釉（透明→深陶），按下翻白。起止点写**绝对画布坐标**——brush 的坐标系是画布，
            // 不随 drawRect 的 topLeft 平移（写相对坐标会整条退化成端色）。
            val bottomBrush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, if (pressed) pressBottom else bottomPool),
                startY = size.height - edgeHeight,
                endY = size.height,
            )
            val grainBrush = ShaderBrush(ImageShader(AppGrain.tile, TileMode.Repeated, TileMode.Repeated))
            val capsule = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius, radius)))
            }
            onDrawBehind {
                shadowPaints.forEach { (paint, dy) ->
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.translate(0f, dy)
                        native.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
                        native.restore()
                    }
                }
                drawRoundRect(brush = face, cornerRadius = CornerRadius(radius, radius))
                // 两道沿与颗粒都裁进胶囊内（两端弧上下缘自然跟随·同 porcelainTrack）。
                clipPath(capsule) {
                    drawRect(brush = topBrush, size = Size(size.width, topBandHeight))
                    drawRect(
                        brush = bottomBrush,
                        topLeft = Offset(0f, size.height - edgeHeight),
                        size = Size(size.width, edgeHeight),
                    )
                    drawRect(brush = grainBrush, alpha = AppPorcelain.GLAZE_GRAIN_ALPHA)
                }
            }
        }
        .clip(AppShapes.full)
}
