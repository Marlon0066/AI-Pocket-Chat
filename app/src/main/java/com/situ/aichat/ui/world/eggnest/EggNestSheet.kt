package com.situ.aichat.ui.world.eggnest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.pet.EggNestCandidate
import com.situ.aichat.pet.EggNestPhrase
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.world.WorldSceneColors.NsBackBg
import com.situ.aichat.ui.world.WorldSceneColors.NsBarEnd
import com.situ.aichat.ui.world.WorldSceneColors.NsBarStart
import com.situ.aichat.ui.world.WorldSceneColors.NsBody
import com.situ.aichat.ui.world.WorldSceneColors.NsBodyText
import com.situ.aichat.ui.world.WorldSceneColors.NsBtnHighlight
import com.situ.aichat.ui.world.WorldSceneColors.NsChevron
import com.situ.aichat.ui.world.WorldSceneColors.NsClayEnd
import com.situ.aichat.ui.world.WorldSceneColors.NsClayStart
import com.situ.aichat.ui.world.WorldSceneColors.NsClose
import com.situ.aichat.ui.world.WorldSceneColors.NsFoot
import com.situ.aichat.ui.world.WorldSceneColors.NsHandle
import com.situ.aichat.ui.world.WorldSceneColors.NsHeadBg
import com.situ.aichat.ui.world.WorldSceneColors.NsInk
import com.situ.aichat.ui.world.WorldSceneColors.NsStateText
import com.situ.aichat.ui.world.WorldSceneColors.NsBarTrack
import com.situ.aichat.ui.world.WorldSceneColors.NsSub
import com.situ.aichat.ui.world.WorldSceneColors.NsTitle

private val NsEnterEasing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f)

private fun phraseRes(p: EggNestPhrase): Int = when (p) {
    EggNestPhrase.READY -> R.string.world_nest_phrase_ready
    EggNestPhrase.CLOSE -> R.string.world_nest_phrase_close
    EggNestPhrase.WARMING -> R.string.world_nest_phrase_warming
    EggNestPhrase.FAR -> R.string.world_nest_phrase_far
}

/**
 * 「孵蛋之约」底部 sheet（W12.5 图纸 §4.3·快聊 sheet 家族容器值）：深玻璃头 + 暖纸候选列表 + 确认二段。
 * 状态自持（选中候选）；定约经 [onConfirm] 外抛（宿主接 VM.setPact + toast）。
 */
@Composable
internal fun BoxScope.EggNestSheet(
    visible: Boolean,
    candidates: List<EggNestCandidate>,
    reduceMotion: Boolean,
    onConfirm: (EggNestCandidate) -> Unit,
    onClose: () -> Unit,
) {
    var picked by remember { mutableStateOf<EggNestCandidate?>(null) }
    LaunchedEffect(visible) { if (visible) picked = null }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduceMotion) fadeIn(tween(120)) else slideInVertically(tween(340, easing = NsEnterEasing), initialOffsetY = { (it * 1.1f).toInt() }) + fadeIn(tween(340)),
        exit = if (reduceMotion) fadeOut(tween(120)) else slideOutVertically(tween(260, easing = NsEnterEasing), targetOffsetY = { (it * 1.1f).toInt() }) + fadeOut(tween(260)),
    ) {
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.76f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxH)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(NsBody),
        ) {
            NsHead(onClose)
            val sel = picked
            if (sel == null) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    candidates.forEach { c -> NsCandidateRow(c, onPick = { picked = it }) }
                }
                Text(
                    stringResource(R.string.world_nest_pact_foot),
                    color = NsFoot, fontSize = 10.5.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            } else {
                NsConfirmPane(sel, onBack = { picked = null }, onConfirm = { onConfirm(sel) })
            }
        }
    }
}

/** 头部（§4.3·深玻璃·手柄/标题/副题/关闭）。 */
@Composable
private fun NsHead(onClose: () -> Unit) {
    val closeCd = stringResource(R.string.world_qc_close_a11y)
    Box(Modifier.fillMaxWidth().background(NsHeadBg).padding(start = 16.dp, end = 8.dp, bottom = 12.dp)) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(width = 36.dp, height = 4.dp).clip(AppShapes.full).background(NsHandle))
        Column(Modifier.padding(top = 16.dp, end = 48.dp)) {
            Text(stringResource(R.string.world_nest_pact_title), color = NsTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.world_nest_pact_sub), color = NsSub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 8.dp).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickableScale(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = NsClose, fontSize = 16.sp, modifier = Modifier.semantics { contentDescription = closeCd })
        }
    }
}

/** 候选行（§4.3）：头像 40dp + 名 + 状态行；无宠 = 朦胧短语 + 4dp 进度条（percent·上限 150dp）+ ›；有宠 = 禁选 alpha .52。 */
@Composable
private fun NsCandidateRow(c: EggNestCandidate, onPick: (EggNestCandidate) -> Unit) {
    val enabled = c.petName == null
    val rowMod = if (enabled) {
        Modifier.clip(RoundedCornerShape(14.dp)).clickableScale(role = Role.Button) { onPick(c) }
    } else {
        Modifier.clip(RoundedCornerShape(14.dp))
    }
    Row(
        rowMod.fillMaxWidth().sizeIn(minHeight = 48.dp).padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        val alpha = if (enabled) 1f else 0.52f
        Box(Modifier.alpha(alpha)) { CharacterAvatar(name = c.name, avatarPath = c.avatarPath, size = 40.dp) }
        Column(Modifier.weight(1f).alpha(alpha)) {
            Text(c.name, color = NsInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val state = if (c.petName != null) stringResource(R.string.world_nest_row_has_pet, c.petName) else c.phrase?.let { stringResource(phraseRes(it)) }.orEmpty()
            Text(state, color = NsStateText, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            if (enabled) {
                Box(Modifier.padding(top = 6.dp).height(4.dp).widthIn(max = 150.dp).fillMaxWidth().clip(AppShapes.full).background(NsBarTrack)) {
                    Box(
                        Modifier.fillMaxWidth(c.overallPercent.coerceIn(0f, 1f)).height(4.dp).clip(AppShapes.full)
                            .background(Brush.horizontalGradient(0f to NsBarStart, 1f to NsBarEnd)),
                    )
                }
            }
        }
        if (enabled) Text("›", color = NsChevron, fontSize = 16.sp)
    }
}

/** 确认二段（§4.3）：迷你巢蛋 88×63dp（drawEggNest 单源·静止）+ 标题/副文 + 主次按钮。 */
@Composable
private fun NsConfirmPane(c: EggNestCandidate, onBack: () -> Unit, onConfirm: () -> Unit) {
    val eggColor = AvatarColor.color(c.name)
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.padding(top = 2.dp, bottom = 6.dp).size(width = 88.dp, height = 63.dp)) {
            drawEggNest(withEgg = true, eggColor = eggColor)
        }
        Text(stringResource(R.string.world_nest_confirm_title, c.name), color = NsInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.world_nest_confirm_body),
            color = NsBodyText, fontSize = 12.sp, lineHeight = 20.4.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.sizeIn(minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.clip(AppShapes.full)
                        .background(Brush.horizontalGradient(0f to NsClayStart, 1f to NsClayEnd))
                        .background(Brush.verticalGradient(0f to NsBtnHighlight, 0.42f to Color.Transparent))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.world_nest_confirm_cta), color = NsInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(
                Modifier.sizeIn(minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.clip(AppShapes.full).background(NsBackBg).padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.world_nest_confirm_back), color = NsBodyText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
