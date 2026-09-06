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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.KernelObservatoryRow
import com.situ.aichat.ui.settings.KernelObservatoryViewModel
import com.situ.aichat.util.DateFormatters

/**
 * 内核观测台的全部文案（琉璃·A-6 逐字复制·**图纸 §3.5 显式豁免双语 · 勿顺手抽 res**）。
 * 与暖陶 `KernelObservatoryScreen.kt` 同值；改一侧必须同步另一侧。
 */
private object KernelText {
    const val TITLE = "内核观测台"
    const val LOADING = "读取中…"
    const val EMPTY = "还没有角色。"
    const val FLAG_TENSION = "⚠️ 张力越顶"
    const val FLAG_ATTACHMENT_PREFIX = "⚠️ 依恋越平衡点("
    const val FLAG_OPENNESS = "⚠️ 坦诚越顶"
    const val NEVER = "从未"
}

/**
 * 内核观测台（琉璃·图纸 2026-09-06 卷五 §4.1 屏 26·A-11「只读三屏最薄」）。与暖陶
 * `KernelObservatoryScreen` 共用 [KernelObservatoryViewModel]。
 *
 * **双守卫范式原样保留**：入口（成长设置页）已有一道 `BuildConfig.DEBUG` 门，屏内这一道是第二道
 * ——release 构建里就算被谁导航到，也立刻 `onBack()` 退出去。
 *
 * 行是**一整句**（「关系：亲密 62 信任 55 …」）不是「标题 + 值」，故落在 [LiuliRowBase] + 一枚 `Text` 上
 * 而不是 A-11 写的 `LiuliValueRow(onClick = null)`——后者会多出一个空值节点让读屏念一次空（见图纸 §11 D-18）。
 */
@Composable
fun LiuliKernelObservatoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KernelObservatoryViewModel = hiltViewModel(),
) {
    if (!BuildConfig.DEBUG) {
        onBack()
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliKernelObservatoryContent(
        loading = state.loading,
        rows = state.rows,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 观测台内容层（纯参数·可测）。三态：读取中 / 没有角色 / 每角色一组。 */
@Composable
internal fun LiuliKernelObservatoryContent(
    loading: Boolean,
    rows: List<KernelObservatoryRow>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = KernelText.TITLE,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(KernelText.TITLE) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    when {
                        loading -> LiuliGroup { HintRow(KernelText.LOADING) }
                        rows.isEmpty() -> LiuliGroup { HintRow(KernelText.EMPTY) }
                        else -> rows.forEach { row ->
                            LiuliGroup(header = row.name) {
                                // 十二行是一段诊断文本，不是十二条设置项——行间不画发丝（复核 R1 C-8）。
                                observatoryLines(row).forEach { line -> BodyRow(line, divider = false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一张角色卡的全部行（**拼法逐字照暖陶 `CharacterCard`**：越顶标记 → 关系 → 正压 → 负压 → 四场 →
 * 预算 → 命中 → 意图 → 性格 → 名分 → 上次分析 → 下次窗口）。抽成纯函数便于 T1 逐句核。
 */
internal fun observatoryLines(row: KernelObservatoryRow): List<String> = buildList {
    val flags = buildList {
        if (row.tensionOverCap) add(KernelText.FLAG_TENSION)
        if (row.attachmentOverEquilibrium) add("${KernelText.FLAG_ATTACHMENT_PREFIX}${row.attachmentCap})")
        if (row.opennessOverCap) add(KernelText.FLAG_OPENNESS)
    }
    if (flags.isNotEmpty()) add(flags.joinToString("  "))
    add("关系：" + row.relationship.joinToString("  ") { "${it.first}${it.second}" })
    // 卷二双压（P-9）：净额是差、这两行是那两股力本身——越矛盾阈的维带 ⚠️。
    add("正压：" + row.pressure.joinToString("  ") { "${it.first}${it.second.first}${if (it.third) "⚠️" else ""}" })
    add("负压：" + row.pressure.joinToString("  ") { "${it.first}${it.second.second}${if (it.third) "⚠️" else ""}" })
    add(
        "四场：安全感${row.fieldRead.security}(参 ${row.field.security})  投入度${row.fieldRead.investment}(参 ${row.field.investment})  " +
            "效价${row.fieldRead.valence}(参 ${row.field.valence})  激活度${row.fieldRead.arousal}(参 ${row.field.arousal})",
    )
    add("预算：今日已用 ${row.field.budgetUsed}/${AffectField.DAILY_BUDGET}  上次更新：${formatStamp(row.field.updatedAt.takeIf { it > 0L })}")
    add(
        if (row.hitsAgoHours == null) {
            "命中：—"
        } else {
            "命中：${row.field.hits.joinToString(" ").ifEmpty { "—" }}（${row.hitsAgoHours}h 前）"
        },
    )
    add("意图：" + row.intents.joinToString("  ").ifEmpty { "—" })
    add("性格：" + row.personality.joinToString("  ") { "${it.first}${it.second}" })
    add("名分：${row.relationshipName ?: "—"}  原型：${row.archetypeId ?: "—"}  阶段：${row.currentPhase ?: "—"}")
    add("上次分析：${formatStamp(row.lastAnalysisDate)}  距上次：${row.roundsSinceLastAnalysis} 轮  累计：${row.totalAnalysisCount} 次")
    add("下次窗口预估：前置 ${row.leadInSize} 条 + 新内容 ${row.freshSize} 条")
}

/** 时间戳 → 可读串（「从未」兜底·逐字照暖陶 `formatStamp`）。 */
internal fun formatStamp(millis: Long?): String =
    if (millis == null) KernelText.NEVER else DateFormatters.yearMonthDayHourMinute(millis)

/** 三态提示行。 */
@Composable
private fun HintRow(text: String) {
    LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.rowTwoLinePad, verticalAlignment = Alignment.Top) {
        Text(text, style = AppTypography.listPreview, color = AppTheme.colors.text.secondary, modifier = Modifier.fillMaxWidth())
    }
}

/** 一整句诊断行（只读·不可点）。 */
@Composable
private fun BodyRow(text: String, divider: Boolean) {
    LiuliRowBase(
        divider = divider,
        minHeight = 0.dp,
        verticalPadding = LiuliPageGeometry.rowTwoLinePad,
        verticalAlignment = Alignment.Top,
    ) {
        Text(text, style = AppTypography.listPreview, color = AppTheme.colors.text.secondary, modifier = Modifier.fillMaxWidth())
    }
}
