package com.situ.aichat.ui.designsystem

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-G：**屏 gutter 视觉边缘几何断言**（间距规范落地 图纸二 §4.3）。
 *
 * 防的是这一批修的那个 bug 本身：贴屏边的槽被写成「同一个 padding 数字」，而不同形态的组件
 * 内部补偿不同，于是视觉上参差（编辑页门楣右边的「保存」钮曾贴到距屏边只有 4dp）。
 *
 * **期望值一律从规格反推、绝不从实现读**——军规（设计语言 §2.5）说**屏 gutter 视觉边缘恒 20dp**，
 * 每例注释写清「视觉边缘 = 断言值 + 补偿 = 20dp」这条算式。容差 ±1dp（抗锯齿与布局取整）。
 *
 * **量到的是哪个盒子**（机械事实，非规格）：
 * - [AppTopBarAction] 的 `minimumInteractiveComponentSize()` 在**最外层**、语义挂在 `.size(40.dp)` 之内
 *   → 测得的是 **40dp 视觉盒**，故断言值直接就是视觉边缘 20dp（触达外溢 4dp 在盒外）。
 * - [AppButton] 的 `.clickable` 在 min-size **之外** → 测得的是撑开后的**触达盒**，断言值 = 布局 padding。
 *
 * ⚠️ **Robolectric 字形宽失真**（PITFALLS §1e）：中文字宽被压到近 0，两字钮的自然宽 < 48dp 会被
 * `minimumInteractiveComponentSize()` 撑开并把内容**居中** → 「取消」的**文字左缘**在这里量不到真值
 * （真机 ≈ 20dp）。故 G3 只钉**钮盒左缘 = 8dp**（= [AppSpacing.gutterForTextButton]），文字左缘那一段
 * 由装机像素审计兜底（图纸二 §7 / §11 D-4）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class GutterAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    /** 军规落值：屏 gutter 视觉边缘恒 20dp（设计语言 §2.5.2）。**测试里重新打字，不引实现常量。** */
    private val gutter = 20.dp

    /** [AppTopBarAction] 48dp 触达比 40dp 视觉每边外溢的量。 */
    private val roundButtonHalo = 4.dp

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    private fun assertDp(message: String, expected: Dp, actual: Dp) {
        assertEquals(message, expected.value.toDouble(), actual.value.toDouble(), 1.0)
    }

    private fun topBar() = content {
        AppTopBar(
            title = "标题",
            onBack = {},
            actions = {
                AppTopBarAction(icon = AppTopBarIcons.Add, contentDescription = "新建", onClick = {})
            },
        )
    }

    @Test
    fun G1_门楣返回钮视觉左缘落在gutter线上() {
        topBar()
        // 视觉边缘 = padding 16（gutterForRoundButton）+ 触达外溢 4 = 20dp；量的是 40dp 视觉盒故直接读 20。
        val back = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot()
        assertDp("返回钮视觉左缘必须落在 20dp 屏 gutter 线上（padding 回退成 12 会当场红）", gutter, back.left)
    }

    @Test
    fun G2_门楣actions槽视觉右缘落在gutter线上() {
        topBar()
        val rootRight = compose.onRoot().getUnclippedBoundsInRoot().right
        val action = compose.onNodeWithContentDescription("新建").getUnclippedBoundsInRoot()
        // 同 G1：40dp 视觉盒 → 距屏右缘的净距就是视觉边缘。
        assertDp("actions 槽视觉右缘必须落在 20dp 线上", gutter, rootRight - action.right)
    }

    private fun formBar() = content {
        AppFormBar(
            title = "编辑资料",
            onCancel = {},
            trailing = { AppButton(onClick = {}) { Text("保存") } },
        )
    }

    @Test
    fun G3_表单条左文字钮的布局padding是gutter减文字钮内边距() {
        formBar()
        // 视觉边缘 = 断言值 8 + Text 档自带横内边距 12 = 20dp（钮宽 ≥48dp 时成立·设计语言 §2.5.3）。
        // 量的是撑开后的触达盒，故读到的就是布局 padding 本身。
        val cancel = compose.onNode(hasClickAction() and hasText("取消")).getUnclippedBoundsInRoot()
        assertDp("左槽 padding 必须是 8dp（左右槽被写成同一个值会当场红）", 8.dp, cancel.left)
    }

    @Test
    fun G4_表单条右实心钮视觉右缘落在gutter线上() {
        formBar()
        val rootRight = compose.onRoot().getUnclippedBoundsInRoot().right
        val save = compose.onNode(hasClickAction() and hasText("保存")).getUnclippedBoundsInRoot()
        // 视觉边缘 = 断言值 20 + 实心钮补偿 0 = 20dp。**这一例就是本批 bug 的正主**：写回 4dp 必红。
        assertDp("右槽实心钮右缘必须落在 20dp 线上（这一批要修的就是它曾经只有 4dp）", gutter, rootRight - save.right)
    }

    private fun listHeader() = content {
        AppListScreenHeader(
            title = "聊天",
            actionIcon = AppTopBarIcons.Add,
            actionContentDescription = "新建",
            onAction = {},
        )
    }

    @Test
    fun G5_一级页页眉圆钮视觉右缘落在gutter线上() {
        listHeader()
        val rootRight = compose.onRoot().getUnclippedBoundsInRoot().right
        val action = compose.onNodeWithContentDescription("新建").getUnclippedBoundsInRoot()
        // 视觉边缘 = padding（edgeMargin 20 − halo 4 = 16）+ 外溢 4 = 20dp；量 40dp 视觉盒故直接读 20。
        assertDp("页眉圆钮视觉右缘必须落在 20dp 线上（edgeMargin 被改回 16 会当场红）", gutter, rootRight - action.right)
    }

    @Test
    fun G6_设置行标题左缘落在列表行起点线上() {
        content { AppSettingsRow(title = "外观", onClick = {}) }
        // 军规「列表行水平起点 16dp」；裸文字无补偿故 padding = 视觉边缘 = 16dp（18dp 孤值回潮会当场红）。
        // 整行 clickable 合并语义 → 必须走 unmerged 树才量得到文字自己那个盒子。
        val title = compose.onNodeWithText("外观", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp("设置行文字左缘必须落在 16dp 列表行起点上", 16.dp, title.left)
    }

    @Test
    fun G7_页眉band高由edgeMargin推导而非写死() {
        listHeader()
        val band = compose.onRoot().getUnclippedBoundsInRoot()
        // band 高 = edgeMargin×2 + 圆钮视觉 40 = 20×2 + 40 = 80dp（图纸二 B5·有意变化 V2）。
        // §9 机制锁：**不许把高度写死回 72**——那会让「三边等距」的设计意图断掉。
        assertDp("页眉 band 高必须 = gutter×2 + 40（写死 72 会当场红）", gutter * 2 + 40.dp, band.bottom - band.top)
    }

    @Test
    fun G8_纸条卡外边距落在gutter线上() {
        compose.setContent {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar(message = "已保存", duration = SnackbarDuration.Long) }
            Scaffold(snackbarHost = { AppSnackbarHost(host) }) { }
        }
        // 纸卡有底色 → 卡的左缘就是视觉边缘 = 20dp（原为 14dp 孤值）；卡内边距 14dp 是容器内间距、本卷不动，
        // 故文案左缘 = 20 + 14 = 34dp。量文案是因为卡本身没有语义节点。
        val text = compose.onNodeWithText("已保存").getUnclippedBoundsInRoot()
        assertDp("纸条卡外边距必须是 20dp（14dp 孤值回潮会当场红）", gutter + 14.dp, text.left)
    }

    @Test
    fun G10_一级页页眉大标题左缘与加号共用同一条gutter线() {
        listHeader()
        val rootRight = compose.onRoot().getUnclippedBoundsInRoot().right
        val action = compose.onNodeWithContentDescription("新建").getUnclippedBoundsInRoot()
        // 裸文字无补偿 → 字形左缘 = padding = 视觉边缘 = 20dp。合并语义在圆钮那边，标题自己是独立节点，
        // 但整行仍走 unmerged 取值最稳。**这一例防的是 D-1 回潮**：标题曾停在 16dp、与右侧加号的 20dp 不共线。
        val title = compose.onNodeWithText("聊天", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp("页眉大标题左缘必须落在 20dp 屏 gutter 线上", gutter, title.left)
        // 左右两端必须真的对称——只钉一边挡不住「一边 20 一边 16」。
        assertDp("大标题左缘与加号视觉右缘必须共用同一条 gutter 线", title.left, rootRight - action.right)
    }

    /** 圆钮触达外溢常量本身也钉一下——G1/G2/G5 的算式全靠它成立。 */
    @Test
    fun G9_圆钮触达盒比视觉盒每边外溢4dp() {
        topBar()
        val back = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot()
        assertDp("圆钮视觉直径必须是 40dp（48 触达 − 每边 4dp 外溢）", 48.dp - roundButtonHalo * 2, back.right - back.left)
    }
}
