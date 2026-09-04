package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 记忆 hub（SETTINGS_REORG D3·2026-07-02 过审）：原「记忆设置」「记忆提示词」两个入口二合一，
 * 单页两段上下堆叠 = 参数段 [MemorySettingsSections] + 提示词段 [MemoryPromptsSections]，
 * 挂原路由 memorySettings（世界书设置页的交叉链接随之直达本页）。壳只管 Scaffold / 标题 / 滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryHubScreen(onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.mem_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
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
            MemoryPromptsSections()
        }
    }
}
