package com.situ.aichat.ui.character

import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import com.situ.aichat.ui.designsystem.OnGlass
import com.situ.aichat.util.ImageOrientation
import com.situ.aichat.util.WallpaperCropMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 解码工作图长边 cap（兼顾显示清晰 + 缩放余量 + 内存；成品最终再缩到 WallpaperStore.MAX_EDGE）。 */
private const val WORKING_EDGE = 2560
/** 最大放大倍数（相对铺满缩放 coverScale）。 */
private const val MAX_ZOOM = 4f

/**
 * 壁纸裁剪取景全屏编辑器（契约 FABLE5_CHAT_WALLPAPER_PROPOSAL.md §10·C1/C2/C3·mockup 已过审）。
 *
 * - **C1**：选完图直接进此编辑器，默认 `coverScale` 居中铺满（=自适应）；双指缩放 + 单/双指拖动平移即裁剪取景。
 * - **C2**：「完成」把当前取景按 [WallpaperCropMath.sourceRect] 换算成源像素矩形 → `Bitmap.createBitmap` 裁出成品图
 *   回调 [onConfirm]（调用方存盘）。所见即所得。
 * - **C3**：半透明虚叠**顶栏玻璃丸 + 底部三件控件**参考线（[CropGuides]）+ 真实系统状态栏（edge-to-edge 透出），
 *   让用户避开人脸被栏/气泡挡。
 *
 * 全屏 Dialog（自有窗口·edge-to-edge），裁剪数学全走纯函数 [WallpaperCropMath]（已单测·稳定性关键）。
 */
@Composable
fun WallpaperCropScreen(
    imageUri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val source by produceState<Bitmap?>(initialValue = null, imageUri) {
        value = withContext(Dispatchers.IO) {
            // 走 ImageOrientation 而非裸 decodeSampled：竖拍照片带 EXIF 方向标签、像素本身没转，
            // 不扶正就会横躺着进取景框，裁出来的壁纸同错（甲 0·与头像裁剪同一口）。
            ImageOrientation.decodeOriented(context, imageUri, WORKING_EDGE)
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 全屏 + edge-to-edge：让壁纸取景框 = 真实全屏（与最终铺到系统栏后的显示一致），参考线按真 inset 摆位。
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(w, false)
                // 顶栏永远是深色半透明条 → 强制白状态栏图标，任意亮度照片下时间/电量都清楚。
                WindowCompat.getInsetsController(w, dialogView).isAppearanceLightStatusBars = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val src = source
            if (src == null) {
                // TODO(图纸未覆盖): 同 AvatarCropScreen —— 画在用户壁纸照片上的白色转圈，换 accent 会糊掉（D-13）。
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                CropEditor(src, onCancel, onConfirm)
            }
        }
    }
}

@Composable
private fun CropEditor(
    source: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val srcW = source.width
    val srcH = source.height
    val image = remember(source) { source.asImageBitmap() }
    val scope = rememberCoroutineScope()

    var frame by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(0f) }          // 0 = 取景框未测量、未初始化
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showHint by remember { mutableStateOf(true) }

    // 取景框测出后初始化为「居中铺满」（自适应初值·契约 C1）。
    LaunchedEffect(frame, srcW, srcH) {
        if (frame.width > 0 && frame.height > 0 && scale == 0f) {
            val cover = WallpaperCropMath.coverScale(srcW, srcH, frame.width, frame.height)
            val c = WallpaperCropMath.centerOffset(srcW, srcH, frame.width, frame.height, cover)
            scale = cover
            offset = Offset(c.x, c.y)
        }
    }
    LaunchedEffect(Unit) { delay(2800); showHint = false }

    fun reset() {
        if (frame.width == 0 || frame.height == 0) return
        val cover = WallpaperCropMath.coverScale(srcW, srcH, frame.width, frame.height)
        val c = WallpaperCropMath.centerOffset(srcW, srcH, frame.width, frame.height, cover)
        scale = cover
        offset = Offset(c.x, c.y)
    }

    fun confirm() {
        if (scale <= 0f || frame.width == 0) return
        val rect = WallpaperCropMath.sourceRect(offset.x, offset.y, scale, srcW, srcH, frame.width, frame.height)
        scope.launch {
            val cropped = withContext(Dispatchers.Default) {
                runCatching { Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height) }.getOrNull()
            }
            if (cropped != null) onConfirm(cropped)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { frame = it }
            .pointerInput(srcW, srcH) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (scale <= 0f || frame.width == 0 || frame.height == 0) return@detectTransformGestures
                    val cover = WallpaperCropMath.coverScale(srcW, srcH, frame.width, frame.height)
                    val newScale = (scale * zoom).coerceIn(cover, cover * MAX_ZOOM)
                    val k = newScale / scale // 真实缩放比（已含钳位）——保证缩放锚定 centroid，即便触顶/底
                    val rawX = centroid.x + (offset.x - centroid.x) * k + pan.x
                    val rawY = centroid.y + (offset.y - centroid.y) * k + pan.y
                    val clamped = WallpaperCropMath.clampOffset(rawX, rawY, newScale, srcW, srcH, frame.width, frame.height)
                    scale = newScale
                    offset = Offset(clamped.x, clamped.y)
                }
            },
    ) {
        if (scale > 0f && frame.width > 0) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                    dstSize = IntSize((srcW * scale).roundToInt(), (srcH * scale).roundToInt()),
                )
            }
        }

        CropGuides()

        // 顶部 chrome：取消 / 标题 / 完成（半透明深条压在任意照片上都清楚）。
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x73101418))
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.action_cancel),
                color = OnGlass.PrimaryOnDark,
                fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(stringResource(R.string.char_wallpaper_crop_title), color = Color(0xCCFFFFFF), fontSize = 13.sp)
            Text(
                stringResource(R.string.char_wallpaper_crop_done),
                color = OnGlass.PrimaryOnLight,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFC99A86))
                    .clickable(onClick = ::confirm)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // 中部首入提示（短暂淡出）。
        AnimatedVisibility(showHint, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Text(
                stringResource(R.string.char_wallpaper_crop_hint),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x80000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // 底部「重置自适应」。
        Text(
            stringResource(R.string.char_wallpaper_crop_reset),
            color = OnGlass.PrimaryOnDark,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp) // 让开底部「输入丸」参考线行（避免与三件控件参考重叠）
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x73000000))
                .clickable(onClick = ::reset)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * C3 参考线：半透明虚线叠出「将来聊天 UI 的位置」——顶部玻璃信息丸 + 底部三件玻璃控件（➕/输入丸/语音），
 * 按真实系统栏 inset 摆位，让用户裁剪时避开人脸被栏/气泡挡。纯绘制、不拦手势。
 */
@Composable
private fun CropGuides() {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    Canvas(Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.85f)
        val stroke = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        val m = 12.dp.toPx()
        // 顶栏玻璃丸参考（状态栏下方一条）。
        val pillTop = statusTop + 44.dp.toPx()
        val pillH = 52.dp.toPx()
        drawRoundRect(
            color = line,
            topLeft = Offset(m, pillTop),
            size = Size(size.width - 2 * m, pillH),
            cornerRadius = CornerRadius(pillH / 2),
            style = stroke,
        )
        // 底部三件控件参考（导航栏上方一行：➕圆 · 输入丸 · 语音圆）。
        val ctrl = 46.dp.toPx()
        val gap = 10.dp.toPx()
        val rowBottom = size.height - navBottom - 14.dp.toPx()
        val rowTop = rowBottom - ctrl
        drawRoundRect(line, Offset(m, rowTop), Size(ctrl, ctrl), CornerRadius(ctrl / 2), style = stroke)
        drawRoundRect(line, Offset(size.width - m - ctrl, rowTop), Size(ctrl, ctrl), CornerRadius(ctrl / 2), style = stroke)
        drawRoundRect(
            color = line,
            topLeft = Offset(m + ctrl + gap, rowTop),
            size = Size(size.width - 2 * m - 2 * ctrl - 2 * gap, ctrl),
            cornerRadius = CornerRadius(ctrl / 2),
            style = stroke,
        )
    }
}
