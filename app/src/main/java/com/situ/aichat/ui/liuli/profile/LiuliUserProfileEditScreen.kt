package com.situ.aichat.ui.liuli.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.character.AvatarCropScreen
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.profile.UserBirthdayDatePickerDialog
import com.situ.aichat.ui.profile.UserProfileEditViewModel
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.util.AvatarStore
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** 编辑个人资料（琉璃 2.0 脸）。 */
@Composable
fun LiuliUserProfileEditScreen(
    onClose: () -> Unit,
    viewModel: UserProfileEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingAvatarCropUri by remember { mutableStateOf<Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingAvatarCropUri = uri
    }
    pendingAvatarCropUri?.let { uri ->
        AvatarCropScreen(
            uri = uri,
            onCancel = { pendingAvatarCropUri = null },
            onConfirm = { cropped ->
                scope.launch {
                    AvatarStore.save(context, cropped)?.let { path -> viewModel.update { it.copy(avatarPath = path) } }
                }
                pendingAvatarCropUri = null
            },
        )
    }

    var showDatePicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val collapsed = rememberScrollCollapsed(scrollState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = stringResource(R.string.profile_edit_title)

    LiuliPage(
        title = title,
        onBack = onClose,
        collapsed = collapsed,
        actions = {
            LiuliButton(
                onClick = { viewModel.save(onClose) },
                style = LiuliButtonStyle.Prominent,
                enabled = !saving,
            ) { Text(stringResource(R.string.action_save)) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = LiuliPageGeometry.gutter)
                .padding(top = LiuliPageGeometry.navRow + 4.dp, bottom = navBottom + LiuliPageGeometry.pageBottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LiuliLargeTitle(title)

            // 头像卡
            Column(
                Modifier
                    .fillMaxWidth()
                    .liuliCardMaterial(LiuliShapes.group, dark)
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    CharacterAvatar(
                        name = state.nickname.ifEmpty { "我" },
                        avatarPath = state.avatarPath,
                        size = 92.dp,
                        modifier = Modifier.clickable {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(colors.accent.primary)
                            .clickable {
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            .padding(6.dp),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, stringResource(R.string.char_avatar_change), tint = colors.accent.onPrimary)
                    }
                }
                if (state.avatarPath != null) {
                    LiuliButton(
                        onClick = { viewModel.update { it.copy(avatarPath = null) } },
                        style = LiuliButtonStyle.Text,
                        danger = true,
                    ) { Text(stringResource(R.string.char_avatar_remove)) }
                }
            }

            // 表单组
            Column(
                Modifier
                    .fillMaxWidth()
                    .liuliCardMaterial(LiuliShapes.group, dark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LiuliField(
                    value = state.nickname,
                    onValueChange = { v -> viewModel.update { it.copy(nickname = v) } },
                    label = stringResource(R.string.profile_field_nickname),
                    placeholder = stringResource(R.string.profile_hint_nickname),
                    supportingText = stringResource(R.string.profile_footer_nickname),
                    modifier = Modifier.fillMaxWidth(),
                )
                LiuliField(
                    value = state.bio,
                    onValueChange = { v -> viewModel.update { it.copy(bio = v) } },
                    label = stringResource(R.string.profile_field_bio),
                    placeholder = stringResource(R.string.profile_hint_bio),
                    supportingText = stringResource(R.string.profile_footer_bio),
                    singleLine = false,
                    minHeight = 110.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiuliField(
                    value = state.companionPreference,
                    onValueChange = { v -> viewModel.update { it.copy(companionPreference = v) } },
                    label = stringResource(R.string.profile_field_companion_pref),
                    placeholder = stringResource(R.string.profile_hint_companion_pref),
                    supportingText = stringResource(R.string.profile_footer_companion_pref),
                    singleLine = false,
                    minHeight = 110.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 生日卡
            Column(
                Modifier
                    .fillMaxWidth()
                    .liuliCardMaterial(LiuliShapes.group, dark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.birthdayMillis == null) {
                    LiuliButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.char_field_birthday) + "：" + stringResource(R.string.char_birthday_unset))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f).clickable { showDatePicker = true }) {
                            Text(stringResource(R.string.char_field_birthday), style = AppTypography.caption, color = colors.text.secondary)
                            Text(DateFormat.getDateInstance(DateFormat.LONG).format(Date(state.birthdayMillis!!)), style = AppTypography.body, color = colors.text.primary)
                        }
                        AppIconButton(
                            icon = AppActionIcons.Close,
                            contentDescription = stringResource(R.string.char_birthday_clear),
                            onClick = { viewModel.update { it.copy(birthdayMillis = null) } },
                        )
                    }
                }
                Text(stringResource(R.string.profile_footer_birthday), style = AppTypography.caption, color = colors.text.secondary)
            }
        }
    }

    if (showDatePicker) {
        UserBirthdayDatePickerDialog(
            initialMillis = state.birthdayMillis,
            onConfirm = { millis -> viewModel.update { it.copy(birthdayMillis = millis) } },
            onDismiss = { showDatePicker = false },
        )
    }
}
