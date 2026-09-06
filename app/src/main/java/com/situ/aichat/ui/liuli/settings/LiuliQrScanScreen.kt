package com.situ.aichat.ui.liuli.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.util.ImageScaler
import com.situ.aichat.util.QrCodec
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSnackbarHost
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.BackdropHost
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.settings.QrFrameAnalyzer
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import com.situ.aichat.ui.designsystem.AppTheme

/** 相册解码前的最长边（逐字照暖陶 `QrScanScreen.kt:217` 的 1600）。 */
private const val GALLERY_MAX_EDGE = 1600
/** 底部玻璃条的内距与条内两件的缝。 */
private val BAR_PAD = 16.dp
private val BAR_GAP = 10.dp
/** 相册钮里的图标尺寸（逐字照暖陶的 18）。 */
private val GALLERY_ICON = 18.dp
/** 无相机权限时那句话的左右让位（逐字照暖陶的 32）。 */
private val NO_CAMERA_PAD = 32.dp

/**
 * 扫码页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 10·**T5 气氛壳**·A-10）。
 *
 * **相机预览与解码链零改**：`LifecycleCameraController` / `PreviewView` / [QrFrameAnalyzer]
 * （§2.2-2 已把它从 private class 提为 internal·实现零改）/ 相册解码 / 一次性 [AtomicBoolean] 守卫
 * 全部逐字搬。换的只有壳：返回**圆钮浮在预览上**（同 hero 页）+ 底部一条 Panel 档玻璃放提示与「从相册选」。
 *
 * 扫出的文本可含明文 API Key，故本屏一个字都不落日志（同暖陶）。
 */
@Composable
fun LiuliQrScanScreen(
    onResult: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = LocalIsDarkTheme.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val noQrMsg = stringResource(R.string.api_scan_no_qr)

    val currentOnResult by rememberUpdatedState(onResult)
    val delivered = remember { AtomicBoolean(false) }
    val deliver: (String) -> Unit = { text -> if (delivered.compareAndSet(false, true)) currentOnResult(text) }

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }
    LaunchedEffect(Unit) {
        if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                val bitmap = ImageScaler.decodeSampled(bytes, GALLERY_MAX_EDGE) ?: return@withContext null
                QrCodec.decode(bitmap)
            }
            if (text != null) deliver(text) else snackbarHostState.showSnackbar(noQrMsg)
        }
    }

    BackdropHost(
        modifier = modifier.fillMaxSize(),
        content = {
            if (hasCamera) {
                LiuliCameraPreview(onDecoded = deliver)
            } else {
                // 内容层自画纸面（BackdropHost 铁律·否则底下玻璃条切到透明底）。
                Box(Modifier.matchParentSize().background(AppTheme.colors.surface.base), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.api_scan_permission_needed),
                        style = AppTypography.listPreview,
                        color = LiuliTheme.onGlass.secondary,
                        modifier = Modifier.padding(horizontal = NO_CAMERA_PAD),
                    )
                }
            }
        },
        overlay = {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // 顶部圆钮让的是**状态栏**（施工版写成 navigationBarsPadding = 底栏内距·钮压进时钟·复核 R1 🔴 C2）。
                    .statusBarsPadding()
                    .padding(top = LiuliPageGeometry.titleTop, start = LiuliPageGeometry.gutter)
                    .size(LiuliPageGeometry.backButton),
                contentAlignment = Alignment.Center,
            ) {
                LiuliCircleButton(
                    onClick = onBack,
                    contentDescription = stringResource(R.string.action_back),
                    size = LiuliPageGeometry.backButton,
                ) {
                    Icon(
                        AppTopBarIcons.Back,
                        contentDescription = null,
                        modifier = Modifier.size(LiuliPageGeometry.chromeIcon),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .liuliGlass(LiuliShapes.sheet, dark = dark, style = LiuliGlassStyle.Panel)
                    .navigationBarsPadding()
                    .padding(BAR_PAD),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BAR_GAP),
            ) {
                if (hasCamera) {
                    Text(
                        stringResource(R.string.api_scan_hint),
                        style = AppTypography.secondary,
                        color = LiuliTheme.onGlass.secondary,
                    )
                }
                LiuliButton(
                    onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    style = LiuliButtonStyle.Glass,
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(GALLERY_ICON))
                    Text(stringResource(R.string.api_scan_from_gallery))
                }
            }
            LiuliSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        },
    )
}

/**
 * CameraX 实时取景 + 逐帧 ZXing 解码（扫到即回调一次）。**逐字复制**暖陶的同名 private composable
 * ——它是 private 且 §2.2 只许提纯函数 / 类的可见性，故这二十行在两张脸各存一份；改一侧必须同步另一侧。
 */
@Composable
private fun LiuliCameraPreview(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val onDecodedState by rememberUpdatedState(onDecoded)

    DisposableEffect(lifecycleOwner) {
        controller.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(context),
            QrFrameAnalyzer { text -> onDecodedState(text) },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    AndroidView(
        factory = { ctx -> PreviewView(ctx).apply { this.controller = controller } },
        modifier = Modifier.fillMaxSize(),
    )
}
