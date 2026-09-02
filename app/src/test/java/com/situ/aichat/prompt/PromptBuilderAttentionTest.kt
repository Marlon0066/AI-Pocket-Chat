package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthJson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感内核·卷三《场内核与渲染收编》T2-4（图纸 §7.2 · E14）：睡眠/分心移交在真装配里的表现。
 *
 * 断言从图纸 §4.3 独立反推（四条 ⚠️ 文案在此重新打字·PITFALLS §1e）：
 * - 日程「睡觉」进行中 + 她 2 轮前说「睡不着」+ 激活 30 ⇒ 含 AWAKE_OVERRIDE 全句且不含老睡眠句
 * - 同场景无自述 ⇒ 老句逐字；激活 10 ⇒ 老句（E14 退化保护）
 * - 手机不可用事件 + 她说「有空」⇒ AVAILABLE_OVERRIDE；无自述 ⇒ 老分心句逐字
 * - 线下同场景 ⇒ 零 ⚠️；第 6 条只出现在在线主路（线下 / 兜底不出）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderAttentionTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day = LocalDate.of(2026, 7, 11)
    /** 深夜 23:30（睡觉场景）。 */
    private val night = LocalDateTime.of(2026, 7, 11, 23, 30).atZone(zone).toInstant()

    /** 下午 14:00（开会场景——深夜 + 手机不可用本身就是睡眠信号，`scheduleIsSleepEvent` 会先判成睡觉，故分心场景必须放白天）。 */
    private val afternoon = LocalDateTime.of(2026, 7, 11, 14, 0).atZone(zone).toInstant()

    private val sleepOld = "⚠️ 你此刻处于睡觉/半睡状态——回复要体现困意（用省略号、短句、哈欠等措辞），一两句就好，不要精神饱满地展开长话题。只用文字回复，不要用括号写动作、神态或场景。"
    private val distractedOld = "⚠️ 此刻你注意力不在手机上（比如开会、开车、专注做事）——回复应该简短、略显分心（例如\"稍等\"、\"在开会\"、\"晚点说\"），不要展开长对话。"
    private val awakeOverride = "⚠️ 日程上你这会儿该睡了，但你刚才自己说了还没睡——按你说的来：人是醒着的，别一轮一轮把自己按回困意；只是夜深了，回复可以短一点、软一点。只用文字回复，不要用括号写动作、神态或场景。"
    private val availableOverride = "⚠️ 日程上你这会儿在忙，但你刚才自己说了现在有空——按你说的来，正常聊，不用装作分心。"
    private val rule6 = "6. 你自己在最近几轮对话里刚说过的状态，优先于【此刻】的日程陈述。"

    private fun event(activity: String, phone: Boolean, nowMs: Long) = ScheduleEventEntity(
        uuid = "e1", scheduleUuid = "s1", startTime = nowMs - 30 * 60_000, endTime = nowMs + 7 * 3_600_000,
        periodLabel = "", location = "家里", activity = activity, isPhoneAvailable = phone,
    )

    /** 两轮前她说了 [characterLine]（在线角色行 · 10 分钟前），之后用户又说了两句。 */
    private fun history(characterLine: String?, nowMs: Long) = listOfNotNull(
        MessageEntity(messageUUID = "u0", conversationUuid = "cv1", roleRaw = "user", content = "还没睡？", timestamp = nowMs - 12 * 60_000),
        characterLine?.let { MessageEntity(messageUUID = "a1", conversationUuid = "cv1", roleRaw = "assistant", content = it, timestamp = nowMs - 10 * 60_000) },
        MessageEntity(messageUUID = "u1", conversationUuid = "cv1", roleRaw = "user", content = "那聊聊", timestamp = nowMs - 5 * 60_000),
        MessageEntity(messageUUID = "a2", conversationUuid = "cv1", roleRaw = "assistant", content = "好呀", timestamp = nowMs - 4 * 60_000),
        MessageEntity(messageUUID = "u2", conversationUuid = "cv1", roleRaw = "user", content = "今天累不累", timestamp = nowMs - 60_000),
    )

    private fun systemText(
        activity: String = "睡觉",
        phone: Boolean = true,
        characterLine: String? = null,
        arousal: Int = 30,
        offline: Boolean = false,
        withSchedule: Boolean = true,
        now: java.time.Instant = night,
    ): String {
        val nowMs = now.toEpochMilli()
        val sched = if (withSchedule) CharacterDailyScheduleEntity(uuid = "s1", characterUuid = "c1", date = day.atStartOfDay(zone).toInstant().toEpochMilli(), generatedAt = nowMs - 20 * 3_600_000) else null
        val conversation = if (offline) ConversationEntity(uuid = "cv1", title = "t", characterUuid = "c1", creationDate = 0L, isInOfflineMode = true, currentOfflineSessionId = "os1") else null
        val msgs = PromptBuilder.buildMessages(
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L, affectFieldJSON = GrowthJson.encode(AffectField(arousal = arousal))),
            conversation = conversation,
            sortedMessages = history(characterLine, nowMs),
            userProfile = null, appSettings = AppSettings(), strings = PromptStrings(RuntimeEnvironment.getApplication()),
            todaySchedule = sched, todayScheduleEvents = if (withSchedule) listOf(event(activity, phone, nowMs)) else emptyList(), now = now,
        )
        return msgs.filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }
    }

    @Test
    fun sleepSchedule_butSheSaidAwake_overridesWithNewLine() {
        val text = systemText(characterLine = "今晚睡不着，你陪我聊会儿")
        assertTrue(text.contains(awakeOverride))
        assertFalse(text.contains(sleepOld))
        assertTrue("仍以 ⚠️ 开头，第 3 条硬依赖", text.contains("\n$awakeOverride"))
    }

    @Test
    fun sleepSchedule_noSelfReport_keepsOldLineVerbatim() {
        val text = systemText(characterLine = "上午忙完啦")
        assertTrue(text.contains(sleepOld))
        assertFalse(text.contains(awakeOverride))
    }

    @Test
    fun sleepSchedule_saidAwake_butLowArousal_degradesToOldLine_E14() {
        val text = systemText(characterLine = "今晚睡不着", arousal = 10)
        assertTrue(text.contains(sleepOld))
        assertFalse(text.contains(awakeOverride))
    }

    @Test
    fun phoneUnavailable_sheSaidAvailable_overrides_elseOldDistracted() {
        val over = systemText(activity = "开会", phone = false, characterLine = "开完会了，现在有空", now = afternoon)
        assertTrue(over.contains(availableOverride))
        assertFalse(over.contains(distractedOld))
        val old = systemText(activity = "开会", phone = false, characterLine = "在改方案", now = afternoon)
        assertTrue(old.contains(distractedOld))
        assertFalse(old.contains(availableOverride))
    }

    @Test
    fun offlineMeeting_sameScenario_hasNoWarningAtAll() {
        val text = systemText(characterLine = "今晚睡不着", offline = true)
        assertFalse(text.contains("⚠️"))
        assertFalse(text.contains(rule6))
    }

    @Test
    fun rule6_onlyOnOnlineMainPath() {
        assertTrue(systemText().contains(rule6))
        assertTrue("第 6 条在第 5 条之后", systemText().indexOf("那些是过去，这是现在") < systemText().indexOf(rule6))
        assertFalse(systemText(withSchedule = false).contains(rule6))
        assertFalse(systemText(offline = true).contains(rule6))
    }
}
