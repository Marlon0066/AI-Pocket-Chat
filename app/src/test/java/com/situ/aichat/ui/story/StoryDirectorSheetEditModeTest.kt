package com.situ.aichat.ui.story

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 导演台编辑模式分派 T2（图纸 2026-08-06「已存走向」§7 T2-5·边界 E4/E5/E11）。
 *
 * 钉的是 §4.4 的**保存路由**：栏 A 非空且真改过时，`directionCommitted` 决定走覆盖写口还是既有创建路——
 * 这条分派是本卷根治「submitChoice 静默丢弃」的落点，走错一侧就是 bug 原样复发。
 * 另钉撤回二段式（点撤回 → 确认弹窗 → 回调）与「没改就不写库」。
 */
// ⚠️ qualifiers 不是装饰：Robolectric 默认屏只有 320×470dp，面板内容（两栏 textarea + 按钮排）在
// verticalScroll 里被推出可视区 ⇒ 保存/撤回钮的 performClick **静默不命中**（测试不报错、回调不发 = 假绿）。
// 给一块真机尺寸的屏（411×891dp）后整面板装得下，交互才真的落到按钮上。〔本卷施工实测·probe 出的 3 个 root 窗口〕
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class StoryDirectorSheetEditModeTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    /** 栏 A 的输入框（面板里两个 textarea 按出现序：0 = 剧情走向、1 = 本章节拍）。 */
    private fun flowField() = compose.onAllNodes(hasSetTextAction())[0]

    private var submitted: String? = null
    private var overwritten: String? = null
    private var withdrawCount = 0
    private var dismissCount = 0

    private fun flowLabel() = app.getString(R.string.story_director_flow_label)
    private fun saveText() = app.getString(R.string.action_save)
    private fun withdrawText() = app.getString(R.string.story_director_withdraw)
    private fun tagText() = app.getString(R.string.story_continue_direction_tag)
    private fun savedHint() = app.getString(R.string.story_director_flow_saved_hint)

    private fun setSheet(savedDirection: String?, directionCommitted: Boolean) {
        compose.setContent {
            // AppButton 读 LocalAppHaptics（无提供者即抛），面板里有三枚 → 必须注入。
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                StoryDirectorSheet(
                    beats = "AI 预排的节拍",
                    beatsUserEdited = false,
                    savedDirection = savedDirection,
                    directionCommitted = directionCommitted,
                    onSubmitFlow = { submitted = it },
                    onOverwriteDirection = { overwritten = it },
                    onWithdrawDirection = { withdrawCount++ },
                    onSaveBeats = {},
                    onRestoreAiBeats = {},
                    onDismiss = { dismissCount++ },
                )
            }
        }
    }

    private fun assertAbsent(text: String, why: String) = assertTrue(
        why,
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty(),
    )

    // ── 编辑模式：预填 + tag + hint + 覆盖路由 ──

    @Test
    fun 编辑模式_预填已存走向并挂tag与覆盖提示() {
        setSheet(savedDirection = "旧走向：让她先回家", directionCommitted = true)

        compose.onNodeWithText("旧走向：让她先回家").assertIsDisplayed()
        compose.onNodeWithText(tagText()).assertIsDisplayed()
        compose.onNodeWithText(savedHint()).assertIsDisplayed()
    }

    /**
     * 编辑模式核心分派：改了文本再保存 → **只走覆盖写口**，创建路（submitChoice）一次都不许被调用
     * ——走错这一侧就等于 bug 原样复发（submitChoice 见 userChoice 非空即 return，字被静默吃掉）。
     */
    @Test
    fun 编辑模式_改文本保存_走覆盖写口且创建路零调用() {
        setSheet(savedDirection = "旧走向", directionCommitted = true)

        flowField().performTextClearance()
        flowField().performTextInput("  新走向：留在旅馆  ")
        compose.onNodeWithText(saveText()).performClick()

        assertEquals("覆盖写口收到 trim 后的新文本", "新走向：留在旅馆", overwritten)
        assertNull("创建路一次都不许被调用", submitted)
        assertEquals("保存后关面板", 1, dismissCount)
    }

    /**
     * E5 哨兵态（生成失败残留「（让故事自然发展）」）：`savedDirection = null` 但 `directionCommitted = true`
     * ——栏 A 预填空、无 tag、无撤回钮，但非空保存仍**路由到覆盖写口**。
     * 这是关掉静默丢弃最后一个洞的地方：走创建路的话，submitChoice 见哨兵非空照样 return。
     */
    @Test
    fun E5_哨兵态_预填空且无tag无撤回_但保存走覆盖写口() {
        setSheet(savedDirection = null, directionCommitted = true)

        assertAbsent(tagText(), "哨兵不是用户写的走向，不挂「已保存 · 待生成」")
        assertAbsent(withdrawText(), "哨兵态不给撤回（撤 SKIP 会造出选择重开 + 结局意图仍挂的幽灵组合）")
        assertAbsent(savedHint(), "没有已存走向文本，覆盖提示无从谈起")

        flowField().performTextInput("哨兵态下写的走向")
        compose.onNodeWithText(saveText()).performClick()

        assertEquals("哨兵态保存必须走覆盖写口", "哨兵态下写的走向", overwritten)
        assertNull("否则 submitChoice 见哨兵非空照样 return = 洞还在", submitted)
    }

    // ── 创建模式回归钉（态 A 逐字节不变）──

    @Test
    fun 创建模式_保存走既有submitChoice创建路_覆盖写口零调用() {
        setSheet(savedDirection = null, directionCommitted = false)

        compose.onNodeWithText(flowLabel()).assertIsDisplayed()
        assertAbsent(tagText(), "没答过时不挂 tag")
        assertAbsent(withdrawText(), "没答过时没有可撤回的东西")

        flowField().performTextInput("  第一次写的走向  ")
        compose.onNodeWithText(saveText()).performClick()

        assertEquals("第一次写的走向", submitted)
        assertNull("创建路不许误走覆盖写口", overwritten)
    }

    // ── 撤回二段式 ──

    @Test
    fun 撤回_点按钮先弹确认_确认后才发撤回回调() {
        setSheet(savedDirection = "要撤掉的走向", directionCommitted = true)

        compose.onNodeWithText(withdrawText()).performClick()
        // 一点即永久删掉手写文本 → 必须先过 Danger 确认闸。
        compose.onNodeWithText(app.getString(R.string.story_director_withdraw_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.story_director_withdraw_body)).assertIsDisplayed()
        assertEquals("确认之前一次都不许发撤回", 0, withdrawCount)

        compose.onNodeWithText(app.getString(R.string.story_director_withdraw_confirm)).performClick()

        assertEquals(1, withdrawCount)
        assertEquals("撤回后关面板", 1, dismissCount)
    }

    @Test
    fun 撤回_确认弹窗点继续编辑_不发撤回也不关面板() {
        setSheet(savedDirection = "要撤掉的走向", directionCommitted = true)

        compose.onNodeWithText(withdrawText()).performClick()
        compose.onNodeWithText(app.getString(R.string.story_field_discard_no)).performClick()

        assertEquals("取消 = 什么都没发生", 0, withdrawCount)
        assertEquals(0, dismissCount)
    }

    // ── E11 没改就不写库 ──

    /**
     * E11：编辑模式下原样不动直接保存 → 栏 A **一个字节都不写库**，只关面板。
     *
     * ⚠️ 「两个回调都是 null」这种全否定断言，在点击根本没落到按钮上时**照样绿**（假绿）——
     * 故每条都配一枚正向证据 `dismissCount == 1`：它只有 save() 真跑过才会变。
     */
    @Test
    fun E11_原样保存_栏A零写库() {
        setSheet(savedDirection = "原样走向", directionCommitted = true)

        compose.onNodeWithText(saveText()).performClick()

        assertEquals("正向证据：保存确实点到了（否则下面两条全否定断言是假绿）", 1, dismissCount)
        assertNull("没改过 → 覆盖写口零调用", overwritten)
        assertNull("更不许误走创建路", submitted)
    }

    /** E11 另一半：清空栏 A 再保存 → 同样零写库（清空 ≠ 撤回，hint 已指路撤回按钮）。 */
    @Test
    fun E11_清空后保存_栏A零写库() {
        setSheet(savedDirection = "原样走向", directionCommitted = true)

        flowField().performTextClearance()
        compose.onNodeWithText(saveText()).performClick()

        assertEquals("正向证据：保存确实点到了", 1, dismissCount)
        assertNull("清空 ≠ 撤回：栏 A 不写库", overwritten)
        assertNull(submitted)
    }
}
