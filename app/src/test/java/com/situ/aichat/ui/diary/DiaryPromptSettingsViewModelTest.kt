package com.situ.aichat.ui.diary

import android.os.Looper
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T2-2（图纸 §7·E13/E14/E15/E22）：写作规则屏 VM。断言从图纸 §3.5/§4.2 规格独立反推——
 * 播种（空设置 → 显示默认文案 / 字数 1000）、改文本落盘、等于默认存 ""、手动输入钳位、
 * 恢复默认四项一起还原、滑块拖动中不落盘（J-8）。
 *
 * MockK 假仓库 + Robolectric 主循环驱动 viewModelScope（照 `StoryGlobalSettingsViewModelTest` 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class DiaryPromptSettingsViewModelTest {

    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)

    private fun vm(settings: AppSettings = AppSettings()): DiaryPromptSettingsViewModel {
        coEvery { settingsRepo.getAppSettings() } returns settings
        val vm = DiaryPromptSettingsViewModel(RuntimeEnvironment.getApplication(), settingsRepo)
        idle()
        return vm
    }

    private fun idle() = repeat(20) { shadowOf(Looper.getMainLooper()).idle() }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    // ── E22 播种：空设置 → 四项显示默认（文本框里是默认文案原文，不是空白）──

    @Test fun `播种 - 未自定义时显示默认文案原文与字数 1000`() {
        val vm = vm()
        val mine = vm.state.value.mine
        assertEquals(1000, mine.wordCount)
        assertEquals("用第一人称（我）书写", mine.narrativePerson)
        assertTrue("文风播种默认文案原文", mine.styleHint.startsWith("这是只写给自己看的日记"))
        assertEquals("补充规则默认空（靠 placeholder 提示）", "", mine.extraRules)

        val ex = vm.state.value.exchange
        assertEquals(1000, ex.wordCount)
        assertEquals("用第一人称写你自己（我=你，绝不是替对方写）", ex.narrativePerson)
        // TA 的信文风：资源里的 %1$s 显示成 {用户名}（与占位替换同口径）。
        assertTrue("显示 {用户名} 而不是 %1\$s", ex.styleHint.contains("对 {用户名} 的在意"))
    }

    @Test fun `播种 - 已自定义时显示自定义值而非默认`() {
        val vm = vm(AppSettings(diaryWordCount = 1500, diaryStyleHint = "克制一点", diaryExtraRules = "别写天气"))
        assertEquals(1500, vm.state.value.mine.wordCount)
        assertEquals("克制一点", vm.state.value.mine.styleHint)
        assertEquals("别写天气", vm.state.value.mine.extraRules)
        // 没自定义的那项仍显示默认。
        assertEquals("用第一人称（我）书写", vm.state.value.mine.narrativePerson)
    }

    // ── E14 改文本 → 落盘；等于默认 → 存 "" ──

    @Test fun `改文本落盘；改回默认则落盘空串`() {
        val vm = vm()
        vm.onStyleHintChange(DiaryRuleSection.MINE, "克制一点，别抒情")
        await("文风落盘") { runCatching { coVerify { settingsRepo.setDiaryStyleHint("克制一点，别抒情") } }.isSuccess }
        assertEquals("克制一点，别抒情", vm.state.value.mine.styleHint)

        // 改回与默认逐字相同 → 存 ""（消费端据此回落默认文案）。
        val default = vm.defaults.mine.styleHint
        vm.onStyleHintChange(DiaryRuleSection.MINE, default)
        await("改回默认存空串") { runCatching { coVerify { settingsRepo.setDiaryStyleHint("") } }.isSuccess }

        // 两分区各写各的键，绝不串台。
        vm.onStyleHintChange(DiaryRuleSection.EXCHANGE, "别太黏")
        await("TA 的信文风落盘") { runCatching { coVerify { settingsRepo.setDiaryExchangeStyleHint("别太黏") } }.isSuccess }
        coVerify(exactly = 0) { settingsRepo.setDiaryStyleHint("别太黏") }
    }

    @Test fun `清空文本框存空串 - 生成时回落默认文案`() {
        val vm = vm(AppSettings(diaryNarrativePerson = "用「我」写"))
        vm.onNarrativePersonChange(DiaryRuleSection.MINE, "")
        await("清空落盘") { runCatching { coVerify { settingsRepo.setDiaryNarrativePerson("") } }.isSuccess }
        assertEquals("", vm.state.value.mine.narrativePerson)
    }

    // ── E13 手动输入钳位 / J-8 拖动中不落盘 ──

    @Test fun `手动输入 0 与 99999 钳到 50 与 5000`() {
        val vm = vm()
        vm.setWordCount(DiaryRuleSection.MINE, 0)
        await("下限钳位") { runCatching { coVerify { settingsRepo.setDiaryWordCount(50) } }.isSuccess }
        assertEquals(50, vm.state.value.mine.wordCount)

        vm.setWordCount(DiaryRuleSection.EXCHANGE, 99_999)
        await("上限钳位") { runCatching { coVerify { settingsRepo.setDiaryExchangeWordCount(5000) } }.isSuccess }
        assertEquals(5000, vm.state.value.exchange.wordCount)
    }

    @Test fun `拖动中只更本地态，松手才落盘`() {
        val vm = vm()
        vm.onWordCountDrag(DiaryRuleSection.MINE, 700)
        vm.onWordCountDrag(DiaryRuleSection.MINE, 1200)
        idle()
        assertEquals("本地态跟手", 1200, vm.state.value.mine.wordCount)
        coVerify(exactly = 0) { settingsRepo.setDiaryWordCount(any()) }

        vm.commitWordCount(DiaryRuleSection.MINE)
        await("松手落盘") { runCatching { coVerify { settingsRepo.setDiaryWordCount(1200) } }.isSuccess }
        // 拖动经过的中间值 700 从未落盘。
        coVerify(exactly = 0) { settingsRepo.setDiaryWordCount(700) }
    }

    // ── E15 恢复默认：四项一起还原 ──

    @Test fun `恢复默认 - 该分区四项一起还原且三个文本落盘空串`() {
        val vm = vm(
            AppSettings(
                diaryWordCount = 1800, diaryNarrativePerson = "用「我」写",
                diaryStyleHint = "克制", diaryExtraRules = "别写天气",
                diaryExchangeWordCount = 400, diaryExchangeStyleHint = "别太黏",
            ),
        )
        assertNotEquals(1000, vm.state.value.mine.wordCount)

        vm.resetSection(DiaryRuleSection.MINE)
        await("字数还原落盘") { runCatching { coVerify { settingsRepo.setDiaryWordCount(1000) } }.isSuccess }
        val mine = vm.state.value.mine
        assertEquals(1000, mine.wordCount)
        assertEquals(vm.defaults.mine.narrativePerson, mine.narrativePerson)
        assertEquals(vm.defaults.mine.styleHint, mine.styleHint)
        assertEquals("", mine.extraRules)
        coVerify { settingsRepo.setDiaryNarrativePerson("") }
        coVerify { settingsRepo.setDiaryStyleHint("") }
        coVerify { settingsRepo.setDiaryExtraRules("") }

        // 另一分区不受影响（恢复默认按分区，不是全屏）。
        assertEquals(400, vm.state.value.exchange.wordCount)
        assertEquals("别太黏", vm.state.value.exchange.styleHint)
        coVerify(exactly = 0) { settingsRepo.setDiaryExchangeWordCount(1000) }
    }

    // ── 播种口径纯函数（剥列表前缀 + %1$s → {用户名}）──

    @Test fun `播种口径 - 剥掉列表前缀并把格式占位显示成用户名占位`() {
        assertEquals("用第一人称（我）书写", diaryRuleSeed("- 用第一人称（我）书写"))
        assertEquals("对 {用户名} 的在意", diaryRuleSeed("- 对 %1\$s 的在意"))
        assertEquals("没有前缀的行原样", diaryRuleSeed("没有前缀的行原样"))
    }
}
