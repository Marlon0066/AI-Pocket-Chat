package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感内核·卷三《场内核与渲染收编》T2-3（图纸 §7.2 · E12 / E15）：【此刻】三入口都接了内心行，且恒在私 note 之前。
 *
 * 照 [PromptBuilderOfflineNowCardTest] 骨架走真 `PromptBuilder.buildMessages` 装配。断言从图纸 §4.2 独立反推：
 * - 角色场列效价 −70 ⇒ 在线 / 线下 / 无日程兜底三路都含 `此刻你心里：心里堵着一股闷气，没什么耐心。`
 * - 三路里该行下标 < `这段是给你看的`（私 note 恒最后一行·总图纸 §4.2 负向锁）
 * - 默认场 ⇒ 三路都不含前缀（整行不出，输出与卷三前逐字节同形）；成长系统关 ⇒ 不出
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderInnerStateTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    // 内心行换气后台词按本地日每 3 天轮换：基准日取 2026-07-12（epochDay 20646 ⇒ (20646/3)%3 = 0 ⇒ 变体 0 = 原文），
    // 旧基准 07-11 落在变体 2 的窗里；相对结构（13:39 正在午休小憩）一字不动。变体 2 的日期由下方 innerLineVariant_* 专门覆盖。
    private val day = LocalDate.of(2026, 7, 12)
    private val now = LocalDateTime.of(2026, 7, 12, 13, 39).atZone(zone).toInstant()
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val gloomy = GrowthJson.encode(AffectField(valence = -70))
    private val inner = "此刻你心里：心里堵着一股闷气，没什么耐心。"
    private val note = "这段是给你看的"

    private fun systemText(
        offline: Boolean,
        withSchedule: Boolean,
        affectJson: String,
        settings: AppSettings = AppSettings(),
        intentJson: String = "",
        profile: UserProfileEntity? = null,
    ): String {
        val sched = if (withSchedule) {
            CharacterDailyScheduleEntity(uuid = "s1", characterUuid = "c1", date = day.atStartOfDay(zone).toInstant().toEpochMilli(), generatedAt = at(6, 0))
        } else {
            null
        }
        val events = if (withSchedule) {
            listOf(
                ScheduleEventEntity("e1", "s1", at(9, 0), at(11, 30), "上午", "咖啡店", "开店"),
                ScheduleEventEntity("e3", "s1", at(12, 30), at(14, 0), "午后", "店里二楼", "午休小憩"),
                ScheduleEventEntity("e4", "s1", at(14, 0), at(17, 30), "下午", "咖啡店", "拉花赶单"),
            )
        } else {
            emptyList()
        }
        val conversation = if (offline) {
            ConversationEntity(uuid = "cv1", title = "t", characterUuid = "c1", creationDate = 0L, isInOfflineMode = true, currentOfflineSessionId = "os1")
        } else {
            null
        }
        val msgs = PromptBuilder.buildMessages(
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L, affectFieldJSON = affectJson, intentQueueJSON = intentJson),
            conversation = conversation,
            sortedMessages = listOf(
                MessageEntity(messageUUID = "a1", conversationUuid = "cv1", roleRaw = "assistant", content = "上午忙完啦", timestamp = now.toEpochMilli() - 3 * 3_600_000),
                MessageEntity(messageUUID = "u1", conversationUuid = "cv1", roleRaw = "user", content = "我到啦", timestamp = now.toEpochMilli() - 60_000),
            ),
            userProfile = profile, appSettings = settings, strings = PromptStrings(RuntimeEnvironment.getApplication()),
            todaySchedule = sched, todayScheduleEvents = events, now = now,
        )
        return msgs.filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }
    }

    private fun assertInnerBeforeNote(text: String, label: String) {
        assertTrue("$label 含内心行", text.contains(inner))
        assertTrue("$label 含私 note", text.contains(note))
        assertTrue("$label 内心行必须在私 note 之前", text.indexOf(inner) < text.indexOf(note))
    }

    @Test
    fun online_offline_fallback_allCarryInnerLine_beforePrivateNote() {
        assertInnerBeforeNote(systemText(offline = false, withSchedule = true, affectJson = gloomy), "在线")
        assertInnerBeforeNote(systemText(offline = true, withSchedule = true, affectJson = gloomy), "线下")
        assertInnerBeforeNote(systemText(offline = false, withSchedule = false, affectJson = gloomy), "兜底")
    }

    @Test
    fun online_innerLine_sitsInsideMomentBlock_afterLastScheduleLine() {
        val text = systemText(offline = false, withSchedule = true, affectJson = gloomy)
        // 情况 1（13:39 正在午休小憩）：内心行在「接下来 14:00 要拉花赶单」之后、注入指令第 1 条之前
        assertTrue(text.indexOf("接下来 14:00 要拉花赶单") < text.indexOf(inner))
        assertTrue(text.indexOf(inner) < text.indexOf("请按以下节奏把握"))
    }

    @Test
    fun offline_innerLine_afterBackdropLines_noWarningNoRule6() {
        val text = systemText(offline = true, withSchedule = true, affectJson = gloomy)
        assertTrue(text.indexOf("今天晚些时候（14:00）原本还有「拉花赶单」") < text.indexOf(inner))
        assertFalse("线下版不出 ⚠️（E15 / N-5）", text.contains("⚠️"))
        assertFalse("线下版不出第 6 条", text.contains("6. 你自己在最近几轮对话里刚说过的状态"))
    }

    @Test
    fun defaultField_rendersNoInnerLine_onAnyPath() {
        val neutral = GrowthJson.encode(AffectField())
        for ((label, text) in listOf(
            "在线" to systemText(offline = false, withSchedule = true, affectJson = neutral),
            "线下" to systemText(offline = true, withSchedule = true, affectJson = neutral),
            "兜底" to systemText(offline = false, withSchedule = false, affectJson = neutral),
            "空列" to systemText(offline = false, withSchedule = false, affectJson = ""),
        )) {
            assertFalse("$label 不该出现前缀", text.contains("此刻你心里："))
            assertTrue("$label 私 note 仍在", text.contains(note))
        }
        // 兜底块无内心行时与改前逐字节同形：【此刻】行紧接私 note
        val fallback = systemText(offline = false, withSchedule = false, affectJson = neutral)
        assertTrue(fallback.contains("不用硬编具体地点。\n（这段是给你看的，不要在回复里输出。）"))
    }

    @Test
    fun staleFieldColumn_innerLineUsesRelaxedReadValue_E21() {
        // 修缮卷 E21：场列 6h 前写的效价 50（存值句 = 「心里亮堂」≥45）⇒ 读值 50 × 0.5^(6/24) = 42 ⇒ 「心情不错」（20..44）
        val stale = GrowthJson.encode(AffectField(valence = 50, updatedAt = now.toEpochMilli() - 6 * 3_600_000L))
        for ((label, text) in listOf(
            "在线" to systemText(offline = false, withSchedule = true, affectJson = stale),
            "线下" to systemText(offline = true, withSchedule = true, affectJson = stale),
            "兜底" to systemText(offline = false, withSchedule = false, affectJson = stale),
        )) {
            assertTrue("$label 按读值出句", text.contains("此刻你心里：心情不错。"))
            assertFalse("$label 不该按列里的存值出句", text.contains("心里亮堂"))
        }
    }

    @Test
    fun growthSystemDisabled_rendersNoInnerLine() {
        val text = systemText(offline = false, withSchedule = true, affectJson = gloomy, settings = AppSettings(growthSystemEnabled = false))
        assertFalse(text.contains("此刻你心里："))
    }

    // MARK: - 卷四 T2-5（图纸 §7.2 · §4.4）：意图句在三路都进【此刻】、恒在私 note 之前；默认列三路不含

    private val apologyQueue = GrowthJson.encode(
        IntentQueueState(
            intents = listOf(
                CharacterIntent(
                    id = "i1", kind = IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 50,
                    bornAt = now.toEpochMilli() - 3_600_000L, lastChangeAt = now.toEpochMilli() - 3_600_000L,
                ),
            ),
        ),
    )
    private val apologyLine = "你想跟小明道个歉，话到嘴边又咽了回去。"
    private val xiaoming = UserProfileEntity(nickname = "小明")

    @Test
    fun activeApologyIntent_rendersOnAllThreePaths_beforePrivateNote() {
        for ((label, text) in listOf(
            "在线" to systemText(offline = false, withSchedule = true, affectJson = "", intentJson = apologyQueue, profile = xiaoming),
            "线下" to systemText(offline = true, withSchedule = true, affectJson = "", intentJson = apologyQueue, profile = xiaoming),
            "兜底" to systemText(offline = false, withSchedule = false, affectJson = "", intentJson = apologyQueue, profile = xiaoming),
        )) {
            assertTrue("$label 含意图句", text.contains(apologyLine))
            assertTrue("$label 意图句在私 note 之前", text.indexOf(apologyLine) < text.indexOf(note))
        }
    }

    // MARK: - 内心行换气（微图纸 2026-09-02 §5）：三入口用同一 zone 算台词变体（有日程 = 日程时区；无日程 = 系统时区）

    /** 日程与事件全按 [scheduleZone] 的本地日建（`timezoneIdentifier` = 该时区），`now` 显式传入。 */
    private fun systemTextZoned(offline: Boolean, withSchedule: Boolean, nowInstant: Instant, scheduleZone: ZoneId, affectJson: String): String {
        val kDay = nowInstant.atZone(scheduleZone).toLocalDate()
        fun atK(h: Int, m: Int) = kDay.atTime(h, m).atZone(scheduleZone).toInstant().toEpochMilli()
        val sched = if (withSchedule) {
            CharacterDailyScheduleEntity(
                uuid = "s1", characterUuid = "c1", date = kDay.atStartOfDay(scheduleZone).toInstant().toEpochMilli(),
                timezoneIdentifier = scheduleZone.id, generatedAt = atK(6, 0),
            )
        } else {
            null
        }
        val events = if (withSchedule) {
            listOf(
                ScheduleEventEntity("e1", "s1", atK(9, 0), atK(11, 30), "上午", "咖啡店", "开店"),
                ScheduleEventEntity("e4", "s1", atK(14, 0), atK(17, 30), "下午", "咖啡店", "拉花赶单"),
            )
        } else {
            emptyList()
        }
        val conversation = if (offline) {
            ConversationEntity(uuid = "cv1", title = "t", characterUuid = "c1", creationDate = 0L, isInOfflineMode = true, currentOfflineSessionId = "os1")
        } else {
            null
        }
        val msgs = PromptBuilder.buildMessages(
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L, affectFieldJSON = affectJson),
            conversation = conversation,
            sortedMessages = listOf(
                MessageEntity(messageUUID = "a1", conversationUuid = "cv1", roleRaw = "assistant", content = "上午忙完啦", timestamp = nowInstant.toEpochMilli() - 3 * 3_600_000),
                MessageEntity(messageUUID = "u1", conversationUuid = "cv1", roleRaw = "user", content = "我到啦", timestamp = nowInstant.toEpochMilli() - 60_000),
            ),
            userProfile = null, appSettings = AppSettings(), strings = PromptStrings(RuntimeEnvironment.getApplication()),
            todaySchedule = sched, todayScheduleEvents = events, now = nowInstant,
        )
        return msgs.filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }
    }

    @Test
    fun innerLineVariant_followsScheduleZone_onSchedulePaths_systemZoneOnFallback() {
        // 日程时区取 +14（Pacific/Kiritimati·领先任何系统时区）：K 区 2026-07-12 00:30 ⇒ epochDay 20646 → (20646/3)%3 = 0；
        // 系统时区此刻仍是 07-11 ⇒ epochDay 20645 → 6881%3 = 2。效价 30（读值 dt=0 仍 30）⇒ 变体 0「心情不错。」/ 变体 2「这会儿心里挺舒坦。」
        val k = ZoneId.of("Pacific/Kiritimati")
        val nowK = LocalDate.of(2026, 7, 12).atTime(0, 30).atZone(k).toInstant()
        assertEquals("前提自检：系统时区此刻仍是前一天", LocalDate.of(2026, 7, 11), nowK.atZone(zone).toLocalDate())
        assertEquals("前提自检：手算 epochDay", 20_646L, LocalDate.of(2026, 7, 12).toEpochDay())
        val cheerful = GrowthJson.encode(AffectField(valence = 30, updatedAt = nowK.toEpochMilli()))
        for ((label, text) in listOf(
            "在线" to systemTextZoned(offline = false, withSchedule = true, nowInstant = nowK, scheduleZone = k, affectJson = cheerful),
            "线下" to systemTextZoned(offline = true, withSchedule = true, nowInstant = nowK, scheduleZone = k, affectJson = cheerful),
        )) {
            assertTrue("$label 按日程时区的本地日取变体 0", text.contains("此刻你心里：心情不错。"))
            assertFalse("$label 不该按系统时区取变体 2", text.contains("这会儿心里挺舒坦"))
        }
        // 无日程兜底：没有日程时区可依 ⇒ 系统时区 ⇒ 变体 2（与 innerLine 的 zone 取法同源）
        val fallback = systemTextZoned(offline = false, withSchedule = false, nowInstant = nowK, scheduleZone = k, affectJson = cheerful)
        assertTrue("兜底按系统时区取变体 2", fallback.contains("此刻你心里：这会儿心里挺舒坦。"))
        assertFalse(fallback.contains("此刻你心里：心情不错。"))
    }

    @Test
    fun defaultIntentColumn_rendersNoIntentSentence_onAnyPath() {
        for ((label, text) in listOf(
            "在线" to systemText(offline = false, withSchedule = true, affectJson = "", profile = xiaoming),
            "线下" to systemText(offline = true, withSchedule = true, affectJson = "", profile = xiaoming),
            "兜底" to systemText(offline = false, withSchedule = false, affectJson = "", profile = xiaoming),
            "坏列" to systemText(offline = false, withSchedule = false, affectJson = "", intentJson = "{坏", profile = xiaoming),
        )) {
            assertFalse("$label 不该含意图句", text.contains("道个歉"))
            assertFalse("$label 不该出现前缀", text.contains("此刻你心里："))
        }
    }
}
