package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷二《正负双压》T1-6（图纸 §7.2 · P-E6–P-E9）：`relationship_changes` 的**双形状解析**。
 *
 * 断言从图纸 §3.3 那张表**独立反推**（不照抄实现）：
 *
 * | 输入 | 期望 |
 * |---|---|
 * | `{"pos":3,"neg":2}` | `pos=3, neg=2` |
 * | `3`（旧形状） | `pos=3, neg=0` |
 * | `-2`（旧形状负数） | `pos=0, neg=2` |
 * | `{"pos":9,"neg":-1}` | 各钳 `[0,5]` ⇒ `pos=5, neg=0` |
 * | 非法维度 key | 丢弃 |
 *
 * ⚠️ 这也是**格式锁**（图纸 §6.1）：生成端（`buildAnalysisPrompt` 的「## 输出格式」段）与解析端在同一个类里，
 * 任一侧改了形状而另一侧没跟，这条测试就该红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureParseTest {

    private val service = GrowthAnalysisService(
        contextLog = mockk<ContextLogService>(relaxed = true),
        conversationDao = mockk<ConversationDao>(relaxed = true),
        messageDao = mockk<MessageDao>(relaxed = true),
        scheduleDao = mockk<ScheduleDao>(relaxed = true),
    )

    private fun parse(relationshipChangesJson: String) = service.parseAnalysisResponse(
        """{"relationship_changes":$relationshipChangesJson,"events":[{"type":"majorEvent","summary":"x"}],"narrative":"n"}""",
    ).relationshipChanges

    @Test
    fun `P-E6 新形状 - 正负各自入账`() {
        val out = parse("""{"trust":{"pos":3,"neg":2}}""")
        assertEquals(3, out["trust"]!!.pos)
        assertEquals(2, out["trust"]!!.neg)
    }

    @Test
    fun `P-E6 旧形状正数 - 全进正压`() {
        val out = parse("""{"trust":3}""")
        assertEquals(3, out["trust"]!!.pos)
        assertEquals(0, out["trust"]!!.neg)
    }

    @Test
    fun `P-E7 旧形状负数 - 按符号进负压`() {
        val out = parse("""{"trust":-2}""")
        assertEquals(0, out["trust"]!!.pos)
        assertEquals(2, out["trust"]!!.neg)
    }

    @Test
    fun `P-E8 越界值各钳 0 到 5`() {
        val out = parse("""{"trust":{"pos":9,"neg":-1},"closeness":{"pos":0,"neg":88}}""")
        assertEquals(5, out["trust"]!!.pos)
        assertEquals(0, out["trust"]!!.neg)
        assertEquals(0, out["closeness"]!!.pos)
        assertEquals(5, out["closeness"]!!.neg)
    }

    @Test
    fun `P-E8 旧形状越界也钳到 5`() {
        assertEquals(5, parse("""{"trust":12}""")["trust"]!!.pos)
        assertEquals(5, parse("""{"trust":-12}""")["trust"]!!.neg)
    }

    @Test
    fun `P-E9 非法维度 key 丢弃`() {
        val out = parse("""{"trust":{"pos":1,"neg":0},"友情":3,"extroversion":2}""")
        assertEquals("只剩合法的那一个", setOf("trust"), out.keys)
        assertNull(out["友情"])
        assertNull("性格维度的 key 不许混进关系图", out["extroversion"])
    }

    @Test
    fun `缺子键的对象按 0 兜底`() {
        val out = parse("""{"trust":{"pos":4},"closeness":{}}""")
        assertEquals(4, out["trust"]!!.pos)
        assertEquals(0, out["trust"]!!.neg)
        assertEquals(0, out["closeness"]!!.pos)
        assertEquals(0, out["closeness"]!!.neg)
    }

    @Test
    fun `修缮卷 D-13 小数与数字串子键也认`() {
        val out = parse("""{"trust":{"pos":"3","neg":1.6},"closeness":"2","rapport":2.4}""")
        assertEquals(3, out["trust"]!!.pos)
        assertEquals(2, out["trust"]!!.neg)
        assertEquals("单值数字串 ⇒ 旧形状", 2, out["closeness"]!!.pos)
        assertEquals("单值小数取整（此前整维丢弃）", 2, out["rapport"]!!.pos)
    }

    @Test
    fun `不认识的形状整维丢弃而不是崩`() {
        val out = parse("""{"trust":[1,2],"closeness":"很多"}""")
        assertTrue("数组与非数字字符串都不成形 ⇒ 丢弃该维，其余照常", out.isEmpty())
    }

    @Test
    fun `全 8 维一起报 - 一个不漏`() {
        val all = com.situ.aichat.data.model.RelationshipQuality.DIMENSION_KEYS
            .joinToString(",") { """"$it":{"pos":1,"neg":1}""" }
        assertEquals(8, parse("{$all}").size)
    }
}
