package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.prompt.memory.MemoryService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 见面摘要「记录名字化」机制（图纸 2026-07-15-见面摘要总结提示词优化 §D-2 / T2-1）：喂进见面摘要的对话记录
 * 经 [MemoryService.formatMessages] 传真实名字标签后，说话人渲染成名字（阿泽：/夏晴子：），不再是通用「用户：/角色：」。
 * 断言从 D-2 规格独立反推。Robolectric：安卓依赖保留（formatTimestamp 自 2026-09-01 件⑤起改 Locale.ROOT 纯 JVM）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineMeetingSummaryRecordLabelTest {

    private fun userMsg(content: String) = MessageEntity(
        messageUUID = "u1", conversationUuid = "conv", roleRaw = "user", content = content,
        timestamp = 1_700_000_000_000L, messageKindRaw = "plain_text",
    )

    private fun charMsg(content: String) = MessageEntity(
        messageUUID = "c1", conversationUuid = "conv", roleRaw = "assistant", content = content,
        timestamp = 1_700_000_001_000L, messageKindRaw = "plain_text",
    )

    // T2-1：传昵称/角色名作 label → 说话人渲染成名字，不残留通用「用户：/角色：」。
    @Test
    fun formatMessages_meetingSummaryLabels_rendersRealNames() {
        val out = MemoryService.formatMessages(
            listOf(userMsg("今天真开心"), charMsg("我也是")),
            userLabel = "阿泽", charLabel = "夏晴子",
        )
        assertTrue("用户侧应渲染成昵称: $out", out.contains("阿泽："))
        assertTrue("角色侧应渲染成角色名: $out", out.contains("夏晴子："))
        assertFalse("不应残留通用「用户：」标签: $out", out.contains("用户："))
        assertFalse("不应残留通用「角色：」标签: $out", out.contains("角色："))
    }
}
