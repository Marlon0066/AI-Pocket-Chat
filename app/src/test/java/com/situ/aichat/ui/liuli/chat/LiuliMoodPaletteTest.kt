package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import com.situ.aichat.tts.EmotionType
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.LiuliDarkAppColors
import com.situ.aichat.ui.designsystem.LiuliLightAppColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-2 / T2-6 纯函数档（图纸 2026-09-05 卷二A §7）：心情四色的**族映射 / 派生 / 时段档 / 绕位轮转 / 动效档**。
 *
 * 断言从图纸 §0 ② 4 与 §3.2 的规格独立反推（族表逐条重打、混合比 0.62 / 0.70 / 0.72 重打、槽位表重打），
 * 不照抄实现输出。E4（无心情）/ E5（字典外 emoji）/ E6（同族异 emoji 同色）在此闭环。
 */
class LiuliMoodPaletteTest {

    /** 图纸 §0 ② 4 的族表——在测试里**重新打一遍**（不引用实现的 when）。 */
    private val expectedFamilies = mapOf(
        EmotionType.HAPPY to LiuliMoodFamily.JOY,
        EmotionType.EXCITED to LiuliMoodFamily.JOY,
        EmotionType.PLAYFUL to LiuliMoodFamily.JOY,
        EmotionType.LOVE to LiuliMoodFamily.SHY,
        EmotionType.SHY to LiuliMoodFamily.SHY,
        EmotionType.SAD to LiuliMoodFamily.SAD,
        EmotionType.SIGH to LiuliMoodFamily.SAD,
        EmotionType.ANGRY to LiuliMoodFamily.ANGER,
        EmotionType.SCARED to LiuliMoodFamily.ANGER,
        EmotionType.THINKING to LiuliMoodFamily.CALM,
        EmotionType.SHOCKED to LiuliMoodFamily.CALM,
        EmotionType.NEUTRAL to LiuliMoodFamily.CALM,
    )

    @Test fun family_table_coversAll12EmotionTypes_withNoGap() {
        // 穷举：枚举有多少个，表就必须有多少条（新增情绪不许静默落进某个族）。
        assertEquals(EmotionType.entries.size, expectedFamilies.size)
        EmotionType.entries.forEach { emotion ->
            assertEquals("$emotion 归族不符图纸 §0 ② 4", expectedFamilies[emotion], liuliMoodFamily(emotion))
        }
    }

    @Test fun emptyOrUnknownEmoji_fallsBackToCalm() {
        // E4：新会话 moodEmoji 为空 → NEUTRAL → calm。
        assertEquals(LiuliMoodFamily.CALM, liuliMoodFamily(EmotionType.from("")))
        assertEquals(LiuliMoodFamily.CALM, liuliMoodFamily(EmotionType.from(null)))
        // E5：字典外 emoji → NEUTRAL → calm。
        assertEquals(LiuliMoodFamily.CALM, liuliMoodFamily(EmotionType.from("🐳")))
    }

    @Test fun sameFamilyDifferentEmoji_yieldsSameColors() {
        // E6：😊 与 🥳 同属 joy → 四色逐位相同（族不变就不换色）。
        val a = liuliMoodBlobColors(liuliMoodFamily(EmotionType.from("😊")), LiuliLightAppColors, hour = 14)
        val b = liuliMoodBlobColors(liuliMoodFamily(EmotionType.from("🥳")), LiuliLightAppColors, hour = 14)
        assertEquals(a, b)
    }

    @Test fun blobColors_returnFourPerTier_andTiersDiffer() {
        val day = liuliMoodBlobColors(LiuliMoodFamily.JOY, LiuliLightAppColors, hour = 14)
        val dayLate = liuliMoodBlobColors(LiuliMoodFamily.JOY, LiuliLightAppColors, hour = 23)
        val night = liuliMoodBlobColors(LiuliMoodFamily.JOY, LiuliDarkAppColors, hour = 14)
        listOf(day, dayLate, night).forEach { assertEquals(4, it.size) }
        // 三档互不相同（昼 0.62 / 昼夜间 0.70 / 夜档换底混）。
        assertNotEquals(day, dayLate)
        assertNotEquals(day, night)
        assertNotEquals(dayLate, night)
        // 非 calm 族的四点彼此可辨（calm 族第 1、3 点本就同源=有意重合，不在此断言内）。
        assertEquals(4, day.toSet().size)
    }

    @Test fun lateHourWindow_isTwentyTwoToSix() {
        // 图纸 §0 ② 4：昼档 22:00–06:00 更沉一档。
        listOf(22, 23, 0, 3, 5).forEach { assertTrue("$it 点应属夜间时段", isLateHour(it)) }
        listOf(6, 7, 12, 21).forEach { assertTrue("$it 点不应属夜间时段", !isLateHour(it)) }
    }

    @Test fun lateHour_mixesWhiter_thanPlainDay() {
        // 更沉 = 向白混得更多 → 越接近白（亮度更高）。逐点比较红通道足以区分（同一底色系）。
        val day = liuliMoodBlobColors(LiuliMoodFamily.SAD, LiuliLightAppColors, hour = 14)
        val late = liuliMoodBlobColors(LiuliMoodFamily.SAD, LiuliLightAppColors, hour = 23)
        day.indices.forEach { i ->
            assertTrue("第 $i 点 22 点档应更向白", late[i].red >= day[i].red && late[i].green >= day[i].green)
        }
    }

    @Test fun slots_rotateOneStepPerSendTurn_andStayAPermutation() {
        // 图纸 §3.2 槽位表在此重打一遍（0.18/0.12 · 0.85/0.25 · 0.25/0.85 · 0.85/0.90）。
        assertEquals(0.18f, MOOD_SLOTS[0].x, 1e-6f)
        assertEquals(0.12f, MOOD_SLOTS[0].y, 1e-6f)
        assertEquals(0.85f, MOOD_SLOTS[1].x, 1e-6f)
        assertEquals(0.25f, MOOD_SLOTS[1].y, 1e-6f)
        assertEquals(0.25f, MOOD_SLOTS[2].x, 1e-6f)
        assertEquals(0.85f, MOOD_SLOTS[2].y, 1e-6f)
        assertEquals(0.85f, MOOD_SLOTS[3].x, 1e-6f)
        assertEquals(0.90f, MOOD_SLOTS[3].y, 1e-6f)
        // 第 0 轮 = 原位；每 +1 轮整体挪一格；四点始终占满四个槽（是轮换不是塌缩）。
        repeat(4) { i -> assertEquals(MOOD_SLOTS[i], liuliMoodSlot(i, 0)) }
        repeat(4) { i -> assertEquals(MOOD_SLOTS[(i + 1) % 4], liuliMoodSlot(i, 1)) }
        repeat(9) { turn ->
            assertEquals(4, (0..3).map { liuliMoodSlot(it, turn) }.toSet().size)
        }
        // 绕一圈回到原位。
        repeat(4) { i -> assertEquals(MOOD_SLOTS[i], liuliMoodSlot(i, 4)) }
    }

    @Test fun reduceMotion_freezesSlotRotation() {
        // 复核 R1 🟡-4（图纸 §0 ② 4 / E14「RM 不轮转」）：减弱动画时相位恒 0，发送再多四点也不挪位。
        assertEquals(0, liuliMoodSlotTurn(sendTurn = 5, reduceMotion = true))
        assertEquals(5, liuliMoodSlotTurn(sendTurn = 5, reduceMotion = false))
        repeat(4) { i -> assertEquals(MOOD_SLOTS[i], liuliMoodSlot(i, liuliMoodSlotTurn(7, reduceMotion = true))) }
    }

    @Test fun reduceMotion_makesTargetsLandImmediately() {
        // T2-6：减弱动画 → 换色与绕位都是 snap（目标即终值）。
        assertTrue(liuliMoodColorSpec(reduceMotion = true) is SnapSpec)
        assertTrue(liuliMoodSlotSpec(reduceMotion = true) is SnapSpec)
    }

    @Test fun normalMotion_usesLockedColorTween() {
        // 图纸 §3.2 锁：换色 tween(2600, EaseOut)。
        val spec = liuliMoodColorSpec(reduceMotion = false)
        assertTrue(spec is TweenSpec)
        val tween = spec as TweenSpec
        assertEquals(2600, tween.durationMillis)
        assertEquals(0, tween.delay)
        assertEquals(AppMotion.EaseOut, tween.easing)
    }
}
