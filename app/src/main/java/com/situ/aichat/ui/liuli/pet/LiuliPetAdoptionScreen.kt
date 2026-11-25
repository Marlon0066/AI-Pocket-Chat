package com.situ.aichat.ui.liuli.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
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
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.pet.PET_QUIZ_QUESTIONS
import com.situ.aichat.ui.pet.PetAdoptionStep
import com.situ.aichat.ui.pet.PetAdoptionViewModel
import com.situ.aichat.ui.pet.PetAnimationView
import com.situ.aichat.ui.pet.PetQuizOption
import com.situ.aichat.ui.pet.PetQuizQuestion
import com.situ.aichat.ui.pet.PetSpriteManager
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 宠物领养流程（琉璃 2.0 脸：答题 → 选物种 → 起名）。 */
@Composable
fun LiuliPetAdoptionScreen(
    onClose: () -> Unit,
    viewModel: PetAdoptionViewModel = hiltViewModel(),
) {
    var step by remember { mutableStateOf(PetAdoptionStep.QUIZ) }
    var quizIndex by remember { mutableIntStateOf(0) }
    var education by remember { mutableStateOf<String?>(null) }
    var selectedSpecies by remember { mutableStateOf(PetSpecies.CAT) }
    var petName by remember { mutableStateOf("") }

    val dark = LocalIsDarkTheme.current
    val scrollState = rememberScrollState()
    val collapsed = rememberScrollCollapsed(scrollState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(title = "领养宠物", onBack = onClose, collapsed = collapsed) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = LiuliPageGeometry.gutter)
                .padding(top = LiuliPageGeometry.navRow + 4.dp, bottom = navBottom + LiuliPageGeometry.pageBottom),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LiuliLargeTitle("领养宠物", modifier = Modifier.fillMaxWidth())
            when (step) {
                PetAdoptionStep.QUIZ -> LiuliQuizStep(
                    question = PET_QUIZ_QUESTIONS[quizIndex],
                    index = quizIndex,
                    total = PET_QUIZ_QUESTIONS.size,
                    education = education,
                    dark = dark,
                    onSelect = { opt ->
                        if (opt.education != null) education = opt.education
                        else {
                            education = null
                            if (quizIndex < PET_QUIZ_QUESTIONS.lastIndex) quizIndex++ else step = PetAdoptionStep.SELECT
                        }
                    },
                    onContinue = {
                        education = null
                        if (quizIndex < PET_QUIZ_QUESTIONS.lastIndex) quizIndex++ else step = PetAdoptionStep.SELECT
                    },
                )
                PetAdoptionStep.SELECT -> LiuliSelectStep(
                    selected = selectedSpecies,
                    dark = dark,
                    onSelect = { selectedSpecies = it },
                    onConfirm = { step = PetAdoptionStep.NAME },
                )
                PetAdoptionStep.NAME -> LiuliNameStep(
                    selectedSpecies = selectedSpecies,
                    name = petName,
                    onNameChange = { petName = it },
                    onAdopt = { viewModel.adopt(petName, selectedSpecies, onClose) },
                )
            }
        }
    }
}

@Composable
private fun LiuliQuizStep(
    question: PetQuizQuestion,
    index: Int,
    total: Int,
    education: String?,
    dark: Boolean,
    onSelect: (PetQuizOption) -> Unit,
    onContinue: () -> Unit,
) {
    val colors = AppTheme.colors
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
    Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accent.container), contentAlignment = Alignment.Center) {
        Icon(AppFeatureIcons.Pet, null, tint = colors.accent.text, modifier = Modifier.size(30.dp))
    }
    Text(question.question, style = AppTypography.titleSmall, color = colors.text.primary)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options.forEach { opt ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .liuliCardMaterial(LiuliShapes.group, dark)
                    .clickableScale(onClick = { onSelect(opt) })
                    .padding(16.dp),
            ) {
                Text(opt.text, style = AppTypography.body, color = colors.text.primary)
            }
        }
    }
    if (education != null) {
        Row(
            Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Info, null, tint = colors.accent.text, modifier = Modifier.size(20.dp))
            Text(education, style = AppTypography.secondary, color = colors.text.primary)
        }
        LiuliButton(onClick = onContinue, style = LiuliButtonStyle.Prominent, modifier = Modifier.fillMaxWidth()) {
            Text("我明白了，继续")
        }
    }
}

@Composable
private fun LiuliSelectStep(selected: PetSpecies, dark: Boolean, onSelect: (PetSpecies) -> Unit, onConfirm: () -> Unit) {
    val colors = AppTheme.colors
    Text("选一只你喜欢的宠物", style = AppTypography.titleSmall, color = colors.text.primary)
    PetSpecies.normalSpecies.chunked(2).forEach { rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rowItems.forEach { species ->
                val isSel = species == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .liuliCardMaterial(LiuliShapes.group, dark)
                        .then(if (isSel) Modifier.border(1.5.dp, colors.accent.primary, LiuliShapes.group) else Modifier)
                        .clickableScale(onClick = { onSelect(species) })
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PetAnimationView(species.raw, "baby", PetSpriteManager.AnimationState.IDLE, size = 72.dp, isAnimating = isSel)
                    Text(
                        species.displayName,
                        style = AppTypography.label,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSel) colors.accent.text else colors.text.primary,
                    )
                }
            }
        }
    }
    PetAnimationView(selected.raw, "baby", PetSpriteManager.AnimationState.HAPPY, size = 120.dp)
    LiuliButton(onClick = onConfirm, style = LiuliButtonStyle.Prominent, modifier = Modifier.fillMaxWidth()) {
        Text("就选${selected.displayName}！")
    }
}

@Composable
private fun LiuliNameStep(selectedSpecies: PetSpecies, name: String, onNameChange: (String) -> Unit, onAdopt: () -> Unit) {
    val colors = AppTheme.colors
    Spacer(Modifier.height(12.dp))
    PetAnimationView(selectedSpecies.raw, "baby", PetSpriteManager.AnimationState.HAPPY, size = 140.dp)
    Text("给你的${selectedSpecies.displayName}起个名字吧", style = AppTypography.titleSmall, color = colors.text.primary)
    LiuliField(value = name, onValueChange = onNameChange, label = "宠物名字", modifier = Modifier.fillMaxWidth())
    LiuliButton(onClick = onAdopt, style = LiuliButtonStyle.Prominent, enabled = name.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text("领养！")
    }
}
