package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.prompt.diary.PreviewLine
import com.situ.aichat.prompt.diary.PreviewLineKind
import com.situ.aichat.ui.diary.DiaryPromptPreviewViewModel
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed

/** 骨架块内距 / 空行高 / CUSTOM 底色的圆角与内距（逐字照暖陶 14 / 6 / 3 / 2）。 */
private val BLOCK_PAD = 14.dp
private val EMPTY_LINE = 6.dp
private val CUSTOM_SHAPE = RoundedCornerShape(3.dp)
private val CUSTOM_PAD_H = 2.dp
/** CUSTOM 行的底色透明度（逐字照暖陶的 0.16）。 */
private const val CUSTOM_BG_ALPHA = 0.16f

/**
 * 日记提示词预览页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 16·A-11「只读三屏最薄」）。与暖陶
 * `DiaryPromptPreviewScreen` 共用 [DiaryPromptPreviewViewModel]（**标题也来自 VM**：它按
 * `savedStateHandle[section]` 分「我的 / 交换」两份）。
 *
 * **prompt 耦合最深的一屏**：显示的是真装配骨架、占位符尖括号、以及三态染色（哪几行被改过）——
 * 文字裁剪 / 换行 / 三态判定**零改**，换脸只换外面那层壳与块底。
 */
@Composable
fun LiuliDiaryPromptPreviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiaryPromptPreviewViewModel = hiltViewModel(),
) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    LiuliDiaryPromptPreviewContent(
        title = stringResource(viewModel.titleRes),
        lines = lines,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 预览页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliDiaryPromptPreviewContent(
    title: String,
    lines: List<PreviewLine>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(footer = stringResource(R.string.diary_rules_preview_hint)) {
                        LiuliRowBase(
                            divider = false,
                            verticalPadding = LiuliPageGeometry.groupPadH,
                            verticalAlignment = Alignment.Top,
                        ) {
                            SelectionContainer {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(LiuliShapes.small)
                                        .background(colors.surface.sunken)
                                        .padding(BLOCK_PAD),
                                ) {
                                    lines.forEach { LiuliPreviewLineText(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一行预览：三态染色逐字照暖陶 `PreviewLineText`（空行 = 6dp 空当·不是空 Text）。 */
@Composable
private fun LiuliPreviewLineText(line: PreviewLine) {
    val colors = AppTheme.colors
    if (line.text.isEmpty()) {
        Spacer(Modifier.height(EMPTY_LINE))
        return
    }
    when (line.kind) {
        PreviewLineKind.PLAIN -> Text(line.text, style = AppTypography.secondary, color = colors.text.secondary)
        PreviewLineKind.SLOT -> Text(line.text, style = AppTypography.secondary, color = colors.accent.text)
        PreviewLineKind.CUSTOM -> Text(
            line.text,
            style = AppTypography.secondary,
            color = colors.text.primary,
            modifier = Modifier
                .clip(CUSTOM_SHAPE)
                .background(colors.accent.primary.copy(alpha = CUSTOM_BG_ALPHA))
                .padding(horizontal = CUSTOM_PAD_H),
        )
    }
}
