package com.situ.aichat.prompt

import android.util.Log
import com.situ.aichat.data.model.MessageKind
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * assistant 输出落库前置闸（图纸 2026-09-01「记忆与防污染加固批」件①·T1-1/T1-2）。
 *
 * 断言从规格独立反推：脏段**丢弃不落库**、净段一条不少地放行、结构化 kind 天然免检；
 * 脏文本一律在此重新打字为字面量（不引检测器常量），闸门只是检测器的新消费点，规则零增改。
 */
class AssistantOutputGateTest {

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    /** 复读记忆段标题 = 典型脏输出（模型把提示词里的【长期事实】原样吐回来）。 */
    private val dirtyMemoryEcho = "【长期事实】\n- 喜欢猫\n【近期经历】\n- [2026-06-10] 去了公园"

    /** 复读系统留痕行 = 另一类典型脏输出。 */
    private val dirtySystemRecord =
        "[系统记录：线下见面结束（约40分钟），两人回到了线上聊天]"

    private val clean = "今天下班早，要不要一起吃个饭？"

    // ---------- shouldDiscard ----------

    @Test fun dirtyPlainText_isDiscarded() {
        assertTrue(AssistantOutputGate.shouldDiscard(dirtyMemoryEcho, MessageKind.PLAIN_TEXT, "test"))
        assertTrue(AssistantOutputGate.shouldDiscard(dirtySystemRecord, MessageKind.PLAIN_TEXT, "test"))
    }

    @Test fun cleanText_passes() {
        assertFalse(AssistantOutputGate.shouldDiscard(clean, MessageKind.PLAIN_TEXT, "test"))
        assertFalse(AssistantOutputGate.shouldDiscard("", MessageKind.PLAIN_TEXT, "test"))
        assertFalse(AssistantOutputGate.shouldDiscard("   ", MessageKind.PLAIN_TEXT, "test"))
    }

    @Test fun structuredKinds_areImmune() {
        // 结构化卡的 content 是 JSON，本就不该按聊天文本判脏（检测器 kind != PLAIN_TEXT 恒返 null）。
        for (kind in listOf(MessageKind.GIFT_CARD, MessageKind.RED_PACKET, MessageKind.CALL_RECORD_CARD)) {
            assertFalse("$kind 不该被闸拦", AssistantOutputGate.shouldDiscard(dirtyMemoryEcho, kind, "test"))
        }
    }

    // ---------- filterSegments（T1-1 E1/E26）----------

    @Test fun filterSegments_dropsOnlyDirtyOnes_keepsOrder() {
        val segments = listOf(clean, dirtyMemoryEcho, "那我七点到楼下等你")
        val kept = AssistantOutputGate.filterSegments(segments, isOfflineMode = false, source = "test")
        assertEquals(listOf(clean, "那我七点到楼下等你"), kept)
    }

    @Test fun filterSegments_allDirty_returnsEmpty() {
        // E2/E26：全脏 → 空表 = 空回合，交既有重试链处理（闸门自己绝不重试）。
        val kept = AssistantOutputGate.filterSegments(
            listOf(dirtyMemoryEcho, dirtySystemRecord), isOfflineMode = false, source = "test",
        )
        assertTrue(kept.isEmpty())
    }

    @Test fun filterSegments_allClean_isIdentity() {
        val segments = listOf(clean, "在忙吗", "我刚到家")
        assertEquals(segments, AssistantOutputGate.filterSegments(segments, isOfflineMode = false, source = "test"))
    }

    /** T1-2（E3）：含 [#E1] 的段按落库口径推断为 SCHEDULE_CARD → 免检存活（有意保留的旁路，钉死防误杀）。 */
    @Test fun filterSegments_calendarRefSegment_survivesEvenWithEchoedHeading() {
        val calendarSegment = "【你今天完整的日程】\n[#E1] 19:00 一起吃饭"
        assertEquals(
            MessageKind.SCHEDULE_CARD,
            MessageKindInference.forAssistantText(calendarSegment, isOfflineMode = false),
        )
        val kept = AssistantOutputGate.filterSegments(listOf(calendarSegment), isOfflineMode = false, source = "test")
        assertEquals(listOf(calendarSegment), kept)
    }

    /** 线下见面段不做日历卡识别 → 同一段在线下模式下按 PLAIN_TEXT 受检。 */
    @Test fun filterSegments_offlineMode_usesPlainTextKind() {
        val kept = AssistantOutputGate.filterSegments(
            listOf(dirtyMemoryEcho), isOfflineMode = true, source = "test",
        )
        assertTrue("线下模式下脏段同样被拦", kept.isEmpty())
    }

    // ---------- filterPlainChunks ----------

    @Test fun filterPlainChunks_dropsDirtyKeepsClean() {
        val chunks = listOf(clean, dirtySystemRecord, "晚点打给你")
        assertEquals(
            listOf(clean, "晚点打给你"),
            AssistantOutputGate.filterPlainChunks(chunks, source = "test"),
        )
    }

    @Test fun filterPlainChunks_ignoresCalendarBypass() {
        // 语音路落库 kind 恒 PLAIN_TEXT——含 [#E1] 的 chunk 在这条路上按 PLAIN_TEXT 受检（与该路落库口径一致）。
        val calendarChunk = "【你今天完整的日程】\n[#E1] 19:00 一起吃饭"
        val kept = AssistantOutputGate.filterPlainChunks(listOf(calendarChunk), source = "test")
        assertTrue("这条路没有 SCHEDULE_CARD 旁路", kept.isEmpty())
    }
}
