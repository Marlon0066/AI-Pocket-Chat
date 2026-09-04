package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.story.StoryFieldValueLabel
import com.situ.aichat.story.StoryFieldValueStyle
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 书页与设定族共用的自绘行件（卷二：**只搬不改**自 [StorySettingsScreen]，行为字节级不变）。
 *
 * 自绘分组卡 [SettingsGroup] + 组头 [GroupHeader] + 分隔线 [RowDivider] + 三型紧凑行
 * （[NavRow]/[SwitchRow]/[ChoiceOptionRow]）。搬到独立文件是因为书页两个 Tab 与退役前的旧设定屏
 * 同时用它们；同包 internal，调用点一行未动。
 *
 * 唯一的新增件是 [HubValueLabel]——设定 Tab 行右侧的三态值标（文案与语气由
 * [com.situ.aichat.story.StoryEditableField.valueLabel] 推导，本件只负责染色与删除线）。
 */

@Composable
internal fun GroupHeader(text: String) {
    Text(
        text,
        style = AppTheme.typography.caption,
        color = AppTheme.colors.text.tertiary,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
internal fun SettingsGroup(header: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        GroupHeader(header)
        // clip+background(raised)+border 三连 → appCardSurface（§4.A8）；GroupHeader/RowDivider 零改。
        Column(Modifier.fillMaxWidth().appCardSurface(), content = content)
        // 卷三 V1：组脚注（照 SettingsSection 惯例留在卡外·仅生成组用）。
        if (footer != null) {
            Text(
                footer,
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
internal fun RowDivider() {
    AppListDivider(modifier = Modifier.padding(horizontal = 14.dp), startInset = 0.dp)
}

@Composable
internal fun NavRow(label: String, value: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = AppTheme.typography.body, color = c.text.primary)
        Text(value, style = AppTheme.typography.secondary, color = c.text.secondary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.text.tertiary)
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = AppTheme.typography.body, color = c.text.primary)
            Text(subtitle, style = AppTheme.typography.caption, color = c.text.secondary)
        }
        AppSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ChoiceOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AppTheme.typography.body, color = c.text.primary, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = c.accent.text)
    }
}

/**
 * 设定 Tab 行右侧的三态值标（图纸 §4.3）：已自定义 = 陶土色 Medium；已关闭 / 本书已关 = 次级色 + 删除线；
 * 其余（出厂默认 / 跟随全局 / 全局已关 / 未设置）= 次级色常规。节奏偏好走 echo 直接回显原文摘要。
 */
@Composable
internal fun HubValueLabel(label: StoryFieldValueLabel, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Text(
        text = label.echo ?: label.labelRes?.let { stringResource(it) }.orEmpty(),
        style = AppTheme.typography.caption.copy(
            fontSize = 11.sp,
            fontWeight = if (label.style == StoryFieldValueStyle.CUSTOM) FontWeight.Medium else null,
        ),
        color = if (label.style == StoryFieldValueStyle.CUSTOM) c.accent.text else c.text.tertiary,
        textDecoration = if (label.style == StoryFieldValueStyle.OFF) TextDecoration.LineThrough else null,
        modifier = modifier,
    )
}

/**
 * 标签胶囊（档案卡的维护者标签 / 设定行的 NEW 徽章共用）：常规 = 凹陷底 + 次级字；
 * 高亮 = 陶土容器 + 陶土字（造型照故事域既有 pill，零新 token 对）。
 */
@Composable
internal fun HubTagChip(text: String, highlighted: Boolean) {
    val c = AppTheme.colors
    Text(
        text,
        style = AppTheme.typography.caption.copy(
            fontSize = 10.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else null,
        ),
        color = if (highlighted) c.accent.text else c.text.secondary,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (highlighted) c.accent.container else c.surface.sunken)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
