package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRadioRow
import com.situ.aichat.ui.settings.WORLD_TIMEZONES
import com.situ.aichat.ui.settings.gmtOffsetShort
import java.time.ZoneId

/** 弹层内的说明句到清单的缝与底部留白（逐字照暖陶 `WorldTimezoneSheet` 的 4 / 24）。 */
private val HEAD_GAP = 4.dp
private val SHEET_BOTTOM = 24.dp

/**
 * 时区选择弹层（琉璃·图纸 2026-09-06 卷五 §4.1 屏 18）。清单内容 = 暖陶 `WorldTimezoneSheet` 逐字：
 * 「跟随设备」+ [WORLD_TIMEZONES] 八常用区（**顺序锁死**）+「已钉且不在表内」那一行；
 * 每行的 `· GMT±N` 由暖陶 [gmtOffsetShort] 现算（§2.2-2 已提 internal·非法串安全空）。
 *
 * 壳换 [LiuliSheetShell]（T5 气氛壳口径：弹层换壳、内容零改）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliWorldTimezoneSheet(
    currentZoneId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    LiuliSheetShell(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = SHEET_BOTTOM),
            verticalArrangement = Arrangement.spacedBy(HEAD_GAP),
        ) {
            Text(
                stringResource(R.string.world_tz_sheet_title),
                style = AppTypography.titleSmall,
                color = colors.text.primary,
                modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
            )
            Text(
                stringResource(R.string.world_tz_sheet_sub),
                style = AppTypography.secondary,
                color = colors.text.secondary,
                modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
            )
            LiuliGroup(modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                // 跟随设备。
                LiuliRadioRow(
                    title = stringResource(R.string.world_tz_follow_device) + GMT_PREFIX +
                        gmtOffsetShort(ZoneId.systemDefault().id),
                    selected = currentZoneId == null,
                    onSelect = { onPick(null) },
                    notifyWhenSelected = true, // 点当前项也关面板（暖陶 TimezoneRow 是裸 clickable·复核 R1 A-2）
                    divider = false,
                )
                // 8 常用区（顺序锁死）。
                WORLD_TIMEZONES.forEach { opt ->
                    LiuliRadioRow(
                        title = stringResource(opt.nameRes) + GMT_PREFIX + gmtOffsetShort(opt.zoneId),
                        selected = currentZoneId == opt.zoneId,
                        onSelect = { onPick(opt.zoneId) },
                        notifyWhenSelected = true,
                    )
                }
                // 已钉且不在表内 → 展示当前钉值。
                if (currentZoneId != null && WORLD_TIMEZONES.none { it.zoneId == currentZoneId }) {
                    LiuliRadioRow(
                        title = stringResource(R.string.char_world_city_current) + " · " + currentZoneId,
                        selected = true,
                        onSelect = { onPick(currentZoneId) },
                        notifyWhenSelected = true,
                    )
                }
            }
        }
    }
}

/** 「· GMT」拼接前缀（逐字照暖陶——三处拼接都用它，改一处必须改三处）。 */
private const val GMT_PREFIX = " · GMT"
