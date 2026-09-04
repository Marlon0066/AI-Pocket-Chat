package com.situ.aichat.ui.character

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.ImageOrientation
import com.situ.aichat.util.WallpaperCropMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 裁剪屏显示用解码清晰度（成品另经 AvatarStore 缩到 512·§9 锁定）。 */
private const val WORKING_EDGE = 2048

/** 最大放大倍数：相对「盖满取景框」的 cover 尺度（同壁纸裁剪·§9 锁定 [cover, cover×4]）。 */
private const val MAX_ZOOM = 4f

/** 圆形取景框直径 = min(屏宽, 屏高) − 48dp（两侧各 24dp·§4.A）。 */
private val FRAME_INSET = 48.dp

/** 圆心垂直位置 = 屏高的 44%（§4.A·略高于几何中心，给底部按钮让出呼吸位）。 */
private const val FRAME_CENTER_Y_FRACTION = 0.44f

/**
 * 头像圆形取景裁剪屏（甲 2·mockup = `fable5_artifacts/mockups/avatar_crop_mockup.html`）。
 *
 * **fork 自 [WallpaperCropScreen] 而非参数化改造它**（§0.② 2）：两屏的差异面（取景框形状 / 遮罩 /
 * 输出仓 / 文案）大于共享面，硬揉一个组件会造出「既管全屏又管圆形」的双职责件。数学与手势天然复用——
 * [WallpaperCropMath] 四个函数全部参数化于 frame，**喂方形 frame 即得 1:1 输出，一字不改**。
 *
 * 恒暗场不随主题（同壁纸裁剪先例）：底是纯黑图层上绘位图，遮罩压暗圆外。
 * 交出的是**方形位图**，不烘焙圆形 alpha——显示层 [com.situ.aichat.ui.components.CharacterAvatar]
 * 已经 `clip(CircleShape)`，成品只需正方形（§0.③）。
 *
 * @param onConfirm 用户点「就这样」→ 交出裁好的方形位图（调用方负责落盘与回收）。
 * @param onCancel 取消 / 返回键 / 图片解不出来（E1）→ 静默回原页，原头像不动。
 */
@Composable
fun AvatarCropScreen(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    // 三态而非 Bitmap?：null 分不清「还在解」和「解砸了」，而这两者的正确行为完全不同（转圈 vs 退出）。
    val state by produceState<CropSource>(initialValue = CropSource.Loading, uri) {
        // decodeOriented：竖拍自拍带 EXIF 方向标签，不扶正就横躺着进圆框（甲 0）。
        value = withContext(Dispatchers.IO) {
            ImageOrientation.decodeOriented(context, uri, WORKING_EDGE)?.let(CropSource::Loaded) ?: CropSource.Failed
        }
    }
    // 解不出来（坏图 / 权限 / 超大）→ 静默回编辑页 + Log.w，原头像不动，绝不弹错误吓人（E1）。
    LaunchedEffect(state) {
        if (state is CropSource.Failed) {
            Log.w("AvatarCropScreen", "图片解码失败，按取消处理 uri=$uri")
            onCancel()
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(w, false)
                // 恒暗场 → 强制白状态栏图标，任意亮度照片下时间/电量都清楚。
                WindowCompat.getInsetsController(w, dialogView).isAppearanceLightStatusBars = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                // Failed 也走转圈：上面的 LaunchedEffect 正在退场，这一帧不值得闪个错误态给用户看。
                is CropSource.Loaded -> AvatarCropEditor(s.bitmap, onCancel, onConfirm)
                // TODO(图纸未覆盖): 这枚转圈画在**用户照片上**，靠 color = Color.White 才看得见；AppLoadingRing
                //  恒走 accent 陶土色、无 color 槽（§3 签名锁定）。换过去在浅色照片上会糊掉 → 停手登记（D-13）。
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
        }
    }
}

/** 裁剪源图的三态：还在解 / 解好了 / 解砸了（[AvatarCropScreen] 私有）。 */
private sealed interface CropSource {
    data object Loading : CropSource
    data object Failed : CropSource
    data class Loaded(val bitmap: Bitmap) : CropSource
}

@Composable
private fun AvatarCropEditor(
    source: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val srcW = source.width
    val srcH = source.height
    val image = remember(source) { source.asImageBitmap() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 取景框 = 圆的外接正方形；喂给 WallpaperCropMath 的就是它（方形 → 1:1 成品）。
        val diameterPx = with(density) { (minOf(maxWidth, maxHeight) - FRAME_INSET).toPx() }
        val frame = IntSize(diameterPx.roundToInt(), diameterPx.roundToInt())
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val frameCenter = Offset(screenW / 2f, screenH * FRAME_CENTER_Y_FRACTION)
        // 框左上角在屏幕坐标里的位置：手势与绘制都要在「屏幕坐标 ↔ 框坐标」之间换算。
        val frameOrigin = Offset(frameCenter.x - diameterPx / 2f, frameCenter.y - diameterPx / 2f)

        var scale by remember { mutableStateOf(0f) } // 0 = 还没初始化
        var offset by remember { mutableStateOf(Offset.Zero) } // 框坐标系内的图片左上角

        // 初始化为「居中铺满取景框」（同壁纸屏的自适应初值）。
        LaunchedEffect(frame, srcW, srcH) {
            if (frame.width > 0 && scale == 0f) {
                val cover = WallpaperCropMath.coverScale(srcW, srcH, frame.width, frame.height)
                val c = WallpaperCropMath.centerOffset(srcW, srcH, frame.width, frame.height, cover)
                scale = cover
                offset = Offset(c.x, c.y)
            }
        }

        fun confirm() {
            if (scale <= 0f || frame.width == 0) return
            val rect = WallpaperCropMath.sourceRect(offset.x, offset.y, scale, srcW, srcH, frame.width, frame.height)
            scope.launch {
                val cropped = withContext(Dispatchers.Default) {
                    runCatching { Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height) }.getOrNull()
                }
                if (cropped != null) {
                    onConfirm(cropped)
                } else {
                    Log.w("AvatarCropScreen", "裁剪失败，按取消处理 rect=$rect src=${srcW}x$srcH")
                    onCancel()
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(srcW, srcH, frame) {
                    // 手势块整段照搬壁纸屏（§9 锁定 [cover, cover×4] + centroid 锚定 + clampOffset 不露边）；
                    // 唯一差别：centroid 要从屏幕坐标平移到框坐标，数学才对得上。
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (scale <= 0f || frame.width == 0) return@detectTransformGestures
                        val local = centroid - frameOrigin
                        val cover = WallpaperCropMath.coverScale(srcW, srcH, frame.width, frame.height)
                        val newScale = (scale * zoom).coerceIn(cover, cover * MAX_ZOOM)
                        val k = newScale / scale // 真实缩放比（已含钳位）→ 触顶/底时仍锚定 centroid
                        val rawX = local.x + (offset.x - local.x) * k + pan.x
                        val rawY = local.y + (offset.y - local.y) * k + pan.y
                        val clamped =
                            WallpaperCropMath.clampOffset(rawX, rawY, newScale, srcW, srcH, frame.width, frame.height)
                        scale = newScale
                        offset = Offset(clamped.x, clamped.y)
                    }
                },
        ) {
            if (scale > 0f && frame.width > 0) {
                Canvas(Modifier.fillMaxSize()) {
                    // 图片铺满全屏（圆外的部分由遮罩压暗），但位置/尺度都按**框坐标**算 → 加上框原点即可。
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(srcW, srcH),
                        dstOffset = IntOffset(
                            (frameOrigin.x + offset.x).roundToInt(),
                            (frameOrigin.y + offset.y).roundToInt(),
                        ),
                        dstSize = IntSize((srcW * scale).roundToInt(), (srcH * scale).roundToInt()),
                    )
                }
            }

            CircleMask(frameCenter, diameterPx / 2f)

            Text(
                stringResource(R.string.avatar_crop_hint),
                style = AppTypography.secondary,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(density) { (screenH * 0.085f).toDp() }),
            )

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CropButton(
                    text = stringResource(R.string.avatar_crop_cancel),
                    background = SolidColor(Color.White.copy(alpha = 0.12f)),
                    textColor = Color.White.copy(alpha = 0.85f),
                    border = Color.White.copy(alpha = 0.25f),
                    onClick = onCancel,
                )
                CropButton(
                    text = stringResource(R.string.avatar_crop_confirm),
                    // 主钮浅档双 stop 渐变 135°（设计语言 §1.3）。
                    // 浅档双 stop 135°（默认 linearGradient = 左上→右下，同 AppButton Primary 写法）。
                    background = Brush.linearGradient(listOf(Color(0xFFC99A86), Color(0xFFBE8A76))),
                    textColor = Color(0xFF2E2925),
                    border = null,
                    onClick = ::confirm,
                )
            }
        }
    }
}

/**
 * 圆形取景遮罩：全屏黑 55% + 中央圆洞全透 + 白 90% 描边 2dp（§4.A 锁定）。
 *
 * 挖洞必须走 [CompositingStrategy.Offscreen]：[BlendMode.Clear] 要在**本图层**内擦，
 * 否则会连同底下的照片一起擦成黑洞（离屏合成 = 先把这层单独画好再贴回去）。
 */
@Composable
private fun CircleMask(center: Offset, radius: Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        drawRect(color = Color.Black.copy(alpha = 0.55f), size = Size(size.width, size.height))
        drawCircle(color = Color.Black, radius = radius, center = center, blendMode = BlendMode.Clear)
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** 底部两钮共用壳：r-full 胶囊、≥48dp 触达、15sp/600（§4.A 锁定）。 */
@Composable
private fun CropButton(
    text: String,
    background: Brush,
    textColor: Color,
    border: Color?,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
