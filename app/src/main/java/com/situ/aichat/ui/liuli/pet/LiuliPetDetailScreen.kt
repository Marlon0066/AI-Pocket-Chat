package com.situ.aichat.ui.liuli.pet

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.situ.aichat.pet.PetGrowthStage
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.PetRecoveryThresholds
import com.situ.aichat.pet.PetWalkService
import com.situ.aichat.pet.growthProgressFraction
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.metadata
import com.situ.aichat.pet.neglectPhase
import com.situ.aichat.pet.personalityType
import com.situ.aichat.pet.petNeedHeadline
import com.situ.aichat.pet.species
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.pet.NoPetView
import com.situ.aichat.ui.pet.PetAnimationView
import com.situ.aichat.ui.pet.PetDetailSheet
import com.situ.aichat.ui.pet.PetDetailUiState
import com.situ.aichat.ui.pet.PetDetailViewModel
import com.situ.aichat.ui.pet.PetMoodBackground
import com.situ.aichat.ui.pet.PetMoodRing
import com.situ.aichat.ui.pet.PetMoodType
import com.situ.aichat.ui.pet.PetParticleOverlay
import com.situ.aichat.ui.pet.PetSpriteManager
import com.situ.aichat.ui.pet.PetStatsValueRow
import com.situ.aichat.ui.pet.PetVisualPerformance
import com.situ.aichat.ui.pet.RenameDialog
import com.situ.aichat.ui.pet.WalkCountdownButton
import com.situ.aichat.ui.pet.WalkSettlementDialog
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val LIULI_WALKING_TEXTS = listOf(
    "在公园里走来走去…", "看到一只蝴蝶！", "在草地上打了个滚",
    "好奇地闻来闻去…", "追着落叶跑…", "开心地摇尾巴",
)

/** 宠物详情页（琉璃 2.0 脸：保留全屏心情画布、精灵与粒子，顶部与底部养护浮岛升级为琉璃玻璃材质）。 */
@Composable
fun LiuliPetDetailScreen(
    onBack: () -> Unit,
    onAdopt: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenInventory: () -> Unit,
    pendingReaction: String? = null,
    onReactionConsumed: () -> Unit = {},
    viewModel: PetDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pet = state.pet
    DisposableEffect(Unit) { onDispose { viewModel.saveTrendSnapshot() } }
    LaunchedEffect(pendingReaction) {
        if (pendingReaction != null) {
            viewModel.showCustomReaction(pendingReaction)
            onReactionConsumed()
        }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppLoadingRing(size = AppLoadingRingSize.Large) }
            pet != null -> LiuliPetContent(pet, state, viewModel, onBack, onOpenShop, onOpenInventory)
            else -> NoPetView(state.adoptionProgress, state.canAdopt, onBack, onAdopt)
        }
    }
}

@Composable
private fun LiuliPetContent(
    pet: CharacterPetEntity,
    state: PetDetailUiState,
    viewModel: PetDetailViewModel,
    onBack: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val performance = rememberLiuliPetPerformance()
    val isWalking = pet.metadata.walkStartTime != null
    val mood = if (isWalking) PetMoodType.HAPPY else PetMoodType.from(pet)
    var showRename by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }

    LaunchedEffect(pet.uuid, pet.metadata.walkStartTime) { viewModel.checkWalkCompletion() }
    var seenCelebration by remember { mutableIntStateOf(state.celebrationHapticToken) }
    LaunchedEffect(state.celebrationHapticToken) {
        if (state.celebrationHapticToken > seenCelebration) { seenCelebration = state.celebrationHapticToken; haptics.success() }
    }
    var seenCareMedium by remember { mutableIntStateOf(state.careMediumHapticToken) }
    LaunchedEffect(state.careMediumHapticToken) {
        if (state.careMediumHapticToken > seenCareMedium) { seenCareMedium = state.careMediumHapticToken; haptics.medium() }
    }
    var seenAchievement by remember { mutableIntStateOf(state.achievementHapticToken) }
    LaunchedEffect(state.achievementHapticToken) {
        if (state.achievementHapticToken > seenAchievement) { seenAchievement = state.achievementHapticToken; haptics.success() }
    }

    Box(Modifier.fillMaxSize()) {
        PetMoodBackground(mood, Modifier.fillMaxSize())
        PetParticleOverlay(performance, state.activeParticleEffect, state.effectStartMillis, pet.species, 0.32f)

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
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
                var tapBounce by remember { mutableStateOf(false) }
                val tapScale by animateFloatAsState(if (tapBounce) 1.12f else 1f, spring(0.45f, 600f), label = "liuliPetTap")
                LaunchedEffect(tapBounce) { if (tapBounce) { delay(150); tapBounce = false } }
                PetAnimationView(
                    speciesRaw = pet.speciesRaw,
                    stageRaw = pet.growthStageRaw,
                    animationState = animState,
                    size = 200.dp,
                    equippedCostumeId = pet.metadata.petInventory.equippedItemId,
                    modifier = Modifier
                        .graphicsLayer { scaleX = tapScale; scaleY = tapScale }
                        .clickable(remember { MutableInteractionSource() }, null) {
                            haptics.heavy(); tapBounce = true; viewModel.teasePet()
                        },
                )
                val reactionVisible = state.reactionText != null
                val bubbleAlpha by animateFloatAsState(if (reactionVisible) 1f else 0f, label = "liuliBubbleAlpha")
                val bubbleScale by animateFloatAsState(if (reactionVisible) 1f else 0.8f, label = "liuliBubbleScale")
                var held by remember { mutableStateOf("") }
                LaunchedEffect(state.reactionText) { state.reactionText?.let { held = it } }
                if (bubbleAlpha > 0.01f) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-4).dp)
                            .graphicsLayer { alpha = bubbleAlpha; scaleX = bubbleScale; scaleY = bubbleScale }
                            .liuliCardMaterial(LiuliShapes.pill, dark)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) { Text(held, fontSize = 12.sp, color = colors.text.primary) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clickable { showRename = true }) {
                Text(pet.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.text.primary)
                Icon(AppActionIcons.Edit, stringResource(R.string.a11y_rename), Modifier.size(16.dp), tint = colors.text.secondary)
            }
            Spacer(Modifier.height(8.dp))
            if (isWalking) {
                val elapsedSec = ((System.currentTimeMillis() - (pet.metadata.walkStartTime ?: 0L)) / 1000).toInt()
                val idx = (elapsedSec / 300) % LIULI_WALKING_TEXTS.size
                Text("正在散步 · ${LIULI_WALKING_TEXTS[idx.coerceIn(0, LIULI_WALKING_TEXTS.lastIndex)]}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.accent.text)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(need.emoji, fontSize = 18.sp)
                    Text(need.headline, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (need.action != null) colors.accent.text else colors.text.primary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("${pet.species.displayName} · ${pet.personalityType.displayName} · ${pet.growthStage.displayName}", fontSize = 13.sp, color = colors.text.secondary)
            Spacer(Modifier.height(18.dp))
            PetStatsValueRow(pet.hunger, pet.cleanliness, pet.happiness, pet.health, need.action)
            Spacer(Modifier.height(14.dp))
            LiuliPetGrowthBar(pet)
        }

        // 顶部琉璃玻璃胶囊：返回 + 背包/商店
        Box(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .size(40.dp)
                .liuliCardMaterial(CircleShape, dark)
                .clickableScale(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(AppTopBarIcons.Back, "返回", tint = colors.text.primary, modifier = Modifier.size(19.dp)) }

        Row(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .height(40.dp)
                .liuliCardMaterial(LiuliShapes.pill, dark),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(46.dp).fillMaxHeight().clickableScale(role = Role.Button, onClick = onOpenInventory), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Backpack, "背包", tint = colors.accent.text, modifier = Modifier.size(19.dp))
            }
            Box(Modifier.width(0.5.dp).height(20.dp).background(LiuliMaterials.divider(dark)))
            Box(Modifier.width(46.dp).fillMaxHeight().clickableScale(role = Role.Button, onClick = onOpenShop), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ShoppingCart, "商店", tint = colors.accent.text, modifier = Modifier.size(19.dp))
            }
        }

        // 底部琉璃养护浮岛
        LiuliCareToolbar(
            pet = pet,
            state = state,
            dark = dark,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            onFeed = { haptics.medium(); viewModel.care(PetDetailViewModel.CareKind.FEED) },
            onClean = { haptics.light(); viewModel.care(PetDetailViewModel.CareKind.CLEAN) },
            onPlay = { haptics.heavy(); viewModel.care(PetDetailViewModel.CareKind.PLAY) },
            onTreat = { viewModel.treat() },
            onSearch = { viewModel.search() },
            onStartWalk = { haptics.heavy(); viewModel.startWalk() },
            onWalkComplete = { viewModel.checkWalkCompletion() },
            onShowDetail = { showDetailSheet = true },
        )

        state.milestoneToast?.let { text ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp)
                    .liuliCardMaterial(LiuliShapes.pill, dark)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("🏆 $text", fontWeight = FontWeight.Medium, color = colors.accent.text) }
        }
    }

    if (showRename) RenameDialog(pet.name, onConfirm = { viewModel.rename(it); showRename = false }, onDismiss = { showRename = false })
    if (showDetailSheet) PetDetailSheet(pet, trends = viewModel.statusTrends(pet), onDismiss = { showDetailSheet = false })
    state.walkSettlement?.let { s -> WalkSettlementDialog(pet.name, s, onDismiss = { viewModel.dismissWalkSettlement() }) }
}

@Composable
private fun LiuliPetGrowthBar(pet: CharacterPetEntity) {
    val colors = AppTheme.colors
    val frac = growthProgressFraction(pet)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("成长 · ${pet.growthStage.displayName}", fontSize = 12.sp, color = colors.text.secondary)
            Text(if (pet.growthStage == PetGrowthStage.SPECIAL) "满级" else "${(frac * 100).roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accent.text)
        }
        Box(Modifier.width(200.dp).height(8.dp).clip(RoundedCornerShape(50)).background(colors.surface.sunken)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))))
        }
    }
}

@Composable
private fun LiuliCareToolbar(
    pet: CharacterPetEntity,
    state: PetDetailUiState,
    dark: Boolean,
    modifier: Modifier,
    onFeed: () -> Unit,
    onClean: () -> Unit,
    onPlay: () -> Unit,
    onTreat: () -> Unit,
    onSearch: () -> Unit,
    onStartWalk: () -> Unit,
    onWalkComplete: () -> Unit,
    onShowDetail: () -> Unit,
) {
    Row(
        modifier = modifier.liuliCardMaterial(RoundedCornerShape(32.dp), dark).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (pet.neglectPhase) {
            PetNeglectPhase.RAN_AWAY -> {
                LiuliCareBtn(AppActionIcons.Search, "寻找", state.searchCooldown, onSearch)
                val attempts = pet.metadata.searchAttempts
                if (attempts > 0) Text("$attempts/${PetRecoveryThresholds.ATTEMPTS_TO_FIND}", fontSize = 11.sp, color = AppTheme.colors.text.secondary)
            }
            PetNeglectPhase.SICK -> {
                LiuliCareBtn(Icons.Filled.Healing, "治疗", state.treatCooldown, onTreat)
                LiuliCareBtn(Icons.Filled.Restaurant, "喂食", state.feedCooldown, onFeed)
                LiuliCareBtn(Icons.Filled.Shower, "清洁", state.cleanCooldown, onClean)
            }
            else -> {
                LiuliCareBtn(Icons.Filled.Restaurant, "喂食", state.feedCooldown, onFeed)
                LiuliCareBtn(Icons.Filled.Shower, "清洁", state.cleanCooldown, onClean)
                LiuliCareBtn(Icons.Filled.Toys, "玩耍", state.playCooldown, onPlay)
                val startTime = pet.metadata.walkStartTime
                if (startTime != null && PetWalkService.walkState(pet) is PetWalkService.WalkState.Walking) {
                    WalkCountdownButton(startTime, onWalkComplete)
                } else {
                    LiuliCareBtn(Icons.AutoMirrored.Filled.DirectionsWalk, "散步", !PetWalkService.canStartWalk(pet), onStartWalk)
                }
            }
        }
        if (pet.neglectPhase != PetNeglectPhase.RAN_AWAY) LiuliCareBtn(Icons.Filled.BarChart, "详情", false, onShowDetail)
    }
}

@Composable
private fun LiuliCareBtn(icon: ImageVector, label: String, disabled: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.accent.container)
                .then(if (disabled) Modifier else Modifier.clickableScale(role = Role.Button, onClick = onClick)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (disabled) colors.text.tertiary else colors.accent.text, modifier = Modifier.size(22.dp)) }
        Text(label, fontSize = 11.sp, color = if (disabled) colors.text.tertiary else colors.text.secondary)
    }
}

@Composable
private fun rememberLiuliPetPerformance(): PetVisualPerformance {
    val context = LocalContext.current
    return remember {
        val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrDefault(1f)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        PetVisualPerformance.current(reduceMotion = scale == 0f, lowPowerMode = pm?.isPowerSaveMode == true)
    }
}
