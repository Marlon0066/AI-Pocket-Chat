package com.situ.aichat.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.PersonaCompileMeta
import com.situ.aichat.data.model.personaCurrentMarkerVisible
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import kotlin.math.roundToInt

// 活人感内核·卷一《人设编译器》的性格区上半（图纸 §4.1 / §4.2·已过审 mockup v1.2）：
// 生成卡（状态 A/B + D-2 提醒条 + D-5 失败提示 + 丢弃提示）与双标记滑杆（本性可拖 / 「现在」只读竖线）。
// 既有 DimensionSlider（CharacterEditFormFields.kt）零碰——关系质感 8 根继续用它，二者并存（图纸 Y-N6）。

/**
 * 生成卡（图纸 §4.1）。状态 A = 从未编译过（`source == default`），状态 B = 编译过。
 *
 * [personaBlank] 时主按钮置灰不可点（D-5 / Y-E1：不发起任何调用）；[compiling] 时转「生成中…」且不可点（I-4）。
 */
@Composable
internal fun PersonaCompileCard(
    meta: PersonaCompileMeta,
    personaStale: Boolean,
    personaBlank: Boolean,
    compiling: Boolean,
    /** R1 复核 TODO-1：表单里的人设与库里那份不一致（改了没保存）⇒ 编译会读到旧/空人设，先提示。 */
    needsSave: Boolean,
    onCompile: () -> Unit,
) {
    val colors = AppTheme.colors
    val neverCompiled = meta.source == PersonaCompileMeta.SOURCE_DEFAULT
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface.raised)
            .border(1.dp, colors.surface.stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(
                if (neverCompiled) R.string.persona_compile_title_idle else R.string.persona_compile_title_done,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = if (neverCompiled) colors.text.primary else colors.text.secondary,
        )
        if (neverCompiled) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.persona_compile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text.tertiary,
            )
        }
        Spacer(Modifier.height(12.dp))
        AppButton(
            onClick = onCompile,
            modifier = Modifier.align(Alignment.End),
            style = AppButtonStyle.Tonal,
            enabled = !personaBlank && !compiling,
        ) {
            if (compiling) {
                AppLoadingRing(size = AppLoadingRingSize.Small)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    when {
                        compiling -> R.string.persona_compile_button_running
                        neverCompiled -> R.string.persona_compile_button
                        else -> R.string.persona_compile_button_again
                    },
                ),
            )
        }
        // R1 复核 TODO-1：人设改了没保存就点生成 ⇒ 协调器读的是库里那份，给一条可操作提示，
        // 不替用户隐式落库（保存是用户的动作，生成按钮不该有隐藏副作用）。
        if (needsSave) {
            Spacer(Modifier.height(8.dp))
            HintBanner(
                text = stringResource(R.string.persona_compile_needs_save_hint),
                container = colors.status.warningContainer,
                content = colors.status.onWarning,
            )
        }
        // D-2：人设改过、数值还是上一版编译的 ⇒ 提醒，但**绝不自动改任何数值**。
        if (personaStale) {
            Spacer(Modifier.height(8.dp))
            HintBanner(
                text = stringResource(R.string.persona_compile_stale_hint),
                container = colors.status.warningContainer,
                content = colors.status.onWarning,
            )
        }
        // D-5：上次编译失败（失败戳晚于成功戳）⇒ 说明数值没动过。
        if (meta.lastFailedAt > meta.compiledAt) {
            Spacer(Modifier.height(8.dp))
            HintBanner(
                text = stringResource(R.string.persona_compile_failed_hint),
                container = colors.status.errorContainer,
                content = colors.status.onError,
            )
        } else if (meta.droppedCount > 0) {
            // Y-6：上次成功编译丢过条目 ⇒ 明说丢了几条，绝不静默吞。
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.persona_compile_dropped_hint, meta.droppedCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text.tertiary,
            )
        }
    }
}

@Composable
private fun HintBanner(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = content,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * 双标记滑杆（图纸 §4.2 · D-3）：可拖的是**本性**[anchor]，[current]（现在）只作一条只读竖线。
 *
 * 名称行 / 滑杆行 / 端点说明行与既有 [DimensionSlider] 同规格；多出「现在 N」标签与楷体依据短语行。
 * 偏移 ≤5 时竖线整条隐藏（[personaCurrentMarkerVisible]）——未编译角色本性==现在，天然不显示。
 */
@Composable
internal fun PersonaAnchorSlider(
    name: String,
    hint: String,
    anchor: Int,
    current: Int,
    basis: String?,
    onChange: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    val markerVisible = personaCurrentMarkerVisible(anchor = anchor, current = current)
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                anchor.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 轨道可用宽 = 总宽 − 拇指直径（M3 Slider 两端各内缩半个拇指，AppSlider 拇指 22.dp）。
            val trackInset = THUMB_DIAMETER / 2
            val markerX = trackInset + (maxWidth - THUMB_DIAMETER) * (current.coerceIn(0, 100) / 100f)
            AppSlider(
                value = anchor.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = 0f..100f,
                // 无障碍：滑杆焦点播报「维度名, 数值」（同 DimensionSlider 口径）。
                modifier = Modifier.semantics { contentDescription = name },
            )
            if (markerVisible) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = markerX - maxWidth / 2 - MARKER_WIDTH / 2)
                        .size(width = MARKER_WIDTH, height = MARKER_HEIGHT)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.text.tertiary)
                        // 只读：不接手势，也不进无障碍焦点（免得与滑杆抢焦点）。
                        .clearAndSetSemantics {},
                )
            }
        }
        if (markerVisible) {
            CurrentMarkerLabel(current = current)
        }
        if (!basis.isNullOrBlank()) {
            // 中文无斜体三件套：引文走楷体点缀，绝不用 italic（设计语言 §2）。
            Text(
                text = stringResource(R.string.persona_anchor_basis, basis),
                style = AppTypography.kaiQuote,
                color = colors.text.tertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 「现在 N」标签：水平与竖线对齐，贴边时向内收（图纸 §4.2）。 */
@Composable
private fun CurrentMarkerLabel(current: Int) {
    val density = LocalDensity.current
    var labelWidth by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val trackInset = THUMB_DIAMETER / 2
        val markerX = trackInset + (maxWidth - THUMB_DIAMETER) * (current.coerceIn(0, 100) / 100f)
        val rawX = markerX - labelWidth / 2
        val clampedX = rawX.coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp))
        Text(
            text = stringResource(R.string.persona_anchor_current_marker, current),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.text.tertiary,
            modifier = Modifier
                .offset(x = clampedX)
                .onSizeChanged { labelWidth = with(density) { it.width.toDp() } },
        )
    }
}

/** AppSlider 的拇指直径（AppSlider.kt 的 `Modifier.size(22.dp)`）——竖线定位要按同一几何量算。 */
private val THUMB_DIAMETER = 22.dp

/** 竖线：宽 2dp、高 = 轨道 4dp + 上下各出头 2dp（图纸 §4.2 锁定值）。 */
private val MARKER_WIDTH = 2.dp
private val MARKER_HEIGHT = 8.dp
