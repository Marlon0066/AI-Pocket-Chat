package com.situ.aichat.ui.offline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.OnGlass

/** 线下沉浸四步输入的步骤定义（1:1 iOS `StepDefinition`；标签名见 [OfflineInputTags]）。 */
private data class OfflineInputStep(val label: String, val icon: ImageVector, val placeholder: String)

private val OfflineInputSteps = listOf(
    OfflineInputStep("环境", Icons.Filled.Cloud, "描述你们所在的环境…"),
    OfflineInputStep("动作", Icons.AutoMirrored.Filled.DirectionsWalk, "描述你的动作和肢体语言…"),
    OfflineInputStep("对话", Icons.AutoMirrored.Filled.Chat, "你想说的话…"),
    OfflineInputStep("内心", Icons.Filled.Psychology, "描述你此刻的内心想法…"),
)

/**
 * 线下见面沉浸模式的四步输入框（1:1 iOS `OfflineImmersiveInputView`）：环境→动作→对话→内心逐步收集，
 * 组合成 `[环境]…[/环境]\n[动作]…` 标签文本发送（仅 `offlineImmersiveInputEnabled` 启用，替换普通输入栏）。
 */
@Composable
fun OfflineImmersiveInputView(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    themeColor: Color = OfflineTheater.defaultAccent,
) {
    val total = OfflineInputSteps.size
    // 卷一 F1：进程死亡（系统回收）后草稿不丢——四步输入攒了几十字被杀掉重来最伤。
    // 用户主动退出会话 = 栈弹出丢弃（与普通输入框同级，接受）。
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    val stepTexts = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf("", "", "", "") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentStep) { runCatching { focusRequester.requestFocus() } }

    val hasAnyContent = stepTexts.any { it.trim().isNotEmpty() }
    val isLastStep = currentStep == total - 1
    val isFirstStep = currentStep == 0
    val stepDef = OfflineInputSteps[currentStep]

    fun send() {
        val combined = buildImmersiveInputMessage(stepTexts.toList())
        if (combined.isEmpty()) return
        onSend(combined)
        for (i in stepTexts.indices) stepTexts[i] = ""
        currentStep = 0
    }

    Column(modifier.padding(bottom = 4.dp)) {
        // 步骤头部
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // §4.6 步骤标题/图标 = 调和色（themeColor 由 ChatBottomBar 侧已 harmonize）；只换色·结构与字体不动。
            Icon(stepDef.icon, contentDescription = null, tint = themeColor, modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stepDef.label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = themeColor)
            Spacer(Modifier.weight(1f))
            Text("${currentStep + 1} / $total", style = MaterialTheme.typography.labelMedium, color = OnGlass.SecondaryOnDarkTopBar)
        }

        // 进度条（§4.6：track 白 12% / fill 调和色 90% / 高 3dp 不动）
        val fillFraction by animateFloatAsState((currentStep + 1).toFloat() / total, label = "offlineInputProgress")
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 0.dp)
                .padding(top = 8.dp)
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.12f), CircleShape),
        ) {
            Box(Modifier.fillMaxWidth(fillFraction).height(3.dp).background(themeColor.copy(alpha = 0.9f), CircleShape))
        }

        // 步骤导航（恒显·可点跳步·#8）
        FilledTagsRow(stepTexts, currentStep, themeColor, onStepClick = { currentStep = it })

        // 输入框（§4.6 · R1 拍板 TODO-4：AppTextArea 加可选色参，深玻璃上文本域融为一体）
        AppTextArea(
            value = stepTexts[currentStep],
            onValueChange = { stepTexts[currentStep] = it },
            placeholder = stepDef.placeholder,
            minHeight = 56.dp, // 紧凑分步输入：起步约 1 行，随内容长到 maxLines=4（= 原 M3 单行起步观感）
            maxLines = 4,
            focusRequester = focusRequester,
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = OnGlass.PrimaryOnDark,
            placeholderColor = OnGlass.SecondaryOnDarkInput,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp),
        )

        // 底部按钮
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // §4.6「上一步」= 中性主色 OnGlass.PrimaryOnDark（禁用态 @0.4f 保留原 M3 dim 观感）。
            val backColor = OnGlass.PrimaryOnDark.copy(alpha = if (isFirstStep) 0.4f else 1f)
            TextButton(onClick = { if (!isFirstStep) currentStep -= 1 }, enabled = !isFirstStep) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = null, tint = backColor, modifier = Modifier.width(16.dp))
                Text("上一步", style = MaterialTheme.typography.titleSmall, color = backColor)
            }
            Spacer(Modifier.weight(1f))
            // #8·J5：有内容即出「发送」（最后一步保留灰态占位=现状字节级）；发送在左、下一步恒最右（推进键不因发送出现挪位）。
            if (hasAnyContent || isLastStep) {
                TextButton(onClick = { send() }, enabled = hasAnyContent) {
                    Text("发送", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = if (hasAnyContent) themeColor else themeColor.copy(alpha = 0.4f))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = if (hasAnyContent) themeColor else themeColor.copy(alpha = 0.4f), modifier = Modifier.width(18.dp))
                }
            }
            if (!isLastStep) {
                TextButton(onClick = { currentStep += 1 }) {
                    Text("下一步", style = MaterialTheme.typography.titleSmall, color = themeColor)
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = themeColor, modifier = Modifier.width(16.dp))
                }
            }
        }
    }
}

/** 步骤导航行（恒显·可点跳步·#8）：当前高亮、过去已填打勾、过去未填打叉（1:1 iOS filledTagsRow）。 */
@Composable
private fun FilledTagsRow(stepTexts: List<String>, currentStep: Int, themeColor: Color, onStepClick: (Int) -> Unit) {
    val haptics = LocalAppHaptics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OfflineInputSteps.forEachIndexed { index, step ->
            val filled = stepTexts[index].trim().isNotEmpty()
            val isCurrent = index == currentStep
            val isPast = index < currentStep
            // §4.6 标签 chips：当前=调和色@0.20 底/调和色字；已填=调和色@0.10 底/调和色@0.8 字；未填=白@0.06 底/次级@0.6 字。
            val bg = when {
                isCurrent -> themeColor.copy(alpha = 0.20f)
                isPast && filled -> themeColor.copy(alpha = 0.10f)
                else -> Color.White.copy(alpha = 0.06f)
            }
            val fg = when {
                isCurrent -> themeColor
                isPast && filled -> themeColor.copy(alpha = 0.8f)
                else -> OnGlass.SecondaryOnDarkTopBar.copy(alpha = 0.6f)
            }
            Row(
                // 锁定链序：clip 在前保 ripple 裁进圆角；触达靠 Compose 默认 minimum touch target 外扩（不改 chip 视觉尺寸）。
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "跳到${step.label}") { haptics.selection(); onStepClick(index) }
                    .background(bg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (isPast && filled) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = fg, modifier = Modifier.width(8.dp))
                } else if (isPast) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = fg, modifier = Modifier.width(8.dp))
                }
                Text(step.label, style = MaterialTheme.typography.labelSmall, color = fg)
            }
        }
    }
}
