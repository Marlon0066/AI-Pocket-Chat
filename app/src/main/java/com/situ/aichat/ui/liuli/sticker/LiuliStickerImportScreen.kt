package com.situ.aichat.ui.liuli.sticker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialogShell
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.sticker.StickerImportViewModel
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 自定义表情包导入（琉璃 2.0 脸）。 */
@Composable
fun LiuliStickerImportScreen(
    onBack: () -> Unit,
    viewModel: StickerImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    var showSource by remember { mutableStateOf(false) }

    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.pickImage(uri)
    }
    val pickFromFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.pickImage(uri)
    }

    val scrollState = rememberScrollState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = "添加表情包"

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberScrollCollapsed(scrollState),
        actions = {
            LiuliButton(
                onClick = { viewModel.save(onBack) },
                style = LiuliButtonStyle.Prominent,
                enabled = state.canSave,
            ) { Text("保存") }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = LiuliPageGeometry.gutter)
                .padding(top = LiuliPageGeometry.navRow + 4.dp, bottom = navBottom + LiuliPageGeometry.pageBottom),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LiuliLargeTitle(title)

            Column(
                modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.preview?.let { bmp ->
                    Box(modifier = Modifier.size(112.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(100.dp),
                        )
                    }
                }
                LiuliButton(onClick = { showSource = true }, modifier = Modifier.fillMaxWidth(), style = LiuliButtonStyle.Glass) {
                    Text(if (state.bytes == null) "选择图片" else "重新选择")
                }
                if (state.isAnimated) {
                    Text("✓ 检测到 GIF 动图", style = AppTypography.secondary, color = colors.accent.text)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LiuliField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = "表情包名称（必填）",
                    modifier = Modifier.fillMaxWidth(),
                )
                LiuliField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    label = "语义描述（选填，帮助 AI 理解含义）",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "例如：名称「狗头保命」，描述「开玩笑、别当真、保护性自嘲」",
                style = AppTypography.caption,
                color = colors.text.secondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showSource) {
        LiuliDialogShell(onDismissRequest = { showSource = false }) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择图片来源", style = AppTypography.titleMedium, color = colors.text.primary)
                LiuliButton(
                    style = LiuliButtonStyle.Glass,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showSource = false
                        pickFromGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                ) { Text("从相册选择") }
                LiuliButton(
                    style = LiuliButtonStyle.Glass,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showSource = false
                        pickFromFile.launch(arrayOf("image/*"))
                    },
                ) { Text("从文件选择") }
                LiuliButton(
                    style = LiuliButtonStyle.Text,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showSource = false },
                ) { Text("取消") }
            }
        }
    }
}
