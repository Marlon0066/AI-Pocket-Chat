package com.situ.aichat.ui.gift

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 心意值粉（1:1 iOS `Color.affinityPink = Color(hex: 0xE06A80)`）。 */
private val AffinityPink = Color(0xFFE06A80)

/**
 * 礼物店送礼后的反应页（9.2d d-2，1:1 iOS `GiftReactionView`）。
 *
 * 上区：200dp 大图 + 「你送出了 / 名称 / 给 X」；反应气泡：头像 + 名 + mood emoji + 文字（loading 态转圈 + 每 3s
 * 切等待文案）；心意徽章：heart + 拟人 senseText（**不显数字**）+ 手作灰色副标签。
 *
 * @param isSendFlow true=送礼流程（隐返回，「完成」按钮 loading 时禁用，点击 [onFinish] 回礼物店）；
 *                   false=收礼盒回放（标准返回 [onBack]，无完成按钮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftReactionScreen(
    isSendFlow: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    viewModel: GiftReactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loading = state.phase == GiftReactionViewModel.Phase.LOADING
    val revealed = state.phase == GiftReactionViewModel.Phase.REVEALED
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalAppHaptics.current
    // P1-11：揭晓那一刻柔触觉（=iOS GiftReactionView.swift:68 sensoryFeedback(.impact(.soft, 0.6),
    // trigger: token)，token 在 :251/:265 phase=.revealed 后 +1）——含收礼盒回放路径；ERROR 不触发；
    // 触发点是相变非「完成」按钮；reduceMotion 下触觉仍放（iOS 不门控 + 项目惯例「只放触感跳视觉」）。
    // 批5 复核 #5 修：前值守卫（惯例=MomentPostCard prevLiked/ChatScreen wasTyping 同构）——配置重建
    // （转屏/自动深色/分屏）时 VM 存活、首组合即 revealed=true，裸放会无视觉对应地空震一次（iOS
    // sensoryFeedback 仅 token 变更触发无此重放）；新进屏/进程死恢复仍走 LOADING→REVEALED 相变照放。
    var prevRevealed by remember { mutableStateOf(revealed) }
    LaunchedEffect(revealed) {
        if (revealed && !prevRevealed) haptics.soft()
        prevRevealed = revealed
    }

    // loading 期禁止返回（1:1 iOS interactiveDismissDisabled，防扣钱后反应未写回就退出；配 15s 超时兜底）
    BackHandler(enabled = isSendFlow && loading) { /* 吞掉返回 */ }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = if (isSendFlow) "送出" else "礼物详情",
                // 送出流程里原本就不渲染返回钮（原 navigationIcon 外包一层 if）——onBack = null 与之等价。
                onBack = onBack.takeIf { !isSendFlow },
                lifted = scrollState.value > 0,
                actions = {
                    if (isSendFlow) {
                        AppButton(onClick = onFinish, style = AppButtonStyle.Text, enabled = !loading) { Text("完成") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            GiftHeader(state, revealed, reduceMotion)
            ReactionBubble(state, loading, reduceMotion)
            AffinityBadge(state, reduceMotion)
        }
    }
}

@Composable
private fun GiftHeader(state: GiftReactionViewModel.UiState, revealed: Boolean, reduceMotion: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // P1-11：揭晓时大图 scale 0.92→1.0 + alpha 0.85→1.0（=iOS GiftReactionView.swift:82-84，修饰符
        // 只贴 200pt 礼物大图、下方文字块不动）；弹簧=iOS .smooth(0.3) 的精确换算（spring ζ=1、k≈438.65，
        // :248/:262 withAnimation 驱动）。reduceMotion：snap 直达终态。
        // 批5 复核 #4 修（⚠️ 动画态必须在 item 门控之外）：回放路径 VM 两次赋值（LOADING+item→REVEALED）
        // 间零挂起点，StateFlow 合并丢弃中间帧 → 组合只见 REVEALED+item 同帧到达；若 animateFloatAsState
        // 关在 if(item!=null) 内则首组合初值=目标 1f 揭晓动画一帧不播。外提后动画态先以 0.92/0.85 入场
        // （首组合 revealed=false 的默认 UiState 帧），合并帧只是目标变 1f → 正常起播=iOS 回放同动画。
        val spec: AnimationSpec<Float> = if (reduceMotion) snap() else AppMotion.smoothSpring()
        val scale by animateFloatAsState(if (revealed) 1f else 0.92f, spec, label = "giftRevealScale")
        val alpha by animateFloatAsState(if (revealed) 1f else 0.85f, spec, label = "giftRevealAlpha")
        val item = state.item
        if (item != null) {
            GiftImage(
                item = item,
                size = 200.dp,
                cornerRadius = 24.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("你送出了", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = item?.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "给 ${state.characterName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReactionBubble(state: GiftReactionViewModel.UiState, loading: Boolean, reduceMotion: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterAvatar(name = state.characterName, avatarPath = state.avatarPath, size = 44.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.characterName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val emoji = state.outcome?.moodEmoji
                if (!emoji.isNullOrEmpty()) Text(emoji, style = MaterialTheme.typography.titleSmall)
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                // P1-11：loading 行→反应文字的淡入淡出+气泡高度动画（iOS :248/:262 同一 withAnimation(.smooth(0.3))
                // 隐式驱动 :123-144 Group switch 分支的 .opacity 转场+尺寸变化；转场 API 用 tween=项目惯例）。
                AnimatedContent(
                    targetState = loading,
                    transitionSpec = {
                        if (reduceMotion) {
                            (fadeIn(snap()) togetherWith fadeOut(snap()))
                                .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> snap() }))
                        } else {
                            (fadeIn(tween(AppMotion.SMOOTH_MS)) togetherWith fadeOut(tween(AppMotion.SMOOTH_MS)))
                                .using(SizeTransform(clip = false))
                        }
                    },
                    label = "reactionContent",
                ) { isLoading ->
                    // 内容必须用自身参数 isLoading 而非捕获外层 loading（否则转场两侧渲染同一分支）。
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (isLoading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppLoadingRing(size = AppLoadingRingSize.Small)
                                Text(
                                    text = loadingText(state.characterName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            // 回放无已存反应（如聊天送礼记录）时给中性占位，不显空气泡
                            Text(
                                text = state.outcome?.reactionText?.ifBlank { "TA 收下了这份礼物" } ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 等待文案分三档：0-4s「在看」/ 5-9s「还在想」/ ≥10s「想了很久」（每秒重算桶，15s 后服务走兜底）。 */
@Composable
private fun loadingText(characterName: String): String {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedTicker { elapsed = it }
    return when {
        elapsed < 5 -> "$characterName 在看这份礼物…"
        elapsed < 10 -> "$characterName 还在想…"
        else -> "$characterName 想了很久…"
    }
}

@Composable
private fun LaunchedTicker(onTick: (Int) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var sec = 0
        while (isActive) {
            delay(1000)
            sec += 1
            onTick(sec)
        }
    }
}

@Composable
private fun AffinityBadge(state: GiftReactionViewModel.UiState, reduceMotion: Boolean) {
    val outcome = state.outcome ?: return
    if (state.phase != GiftReactionViewModel.Phase.REVEALED || outcome.affinityGain <= 0) return
    // P1-11：插入淡入（iOS :186-219 `if phase == .revealed && affinityGain > 0` 在 withAnimation 下的隐式
    // .opacity 转场）。保留早退结构 + graphicsLayer alpha（合同回退方案：AnimatedVisibility 隐藏态在
    // spacedBy(28dp) Column 里是 0 尺寸节点仍占一段间距；徽章是末元素，进组合即淡入与 iOS 视觉等价）。
    // 安卓守钱分叉（回放空反应 affinityGain=0 不显徽章、不二次入账）由上方早退天然保持，VM 零触碰。
    val badgeAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { badgeAlpha.animateTo(1f, tween(AppMotion.SMOOTH_MS)) }
    Column(
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = badgeAlpha.value },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = AffinityPink.copy(alpha = 0.12f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = AffinityPink, modifier = Modifier.size(18.dp))
                Text(
                    text = outcome.senseText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        val badge = outcome.handmadeBadge
        if (badge != null) {
            Spacer(Modifier.width(0.dp))
            Text(
                text = "· $badge ·",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
