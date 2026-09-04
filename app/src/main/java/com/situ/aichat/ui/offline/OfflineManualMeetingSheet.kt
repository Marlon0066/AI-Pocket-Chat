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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextField

/**
 * 手动发起线下见面表单（「+」入口·medium detent → ModalBottomSheet）：地点 + 活动，两者均非空才可提交。
 * （原「改成邀约」路径的原文预览三参数随该功能于 2026-09-04 一并去掉——用户拍板：与本表单完全重复。）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineManualMeetingSheet(
    onStart: (location: String, activity: String) -> Unit,
    onDismiss: () -> Unit,
    /** 未提交就取消/下滑关闭 → 通知 AI（1:1 iOS .sheet onCancel）·无默认值=调用方必须显式表态。 */
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // 审计 B2（拍板 2026-07-02）：表单字段跨重建存活——填一半转屏/切深色不丢（弹窗开合旗标在 ChatSheetsState 同升）。
    var location by rememberSaveable { mutableStateOf("") }
    var activity by rememberSaveable { mutableStateOf("") }
    val canStart = location.trim().isNotEmpty() && activity.trim().isNotEmpty()
    // offline-2：区分「提交」与「取消/下滑关闭未提交」——只有后者才回调 onCancel（1:1 iOS .sheet onCancel）。
    var committed by remember { mutableStateOf(false) }

    AppSheet(
        onDismissRequest = { if (!committed) onCancel(); onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("发起线下见面", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))

            AppTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                label = "地点",
                placeholder = "星巴克、公园、家里…",
            )
            AppTextField(
                value = activity,
                onValueChange = { activity = it },
                modifier = Modifier.fillMaxWidth(),
                label = "活动",
                placeholder = "喝咖啡、散步、看电影…",
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(onClick = { onCancel(); onDismiss() }, style = AppButtonStyle.Text, modifier = Modifier.weight(1f)) { Text("取消") }
                AppButton(
                    onClick = {
                        committed = true
                        onStart(location.trim(), activity.trim())
                        onDismiss()
                    },
                    style = AppButtonStyle.Primary,
                    enabled = canStart,
                    modifier = Modifier.weight(1f),
                ) { Text("见面！") }
            }
        }
    }
}
