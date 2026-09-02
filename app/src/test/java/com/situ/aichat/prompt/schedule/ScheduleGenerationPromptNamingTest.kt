package com.situ.aichat.prompt.schedule

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * T2-2（图纸二·人称指名·§7）：buildPrompt 里 6 处「用户」称呼 → `request.userName`（真名）。
 * 传 userName="小明" → 6 处渲染真名；旧「用户/你」裸称呼精确否定（含合法子串「用户名」不误伤——
 * 本 prompt 无该标签，故 blanket 亦成立，数据侧刻意不含「用户」二字·图纸一 D-3 口径）。
 * 传默认（不传 userName）→ 回退「用户」字节不变（尾参默认字节级零变化·§1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleGenerationPromptNamingTest {

    private lateinit var db: AppDatabase
    private lateinit var genService: ScheduleGenerationService

    private val zone = ZoneOffset.UTC
    // 2026-07-13 = 表内普通周一（非节假日非补班）
    private val dateMillis = LocalDate.of(2026, 7, 13).atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        genService = ScheduleGenerationService(mockk<ContextLogService>(), db.scheduleDao())
    }

    @After
    fun tearDown() = db.close()

    /** 全料角色：让关系块（!backfill·需 firstMessageDate+关系分）真出现。 */
    private fun loadedCharacter() = CharacterEntity(
        uuid = "c1", name = "夏晴子", creationDate = 0L,
        relationshipQualityJSON = GrowthJson.encode(
            RelationshipQuality(familiarity = 80, trust = 80, closeness = 80, attachment = 80),
        ),
        firstMessageDate = 0L,
        memorySummary = "【长期事实】\n她养了一只猫",
    )

    // 数据侧刻意不含「用户」二字（loop/promise 用中性词），保证 blanket 否定不被数据污染。
    private fun cleanLiveness() = ScheduleLivenessContext(
        todayMeetings = listOf(ScheduleLivenessContext.MeetingLine("15:00", "美术馆", "看展")),
        todayPromises = listOf("去配眼镜"),
        openLoops = listOf("面试结果还没出"),
    )

    private fun req(userName: String) = ScheduleGenerationRequest(
        character = loadedCharacter(), dateMillis = dateMillis, zone = zone,
        yesterdayEvents = emptyList(),
        recentConversationSummary = "小明：明天一起去看展\n夏晴子：好呀",
        otherCharacterSchedules = emptyList(), crossCharacterLevel = 0,
        liveness = cleanLiveness(), userName = userName,
    )

    @Test
    fun `传小明_6处buildPrompt称呼渲染真名_旧用户你裸称呼绝迹`() {
        val user = genService.buildPrompt(req(userName = "小明")).second
        // 正向：6 处（:266/:269/:359/:360/:364×2）
        assertTrue(user.contains("【最近和小明聊到的事】"))
        assertTrue(user.contains("禁止在 activity 里写「和小明发消息/聊天/分享」之类的互动动作"))
        assertTrue(user.contains("严禁写「和小明发消息/聊天/打电话/视频通话/分享截图」之类的互动动作"))
        assertTrue(user.contains("如需体现角色对小明的思念或情感"))
        assertTrue(user.contains("不是和小明互动的记录"))
        assertTrue(user.contains("按【和小明的关系】给出的参考控制想到小明的频率"))
        // 精确否定：旧「用户/你」裸称呼各串（图纸一 D-3 口径·避合法子串误伤）
        assertFalse(user.contains("【最近和用户聊到的事】"))
        assertFalse(user.contains("和你发消息"))
        assertFalse(user.contains("和用户发消息"))
        assertFalse(user.contains("角色对用户的思念"))
        assertFalse(user.contains("不是和用户互动的记录"))
        assertFalse(user.contains("【和用户的关系】"))
        assertFalse(user.contains("想到用户的频率"))
        // blanket：本 prompt 无「用户名」等合法子串标签、数据侧亦无「用户」→ 全篇零「用户」码
        assertFalse("prompt 不应残留任何「用户」码", user.contains("用户"))
    }

    @Test
    fun `默认不传userName_回退用户_6处字节不变`() {
        val user = genService.buildPrompt(req(userName = "用户")).second // 与旧默认构造点等价
        assertTrue(user.contains("【最近和用户聊到的事】"))
        assertTrue(user.contains("禁止在 activity 里写「和用户发消息/聊天/分享」之类的互动动作"))
        assertTrue(user.contains("严禁写「和用户发消息/聊天/打电话/视频通话/分享截图」之类的互动动作"))
        assertTrue(user.contains("如需体现角色对用户的思念或情感"))
        assertTrue(user.contains("不是和用户互动的记录"))
        assertTrue(user.contains("按【和用户的关系】给出的参考控制想到用户的频率"))
    }

    // 卷四 T2-6 ③（图纸 §4.5 / §2.2）：意图块只在 !backfill 出现，落在惦记块之后；无 live 意图零行。
    @Test
    fun `卷四_意图块_非backfill含_backfill不含_无意图不含`() {
        val now = System.currentTimeMillis()
        val queue = GrowthJson.encode(
            IntentQueueState(
                intents = listOf(CharacterIntent(id = "i", kind = IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 50, bornAt = now, lastChangeAt = now)),
            ),
        )
        val withIntent = req(userName = "小明").copy(character = loadedCharacter().copy(intentQueueJSON = queue))
        val user = genService.buildPrompt(withIntent).second
        assertTrue(user.contains("\n\n【TA心里挂着的事】\n- TA想向小明道歉\n这些只能进 innerThought（比如「要不要找个机会跟小明说一声」），不要变成日程事件，也不必每条都用。"))
        assertTrue("惦记块之后", user.indexOf("【TA心里惦记的事】") < user.indexOf("【TA心里挂着的事】"))
        val backfill = genService.buildPrompt(withIntent.copy(isBackfill = true, liveness = null)).second
        assertFalse(backfill.contains("【TA心里挂着的事】"))
        val noIntent = genService.buildPrompt(req(userName = "小明")).second
        assertFalse(noIntent.contains("【TA心里挂着的事】"))
    }
}
