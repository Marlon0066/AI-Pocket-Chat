package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.character.ProfileTab
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmentedStyle

/** 纸面态分段条的外距：左右 20 · 上 0 下 16（A-11）。 */
private val PAPER_BOTTOM = 16.dp

/**
 * 详情页三段分段条（契约 §6.5「分段 sticky」· A-11 · Q-S5 甲）。
 *
 * 两态同一枚 [LiuliSegmented]：[glass] = false 是列表里的纸面态（36 高 · 轨 `surface.sunken`），
 * true 是收起后住进玻璃顶栏 `subBar` 槽的那一枚（40 高 · 选中片走 Button 档玻璃）。
 * 段序 = [ProfileTab.entries]（锁定·默认落「近况」），切段 = 节显隐（同暖陶 D-A），不是滚动锚点。
 */
@Composable
fun LiuliTabStrip(
    selected: ProfileTab,
    onSelect: (ProfileTab) -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
) {
    LiuliSegmented(
        options = ProfileTab.entries,
        selected = selected,
        label = { stringResource(it.labelRes) },
        onSelect = onSelect,
        modifier = if (glass) {
            modifier
        } else {
            modifier.padding(start = LiuliPageGeometry.gutter, end = LiuliPageGeometry.gutter, bottom = PAPER_BOTTOM)
        },
        style = if (glass) LiuliSegmentedStyle.Glass else LiuliSegmentedStyle.Paper,
        role = Role.Tab,
    )
}
