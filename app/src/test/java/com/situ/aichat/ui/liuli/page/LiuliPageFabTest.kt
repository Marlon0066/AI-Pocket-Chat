package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：页壳的 FAB 槽（图纸 2026-09-06 卷五 A-4 ⑦·§8 C0「三处 add-only」）。
 *
 * **加法零回归**的钉：不传 `fab` 时页壳上一个新节点都不多；传了才出、落在右下角、点得动、触达 48。
 * 「贴右下角」量的是真件边框（`getUnclippedBoundsInRoot`），不回读构造参数（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliPageFabTest {

    @get:Rule
    val compose = createComposeRule()

    private var fabTaps = 0

    private fun page(withFab: Boolean) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliPage(
                        title = "提示词模块",
                        onBack = {},
                        collapsed = false,
                        fab = if (withFab) {
                            {
                                LiuliCircleButton(
                                    onClick = { fabTaps++ },
                                    contentDescription = "新建模块",
                                    size = LiuliPageGeometry.fab,
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(LiuliPageGeometry.fabIcon),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow),
                        ) {
                            item { Box { Text("正文") } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 不传fab时一个节点都不多() {
        page(withFab = false)
        assertEquals(0, compose.onAllNodesWithContentDescription("新建模块").fetchSemanticsNodes().size)
        compose.onNodeWithText("正文").assertExists()
    }

    @Test fun 传fab时点得动且恰一次() {
        page(withFab = true)
        compose.onNodeWithContentDescription("新建模块").performClick()
        compose.waitForIdle()
        assertEquals(1, fabTaps)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun fab贴右下角且触达48() {
        page(withFab = true)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val fab = compose.onNodeWithContentDescription("新建模块").getUnclippedBoundsInRoot()
        // Robolectric 下导航栏 inset 恒 0，故右缘 = gutter 20、底缘 = fabBottom 24 是纯算式可验的那半。
        assertEquals(LiuliPageGeometry.gutter.value, (root.right - fab.right).value, 0.5f)
        assertEquals(LiuliPageGeometry.fabBottom.value, (root.bottom - fab.bottom).value, 0.5f)
        assertTrue("视觉直径应是 56", (fab.bottom - fab.top).value >= LiuliPageGeometry.fab.value - 0.5f)
        compose.onNodeWithContentDescription("新建模块").assertTouchHeightIsEqualTo(LiuliPageGeometry.fab)
    }
}