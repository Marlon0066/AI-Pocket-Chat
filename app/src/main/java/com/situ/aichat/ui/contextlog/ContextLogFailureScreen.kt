package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.contextlog.model.LogFailureView
import com.situ.aichat.ui.contextlog.model.failureOffersApiSettings
import com.situ.aichat.ui.contextlog.shared.failureCharacterText
import com.situ.aichat.ui.contextlog.shared.failureCopy
import com.situ.aichat.ui.contextlog.shared.failureIcon
import com.situ.aichat.ui.contextlog.shared.failureTimeText
import com.situ.aichat.ui.contextlog.shared.sameKindText
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar

/** 失败详情（四期·图纸四 §4.6·暖陶）：头卡 → 可以这样做（+ 去 API 设置）→ 详情键值 → 原始报错（默认收起）。 */
@Composable
fun ContextLogFailureScreen(
    onBack: () -> Unit,
    onOpenApiSettings: () -> Unit,
    viewModel: ContextLogEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ContextLogFailureContent(state, onBack, onOpenApiSettings)
}

@Composable
internal fun ContextLogFailureContent(state: ContextLogEntryUiState, onBack: () -> Unit, onOpenApiSettings: () -> Unit) {
    val listState = rememberLazyListState()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = { AppTopBar(title = stringResource(R.string.clog_failure_title), onBack = onBack, lifted = listState.canScrollBackward) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, top = AppSpacing.s, bottom = AppSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.cardGapInGroup),
        ) {
            if (!state.loaded) return@LazyColumn
            val e = state.entry
            val f = state.failure
            if (e == null || f == null) {
                item(key = "missing") { MissingText() }
                return@LazyColumn
            }
            item(key = "head") { HeadCard(f) }
            item(key = "steps") { StepsCard(f, onOpenApiSettings) }
            item(key = "details") {
                LogCard {
                    LogKvRow(stringResource(R.string.clog_kv_time), failureTimeText(e, f))
                    LogKvRow(stringResource(R.string.clog_kv_character), failureCharacterText(e, state.quote))
                    LogKvRow(stringResource(R.string.clog_kv_model), e.modelName)
                    LogKvRow(stringResource(R.string.clog_kv_same_kind), sameKindText(e, f))
                }
            }
            item(key = "raw") { RawErrorCard(f) }
        }
    }
}

@Composable
private fun HeadCard(f: LogFailureView) {
    val copy = failureCopy(f.kind)
    LogCard {
        Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(AppTheme.colors.status.errorContainer), contentAlignment = Alignment.Center) {
                Icon(failureIcon(f.kind), contentDescription = null, tint = AppTheme.colors.status.onError, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(AppSpacing.s))
            Text(stringResource(copy.name), style = AppTheme.typography.titleSmall, color = AppTheme.colors.text.primary)
            Spacer(Modifier.height(AppSpacing.xs))
            Text(stringResource(copy.reason), style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StepsCard(f: LogFailureView, onOpenApiSettings: () -> Unit) {
    val copy = failureCopy(f.kind)
    LogCard(title = stringResource(R.string.clog_failure_steps_title)) {
        listOf(copy.step1, copy.step2).forEachIndexed { i, res ->
            Row(Modifier.padding(vertical = AppSpacing.xs)) {
                Text("${i + 1}.", style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary)
                Spacer(Modifier.width(AppSpacing.s))
                Text(stringResource(res), style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary)
            }
        }
        if (failureOffersApiSettings(f.kind)) {
            AppButton(onClick = onOpenApiSettings, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.m)) {
                Text(stringResource(R.string.clog_go_api_settings))
            }
        }
    }
}

@Composable
private fun RawErrorCard(f: LogFailureView) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.clog_raw_error)
    LogCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = label) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = AppTheme.typography.label, color = AppTheme.colors.text.secondary, modifier = Modifier.weight(1f))
            Icon(
                if (open) AppActionIcons.ChevronUp else AppActionIcons.ChevronDown,
                contentDescription = null, tint = AppTheme.colors.text.secondary, modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            SelectionContainer {
                Text(
                    f.raw,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                    color = AppTheme.colors.text.secondary,
                    modifier = Modifier.fillMaxWidth().clip(AppTheme.shapes.small).background(AppTheme.colors.surface.sunken).padding(AppSpacing.s),
                )
            }
        }
    }
}
