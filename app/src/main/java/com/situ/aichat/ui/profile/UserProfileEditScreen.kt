package com.situ.aichat.ui.profile

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
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
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppFormBar
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.util.AvatarStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

/**
 * 编辑个人资料（暖陶外衣）：头像 + 昵称 + 简介 + 相处偏好 + 生日。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileEditScreen(
    onClose: () -> Unit,
    viewModel: UserProfileEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val colors = AppTheme.colors

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

    Scaffold(
        containerColor = colors.surface.base,
        topBar = {
            AppFormBar(
                title = stringResource(R.string.profile_edit_title),
                lifted = scrollState.value > 0,
                onCancel = onClose,
                trailing = {
                    AppButton(onClick = { viewModel.save(onClose) }, enabled = !saving) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(colors.surface.base)
                .grainSurface()
                .imePadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = AppSpacing.screenGutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        CharacterAvatar(
                            name = state.nickname.ifEmpty { "我" },
                            avatarPath = state.avatarPath,
                            size = 96.dp,
                            modifier = Modifier.clickable {
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.accent.primary)
                                .clickable {
                                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                .padding(6.dp),
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.char_avatar_change),
                                tint = colors.accent.onPrimary,
                            )
                        }
                    }
                    if (state.avatarPath != null) {
                        AppButton(onClick = { viewModel.update { it.copy(avatarPath = null) } }, style = AppButtonStyle.Text, danger = true) {
                            Text(stringResource(R.string.char_avatar_remove))
                        }
                    }
                }
            }

            AppTextField(
                value = state.nickname,
                onValueChange = { v -> viewModel.update { it.copy(nickname = v) } },
                label = stringResource(R.string.profile_field_nickname),
                placeholder = stringResource(R.string.profile_hint_nickname),
                supportingText = stringResource(R.string.profile_footer_nickname),
                modifier = Modifier.fillMaxWidth(),
            )

            AppTextArea(
                value = state.bio,
                onValueChange = { v -> viewModel.update { it.copy(bio = v) } },
                label = stringResource(R.string.profile_field_bio),
                placeholder = stringResource(R.string.profile_hint_bio),
                supportingText = stringResource(R.string.profile_footer_bio),
                modifier = Modifier.fillMaxWidth(),
            )

            AppTextArea(
                value = state.companionPreference,
                onValueChange = { v -> viewModel.update { it.copy(companionPreference = v) } },
                label = stringResource(R.string.profile_field_companion_pref),
                placeholder = stringResource(R.string.profile_hint_companion_pref),
                supportingText = stringResource(R.string.profile_footer_companion_pref),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.birthdayMillis == null) {
                AppButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal) {
                    Text(stringResource(R.string.char_field_birthday) + "：" + stringResource(R.string.char_birthday_unset))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).clickable { showDatePicker = true }) {
                        Text(
                            stringResource(R.string.char_field_birthday),
                            style = AppTypography.caption,
                            color = colors.text.secondary,
                        )
                        Text(
                            DateFormat.getDateInstance(DateFormat.LONG).format(Date(state.birthdayMillis!!)),
                            style = AppTypography.body,
                            color = colors.text.primary,
                        )
                    }
                    AppIconButton(
                        icon = AppActionIcons.Close,
                        contentDescription = stringResource(R.string.char_birthday_clear),
                        onClick = { viewModel.update { it.copy(birthdayMillis = null) } },
                    )
                }
            }
            Text(
                stringResource(R.string.profile_footer_birthday),
                style = AppTypography.caption,
                color = colors.text.secondary,
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserBirthdayDatePickerDialog(
    initialMillis: Long?,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val dpState = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis ?: System.currentTimeMillis(),
        yearRange = 1900..Calendar.getInstance().get(Calendar.YEAR),
        selectableDates = PastOrPresentDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = AppTheme.colors.surface.raised),
        confirmButton = {
            AppButton(style = AppButtonStyle.Text, onClick = {
                onConfirm(dpState.selectedDateMillis)
                onDismiss()
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            AppButton(style = AppButtonStyle.Text, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = dpState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal object PastOrPresentDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
    override fun isSelectableYear(year: Int): Boolean = year <= Calendar.getInstance().get(Calendar.YEAR)
}
