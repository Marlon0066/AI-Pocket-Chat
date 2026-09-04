package com.situ.aichat.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.character.AvatarCropScreen
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppFormBar
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.util.AvatarStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Edit the (singleton) user profile: avatar + nickname + bio + birthday (iOS `UserProfileEditView`).
 * City (P5, 高德) is deferred. nickname/bio feed the userPersona prompt module, so edits reflect in
 * chat. Visuals are native Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileEditScreen(
    onClose: () -> Unit,
    viewModel: UserProfileEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingAvatarCropUri by remember { mutableStateOf<Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // 选完图先进圆形取景裁剪屏（甲 3）；「就这样」才存裁好的成品图，「取消」不改原头像。
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
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Avatar
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
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
                        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.char_avatar_change),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(6.dp).clickable {
                                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
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

            // 相处偏好（四小件·2026-07-16）：逐参照抄 bio 纹路（默认 minHeight 120dp·不设字数闸）。
            AppTextArea(
                value = state.companionPreference,
                onValueChange = { v -> viewModel.update { it.copy(companionPreference = v) } },
                label = stringResource(R.string.profile_field_companion_pref),
                placeholder = stringResource(R.string.profile_hint_companion_pref),
                supportingText = stringResource(R.string.profile_footer_companion_pref),
                modifier = Modifier.fillMaxWidth(),
            )

            // Birthday
            if (state.birthdayMillis == null) {
                AppButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal) {
                    Text(stringResource(R.string.char_field_birthday) + "：" + stringResource(R.string.char_birthday_unset))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).clickable { showDatePicker = true }) {
                        Text(
                            stringResource(R.string.char_field_birthday),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(DateFormat.getDateInstance(DateFormat.LONG).format(Date(state.birthdayMillis!!)))
                    }
                    IconButton(onClick = { viewModel.update { it.copy(birthdayMillis = null) } }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.char_birthday_clear))
                    }
                }
            }
            Text(
                stringResource(R.string.profile_footer_birthday),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = state.birthdayMillis ?: System.currentTimeMillis(),
            yearRange = 1900..Calendar.getInstance().get(Calendar.YEAR),
            selectableDates = PastOrPresentDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            colors = DatePickerDefaults.colors(containerColor = AppTheme.colors.surface.raised),
            confirmButton = {
                AppButton(style = AppButtonStyle.Text, onClick = {
                    viewModel.update { it.copy(birthdayMillis = dpState.selectedDateMillis) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                AppButton(style = AppButtonStyle.Text, onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = dpState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private object PastOrPresentDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
    override fun isSelectableYear(year: Int): Boolean = year <= Calendar.getInstance().get(Calendar.YEAR)
}
