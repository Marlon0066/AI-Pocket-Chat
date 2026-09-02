package com.situ.aichat.prompt.ourdays

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.kind
import com.situ.aichat.prompt.memory.MemoryService

/**
 * 「用户当前消息组」文本（卷二图纸 §3.3 锁定·W-4）：`filteredMessages` 尾部**连续** `roleRaw == "user"` 的消息
 * （合并连发·遇第一条非 user 即停），过滤空内容 / 系统耳语 / 结构化卡，每条经 [MemoryService.renderMemoryContent]
 * 与记忆 / 向量同源渲染（表情包标签语义化 / 图片摘要），按时间升序 `"\n"` 拼接；超过 [MAX_CODE_POINTS] 保尾。
 * 只在 [PromptBuilder.buildMessages] 从真实窗口派生一次（调用方重算会漂）。纯函数。
 */
internal object OurDaysTurnText {

    /** 消息组上限（§9.2 锁定 600 码点）。 */
    const val MAX_CODE_POINTS = 600

    fun from(filteredMessages: List<MessageEntity>): String {
        val group = ArrayList<MessageEntity>()
        for (m in filteredMessages.asReversed()) {
            if (m.roleRaw != PromptBuilder.ROLE_USER) break
            group.add(m)
        }
        if (group.isEmpty()) return ""
        val lines = group.asReversed()
            .filter { it.content.isNotEmpty() && it.kind() != MessageKind.SYSTEM_HINT && !it.kind().isStructuredCard }
            .map { MemoryService.renderMemoryContent(it.content, it.mediaMemorySummary, it.imageRelativePath != null) }
            .toMutableList()
        // 保尾：从头逐行删至 ≤ 600 码点；只剩最后一行仍超长 → 截其尾 600 码点（不整段丢掉·R1 核准施工 O-3·图纸 §3.3 已回填）。
        while (lines.size > 1 && codePoints(lines.joinToString("\n")) > MAX_CODE_POINTS) lines.removeAt(0)
        val text = lines.joinToString("\n")
        val total = codePoints(text)
        if (total <= MAX_CODE_POINTS) return text
        return text.substring(text.offsetByCodePoints(0, total - MAX_CODE_POINTS))
    }

    private fun codePoints(s: String): Int = s.codePointCount(0, s.length)
}
