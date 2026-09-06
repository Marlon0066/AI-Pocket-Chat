package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiFunctionCategory
import com.situ.aichat.ui.settings.FunctionVisionHint
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliMenuRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ApiFunctionAssignmentViewModel
import com.situ.aichat.ui.settings.categoryLabel
import com.situ.aichat.ui.settings.chatVisionFootnote
import com.situ.aichat.ui.settings.imageVisionFootnote

/**
 * API 功能分配页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 9「每行下拉」）。与暖陶
 * `ApiFunctionAssignmentScreen` 共用 [ApiFunctionAssignmentViewModel]。
 *
 * 每个 [ApiFunctionCategory] 一组，组内每个功能一行 [LiuliMenuRow]（A-4 ②）：标题 = 功能名 ·
 * 副标 = 功能说明 · 右值 = 当前选中的配置（未指派时是「跟随当前启用」那句）· 菜单里选中项打勾。
 * 两行的联动脚注（聊天对话 / 图片理解）借暖陶 [chatVisionFootnote] / [imageVisionFootnote]
 * （§2.2-2 已提 internal·实现零改），落在**组脚注**上——一组一行时它就贴在那一行下方。
 */
@Composable
fun LiuliApiFunctionAssignmentScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApiFunctionAssignmentViewModel = hiltViewModel(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val active by viewModel.activeConfig.collectAsStateWithLifecycle()
    val assignments by viewModel.assignments.collectAsStateWithLifecycle()
    val chatVisionHint by viewModel.chatVisionHint.collectAsStateWithLifecycle()
    val imageVisionHint by viewModel.imageUnderstandingVisionHint.collectAsStateWithLifecycle()

    LiuliApiFunctionAssignmentContent(
        configs = configs,
        activeName = active?.let { "${it.providerName} ${it.modelName}" },
        assignments = assignments,
        chatVisionHint = chatVisionHint,
        imageVisionHint = imageVisionHint,
        onSelect = viewModel::setAssignment,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 功能分配页内容层（纯参数·可测）。类别顺序 / 行内文案 / 脚注条件逐字继承暖陶。 */
@Composable
internal fun LiuliApiFunctionAssignmentContent(
    configs: List<ApiConfigEntity>,
    activeName: String?,
    assignments: Map<ApiFunction, String?>,
    chatVisionHint: FunctionVisionHint,
    imageVisionHint: FunctionVisionHint,
    onSelect: (ApiFunction, String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.api_fn_assign_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 同屏十几行下拉必须互斥：展开态提到屏这一层，一次只许一个开着。
    var expandedFn by remember { mutableStateOf<ApiFunction?>(null) }

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
                    ApiFunctionCategory.entries.forEach { category ->
                        // 类内两行有联动提示时把它作组脚注（暖陶是行下小字·琉璃的组脚注就在组体正下方）。
                        val footnote = category.functions.firstNotNullOfOrNull { fn ->
                            when (fn) {
                                ApiFunction.CHAT -> chatVisionFootnote(chatVisionHint)
                                ApiFunction.IMAGE_UNDERSTANDING -> imageVisionFootnote(imageVisionHint)
                                else -> null
                            }
                        }
                        LiuliGroup(header = categoryLabel(category), footer = footnote) {
                            category.functions.forEachIndexed { index, fn ->
                                val defaultLabel = activeName?.let { stringResource(R.string.api_fn_default_named, it) }
                                    ?: stringResource(R.string.api_fn_default)
                                val assignedUuid = assignments[fn]
                                val assigned = assignedUuid?.let { id -> configs.firstOrNull { it.uuid == id } }
                                LiuliMenuRow(
                                    title = fn.displayName,
                                    subtitle = fn.subtitle,
                                    value = assigned?.let { "${it.providerName} ${it.modelName}" } ?: defaultLabel,
                                    options = buildList {
                                        add(
                                            LiuliMenuEntry(
                                                text = defaultLabel,
                                                selected = assignedUuid == null,
                                                onClick = { onSelect(fn, null) },
                                            ),
                                        )
                                        configs.forEach { cfg ->
                                            add(
                                                LiuliMenuEntry(
                                                    text = "${cfg.providerName} ${cfg.modelName}",
                                                    selected = assignedUuid == cfg.uuid,
                                                    onClick = { onSelect(fn, cfg.uuid) },
                                                ),
                                            )
                                        }
                                    },
                                    expanded = expandedFn == fn,
                                    onExpandedChange = { expandedFn = if (it) fn else null },
                                    divider = index > 0,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
