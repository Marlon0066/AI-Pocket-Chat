package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.character.CharacterWorldUiState
import com.situ.aichat.ui.character.CharacterWorldViewModel
import com.situ.aichat.ui.character.CityUi
import com.situ.aichat.ui.character.worldAddressTailRes
import com.situ.aichat.ui.character.worldFooterRes
import com.situ.aichat.ui.character.worldJoinSubtitleRes
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRadioRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow

/**
 * 世界组外壳（§4.10·编辑态）：照暖陶 `CharacterWorldSection` 自取 [CharacterWorldViewModel]（从路由的 `SavedStateHandle`
 * 取 `characterUuid`·同一路由内两张脸拿到同一实例）；三动作立即落库。
 */
@Composable
internal fun LiuliCharacterWorldGroup(viewModel: CharacterWorldViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliCharacterWorldGroupContent(
        state = state,
        onJoin = viewModel::join,
        onJoinClearingWorldBooks = viewModel::joinClearingWorldBooks,
        onLeave = viewModel::leave,
        onSelectRegion = viewModel::selectRegion,
        onMove = viewModel::move,
    )
}

/** 世界组内容（无状态·可单测）：仅原住民锁死；挂世界书时点开弹切换确认框一键摘书入世 + 住址行 + 离开确认 + 城市弹层。 */
@Composable
internal fun LiuliCharacterWorldGroupContent(
    state: CharacterWorldUiState,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onSelectRegion: (String) -> Unit,
    onMove: (String) -> Unit,
    onJoinClearingWorldBooks: () -> Unit = onJoin,
) {
    val reduceMotion = rememberReduceMotion()
    val wb = state.worldbookBound
    val native = state.nativeOrigin
    val joined = state.joined
    var showLeave by remember { mutableStateOf(false) }
    var showSwitchJoin by remember { mutableStateOf(false) }
    var showCity by remember { mutableStateOf(false) }

    LiuliGroup(
        header = stringResource(R.string.char_world_section),
        footer = stringResource(worldFooterRes(worldbookBound = wb, nativeOrigin = native, joined = joined)),
    ) {
        LiuliToggleRow(
            title = stringResource(R.string.char_world_join_title),
            checked = joined,
            onCheckedChange = { on ->
                when {
                    !on -> showLeave = true
                    wb -> showSwitchJoin = true
                    else -> onJoin()
                }
            },
            subtitle = stringResource(worldJoinSubtitleRes(worldbookBound = wb, nativeOrigin = native, joined = joined)),
            enabled = !native,
            icon = Icons.Filled.Public,
            tileColor = LiuliTileTone.Sky,
            divider = false,
        )
        AnimatedVisibility(
            visible = joined,
            enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            val address = "${state.homeCityName} · ${stringResource(worldAddressTailRes(nativeOrigin = native, sameCityAsUser = state.sameCityAsUser))}"
            if (native) {
                LiuliValueRow(
                    title = stringResource(R.string.char_world_addr_title),
                    value = "",
                    subtitle = address,
                    icon = Icons.Filled.Home,
                    tileColor = LiuliTileTone.Mint,
                    enabled = false,
                )
            } else {
                LiuliNavRow(
                    title = stringResource(R.string.char_world_addr_title),
                    onClick = { showCity = true },
                    icon = Icons.Filled.Home,
                    tileColor = LiuliTileTone.Mint,
                    subtitle = address,
                )
            }
        }
    }

    if (showLeave) {
        LiuliDialog(
            onDismissRequest = { showLeave = false },
            title = stringResource(R.string.char_world_leave_title),
            body = stringResource(R.string.char_world_leave_body),
            confirmText = stringResource(R.string.char_world_leave_confirm),
            onConfirm = {
                onLeave()
                showLeave = false
            },
            dismissText = stringResource(R.string.char_world_leave_cancel),
            onDismiss = { showLeave = false },
        )
    }
    if (showSwitchJoin) {
        LiuliDialog(
            onDismissRequest = { showSwitchJoin = false },
            title = stringResource(R.string.char_world_switch_join_title),
            body = stringResource(R.string.char_world_switch_join_body),
            confirmText = stringResource(R.string.char_world_switch_join_confirm),
            onConfirm = {
                onJoinClearingWorldBooks()
                showSwitchJoin = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showSwitchJoin = false },
        )
    }
    if (showCity) {
        LiuliWorldCitySheet(
            state = state,
            onSelectRegion = onSelectRegion,
            onMove = { id ->
                onMove(id)
                showCity = false
            },
            onDismiss = { showCity = false },
        )
    }
}

/** 城市弹层（§4.10-4）：大区胶囊横排 + 城市单选行；点非当前城 → 搬家确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiuliWorldCitySheet(
    state: CharacterWorldUiState,
    onSelectRegion: (String) -> Unit,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingMove by remember { mutableStateOf<CityUi?>(null) }
    LiuliSheetShell(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.char_world_city_sheet_title),
        subtitle = stringResource(R.string.char_world_city_sheet_sub),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LiuliPageGeometry.gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.regions.forEach { region ->
                LiuliChip(selected = region.id == state.selectedRegionId, onClick = { onSelectRegion(region.id) }, label = region.name)
            }
        }
        LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
            state.citiesOfRegion.forEachIndexed { i, city ->
                LiuliRadioRow(
                    title = city.name,
                    selected = city.isCurrentAddress,
                    onSelect = { if (!city.isCurrentAddress) pendingMove = city },
                    subtitle = if (city.isUserHome) stringResource(R.string.char_world_city_tag_home) else null,
                    divider = i > 0,
                )
            }
        }
    }
    pendingMove?.let { target ->
        LiuliDialog(
            onDismissRequest = { pendingMove = null },
            title = stringResource(R.string.char_world_move_title, target.name),
            body = stringResource(R.string.char_world_move_body, state.homeCityName, target.name),
            confirmText = stringResource(R.string.char_world_move_confirm),
            onConfirm = {
                pendingMove = null
                onMove(target.id)
            },
            dismissText = stringResource(R.string.char_world_move_cancel),
            onDismiss = { pendingMove = null },
        )
    }
}

/** 新建版世界组（§4.10）：仅「加入世界」开关（暂存进 `CharacterEditState.joinWorld`，保存时应用）+ create 脚注。 */
@Composable
internal fun LiuliCharacterWorldCreateGroup(joined: Boolean, onToggle: (Boolean) -> Unit) {
    LiuliGroup(header = stringResource(R.string.char_world_section), footer = stringResource(R.string.char_world_foot_create)) {
        LiuliToggleRow(
            title = stringResource(R.string.char_world_join_title),
            checked = joined,
            onCheckedChange = onToggle,
            subtitle = stringResource(if (joined) R.string.char_world_join_sub_on else R.string.char_world_join_sub_off),
            icon = Icons.Filled.Public,
            tileColor = LiuliTileTone.Sky,
            divider = false,
        )
    }
}
