package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.character.ProfileTab
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.awaitCancellation

/**
 * T2：详情页三件套（图纸 2026-09-06 卷四 §8 C4a · A-8 头图 / A-9 动作排 / A-10 统计卡 / A-11 分段条）。
 *
 * 钉：无头像走 monogram、有路径但没解出来时**只渐变不闪字**（E10）；关系胶囊与副行文案；
 * 统计卡列数随数据增减（E11）且值不带单位；动作排四钮各回各的；分段条选中语义与切段回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliProfileKitTest {

    @get:Rule
    val compose = createComposeRule()

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    // ── 头图 ────────────────────────────────────────────────────────────────
    @Test fun 没设过头像时画首字monogram() {
        host {
            LiuliHeroHeader(name = "林晚", avatarPath = null, relationshipLabel = "恋人", subtitle = "24 岁 · 插画师")
        }
        compose.onNodeWithText("林").assertExists()
        compose.onNodeWithText("林晚").assertExists()
        compose.onNodeWithText("恋人").assertExists()
        compose.onNodeWithText("24 岁 · 插画师").assertExists()
    }

    @Test fun 有头像路径但还没解出来时不闪首字() {
        host {
            // 注入一个永远解不完的加载器 = 真「加载中」（复核 R1 🟡-7：原例拿坏路径冒充加载中）。
            LiuliHeroHeader(
                name = "林晚",
                avatarPath = "/some/photo.png",
                relationshipLabel = "初识",
                subtitle = "",
                loadAvatar = { awaitCancellation() },
            )
        }
        // 只留渐变底：monogram 那一枚「林」不该出现（名字里的「林」是另一个节点，故按恰一次数）。
        compose.onAllNodesWithText("林").assertCountEquals(0)
        compose.onNodeWithText("林晚").assertExists()
    }

    /** E10 第三态（复核 R1 🟡-7）：路径有但解不出来（文件没了 / 坏图 → 加载器给 null）→ 回落 monogram，不能永远留空渐变。 */
    @Test fun 头像路径解不出来时回落首字() {
        host {
            LiuliHeroHeader(
                name = "林晚",
                avatarPath = "/not/there.png",
                relationshipLabel = "初识",
                subtitle = "",
                loadAvatar = { null },
            )
        }
        compose.onAllNodesWithText("林").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 头图恒280高() {
        host {
            LiuliHeroHeader(
                name = "林晚",
                avatarPath = null,
                relationshipLabel = "恋人",
                subtitle = "",
                modifier = Modifier.testTag("hero"),
            )
        }
        val hero = compose.onNodeWithTag("hero").getUnclippedBoundsInRoot()
        assertEquals(280f, (hero.bottom - hero.top).value, 0.01f)
    }

    // ── 统计卡 ──────────────────────────────────────────────────────────────
    @Test fun 统计卡见面与连续为零时不占列() {
        host { LiuliStatCard(listOf("相识" to "128", "消息" to "3412", "记忆" to "86")) }
        listOf("相识", "消息", "记忆").forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertExists() }
        compose.onNodeWithText("见面", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("连续", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun 统计卡五列时值不带单位() {
        host {
            LiuliStatCard(
                listOf("相识" to "128", "消息" to "3412", "记忆" to "86", "见面" to "3", "连续" to "🔥12"),
            )
        }
        // 值就是暖陶 StatsBar 的同一串字：128 而不是「128 天」。
        compose.onNodeWithText("128", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("🔥12", useUnmergedTree = true).assertExists()
    }

    @Test fun 统计卡空表不画() {
        host { LiuliStatCard(emptyList()) }
        compose.onNodeWithText("相识", useUnmergedTree = true).assertDoesNotExist()
    }

    // ── 动作排 ──────────────────────────────────────────────────────────────
    @Test fun 动作排四钮各回各的() {
        val taps = mutableListOf<String>()
        host {
            LiuliActionRow(
                listOf("今日行程", "我们的约定", "我们的日子", "记忆星空").map { label ->
                    LiuliActionItem(Icons.Filled.CalendarMonth, label, label) { taps += label }
                },
            )
        }
        listOf("今日行程", "我们的约定", "我们的日子", "记忆星空").forEach {
            compose.onNodeWithContentDescription(it).performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf("今日行程", "我们的约定", "我们的日子", "记忆星空"), taps)
    }

    // ── 分段条 ──────────────────────────────────────────────────────────────
    @Test fun 分段条三段选中语义与切段回调() {
        val tab = mutableStateOf(ProfileTab.NEAR)
        host { LiuliTabStrip(selected = tab.value, onSelect = { tab.value = it }) }
        compose.onNodeWithText("近况").assertIsSelected()
        compose.onNodeWithText("经历").performClick()
        compose.waitForIdle()
        assertEquals(ProfileTab.STORY, tab.value)
        compose.onNodeWithText("经历").assertIsSelected()
    }
}
