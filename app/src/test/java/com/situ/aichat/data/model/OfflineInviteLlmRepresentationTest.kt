package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线下邀约留痕行 T1（留痕改造 2026-08-31·图纸 §7 T1-1）：[OfflineInviteData.llmRepresentation] 的三态措辞 /
 * 空字段省略 / 非邀约型返 null，以及**措辞强耦合的三条负正向约束**（不含「邀约卡片」连写、以 `[系统记录：` 开头、
 * 含「线下见面邀约」）。断言从图纸 §3.1 规格独立反推（措辞在此处重新逐字打出，不引用实现常量）。
 */
class OfflineInviteLlmRepresentationTest {

    private fun invite(
        location: String? = "咖啡馆",
        activity: String? = "喝咖啡",
        responded: String? = null,
    ) = OfflineInviteData(
        type = OfflineInviteJson.TYPE_INVITE,
        location = location,
        activity = activity,
        invitation = "走吧，我知道一家新开的咖啡厅~", // 台词：绝不该出现在留痕行
        tensionHint = "她今天有点心事",
        hiddenTension = "她其实在等一个消息",
        responded = responded,
    )

    // ── 三态措辞（E3）──

    @Test fun `未回应态_responded为null`() {
        assertEquals(
            "[系统记录：你向小满发出了线下见面邀约 | 地点=咖啡馆 | 活动=喝咖啡 | 状态=对方还没回应]",
            invite(responded = null).llmRepresentation("小满"),
        )
    }

    @Test fun `接受态_用过去式表述见过面`() {
        assertEquals(
            "[系统记录：你向小满发出了线下见面邀约 | 地点=咖啡馆 | 活动=喝咖啡 | 状态=对方接受了，你们随后见了面]",
            invite(responded = "accepted").llmRepresentation("小满"),
        )
    }

    @Test fun `婉拒态`() {
        assertEquals(
            "[系统记录：你向小满发出了线下见面邀约 | 地点=咖啡馆 | 活动=喝咖啡 | 状态=对方婉拒了，这次没见成]",
            invite(responded = "declined").llmRepresentation("小满"),
        )
    }

    @Test fun `continued与未知值一律按未回应渲染`() {
        val continued = invite(responded = "continued").llmRepresentation("小满")
        val unknown = invite(responded = "some_future_value").llmRepresentation("小满")
        assertTrue("continued 应按未回应，实际：$continued", continued!!.endsWith("状态=对方还没回应]"))
        assertTrue("未知值应按未回应，实际：$unknown", unknown!!.endsWith("状态=对方还没回应]"))
    }

    // ── 空字段省略（E4）──

    @Test fun `空白地点被省略_只留活动`() {
        assertEquals(
            "[系统记录：你向用户发出了线下见面邀约 | 活动=喝咖啡 | 状态=对方还没回应]",
            invite(location = "   ").llmRepresentation(),
        )
    }

    @Test fun `空白活动被省略_只留地点`() {
        assertEquals(
            "[系统记录：你向用户发出了线下见面邀约 | 地点=咖啡馆 | 状态=对方还没回应]",
            invite(activity = null).llmRepresentation(),
        )
    }

    @Test fun `地点与活动皆空_只剩动作加状态_行仍合法`() {
        assertEquals(
            "[系统记录：你向用户发出了线下见面邀约 | 状态=对方还没回应]",
            invite(location = null, activity = "").llmRepresentation(),
        )
    }

    // ── 非邀约型（E2）──

    @Test fun `结束型卡返null_调用方整条跳过`() {
        val end = OfflineInviteData(type = OfflineInviteJson.TYPE_END, finalMood = "warm", farewell = "路口再见")
        assertNull(end.llmRepresentation("小满"))
    }

    // ── 措辞强耦合三约束（图纸 §3.1 ⚠️ 段）──

    @Test fun `留痕行永不含邀约卡片四字连写_否则会被解析成新卡`() {
        for (state in listOf(null, "accepted", "declined", "continued")) {
            val line = invite(responded = state).llmRepresentation("小满")!!
            assertFalse("留痕行绝不能含「邀约卡片」连写，实际：$line", line.contains("邀约卡片"))
        }
    }

    @Test fun `留痕行以系统记录冒号开头且含线下见面邀约_供复读折叠`() {
        val line = invite().llmRepresentation("小满")!!
        assertTrue(line.startsWith("[系统记录："))
        assertTrue(line.contains("线下见面邀约"))
    }

    @Test fun `留痕行不含台词与心事种子`() {
        val line = invite(responded = "declined").llmRepresentation("小满")!!
        assertFalse("不应漏 invitation 台词，实际：$line", line.contains("新开的咖啡厅"))
        assertFalse("不应漏 tensionHint，实际：$line", line.contains("她今天有点心事"))
        assertFalse("不应漏 hiddenTension，实际：$line", line.contains("她其实在等一个消息"))
    }
}
