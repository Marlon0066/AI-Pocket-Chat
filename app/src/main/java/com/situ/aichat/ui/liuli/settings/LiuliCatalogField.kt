package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.situ.aichat.ui.liuli.page.LiuliInputRow

/** 拉取钮的视觉直径（与步进钮同档 28）。 */
private val FETCH_BUTTON = LiuliPageGeometry.stepperButton
/** 钮里的图标 / 转圈尺寸。 */
private val FETCH_ICON = 16.dp
private val FETCH_SPINNER = 12.dp
/** 候选菜单贴着行右缘（内缩 16 = 组内距）落在行正下方；菜单宽 280（条目是长串）。 */
private val MENU_INSET_END = (-16).dp
private val CATALOG_MENU_WIDTH = 280.dp

/** 候选列表默认上限（图纸 2026-09-06 卷五 A-4 ②′ `listCap = 100`）。 */
const val LIULI_CATALOG_LIST_CAP = 100

/**
 * 候选过滤（图纸 2026-09-06 卷五 A-4 ②′·**语义逐字照暖陶 `ModelDropdownField`**·`ApiConfigScreen.kt:552–567`）：
 *
 * 1. 空查询、或查询恰好等于某个候选的 id（= 已选中）→ 全量候选；
 * 2. 否则按 id **或**显示名 `contains`（忽略大小写）筛——**不是前缀匹配**：中转站的 id 常带
 *    `openai/` 之类前缀，前缀匹配会让用户敲 `gpt` 一条也搜不到（图纸 A-4 ②′ 写的「按输入前缀筛选」
 *    与同句的「筛选纯函数复用暖陶」互相冲突，取后者·见图纸 §11 D-6）；
 * 3. 已选中项置顶（长列表里它可能排在很后面，滚不到 = 看不见选中态）；
 * 4. 末了截到 [listCap] 条。置顶排在截断**之前**，故选中项永远不会被截掉。
 *
 * [items] = `(id, 显示名)`；显示名为 null 时用 id 当显示名。
 */
internal fun filterCatalogItems(
    query: String,
    items: List<Pair<String, String?>>,
    listCap: Int = LIULI_CATALOG_LIST_CAP,
): List<Pair<String, String?>> {
    val q = query.trim()
    val isExactSelection = items.any { it.first.equals(q, ignoreCase = true) }
    val filtered = if (q.isEmpty() || isExactSelection) {
        items
    } else {
        items.filter { (id, name) ->
            id.contains(q, ignoreCase = true) || (name ?: id).contains(q, ignoreCase = true)
        }
    }
    val ordered = if (isExactSelection) {
        filtered.sortedByDescending { it.first.equals(q, ignoreCase = true) }
    } else {
        filtered
    }
    return ordered.take(listCap)
}

/**
 * 可输入下拉（图纸 2026-09-06 卷五 A-4 ②′·暖陶 `ModelDropdownField` 的琉璃对应件）。
 *
 * **长相 = 一条无框输入行**（[LiuliInputRow]：标签 96 左 + 无框输入 + 尾随一枚 28 拉取圆钮），与同组的
 * Base URL / API Key 行同一副基线——卷五施工版套的是带框 [LiuliField] + 标签在上，一组里两种输入长相、
 * 拉取钮还比输入格低半截（复核 R1 C3 / C4·用户点名「按钮文案没对齐」）。
 *
 * 候选菜单挂在行下（[LiuliPopupMenu]·**不抢焦点**——抢了键盘落到菜单上、打字框立刻失焦收起）；
 * 得焦且从没拉过（无候选、非加载中）→ 自动拉一次（暖陶 `:576–579` / TTS `:341–344` 同时序·复核 R1 A1）；
 * 候选到达且仍在焦点里 → 自动展开（复核 R1 D2）。[error] 走红字；[fetchedEmptyHint] = 拉到 0 条时的
 * 中性提示（`api_models_empty_hint`·复核 R1 A2）。
 *
 * **禁 M3 `ExposedDropdownMenuBox` / `DropdownMenu`**（§9 ⑤）。状态机由 VM 给，本件只负责长相与筛选；
 * [onFetch] 是**唯一**的拉取入口。
 */
@Composable
fun LiuliCatalogField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    items: List<Pair<String, String?>>,
    loading: Boolean,
    error: String?,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier,
    listCap: Int = LIULI_CATALOG_LIST_CAP,
    emptyHint: String? = null,
    /** 拉取成功但一条都没有时显示的中性提示（不走红）；null = 不提示。 */
    fetchedEmptyHint: String? = null,
    divider: Boolean = true,
    /** 值为空时的占位（系统音色行的「默认」）；null = 不占位。 */
    placeholder: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val shown = filterCatalogItems(value, items, listCap)
    val entries = when {
        shown.isNotEmpty() -> shown.map { (id, name) ->
            LiuliMenuEntry(
                text = name ?: id,
                selected = id == value,
                onClick = { onValueChange(id) },
            )
        }
        items.isNotEmpty() && emptyHint != null -> listOf(LiuliMenuEntry(text = emptyHint, onClick = {}))
        else -> emptyList()
    }
    // 得焦即自动拉一次（只在从没拉过时·按焦点事件触发一次，不会因为拉到 0 条而循环）。
    LaunchedEffect(focused) {
        if (focused && items.isEmpty() && !loading) onFetch()
    }
    // 候选晚于焦点到达 → 自动展开。
    LaunchedEffect(items.size) {
        if (focused && items.isNotEmpty()) expanded = true
    }
    val supporting = error ?: fetchedEmptyHint?.takeIf { items.isEmpty() && !loading }

    Box(
        modifier
            .onSizeChanged { rowHeightPx = it.height }
            .onFocusChanged {
                focused = it.hasFocus
                expanded = it.hasFocus && entries.isNotEmpty()
            },
    ) {
        LiuliInputRow(
            label = label,
            value = value,
            placeholder = placeholder,
            onValueChange = { next ->
                onValueChange(next)
                expanded = true
            },
            supportingText = supporting,
            supportingIsError = error != null,
            divider = divider,
            trailing = {
                // 版位恰 28（外层盒），48 触达框由圆钮自带的 minimumInteractiveComponentSize 居中外溢。
                Box(Modifier.size(FETCH_BUTTON), contentAlignment = Alignment.Center) {
                    LiuliCircleButton(
                        onClick = onFetch,
                        // 不能拿 label 当 cd：输入行已把 label 挂成输入框的 contentDescription，同屏两个同名可点节点
                        // 读屏念不清、测试也定位不到。复用暖陶菜单末行那句「拉取模型列表」（零新增键）。
                        contentDescription = stringResource(R.string.api_fetch_models),
                        size = FETCH_BUTTON,
                        enabled = !loading,
                    ) {
                        if (loading) {
                            LiuliSpinner(size = FETCH_SPINNER)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(FETCH_ICON))
                        }
                    }
                }
            },
        )
        LiuliPopupMenu(
            expanded = expanded && entries.isNotEmpty(),
            onDismiss = { expanded = false },
            items = entries,
            // 贴着行右缘、落在行正下方（行高实测）；候选是「服务商 模型名」长串，给 280 宽。
            offset = DpOffset(MENU_INSET_END, with(density) { rowHeightPx.toDp() }),
            width = CATALOG_MENU_WIDTH,
            focusable = false,
        )
    }
}
