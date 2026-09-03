package com.situ.aichat.ui.character

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.CompositionLocalProvider
import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷一《人设编译器》T2-4（图纸 §7.2 · Y-E15–Y-E18）：手写新增专属项的**三护栏**。
 *
 * 断言从图纸 §4.3 的 D-10 三护栏独立反推：
 * - 护栏 2 语义查重：与 27 项标签或已有专属项重名 ⇒ 提示 + **提交键禁用**（Y-E15）
 * - 护栏 3 上限：满 10 项 ⇒ 触发行点不开 + 上限提示（Y-E16）
 * - 输入上限 12 字：**超出不接收**（Y-E17）
 * - 去空白后为空 ⇒ 提交键禁用（Y-E18）
 *
 * `@Config(qualifiers = "w411dp-h891dp")`：Robolectric 默认屏只有 320×470，长表单里的按钮会被推出可视区
 * 而 `performClick` **静默不命中**（记忆 `reference-robolectric-screen-size-fake-green`）。
 */
@RunWith(RobolectricTestRunner::class)
// zh-rCN：断言用的是图纸锁定的中文文案，Robolectric 默认 locale 是 en（会拿到英文资源）。
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class PersonaGainsEditTest {

    @get:Rule val compose = createComposeRule()

    private var latest: PersonaGains? = null
    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun setContent(initial: PersonaGains) {
        latest = null
        compose.setContent {
            AIPocketChatTheme {
                CompositionLocalProvider(LocalAppHaptics provides haptics) {
                    androidx.compose.foundation.layout.Column {
                        PersonaGainsSection(gains = initial, onChange = { latest = it })
                    }
                }
            }
        }
    }

    private fun addTrigger() = compose.onNodeWithText("添加一件她在意的事")
    private fun confirm() = compose.onNodeWithText("添加")
    private fun field() = compose.onNodeWithText("例：被叫全名")

    @Test
    fun duplicateOfSystemLabel_showsHintAndBlocksSubmit() {
        setContent(PersonaGains())
        addTrigger().performClick()
        field().performTextInput("被夸奖肯定") // = g04 的标签

        compose.onNodeWithText("这条和「被夸奖肯定」重了。").assertExists()
        confirm().assertIsNotEnabled()
        assertNull("查重命中期间一个字都不该写回去", latest)
    }

    @Test
    fun duplicateOfExistingCustom_alsoBlocks() {
        setContent(PersonaGains(custom = listOf(CustomGain(id = "u1", label = "被叫全名"))))
        addTrigger().performClick()
        field().performTextInput("  被叫全名  ") // 去空白后同名

        compose.onNodeWithText("这条和「被叫全名」重了。").assertExists()
        confirm().assertIsNotEnabled()
    }

    @Test
    fun uniqueLabel_submitsAsManualSensitive() {
        setContent(PersonaGains())
        addTrigger().performClick()
        field().performTextInput("被打断说话")
        confirm().assertIsEnabled()
        confirm().performClick()

        val written = requireNotNull(latest).custom.single()
        assertEquals("被打断说话", written.label)
        assertEquals("D-10：手写新项默认「很敏感」", PersonaVocab.LEVEL_SENSITIVE, written.level)
        assertEquals(CustomGain.ORIGIN_MANUAL, written.origin)
        assertTrue("必须带 id，否则删除 / 改档位认不出行", written.id.isNotEmpty())
    }

    @Test
    fun blankLabel_keepsSubmitDisabled() {
        setContent(PersonaGains())
        addTrigger().performClick()
        confirm().assertIsNotEnabled() // 一个字没输
        field().performTextInput("   ")
        confirm().assertIsNotEnabled() // 只有空白
        assertNull(latest)
    }

    @Test
    fun labelLongerThanTwelve_isNotAccepted() {
        setContent(PersonaGains())
        addTrigger().performClick()
        field().performTextInput("一二三四五六七八九十十一十二十三") // 16 字

        // 超 12 字**不接收**：输入框里应当一个字都没进（组件在 onValueChange 处挡掉整次输入）。
        compose.onNodeWithText("例：被叫全名").assertExists()
        confirm().assertIsNotEnabled()
    }

    @Test
    fun exactlyTwelveChars_isAccepted() {
        setContent(PersonaGains())
        addTrigger().performClick()
        field().performTextInput("一二三四五六七八九十十一") // 恰 12 字
        confirm().assertIsEnabled()
        confirm().performClick()

        assertEquals(12, requireNotNull(latest).custom.single().label.length)
    }

    @Test
    fun tenCustomItems_greyOutTriggerAndShowCapHint() {
        val full = PersonaGains(custom = (1..PersonaGains.MAX_CUSTOM).map { CustomGain(id = "u$it", label = "专属$it") })
        setContent(full)

        compose.onNodeWithText("最多 10 项，删掉一条再加。").assertExists()
        addTrigger().performClick() // 置灰 ⇒ 点不开
        compose.onNodeWithText("例：被叫全名").assertDoesNotExist()
        assertNull(latest)
    }

    @Test
    fun belowCap_showsRemainingCount() {
        setContent(PersonaGains(custom = listOf(CustomGain(id = "u1", label = "被叫全名"))))
        // 可观察性提示 + 剩余额度拼在同一行（9 = 10 − 1）。
        compose.onNodeWithText("还能加 9 项。", substring = true).assertExists()
    }

    @Test
    fun summaryLine_countsDifferingSystemItemsAndCustom() {
        setContent(
            PersonaGains(
                system = mapOf("g02" to PersonaVocab.LEVEL_SENSITIVE, "g04" to PersonaVocab.LEVEL_NUMB),
                custom = listOf(CustomGain(id = "u1", label = "被叫全名")),
            ),
        )
        compose.onNodeWithText("系统 27 项里她有 2 项与常人不同，另外从人设里读出 1 项是她专属的。").assertExists()
    }

    @Test
    fun noCustomItems_dropsSecondHalfOfSummary() {
        setContent(PersonaGains(system = mapOf("g02" to PersonaVocab.LEVEL_SENSITIVE)))
        compose.onNodeWithText("系统 27 项里她有 1 项与常人不同。").assertExists()
    }

    @Test
    fun systemSection_collapsesNormalItemsByDefault() {
        setContent(PersonaGains(system = mapOf("g02" to PersonaVocab.LEVEL_SENSITIVE)))

        compose.onNodeWithText("被冷落 · 已读不回").assertExists()   // 档位 ≠ 正常 ⇒ 默认展开
        compose.onNodeWithText("被关心问候").assertDoesNotExist()     // 正常 ⇒ 默认折叠
        compose.onNodeWithText("展开其余 26 项（均为「正常」）").assertExists()
    }

    @Test
    fun expandingSystemSection_revealsAllTwentySeven() {
        setContent(PersonaGains(system = mapOf("g02" to PersonaVocab.LEVEL_SENSITIVE)))
        compose.onNodeWithText("展开其余 26 项（均为「正常」）").performClick()

        compose.onNodeWithText("被关心问候").assertExists()
        compose.onNodeWithText("被抛弃的信号").assertExists()
        compose.onNodeWithText("收起").assertExists()
    }
}
