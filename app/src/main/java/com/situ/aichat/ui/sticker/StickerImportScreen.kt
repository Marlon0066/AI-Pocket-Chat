package com.situ.aichat.ui.sticker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 自定义表情包导入（1:1 iOS `StickerImportView`）：选图（相册 / 文件）→ 严格判定 GIF → 名称（必填）+
 * 语义描述（选填）→ 保存。无 GMS：相册走系统 Photo Picker（[ActivityResultContracts.PickVisualMedia]，
 * HyperOS 原生支持），文件走 SAF（[ActivityResultContracts.OpenDocument]，image 通配类型，含 GIF）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerImportScreen(
    onBack: () -> Unit,
    viewModel: StickerImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSource by remember { mutableStateOf(false) }

    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.pickImage(uri)
    }
    val pickFromFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.pickImage(uri)
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "添加表情包",
                onBack = onBack,
                lifted = scrollState.value > 0,
                actions = {
                    AppButton(onClick = { viewModel.save(onBack) }, style = AppButtonStyle.Text, enabled = state.canSave) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState).contentMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                state.preview?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(100.dp),
                    )
                }
            }
            AppButton(onClick = { showSource = true }, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal) {
                Text(if (state.bytes == null) "选择图片" else "重新选择")
            }
            if (state.isAnimated) {
                Text("✓ 检测到 GIF 动图", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            AppTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = "表情包名称（必填）",
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                label = "语义描述（选填，帮助 AI 理解含义）",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "例如：名称「狗头保命」，描述「开玩笑、别当真、保护性自嘲」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showSource) {
        AppDialog(
            onDismissRequest = { showSource = false },
            title = "选择图片来源",
            dismissText = "取消",
            onDismiss = { showSource = false },
            content = {
                Column {
                    AppButton(style = AppButtonStyle.Text, onClick = {
                        showSource = false
                        pickFromGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("从相册选择") }
                    AppButton(style = AppButtonStyle.Text, onClick = {
                        showSource = false
                        pickFromFile.launch(arrayOf("image/*"))
                    }) { Text("从文件选择") }
                }
            },
        )
    }
}
