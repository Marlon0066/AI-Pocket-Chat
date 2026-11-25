package com.situ.aichat.ui.liuli.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.contextlog.ContextLogEntryUiState
import com.situ.aichat.ui.contextlog.ContextLogEntryViewModel
import com.situ.aichat.ui.contextlog.model.LogFailureView
import com.situ.aichat.ui.contextlog.model.failureOffersApiSettings
import com.situ.aichat.ui.contextlog.shared.failureCharacterText
import com.situ.aichat.ui.contextlog.shared.failureCopy
import com.situ.aichat.ui.contextlog.shared.failureIcon
import com.situ.aichat.ui.contextlog.shared.failureTimeText
import com.situ.aichat.ui.contextlog.shared.sameKindText
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase

/** 失败详情（四期·图纸四 §4.6·琉璃）：内容同暖陶——头卡 → 可以这样做（+ 三类给「去 API 设置」）→ 详情 → 原始报错（默认收起）。 */
@Composable
fun LiuliContextLogFailureScreen(
    onBack: () -> Unit,
    onOpenApiSettings: () -> Unit,
    viewModel: ContextLogEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliContextLogFailureContent(state, onBack, onOpenApiSettings)
}

@Composable
internal fun LiuliContextLogFailureContent(state: ContextLogEntryUiState, onBack: () -> Unit, onOpenApiSettings: () -> Unit) {
    LiuliLogPage(stringResource(R.string.clog_failure_title), onBack) {
        if (!state.loaded) return@LiuliLogPage
        val e = state.entry
        val f = state.failure
        if (e == null || f == null) {
            item(key = "missing") { LiuliLogEmpty(stringResource(R.string.clog_entry_missing)) }
            return@LiuliLogPage
        }
        item(key = "head") { HeadGroup(f) }
        item(key = "steps") { StepsGroup(f, onOpenApiSettings) }
        item(key = "details") {
            LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                LiuliLogKvRow(stringResource(R.string.clog_kv_time), failureTimeText(e, f), divider = false)
                LiuliLogKvRow(stringResource(R.string.clog_kv_character), failureCharacterText(e, state.quote))
                LiuliLogKvRow(stringResource(R.string.clog_kv_model), e.modelName)
                LiuliLogKvRow(stringResource(R.string.clog_kv_same_kind), sameKindText(e, f))
            }
        }
        item(key = "raw") { RawErrorGroup(f) }
    }
}

@Composable
private fun HeadGroup(f: LogFailureView) {
    val copy = failureCopy(f.kind)
    LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
        Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.l, horizontal = LiuliPageGeometry.groupPadH), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(AppTheme.colors.status.errorContainer), contentAlignment = Alignment.Center) {
                Icon(failureIcon(f.kind), contentDescription = null, tint = AppTheme.colors.status.onError, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(AppSpacing.s))
            Text(stringResource(copy.name), style = AppTypography.titleSmall, color = AppTheme.colors.text.primary)
            Spacer(Modifier.height(AppSpacing.xs))
            Text(stringResource(copy.reason), style = AppTypography.secondary, color = AppTheme.colors.text.secondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StepsGroup(f: LogFailureView, onOpenApiSettings: () -> Unit) {
    val copy = failureCopy(f.kind)
    LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter), header = stringResource(R.string.clog_failure_steps_title)) {
        LiuliLogFreeRow(first = true) {
            Column {
                listOf(copy.step1, copy.step2).forEachIndexed { i, res ->
                    Row(Modifier.padding(vertical = AppSpacing.xs)) {
                        Text("${i + 1}.", style = AppTypography.secondary, color = AppTheme.colors.text.primary)
                        Spacer(Modifier.width(AppSpacing.s))
                        Text(stringResource(res), style = AppTypography.secondary, color = AppTheme.colors.text.primary)
                    }
                }
            }
        }
    }
    if (failureOffersApiSettings(f.kind)) {
        LiuliButton(
            onClick = onOpenApiSettings, style = LiuliButtonStyle.Prominent,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter).padding(bottom = LiuliPageGeometry.groupGap),
        ) { Text(stringResource(R.string.clog_go_api_settings)) }
    }
}

@Composable
private fun RawErrorGroup(f: LogFailureView) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.clog_raw_error)
    LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
        LiuliRowBase(onClick = { open = !open }, onClickLabel = label, divider = false) {
            Text(label, style = AppTypography.label, color = AppTheme.colors.text.secondary, modifier = Modifier.weight(1f))
            Icon(if (open) AppActionIcons.ChevronUp else AppActionIcons.ChevronDown, contentDescription = null, tint = AppTheme.colors.text.secondary, modifier = Modifier.size(18.dp))
        }
        if (open) {
            LiuliLogFreeRow(first = false) {
                SelectionContainer {
                    Text(
                        f.raw,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                        color = AppTheme.colors.text.secondary,
                        modifier = Modifier.fillMaxWidth().clip(LiuliShapes.small).background(AppTheme.colors.surface.sunken).padding(AppSpacing.s),
                    )
                }
            }
        }
    }
}
