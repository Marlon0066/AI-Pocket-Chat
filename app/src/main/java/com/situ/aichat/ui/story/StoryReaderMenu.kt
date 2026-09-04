package com.situ.aichat.ui.story

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppSwitch

/**
 * 阅读器 ⋮ 弹出菜单（玻璃质感·随深浅换肤·过审 mockup `story_reader_menu_glass_mockup`）。
 *
 * **锚定修复**：IconButton 与 DropdownMenu 同包一个 [Box]——弹窗锚点 = ⋮ 自身、贴其右下方展开
 * （旧实现两者是胶囊 Row 里的兄弟节点，锚点落在整条胶囊上 → 菜单跑到屏幕左侧）。
 *
 * 玻璃配方与顶栏胶囊同族（chromeScrim / 0.75dp 发丝迎光边的「等价玻璃」，见 [StoryReaderLayout]）：
 * 垫底提到 94%（浮于正文文字上防串行）。
 *
 * 结构照主流阅读 App 菜单式样：动作行（前导暖陶图标）→ 发丝分隔 → 开关行（双行式：标题 + 职责小字，
 * 整行点击切换、Switch 只作视觉·P1-20 语义沿用 Role.Switch + stateDescription）→ 发丝分隔 → 字号四档。
 * **卷三 §4.6 起本菜单只管「怎么读」+ 一个导航**：书页 / 阅读动画 / 字号
 * （「沉浸氛围」开关随 2026-08-03 氛围演出层退役一并删除）——
 * 「刚读完这章」的三个动作在章末饰线 ⋯ 浮层（[storyChapterEndZoneItems]），推进类动作在推进区。
 * 开关与字号**拨完不关菜单**：隔着玻璃即时看到背景/排版变化（活预览）。
 * 字号分档不用 AppSegmentedControl：其配色跟 App 主题，在深玻璃上会亮块突兀——本屏纸面层与主题正交，
 * 改用纸面自适应四格（selectable + Role.RadioButton 语义）。
 */
@Composable
internal fun StoryReaderMenu(
    readingAnimationsEnabled: Boolean,
    fontSizeIndex: Int,
    isDark: Boolean,
    contentColor: Color,
    secondaryColor: Color,
    borderColor: Color,
    triggerColor: Color,
    onOpenBookHub: (() -> Unit)?,
    onToggleAnimations: (Boolean) -> Unit,
    onSetFontSizeIndex: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val setExpanded: (Boolean) -> Unit = { expanded = it; onExpandedChange(it) }
    val surface = StoryReaderLayout.menuSurfaceColor(isDark)
    val divider = StoryReaderLayout.menuDividerColor(isDark)
    val accent = StoryReaderLayout.menuAccentColor(isDark)

    Box {
        // 触发钮 = 圆形玻璃小岛（统一岛高 44dp·与顶栏返回钮同配方）；菜单锚定其上、贴右下展开。
        Surface(
            onClick = { setExpanded(true) },
            shape = CircleShape,
            color = triggerColor,
            border = BorderStroke(0.75.dp, borderColor),
            modifier = Modifier.size(StoryReaderLayout.islandHeight),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.story_reader_menu), tint = contentColor, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { setExpanded(false) },
            offset = DpOffset(0.dp, 4.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(0.75.dp, borderColor),
            modifier = Modifier.width(240.dp),
        ) {
            // 卷三 §4.6（D-13 菜单瘦身）：六条动作行全部迁走——继续写 / 请求结局与推进区重复（删）、
            // 换一版 / 看上一版 / 编辑本章小结去了章末「本章操作」浮层、查看角色现状去了书页·档案。
            // 只留一个导航：书页（storyId 还没解析出来时不显示——空书页无意义）。
            if (onOpenBookHub != null) {
                MenuActionRow(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.story_reader_menu_book_hub), contentColor, accent) {
                    setExpanded(false); onOpenBookHub()
                }
                MenuHairline(divider)
            }
            MenuToggleRow(
                title = stringResource(R.string.story_reader_menu_animations),
                hint = stringResource(R.string.story_reader_menu_animations_hint),
                checked = readingAnimationsEnabled,
                contentColor = contentColor,
                secondaryColor = secondaryColor,
                onToggle = onToggleAnimations,
            )
            MenuHairline(divider)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.story_reader_menu_font_size), fontSize = 11.sp, color = secondaryColor)
                Spacer(Modifier.height(6.dp))
                MenuFontSizeTiers(fontSizeIndex, contentColor, secondaryColor, accent, onSetFontSizeIndex)
            }
        }
    }
}

/**
 * 动作行：前导暖陶图标 + 15sp 标签，44dp 行高（触控最小尺寸）。
 *
 * 卷三 §4.2 起跨文件复用（章末「本章操作」浮层照同款行）——**只改可见性 private→internal，实现一字未动**。
 */
@Composable
internal fun MenuActionRow(icon: ImageVector, label: String, contentColor: Color, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, color = contentColor)
    }
}

/** 开关行（双行式）：标题 + 职责小字；整行点击切换，Switch 只作视觉（P1-20 语义沿用旧实现）。 */
@Composable
private fun MenuToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    contentColor: Color,
    secondaryColor: Color,
    onToggle: (Boolean) -> Unit,
) {
    val switchState = stringResource(if (checked) R.string.a11y_switch_on else R.string.a11y_switch_off)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .semantics {
                role = Role.Switch
                stateDescription = switchState
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = contentColor)
            Text(hint, fontSize = 11.sp, color = secondaryColor)
        }
        // TODO(图纸未覆盖): 阅读器沉浸菜单是「自研 chrome」（§0.5 明文不动），它这枚开关的六个颜色全部
        //  跟着阅读器主题走（深底 + menuSwitchTrack），换成白瓷陶土开关会在深色阅读浮层上突兀。
        //  AppSwitch 自绘后按 §3 删掉了 colors 形参，故本站从「AppSwitch 透传 colors」退回直接用 M3 Switch
        //  ——onCheckedChange 本就是 null（整行 clickable 接管），AppSwitch 在这里原本也只是零逻辑透传，
        //  像素与语义逐字不变。收编与否留复核裁决（施工日志 D-11）。
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedTrackColor = StoryReaderLayout.menuSwitchTrack,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = contentColor.copy(alpha = 0.14f),
                uncheckedThumbColor = contentColor.copy(alpha = 0.55f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** 发丝分隔线（0.5dp·左右 12dp 内缩）。 */
@Composable
private fun MenuHairline(color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .height(0.5.dp)
            .background(color),
    )
}

/** 字号四档（纸面自适应四格·选档不关菜单=正文活预览；单选语义 Role.RadioButton）。 */
@Composable
private fun MenuFontSizeTiers(
    selectedIndex: Int,
    contentColor: Color,
    secondaryColor: Color,
    accent: Color,
    onSelect: (Int) -> Unit,
) {
    val labels = listOf(
        R.string.story_reader_font_size_small,
        R.string.story_reader_font_size_standard,
        R.string.story_reader_font_size_large,
        R.string.story_reader_font_size_xlarge,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(contentColor.copy(alpha = 0.07f))
            .padding(2.dp),
    ) {
        labels.forEachIndexed { index, res ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(index) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(res),
                    fontSize = 12.sp,
                    maxLines = 1,
                    color = if (selected) accent else secondaryColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
