package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRadioRow
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.settings.narrativeDetailFooter

/**
 * 线下见面设置页文案（琉璃·设置全面重构卷二）：
 * B-3 已移除无入口的自定义图片选项；见面记忆（次数 / 字数上限）已搬至「记忆」页。
 * 存储 raw 值（plain/normal/detailed/custom、particle/solidColor、stars/firefly/dust）与线下叙事 prompt 耦合，零碰。
 */
private object ImmersiveText {
    const val INVITE_TITLE = "邀约主导权"
    const val INVITE_FOOTER =
        "开启时，角色会根据聊天情境主动提议见面。关闭后，只有你从聊天工具栏点「发起线下见面」才会进入线下模式。"
    const val INVITE_ROW = "角色主动发起见面"
    const val INVITE_ROW_SUB = "关闭后，角色不会主动邀你见面"

    const val INPUT_TITLE = "沉浸输入"
    const val INPUT_FOOTER =
        "开启后，线下见面时你可以分步描述环境、动作、对话和内心想法，获得更沉浸的角色扮演体验。"
    const val INPUT_ROW = "线下见面沉浸模式"
    const val INPUT_ROW_SUB = "输入框变为环境→动作→对话→内心四步输入"

    const val NARRATIVE_TITLE = "叙事风格"
    const val NARRATIVE_PLAIN = "平淡"
    const val NARRATIVE_NORMAL = "正常"
    const val NARRATIVE_DETAILED = "细腻"
    const val NARRATIVE_CUSTOM = "自定义"

    const val STYLE_TITLE = "写作风格指导"
    const val STYLE_SUB = "控制整体的写作风格、对话与描写的比例"
    const val STYLE_PLACEHOLDER = "对话为主，动作和环境简单穿插。不要用文学腔，像朋友聊天一样自然"
    const val DIRECTIVE_TITLE = "每轮叙事指令"
    const val DIRECTIVE_SUB = "每行写一条指令，系统每轮随机抽一条发给 AI"
    const val DIRECTIVE_PLACEHOLDER = "让角色主动问用户一个问题\n让角色提起一件最近的小事\n让角色注意到周围的某个东西"
    const val EMOTION_TITLE = "情绪底色"
    const val EMOTION_SUB = "每行写一种情绪氛围，系统每轮随机抽一条"
    const val EMOTION_PLACEHOLDER = "轻松愉快的聊天氛围\n安静但不尴尬的相处\n有点想靠近的心情"

    const val AFTERGLOW_TITLE = "见面后"
    const val AFTERGLOW_FOOTER = "见面记忆的注入条数与字数预算已统一收进「记忆」设置。"
    const val AFTERGLOW_ROW = "见面后余温消息"
    const val AFTERGLOW_SUB = "见面结束几小时后，TA 会主动发来一条回味见面的短消息"

    const val BACKGROUND_TITLE = "背景"
    const val BACKGROUND_FOOTER = "线下模式是角色邀请你面对面见面时的沉浸式叙事界面。"
    const val BG_PARTICLE = "柔和粒子"
    const val BG_SOLID = "纯色"
    const val PARTICLE_STARS = "✦ 星光"
    const val PARTICLE_FIREFLY = "✧ 萤火"
    const val PARTICLE_DUST = "· 微尘"
    const val BG_COLOR_LABEL = "背景色"
    const val BG_COLOR_PLACEHOLDER = "FF6B6B"
}

private val COLOR_FIELD_WIDTH = 140.dp
private val BLOCK_GAP = 12.dp
private val SUB_INDENT = LiuliPageGeometry.groupPadH

/** 线下见面页的写口（保留兼容参数默认值，避免破坏外部调用）。 */
@Immutable
data class LiuliImmersiveCallbacks(
    val onSetCharacterCanInitiate: (Boolean) -> Unit,
    val onSetImmersiveInput: (Boolean) -> Unit,
    val onSetNarrativeDetail: (String) -> Unit,
    val onSetCustomStyle: (String) -> Unit,
    val onSetCustomDirective: (String) -> Unit,
    val onSetCustomEmotion: (String) -> Unit,
    val onSetMeetingMemoryInjectCount: (Int) -> Unit = {},
    val onSetMeetingMemoryMaxLength: (Int) -> Unit = {},
    val onSetAfterglowEnabled: (Boolean) -> Unit,
    val onSetBackgroundStyle: (String) -> Unit,
    val onSetParticleStyle: (String) -> Unit,
    val onSetBackgroundColor: (String) -> Unit,
)

@Composable
internal fun ColumnScope.liuliImmersiveGroups(s: AppSettings, callbacks: LiuliImmersiveCallbacks) {
    LiuliGroup(header = ImmersiveText.INVITE_TITLE, footer = ImmersiveText.INVITE_FOOTER) {
        LiuliToggleRow(
            title = ImmersiveText.INVITE_ROW,
            subtitle = ImmersiveText.INVITE_ROW_SUB,
            checked = s.characterCanInitiateOfflineMeeting,
            onCheckedChange = callbacks.onSetCharacterCanInitiate,
            divider = false,
        )
    }

    LiuliGroup(header = ImmersiveText.INPUT_TITLE, footer = ImmersiveText.INPUT_FOOTER) {
        LiuliToggleRow(
            title = ImmersiveText.INPUT_ROW,
            subtitle = ImmersiveText.INPUT_ROW_SUB,
            checked = s.offlineImmersiveInputEnabled,
            onCheckedChange = callbacks.onSetImmersiveInput,
            divider = false,
        )
    }

    LiuliGroup(
        header = ImmersiveText.NARRATIVE_TITLE,
        footer = narrativeDetailFooter(s.offlineNarrativeDetailRaw),
    ) {
        val raw = s.offlineNarrativeDetailRaw
        LiuliRadioRow(ImmersiveText.NARRATIVE_PLAIN, raw == "plain", { callbacks.onSetNarrativeDetail("plain") }, divider = false)
        LiuliRadioRow(ImmersiveText.NARRATIVE_NORMAL, raw == "normal", { callbacks.onSetNarrativeDetail("normal") })
        LiuliRadioRow(ImmersiveText.NARRATIVE_DETAILED, raw == "detailed", { callbacks.onSetNarrativeDetail("detailed") })
        LiuliRadioRow(ImmersiveText.NARRATIVE_CUSTOM, raw == "custom", { callbacks.onSetNarrativeDetail("custom") })
    }

    if (s.offlineNarrativeDetailRaw == "custom") {
        PromptGroup(
            title = ImmersiveText.STYLE_TITLE,
            subtitle = ImmersiveText.STYLE_SUB,
            value = s.offlineCustomStylePrompt,
            placeholder = ImmersiveText.STYLE_PLACEHOLDER,
            onValueChange = callbacks.onSetCustomStyle,
        )
        PromptGroup(
            title = ImmersiveText.DIRECTIVE_TITLE,
            subtitle = ImmersiveText.DIRECTIVE_SUB,
            value = s.offlineCustomDirectivePrompt,
            placeholder = ImmersiveText.DIRECTIVE_PLACEHOLDER,
            onValueChange = callbacks.onSetCustomDirective,
        )
        PromptGroup(
            title = ImmersiveText.EMOTION_TITLE,
            subtitle = ImmersiveText.EMOTION_SUB,
            value = s.offlineCustomEmotionPrompt,
            placeholder = ImmersiveText.EMOTION_PLACEHOLDER,
            onValueChange = callbacks.onSetCustomEmotion,
        )
    }

    LiuliGroup(header = ImmersiveText.AFTERGLOW_TITLE, footer = ImmersiveText.AFTERGLOW_FOOTER) {
        LiuliToggleRow(
            title = ImmersiveText.AFTERGLOW_ROW,
            subtitle = ImmersiveText.AFTERGLOW_SUB,
            checked = s.offlineAfterglowEnabled,
            onCheckedChange = callbacks.onSetAfterglowEnabled,
            divider = false,
        )
    }

    LiuliGroup(header = ImmersiveText.BACKGROUND_TITLE, footer = ImmersiveText.BACKGROUND_FOOTER) {
        // B-3：旧存量若为 customImage，按柔和粒子回退展示，不暴露死选项。
        val bg = if (s.offlineBackgroundStyleRaw == "customImage") "particle" else s.offlineBackgroundStyleRaw
        LiuliRadioRow(ImmersiveText.BG_PARTICLE, bg == "particle", { callbacks.onSetBackgroundStyle("particle") }, divider = false)
        LiuliRadioRow(ImmersiveText.BG_SOLID, bg == "solidColor", { callbacks.onSetBackgroundStyle("solidColor") })
        when (bg) {
            "particle" -> {
                val p = s.offlineParticleStyleRaw
                LiuliRadioRow(ImmersiveText.PARTICLE_STARS, p == "stars", { callbacks.onSetParticleStyle("stars") }, modifier = Modifier.padding(start = SUB_INDENT))
                LiuliRadioRow(ImmersiveText.PARTICLE_FIREFLY, p == "firefly", { callbacks.onSetParticleStyle("firefly") }, modifier = Modifier.padding(start = SUB_INDENT))
                LiuliRadioRow(ImmersiveText.PARTICLE_DUST, p == "dust", { callbacks.onSetParticleStyle("dust") }, modifier = Modifier.padding(start = SUB_INDENT))
            }
            "solidColor" -> LiuliRowBase(verticalPadding = BLOCK_GAP) {
                Text(
                    ImmersiveText.BG_COLOR_LABEL,
                    style = AppTypography.body,
                    color = AppTheme.colors.text.primary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(LiuliPageGeometry.tileGap))
                LiuliField(
                    value = s.offlineBackgroundColor,
                    onValueChange = { callbacks.onSetBackgroundColor(it.trim().take(6)) },
                    placeholder = ImmersiveText.BG_COLOR_PLACEHOLDER,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.width(COLOR_FIELD_WIDTH),
                )
            }
        }
    }
}

@Composable
private fun PromptGroup(
    title: String,
    subtitle: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    LiuliGroup(header = title) {
        LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.groupPadH, verticalAlignment = Alignment.Top) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
                Text(subtitle, style = AppTypography.secondary, color = AppTheme.colors.text.secondary)
                LiuliField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = placeholder,
                    singleLine = false,
                    minHeight = LiuliMultiline.MIN_HEIGHT,
                )
            }
        }
    }
}

/** 多行组的默认最小高（契约 §6.5「输入行（T2）」的「min 96」）。 */
internal object LiuliMultiline {
    val MIN_HEIGHT = 96.dp
}
