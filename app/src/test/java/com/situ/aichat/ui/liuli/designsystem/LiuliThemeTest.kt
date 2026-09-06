package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1：琉璃 token 落值钉死（契约 FABLE5_THEME_LIULI_PROPOSAL.md §3.3 形状表 / §4.1 玻璃上文字色·
 * 图纸 2026-09-04-琉璃第二张脸-卷一 §3.6 逐字）。
 *
 * 断言从**规格**独立反推（值抄自契约表格，不是抄实现输出）：形状阶改一格 = 器型走样，onGlass 改一色 =
 * 玻璃上的字失去对比度保障，两者都是静帧截图看不出的偏离。
 */
class LiuliThemeTest {

    private val density = Density(density = 1f)

    /** 在 1000×1000 的形状上求角半径 px（density=1 → px 数 = dp 数；percent 档按短边比例算）。 */
    private fun corner(size: CornerSize): Float = size.toPx(Size(1000f, 1000f), density)

    @Test fun shapeRadii_matchContract() {
        assertEquals(10f, corner(LiuliShapes.small.topStart), 0.001f)
        assertEquals(20f, corner(LiuliShapes.medium.topStart), 0.001f)
        assertEquals(18f, corner(LiuliShapes.bubble.topStart), 0.001f)
        assertEquals(20f, corner(LiuliShapes.overlay.topStart), 0.001f)
        assertEquals(5.dp, LiuliShapes.bubbleTailCorner)
    }

    @Test fun sheetShape_is38TopAnd0Bottom() {
        assertEquals(38f, corner(LiuliShapes.sheet.topStart), 0.001f)
        assertEquals(38f, corner(LiuliShapes.sheet.topEnd), 0.001f)
        assertEquals(0f, corner(LiuliShapes.sheet.bottomStart), 0.001f)
        assertEquals(0f, corner(LiuliShapes.sheet.bottomEnd), 0.001f)
    }

    @Test fun pillShape_isHalfOfShortestSide() {
        // percent = 50：1000×1000 上 = 500。
        assertEquals(500f, corner(LiuliShapes.pill.topStart), 0.001f)
    }

    @Test fun onGlassColors_matchContract() {
        assertEquals(Color(0xFF111318), LiuliOnGlassLight.primary)
        assertEquals(Color(0xFF5F6470), LiuliOnGlassLight.secondary)
        assertEquals(Color(0xFFF2F4F8), LiuliOnGlassDark.primary)
        assertEquals(Color(0xFFA3A9B5), LiuliOnGlassDark.secondary)
    }
}
