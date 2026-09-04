package com.situ.aichat.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.ThemePalette
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.LightAppColors
import com.situ.aichat.ui.designsystem.QinghuaLightAppColors
import kotlin.math.roundToInt

/**
 * 外观设置页（11.4a + 主题配色 2026-06-30）：主题配色（暖陶/青花·多主题 opt-in，反转旧「不做多主题换肤」）
 * + 深浅模式（对齐 iOS `AppearanceMode`：浅色/深色/跟随系统·与配色正交）+ Material You 动态取色（安卓特有·
 * 仅 Android 12+ 显示）。读写走 DataStore，根部主题即时生效。见 FABLE5_THEME_QINGHUA_PROPOSAL.md。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: AppearanceSettingsViewModel = hiltViewModel(),
) {
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    val bottomNavOpacity by viewModel.bottomNavOpacity.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.appearance_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth(),
        ) {
            SettingsSection(title = stringResource(R.string.appearance_palette_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemePalette.entries.forEach { option ->
                        PaletteOptionCard(
                            palette = option,
                            selected = option == palette,
                            onSelect = { viewModel.setPalette(option) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.appearance_mode_section)) {
                Column(Modifier.selectableGroup()) {
                    AppearanceMode.entries.forEach { option ->
                        ModeRow(
                            label = stringResource(option.labelRes()),
                            selected = option == mode,
                            onSelect = { viewModel.setMode(option) },
                        )
                    }
                }
            }

            // 动态取色（Material You）仅 Android 12+ 有意义；无独立分区标题 → 无标题卡壳（自研设置行自带透明底）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp).appCardSurface().padding(vertical = 6.dp)) {
                    AppSettingsRow(
                        title = stringResource(R.string.appearance_dynamic_color_title),
                        subtitle = stringResource(R.string.appearance_dynamic_color_subtitle),
                        trailing = {
                            AppSwitch(
                                checked = useDynamicColor,
                                onCheckedChange = { viewModel.setDynamicColor(it) },
                            )
                        },
                    )
                }
            }

            // 过渡丝滑化·A1：悬浮底栏背景不透明度（默认 0.88）+ 实时预览。
            SettingsSection(title = stringResource(R.string.appearance_bottom_nav_section)) {
                BottomNavOpacityControl(
                    opacity = bottomNavOpacity,
                    onCommit = { viewModel.setBottomNavOpacity(it) },
                )
            }
        }
    }
}

/**
 * 底栏不透明度调节（过渡丝滑化·A1·D3）。拖动期用本地 [live] 即时驱动预览，松手 [onCommit] 才落 DataStore
 * （底栏不在本屏可见，故拖动期无需写盘；预览给即时视觉反馈）。范围 0.5–1.0。
 */
@Composable
private fun BottomNavOpacityControl(opacity: Float, onCommit: (Float) -> Unit) {
    var live by remember(opacity) { mutableStateOf(opacity) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.appearance_bottom_nav_opacity_title),
                modifier = Modifier.weight(1f),
            )
            Text("${(live * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.appearance_bottom_nav_opacity_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        BottomNavOpacityPreview(opacity = live)
        AppSlider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(live) },
            valueRange = 0.5f..1f,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A1 实时预览：示意内容（两行带色头像）+ 悬浮胶囊栏（按当前不透明度），让滑块拖动即时可见「内容透到栏后」效果。 */
@Composable
private fun BottomNavOpacityPreview(opacity: Float) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(AppShapes.large)
            .background(colors.surface.base),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewContentRow(Color(0xFFFF9500))
            PreviewContentRow(Color(0xFF30B0C7))
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clip(AppShapes.full)
                .background(colors.surface.raised.copy(alpha = opacity))
                .border(0.5.dp, colors.surface.stroke, AppShapes.full)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewTab(selected = true)
            PreviewTab(selected = false)
            PreviewTab(selected = false)
            PreviewTab(selected = false)
        }
    }
}

@Composable
private fun PreviewContentRow(avatarColor: Color) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(avatarColor))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.height(9.dp).width(96.dp).clip(AppShapes.small).background(colors.text.primary.copy(alpha = 0.78f)))
            Box(Modifier.height(8.dp).width(168.dp).clip(AppShapes.small).background(colors.text.primary.copy(alpha = 0.30f)))
        }
    }
}

@Composable
private fun PreviewTab(selected: Boolean) {
    val colors = AppTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(width = 44.dp, height = 26.dp),
    ) {
        if (selected) {
            Box(Modifier.size(width = 44.dp, height = 26.dp).clip(AppShapes.medium).background(colors.accent.primary.copy(alpha = 0.16f)))
        }
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (selected) colors.accent.text else colors.text.secondary))
    }
}

@Composable
private fun ModeRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppRadio(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * 主题配色选项卡（两色样：底 + 强调叠放 + 名 + 选中勾）。选中=2dp 强调描边 + 勾；未选=0.5dp 表面分隔。
 * a11y=selectable Role.RadioButton（外层 selectableGroup）。色样直接读对应 AppColors 浅档代表色，随主题色定义自动跟随。
 */
@Composable
private fun PaletteOptionCard(
    palette: ThemePalette,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val (swatchBase, swatchAccent) = palette.swatchColors()
    Row(
        modifier = modifier
            .clip(AppShapes.medium)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) colors.accent.text else colors.surface.stroke,
                shape = AppShapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 30.dp, height = 18.dp)) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(swatchBase)
                    .border(0.5.dp, colors.surface.stroke, CircleShape),
            )
            Box(Modifier.size(18.dp).align(Alignment.CenterEnd).clip(CircleShape).background(swatchAccent))
        }
        Text(
            stringResource(palette.labelRes()),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(18.dp))
        }
    }
}

/** 配色色样（底色 + 强调色·读对应 AppColors 浅档代表色，不复制色值）。 */
private fun ThemePalette.swatchColors(): Pair<Color, Color> = when (this) {
    ThemePalette.CLAY -> LightAppColors.surface.base to LightAppColors.accent.primary
    ThemePalette.QINGHUA -> QinghuaLightAppColors.surface.base to QinghuaLightAppColors.bubble.userEnd
}

/** 配色 → 显示文案资源。 */
internal fun ThemePalette.labelRes(): Int = when (this) {
    ThemePalette.CLAY -> R.string.appearance_palette_clay
    ThemePalette.QINGHUA -> R.string.appearance_palette_qinghua
}

/** 深浅模式 → 显示文案资源（与 iOS `AppearanceMode.displayName` 对齐）。 */
internal fun AppearanceMode.labelRes(): Int = when (this) {
    AppearanceMode.SYSTEM -> R.string.appearance_mode_system
    AppearanceMode.LIGHT -> R.string.appearance_mode_light
    AppearanceMode.DARK -> R.string.appearance_mode_dark
}
