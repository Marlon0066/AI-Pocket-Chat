package com.situ.aichat.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.offline.OfflineContentBlock
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 线下「梦剧场」10 类内容块差异化渲染（契约 FABLE5_MEETING_THEATER_PROPOSAL.md §4 / 图纸 §4.3）。
 *
 * 恒暗舞台字色一律走 [OfflineTheater]（禁直引 `MaterialTheme.colorScheme`）：楷体旁白系（环境/叙述/动作/独白/心绪签）+
 * 思源黑台词系；斜体全废。照片类背景（壁纸/角色专属图·[onPhotoBackdrop]）时全部可读文字加字幕微影兜底。
 * 逐块淡入由父层经 [Modifier.offlineBlockEntry] 施加；本视图内含对话呼吸、独白呼吸、过渡展开三种块内动画（不动）。
 */
@Composable
fun OfflineContentBlockView(
    block: OfflineContentBlock,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    themeColor: Color = OfflineTheater.defaultAccent,
    reduceMotion: Boolean = false,
    onPhotoBackdrop: Boolean = false,
) {
    // 字幕微影（仅照片类背景）·稳定 remember 后按 onPhotoBackdrop 选用（§4.3 通用）。
    val photoShadow = OfflineTheater.rememberStageTextShadow()
    val shadow = if (onPhotoBackdrop) photoShadow else null
    when (block) {
        is OfflineContentBlock.SceneHeader -> SceneHeaderView(block.location, block.time, shadow)
        is OfflineContentBlock.Environment -> EnvironmentView(block.text, shadow)
        is OfflineContentBlock.Narration -> NarrationView(block.text, shadow)
        is OfflineContentBlock.CharacterDialogue ->
            DialogueView(block.text, isUser = false, characterName, characterAvatarPath, userName, userAvatarPath, shadow)
        is OfflineContentBlock.Action -> ActionView(block.text, themeColor, shadow)
        is OfflineContentBlock.InnerMonologue -> MonologueView(block.text, reduceMotion, shadow)
        is OfflineContentBlock.Emotion -> EmotionView(block.text, themeColor, shadow)
        is OfflineContentBlock.UserAction ->
            DialogueView(block.text, isUser = true, characterName, characterAvatarPath, userName, userAvatarPath, shadow)
        is OfflineContentBlock.TimeSkip -> TimeSkipView(block.text, shadow)
        OfflineContentBlock.SceneTransition -> TransitionView(reduceMotion)
    }
}

// MARK: ① 场景标题 ——居中 ──✦ 地点·时间 ✦──（幕上刺绣·不加底）

@Composable
private fun SceneHeaderView(location: String, time: String, shadow: Shadow?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineDecoration()
        Text("✦", color = OfflineTheater.textFaint, style = TextStyle(fontSize = 11.sp, shadow = shadow))
        Text(
            text = if (time.isEmpty()) location else "$location · $time",
            style = OfflineTheater.sceneHeader.copy(shadow = shadow),
            color = OfflineTheater.textDim,
        )
        Text("✦", color = OfflineTheater.textFaint, style = TextStyle(fontSize = 11.sp, shadow = shadow))
        LineDecoration()
    }
}

// MARK: ② 环境描写 ——竖排居中：氛围 emoji + 楷体（无橙条）

@Composable
private fun EnvironmentView(text: String, shadow: Shadow?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(environmentIcon(text), style = TextStyle(fontSize = 11.sp, shadow = shadow))
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = OfflineTheater.environment.copy(shadow = shadow),
            color = OfflineTheater.textDim,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: ③ 叙述 ——楷体居中（画外音基准声部）

@Composable
private fun NarrationView(text: String, shadow: Shadow?) {
    Text(
        text = text,
        style = OfflineTheater.narration.copy(shadow = shadow),
        color = OfflineTheater.textBody,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 10.dp),
    )
}

// MARK: ④ 角色对话 / ⑧ 用户行为 ——头像 36dp +「」（聚光灯 100% 白·对手戏平等打光）

@Composable
private fun DialogueView(
    text: String,
    isUser: Boolean,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    shadow: Shadow?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isUser) {
            Spacer(Modifier.width(40.dp))
            DialogueText(text, TextAlign.End, Modifier.weight(1f), shadow)
            Spacer(Modifier.width(12.dp))
            AvatarLabel(userName, userAvatarPath, shadow)
        } else {
            AvatarLabel(characterName, characterAvatarPath, shadow)
            Spacer(Modifier.width(12.dp))
            DialogueText(text, TextAlign.Start, Modifier.weight(1f), shadow)
            Spacer(Modifier.width(40.dp))
        }
    }
}

@Composable
private fun DialogueText(text: String, align: TextAlign, modifier: Modifier, shadow: Shadow?) {
    Text(
        text = "「$text」",
        style = OfflineTheater.dialogue.copy(shadow = shadow),
        color = OfflineTheater.textBright,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun AvatarLabel(name: String, avatarPath: String?, shadow: Shadow?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CharacterAvatar(name = name, avatarPath = avatarPath, size = 36.dp)
        Spacer(Modifier.height(4.dp))
        Text(name, style = AppTypography.caption.copy(shadow = shadow), color = OfflineTheater.textFaint)
    }
}

// MARK: ⑤ 角色动作 ——楷体 + 左调和色竖条

@Composable
private fun ActionView(text: String, themeColor: Color, shadow: Shadow?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(themeColor.copy(alpha = 0.55f), CircleShape),
        )
        Text(
            text = text,
            style = OfflineTheater.narration.copy(shadow = shadow),
            color = OfflineTheater.textBody,
        )
    }
}

// MARK: ⑥ 内心独白 ——💭 + 楷体 + 呼吸透明度

@Composable
private fun MonologueView(text: String, reduceMotion: Boolean, shadow: Shadow?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offlineMonologueBreathing(reduceMotion)
            .padding(horizontal = 40.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("💭", style = TextStyle(fontSize = 11.sp, shadow = shadow))
        Text(
            text = text,
            style = OfflineTheater.monologue.copy(shadow = shadow),
            color = OfflineTheater.textDim,
        )
    }
}

// MARK: ⑦ 情绪 ——心绪签（scrimPill 胶囊 + 白高光边 + 调和色圆点 + 楷体）

@Composable
private fun EmotionView(text: String, themeColor: Color, shadow: Shadow?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .background(OfflineTheater.scrimPill, AppShapes.full)
                .border(1.dp, OfflineTheater.pillStroke, AppShapes.full)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(6.dp).background(themeColor, CircleShape))
            Text(
                text = text,
                style = OfflineTheater.moodPill.copy(shadow = shadow),
                color = OfflineTheater.textDim,
            )
        }
    }
}

// MARK: ⑨ 时间流逝 ——虚线 + clock + 字间距

@Composable
private fun TimeSkipView(text: String, shadow: Shadow?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DashedLine()
            Icon(
                AppActionIcons.Clock,
                contentDescription = null,
                tint = OfflineTheater.textFaint,
                modifier = Modifier.height(14.dp),
            )
            DashedLine()
        }
        Text(
            text = formatTimeSkipDisplay(text),
            style = OfflineTheater.timeSkip.copy(shadow = shadow),
            color = OfflineTheater.textDim,
        )
    }
}

// MARK: ⑩ 场景过渡 ——装饰线水平展开

@Composable
private fun TransitionView(reduceMotion: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offlineSceneTransitionEntry(reduceMotion)
            .padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineDecoration()
        Text("·", color = OfflineTheater.textFaint)
        LineDecoration()
    }
}

// MARK: 辅助装饰

/** 实线装饰（40×0.5dp·textFaint·§4.3 ①）。 */
@Composable
private fun LineDecoration() {
    Box(
        Modifier
            .width(40.dp)
            .height(0.5.dp)
            .background(OfflineTheater.textFaint),
    )
}

/** 虚线装饰（40dp 宽、dash[4,3]·textFaint·§4.3 ⑨）。 */
@Composable
private fun DashedLine() {
    val color = OfflineTheater.textFaint
    Box(
        Modifier
            .width(40.dp)
            .height(1.dp)
            .drawBehind {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 0.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        0f,
                    ),
                )
            },
    )
}
