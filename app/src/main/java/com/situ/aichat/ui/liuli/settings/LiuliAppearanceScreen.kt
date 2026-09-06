package com.situ.aichat.ui.liuli.settings

import android.os.Build
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
import com.situ.aichat.ui.designsystem.LightAppColors
import com.situ.aichat.ui.designsystem.LiuliLightAppColors
import com.situ.aichat.ui.liuli.designsystem.LiuliOptionCard
import com.situ.aichat.ui.liuli.glass.realtimeBlurSupported
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSegmentRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.AppearanceSettingsViewModel
import com.situ.aichat.ui.settings.labelRes

/** 页底留白（同设置主页·契约 §6.5「底内距 = 导航栏 + 24」）。 */
private val PAGE_BOTTOM = 24.dp
/** 主题两列卡之间的缝（A-6）。 */
private val OPTION_GAP = 12.dp

/**
 * 外观页（琉璃·图纸 2026-09-06 卷四 A-6）。与暖陶 `AppearanceSettingsScreen` 共用
 * [AppearanceSettingsViewModel]、共用节序与条件；**底栏不透明度节琉璃不做**（契约 D-7：琉璃底栏是玻璃片，
 * 根本不读那个偏好）。
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
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    LiuliAppearanceContent(
        skin = skin,
        glassTier = glassTier,
        mode = mode,
        useDynamicColor = useDynamicColor,
        onSetSkin = { viewModel.setSkin(it) },
        onSetGlassTier = { viewModel.setGlassTier(it) },
        onSetMode = { viewModel.setMode(it) },
        onSetDynamicColor = { viewModel.setDynamicColor(it) },
        onBack = onBack,
        modifier = modifier,
    )
}

/** 外观页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliAppearanceContent(
    skin: AppSkin,
    glassTier: GlassTier,
    mode: AppearanceMode,
    useDynamicColor: Boolean,
    onSetSkin: (AppSkin) -> Unit,
    onSetGlassTier: (GlassTier) -> Unit,
    onSetMode: (AppearanceMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    blurSupported: Boolean = realtimeBlurSupported,
    dynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
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
                    // 主题：两列选项卡（A-6·swatch 双色圆 + 标签 + 副标）。
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
                    // 透明度：只在琉璃 + 有实时模糊能力时给选（API 29–30 强制着色，没得选就不给选项）。
                    if (skin == AppSkin.LIULI && blurSupported) {
                        LiuliGroup(
                            header = stringResource(R.string.appearance_glass_section),
                            footer = stringResource(R.string.appearance_glass_footer),
                        ) {
                            LiuliSegmentRow(
                                title = null, // 组标题已点名（复核 R1 🟡-2）
                                options = GlassTier.entries,
                                selected = glassTier,
                                label = { stringResource(it.labelRes()) },
                                onSelect = onSetGlassTier,
                                divider = false,
                            )
                        }
                    }
                    LiuliGroup(header = stringResource(R.string.appearance_mode_section)) {
                        LiuliSegmentRow(
                            title = null, // 组标题已点名（复核 R1 🟡-2）
                            options = AppearanceMode.entries,
                            selected = mode,
                            label = { stringResource(it.labelRes()) },
                            onSelect = onSetMode,
                            divider = false,
                        )
                    }
                    // 动态取色（Material You）仅 Android 12+ 有意义；无分区标题（同暖陶）。
                    if (dynamicColorSupported) {
                        LiuliGroup {
                            LiuliToggleRow(
                                title = stringResource(R.string.appearance_dynamic_color_title),
                                subtitle = stringResource(R.string.appearance_dynamic_color_subtitle),
                                checked = useDynamicColor,
                                onCheckedChange = onSetDynamicColor,
                                divider = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 脸的色样两色（底色 + 强调色·读对应 `AppColors` 浅档代表色，不复制色值）。
 *
 * 暖陶那份 `AppSkin.swatchColors()` 是 private（`AppearanceSettingsScreen.kt:355`），此处**自写同值**：
 * 暖陶 = `LightAppColors.surface.base` + `accent.primary`；琉璃 = `LiuliLightAppColors.surface.base` +
 * `bubble.userEnd`。改任一侧要同步另一侧（图纸 §11 D-9）。
 */
private fun AppSkin.liuliSwatchColors(): Pair<Color, Color> = when (this) {
    AppSkin.CLAY -> LightAppColors.surface.base to LightAppColors.accent.primary
    AppSkin.LIULI -> LiuliLightAppColors.surface.base to LiuliLightAppColors.bubble.userEnd
}
