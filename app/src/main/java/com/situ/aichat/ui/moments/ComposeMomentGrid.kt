package com.situ.aichat.ui.moments

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.floor

// 发布页三列图片格（朋友圈发布页·乙 §4.6）：小叉移除、「+」格加图、点开看大图、按住拖动换位（跟手不做动画·其余格弹簧让位）、
// 无障碍前后移。换位 / 让位只用 Compose 原语（零第三方）。

private const val COLUMNS = 3
/** 列 / 行间距（锁定）。 */
internal val GRID_GAP = 8.dp
/** 拖起的格放大 / 倾斜（锁定·reduceMotion 下不放大不歪）。 */
private const val DRAG_SCALE = 1.08f
private const val DRAG_TILT = -2f
private val DRAG_SHADOW = 12.dp
/** 小叉：视觉 20dp 圆、距格右上 4dp、48dp 触达。 */
private val REMOVE_VISUAL = 20.dp
private val REMOVE_INSET = 4.dp
private val TOUCH_TARGET = 48.dp

/** 指针落在哪一格：列 = floor(x / (cell + gap)) 钳 0..2，行 = floor(y / (cell + gap)) 钳 ≥ 0，序号 = 行 × 3 + 列，再钳 0..count−1。 */
internal fun composeGridTargetIndex(point: Offset, cellPx: Float, gapPx: Float, count: Int): Int {
    if (count <= 0) return 0
    val pitch = cellPx + gapPx
    val col = floor(point.x / pitch).toInt().coerceIn(0, COLUMNS - 1)
    val row = floor(point.y / pitch).toInt().coerceAtLeast(0)
    return (row * COLUMNS + col).coerceIn(0, count - 1)
}

/** 拖动中非被拖格 j 的显示序号：from < to 时 j ∈ (from, to] 前移一位；from > to 时 j ∈ [to, from) 后移一位；其余不变。 */
internal fun composeGridVisualIndex(j: Int, from: Int, to: Int): Int = when {
    from < to && j in (from + 1)..to -> j - 1
    from > to && j in to until from -> j + 1
    else -> j
}

private fun gridCellOffset(index: Int, cellPx: Float, gapPx: Float): IntOffset {
    val pitch = cellPx + gapPx
    return Offset((index % COLUMNS) * pitch, (index / COLUMNS) * pitch).round()
}

/**
 * 拖动态：按路径认被拖的格，序号以**起拖时的列表快照**为准——VM 换位落地后那一帧与落地前算出的显示位置一致，
 * 松手后等新列表到了（[ComposeMomentGrid] 的 `LaunchedEffect(images)`）才清掉，避免一帧回跳。
 */
@Stable
private class ComposeGridDrag {
    var path by mutableStateOf<String?>(null)
    var snapshot by mutableStateOf<List<String>>(emptyList())
    var from by mutableIntStateOf(-1)
    var to by mutableIntStateOf(-1)
    var offset by mutableStateOf(Offset.Zero)
    /** 手指还按着（被拖格跟手）；松手后为 false、格子从落点弹回新格位。 */
    var following by mutableStateOf(false)
    val active: Boolean get() = path != null

    /** 网格在根坐标里的纵向位置（随布局更新）与起拖那一刻的值。 */
    private var gridY = 0f
    private var gridYAtStart = 0f
    /**
     * 起拖后网格自己被推动的量：拖动提示行一出现就把整块网格往下推一行（§4.5 版式·§11 O-1）。从跟手位移里扣掉，
     * 被拖格才一直留在手指下（复核 R1）。
     */
    private var gridShift by mutableFloatStateOf(0f)
    /** 被拖格相对起点格的位移（网格坐标）= 手指累计位移 − 网格自身位移。 */
    val follow: Offset get() = Offset(offset.x, offset.y - gridShift)

    fun onGridPlaced(y: Float) {
        gridY = y
        if (following) gridShift = y - gridYAtStart
    }

    fun markStart() {
        gridYAtStart = gridY
        gridShift = 0f
    }

    fun reset() {
        path = null
        snapshot = emptyList()
        from = -1
        to = -1
        offset = Offset.Zero
        following = false
        gridShift = 0f
    }
}

@Composable
internal fun ComposeMomentGrid(
    images: List<String>,
    corner: Dp,
    rim: Modifier,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onAdd: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag = remember { ComposeGridDrag() }
    // 换位落地（VM 发来新顺序）→ 收尾；拖动中不动。
    LaunchedEffect(images) { if (drag.active && !drag.following) drag.reset() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = (maxWidth - GRID_GAP * (COLUMNS - 1)) / COLUMNS
        val density = LocalDensity.current
        val cellPx = with(density) { cell.toPx() }
        val gapPx = with(density) { GRID_GAP.toPx() }
        val canAdd = composeMomentCanAddImage(images)
        val slots = images.size + if (canAdd) 1 else 0
        val rows = (slots + COLUMNS - 1) / COLUMNS
        Box(
            Modifier
                .fillMaxWidth()
                .height(cell * rows + GRID_GAP * (rows - 1).coerceAtLeast(0))
                .onGloballyPositioned { drag.onGridPlaced(it.positionInRoot().y) },
        ) {
            images.forEach { path ->
                key(path) {
                    ComposeGridTile(path, images, drag, cell, cellPx, gapPx, corner, rim, onOpen, onRemove, onMove, onDraggingChange)
                }
            }
            if (canAdd) ComposeGridAddTile(images.size, cell, cellPx, gapPx, corner, onAdd)
        }
    }
}

@Composable
private fun ComposeGridTile(
    path: String,
    images: List<String>,
    drag: ComposeGridDrag,
    cell: Dp,
    cellPx: Float,
    gapPx: Float,
    corner: Dp,
    rim: Modifier,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalAppHaptics.current
    val scope = rememberCoroutineScope()
    val order = if (drag.active) drag.snapshot else images
    val j = order.indexOf(path).takeIf { it >= 0 } ?: images.indexOf(path)
    val visual = when {
        !drag.active -> j
        path == drag.path -> drag.to
        else -> composeGridVisualIndex(j, drag.from, drag.to)
    }
    val target = gridCellOffset(visual, cellPx, gapPx)
    val anim = remember { Animatable(target, IntOffset.VectorConverter) }
    val following = drag.following && path == drag.path
    LaunchedEffect(target, following) {
        if (following) return@LaunchedEffect
        if (reduceMotion) anim.snapTo(target) else anim.animateTo(target, AppMotion.gentleSpring(IntOffset.VisibilityThreshold))
    }

    val currentImages by rememberUpdatedState(images)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragging by rememberUpdatedState(onDraggingChange)
    val tileShape = RoundedCornerShape(corner)
    val index = images.indexOf(path)
    val last = images.lastIndex
    val itemLabel = stringResource(R.string.moment_compose_photo_item, index + 1)
    val openLabel = stringResource(R.string.moment_compose_photo_open)
    val earlierLabel = stringResource(R.string.moment_compose_photo_earlier)
    val laterLabel = stringResource(R.string.moment_compose_photo_later)

    Box(
        Modifier
            .offset { if (following) gridCellOffset(drag.from, cellPx, gapPx) + drag.follow.round() else anim.value }
            .size(cell)
            .zIndex(if (path == drag.path) 1f else 0f)
            .semantics {
                contentDescription = itemLabel
                customActions = listOfNotNull(
                    if (index > 0) CustomAccessibilityAction(earlierLabel) { onMove(index, index - 1); true } else null,
                    if (index in 0 until last) CustomAccessibilityAction(laterLabel) { onMove(index, index + 1); true } else null,
                )
            }
            .clickable(onClickLabel = openLabel) { onOpen(path) }
            // 排在 clickable 之内：长按拖动松手时本层先消费抬起，外层 clickable 不再误触「看大图」。
            // 排在放大 / 倾斜层之外：手势坐标不被 1.08 倍放大与 −2° 倾斜换算，被拖格一比一跟手（复核 R1）。
            .pointerInput(path, cellPx, gapPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        val list = currentImages
                        drag.path = path
                        drag.snapshot = list
                        drag.from = list.indexOf(path)
                        drag.to = drag.from
                        drag.offset = Offset.Zero
                        drag.markStart()
                        drag.following = true
                        haptics.selection()
                        currentOnDragging(true)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.offset += amount
                        val start = gridCellOffset(drag.from, cellPx, gapPx)
                        val center = Offset(start.x + cellPx / 2f, start.y + cellPx / 2f) + drag.follow
                        drag.to = composeGridTargetIndex(center, cellPx, gapPx, drag.snapshot.size)
                    },
                    onDragEnd = {
                        val from = drag.from
                        val to = drag.to
                        val drop = gridCellOffset(from, cellPx, gapPx) + drag.follow.round()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { anim.snapTo(drop) }
                        drag.following = false
                        haptics.light()
                        currentOnDragging(false)
                        if (to != from) currentOnMove(from, to) else drag.reset()
                    },
                    onDragCancel = {
                        val drop = gridCellOffset(drag.from, cellPx, gapPx) + drag.follow.round()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { anim.snapTo(drop) }
                        drag.reset()
                        currentOnDragging(false)
                    },
                )
            }
            .graphicsLayer {
                if (following) {
                    scaleX = if (reduceMotion) 1f else DRAG_SCALE
                    scaleY = if (reduceMotion) 1f else DRAG_SCALE
                    rotationZ = if (reduceMotion) 0f else DRAG_TILT
                    shadowElevation = DRAG_SHADOW.toPx()
                    shape = tileShape
                    clip = true
                }
            },
    ) {
        MomentImage(path = path, contentDescription = "", modifier = Modifier.matchParentSize(), corner = corner)
        Box(Modifier.matchParentSize().then(rim))
        ComposeGridRemoveButton(onClick = { onRemove(path) }, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** 图格右上角小叉：视觉 20dp 圆（黑 45%）+ 12dp 白叉，距格右上 4dp；触达 48dp 以视觉为中心外溢（脚印 = 视觉·不挤版）。 */
@Composable
private fun ComposeGridRemoveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.moment_compose_remove_image)
    Box(
        modifier
            .padding(REMOVE_INSET)
            .size(REMOVE_VISUAL)
            .requiredSize(TOUCH_TARGET)
            .clickable(onClickLabel = cd, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(REMOVE_VISUAL).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppActionIcons.Close, contentDescription = cd, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

/** 「+」格（排在最后·不可拖）：虚线圆角框 + 竖排「+」与「n/9」。 */
@Composable
private fun ComposeGridAddTile(index: Int, cell: Dp, cellPx: Float, gapPx: Float, corner: Dp, onAdd: () -> Unit) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val target = gridCellOffset(index, cellPx, gapPx)
    val anim = remember { Animatable(target, IntOffset.VectorConverter) }
    LaunchedEffect(target) {
        if (reduceMotion) anim.snapTo(target) else anim.animateTo(target, AppMotion.gentleSpring(IntOffset.VisibilityThreshold))
    }
    val dash = colors.accent.text.copy(alpha = 0.4f)
    val cd = stringResource(R.string.moment_compose_add_image)
    Box(
        Modifier
            .offset { anim.value }
            .size(cell)
            .clip(RoundedCornerShape(corner))
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(corner.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clickable(onClickLabel = cd, role = Role.Button, onClick = onAdd)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(20.dp))
            Text("$index/${ComposeMomentViewModel.MAX_IMAGES}", style = AppTypography.captionNumeric, color = colors.accent.text)
        }
    }
}
