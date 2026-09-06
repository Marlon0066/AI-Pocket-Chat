package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.moments.MomentsHubViewModel
import com.situ.aichat.ui.moments.WorldHeroCard

/**
 * 琉璃动态枢纽（图纸 2026-09-06 卷三 §4.5 · 契约 §6 C 甲）。
 *
 * 与暖陶唯一的行为差是**标题**：暖陶是 `AppTopBar` 居中小标题，琉璃按 Q-H1 甲改成与其余三页同一套大标题带
 * + 收起玻璃顶栏（A-10）。页内容与顺序照 F6 一个不少：API 横幅 → 世界美术卡（**原样借用**·A-9） →
 * 圈子条 → 日子条 → 日记 / 故事两方卡 → 宠物条。
 *
 * 数据全走 [MomentsHubViewModel] 现成三条流 + `refreshApiMissing()`，一个方法都不新增。
 */
@Composable
fun LiuliMomentsHubScreen(
    onOpenFeed: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenStory: () -> Unit,
    onOpenOurDays: () -> Unit,
    onOpenWorld: () -> Unit,
    onOpenPet: (String) -> Unit,
    onOpenPetHub: () -> Unit,
    viewModel: MomentsHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val worldCard by viewModel.worldCard.collectAsStateWithLifecycle()
    val apiMissing by viewModel.apiMissing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshApiMissing() }

    val scrollState = rememberScrollState()
    LiuliHomeScaffold(
        title = stringResource(R.string.moment_hub_title),
        collapsed = rememberScrollCollapsed(scrollState),
        plus = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(
                    bottom = LiuliHomeGeometry.listBottomInset +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            // 大标题带自带左右 20（四页标题左缘对齐），卡片流的 gutter 挂在内层，两者不叠加。
            LiuliLargeTitle(stringResource(R.string.moment_hub_title))
            Column(
                modifier = Modifier
                    .padding(horizontal = LiuliHomeGeometry.gutter)
                    .padding(top = LiuliHomeGeometry.titleGap),
                verticalArrangement = Arrangement.spacedBy(LiuliHomeGeometry.cardGap),
            ) {
                if (apiMissing) LiuliApiMissingBanner()
                WorldHeroCard(worldCard = worldCard, onOpenWorld = onOpenWorld, onOpenPet = onOpenPet)
                LiuliCircleStrip(state = state, onClick = onOpenFeed)
                LiuliOurDaysStrip(onClick = onOpenOurDays)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LiuliHomeGeometry.cardGap)) {
                    LiuliDiaryCard(state = state, onClick = onOpenDiary, modifier = Modifier.weight(1f))
                    LiuliStoryCard(state = state, onClick = onOpenStory, modifier = Modifier.weight(1f))
                }
                LiuliPetHubStrip(state = state, onClick = onOpenPetHub)
            }
        }
    }
}
