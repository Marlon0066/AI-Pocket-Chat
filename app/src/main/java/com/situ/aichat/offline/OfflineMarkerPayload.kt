package com.situ.aichat.offline

/**
 * 线下见面入场/离场**标记消息**的结构化 payload（1:1 iOS `OfflineMarkerStartPayload`/`OfflineMarkerEndPayload`，
 * `MessageContent.swift`）。标记消息 AI 可见、用户不可见（[com.situ.aichat.data.model.MessageKind] 的
 * OFFLINE_MARKER_START / OFFLINE_MARKER_END），content 存的是 [makeContent] 产出的**人读文本**（非 JSON），
 * 字节级对齐 iOS——LLM 直接读这段文本理解「进入/退出线下模式」。[parse] 反解析回 payload（供 SceneProgress
 * 提取心事种子/地点、PromptBuilder 注入 tensionSeed、状态机校验）。
 */
data class OfflineMarkerStartPayload(
    val location: String,
    val activity: String,
    val timeString: String,
    val tensionSeed: String? = null,
) {
    /**
     * 序列化为入场标记文本（字节级对齐 iOS makeContent）：
     * `【线下见面开始 | 地点：X | 活动：Y | 时间：T】\n从现在起你们面对面在一起，不再是手机聊天。`
     * tensionSeed 非空时追加 `\n【今日场景种子】<seed>`。
     */
    fun makeContent(): String {
        var text = "【线下见面开始 | 地点：$location | 活动：$activity | 时间：$timeString】\n从现在起你们面对面在一起，不再是手机聊天。"
        if (!tensionSeed.isNullOrEmpty()) {
            text += "\n【今日场景种子】$tensionSeed"
        }
        return text
    }

    companion object {
        /** 反解析入场标记文本 → payload；格式异常返回 null（调用方 fallback 到普通文本，1:1 iOS parse）。 */
        fun parse(rawContent: String): OfflineMarkerStartPayload? {
            if (!rawContent.startsWith("【线下见面开始")) return null
            val bracketEnd = rawContent.indexOf('】')
            if (bracketEnd < 0) return null

            // 截取 "【...】" 中 "|" 之间的字段；parts[0]="线下见面开始"，其后为 地点/活动/时间。
            val inner = rawContent.substring(1, bracketEnd)
            val parts = inner.split("|").map { it.trim() }
            if (parts.size < 4) return null

            var location: String? = null
            var activity: String? = null
            var timeString: String? = null
            for (part in parts.drop(1)) {
                val loc = stripOfflineMarkerLabel(part, "地点")
                val act = stripOfflineMarkerLabel(part, "活动")
                val time = stripOfflineMarkerLabel(part, "时间")
                when {
                    loc != null -> location = loc
                    act != null -> activity = act
                    time != null -> timeString = time
                }
            }
            val loc = location ?: return null
            val act = activity ?: return null
            val time = timeString ?: return null

            // 场景种子（可选）：取 "【今日场景种子】" 之后的文本，trim 后空则视为无。
            var tensionSeed: String? = null
            val seedMarker = "【今日场景种子】"
            val seedIdx = rawContent.indexOf(seedMarker)
            if (seedIdx >= 0) {
                val seed = rawContent.substring(seedIdx + seedMarker.length).trim()
                tensionSeed = seed.ifEmpty { null }
            }

            return OfflineMarkerStartPayload(loc, act, time, tensionSeed)
        }
    }
}

data class OfflineMarkerEndPayload(
    val durationText: String,
    val timeString: String,
    /** 结束原因（如「你们自然地结束了这次见面」/「用户主动结束了这次见面」）。 */
    val reasonText: String,
) {
    /**
     * 序列化为离场标记文本（字节级对齐 iOS makeContent）：
     * `【线下见面结束 | 时长：X | 时间：T】\n<reason>。现在恢复正常线上聊天模式。\n【重要】…`
     */
    fun makeContent(): String =
        "【线下见面结束 | 时长：$durationText | 时间：$timeString】\n$reasonText。现在恢复正常线上聊天模式。\n【重要】从现在起不要再使用 [叙述][对话][内心] 等任何标签，像平时发微信一样正常说话。你可以自然地回顾和提及刚才见面时发生的事情。"

    /**
     * 普通聊天窗口的一行脱敏表示（留痕改造 2026-08-31）：只露时长，不带 [makeContent] 的【重要】指令段
     * （那段只服务见面刚结束那一轮，不该永驻历史）。措辞强耦合：以「[系统记录：」开头且含「线下见面结束」
     * ——AI 复读由 [com.situ.aichat.prompt.DirtyMessageDetector] matchesSystemRecordLabel 既有 marker 折叠。
     */
    fun llmRepresentation(): String = "[系统记录：线下见面结束（$durationText），两人回到了线上聊天]"

    companion object {
        /** 反解析离场标记文本 → payload；格式异常返回 null（1:1 iOS parse）。 */
        fun parse(rawContent: String): OfflineMarkerEndPayload? {
            if (!rawContent.startsWith("【线下见面结束")) return null
            val bracketEnd = rawContent.indexOf('】')
            if (bracketEnd < 0) return null

            val inner = rawContent.substring(1, bracketEnd)
            val parts = inner.split("|").map { it.trim() }
            if (parts.size < 3) return null

            var durationText: String? = null
            var timeString: String? = null
            for (part in parts.drop(1)) {
                val dur = stripOfflineMarkerLabel(part, "时长")
                val time = stripOfflineMarkerLabel(part, "时间")
                when {
                    dur != null -> durationText = dur
                    time != null -> timeString = time
                }
            }
            val dur = durationText ?: return null
            val time = timeString ?: return null

            // reasonText 在 "】" 之后到 "。现在恢复正常线上聊天模式。" 之前。
            val afterBracket = rawContent.substring(bracketEnd + 1).trim()
            val terminator = "。现在恢复正常线上聊天模式。"
            val reasonEnd = afterBracket.indexOf(terminator)
            if (reasonEnd < 0) return null
            val reason = afterBracket.substring(0, reasonEnd).trim()

            return OfflineMarkerEndPayload(dur, time, reason)
        }
    }
}

/**
 * 从「标签：值」剥掉「标签：」前缀返回值（兼容全角 `：` / 半角 `:`，1:1 iOS stripLabel）。
 * 例 `stripOfflineMarkerLabel("地点：咖啡馆", "地点")` → `"咖啡馆"`；不匹配返回 null。
 */
internal fun stripOfflineMarkerLabel(text: String, label: String): String? {
    val fullColon = "$label："
    val halfColon = "$label:"
    return when {
        text.startsWith(fullColon) -> text.substring(fullColon.length)
        text.startsWith(halfColon) -> text.substring(halfColon.length)
        else -> null
    }
}
