package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.LightAppColors
import com.situ.aichat.ui.designsystem.LiuliLightAppColors
import com.situ.aichat.ui.liuli.designsystem.LiuliOptionCard
import com.situ.aichat.ui.liuli.glass.hazeGlassSupported
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSegmentRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.AppearanceSettingsViewModel
import com.situ.aichat.ui.settings.labelRes

private val PAGE_BOTTOM = 24.dp
private val OPTION_GAP = 12.dp

/**
 * 琉璃 2.0 外观页（设置全面重构卷二 B-2：已移除动态取色开关）。
 */
@Composable
fun LiuliAppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppearanceSettingsViewModel = hiltViewModel(),
) {
    val skin by viewModel.skin.collectAsStateWithLifecycle()
    val glassTier by viewModel.glassTier.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    LiuliAppearanceContent(
        skin = skin,
        glassTier = glassTier,
        mode = mode,
        onSetSkin = { viewModel.setSkin(it) },
        onSetGlassTier = { viewModel.setGlassTier(it) },
        onSetMode = { viewModel.setMode(it) },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LiuliAppearanceContent(
    skin: AppSkin,
    glassTier: GlassTier,
    mode: AppearanceMode,
    onSetSkin: (AppSkin) -> Unit,
    onSetGlassTier: (GlassTier) -> Unit,
    onSetMode: (AppearanceMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    hazeSupported: Boolean = hazeGlassSupported,
) {
    val title = stringResource(R.string.appearance_title)
    val bottomInset = PAGE_BOTTOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(header = stringResource(R.string.appearance_palette_section)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(LiuliPageGeometry.groupPadH)
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(OPTION_GAP),
                        ) {
                            AppSkin.entries.forEach { option ->
                                val (base, accent) = option.liuliSwatchColors()
                                LiuliOptionCard(
                                    selected = option == skin,
                                    onSelect = { onSetSkin(option) },
                                    title = stringResource(option.labelRes()),
                                    swatchStart = base,
                                    swatchEnd = accent,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    if (skin == AppSkin.LIULI) {
                        LiuliGroup(
                            header = stringResource(R.string.appearance_glass_section),
                            footer = stringResource(
                                if (hazeSupported) R.string.appearance_glass_footer else R.string.appearance_glass_needs_android13,
                            ),
                        ) {
                            LiuliSegmentRow(
                                title = null,
                                options = GlassTier.entries,
                                selected = if (hazeSupported) glassTier else GlassTier.FROSTED,
                                label = { stringResource(it.labelRes()) },
                                onSelect = onSetGlassTier,
                                enabled = hazeSupported,
                                divider = false,
                            )
                        }
                    }
                    LiuliGroup(header = stringResource(R.string.appearance_mode_section)) {
                        LiuliSegmentRow(
                            title = null,
                            options = AppearanceMode.entries,
                            selected = mode,
                            label = { stringResource(it.labelRes()) },
                            onSelect = onSetMode,
                            divider = false,
                        )
                    }
                }
            }
        }
    }
}

private fun AppSkin.liuliSwatchColors(): Pair<Color, Color> = when (this) {
    AppSkin.CLAY -> LightAppColors.surface.base to LightAppColors.accent.primary
    AppSkin.LIULI -> LiuliLightAppColors.surface.base to LiuliLightAppColors.bubble.userEnd
}
