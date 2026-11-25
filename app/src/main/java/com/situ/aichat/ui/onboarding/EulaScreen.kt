package com.situ.aichat.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppDialog

// Display order mirrors iOS UserAgreementView (note: third-party shown as 6, then 6/7/governing-law).
private val agreementSections = listOf(
    Triple("1", R.string.agreement_section_1_title, R.string.agreement_section_1_body),
    Triple("2", R.string.agreement_section_2_title, R.string.agreement_section_2_body),
    Triple("3", R.string.agreement_section_3_title, R.string.agreement_section_3_body),
    Triple("4", R.string.agreement_section_4_title, R.string.agreement_section_4_body),
    Triple("5", R.string.agreement_section_5_title, R.string.agreement_section_5_body),
    Triple("6", R.string.agreement_section_third_party_title, R.string.agreement_section_third_party_body),
    Triple("7", R.string.agreement_section_6_title, R.string.agreement_section_6_body),
    Triple("8", R.string.agreement_section_7_title, R.string.agreement_section_7_body),
    Triple("9", R.string.agreement_section_governing_law_title, R.string.agreement_section_governing_law_body),
)

@Composable
fun EulaScreen(onAccept: () -> Unit) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount > 0 && last != null && last.index >= info.totalItemsCount - 1
        }
    }
    // onboarding-1：一旦滚到协议底部就【永久】启用「同意」——之后上滑不再复位禁用（1:1 iOS
    // UserAgreementView：reachedBottom 一次性锁存）；rememberSaveable 让转屏后保住已启用状态。
    var hasReachedBottom by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(atBottom) { if (atBottom) hasReachedBottom = true }
    var showDecline by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppButton(
                        onClick = { showDecline = true },
                        modifier = Modifier.weight(1f),
                        style = AppButtonStyle.Tonal,
                    ) { Text(stringResource(R.string.agreement_decline_button)) }

                    AppButton(
                        onClick = onAccept,
                        style = AppButtonStyle.Primary,
                        enabled = hasReachedBottom,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.agreement_accept_button)) }
                }
            }
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

    if (showDecline) {
        AppDialog(
            onDismissRequest = { showDecline = false },
            title = stringResource(R.string.agreement_decline_title),
            body = stringResource(R.string.agreement_decline_message),
            confirmText = stringResource(R.string.agreement_decline_ok),
            onConfirm = { showDecline = false },
        )
    }
}

/**
 * 协议正文（头部 + 编号分节 + 末尾间隔），供首启 [EulaScreen] 与「关于」页协议复看
 * [com.situ.aichat.ui.settings.AgreementViewScreen] 共用（DRY，CLAUDE.md §2）。
 */
internal fun LazyListScope.agreementContent() {
    item { AgreementHeader() }
    items(agreementSections) { (number, titleRes, bodyRes) ->
        AgreementSection(number, titleRes, bodyRes)
    }
    item { Spacer(Modifier.height(24.dp)) }
}

@Composable
private fun AgreementHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.agreement_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.agreement_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AgreementSection(number: String, titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text("$number. ", style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
