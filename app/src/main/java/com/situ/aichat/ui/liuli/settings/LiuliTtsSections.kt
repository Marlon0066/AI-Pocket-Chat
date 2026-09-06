package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.situ.aichat.tts.TtsResponseFormat
import com.situ.aichat.tts.pricing.TtsCostEstimate
import com.situ.aichat.tts.provider.MiniMaxRegion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.page.LiuliMenuRow
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase

/**
 * 语音 / TTS 设置页的全部文案（琉璃·A-6「硬编码中文屏逐字复制」）。
 *
 * 暖陶 `TtsConfigurationScreen.kt` **一枚资源键都没有**，本卷零新增键，故这里逐字复制一份。
 * **改任一侧必须同步另一侧**（登记契约 §10.3 F 备注）。教程正文不在此列——那五段借暖陶
 * `tutorialContent`（见 [LiuliTtsTutorialCard]）。
 */
internal object LiuliTtsText {
    const val PAGE_TITLE = "语音 / TTS 设置"
    const val ENGINE_LABEL = "语音引擎"
    const val SYSTEM_NOTE =
        "使用本机系统语音引擎（免 key、免费）。国行 HyperOS 多为小米引擎（部分需联网、质量参差），" +
            "无 Google TTS——追求自然度与情绪建议改用 MiniMax 云。角色的具体音色在「角色编辑 → 语音」里选择。"
    // 暖陶写「系统音色（默认）」一枚浮标签；无框输入行的标签列只有 96 宽，八个字会折成两行——
    // 把「（默认）」挪成值为空时的占位，语义不变（复核 R1 修后装机）。
    const val SYSTEM_VOICE_LABEL = "系统音色"
    const val SYSTEM_VOICE_PLACEHOLDER = "默认"
    const val BASE_URL_LABEL = "Base URL"
    const val MODEL_LABEL = "模型名"
    const val API_KEY_LABEL = "API Key"
    const val API_KEY_LABEL_SET = "API Key（已设置，留空则不修改）"
    /** 拆成「标签 + 占位」两截给 96 宽标签列用（合起来仍是暖陶那句·复核 R1 C6：整句塞标签列会折成三行）。 */
    const val API_KEY_SET_PLACEHOLDER = "已设置，留空则不修改"
    /** 候选一条都没筛中（暖陶 `:362–364`「无匹配项」·复核 R1 A4）。 */
    const val NO_MATCH = "无匹配项"
    const val REMOTE_VOICE_LABEL = "默认音色 ID"
    const val REGION_LABEL = "MiniMax 区域"
    const val REGION_CUSTOM = "自定义端点"
    const val FORMAT_LABEL = "音频格式"
    const val PREVIEW = "试听"
    const val SAVE = "保存"
    const val SAVED_TOAST = "已保存"
    const val COST_PREFIX_HEAD = "近 7 天 "
    const val COST_PREFIX_TAIL = " 字符"
    const val COST_MONTHLY_PREFIX = " · 预估月费 ~$"
    const val COST_NO_PRICE = " · 该模型未公开按量单价"
}

/** 一条目录项（暖陶 private `TtsCatalogItem` 的同形对应件）。 */
@Immutable
internal data class LiuliTtsCatalogItem(val id: String, val name: String, val subtitle: String?)

/** 格式芯片排的行内缝（逐字照暖陶 `ResponseFormatPicker` 的 8）。 */
private val CHIP_GAP = 8.dp

/**
 * MiniMax 区域选择行（暖陶 `MiniMaxRegionPicker` 的对应件）：右值 = 识别出的区域名（识别不出 = 自定义端点），
 * 区域的说明句作副标。选中即回填该区域的 baseUrl。
 */
@Composable
internal fun LiuliMiniMaxRegionRow(
    baseUrl: String,
    onPick: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    divider: Boolean = true,
) {
    val region = MiniMaxRegion.detect(baseUrl)
    LiuliMenuRow(
        title = LiuliTtsText.REGION_LABEL,
        subtitle = region?.localizedHint,
        value = region?.localizedLabel ?: LiuliTtsText.REGION_CUSTOM,
        options = MiniMaxRegion.entries.map { entry ->
            LiuliMenuEntry(
                text = entry.localizedLabel,
                selected = entry == region,
                onClick = { onPick(entry.baseUrl) },
            )
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        divider = divider,
    )
}

/** 音频格式芯片排（暖陶 `ResponseFormatPicker` 的对应件·`LiuliChip` 互斥组）。 */
@Composable
internal fun LiuliResponseFormatRow(
    value: TtsResponseFormat,
    onPick: (TtsResponseFormat) -> Unit,
    divider: Boolean = true,
) {
    LiuliRowBase(divider = divider, verticalPadding = LiuliPageGeometry.rowTwoLinePad) {
        Text(
            LiuliTtsText.FORMAT_LABEL,
            style = AppTypography.body,
            color = AppTheme.colors.text.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = LiuliPageGeometry.tileGap),
            horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TtsResponseFormat.entries.forEach { format ->
                LiuliChip(
                    selected = value == format,
                    onClick = { onPick(format) },
                    label = format.displayName,
                )
            }
        }
    }
}

/**
 * MiniMax 成本提示句（💰 **只读**·暖陶 `CostHint` 的对应件）。两条分支与格式串逐字照抄
 * （`TtsConfigurationScreen.kt:427–429`）——这里一个字都不许改。
 */
internal fun liuliTtsCostText(estimate: TtsCostEstimate): String = buildString {
    append(LiuliTtsText.COST_PREFIX_HEAD)
    append(estimate.actualCharactersLast7Days)
    append(LiuliTtsText.COST_PREFIX_TAIL)
    val usd = estimate.projectedMonthlyUSD
    if (usd != null) {
        append(LiuliTtsText.COST_MONTHLY_PREFIX)
        append("%.2f".format(usd))
    } else {
        append(LiuliTtsText.COST_NO_PRICE)
    }
}
