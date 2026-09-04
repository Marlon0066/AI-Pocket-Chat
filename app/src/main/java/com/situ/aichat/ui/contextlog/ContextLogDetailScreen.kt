package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.LogEntryEntity
import com.situ.aichat.diagnostics.LogShareFormat
import com.situ.aichat.diagnostics.LogTokenFormat
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 日志详情屏（批 D·D-3）：概览 + token 用量 + 错误 + 回复摘要 + 上下文入口 + detail-关提示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextLogDetailScreen(
    onBack: () -> Unit,
    onOpenSegments: (Long) -> Unit,
    onOpenContextText: (Long) -> Unit,
    onOpenResponseText: (Long) -> Unit,
    viewModel: ContextLogDetailViewModel = hiltViewModel(),
) {
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            AppTopBar(
                title = "日志详情",
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        val e = entry
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (e == null) {
                Spacer(Modifier.height(40.dp))
                Text("记录不存在或已被清除", style = AppTheme.typography.body, color = AppTheme.colors.text.tertiary)
                return@Column
            }

            OverviewCard(e)
            if (e.isSuccess) TokenUsageCard(e)
            if (!e.isSuccess && !e.errorMessage.isNullOrBlank()) ErrorCard(e.errorMessage)
            // 工具调用遥测（2026-07-12 工具可见性）：仅聊天管线有值；旧行/后台生成路为空 → 整节隐藏。
            viewModel.decodeToolInfo(e.toolInfoJson)?.let { ToolCallCard(it) }

            val hasResponse = !e.responseContent.isNullOrBlank()
            val hasContext = e.fullContext.isNotBlank()
            val hasSegments = e.contextSegmentsJson.isNotBlank()

            if (hasResponse) {
                SectionCard("回复摘要") {
                    Text(
                        // take(500)：摘要区 maxLines=4 只显几行，但 Text 的段落构建吃整串——全量记录后
                        // responseContent 可达 20 万字，整串进 Text 是可感知的组合卡顿；500 字远超四行所需。
                        e.responseContent.take(RESPONSE_PREVIEW_CHARS),
                        style = AppTheme.typography.secondary,
                        color = AppTheme.colors.text.primary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    NavRow("查看全文") { onOpenResponseText(e.id) }
                }
            }
            if (hasSegments || hasContext) {
                SectionCard(null) {
                    if (hasSegments) NavRow("查看上下文结构") { onOpenSegments(e.id) }
                    if (hasContext) NavRow("查看完整上下文") { onOpenContextText(e.id) }
                }
            }
            if (!hasResponse && !hasContext) {
                Box(
                    Modifier.fillMaxWidth().background(AppTheme.colors.surface.sunken, AppTheme.shapes.medium).padding(13.dp),
                ) {
                    Text(
                        "详细记录已禁用——仅保留元数据与分段统计，未存上下文 / 回复正文。",
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.text.secondary,
                    )
                }
            }
            // 动作排（D-3 打磨·②·mockup §2）：整条日志打包复制/导出（元数据头+完整上下文+回复全文·
            // LogShareFormat 拼装吃大字符串 → 放 launch 的默认调度器，主线程零大文本工作；失败条同样可用=排查素材）。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(
                    onClick = {
                        // Default 调度器起跳：rememberCoroutineScope 默认 Main，而 entryText 拼 20 万字级大串。
                        scope.launch(Dispatchers.Default) {
                            val text = LogShareFormat.entryText(e)
                            LogShareActions.copyOrExport(
                                context, text, LogShareFormat.exportFileName(e.source, e.timestampMillis),
                            )
                        }
                    },
                    style = AppButtonStyle.Tonal,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.contextlog_copy_all))
                }
                AppButton(
                    onClick = {
                        scope.launch(Dispatchers.Default) {
                            val text = LogShareFormat.entryText(e)
                            LogShareActions.exportWithFeedback(
                                context, text, LogShareFormat.exportFileName(e.source, e.timestampMillis),
                            )
                        }
                    },
                    style = AppButtonStyle.Tonal,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.contextlog_export_txt))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 回复摘要预览截取长度（4 行所需的数倍余量；详见摘要区注释）。 */
private const val RESPONSE_PREVIEW_CHARS = 500

@Composable
private fun OverviewCard(e: LogEntryEntity) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(e.isSuccess)
            Spacer(Modifier.weight(1f))
            Text(formatLogTimeFull(e.timestampMillis), style = AppTheme.typography.captionNumeric, color = AppTheme.colors.text.secondary)
        }
        Spacer(Modifier.height(4.dp))
        KvRow("来源", e.source)
        KvRow("角色", e.characterName.ifBlank { "—" })
        KvRow("模型", e.modelName)
        KvRow("消息数", e.messageCount.toString())
        formatDuration(e.durationMillis)?.let { KvRow("耗时", it) }
    }
}

@Composable
private fun TokenUsageCard(e: LogEntryEntity) {
    SectionCard("Token 用量") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("输入", e.promptTokens, Modifier.weight(1f))
            MetricCell("输出", e.completionTokens, Modifier.weight(1f))
            MetricCell("总计", e.promptTokens + e.completionTokens, Modifier.weight(1f))
        }
        // 命中率（四小件·2026-07-16·§4.3）：既有绝对数三项之后**追加**，样式随既有 extras 行（零新样式）。
        // 算法与列表页汇总卡同口径（roundToInt 四舍五入整百分比）；hit+miss==0（无缓存供应商）→ 不追加。
        val cacheTotal = e.cacheHitTokens + e.cacheMissTokens
        val hitRate = if (cacheTotal > 0) (e.cacheHitTokens * 100.0 / cacheTotal).roundToInt() else null
        val extras = buildList {
            if (e.reasoningTokens > 0) add("思考 ${e.reasoningTokens}")
            if (e.cacheHitTokens > 0) add("缓存命中 ${e.cacheHitTokens}")
            if (e.cacheMissTokens > 0) add("未命中 ${e.cacheMissTokens}")
            if (hitRate != null) add(stringResource(R.string.contextlog_cache_rate_inline, hitRate))
        }
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(extras.joinToString(" · "), style = AppTheme.typography.captionNumeric, color = AppTheme.colors.text.secondary)
        }
        if (e.isTokenEstimated) {
            Spacer(Modifier.height(6.dp))
            Text("数值为本地估算（API 未返回用量）", style = AppTheme.typography.caption, color = AppTheme.colors.text.tertiary)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Box(
        Modifier.fillMaxWidth().background(AppTheme.colors.status.errorContainer, AppTheme.shapes.medium).padding(13.dp),
    ) {
        Text(message, style = AppTheme.typography.secondary, color = AppTheme.colors.status.onError)
    }
}

@Composable
private fun MetricCell(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(AppTheme.colors.surface.sunken, AppTheme.shapes.small)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), style = AppTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = AppTheme.colors.text.primary)
        Spacer(Modifier.height(2.dp))
        Text(label, style = AppTheme.typography.caption, color = AppTheme.colors.text.secondary)
    }
}

@Composable
fun SectionCard(title: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface.raised, AppTheme.shapes.medium)
            .border(1.dp, AppTheme.colors.surface.stroke, AppTheme.shapes.medium)
            .padding(13.dp),
    ) {
        if (title != null) {
            Text(title, style = AppTheme.typography.label, color = AppTheme.colors.text.secondary)
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
private fun KvRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary)
        Spacer(Modifier.weight(1f))
        Text(v, style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AppTheme.typography.label, color = AppTheme.colors.accent.text)
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.accent.text)
    }
}

@Composable
private fun StatusPill(isSuccess: Boolean) {
    val container = if (isSuccess) AppTheme.colors.status.successContainer else AppTheme.colors.status.errorContainer
    val on = if (isSuccess) AppTheme.colors.status.onSuccess else AppTheme.colors.status.onError
    Box(Modifier.background(container, AppTheme.shapes.full).padding(horizontal = 11.dp, vertical = 4.dp)) {
        Text(if (isSuccess) "成功" else "失败", style = AppTheme.typography.caption, color = on)
    }
}
