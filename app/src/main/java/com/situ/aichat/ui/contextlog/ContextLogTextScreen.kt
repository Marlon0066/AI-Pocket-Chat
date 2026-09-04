package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.LogShareFormat
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 全文查看器（批 D·D-3；打磨 ②）：完整上下文 / 回复全文（[isContext] 二选一）。
 * - **分块惰性渲染**（[splitLogTextBlocks]）：全量记录后单条可达 20 万字，单 Text 是秒级卡顿源；
 * - 顶栏复制：只复制**当前页正文**（整条打包在详情页「复制全文」），超限自动转导出（[LogShareActions]）；
 * - 底部字数脚注：全量与否一眼可验；含旧版截断标记的历史条目如实标注。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextLogTextScreen(
    isContext: Boolean,
    onBack: () -> Unit,
    viewModel: ContextLogDetailViewModel = hiltViewModel(),
) {
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val text = entry?.let { if (isContext) it.fullContext else it.responseContent.orEmpty() }.orEmpty()
    val blocks = remember(text) { splitLogTextBlocks(text) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            AppTopBar(
                title = if (isContext) "完整上下文" else "回复全文",
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    if (text.isNotEmpty()) {
                        val copyLabel = stringResource(R.string.contextlog_viewer_copy)
                        val e = entry
                        IconButton(onClick = {
                            val current = e ?: return@IconButton
                            scope.launch {
                                LogShareActions.copyOrExport(
                                    context, text,
                                    LogShareFormat.exportFileName(current.source, current.timestampMillis),
                                )
                            }
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = copyLabel)
                        }
                    }
                },
            )
        },
    ) { padding ->
        SelectionContainer(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                if (text.isEmpty()) {
                    item {
                        Text("（无内容）", style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary)
                    }
                } else {
                    itemsIndexed(blocks) { _, block ->
                        Text(block, style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary)
                    }
                    item {
                        val chars = remember(text) { String.format(Locale.ROOT, "%,d", text.length) }
                        val legacyClipped = remember(text) { text.contains(LEGACY_CLIP_MARKER) }
                        Text(
                            stringResource(
                                if (legacyClipped) R.string.contextlog_viewer_chars_truncated else R.string.contextlog_viewer_chars,
                                chars,
                            ),
                            style = AppTheme.typography.caption,
                            color = AppTheme.colors.text.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 落库截断标记前缀（[com.situ.aichat.diagnostics.LogContextFormat.clip] 的提示行）：命中来源 =
 * 旧版软上限的历史条目，或现行 20 万字安全帽的极端条目——两者脚注一律如实标「已截断」。
 */
private const val LEGACY_CLIP_MARKER = "[日志内容已截断"
