package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiFunctionCategory
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppDropdownField
import com.situ.aichat.ui.designsystem.AppDropdownMenuItem

/**
 * Per-function API assignment (P3.5) — faithful port of iOS APIFunctionAssignmentView.
 * Lists every [ApiFunction] grouped by category; each can be pointed at a specific config or left
 * on "default" (the active config). Only chat / memory-summary are wired today; the rest are
 * placeholders for P4–P11 features.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiFunctionAssignmentScreen(
    onBack: () -> Unit,
    viewModel: ApiFunctionAssignmentViewModel = hiltViewModel(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val active by viewModel.activeConfig.collectAsStateWithLifecycle()
    val assignments by viewModel.assignments.collectAsStateWithLifecycle()
    val chatVisionHint by viewModel.chatVisionHint.collectAsStateWithLifecycle()
    val imageVisionHint by viewModel.imageUnderstandingVisionHint.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.api_fn_assign_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .contentMaxWidth(),
        ) {
            ApiFunctionCategory.entries.forEach { category ->
                // 每类别一个 item：SettingsSection 包该类全部功能行（功能数固定少量·不再逐行 items·§4.A6）。
                item(key = "cat_${category.name}") {
                    SettingsSection(title = categoryLabel(category)) {
                        category.functions.forEach { fn ->
                            FunctionAssignmentRow(
                                function = fn,
                                configs = configs,
                                activeName = active?.let { "${it.providerName} ${it.modelName}" },
                                assignedUuid = assignments[fn],
                                onSelect = { uuid -> viewModel.setAssignment(fn, uuid) },
                                // 两行有联动提示：「聊天对话」决定聊天「+」里出不出「照片」；
                                // 「图片理解」决定照片有没有文字描述（没有 = 长期记忆里搜不到这张照片）。
                                footnote = when (fn) {
                                    ApiFunction.CHAT -> chatVisionFootnote(chatVisionHint)
                                    ApiFunction.IMAGE_UNDERSTANDING -> imageVisionFootnote(imageVisionHint)
                                    else -> null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunctionAssignmentRow(
    function: ApiFunction,
    configs: List<ApiConfigEntity>,
    activeName: String?,
    assignedUuid: String?,
    onSelect: (String?) -> Unit,
    /** 该行下方的联动提示（当前仅「聊天对话」用：说明发图入口会不会出现）；null=不显示。 */
    footnote: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = activeName?.let { stringResource(R.string.api_fn_default_named, it) }
        ?: stringResource(R.string.api_fn_default)
    val assignedConfig = assignedUuid?.let { id -> configs.firstOrNull { it.uuid == id } }
    val selectedLabel = assignedConfig?.let { "${it.providerName} ${it.modelName}" } ?: defaultLabel

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(function.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            function.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppDropdownField(
            value = selectedLabel,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            AppDropdownMenuItem(
                text = defaultLabel,
                selected = assignedUuid == null,
                onClick = { onSelect(null); expanded = false },
            )
            configs.forEach { cfg ->
                AppDropdownMenuItem(
                    text = "${cfg.providerName} ${cfg.modelName}",
                    selected = assignedUuid == cfg.uuid,
                    onClick = { onSelect(cfg.uuid); expanded = false },
                )
            }
        }
        footnote?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 「聊天对话」行的联动提示——把「这个模型看不看得懂图」翻译成用户真正关心的那句：
 * 聊天「+」里到底有没有「照片」。看不懂图时同时给出**逃生口**（手动开启），
 * 因为能力识别可能因服务商不给元数据 + 探针判不出而落到「不确定」。
 */
@Composable
private fun chatVisionFootnote(hint: FunctionVisionHint): String? = when (hint) {
    FunctionVisionHint.NO_CONFIG -> null // 一个配置都没有时，本屏其它地方已经在提示了
    FunctionVisionHint.HAS_VISION -> stringResource(R.string.api_fn_chat_vision_on)
    FunctionVisionHint.NO_VISION -> stringResource(R.string.api_fn_chat_vision_off)
}

/**
 * 「图片理解」行的联动提示——把「这个模型看不看得懂图」翻译成用户真正关心的后果：
 * 你发的照片以后**搜不搜得到**。
 *
 * 看不懂图时这条链会静默走兜底，照片在记忆里只剩「发送了一张图片」七个字，撞上向量库
 * 那道与图片无关的 8 字下限 → 这张照片永远进不了语义检索。屏上不说，用户发现不了
 * （R4 §五·用户 2026-08-29 拍板「用辅助文字说明一下」）。逃生口与「聊天对话」同款。
 */
@Composable
private fun imageVisionFootnote(hint: FunctionVisionHint): String? = when (hint) {
    FunctionVisionHint.NO_CONFIG -> null // 同上：一个配置都没有时本屏别处已在提示
    FunctionVisionHint.HAS_VISION -> stringResource(R.string.api_fn_image_vision_on)
    FunctionVisionHint.NO_VISION -> stringResource(R.string.api_fn_image_vision_off)
}

@Composable
private fun categoryLabel(category: ApiFunctionCategory): String = when (category) {
    ApiFunctionCategory.CONVERSATION -> stringResource(R.string.api_fn_cat_conversation)
    ApiFunctionCategory.BACKGROUND -> stringResource(R.string.api_fn_cat_background)
    ApiFunctionCategory.CONTENT -> stringResource(R.string.api_fn_cat_content)
}
