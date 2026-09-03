package com.situ.aichat.ui.ourdays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OurDayCalendarRow
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
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * T3-1（卷三图纸 §7.2·Compose Robolectric·真机尺寸防假绿·zh-rCN 资源）：邻月 / 未来格无 clickable、今天格 stateDescription 含「今天」、
 * 选中格 `selected`、单角色「全部」chip 不存在（E3 / E7 / E8）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class OurDaysMonthGridSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val today = LocalDate.of(2026, 9, 15)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalAppHaptics provides haptics) { block() } }
    }

    private fun row(key: String) = OurDayCalendarRow(
        uuid = key, characterUuid = "c1", dayKey = key, factsJson = "", messageCount = 3, callSeconds = 0, hasMeeting = true, hasRelation = false,
        hasLife = false, note = "手记", factLine = "", noteStatus = "ok", noteAttempts = 0, noteEdited = false, hiddenFromMemory = false,
        deleted = false, generatedAt = null, createdAtMillis = 1, updatedAtMillis = 1,
    )

    private fun month(selected: LocalDate = today) = OurDaysCalendarLogic.buildMonth(
        anchor = today, rows = listOf(row("2026-09-14")), today = today, weekFields = WeekFields.of(Locale.SIMPLIFIED_CHINESE),
        locale = Locale.SIMPLIFIED_CHINESE, allMode = false, characterUuids = listOf("c1"), decor = { DayDecor("装", false, null) },
        selected = selected, card = { d, rows -> OurDayCardLogic.card(d, today, rows.firstOrNull(), null) },
    )

    private fun stateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test
    fun 邻月格与未来格无clickable_期内过去格可点() {
        var selected: LocalDate? = null
        content { OurDaysMonthView(month(), periodContainsToday = true, allMode = false, characterUuid = "c1", characterName = "林晚", onSelectDate = { selected = it }, onShift = {}, onToday = {}, onOpenDay = { _, _ -> }) }
        compose.onNode(hasContentDescription("8 月 31 日")).assertHasNoClickAction()
        compose.onNode(hasContentDescription("9 月 16 日")).assertHasNoClickAction()
        compose.onNode(hasContentDescription("9 月 14 日，有记录")).assertHasClickAction().performClick()
        assertEquals(LocalDate.of(2026, 9, 14), selected)
    }

    @Test
    fun 今天格stateDescription含今天_且已选中() {
        content { OurDaysMonthView(month(), true, false, "c1", "林晚", {}, {}, {}, { _, _ -> }) }
        compose.onNode(hasContentDescription("9 月 15 日，今天，已选中")).assertIsSelected()
        compose.onNode(stateDescription("今天，已选中")).assertIsSelected()
    }

    @Test
    fun 选中格selected_其余不选中() {
        content { OurDaysMonthView(month(selected = LocalDate.of(2026, 9, 14)), true, false, "c1", "林晚", {}, {}, {}, { _, _ -> }) }
        compose.onNode(hasContentDescription("9 月 14 日，有记录，已选中")).assertIsSelected()
        compose.onNode(hasContentDescription("9 月 15 日，今天")).assertIsNotSelected()
    }

    @Test
    fun 单角色无全部chip_两角色有() {
        val one = listOf(CharacterEntity(uuid = "c1", name = "林晚", creationDate = 1))
        content { OurDaysCharacterRow(one, OurDaysSelection.Character("c1"), onSelect = {}) }
        compose.onNodeWithText("全部").assertDoesNotExist()
        compose.onNodeWithText("林晚").assertIsSelected()
    }

    @Test
    fun 两角色出全部chip_点击回调All() {
        val two = listOf(CharacterEntity(uuid = "c1", name = "林晚", creationDate = 1), CharacterEntity(uuid = "c2", name = "阿棠", creationDate = 2))
        var picked: OurDaysSelection? = null
        content { OurDaysCharacterRow(two, OurDaysSelection.Character("c1"), onSelect = { picked = it }) }
        compose.onNodeWithText("全部").assertIsNotSelected().performClick()
        assertEquals(OurDaysSelection.All, picked)
    }
}
