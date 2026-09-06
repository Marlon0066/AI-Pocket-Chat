package com.situ.aichat.ui.liuli.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.content.ContentFilterRule
import com.situ.aichat.content.FilterMode
import com.situ.aichat.content.ContentFilterService
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliInputRow
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSaveBar
import com.situ.aichat.ui.liuli.page.LiuliSegmentRow
import com.situ.aichat.ui.liuli.page.liuliSaveBarInset
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import java.util.UUID

/** 测试输入框最小高（逐字照暖陶 `:401` 的 96dp）与等宽结果块的内距 / 圆角（A-4 ⑨）。 */
private val TEST_INPUT_MIN_HEIGHT = 96.dp
private val MONO_BLOCK_PAD = 12.dp
/** 组内块与块的缝。 */
private val BLOCK_GAP = 12.dp

/**
 * 编辑态草稿（琉璃侧自持·**结构与暖陶 `EditingRule` 逐字同形**，只是那边是 private）。
 * 预设规则不走编辑屏（仅开关），故这里恒为自定义（`isPreset = false`）。
 */
@Immutable
internal data class LiuliEditingRule(
    val id: String,
    val isNew: Boolean,
    val isEnabled: Boolean,
    val name: String,
    val pattern: String,
    val mode: FilterMode,
    val replacement: String,
) {
    fun toRule() = ContentFilterRule(
        id = id,
        name = name,
        pattern = pattern,
        isEnabled = isEnabled,
        isPreset = false,
        mode = mode,
        replacement = replacement,
    )

    companion object {
        fun new() = LiuliEditingRule(
            id = UUID.randomUUID().toString(),
            isNew = true,
            isEnabled = true, // 新规则默认启用（对齐 iOS isAddingRule sheet 初值·同暖陶）
            name = "",
            pattern = "",
            mode = FilterMode.REMOVE,
            replacement = "",
        )

        fun from(rule: ContentFilterRule) = LiuliEditingRule(
            id = rule.id,
            isNew = false,
            isEnabled = rule.isEnabled,
            name = rule.name,
            pattern = rule.pattern,
            mode = rule.mode,
            replacement = rule.replacement,
        )
    }
}

/**
 * 过滤规则编辑屏（琉璃·图纸 2026-09-06 卷五 §4.1 屏 5·编辑态 = A-9 长表单）。
 *
 * 暖陶那边是 `AppFormBar`（取消 / 保存都在顶栏）；琉璃按 A-9 拆成**返回圆钮 = 取消**（`LiuliPage.onBack`
 * 与 `BackHandler` 同一条路）+ **底部玻璃保存栏 = 保存**。守卫逐字照暖陶：`pattern` 空视为有效（不显红），
 * 否则编译校验；`canSave = pattern.isNotEmpty() && patternValid`。
 */
@Composable
internal fun LiuliContentFilterEditScreen(
    editing: LiuliEditingRule,
    onCancel: () -> Unit,
    onSave: (ContentFilterRule) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var name by rememberSaveable(editing.id) { mutableStateOf(editing.name) }
    var pattern by rememberSaveable(editing.id) { mutableStateOf(editing.pattern) }
    var mode by rememberSaveable(editing.id) { mutableStateOf(editing.mode) }
    var replacement by rememberSaveable(editing.id) { mutableStateOf(editing.replacement) }
    var testInput by rememberSaveable(editing.id) { mutableStateOf("") }
    // 测试结果：未跑 = null；跑过则 result = testFilter 输出（null = 正则非法 / "" = 完全过滤 / 文字 = 结果）。
    var testRan by rememberSaveable(editing.id) { mutableStateOf(false) }
    var testResult by rememberSaveable(editing.id) { mutableStateOf<String?>(null) }

    // pattern 空 → 视为有效（不显红字）；否则编译校验（1:1 iOS validatePattern·同暖陶 :302）。
    val patternValid = pattern.isEmpty() || ContentFilterService.isValidRegex(pattern)
    val canSave = pattern.isNotEmpty() && patternValid

    BackHandler(onBack = onCancel)

    val colors = AppTheme.colors
    val title = stringResource(
        if (editing.isNew) R.string.content_filter_add_title else R.string.content_filter_edit_title,
    )
    val bottomInset = LiuliPageGeometry.pageBottom +
        liuliSaveBarInset +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onCancel,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
        bottomBar = {
            LiuliSaveBar(
                text = stringResource(R.string.content_filter_action_save),
                onClick = {
                    onSave(
                        editing.copy(
                            name = name,
                            pattern = pattern,
                            mode = mode,
                            replacement = replacement,
                        ).toRule(),
                    )
                },
                enabled = canSave,
            )
        },
    ) {
        LazyColumn(
            // C4：规则编辑屏（名 / 正则 / 替换多输入）键盘弹起可滚到键盘上方（逐字照暖陶）。
            modifier = Modifier.fillMaxSize().imePadding().contentMaxWidth(),
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
                    LiuliGroup(header = stringResource(R.string.content_filter_section_basic)) {
                        LiuliInputRow(
                            label = stringResource(R.string.content_filter_field_name),
                            value = name,
                            onValueChange = { name = it },
                            divider = false,
                        )
                        LiuliInputRow(
                            label = stringResource(R.string.content_filter_field_pattern),
                            value = pattern,
                            onValueChange = { pattern = it },
                            supportingText = if (!patternValid) {
                                stringResource(R.string.content_filter_invalid_pattern)
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                            ),
                        )
                    }

                    LiuliGroup(
                        header = stringResource(R.string.content_filter_section_mode),
                        footer = stringResource(
                            if (mode == FilterMode.REMOVE) {
                                R.string.content_filter_mode_remove_footer
                            } else {
                                R.string.content_filter_mode_replace_footer
                            },
                        ),
                    ) {
                        LiuliSegmentRow(
                            title = null, // 组标题已经点了名（卷四 R1 🟡-2）
                            options = listOf(FilterMode.REMOVE, FilterMode.REPLACE),
                            selected = mode,
                            label = {
                                stringResource(
                                    if (it == FilterMode.REMOVE) {
                                        R.string.content_filter_mode_remove
                                    } else {
                                        R.string.content_filter_mode_replace
                                    },
                                )
                            },
                            onSelect = { mode = it },
                            divider = false,
                        )
                        if (mode == FilterMode.REPLACE) {
                            LiuliInputRow(
                                label = stringResource(R.string.content_filter_field_replacement),
                                value = replacement,
                                onValueChange = { replacement = it },
                            )
                        }
                    }

                    LiuliGroup(header = stringResource(R.string.content_filter_section_test)) {
                        LiuliRowBase(
                            divider = false,
                            verticalPadding = LiuliPageGeometry.groupPadH,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(BLOCK_GAP),
                            ) {
                                Text(
                                    stringResource(R.string.content_filter_test_input_label),
                                    style = AppTypography.secondary,
                                    color = colors.text.secondary,
                                )
                                LiuliField(
                                    value = testInput,
                                    onValueChange = { testInput = it },
                                    singleLine = false,
                                    minHeight = TEST_INPUT_MIN_HEIGHT,
                                )
                                LiuliButton(
                                    onClick = {
                                        testResult = ContentFilterService.testFilter(testInput, pattern, mode, replacement)
                                        testRan = true
                                    },
                                    style = LiuliButtonStyle.Prominent,
                                    enabled = pattern.isNotEmpty() && patternValid && testInput.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.content_filter_test_run))
                                }
                                if (testRan) {
                                    Text(
                                        stringResource(R.string.content_filter_test_result_label),
                                        style = AppTypography.secondary,
                                        color = colors.text.secondary,
                                    )
                                    val result = testResult
                                    val shown = when {
                                        result == null -> stringResource(R.string.content_filter_test_invalid)
                                        result.isEmpty() -> stringResource(R.string.content_filter_test_fully_filtered)
                                        else -> result
                                    }
                                    // 等宽正文块（A-4 ⑨）：压 surface.sunken 圆角 10 内距 12。
                                    Text(
                                        shown,
                                        style = AppTypography.body.copy(fontFamily = FontFamily.Monospace),
                                        color = colors.accent.text,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(LiuliShapes.small)
                                            .background(colors.surface.sunken)
                                            .padding(MONO_BLOCK_PAD),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
