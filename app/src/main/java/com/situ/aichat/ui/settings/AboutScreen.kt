package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.onboarding.agreementContent

/**
 * 关于页（P12.1c）。对齐 iOS SettingsView 的「关于」段（版本 + 协议 + 免责声明），并按本项目「GitHub/sideload
 * 分发、纯本地无后端」的实际情况适配：版本（BuildConfig）+ 用户协议复看（复用首启协议正文，只读）+ 免责声明
 * + 致谢。**有意偏离 iOS**：iOS 关于段的隐私政策/技术支持/反馈/服务条款均指向原 iOS 应用的个人
 * GitHub Pages 与邮箱，非本移植范畴；本应用以应用内协议为权威文本，故不移植这些外链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenAgreement: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.about_title),
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
            SettingsSection(
                title = stringResource(R.string.about_section_info),
                footer = stringResource(R.string.about_disclaimer),
            ) {
                AppSettingsRow(
                    title = stringResource(R.string.about_version),
                    value = BuildConfig.VERSION_NAME,
                )
                AppSettingsRow(
                    title = stringResource(R.string.about_agreement),
                    showChevron = true,
                    onClick = onOpenAgreement,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSection(title = stringResource(R.string.about_section_ack)) {
                Text(
                    stringResource(R.string.about_ack_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 用户协议复看（只读）：复用首启协议正文 [agreementContent]，无同意/拒绝按钮，仅返回。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgreementViewScreen(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.about_agreement),
                onBack = onBack,
                lifted = listState.canScrollBackward,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .contentMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            agreementContent()
        }
    }
}
