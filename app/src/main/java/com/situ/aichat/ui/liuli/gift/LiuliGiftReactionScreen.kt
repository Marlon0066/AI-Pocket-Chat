package com.situ.aichat.ui.liuli.gift

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
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
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.gift.GiftImage
import com.situ.aichat.ui.gift.GiftReactionViewModel
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val LiuliAffinityRose = Color(0xFFE06A80)

/**
 * 礼物送出反应页（琉璃 2.0 脸 · 💰只换壳，与 `GiftReactionScreen` 共享 [GiftReactionViewModel] 及全部守卫）。
 */
@Composable
fun LiuliGiftReactionScreen(
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
    val dark = LocalIsDarkTheme.current
    val scrollState = rememberScrollState()
    val collapsed = rememberScrollCollapsed(scrollState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var prevRevealed by remember { mutableStateOf(revealed) }
    LaunchedEffect(revealed) {
        if (revealed && !prevRevealed) haptics.soft()
        prevRevealed = revealed
    }
    BackHandler(enabled = isSendFlow && loading) { /* loading 期拦截返回 */ }

    val title = if (isSendFlow) "送出" else "礼物详情"
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = collapsed,
        leading = if (isSendFlow) ({}) else null,
        actions = if (isSendFlow) {
            {
                LiuliButton(onClick = onFinish, style = LiuliButtonStyle.Text, enabled = !loading) {
                    Text("完成")
                }
            }
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    top = LiuliPageGeometry.navRow,
                    bottom = navBottom + LiuliPageGeometry.pageBottom,
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            LiuliLargeTitle(title)
            LiuliGiftHeader(state, revealed, reduceMotion)
            LiuliReactionBubble(state, loading, reduceMotion, dark)
            LiuliAffinityBadge(state, reduceMotion)
        }
    }
}

@Composable
private fun LiuliGiftHeader(state: GiftReactionViewModel.UiState, revealed: Boolean, reduceMotion: Boolean) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val spec: AnimationSpec<Float> = if (reduceMotion) snap() else AppMotion.smoothSpring()
    val scale by animateFloatAsState(if (revealed) 1f else 0.92f, spec, label = "liuliGiftRevealScale")
    val alpha by animateFloatAsState(if (revealed) 1f else 0.85f, spec, label = "liuliGiftRevealAlpha")
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.item?.let { item ->
            GiftImage(
                item = item,
                size = 200.dp,
                cornerRadius = 24.dp,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha },
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("你送出了", style = typography.caption, color = colors.text.secondary)
            Text(state.item?.name.orEmpty(), style = typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.text.primary)
            Text("给 ${state.characterName}", style = typography.secondary, color = colors.text.secondary)
        }
    }
}

@Composable
private fun LiuliReactionBubble(state: GiftReactionViewModel.UiState, loading: Boolean, reduceMotion: Boolean, dark: Boolean) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterAvatar(name = state.characterName, avatarPath = state.avatarPath, size = 44.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.characterName, style = typography.label, color = colors.text.primary)
                val emoji = state.outcome?.moodEmoji
                if (!emoji.isNullOrEmpty()) Text(emoji, style = typography.label)
            }
            Box(Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark)) {
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
                    label = "liuliReactionContent",
                ) { isLoading ->
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (isLoading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                LiuliSpinner(size = 16.dp)
                                Text(liuliLoadingText(state.characterName), style = typography.body, color = colors.text.secondary)
                            }
                        } else {
                            Text(
                                state.outcome?.reactionText?.ifBlank { "TA 收下了这份礼物" } ?: "",
                                style = typography.body,
                                color = colors.text.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun liuliLoadingText(characterName: String): String {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var sec = 0
        while (isActive) {
            delay(1000)
            sec += 1
            elapsed = sec
        }
    }
    return when {
        elapsed < 5 -> "$characterName 在看这份礼物…"
        elapsed < 10 -> "$characterName 还在想…"
        else -> "$characterName 想了很久…"
    }
}

@Composable
private fun LiuliAffinityBadge(state: GiftReactionViewModel.UiState, reduceMotion: Boolean) {
    val outcome = state.outcome ?: return
    if (state.phase != GiftReactionViewModel.Phase.REVEALED || outcome.affinityGain <= 0) return
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val badgeAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { badgeAlpha.animateTo(1f, tween(AppMotion.SMOOTH_MS)) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter).graphicsLayer { alpha = badgeAlpha.value },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .background(LiuliAffinityRose.copy(alpha = 0.14f), AppShapes.full)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = LiuliAffinityRose, modifier = Modifier.size(18.dp))
            Text(outcome.senseText, style = typography.label, color = colors.text.primary)
        }
        outcome.handmadeBadge?.let { badge ->
            Text("· $badge ·", style = typography.caption, color = colors.text.secondary)
        }
    }
}
