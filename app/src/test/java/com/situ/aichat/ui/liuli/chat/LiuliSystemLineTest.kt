package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.model.SystemEventData
import com.situ.aichat.ui.designsystem.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-10 琉璃系统行 / 耳语（图纸 2026-09-05 卷二C §7 · A-8）：
 * ① 有时间 → 「·」+「M月d日 HH:mm」；无时间 → 两者一并隐藏（照抄暖陶 F13 的规则）
 * ② 耳语走楷体点缀族。
 *
 * 楷体这条钉在**组件真正取用的那枚样式常量**（[LiuliSystemHintStyle]）上——Compose 语义树不暴露字体族，
 * 拿渲染断言证不了「是不是楷体」，钉单源常量才有判别力。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSystemLineTest {

    @get:Rule
    val compose = createComposeRule()

    private fun event(timestamp: String) = SystemEventData(
        type = "system_event",
        eventType = "red_packet_accepted",
        title = "你收下了云野的红包",
        emoji = "🧧",
        timestamp = timestamp,
    )

    @Test fun withTimestamp_showsDotAndFormattedTime() {
        compose.setContent { LiuliSystemEventLine(event("2026-04-22T15:45:00Z")) }
        compose.onNodeWithText("你收下了云野的红包").assertIsDisplayed()
        compose.onNodeWithText("·").assertIsDisplayed()
        compose.onNodeWithText(liuliFormatSystemEventTime("2026-04-22T15:45:00Z")).assertIsDisplayed()
    }

    @Test fun blankTimestamp_hidesDotAndTime() {
        compose.setContent { LiuliSystemEventLine(event("")) }
        compose.onNodeWithText("你收下了云野的红包").assertIsDisplayed()
        compose.onNodeWithText("·").assertDoesNotExist()
    }

    @Test fun unparsableTimestamp_hidesDotAndTime() {
        compose.setContent { LiuliSystemEventLine(event("not-a-time")) }
        compose.onNodeWithText("·").assertDoesNotExist()
    }

    @Test fun timeFormatter_matchesWarmClayPattern() {
        // 「M月d日 HH:mm」·`Locale.ROOT` 恒 ASCII 数字（重打暖陶 `formatEventTime` 同值）。
        assertEquals("", liuliFormatSystemEventTime("   "))
        assertEquals("", liuliFormatSystemEventTime("2026-04-22"))
        val formatted = liuliFormatSystemEventTime("2026-04-22T15:45:00Z")
        assertEquals(true, Regex("""\d{1,2}月\d{1,2}日 \d{2}:\d{2}""").matches(formatted))
    }

    @Test fun hintLine_rendersInKaiFamily() {
        compose.setContent { LiuliSystemHintLine("窗外的雨停了。") }
        compose.onNodeWithText("窗外的雨停了。").assertIsDisplayed()
        assertEquals(AppTypography.kaiFontFamily, LiuliSystemHintStyle.fontFamily)
    }

    @Test fun systemLine_is12sp() {
        assertEquals(12.sp, LiuliSystemLineStyle.fontSize)
    }
}
