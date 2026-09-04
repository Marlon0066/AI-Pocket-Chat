package com.situ.aichat.ui.worldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 角色 × 世界观绑定（WB7c·契约 §12.5）：主脸 = 单选「选一本世界观」；叠加多本收进阶入口。
 * 绑定 ≤1 本时 sheet 是单选（选新书 = 收敛为这一本，不会静默丢别的——因为没有别的）；
 * 已叠加多本时 sheet 直接进多选逐本增删。characterUuid 取自角色编辑路由的 SavedStateHandle。
 * D8 与世界系统二选一互斥的对向校验已接入（[joinedWorld]/[nativeOrigin] + [isJoinedWorldNow] 守卫·契约 §11-2·W13 复核 R1 🟡-2）。
 */
@HiltViewModel
class WorldBookBindingViewModel @Inject constructor(
    private val repository: WorldBookRepository,
    private val characterDao: CharacterDao,
    private val worldNativeDao: WorldNativeDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle["characterUuid"] ?: ""

    /**
     * 该角色是否已「加入世界」（D8 与世界系统 per-角色二选一互斥·契约 §11-2·W13 图纸 §3.3）。一次读 +
     * [refreshJoinedWorld]（进段重组刷新）——已加入 → 绑定段灰显、点击不开 sheet、副标改 world_locked。
     * 绑定动作与开 sheet 前另经 [isJoinedWorldNow] 直读库守卫（挡同屏陈旧态击穿·复核 R1 🟡-2）。
     */
    private val _joinedWorld = MutableStateFlow(false)
    val joinedWorld: StateFlow<Boolean> = _joinedWorld.asStateFlow()

    /**
     * 该角色是否原住民出身（招募入世·契约 §11·W13 复核 R1 🟡-2③）。原住民不可绑世界书——段副标优先落
     * `wb_binding_native_locked`（比笼统的 world_locked 更准）。init 与 [refreshJoinedWorld] 时刷新。
     */
    private val _nativeOrigin = MutableStateFlow(false)
    val nativeOrigin: StateFlow<Boolean> = _nativeOrigin.asStateFlow()

    init {
        refreshJoinedWorld()
    }

    fun refreshJoinedWorld() {
        viewModelScope.launch {
            _joinedWorld.value = characterDao.getByUuid(characterUuid)?.joinedWorld == true
            _nativeOrigin.value = worldNativeDao.getByRecruitedUuid(characterUuid) != null
        }
    }

    /**
     * 绑定动作 / 开 sheet 前的 fresh 守卫（复核 R1 🟡-2）：直读库当前 joinedWorld（顺手回写 StateFlow 令卡即刻
     * 灰锁），挡住「世界段与世界书段同屏、先开加入世界后 joinedWorld 陈旧 false」时的单向击穿窗。
     */
    suspend fun isJoinedWorldNow(): Boolean {
        val joined = characterDao.getByUuid(characterUuid)?.joinedWorld == true
        _joinedWorld.value = joined
        return joined
    }

    /** 该角色绑定的书（按绑定先后·带条目数）。 */
    val boundBooks: StateFlow<List<WorldBookSummary>> =
        repository.observeBoundBookUuidsForCharacter(characterUuid)
            .combine(repository.observeBookSummaries()) { uuids, all ->
                uuids.mapNotNull { uuid -> all.firstOrNull { it.book.uuid == uuid } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 可选列表 = 非全局书（全局书自动生效不参与选择）。 */
    val selectableBooks: StateFlow<List<WorldBookSummary>> = repository.observeBookSummaries()
        .map { list -> list.filter { !it.book.isGlobal } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 启用中的全局书（sheet 里只作提示行）。 */
    val globalBooks: StateFlow<List<WorldBookEntity>> = repository.observeAllBooks()
        .map { list -> list.filter { it.isGlobal && it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSole(bookUuid: String?) {
        viewModelScope.launch {
            if (isJoinedWorldNow()) return@launch // 陈旧态守卫（复核 R1 🟡-2）：已加入则不落库
            repository.setSoleBinding(characterUuid, bookUuid)
        }
    }

    fun toggle(bookUuid: String, bound: Boolean) {
        viewModelScope.launch {
            if (isJoinedWorldNow()) return@launch // 陈旧态守卫（复核 R1 🟡-2）：已加入则不落库
            if (bound) repository.bind(characterUuid, bookUuid) else repository.unbind(characterUuid, bookUuid)
        }
    }
}

/** 角色编辑页「世界观」段的入口行卡（SectionHeader/Footer 由宿主渲染保持段落风格统一）。 */
@Composable
fun WorldBookBindingSection(
    onManageBooks: () -> Unit,
    viewModel: WorldBookBindingViewModel = hiltViewModel(),
) {
    val boundBooks by viewModel.boundBooks.collectAsStateWithLifecycle()
    val joinedWorld by viewModel.joinedWorld.collectAsStateWithLifecycle()
    val nativeOrigin by viewModel.nativeOrigin.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    // D8 互斥（W13 图纸 §3.3）：进段重组时刷新「是否已加入世界」——他处开关翻转后回本页即时反映。
    LaunchedEffect(Unit) { viewModel.refreshJoinedWorld() }

    Surface(
        shape = AppShapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (joinedWorld) 0.48f else 1f)
            // 点击前直读库守卫（复核 R1 🟡-2）：同屏先开加入世界、joinedWorld 陈旧 false 时也挡住开 sheet。
            .then(if (joinedWorld) Modifier else Modifier.clickableScale { scope.launch { if (!viewModel.isJoinedWorldNow()) showSheet = true } }),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppFeatureIcons.Worldbook, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                when {
                    // 副标优先级（复核 R1 🟡-2③）：原住民出身 → native_locked，其次已加入 → world_locked。
                    nativeOrigin -> Text(
                        stringResource(R.string.wb_binding_native_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text.secondary,
                    )
                    joinedWorld -> Text(
                        stringResource(R.string.wb_binding_world_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text.secondary,
                    )
                    boundBooks.isEmpty() -> Text(
                        stringResource(R.string.wb_binding_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text.secondary,
                    )
                    boundBooks.size == 1 -> {
                        val summary = boundBooks.first()
                        Text(
                            summary.book.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.wb_book_meta_unbound, summary.entryCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text.secondary,
                        )
                    }
                    else -> Text(
                        stringResource(R.string.wb_binding_multi, boundBooks.first().book.name, boundBooks.size - 1),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.text.secondary)
        }
    }

    if (showSheet && !joinedWorld) {
        WorldBookBindingSheet(
            viewModel = viewModel,
            onManageBooks = {
                showSheet = false
                onManageBooks()
            },
            onDismiss = { showSheet = false },
        )
    }
}

/** 选择弹层：单选主脸（≤1 本时）/ 多选（已叠加或点开进阶入口后）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldBookBindingSheet(
    viewModel: WorldBookBindingViewModel,
    onManageBooks: () -> Unit,
    onDismiss: () -> Unit,
) {
    val boundBooks by viewModel.boundBooks.collectAsStateWithLifecycle()
    val selectableBooks by viewModel.selectableBooks.collectAsStateWithLifecycle()
    val globalBooks by viewModel.globalBooks.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    var multiMode by remember { mutableStateOf(false) }
    val effectiveMulti = multiMode || boundBooks.size > 1
    val boundUuids = boundBooks.map { it.book.uuid }.toSet()

    AppSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.wb_binding_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!effectiveMulti) {
                BindingRow(
                    title = stringResource(R.string.wb_binding_not_use),
                    subtitle = null,
                    dimmed = false,
                    selected = boundUuids.isEmpty(),
                    multi = false,
                    onPick = {
                        viewModel.selectSole(null)
                        onDismiss()
                    },
                )
            }
            selectableBooks.forEach { summary ->
                AppListDivider(modifier = Modifier.padding(horizontal = 20.dp), startInset = 0.dp)
                val disabled = !summary.book.enabled
                BindingRow(
                    title = summary.book.name,
                    subtitle = stringResource(R.string.wb_book_meta_unbound, summary.entryCount) +
                        if (disabled) " · ${stringResource(R.string.wb_book_disabled)}" else "",
                    dimmed = disabled,
                    selected = summary.book.uuid in boundUuids,
                    multi = effectiveMulti,
                    onPick = {
                        if (effectiveMulti) {
                            viewModel.toggle(summary.book.uuid, summary.book.uuid !in boundUuids)
                        } else {
                            viewModel.selectSole(summary.book.uuid)
                            onDismiss()
                        }
                    },
                )
            }
            if (globalBooks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(AppShapes.small)
                        .background(colors.surface.sunken)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = colors.text.secondary, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(
                            R.string.wb_binding_global_note,
                            globalBooks.joinToString("、") { "「${it.name}」" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                    )
                }
            }
            if (!effectiveMulti) {
                AppListDivider(modifier = Modifier.padding(horizontal = 20.dp), startInset = 0.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { multiMode = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.wb_binding_stack),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.text.primary,
                        )
                        Text(
                            stringResource(R.string.wb_binding_stack_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text.secondary,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.text.secondary)
                }
            }
            Text(
                stringResource(R.string.wb_binding_manage),
                style = MaterialTheme.typography.titleSmall,
                color = colors.accent.text,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(AppShapes.full)
                    .clickable(onClick = onManageBooks)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** 弹层里的一行书：单选 = RadioButton / 多选 = Checkbox；停用书淡显但可选。 */
@Composable
private fun BindingRow(
    title: String,
    subtitle: String?,
    dimmed: Boolean,
    selected: Boolean,
    multi: Boolean,
    onPick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .alpha(if (dimmed) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = { onPick() })
        } else {
            AppRadio(selected = selected, onClick = onPick)
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.text.secondary)
            }
        }
    }
}
