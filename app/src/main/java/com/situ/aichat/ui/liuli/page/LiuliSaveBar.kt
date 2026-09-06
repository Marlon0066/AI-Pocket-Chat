package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.glass.LiuliGlassSpec
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * T2 长表单的底部玻璃保存栏（契约 §6.5「底部保存栏（T2）」· 图纸 2026-09-06 卷五 A-9）。
 *
 * [LiuliPageGeometry.saveBar] 高 + 导航栏 · Panel 档 [liuliGlass]（**方角**·横贯到底）· 顶沿一道 0.5 发丝 ·
 * 内 [LiuliButton] [LiuliPageGeometry.saveBarButton] 高满宽、左右 [LiuliPageGeometry.gutter]。
 * 放进 [LiuliPage] 的 `bottomBar` 槽（它已经把栏钉在底部居中）。
 *
 * 只给长表单（角色编辑 / 故事创建 / 世界书条目 / 本卷的 API 编辑 / TTS / 过滤规则编辑 / 提示词模块编辑）；
 * 短表单不要这一栏（A-9）。
 */
@Composable
fun LiuliSaveBar(
    modifier: Modifier = Modifier,
    /**
     * 栏实高回报（含发丝 + 导航栏·卷五复核 R1 🔴 补）：栏是**最小** 56、内容会长高（导入预览的结果摘要 /
     * 错误行 / 进度条），列表底内距要按实高留，别拿名义 56 顶替。
     */
    onHeightChanged: ((Dp) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val hairline = if (dark) LiuliGlassSpec.hairlineDark else LiuliGlassSpec.hairlineLight
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onHeightChanged != null) {
                    Modifier.onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }) }
                } else {
                    Modifier
                },
            )
            // 方角玻璃：栏是横贯到底的一条，不是药丸（契约「Panel 档玻璃 rect」）。
            .liuliGlass(RectangleShape, dark = dark, style = LiuliGlassStyle.Panel),
    ) {
        Box(Modifier.fillMaxWidth().height(LiuliGlassSpec.hairlineWidth).background(hairline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // 最小 56（契约）·内容更高时随内容长（复核 R1：定高会把多行结果剪掉）。
                .heightIn(min = LiuliPageGeometry.saveBar)
                .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.saveBarPadV),
            horizontalArrangement = Arrangement.spacedBy(LiuliPageGeometry.saveBarGap),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 一枚主钮的常规保存栏（满宽 Prominent）。 */
@Composable
fun LiuliSaveBar(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LiuliSaveBar(modifier = modifier) {
        LiuliButton(
            onClick = onClick,
            style = LiuliButtonStyle.Prominent,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(LiuliPageGeometry.saveBarButton),
        ) {
            Text(text)
        }
    }
}

/** 保存栏在场时列表要多留的底内距（栏高 + 契约的 12·导航栏由调用方另加）。 */
val liuliSaveBarInset: androidx.compose.ui.unit.Dp
    @Composable get() = LiuliPageGeometry.saveBar + LiuliPageGeometry.saveBarGap
