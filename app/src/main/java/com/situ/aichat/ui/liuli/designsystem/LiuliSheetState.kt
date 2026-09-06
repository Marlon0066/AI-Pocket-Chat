package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** M3 `BottomSheetDefaults` 的两个内部阈值（1.4.0 同值·手势吸附口径不变）。 */
private val SHEET_POSITIONAL_THRESHOLD = 56.dp
private val SHEET_VELOCITY_THRESHOLD = 125.dp

/**
 * 「即现」弹层态（用户 2026-09-06：点「+」后等弹层从底下滑上来「像卡住了」）：初值就是 [SheetValue.Expanded]，
 * `ModalBottomSheet` 首帧即在最终位置、遮罩直接到位，**没有滑入动画**；下拉关闭 / 点遮罩关闭照常动画，
 * 手势阈值与 M3 默认逐值相同。每次弹层重新组合都是一份新状态（`remember` 不跨关闭）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLiuliInstantSheetState(): SheetState {
    val density = LocalDensity.current
    return remember(density) {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { SHEET_POSITIONAL_THRESHOLD.toPx() } },
            velocityThreshold = { with(density) { SHEET_VELOCITY_THRESHOLD.toPx() } },
            initialValue = SheetValue.Expanded,
        )
    }
}
