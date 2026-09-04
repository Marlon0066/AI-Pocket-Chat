package com.situ.aichat.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val editFullDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)

/**
 * 手动编辑见面记录表单（1:1 iOS `OfflineMeetingEditView`，medium → ModalBottomSheet）：改地点/活动/摘要，
 * 可调 LLM 重新生成摘要（填入编辑框供审阅）；只读信息（日期/时长/情绪）帮确认是哪次见面。地点+活动非空才可保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMeetingEditSheet(
    session: OfflineMeetingSession,
    onSave: (location: String, activity: String, summary: String) -> Unit,
    onRegenerate: suspend (location: String, activity: String) -> String?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var editedLocation by remember { mutableStateOf(session.location) }
    var editedActivity by remember { mutableStateOf(session.activity) }
    var editedSummary by remember { mutableStateOf(session.summaryText.orEmpty()) }
    var isRegenerating by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }

    val canSave = editedLocation.trim().isNotEmpty() && editedActivity.trim().isNotEmpty()
    val mood = OfflineMoodTheme.forMood(session.finalMood)
    val dateText = remember(session.startMillis) {
        Instant.ofEpochMilli(session.startMillis).atZone(ZoneId.systemDefault()).format(editFullDateFormatter)
    }

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("编辑见面记录", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))

            Text("见面信息", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextField(
                value = editedLocation,
                onValueChange = { editedLocation = it },
                modifier = Modifier.fillMaxWidth(),
                label = "地点",
            )
            AppTextField(
                value = editedActivity,
                onValueChange = { editedActivity = it },
                modifier = Modifier.fillMaxWidth(),
                label = "活动",
            )

            Text("见面摘要", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextArea(
                value = editedSummary,
                onValueChange = { editedSummary = it },
                modifier = Modifier.fillMaxWidth(),
                label = "摘要",
            )
            AppButton(
                style = AppButtonStyle.Tonal,
                onClick = {
                    if (isRegenerating) return@AppButton
                    isRegenerating = true
                    scope.launch {
                        val result = onRegenerate(editedLocation.trim(), editedActivity.trim())
                        if (result != null) editedSummary = result else showError = true
                        isRegenerating = false
                    }
                },
                enabled = !isRegenerating,
            ) {
                if (isRegenerating) {
                    AppLoadingRing(Modifier.padding(end = 6.dp), size = AppLoadingRingSize.Large)
                    Text("正在生成…")
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("重新生成摘要")
                }
            }
            Text(
                "由 AI 自动生成的见面记忆，你可以修正不准确的内容。留空表示无摘要。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("记录信息", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReadOnlyRow("日期", dateText)
            if (session.durationText.isNotEmpty()) ReadOnlyRow("时长", session.durationText)
            if (session.finalMood != null) ReadOnlyRow("情绪", "${mood.emoji} ${mood.label}")

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(onClick = onDismiss, style = AppButtonStyle.Text, modifier = Modifier.weight(1f)) { Text("取消") }
                AppButton(
                    onClick = { onSave(editedLocation.trim(), editedActivity.trim(), editedSummary.trim()) },
                    style = AppButtonStyle.Primary,
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }

    if (showError) {
        AppDialog(
            onDismissRequest = { showError = false },
            title = "生成失败",
            body = "请检查记忆提取的 API 配置后重试。",
            confirmText = "确定",
            onConfirm = { showError = false },
        )
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
