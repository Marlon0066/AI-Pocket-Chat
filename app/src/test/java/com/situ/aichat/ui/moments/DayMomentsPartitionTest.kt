package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-1（图纸 2026-09-03 §7）：`partitionDayMoments` 的窗口边界与保序。
 *
 * 断言从 §3.3 规格独立反推——窗口是**半开** `[start, end)`：下界闭（`== start` 属当天）、上界开
 * （`== end` 不属当天）；且本函数**不重排**，两组各自保持入参相对顺序（排序单源在 SQL·E7 / E17）。
 */
class DayMomentsPartitionTest {

    private val start = 1_756_656_000_000L
    private val end = start + 86_400_000L

    private fun post(uuid: String, timestamp: Long) =
        MomentPostWithRelations(MomentPostEntity(uuid = uuid, timestamp = timestamp), emptyList(), emptyList())

    private fun uuidsOf(vararg posts: MomentPostWithRelations): Pair<List<String>, List<String>> {
        val (thatDay, earlier) = partitionDayMoments(posts.toList(), start, end)
        return thatDay.map { it.post.uuid } to earlier.map { it.post.uuid }
    }

    @Test
    fun `当天零点整 属这一天发的（下界闭）`() {
        assertEquals(listOf("p") to emptyList<String>(), uuidsOf(post("p", start)))
    }

    @Test
    fun `当天最后一毫秒 属这一天发的`() {
        assertEquals(listOf("p") to emptyList<String>(), uuidsOf(post("p", end - 1)))
    }

    @Test
    fun `次日零点整 不属这一天（上界开）`() {
        assertEquals(emptyList<String>() to listOf("p"), uuidsOf(post("p", end)))
    }

    @Test
    fun `当天零点前一毫秒 不属这一天`() {
        assertEquals(emptyList<String>() to listOf("p"), uuidsOf(post("p", start - 1)))
    }

    @Test
    fun `按倒序给入时 两组各自保持入参相对顺序`() {
        val (thatDay, earlier) = uuidsOf(
            post("in-late", end - 1),
            post("out-mid", start - 1_000L),
            post("in-early", start),
            post("out-old", start - 9_000L),
        )
        assertEquals(listOf("in-late", "in-early"), thatDay)
        assertEquals(listOf("out-mid", "out-old"), earlier)
    }
}
