package com.situ.aichat.ui.character

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.world.WorldSceneColors

/**
 * 编辑页「世界」段（W13 图纸 §4.1/§4.2）。编辑模式 = 开关行 + 住址行（AnimatedVisibility）+ 四态脚注 +
 * 离开确认 + 城市选择 sheet + 搬家确认；新建模式（[CharacterWorldCreateSection]）= 仅开关行 + create 脚注。
 * 卡行风格与 [com.situ.aichat.ui.worldbook.WorldBookBindingSection] 逐值同族。
 */
@Composable
fun CharacterWorldSection(viewModel: CharacterWorldViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()

    val wb = state.worldbookBound
    val native = state.nativeOrigin
    val joined = state.joined
    val switchEnabled = !wb && !native
    val rowDim = if (wb || native) 0.48f else 1f

    var showLeaveDialog by remember { mutableStateOf(false) }
    var showCitySheet by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.char_world_section))

    Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            // 开关行。
            Row(
                modifier = Modifier.fillMaxWidth().alpha(rowDim).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorldIconBox(Icons.Filled.Public)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.char_world_join_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text.primary,
                    )
                    Text(
                        stringResource(
                            when {
                                wb -> R.string.char_world_join_sub_wb
                                native -> R.string.char_world_join_sub_native
                                joined -> R.string.char_world_join_sub_on
                                else -> R.string.char_world_join_sub_off
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                    )
                }
                AppSwitch(
                    checked = joined,
                    enabled = switchEnabled,
                    onCheckedChange = { on ->
                        if (on) viewModel.join() else showLeaveDialog = true
                    },
                )
            }

            // 住址行（已加入才显示·原住民态灰显不可点）。
            AnimatedVisibility(
                visible = joined,
                enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
            ) {
                Column {
                    AppListDivider(startInset = 0.dp)
                    val addrTail = when {
                        native -> stringResource(R.string.char_world_addr_native)
                        state.sameCityAsUser -> stringResource(R.string.char_world_addr_same_city)
                        else -> stringResource(R.string.char_world_addr_remote)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (native) 0.48f else 1f)
                            .then(if (native) Modifier else Modifier.clickable { showCitySheet = true })
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        WorldIconBox(Icons.Filled.Home)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.char_world_addr_title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text.primary,
                            )
                            Text(
                                "${state.homeCityName} · $addrTail",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.text.secondary,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.text.secondary,
                        )
                    }
                }
            }
        }
    }

    SectionFooter(
        stringResource(
            when {
                wb -> R.string.char_world_foot_wb
                native -> R.string.char_world_foot_native
                joined -> R.string.char_world_foot_on
                else -> R.string.char_world_foot_off
            },
        ),
    )

    if (showLeaveDialog) {
        WorldLeaveDialog(
            onConfirm = {
                viewModel.leave()
                showLeaveDialog = false
            },
            onDismiss = { showLeaveDialog = false },
        )
    }

    if (showCitySheet) {
        WorldCityPickerSheet(
            state = state,
            onSelectRegion = viewModel::selectRegion,
            onConfirmMove = { cityId ->
                viewModel.move(cityId)
                showCitySheet = false
            },
            onDismiss = { showCitySheet = false },
        )
    }
}

/** 新建模式的世界段：仅「加入世界」开关（暂存进 [CharacterEditState.joinWorld]，save() 尾应用）+ create 脚注。 */
@Composable
fun CharacterWorldCreateSection(joined: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    SectionHeader(stringResource(R.string.char_world_section))
    Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorldIconBox(Icons.Filled.Public)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.char_world_join_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text.primary,
                )
                Text(
                    stringResource(if (joined) R.string.char_world_join_sub_on else R.string.char_world_join_sub_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                )
            }
            AppSwitch(checked = joined, onCheckedChange = onToggle)
        }
    }
    SectionFooter(stringResource(R.string.char_world_foot_create))
}

/** 40dp 圆角图标盒（accent.container 底 + 22dp accent.text 图标·与世界观段逐值同族）。 */
@Composable
private fun WorldIconBox(icon: ImageVector) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
    }
}

/** 离开确认弹窗（图纸 §4.1·确认钮 AppButton 主样式）。 */
@Composable
private fun WorldLeaveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.char_world_leave_title),
        body = stringResource(R.string.char_world_leave_body),
        confirmText = stringResource(R.string.char_world_leave_confirm),
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.char_world_leave_cancel),
        onDismiss = onDismiss,
    )
}

/** 城市选择 sheet（图纸 §4.2）：大区 chips + 城市行；点非当前城 → 搬家确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldCityPickerSheet(
    state: CharacterWorldUiState,
    onSelectRegion: (String) -> Unit,
    onConfirmMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    var pendingMove by remember { mutableStateOf<CityUi?>(null) }

    AppSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.char_world_city_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.char_world_city_sheet_sub),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text.secondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            // 大区 chips。
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.regions.forEach { region ->
                    val selected = region.id == state.selectedRegionId
                    Text(
                        region.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = if (selected) colors.accent.text else colors.text.secondary,
                        modifier = Modifier
                            .clip(AppShapes.full)
                            .background(if (selected) colors.accent.container else colors.surface.sunken)
                            .clickable { onSelectRegion(region.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            // 城市行。
            state.citiesOfRegion.forEach { city ->
                CityRow(
                    city = city,
                    onClick = { if (!city.isCurrentAddress) pendingMove = city },
                )
            }
        }
    }

    pendingMove?.let { target ->
        AppDialog(
            onDismissRequest = { pendingMove = null },
            title = stringResource(R.string.char_world_move_title, target.name),
            body = stringResource(R.string.char_world_move_body, state.homeCityName, target.name),
            confirmText = stringResource(R.string.char_world_move_confirm),
            onConfirm = {
                pendingMove = null
                onConfirmMove(target.id)
            },
            dismissText = stringResource(R.string.char_world_move_cancel),
            onDismiss = { pendingMove = null },
        )
    }
}

/** 城市行：7dp 圆点（家乡城金点）+ 城名 + 「当前」标记 + 家乡「和你同城 · 默认」金底 tag。 */
@Composable
private fun CityRow(city: CityUi, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (city.isUserHome) WorldSceneColors.gold else colors.surface.stroke),
        )
        Text(
            city.name,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.primary,
            modifier = Modifier.weight(1f),
        )
        if (city.isUserHome) {
            Text(
                stringResource(R.string.char_world_city_tag_home),
                fontSize = 10.5.sp,
                color = colors.accent.text,
                modifier = Modifier
                    .clip(AppShapes.full)
                    .background(colors.accent.container)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (city.isCurrentAddress) {
            Text(
                stringResource(R.string.char_world_city_current),
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent.text,
            )
        }
    }
}
