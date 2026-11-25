package com.situ.aichat.ui.story

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle

/**
 * 章末推进区 + 建议完结卡（ST11·图纸 §4.1/§4.2·mockup story_ending_sovereignty_mockup）。
 *
 * 显示条件一律由 [StoryReaderEndgameLogic] 判定，本文件只管长相；两者都不碰选择区（[StoryChoiceSection] 零改）。
 * 配色全部走阅读器纸面单源（[StoryReaderLayout]），不硬编码陶土字面量——推进区浮在纸面上，跟着深浅换肤。
 */

/**
 * 建议卡正文的「可读次要」透明度（施工推导值·图纸 §11 D-6 登记）。
 *
 * 图纸 §4.2 要正文走「secondary 档」**且**对比 ≥4.5，但阅读器纸面层的
 * [StoryReaderLayout.secondaryTextColor]（浅档黑 @0.4）在金 @0.08 的卡底上实测只有 **2.73–2.78:1**，
 * 拿它做 12.5sp 正文必然违反 §4.2 自己的对比度断言。故正文改取纸面层**正文色**降到本档：
 * 实测浅 worst 5.53 / 深 worst 7.41（[com.situ.aichat.ui.designsystem.ColorContrastTest] 看门），
 * 同时仍明显轻于标题（alpha 1.0/0.88），标题↔正文层级不糊。
 */
internal const val SUGGEST_BODY_ALPHA = 0.70f

/** 建议卡底/边/饰的金色透明度（§9 锁定值·与 [com.situ.aichat.ui.designsystem.ColorContrastTest] 互指）。 */
internal const val SUGGEST_GOLD_FILL_ALPHA = 0.08f

/** 金族描边透明度（建议卡边框现用值·卷二的「准备收尾」胶囊与「收尾中」chip 原样引用，不新造）。 */
internal const val SUGGEST_GOLD_LINE_ALPHA = 0.28f

/**
 * 建议完结卡的列表项接线（显示条件走 [StoryReaderEndgameLogic.showEndingSuggestCard]）。
 *
 * 与推进区的接线一起放这儿而不是留在 [StoryReaderScreen]：主屏本卷后已 522 行、越 UI 软上限 500
 * （图纸 §2 明令「若超 500…抽私有函数控回，禁止越 500 不处理」），且这两段接线与本文件的两个 composable
 * 是同一件事，co-locate 更好读。
 */
internal fun LazyListScope.storyEndingSuggestItem(
    chapter: StoryChapterEntity?,
    isLatestChapter: Boolean,
    storyStatus: String?,
    isDark: Boolean,
    onGracefulFinale: () -> Unit,
    onFinish: () -> Unit,
    onKeepWriting: () -> Unit,
) {
    val show = storyShowsEndingSuggest(chapter, isLatestChapter, storyStatus)
    if (!show) return
    item(key = "endingSuggest") {
        StoryEndingSuggestCard(
            isDark = isDark,
            onGracefulFinale = onGracefulFinale,
            onFinish = onFinish,
            onKeepWriting = onKeepWriting,
            modifier = Modifier
                .widthIn(max = StoryReaderLayout.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = StoryReaderLayout.horizontalPadding),
        )
    }
}

/**
 * 章末推进区的列表项接线（显示条件走 [StoryReaderEndgameLogic.showContinueZone]）。
 *
 * @param finaleProgress 非 null = 已定下收尾计划，胶囊槽位换成「收尾中 · 本弧第 K/L 章」+ 取消（卷二 §4.4 画面②）
 */
internal fun LazyListScope.storyContinueZoneItem(
    chapter: StoryChapterEntity?,
    isLatestChapter: Boolean,
    storyStatus: String?,
    isDark: Boolean,
    breatheTrigger: Int,
    finaleProgress: StoryFinaleProgress?,
    draftBeats: String?,
    draftUserEdited: Boolean,
    onWriteClick: () -> Unit,
    onFlowClick: () -> Unit,
    onFinaleClick: () -> Unit,
    onCancelFinaleClick: () -> Unit,
) {
    val show = storyShowsContinueZone(chapter, isLatestChapter, storyStatus)
    if (!show) return
    val zone = storyContinueZoneState(chapter)
    item(key = "continueZone") {
        StoryContinueZone(
            isDark = isDark,
            breatheTrigger = breatheTrigger,
            finaleProgress = finaleProgress,
            mode = zone.mode,
            directionText = zone.directionText,
            draftBeats = draftBeats,
            draftUserEdited = draftUserEdited,
            onWriteClick = onWriteClick,
            onFlowClick = onFlowClick,
            onFinaleClick = onFinaleClick,
            onCancelFinaleClick = onCancelFinaleClick,
            modifier = Modifier
                .widthIn(max = StoryReaderLayout.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = StoryReaderLayout.horizontalPadding),
        )
    }
}

/**
 * 「收尾中」状态 chip 的两个数字（本弧第 [current] / 共 [total] 章）。
 *
 * null = 没有收尾计划（推进区显示金调「准备收尾」胶囊）。数字由 [StoryReaderScreen] 从故事的
 * 弧起点 + 大纲自报章数换算（[com.situ.aichat.story.StoryArcPlanning]），此处只负责显示。
 */
internal data class StoryFinaleProgress(val current: Int, val total: Int)

/**
 * 章末推进区：分隔「接下来」+ 走向卡 / 草稿卡 / 输入入口卡 + 主胶囊。
 *
 * 治的是「AI 没给选项的末章 = 没有方向盘」这条断头路（正文后一片空白，只能翻顶栏菜单）。
 *
 * **三模式（图纸 2026-08-06 §4.2·[mode]）**——按「已存的是什么」换装，显示门 `showContinueZone` 零改：
 * NATURAL_FLOW = 态 A 现状（输入卡 + 「让故事自然发展」tonal）；NEXT_CHAPTER = 点了选项或哨兵残留（同上但文案改
 * 「继续写下一章」——方向早定了，再说自然发展就是说反话）；BY_DIRECTION = 亲笔走向已存（**走向卡顶替输入卡**当
 * 编辑入口 D-2 + 「按走向继续写」实底胶囊）。三模式点击**恒走同一个 [onFlowClick]**（= `forceContinue`：userChoice
 * 非空时不覆盖、直接生成，已存走向天然被 prompt 吃到）——不许为胶囊新开生成路径。
 *
 * @param breatheTrigger 变化即让入口卡呼吸一次（建议卡「还想继续写」滚来后的指路信号·§4.2）；态 B 下挂走向卡。
 * @param directionText 已存的亲笔走向原文；仅 BY_DIRECTION 下有值。
 */
@Composable
internal fun StoryContinueZone(
    isDark: Boolean,
    breatheTrigger: Int,
    finaleProgress: StoryFinaleProgress?,
    mode: ContinueZoneMode,
    directionText: String?,
    /** 上一章末预排的本章计划草稿（null/空白 = 草稿卡不出现）＋它是否被用户在导演台改过（决定卡上 tag）。 */
    draftBeats: String?,
    draftUserEdited: Boolean,
    onWriteClick: () -> Unit,
    onFlowClick: () -> Unit,
    onFinaleClick: () -> Unit,
    onCancelFinaleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val scrim = StoryReaderLayout.chromeScrimColor(isDark)
    val border = StoryReaderLayout.chromeBorderColor(isDark)
    val secondary = StoryReaderLayout.secondaryTextColor(isDark)
    val accent = StoryReaderLayout.menuAccentColor(isDark)

    val breathe = rememberStoryBreatheScale(breatheTrigger)

    val byDirection = mode == ContinueZoneMode.BY_DIRECTION
    Column(modifier = modifier.fillMaxWidth()) {
        ContinueHeader(isDark)
        // 态 B 走向卡（§4.2）：置于草稿卡**之上**——先看见「我要什么」，再看见「AI 打算怎么写」。
        if (byDirection && directionText != null) {
            StoryDirectionCard(directionText, isDark, onWriteClick, Modifier.scale(breathe.value))
        }
        // 草稿卡（U-2·零新回调 J8）：决定怎么推进之前先给 AI 的打算；点它 = 开导演台改草稿
        draftBeats?.takeIf { it.isNotBlank() }?.let { StoryDraftCard(it, draftUserEdited, isDark, onWriteClick) }
        // 输入卡：态 B 下整卡隐藏（D-2·走向卡顶替入口，两个入口并排只会让人懵）。
        if (!byDirection) Surface(
            onClick = onWriteClick,
            shape = RoundedCornerShape(16.dp),
            color = scrim,
            border = BorderStroke(0.75.dp, border),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .scale(breathe.value),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AppActionIcons.Edit, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                // 卷三 §4.5：入口从「写一句」升级为导演台（选择区的输入提示走 story_custom_choice_hint_* 族，与此入口无涉）。
                Text(stringResource(R.string.story_continue_director_hint), color = secondary, fontSize = 15.sp)
            }
        }
        // 胶囊排（§4.4 画面②）：陶土「让故事自然发展」恒在；右侧槽位按有没有收尾计划二选一——
        // 无计划 = 金调「准备收尾」；有计划 = 金调状态 chip（不可点）+ 幽灵虚线「取消收尾」。
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 主胶囊三模式（§4.2/§4.3）：文案按 mode 换，态 B 升**实底**（走向已定，这枚是此刻的主动作）；
            // 几何与点击回调三模式共用——点击恒 onFlowClick，禁新开生成路径。
            val onPill = if (byDirection) StoryReaderLayout.onAccentTextColor(isDark) else accent
            Surface(
                onClick = { haptics.light(); onFlowClick() },
                shape = CircleShape,
                color = if (byDirection) accent else accent.copy(alpha = if (isDark) 0.20f else 0.15f),
                modifier = Modifier.height(40.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AppActionIcons.Play, contentDescription = null, tint = onPill, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(storyContinueFlowLabelRes(mode)),
                        color = onPill,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (finaleProgress == null) {
                FinalePill(isDark = isDark, onClick = { haptics.light(); onFinaleClick() })
            } else {
                FinaleStatusChip(isDark = isDark, progress = finaleProgress)
                CancelFinalePill(isDark = isDark, onClick = { haptics.light(); onCancelFinaleClick() })
            }
        }
    }
}

/**
 * 金调「准备收尾」胶囊（§4.4 画面②）：金 = 完结语义，与建议完结卡同族——底/边/字全部取
 * [StoryReaderLayout.suggestGoldColor] 与建议卡现用的两个 alpha（[SUGGEST_GOLD_FILL_ALPHA] / 0.28f），
 * **不新造 token**。触达 48dp 由 M3 可点 `Surface` 的 `minimumInteractiveComponentSize` 自动保证（视觉 40dp 与
 * 左侧陶土胶囊等高）；无入场动效，随推进区整体出现。
 * [enabled] / [modifier]：琉璃脸写作中上锁用（加法·默认 = 原行为）。
 */
@Composable
internal fun FinalePill(isDark: Boolean, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val gold = StoryReaderLayout.suggestGoldColor(isDark)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = gold.copy(alpha = SUGGEST_GOLD_FILL_ALPHA),
        border = BorderStroke(0.75.dp, gold.copy(alpha = SUGGEST_GOLD_LINE_ALPHA)),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Flag, contentDescription = null, tint = gold, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.story_finale_pill),
                color = gold,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 「收尾中 · 本弧第 K/L 章」状态 chip（同金族·**不可点**：它是状态显示，不是动作）。 */
@Composable
internal fun FinaleStatusChip(isDark: Boolean, progress: StoryFinaleProgress) {
    val gold = StoryReaderLayout.suggestGoldColor(isDark)
    Surface(
        shape = CircleShape,
        color = gold.copy(alpha = SUGGEST_GOLD_FILL_ALPHA),
        border = BorderStroke(0.75.dp, gold.copy(alpha = SUGGEST_GOLD_LINE_ALPHA)),
        modifier = Modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Flag, contentDescription = null, tint = gold, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.story_finale_chip, progress.current, progress.total),
                color = gold,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 幽灵虚线「取消收尾」胶囊：次级撤销语义——虚线画法照同屏「继续写这个故事」/「开新故事」入场卡先例
 * （drawBehind + dashPathEffect 10/8），色走纸面层 secondary，**不用金**（它不是完结动作）。
 */
@Composable
internal fun CancelFinalePill(isDark: Boolean, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val secondary = StoryReaderLayout.secondaryTextColor(isDark)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = modifier
            .height(40.dp)
            .drawBehind {
                drawRoundRect(
                    color = secondary.copy(alpha = 0.45f),
                    cornerRadius = CornerRadius(size.height / 2f),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.story_finale_cancel),
                color = secondary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 推进区分隔行：──── ✧ 接下来 ✧ ────（样式取自 StoryChoiceSection 的 StoryChoiceHeader 同族·仅搬样式不共享组件）。 */
@Composable
internal fun ContinueHeader(isDark: Boolean) {
    val ornament = StoryReaderLayout.ornamentColor(isDark)
    val textColor = StoryReaderLayout.secondaryTextColor(isDark)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(ornament))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✧", color = ornament, fontSize = 13.sp)
            Text(
                stringResource(R.string.story_continue_header),
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Serif,
                letterSpacing = StoryReaderLayout.ornamentKerning,
            )
            Text("✧", color = ornament, fontSize = 13.sp)
        }
        Box(Modifier.weight(1f).height(0.5.dp).background(ornament))
    }
}

/**
 * 建议完结卡（§4.2）：AI 觉得可以收尾了 → 请用户盖章。
 *
 * 拍板②的门面：AI 说了不算，卡片把「完结 / 继续写」两条路平等摆出来，用户点哪条都算数。
 * 与选择区可并存（矛盾输出场景）；不做 dismiss——写出新章后本章非末章，卡自然不见。
 */
@Composable
fun StoryEndingSuggestCard(
    isDark: Boolean,
    onGracefulFinale: () -> Unit,
    onFinish: () -> Unit,
    onKeepWriting: () -> Unit,
    modifier: Modifier = Modifier,
    /** 卡底三枚钮（琉璃 2.0 卷六·三·下甲「槽外给」：默认 = 暖陶三枚 AppButton，琉璃传三枚 LiuliButton）。 */
    actions: @Composable () -> Unit = { StoryEndingSuggestWarmActions(onGracefulFinale, onFinish, onKeepWriting) },
) {
    // 金按**纸面深浅**取档（不跟 App 主题）：浅主题 + 深纸面会撞成深金压深纸（实测 2.97:1）。
    val gold = StoryReaderLayout.suggestGoldColor(isDark)
    val title = StoryReaderLayout.textColor(isDark)
    val body = StoryReaderLayout.textColor(isDark).copy(alpha = SUGGEST_BODY_ALPHA)
    Surface(
        shape = RoundedCornerShape(18.dp),
        // 金 @0.08 直接叠在纸面上（Surface 的半透明色由 Compose 与背后纸面合成 → 浅深自适应天然成立）。
        color = gold.copy(alpha = SUGGEST_GOLD_FILL_ALPHA),
        border = BorderStroke(0.75.dp, gold.copy(alpha = SUGGEST_GOLD_LINE_ALPHA)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            Text(
                "✦",
                color = gold.copy(alpha = 0.35f),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 16.dp),
            )
            Column(modifier = Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Flag, contentDescription = null, tint = gold, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.story_ending_suggest_title),
                        color = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    stringResource(R.string.story_ending_suggest_body),
                    color = body,
                    fontSize = 12.5.sp,
                    lineHeight = 21.25.sp, // 12.5 × 1.7
                    modifier = Modifier.padding(top = 8.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun StoryEndingSuggestWarmActions(onGracefulFinale: () -> Unit, onFinish: () -> Unit, onKeepWriting: () -> Unit) {
    // 卷二 §4.4 画面③：双钮改**竖排三钮**——新增主推「从容收尾」置顶（金实底=原主钮样式），
    // 原「就此完结」降次钮（Tonal），「还想继续写」降 quiet（Text）。破坏性/终局动作不占主 CTA
    // 是既有房规（PITFALLS 1d）：这里主 CTA 给的是「慢慢收好」而不是「立刻归档」。
    AppButton(
        onClick = onGracefulFinale,
        style = AppButtonStyle.Primary,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Text(stringResource(R.string.story_ending_suggest_graceful), textAlign = TextAlign.Center)
    }
    AppButton(
        onClick = onFinish,
        style = AppButtonStyle.Tonal,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Text(stringResource(R.string.story_ending_suggest_finish), textAlign = TextAlign.Center)
    }
    AppButton(
        onClick = onKeepWriting,
        style = AppButtonStyle.Text,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Text(stringResource(R.string.story_ending_suggest_continue), textAlign = TextAlign.Center)
    }
}
