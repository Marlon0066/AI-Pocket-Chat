package com.situ.aichat.ui.liuli.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-2：主页几何的三条派生算式（图纸 2026-09-06 卷三 §7 T1-2 · §4.7）。
 *
 * 金标**独立算**：数字在这里重新打一遍（不读实现常量相加），这样任何一处落值被悄悄改动都会红。
 */
class LiuliHomeGeometryTest {

    @Test fun 列表底留白等于底栏离屏底加栏高加呼吸() {
        // §4.7 ④：末条底 ≥ 底栏顶 + 12；底栏 = 离屏底 12 + 栏高 66。
        assertEquals((12 + 66 + 12).dp, LiuliHomeGeometry.listBottomInset)
    }

    @Test fun 分隔发丝起点等于屏gutter加头像加缝() {
        // §3.2「列表行」：发丝 0.5 从 86 起 = 20 + 54 + 12。
        assertEquals((20 + 54 + 12).dp, LiuliHomeGeometry.dividerInset)
    }

    @Test fun 透镜丸与槽的几何() {
        // 用户 09-06 拍板：丸 72×46 扁透镜、顶距 (66 − 46) / 2 = 10；槽仍 52（≥ 48 触达）、槽顶距 (66 − 52) / 2 = 7。
        assertEquals(72.dp, LiuliHomeGeometry.tabPillWidth)
        assertEquals(46.dp, LiuliHomeGeometry.tabPillHeight)
        assertEquals(10.dp, LiuliHomeGeometry.tabPillTop)
        assertEquals(52.dp, LiuliHomeGeometry.tabSlot)
        assertEquals(7.dp, LiuliHomeGeometry.tabBarVPad)
        assertEquals("槽必须 ≥ 48 触达", true, LiuliHomeGeometry.tabSlot >= 48.dp)
    }

    @Test fun 列表首行顶等于标题带加搜索槽各自的缝() {
        // §3.2「大标题带」：状态栏底 + 2 起 40 高 → 缝 12 → 搜索槽 38 → 缝 12 = 104。
        assertEquals((2 + 40 + 12).dp, LiuliHomeGeometry.searchTop)
        assertEquals((2 + 40 + 12 + 38 + 12).dp, LiuliHomeGeometry.listTop)
    }
}
