package com.situ.aichat.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.util.ImageScaler
import com.situ.aichat.util.QrCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码导入配置（13.10b · C7，安卓便利加分）：相机实时扫二维码 + 从相册选图解码（两条路都脱离 GMS）。
 *
 * 本屏只负责「扫出一段二维码文本」就回传（[onResult]）；这段文本是不是有效的 API 配置由
 * [ApiConfigScreen] 经 [com.situ.aichat.share.ApiConfigShareCodec] 判定并预填表单（相机/相册/导出三路统一）。
 * 相机权限被拒仍可用相册路径；结果只回传一次（[AtomicBoolean] 守卫，避免相机连续帧 + 相册重复触发）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onResult: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.api_scan_title),
                onBack = onBack,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (hasCamera) {
                CameraPreview(onDecoded = deliver)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.api_scan_permission_needed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasCamera) {
                    Text(
                        stringResource(R.string.api_scan_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, style = AppButtonStyle.Tonal) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.api_scan_from_gallery))
                }
            }
        }
    }
}

/** CameraX 实时取景 + 逐帧 ZXing 解码（扫到即回调一次）。 */
@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
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

/** 逐帧从 YUV 的 Y 平面解二维码；扫到一帧即回调并停止后续解码（[done] 守卫）。琉璃卷五复用（`ui/liuli` 树借同一份实现·改这里两张脸同时变）。 */
internal class QrFrameAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    @Volatile
    private var done = false

    override fun analyze(image: ImageProxy) {
        if (done) {
            image.close()
            return
        }
        try {
            val text = runCatching { decodeFrame(image) }.getOrNull()
            if (text != null) {
                done = true
                onDecoded(text)
            }
        } finally {
            image.close()
        }
    }

    private fun decodeFrame(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return QrCodec.decodeYuvLuminance(
            yPlane = data,
            dataWidth = plane.rowStride,
            dataHeight = image.height,
            cropWidth = image.width,
            cropHeight = image.height,
        )
    }
}

private const val GALLERY_MAX_EDGE = 1600
