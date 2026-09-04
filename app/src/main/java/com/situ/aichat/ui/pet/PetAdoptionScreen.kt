package com.situ.aichat.ui.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.pet.PetSpecies

/** 领养答题（5 题；选不佳选项弹教育引导，看后才继续，纯引导不阻塞）。1:1 iOS quizQuestions。 */
private data class QuizOption(val text: String, val education: String?)
private data class QuizQuestion(val question: String, val options: List<QuizOption>)

private val QUIZ_QUESTIONS = listOf(
    QuizQuestion(
        "你为什么想养一只宠物？",
        listOf(
            QuizOption("它们太可爱了，想要一只陪我玩", null),
            QuizOption("看别人都养了，我也想要", "养宠物需要持续的责任心，不只是跟风哦。先想好你是否愿意每天花时间照顾它～"),
            QuizOption("我想有一个生命需要依赖我", "宠物是伙伴，不是附属品。它需要你的爱和照顾，但也有自己的个性～"),
            QuizOption("和伙伴一起照顾一个小生命，感觉关系会更好", null),
        ),
    ),
    QuizQuestion(
        "以下哪种食物对宠物来说是危险的？",
        listOf(
            QuizOption("巧克力", null),
            QuizOption("胡萝卜", "胡萝卜对大多数宠物是安全的。巧克力才是危险的——它含有可可碱，对猫狗等动物有毒！"),
            QuizOption("鸡胸肉", "鸡胸肉是安全的优质蛋白。巧克力才是危险的——它含有可可碱，对猫狗等动物有毒！"),
        ),
    ),
    QuizQuestion(
        "如果你连续好几天很忙，顾不上宠物，你会怎么办？",
        listOf(
            QuizOption("忙完了第一时间回来看看它", null),
            QuizOption("让伙伴帮我照顾", null),
            QuizOption("宠物应该能自己照顾自己吧", "宠物需要你的关注和照顾才会开心哦。它不会自己准备食物和清洁，需要你每天花一点时间～"),
        ),
    ),
    QuizQuestion(
        "一只宠物每天大约需要多少次照顾？",
        listOf(
            QuizOption("想起来了就看一眼", "虽然宠物很独立，但每天至少需要一次认真的照顾——确保它吃饱、干净、心情好～"),
            QuizOption("至少每天一次，确保它吃饱喝足心情好", null),
            QuizOption("不需要照顾，它自己会好好的", "所有宠物都需要主人的照顾和关爱。每天至少看一次，确保它的基本需求被满足～"),
        ),
    ),
    QuizQuestion(
        "你愿意承诺好好照顾这只小生命吗？",
        listOf(
            QuizOption("我愿意，我会尽我所能", null),
            QuizOption("我试试看吧", "试试看可不够哦～养宠物需要持续的承诺。不过没关系，只要你愿意用心，一定能做好的！"),
            QuizOption("和伙伴一起，我一定能做到", null),
        ),
    ),
)

private enum class AdoptionStep { QUIZ, SELECT, NAME }

/**
 * 宠物领养流程（1:1 iOS `PetAdoptionView`）：答题考核 → 选种类（仅 4 普通）→ 命名 → 创建（随机性格 + 3%
 * 隐藏款）。从详情页无宠物的「领养」按钮进入；完成后 [onClose] 返回（详情页观察到新宠物自动刷新）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetAdoptionScreen(
    onClose: () -> Unit,
    viewModel: PetAdoptionViewModel = hiltViewModel(),
) {
    var step by remember { mutableStateOf(AdoptionStep.QUIZ) }
    var quizIndex by remember { mutableIntStateOf(0) }
    var education by remember { mutableStateOf<String?>(null) }
    var selectedSpecies by remember { mutableStateOf(PetSpecies.CAT) }
    var petName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            // 滚动源在三个 step 子件内部（各自 rememberScrollState）→ 恒静止（图纸 §11 D-2）。
            AppTopBar(title = "领养宠物", onBack = onClose)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                AdoptionStep.QUIZ -> QuizStep(
                    question = QUIZ_QUESTIONS[quizIndex],
                    index = quizIndex,
                    total = QUIZ_QUESTIONS.size,
                    education = education,
                    onSelect = { opt -> if (opt.education != null) education = opt.education else advance(quizIndex, { quizIndex = it }, { step = AdoptionStep.SELECT }, { education = null }) },
                    onContinue = { advance(quizIndex, { quizIndex = it }, { step = AdoptionStep.SELECT }, { education = null }) },
                )
                AdoptionStep.SELECT -> SelectStep(selectedSpecies, onSelect = { selectedSpecies = it }, onConfirm = { step = AdoptionStep.NAME })
                AdoptionStep.NAME -> NameStep(
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
    if (quizIndex < QUIZ_QUESTIONS.size - 1) setIndex(quizIndex + 1) else toSelect()
}

@Composable
private fun QuizStep(
    question: QuizQuestion,
    index: Int,
    total: Int,
    education: String?,
    onSelect: (QuizOption) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 进度
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).background(
                        if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(2.dp),
                    ),
                )
            }
        }
        Text("🐾", fontSize = 44.sp)
        Text(question.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            question.options.forEach { opt ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(opt) },
                ) {
                    Text(opt.text, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }
        if (education != null) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(20.dp))
                    Text(education, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AppButton(onClick = onContinue, style = AppButtonStyle.Primary) { Text("我明白了，继续") }
        }
    }
}

@Composable
private fun SelectStep(selected: PetSpecies, onSelect: (PetSpecies) -> Unit, onConfirm: () -> Unit) {
    val normals = PetSpecies.normalSpecies
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("选一只你喜欢的宠物", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // 2x2 网格
        normals.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { species ->
                    SpeciesCard(species, species == selected, Modifier.weight(1f)) { onSelect(species) }
                }
            }
        }
        PetAnimationView(speciesRaw = selected.raw, stageRaw = "baby", animationState = PetSpriteManager.AnimationState.HAPPY, size = 120.dp)
        AppButton(onClick = onConfirm, style = AppButtonStyle.Primary) { Text("就选${selected.displayName}！") }
    }
}

@Composable
private fun SpeciesCard(species: PetSpecies, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PetAnimationView(speciesRaw = species.raw, stageRaw = "baby", animationState = PetSpriteManager.AnimationState.IDLE, size = 72.dp, isAnimating = selected)
            Text(species.displayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun NameStep(selectedSpecies: PetSpecies, name: String, onNameChange: (String) -> Unit, onAdopt: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PetAnimationView(speciesRaw = selectedSpecies.raw, stageRaw = "baby", animationState = PetSpriteManager.AnimationState.HAPPY, size = 140.dp)
        Spacer(Modifier.height(20.dp))
        Text("给你的${selectedSpecies.displayName}起个名字吧", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        AppTextField(value = name, onValueChange = onNameChange, label = "宠物名字", modifier = Modifier.fillMaxWidth(0.8f))
        Spacer(Modifier.height(20.dp))
        AppButton(onClick = onAdopt, enabled = name.trim().isNotEmpty(), style = AppButtonStyle.Tonal) { Text("领养！") }
    }
}
