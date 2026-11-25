package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 自研白瓷悬浮圆钮（去谷歌化批一过审）——取代 M3 `FloatingActionButton`（如聊天回底钮）。
 *
 * 44dp 白瓷釉面圆钮（[porcelainThumb] 软影 + 暖边 + 按压轻缩 0.92），触达区经
 * [minimumInteractiveComponentSize] 扩到 48dp。
 */
@Composable
fun AppRoundFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.92f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "roundFabScale",
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(44.dp)
            .porcelainThumb(pressed = pressed, raised = enabled)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { haptics.light(); onClick() },
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent.text,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Fable-5 自研釉烧悬浮胶囊钮（去谷歌化批一过审）——取代 M3 `FloatingActionButton` / `ExtendedFloatingActionButton`。
 *
 * 46dp 釉烧陶土胶囊（[glazedClay] 材质 + 按压轻缩），带图标与文字（或仅图标时传空串）。
 */
@Composable
fun AppPillFab(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.95f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "pillFabScale",
    )

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(46.dp)
            .glazedClay(pressed = pressed, enabled = enabled)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { haptics.light(); onClick() },
            )
            .padding(horizontal = if (label.isNotEmpty()) 18.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (label.isEmpty()) null else null,
            tint = colors.accent.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = AppTypography.label,
                color = colors.accent.onPrimary,
            )
        }
    }
}
