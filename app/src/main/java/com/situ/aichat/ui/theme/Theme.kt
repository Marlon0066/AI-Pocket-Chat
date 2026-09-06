package com.situ.aichat.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.designsystem.DarkAppColors
import com.situ.aichat.ui.designsystem.LightAppColors
import com.situ.aichat.ui.designsystem.LocalAppColors
import com.situ.aichat.ui.designsystem.LiuliDarkAppColors
import com.situ.aichat.ui.designsystem.LiuliLightAppColors
import com.situ.aichat.ui.designsystem.brandDarkColorScheme
import com.situ.aichat.ui.designsystem.brandLightColorScheme
import com.situ.aichat.ui.designsystem.brandLiuliDarkColorScheme
import com.situ.aichat.ui.designsystem.brandLiuliLightColorScheme
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalGlassTier

/**
 * 当前**已解析**的深色状态（含 AppearanceMode 强制「浅色/深色」覆盖，非仅系统深浅）。由 [AIPocketChatTheme]
 * provide。全屏自绘背景（宠物心情背景 / 线下见面 / 故事阅读器）应读它而非 `isSystemInDarkTheme()`——否则
 * 用户强制深色但手机浅色时这些背景仍按浅色画，与 App 其余部分深浅打架（P15.2 #26，对齐 iOS @Environment(colorScheme)）。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

// Fable-5 暖中性 + 陶土玫主强调（Phase 0 全局换装·从 designsystem token 烘焙·见 FABLE5_DESIGN_LANGUAGE.md §1）。
// 未迁移屏经此一夜换暖装；已迁移组件直接读 AppTheme.* token。
private val LightColors = brandLightColorScheme()
private val DarkColors = brandDarkColorScheme()
private val LiuliLightColors = brandLiuliLightColorScheme()
private val LiuliDarkColors = brandLiuliDarkColorScheme()

@Composable
fun AIPocketChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色默认翻转为品牌调色板（Monet 降为 opt-in·设计语言 §1.5）；管线/开关保留，沉没成本零。
    dynamicColor: Boolean = false,
    // 界面「脸」（暖陶默认 / 琉璃·与深浅正交·见 FABLE5_THEME_LIULI_PROPOSAL.md §7.1）。
    skin: AppSkin = AppSkin.CLAY,
    // 琉璃玻璃透明度档（只影响琉璃的玻璃片；暖陶下无消费者）。
    glassTier: GlassTier = GlassTier.CLEAR,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        skin == AppSkin.LIULI -> if (darkTheme) LiuliDarkColors else LiuliLightColors
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 状态栏/导航栏图标对比度随解析后的深浅同步（手动「深色/浅色」覆盖时也正确；
    // edgeToEdge 默认只跟系统深浅，这里按 App 内选择覆盖图标亮暗）。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // semantic 色 token 随「脸 × 深浅」provide（已迁移组件读 AppTheme.colors，不经 M3）。
    val appColors = when (skin) {
        AppSkin.LIULI -> if (darkTheme) LiuliDarkAppColors else LiuliLightAppColors
        AppSkin.CLAY -> if (darkTheme) DarkAppColors else LightAppColors
    }
    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalAppColors provides appColors,
        // 琉璃侧下发（卷二起 ui/liuli/** 的导航层选脸与玻璃片读它们）。
        LocalAppSkin provides skin,
        LocalGlassTier provides glassTier,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/** 从 Compose 的 context（可能是 ContextWrapper）解包出 Activity，拿不到返回 null。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
