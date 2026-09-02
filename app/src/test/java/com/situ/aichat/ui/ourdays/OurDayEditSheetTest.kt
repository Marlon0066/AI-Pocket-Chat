package com.situ.aichat.ui.ourdays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * T3-2（卷三图纸 §7.2·Compose Robolectric·真机尺寸防假绿）：空白 note 保存禁用（E19）；改动后关 ⇒ 放弃框（E20）；开关变更调回调。
 * 判据 `enabled = draft.isNotBlank() && isDirty()` 在 composable 体内每次求值（PITFALLS §1h）——本测正向证据 = 有改动时能点保存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OurDayEditSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val date = LocalDate.of(2026, 9, 14)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalAppHaptics provides haptics) { block() } }
    }

    @Test
    fun E19_空白note保存禁用_有改动可保存() {
        var saved = 0
        val draft = mutableStateOf("   ")
        content {
            OurDayEditSheetBody(date, draft = draft.value, draftHidden = false, isDirty = { true }, onDraftChange = { draft.value = it }, onHiddenChange = {}, onSave = { saved++ }, onDelete = {})
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(0, saved)

        compose.runOnIdle { draft.value = "改过" }
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(1, saved)
    }

    @Test
    fun 未改动时保存禁用() {
        content {
            OurDayEditSheetBody(date, draft = "原文", draftHidden = false, isDirty = { false }, onDraftChange = {}, onHiddenChange = {}, onSave = {}, onDelete = {})
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun E20_有改动时关闭弹放弃框_确认才关_继续编辑不关() {
        var closed = 0
        content {
            OurDayEditSheet(date, draft = "改过", draftHidden = false, isDirty = { true }, onDraftChange = {}, onHiddenChange = {}, onSave = {}, onDelete = {}, onClose = { closed++ })
        }
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        assertEquals(0, closed)
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        assertEquals(0, closed)
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun E20_无改动时关闭直接关() {
        var closed = 0
        content {
            OurDayEditSheet(date, draft = "原文", draftHidden = false, isDirty = { false }, onDraftChange = {}, onHiddenChange = {}, onSave = {}, onDelete = {}, onClose = { closed++ })
        }
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        assertEquals(1, closed)
    }

    @Test
    fun 开关变更调回调_删除钮调回调() {
        var hidden: Boolean? = null
        var deleted = 0
        content {
            OurDayEditSheetBody(date, draft = "原文", draftHidden = false, isDirty = { false }, onDraftChange = {}, onHiddenChange = { hidden = it }, onSave = {}, onDelete = { deleted++ })
        }
        compose.onNodeWithText("Keep this day from them").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        assertEquals(true, hidden)
        compose.onNodeWithText("Delete this page").performClick()
        assertEquals(1, deleted)
    }
}
