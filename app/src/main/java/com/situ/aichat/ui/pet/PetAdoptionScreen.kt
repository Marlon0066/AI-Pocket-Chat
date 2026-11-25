package com.situ.aichat.ui.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.situ.aichat.pet.PetSpecies
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface

/** 领养答题（5 题；选不佳选项弹教育引导，看后才继续，纯引导不阻塞）。暖陶与琉璃共用。 */
internal data class PetQuizOption(val text: String, val education: String?)
internal data class PetQuizQuestion(val question: String, val options: List<PetQuizOption>)

internal val PET_QUIZ_QUESTIONS = listOf(
    PetQuizQuestion(
        "你为什么想养一只宠物？",
        listOf(
            PetQuizOption("它们太可爱了，想要一只陪我玩", null),
            PetQuizOption("看别人都养了，我也想要", "养宠物需要持续的责任心，不只是跟风哦。先想好你是否愿意每天花时间照顾它～"),
            PetQuizOption("我想有一个生命需要依赖我", "宠物是伙伴，不是附属品。它需要你的爱和照顾，但也有自己的个性～"),
            PetQuizOption("和伙伴一起照顾一个小生命，感觉关系会更好", null),
        ),
    ),
    PetQuizQuestion(
        "以下哪种食物对宠物来说是危险的？",
        listOf(
            PetQuizOption("巧克力", null),
            PetQuizOption("胡萝卜", "胡萝卜对大多数宠物是安全的。巧克力才是危险的——它含有可可碱，对猫狗等动物有毒！"),
            PetQuizOption("鸡胸肉", "鸡胸肉是安全的优质蛋白。巧克力才是危险的——它含有可可碱，对猫狗等动物有毒！"),
        ),
    ),
    PetQuizQuestion(
        "如果你连续好几天很忙，顾不上宠物，你会怎么办？",
        listOf(
            PetQuizOption("忙完了第一时间回来看看它", null),
            PetQuizOption("让伙伴帮我照顾", null),
            PetQuizOption("宠物应该能自己照顾自己吧", "宠物需要你的关注和照顾才会开心哦。它不会自己准备食物和清洁，需要你每天花一点时间～"),
        ),
    ),
    PetQuizQuestion(
        "一只宠物每天大约需要多少次照顾？",
        listOf(
            PetQuizOption("想起来了就看一眼", "虽然宠物很独立，但每天至少需要一次认真的照顾——确保它吃饱、干净、心情好～"),
            PetQuizOption("至少每天一次，确保它吃饱喝足心情好", null),
            PetQuizOption("不需要照顾，它自己会好好的", "所有宠物都需要主人的照顾和关爱。每天至少看一次，确保它的基本需求被满足～"),
        ),
    ),
    PetQuizQuestion(
        "你愿意承诺好好照顾这只小生命吗？",
        listOf(
            PetQuizOption("我愿意，我会尽我所能", null),
            PetQuizOption("我试试看吧", "试试看可不够哦～养宠物需要持续的承诺。不过没关系，只要你愿意用心，一定能做好的！"),
            PetQuizOption("和伙伴一起，我一定能做到", null),
        ),
    ),
)

internal enum class PetAdoptionStep { QUIZ, SELECT, NAME }

/**
 * 宠物领养流程（暖陶外衣）：答题考核 → 选种类（仅 4 普通）→ 命名 → 创建（随机性格 + 3% 隐藏款）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetAdoptionScreen(
    onClose: () -> Unit,
    viewModel: PetAdoptionViewModel = hiltViewModel(),
) {
    var step by remember { mutableStateOf(PetAdoptionStep.QUIZ) }
    var quizIndex by remember { mutableIntStateOf(0) }
    var education by remember { mutableStateOf<String?>(null) }
    var selectedSpecies by remember { mutableStateOf(PetSpecies.CAT) }
    var petName by remember { mutableStateOf("") }
    val colors = AppTheme.colors

    Scaffold(
        containerColor = colors.surface.base,
        topBar = { AppTopBar(title = "领养宠物", onBack = onClose) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.surface.base)
                .grainSurface(),
        ) {
            when (step) {
                PetAdoptionStep.QUIZ -> QuizStep(
                    question = PET_QUIZ_QUESTIONS[quizIndex],
                    index = quizIndex,
                    total = PET_QUIZ_QUESTIONS.size,
                    education = education,
                    onSelect = { opt ->
                        if (opt.education != null) education = opt.education
                        else advance(quizIndex, { quizIndex = it }, { step = PetAdoptionStep.SELECT }, { education = null })
                    },
                    onContinue = { advance(quizIndex, { quizIndex = it }, { step = PetAdoptionStep.SELECT }, { education = null }) },
                )
                PetAdoptionStep.SELECT -> SelectStep(selectedSpecies, onSelect = { selectedSpecies = it }, onConfirm = { step = PetAdoptionStep.NAME })
                PetAdoptionStep.NAME -> NameStep(
                    selectedSpecies = selectedSpecies,
                    name = petName,
                    onNameChange = { petName = it },
                    onAdopt = { viewModel.adopt(petName, selectedSpecies, onClose) },
                )
            }
        }
    }
}

private inline fun advance(quizIndex: Int, setIndex: (Int) -> Unit, toSelect: () -> Unit, clearEducation: () -> Unit) {
    clearEducation()
    if (quizIndex < PET_QUIZ_QUESTIONS.size - 1) setIndex(quizIndex + 1) else toSelect()
}

@Composable
private fun QuizStep(
    question: PetQuizQuestion,
    index: Int,
    total: Int,
    education: String?,
    onSelect: (PetQuizOption) -> Unit,
    onContinue: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(AppShapes.full)
                        .background(if (i <= index) colors.accent.primary else colors.surface.sunken),
                )
            }
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(colors.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppFeatureIcons.Pet, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(30.dp))
        }
        Text(question.question, style = AppTypography.titleSmall, color = colors.text.primary)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            question.options.forEach { opt ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .appCardSurface()
                        .clickableScale(onClick = { onSelect(opt) })
                        .padding(16.dp),
                ) {
                    Text(opt.text, style = AppTypography.body, color = colors.text.primary)
                }
            }
        }
        if (education != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.medium)
                    .background(colors.accent.container)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(20.dp))
                Text(education, style = AppTypography.secondary, color = colors.text.primary)
            }
            AppButton(onClick = onContinue, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) {
                Text("我明白了，继续")
            }
        }
    }
}

@Composable
private fun SelectStep(selected: PetSpecies, onSelect: (PetSpecies) -> Unit, onConfirm: () -> Unit) {
    val colors = AppTheme.colors
    val normals = PetSpecies.normalSpecies
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("选一只你喜欢的宠物", style = AppTypography.titleSmall, color = colors.text.primary)
        normals.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { species ->
                    SpeciesCard(species, species == selected, Modifier.weight(1f)) { onSelect(species) }
                }
            }
        }
        PetAnimationView(speciesRaw = selected.raw, stageRaw = "baby", animationState = PetSpriteManager.AnimationState.HAPPY, size = 120.dp)
        AppButton(onClick = onConfirm, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) {
            Text("就选${selected.displayName}！")
        }
    }
}

@Composable
private fun SpeciesCard(species: PetSpecies, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.accent.container else colors.surface.raised)
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) colors.accent.primary else colors.surface.stroke,
                shape = shape,
            )
            .clickableScale(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PetAnimationView(speciesRaw = species.raw, stageRaw = "baby", animationState = PetSpriteManager.AnimationState.IDLE, size = 72.dp, isAnimating = selected)
        Text(
            species.displayName,
            style = AppTypography.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.accent.text else colors.text.primary,
        )
    }
}

@Composable
private fun NameStep(selectedSpecies: PetSpecies, name: String, onNameChange: (String) -> Unit, onAdopt: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PetAnimationView(speciesRaw = selectedSpecies.raw, stageRaw = "baby", animationState = PetSpriteManager.AnimationState.HAPPY, size = 140.dp)
        Spacer(Modifier.height(20.dp))
        Text("给你的${selectedSpecies.displayName}起个名字吧", style = AppTypography.titleSmall, color = colors.text.primary)
        Spacer(Modifier.height(20.dp))
        AppTextField(value = name, onValueChange = onNameChange, label = "宠物名字", modifier = Modifier.fillMaxWidth(0.85f))
        Spacer(Modifier.height(20.dp))
        AppButton(onClick = onAdopt, enabled = name.trim().isNotEmpty(), style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth(0.85f)) {
            Text("领养！")
        }
    }
}
