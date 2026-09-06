package com.situ.aichat.ui.pet

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.pet.AdoptionProgress
import com.situ.aichat.pet.PetGrowthStage
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.growthProgressFraction
import com.situ.aichat.pet.petNeedHeadline
import com.situ.aichat.pet.PetRecoveryThresholds
import com.situ.aichat.pet.PetWalkService
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.metadata
import com.situ.aichat.pet.neglectPhase
import com.situ.aichat.pet.personalityType
import com.situ.aichat.pet.species
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val PET_RINGED_SPRITE_DP = 200 // S3：精灵入心情环中心时的尺寸（环 290dp 外圈环绕）
private const val PET_CENTER_RATIO_Y = 0.32f
private val WALKING_TEXTS = listOf(
    "在公园里走来走去…", "看到一只蝴蝶！", "在草地上打了个滚",
    "好奇地闻来闻去…", "追着落叶跑…", "开心地摇尾巴",
)

/**
 * 宠物详情页（1:1 iOS `PetDetailView`）：三层 ZStack（心情背景 + 粒子 + 内容[精灵 260dp/名字/状态环]）+
 * 玻璃底栏 careToolbar（按 neglectPhase 分支）+ 反应气泡 + 进化庆祝 + 散步倒计时/结算。无宠物 → 领养进度。
 * 商店 / 背包入口 → `petShop/{uuid}` / `petInventory/{uuid}`（P9.3c 已落地·原「P9 占位」注释已过期）。
 */
@Composable
fun PetDetailScreen(
    onBack: () -> Unit,
    onAdopt: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenInventory: () -> Unit,
    pendingReaction: String? = null, // pet-ui-2：从背包返回时携带的反应文案
    onReactionConsumed: () -> Unit = {},
    viewModel: PetDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pet = state.pet

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.saveTrendSnapshot() }
    }

    // pet-ui-2：背包用消耗品/换装成功后回到详情页 → 头顶弹反应气泡 1.5s（对齐 iOS onChange(showInventorySheet) 延迟显示）。
    LaunchedEffect(pendingReaction) {
        if (pendingReaction != null) {
            viewModel.showCustomReaction(pendingReaction)
            onReactionConsumed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppLoadingRing(size = AppLoadingRingSize.Large) }
            pet != null -> PetContent(pet, state, viewModel, onBack, onOpenShop, onOpenInventory)
            else -> NoPetView(state.adoptionProgress, state.canAdopt, onBack, onAdopt)
        }
    }
}

@Composable
private fun PetContent(
    pet: CharacterPetEntity,
    state: PetDetailUiState,
    viewModel: PetDetailViewModel,
    onBack: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val performance = rememberPetPerformance()
    val isWalking = pet.metadata.walkStartTime != null
    val mood = if (isWalking) PetMoodType.HAPPY else PetMoodType.from(pet)
    var showRename by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }

    // pet 加载后检查散步是否已完成（倒计时归零也会触发）
    LaunchedEffect(pet.uuid, pet.metadata.walkStartTime) { viewModel.checkWalkCompletion() }
    // 三个触觉 token 观察点共用「进组合播种水位」守卫（批5 复核 #3/#6 修）：token 驻 VM（跨配置重建/
    // 返回栈存活）而 LaunchedEffect 进组合即跑——裸 `>0` 守卫会在转屏/逛商店返回时凭空补放（iOS
    // .sensoryFeedback(trigger:) 只响值变化、出现绝不响）。remember 以**当前值**播种：重建/重进时
    // seen=现值不重放；进程死 token 与 seen 同归零自洽（rememberSaveable 水位反而会吞恢复后的真触觉）；
    // 离屏期间的迟到 bump 随重进播种丢弃（与「退页丢未播队列」同口径）。
    // pet-logic-3：庆祝触觉——进化/学技能/治愈/寻回/散步结算播 EVOLVE 粒子时 VM +1 token
    //（P1-14 重分档：=iOS .sensoryFeedback(.success, trigger: hapticEvolveToken) PetDetailView:99）。
    var seenCelebration by remember { mutableIntStateOf(state.celebrationHapticToken) }
    LaunchedEffect(state.celebrationHapticToken) {
        if (state.celebrationHapticToken > seenCelebration) {
            seenCelebration = state.celebrationHapticToken
            haptics.success()
        }
    }
    // P1-14：治疗未愈/寻回未果的 medium 触觉（=iOS performTreat:546 / performSearch:558 else 分支 hapticFeedToken）。
    var seenCareMedium by remember { mutableIntStateOf(state.careMediumHapticToken) }
    LaunchedEffect(state.careMediumHapticToken) {
        if (state.careMediumHapticToken > seenCareMedium) {
            seenCareMedium = state.careMediumHapticToken
            haptics.medium()
        }
    }
    // P1-34：成就批解锁 success 触觉（拍板 A：每批一次，drain 在 toast 展示瞬间 bump；iOS 成就零反馈=安卓超越）。
    var seenAchievement by remember { mutableIntStateOf(state.achievementHapticToken) }
    LaunchedEffect(state.achievementHapticToken) {
        if (state.achievementHapticToken > seenAchievement) {
            seenAchievement = state.achievementHapticToken
            haptics.success()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 第 1 层：心情渐变背景（壁纸重构②·NavHost 去垫付 → fillMaxSize 自然铺满系统栏后·不再 clawback）
        PetMoodBackground(mood, Modifier.fillMaxSize())
        // 第 2 层：粒子
        PetParticleOverlay(
            performance = performance,
            activeEffect = state.activeParticleEffect,
            effectStartMillis = state.effectStartMillis,
            species = pet.species,
            petCenterRatioY = PET_CENTER_RATIO_Y,
        )
        // 第 3 层：内容
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // S3：心情环绕精灵（精灵入环中心·"宠物外圈" 4 段莫兰迪柔光环）
            val need = petNeedHeadline(pet)
            PetMoodRing(
                hunger = pet.hunger,
                cleanliness = pet.cleanliness,
                happiness = pet.happiness,
                health = pet.health,
                urgent = need.action,
                ringSize = 290.dp,
                showValues = false,
            ) {
                val animState = when {
                    isWalking -> PetSpriteManager.AnimationState.WALK
                    state.careAnimationState != null -> state.careAnimationState
                    else -> PetSpriteManager.animationStateFor(pet)
                }
                // pet-ui-1：点击逗宠——开心动画 + 弹跳缩放 1.0→1.12→1.0(spring) + 重触觉。
                var tapBounce by remember { mutableStateOf(false) }
                val tapScale by animateFloatAsState(
                    targetValue = if (tapBounce) 1.12f else 1f,
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
                    label = "petTapScale",
                )
                LaunchedEffect(tapBounce) { if (tapBounce) { kotlinx.coroutines.delay(150); tapBounce = false } }
                PetAnimationView(
                    speciesRaw = pet.speciesRaw,
                    stageRaw = pet.growthStageRaw,
                    animationState = animState,
                    size = PET_RINGED_SPRITE_DP.dp,
                    equippedCostumeId = pet.metadata.petInventory.equippedItemId,
                    modifier = Modifier
                        .graphicsLayer { scaleX = tapScale; scaleY = tapScale }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, // 无波纹（逗宠是直接互动，非按钮）
                        ) {
                            haptics.heavy() // P1-14：逗宠=heavy
                            tapBounce = true
                            viewModel.teasePet()
                        },
                )
                // pet-ui-7：反应气泡＝半透磨砂胶囊 + 上移到精灵上方。
                val reactionVisible = state.reactionText != null
                val bubbleAlpha by animateFloatAsState(if (reactionVisible) 1f else 0f, label = "petBubbleAlpha")
                val bubbleScale by animateFloatAsState(if (reactionVisible) 1f else 0.8f, label = "petBubbleScale")
                var heldReaction by remember { mutableStateOf("") }
                LaunchedEffect(state.reactionText) { state.reactionText?.let { heldReaction = it } }
                if (bubbleAlpha > 0.01f) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-4).dp)
                            .graphicsLayer { alpha = bubbleAlpha; scaleX = bubbleScale; scaleY = bubbleScale },
                    ) {
                        Text(heldReaction, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 名字（点击改名）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable { showRename = true },
            ) {
                Text(pet.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.text.primary)
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.a11y_rename), modifier = Modifier.size(16.dp), tint = AppTheme.colors.text.secondary)
            }
            Spacer(Modifier.height(8.dp))
            // S3：需求领衔（emoji + 第一人称文案·urgent → 深陶强调·开心/满足 → 暖常色）
            if (isWalking) {
                val startTime = pet.metadata.walkStartTime ?: 0L
                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                val idx = (elapsedSec / 300) % WALKING_TEXTS.size
                Text("正在散步 · ${WALKING_TEXTS[idx.coerceIn(0, WALKING_TEXTS.size - 1)]}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppTheme.colors.accent.text)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(need.emoji, fontSize = 18.sp)
                    Text(
                        need.headline,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (need.action != null) AppTheme.colors.accent.text else AppTheme.colors.text.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${pet.species.displayName} · ${pet.personalityType.displayName} · ${pet.growthStage.displayName}",
                fontSize = 13.sp,
                color = AppTheme.colors.text.secondary,
            )
            Spacer(Modifier.height(18.dp))
            // S3：行内 4 数值（莫兰迪·urgent 项深陶加粗）
            PetStatsValueRow(pet.hunger, pet.cleanliness, pet.happiness, pet.health, need.action)
            Spacer(Modifier.height(14.dp))
            // S3：成长条
            PetGrowthBar(pet)
        }

        // 顶部返回（左·纸感圆）+ 背包/商店（右·合并悬浮胶囊·C 方案）。壁纸重构②：自管 statusBarsPadding 让位状态栏。
        TopIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), onBack)
        TopActionCapsule(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            items = listOf(
                TopAction(Icons.Filled.Backpack, "背包", onOpenInventory),
                TopAction(Icons.Filled.ShoppingCart, "商店", onOpenShop),
            ),
        )

        // 玻璃底栏
        CareToolbar(
            pet = pet,
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            // P1-14：care 触觉重分档（原统一 LongPress 粗档·早于 AppHaptics 底座）——喂=medium/清洁=light/
            // 玩=heavy（=iOS PetDetailView:96-98 三 token + performCare:471-473）；散步开始=heavy（=Helpers:114
            // hapticPlayToken）；治疗/寻回改结果依赖：点击不触觉，VM 按 治愈/寻回→success(celebration)、
            // 未愈/未果→medium(careMediumHapticToken) 驱动（=iOS :546/:558）。
            onFeed = { haptics.medium(); viewModel.care(PetDetailViewModel.CareKind.FEED) },
            onClean = { haptics.light(); viewModel.care(PetDetailViewModel.CareKind.CLEAN) },
            onPlay = { haptics.heavy(); viewModel.care(PetDetailViewModel.CareKind.PLAY) },
            onTreat = { viewModel.treat() },
            onSearch = { viewModel.search() },
            onStartWalk = { haptics.heavy(); viewModel.startWalk() },
            onWalkComplete = { viewModel.checkWalkCompletion() },
            onShowDetail = { showDetailSheet = true },
        )

        // 顺序 toast 槽（P1-34 queue 化：进化金币 + 成就批共用；🏆 前缀在此供给——资源文案不得再带）
        state.milestoneToast?.let { text ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
            ) {
                Text("🏆 $text", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showRename) {
        RenameDialog(pet.name, onConfirm = { viewModel.rename(it); showRename = false }, onDismiss = { showRename = false })
    }
    if (showDetailSheet) {
        PetDetailSheet(pet, trends = viewModel.statusTrends(pet), onDismiss = { showDetailSheet = false })
    }
    state.walkSettlement?.let { s ->
        WalkSettlementDialog(pet.name, s, onDismiss = { viewModel.dismissWalkSettlement() })
    }
}

/** S3：成长条（领衔在主屏·= [growthProgressFraction] 纯函数·SPECIAL 满级显「满级」）。 */
@Composable
private fun PetGrowthBar(pet: CharacterPetEntity) {
    val colors = AppTheme.colors
    val frac = growthProgressFraction(pet)
    val isSpecial = pet.growthStage == PetGrowthStage.SPECIAL
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("成长 · ${pet.growthStage.displayName}", fontSize = 12.sp, color = colors.text.secondary)
            Text(
                if (isSpecial) "满级" else "${(frac * 100).roundToInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent.text,
            )
        }
        Box(Modifier.width(200.dp).height(8.dp).clip(RoundedCornerShape(50)).background(colors.surface.sunken)) {
            Box(
                Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))),
            )
        }
    }
}

// MARK: - careToolbar（按 neglectPhase 分支）

@Composable
private fun CareToolbar(
    pet: CharacterPetEntity,
    state: PetDetailUiState,
    modifier: Modifier = Modifier,
    onFeed: () -> Unit,
    onClean: () -> Unit,
    onPlay: () -> Unit,
    onTreat: () -> Unit,
    onSearch: () -> Unit,
    onStartWalk: () -> Unit,
    onWalkComplete: () -> Unit,
    onShowDetail: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (pet.neglectPhase) {
                PetNeglectPhase.RAN_AWAY -> {
                    ToolbarButton(Icons.Filled.Search, "寻找", state.searchCooldown, onSearch)
                    val attempts = pet.metadata.searchAttempts
                    if (attempts > 0) {
                        Text("$attempts/${PetRecoveryThresholds.ATTEMPTS_TO_FIND}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PetNeglectPhase.SICK -> {
                    ToolbarButton(Icons.Filled.Healing, "治疗", state.treatCooldown, onTreat)
                    ToolbarButton(Icons.Filled.Restaurant, "喂食", state.feedCooldown, onFeed)
                    ToolbarButton(Icons.Filled.Shower, "清洁", state.cleanCooldown, onClean)
                }
                else -> {
                    ToolbarButton(Icons.Filled.Restaurant, "喂食", state.feedCooldown, onFeed)
                    ToolbarButton(Icons.Filled.Shower, "清洁", state.cleanCooldown, onClean)
                    ToolbarButton(Icons.Filled.Toys, "玩耍", state.playCooldown, onPlay)
                    val startTime = pet.metadata.walkStartTime
                    if (startTime != null && PetWalkService.walkState(pet) is PetWalkService.WalkState.Walking) {
                        WalkCountdownButton(startTime, onWalkComplete)
                    } else {
                        ToolbarButton(Icons.AutoMirrored.Filled.DirectionsWalk, "散步", !PetWalkService.canStartWalk(pet), onStartWalk)
                    }
                }
            }
            if (pet.neglectPhase != PetNeglectPhase.RAN_AWAY) {
                ToolbarButton(Icons.Filled.BarChart, "详情", false, onShowDetail)
            }
        }
    }
}

@Composable
private fun ToolbarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, disabled: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    // S3：养护栏重着色——暖陶图标 + 浅陶软容器（替原冷灰 onSurface/surfaceVariant·沿用 Material 图标只换色）。
    val iconTint = if (disabled) colors.text.tertiary else colors.accent.text
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = CircleShape,
            color = colors.accent.container,
            modifier = Modifier.size(44.dp).then(if (disabled) Modifier else Modifier.clickable(onClick = onClick)),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(22.dp)) }
        }
        Text(label, fontSize = 11.sp, color = if (disabled) colors.text.tertiary else colors.text.secondary)
    }
}

@Composable
private fun WalkCountdownButton(startTime: Long, onComplete: () -> Unit) {
    var remaining by remember(startTime) { mutableLongStateOf(PetWalkService.WALK_DURATION_MS - (System.currentTimeMillis() - startTime)) }
    LaunchedEffect(startTime) {
        while (true) {
            val rem = PetWalkService.WALK_DURATION_MS - (System.currentTimeMillis() - startTime)
            remaining = rem
            if (rem <= 0) { onComplete(); break }
            delay(1000)
        }
    }
    val totalSec = (remaining / 1000).coerceAtLeast(0)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = stringResource(R.string.a11y_pet_walking), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
        }
        Text("${totalSec / 60}:${"%02d".format(totalSec % 60)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/** C 方案·顶部工具按钮：纸感圆（`surface.raised` 暖白纸 + 0.5dp `surface.stroke` 发丝边 + 陶土图标·明度分层浮起·与底栏同手法·点按回弹）。 */
@Composable
internal fun TopIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surface.raised)
            .border(0.5.dp, colors.surface.stroke, CircleShape)
            .clickableScale(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = colors.accent.text, modifier = Modifier.size(19.dp))
    }
}

/** C 方案·背包+商店合并悬浮胶囊（`surface.raised` 纸 + 0.5dp `surface.stroke` 发丝边 + 发丝分隔·各槽独立点按回弹·与底部悬浮胶囊栏呼应）。 */
@Composable
private fun TopActionCapsule(modifier: Modifier = Modifier, items: List<TopAction>) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(AppShapes.full)
            .background(colors.surface.raised)
            .border(0.5.dp, colors.surface.stroke, AppShapes.full),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(Modifier.width(0.5.dp).height(20.dp).background(colors.surface.stroke))
            }
            Box(
                Modifier.width(46.dp).fillMaxHeight().clickableScale(role = Role.Button, onClick = item.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, contentDescription = item.desc, tint = colors.accent.text, modifier = Modifier.size(19.dp))
            }
        }
    }
}

private data class TopAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val desc: String,
    val onClick: () -> Unit,
)

@Composable
private fun rememberPetPerformance(): PetVisualPerformance {
    val context = LocalContext.current
    return remember {
        val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrDefault(1f)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        PetVisualPerformance.current(reduceMotion = scale == 0f, lowPowerMode = pm?.isPowerSaveMode == true)
    }
}
