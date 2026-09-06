package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.prompt.diary.PreviewLine
import com.situ.aichat.prompt.diary.PreviewLineKind
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 日记提示词只读预览（2026-09-05·图纸 §4.3·P-5 骨架式）：走真装配函数出的骨架，当天素材是尖括号占位，
 * 用户改过的那几行高亮。**只读**——没有编辑框、没有保存钮；整块包 [SelectionContainer] 支持长按复制。
 */
@Composable
fun DiaryPromptPreviewScreen(
    onBack: () -> Unit,
    viewModel: DiaryPromptPreviewViewModel = hiltViewModel(),
) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(viewModel.titleRes),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(scrollState),
        ) {
            DiaryPromptPreviewBody(lines)
        }
    }
}

/**
 * 预览正文块（提示行 + 骨架块）。抽成 internal 可组合件供 T2 组件级断言直接渲染——整屏走 `hiltViewModel`
 * 在 Robolectric 下起不来（记忆 `reference-robolectric-hiltviewmodel-blocks-fullscreen`）。
 */
@Composable
internal fun DiaryPromptPreviewBody(lines: List<PreviewLine>) {
    Text(
        text = stringResource(R.string.diary_rules_preview_hint),
        style = AppTheme.typography.secondary,
        color = AppTheme.colors.text.tertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
    SelectionContainer {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(AppTheme.shapes.medium)
                .background(AppTheme.colors.surface.sunken)
                .padding(14.dp),
        ) {
            lines.forEach { PreviewLineText(it) }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PreviewLineText(line: PreviewLine) {
    if (line.text.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        return
    }
    when (line.kind) {
        PreviewLineKind.PLAIN -> Text(
            text = line.text,
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary,
        )
        PreviewLineKind.SLOT -> Text(
            text = line.text,
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.accent.text,
        )
        PreviewLineKind.CUSTOM -> Text(
            text = line.text,
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(AppTheme.colors.accent.primary.copy(alpha = 0.16f))
                .padding(horizontal = 2.dp),
        )
    }
}
