package com.situ.aichat.ui.moments

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import androidx.compose.foundation.layout.PaddingValues
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionChip
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppFormBar
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface

/**
 * 朋友圈发布页（M06 7.2.7，对齐 iOS `ComposeMomentView`）：用户头像 + 正文输入（500 字，超限计数变红）+ 配图
 * （≤9，系统 PickMultipleVisualMedia 即选即落盘）。「发布」落 user 帖并排 AI 延迟互动（VM）；放弃确认清孤儿图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMomentScreen(
    onClose: () -> Unit,
    viewModel: ComposeMomentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    var showDiscard by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ComposeMomentViewModel.MAX_IMAGES),
    ) { uris -> viewModel.addImages(uris) }

    val charCount = state.content.length
    val overLimit = charCount > ComposeMomentViewModel.MAX_CHARS
    val canPublish = state.content.isNotBlank() && !overLimit && !state.publishing
    val handleClose = { if (viewModel.hasUnsavedChanges) showDiscard = true else onClose() }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppFormBar(
                title = stringResource(R.string.moment_compose_title),
                lifted = scrollState.value > 0,
                // ✕ → 文字「取消」（拍板④）；handleClose 的放弃确认逻辑一字不动。
                onCancel = handleClose,
                trailing = {
                    // 发布 = 釉烧 Primary 小胶囊（契约 §2.5·D5 拍板）：行动召唤明确；禁用规格由 AppButton 既有口径管。
                    AppButton(
                        onClick = { viewModel.publish(onClose) },
                        style = AppButtonStyle.Primary,
                        enabled = canPublish,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.moment_compose_publish))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            // 页底 = surface.base + 纸感 grain；gutter 20（契约 §2.5）。
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surface.base)
                .grainSurface()
                .verticalScroll(scrollState)
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 「稿纸卡」（契约 §2.5）：头像 + 输入区 + 字数计数收进一张卡——写字的地方有纸的承托（与日记编写页呼应）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .appCardSurface()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CharacterAvatar(
                        name = userProfile?.nickname?.ifBlank { null } ?: stringResource(R.string.moment_author_me),
                        avatarPath = userProfile?.avatarPath,
                        size = 34.dp, // moments-ui-8 同源：1:1 iOS ComposeMomentView AvatarSize.mini(34)
                    )
                    // moments-ui-5：进入即自动聚焦弹键盘（1:1 iOS ComposeMomentView .onAppear{ isFocused = true }）。
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    AppTextArea(
                        value = state.content,
                        onValueChange = viewModel::setContent,
                        placeholder = stringResource(R.string.moment_compose_hint),
                        minHeight = 150.dp,
                        focusRequester = focusRequester,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "$charCount/${ComposeMomentViewModel.MAX_CHARS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overLimit) AppTheme.colors.status.onError else AppTheme.colors.text.secondary,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            ImageSection(
                images = state.images,
                onAdd = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = viewModel::removeImage,
            )
        }
    }

    if (showDiscard) {
        AppDialog(
            onDismissRequest = { showDiscard = false },
            title = stringResource(R.string.moment_compose_discard_title),
            confirmText = stringResource(R.string.moment_compose_discard_confirm),
            onConfirm = { showDiscard = false; viewModel.discard(); onClose() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.moment_compose_discard_keep),
            onDismiss = { showDiscard = false },
        )
    }
}

@Composable
private fun ImageSection(images: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (images.size < ComposeMomentViewModel.MAX_IMAGES) {
            AppActionChip(
                onClick = onAdd,
                label = stringResource(R.string.moment_compose_add_image),
                leading = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (images.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                images.forEach { path ->
                    Box {
                        // 圆角 10 孤值并轨形状阶 small=8（契约 §2.5）。
                        MomentImage(path = path, contentDescription = "", modifier = Modifier.size(80.dp), corner = 8.dp)
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                                .clickable { onRemove(path) }
                                .align(Alignment.TopEnd),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.moment_compose_remove_image),
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
