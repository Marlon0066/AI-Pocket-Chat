package com.situ.aichat.ui.ourdays

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2（图纸 2026-09-03 的**唯一行为变化**之锁）：日页事实层「朋友圈」行的「看动态 ›」点击，必须带着
 * **该角色 uuid 与该日日键**回调出去（旧行为是无参、落到信息流顶部）。
 *
 * 断言从图纸 §2.3「唯 1 处有意变化」独立反推：回调实参 == 传给 [OurDayFactsSection] 的 characterUuid /
 * dayKey；且同屏其它链接（这里取「看日程 ›」）仍走各自回调，不被本次签名改动串线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class OurDayFactsMomentsLinkTest {

    @get:Rule
    val compose = createComposeRule()

    private val charUuid = "c-林晚"
    private val dayKey = "2026-09-01"

    @Test
    fun `看动态点击带回角色uuid与日键_看日程不受影响`() {
        var moments: Pair<String, String>? = null
        var schedule: Pair<String, String>? = null
        compose.setContent {
            Column {
                OurDayFactsSection(
                    facts = listOf(
                        FactItem(FactKind.MOMENTS, "朋友圈", "发了 1 条", FactLink.MOMENTS),
                        FactItem(FactKind.SCHEDULE, "日程", "3 件事", FactLink.SCHEDULE(dayKey)),
                    ),
                    characterUuid = charUuid,
                    dayKey = dayKey,
                    onOpenMeetings = {},
                    onOpenPromises = {},
                    onOpenMoments = { uuid, key -> moments = uuid to key },
                    onOpenDiary = {},
                    onOpenSchedule = { uuid, key -> schedule = uuid to key },
                )
            }
        }

        compose.onNodeWithText("看动态 ›").performClick()
        compose.waitForIdle()
        assertEquals("「看动态 ›」必须带回角色 uuid 与日键", charUuid to dayKey, moments)
        assertEquals("此时不该触发日程链接", null, schedule)

        compose.onNodeWithText("看日程 ›").performClick()
        compose.waitForIdle()
        assertEquals(charUuid to dayKey, schedule)
    }
}
