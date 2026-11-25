package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 记忆设置页（设置全面重构卷二）：
 * 聚焦日常记忆参数（短期窗口 / 长期摘要 / 结构化记忆 / 向量检索 / 见面记忆）；
 * 记忆提取与注入提示词模板已迁至「高级 → 记忆提示词」独立页（[MemoryPromptsSettingsScreen]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryHubScreen(onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.mem_settings_title),
                onBack = onBack,
                lifted = lifted,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth(),
        ) {
            MemorySettingsSections()
        }
    }
}
