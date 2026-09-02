package com.situ.aichat.ui.settings

import com.situ.aichat.data.model.AffectField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.util.DateFormatters

/**
 * 内核观测台（活人感内核卷零 chunk5）——**开发者调试页**，只读，无任何用户功能。
 *
 * 门控照 [com.situ.aichat.world.WorldDebugEntry] 的双守卫范式：入口行（[GrowthSettingsScreen] 底部）与
 * 本屏首行各判一次 `BuildConfig.DEBUG` ⇒ release 构建两处全短路，**零 UI 变化、入口与路由均不可达**。
 *
 * **有意不做任何视觉设计**（图纸 Z-N7）：骨架与组件用法逐条照抄 [GrowthSettingsScreen]，
 * 不新增颜色 / 动效 / 自定义组件；文案硬编码中文不进 strings.xml（debug-only 不做双语，图纸 §3.5 显式豁免）。
 * 日后若要转成用户可见功能，须单独出 UI 提案过审。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KernelObservatoryScreen(
    onBack: () -> Unit,
    viewModel: KernelObservatoryViewModel = hiltViewModel(),
) {
    if (!BuildConfig.DEBUG) {
        onBack()
        return
    }
    val s by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内核观测台", modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .contentMaxWidth(),
        ) {
            when {
                s.loading -> HintText("读取中…")
                s.rows.isEmpty() -> HintText("还没有角色。")
                else -> s.rows.forEach { row -> CharacterCard(row) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 提示文本（照抄 [GrowthSettingsScreen] 的 growth_disabled_hint 写法）。 */
@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** 单个角色一张卡：越顶标记 → 关系 8 维 → 性格 8 维 → 分析节奏 → 下次窗口预估。 */
@Composable
private fun CharacterCard(row: KernelObservatoryRow) {
    SettingsSection(title = row.name) {
        val flags = buildList {
            if (row.tensionOverCap) add("⚠️ 张力越顶")
            if (row.attachmentOverEquilibrium) add("⚠️ 依恋越平衡点(${row.attachmentCap})")
            if (row.opennessOverCap) add("⚠️ 坦诚越顶")
        }
        if (flags.isNotEmpty()) BodyLine(flags.joinToString("  "))
        BodyLine("关系：" + row.relationship.joinToString("  ") { "${it.first}${it.second}" })
        // 卷二双压（P-9）：净额是差、这两行是那两股力本身——越矛盾阈的维带 ⚠️，卷三调参有据。
        BodyLine("正压：" + row.pressure.joinToString("  ") { "${it.first}${it.second.first}${if (it.third) "⚠️" else ""}" })
        BodyLine("负压：" + row.pressure.joinToString("  ") { "${it.first}${it.second.second}${if (it.third) "⚠️" else ""}" })
        // 卷三 §4.5（照 P-9 写法·debug-only 硬编码中文）：四场 / 日预算 / 最近命中。
        BodyLine(
            "四场：安全感${row.fieldRead.security}(参 ${row.field.security})  投入度${row.fieldRead.investment}(参 ${row.field.investment})  " +
                "效价${row.fieldRead.valence}(参 ${row.field.valence})  激活度${row.fieldRead.arousal}(参 ${row.field.arousal})",
        )
        BodyLine("预算：今日已用 ${row.field.budgetUsed}/${AffectField.DAILY_BUDGET}  上次更新：${formatStamp(row.field.updatedAt.takeIf { it > 0L })}")
        BodyLine(if (row.hitsAgoHours == null) "命中：—" else "命中：${row.field.hits.joinToString(" ").ifEmpty { "—" }}（${row.hitsAgoHours}h 前）")
        // 卷四 §4.6（debug-only 硬编码中文·N7 豁免）：意图队列全部条目（VM 预拼，这里只显示；性格复盘已于修缮卷砍除）。
        BodyLine("意图：" + row.intents.joinToString("  ").ifEmpty { "—" })
        BodyLine("性格：" + row.personality.joinToString("  ") { "${it.first}${it.second}" })
        BodyLine("名分：${row.relationshipName ?: "—"}  原型：${row.archetypeId ?: "—"}  阶段：${row.currentPhase ?: "—"}")
        BodyLine("上次分析：${formatStamp(row.lastAnalysisDate)}  距上次：${row.roundsSinceLastAnalysis} 轮  累计：${row.totalAnalysisCount} 次")
        BodyLine("下次窗口预估：前置 ${row.leadInSize} 条 + 新内容 ${row.freshSize} 条")
    }
}

@Composable
private fun BodyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private fun formatStamp(millis: Long?): String =
    if (millis == null) "从未" else DateFormatters.yearMonthDayHourMinute(millis)
