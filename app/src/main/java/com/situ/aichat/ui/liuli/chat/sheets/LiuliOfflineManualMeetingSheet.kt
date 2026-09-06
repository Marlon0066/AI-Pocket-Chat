package com.situ.aichat.ui.liuli.chat.sheets

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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell

/**
 * 琉璃版「发起线下见面」表单（图纸 2026-09-05 卷二C C6b · A-16 · 照抄源 F26 前半
 * `ui/offline/OfflineManualMeetingSheet.kt:37-97`）。
 *
 * **只换渲染皮**：`rememberSaveable` 字段、`canStart` 门、[committed] 旗标、文案与两处占位、
 * 「取消 = onCancel + onDismiss」的语义逐字照抄——未提交就下滑关闭才回调 [onCancel]（1:1 iOS
 * `.sheet onCancel`），提交过就不回调。暖陶那份零改，两张脸共用同一个 VM 入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliOfflineManualMeetingSheet(
    onStart: (location: String, activity: String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var location by rememberSaveable { mutableStateOf("") }
    var activity by rememberSaveable { mutableStateOf("") }
    val canStart = location.trim().isNotEmpty() && activity.trim().isNotEmpty()
    var committed by remember { mutableStateOf(false) }

    LiuliSheetShell(
        onDismissRequest = { if (!committed) onCancel(); onDismiss() },
        sheetState = sheetState,
        title = "发起线下见面",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LiuliField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                label = "地点",
                placeholder = "星巴克、公园、家里…",
            )
            LiuliField(
                value = activity,
                onValueChange = { activity = it },
                modifier = Modifier.fillMaxWidth(),
                label = "活动",
                placeholder = "喝咖啡、散步、看电影…",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiuliButton(
                    onClick = { onCancel(); onDismiss() },
                    style = LiuliButtonStyle.Text,
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
                LiuliButton(
                    onClick = {
                        committed = true
                        onStart(location.trim(), activity.trim())
                        onDismiss()
                    },
                    style = LiuliButtonStyle.Prominent,
                    enabled = canStart,
                    modifier = Modifier.weight(1f),
                ) { Text("见面！") }
            }
        }
    }
}
