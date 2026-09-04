package com.situ.aichat.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.ui.settings.BackgroundReliabilityScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首启欢迎引导（11.4b + P1-29）。前 4 页信息架构 1:1 iOS `Views/OnboardingView.swift`
 * （欢迎 → 沉浸式体验 → 会成长的角色 → 准备好了吗），视觉走 Material 3：
 * 用 [HorizontalPager] 替 iOS `TabView(.page)`，底部圆点指示器。
 * 第 5 页「让 TA 准时找到你」为安卓超越（iOS 仅 tag0-3 无后台可靠性概念）：国产 ROM 杀后台引导，
 * 「去设置」分支态内嵌 [BackgroundReliabilityScreen]（引导期 NavHost 未在组合，无路由可走），
 * 「稍后再说」=完成引导唯一出口（第 4 页「开始体验」改为翻页，保证人人经过本页）。
 * 在用户协议之后、首启展示一次（门控见 [com.situ.aichat.ui.AppRoot]）。
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    // pagerState 必须留在分支之外：切到保障屏时 pager 离开组合，state 提升在此页码才不丢。
    val pagerState = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    // saveable：用户在系统设置/安全中心停留期间 HyperOS 可能杀进程，回来要恢复保障屏覆盖态。
    var showReliabilitySetup by rememberSaveable { mutableStateOf(false) }
    if (showReliabilitySetup) {
        // 分支互换而非 Box 叠放：pager 离开组合，TalkBack 摸不到被盖住的引导页（批6 P1-17 教训）。
        // 该屏平时在 NavHost 靠回栈返回、自身无 BackHandler——引导期需补，否则返回键直接退 Activity。
        BackHandler { showReliabilitySetup = false }
        BackgroundReliabilityScreen(onBack = { showReliabilitySetup = false })
    } else {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    when (page) {
                        0 -> WelcomePage()
                        1 -> ExperiencePage()
                        2 -> GrowthPage()
                        3 -> StartPage(
                            onContinue = {
                                scope.launch {
                                    if (reduceMotion) {
                                        pagerState.scrollToPage(RELIABILITY_PAGE)
                                    } else {
                                        pagerState.animateScrollToPage(RELIABILITY_PAGE)
                                    }
                                }
                            },
                        )
                        else -> ReliabilityPage(
                            onGoToSettings = { showReliabilitySetup = true },
                            onLater = onComplete,
                        )
                    }
                }
                SwipeHintSlot(pagerState = pagerState, reduceMotion = reduceMotion)
                PageIndicator(count = PAGE_COUNT, current = pagerState.currentPage)
            }
        }
    }
}

private const val PAGE_COUNT = 5
private const val RELIABILITY_PAGE = 4

// 向右滑动引导（方案 A·雪佛龙呼吸）：仅前 3 页（无按钮·靠滑动前进）展示
private const val LAST_HINT_PAGE = 2
private const val SWIPE_HINT_DELAY_MS = 1200L
private val SWIPE_HINT_SLOT_HEIGHT = 22.dp

// MARK: - 页面布局容器（垂直居中 + 可滚动，兜底小屏溢出）

@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

// MARK: - 第 1 页：欢迎

@Composable
private fun WelcomePage() {
    OnboardingPage {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_welcome_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - 第 2 页：核心体验

@Composable
private fun ExperiencePage() {
    OnboardingPage {
        PageTitle(R.string.onboarding_experience_title, R.string.onboarding_experience_subtitle)
        Spacer(Modifier.height(24.dp))
        FeatureCard(
            icon = Icons.Filled.Groups,
            tint = MaterialTheme.colorScheme.primary,
            titleRes = R.string.onboarding_feature_multi_character_title,
            detailRes = R.string.onboarding_feature_multi_character_detail,
        )
        Spacer(Modifier.height(14.dp))
        FeatureCard(
            icon = Icons.AutoMirrored.Filled.Chat,
            tint = MaterialTheme.colorScheme.tertiary,
            titleRes = R.string.onboarding_feature_chat_title,
            detailRes = R.string.onboarding_feature_chat_detail,
        )
        Spacer(Modifier.height(14.dp))
        FeatureCard(
            icon = Icons.Filled.Call,
            tint = MaterialTheme.colorScheme.secondary,
            titleRes = R.string.onboarding_feature_voice_title,
            detailRes = R.string.onboarding_feature_voice_detail,
        )
    }
}

// MARK: - 第 3 页：成长系统

@Composable
private fun GrowthPage() {
    OnboardingPage {
        PageTitle(R.string.onboarding_growth_title, R.string.onboarding_growth_subtitle)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().appCardSurface(raised = true, cornerRadius = 16.dp).grainSurface()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HighlightRow("🌱", R.string.onboarding_growth_personality_title, R.string.onboarding_growth_personality_detail)
                HighlightRow("💝", R.string.onboarding_growth_relationship_title, R.string.onboarding_growth_relationship_detail)
                HighlightRow("🧠", R.string.onboarding_growth_memory_title, R.string.onboarding_growth_memory_detail)
                HighlightRow("📸", R.string.onboarding_growth_social_title, R.string.onboarding_growth_social_detail)
            }
        }
    }
}

// MARK: - 第 4 页：开始使用（P1-29 后按钮=翻到第 5 页，完成引导唯一出口移至可靠性页）

@Composable
private fun StartPage(onContinue: () -> Unit) {
    OnboardingPage {
        PageTitle(R.string.onboarding_start_title, R.string.onboarding_start_subtitle)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().appCardSurface(raised = true, cornerRadius = 16.dp).grainSurface()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StepRow(1, R.string.onboarding_step_1)
                StepRow(2, R.string.onboarding_step_2)
                StepRow(3, R.string.onboarding_step_3)
            }
        }
        Spacer(Modifier.height(24.dp))
        AppButton(
            onClick = onContinue,
            style = AppButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.onboarding_start_button),
                style = AppTypography.bodyEmphasis,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - 第 5 页：后台可靠性（P1-29 安卓超越；iOS 无此页）

@Composable
private fun ReliabilityPage(onGoToSettings: () -> Unit, onLater: () -> Unit) {
    OnboardingPage {
        PageTitle(R.string.onboarding_reliability_title, R.string.onboarding_reliability_subtitle)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().appCardSurface(raised = true, cornerRadius = 16.dp).grainSurface()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HighlightRow("🔋", R.string.bg_battery_title, R.string.bg_battery_desc)
                HighlightRow("🚀", R.string.bg_autostart_title, R.string.bg_autostart_desc)
            }
        }
        Spacer(Modifier.height(24.dp))
        AppButton(
            onClick = onGoToSettings,
            style = AppButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.bg_action_open_settings),
                style = AppTypography.bodyEmphasis,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        AppButton(onClick = onLater, style = AppButtonStyle.Text, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_reliability_later))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_reliability_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - 复用片段

@Composable
private fun ColumnScope.PageTitle(titleRes: Int, subtitleRes: Int) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(subtitleRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FeatureCard(icon: ImageVector, tint: Color, titleRes: Int, detailRes: Int) {
    Box(Modifier.fillMaxWidth().appCardSurface(raised = true, cornerRadius = 16.dp).grainSurface()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(detailRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HighlightRow(emoji: String, titleRes: Int, detailRes: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(emoji, fontSize = 26.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(detailRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepRow(step: Int, textRes: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$step",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val selected = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (selected) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    ),
            )
        }
    }
}

// MARK: - 向右滑动引导（方案 A·雪佛龙呼吸）
// 进页静置一拍才浮现、拖动时隐去、reduceMotion 退化为静态双箭头；色随主题（陶土 primary）。
// 单实例挂在 pager 与圆点之间的固定高度槽，前 3 页显示、后 2 页留空，避免页间布局跳动。
// 无障碍：chevron 是纯装饰，刻意不挂 contentDescription/语义——翻页的无障碍播报由 HorizontalPager
// 自身提供，这里再宣告「向右滑动」只会与系统翻页语义重复啰嗦；故对 TalkBack 透明、不可聚焦，请勿"补全"。

@Composable
private fun SwipeHintSlot(pagerState: PagerState, reduceMotion: Boolean) {
    val onHintPage = pagerState.currentPage <= LAST_HINT_PAGE
    val dragging = pagerState.isScrollInProgress
    // 进页静置一拍才浮现：让视线先读内容，不抢第一眼；拖动期间不计时。
    // 用 remember 而非 rememberSaveable 是有意的：这是瞬态 UI 提示，旋转/进程死恢复后重置、
    // 重新淡入一次即可，无需持久化（持久化反而要处理"恢复后还该不该提示"的边界）。
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage, dragging) {
        settled = false
        if (onHintPage && !dragging) {
            delay(SWIPE_HINT_DELAY_MS)
            settled = true
        }
    }
    val showHint = onHintPage && !dragging && (reduceMotion || settled)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SWIPE_HINT_SLOT_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        SwipeHintChevrons(visible = showHint, reduceMotion = reduceMotion)
    }
}

@Composable
private fun SwipeHintChevrons(visible: Boolean, reduceMotion: Boolean) {
    val containerAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 320),
        label = "swipeHintFade",
    )
    if (containerAlpha <= 0.01f) return
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.graphicsLayer { alpha = containerAlpha },
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwipeHintChevron(color = color, reduceMotion = reduceMotion, startDelayMillis = 0)
        SwipeHintChevron(color = color, reduceMotion = reduceMotion, startDelayMillis = 160)
    }
}

@Composable
private fun SwipeHintChevron(color: Color, reduceMotion: Boolean, startDelayMillis: Int) {
    val driftDp: Float
    val tipAlpha: Float
    if (reduceMotion) {
        driftDp = 0f
        tipAlpha = 0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "swipeHintChevron")
        val spec = infiniteRepeatable<Float>(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(startDelayMillis),
        )
        val drift by transition.animateFloat(initialValue = 0f, targetValue = 6f, animationSpec = spec, label = "drift")
        val fade by transition.animateFloat(initialValue = 0.3f, targetValue = 1f, animationSpec = spec, label = "fade")
        driftDp = drift
        tipAlpha = fade
    }
    Canvas(
        modifier = Modifier
            .size(width = 11.dp, height = 16.dp)
            .graphicsLayer { translationX = driftDp.dp.toPx() },
    ) {
        val stroke = 2.3f.dp.toPx()
        val x0 = size.width * 0.28f
        val xTip = size.width * 0.72f
        val yTop = size.height * 0.2f
        val yMid = size.height * 0.5f
        val yBot = size.height * 0.8f
        drawLine(color, Offset(x0, yTop), Offset(xTip, yMid), strokeWidth = stroke, cap = StrokeCap.Round, alpha = tipAlpha)
        drawLine(color, Offset(xTip, yMid), Offset(x0, yBot), strokeWidth = stroke, cap = StrokeCap.Round, alpha = tipAlpha)
    }
}
