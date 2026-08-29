package com.situ.aichat.offline

import com.situ.aichat.sticker.StickerTagParser

/**
 * 线下模式 AI 输出的 10 种结构化内容块（1:1 iOS `OfflineContentBlock`）。渲染层（10.2e）按类型差异化绘制。
 */
sealed interface OfflineContentBlock {
    /** ① 场景标题（地点 · 时间）— 居中装饰。 */
    data class SceneHeader(val location: String, val time: String) : OfflineContentBlock
    /** ② 环境描写 — serif 居中暖色。 */
    data class Environment(val text: String) : OfflineContentBlock
    /** ③ 叙述（第三人称）— serif 居中正文色。 */
    data class Narration(val text: String) : OfflineContentBlock
    /** ④ 角色对话 — 头像 +「」左对齐。 */
    data class CharacterDialogue(val text: String) : OfflineContentBlock
    /** ⑤ 角色动作 — 斜体 + 左色条。 */
    data class Action(val text: String) : OfflineContentBlock
    /** ⑥ 内心独白 — 💭 + 虚线框。 */
    data class InnerMonologue(val text: String) : OfflineContentBlock
    /** ⑦ 情绪 — 柔和 tint 背景。 */
    data class Emotion(val text: String) : OfflineContentBlock
    /** ⑧ 用户行为（描写用户的动作/对话）— 头像右对齐。 */
    data class UserAction(val text: String) : OfflineContentBlock
    /** ⑨ 时间流逝 — 居中虚线。 */
    data class TimeSkip(val text: String) : OfflineContentBlock
    /** ⑩ 场景过渡 — 装饰线。 */
    data object SceneTransition : OfflineContentBlock
}

/**
 * 把线下模式 AI 回复里的结构化标签解析为内容块数组——1:1 iOS `OfflineContentParser`。
 *
 * 用**逐行状态机**（不是正则，更能容忍 AI 的格式变体）：开/闭标签、单行标签（[场景：地点·时间]/[过渡]/
 * [时间：xxx]）、未标记裸文本兜底为叙述、完全没解析出块时整段作叙述。解析前先去表情包标签 + 行首
 * Markdown 前缀 + 跳过 LLM 复读的 offline JSON 元数据。纯函数，单测断言反推 iOS。
 */
object OfflineContentParser {

    /** 当前正在收集的标签类型（rawValue = 中文标签名，1:1 iOS ActiveTag）。 */
    private enum class ActiveTag(val raw: String) {
        ENVIRONMENT("环境"), NARRATION("叙述"), DIALOGUE("对话"), ACTION("动作"),
        MONOLOGUE("内心"), EMOTION("情绪"), USER_ACTION("你"), TIME_SKIP("时间"),
    }

    /** 开标签 marker → ActiveTag（顺序 1:1 iOS allTags）。 */
    private val allTags: List<Pair<String, ActiveTag>> = listOf(
        "[环境]" to ActiveTag.ENVIRONMENT,
        "[叙述]" to ActiveTag.NARRATION,
        "[对话]" to ActiveTag.DIALOGUE,
        "[动作]" to ActiveTag.ACTION,
        "[内心]" to ActiveTag.MONOLOGUE,
        "[情绪]" to ActiveTag.EMOTION,
        "[你]" to ActiveTag.USER_ACTION,
        "[时间]" to ActiveTag.TIME_SKIP,
    )

    /**
     * 将 AI 回复文本解析为内容块数组（1:1 iOS `parse` + D1 加固·2026-07-06）。
     *
     * D1 解析端加固两刀（只动解析端、提示词格式零碰=§5 强耦合红线）：
     * ① 同行多标签：一行里闭合标签之后的剩余文本**重新入队**继续解析（旧行为=静默丢弃——DeepSeek 等
     *    主流模型偶发把多块压一行，丢的是真内容）；
     * ② 全角写法归一化：【环境】/【/环境】/【场景：…】等已知标签的全角括号变体先归一为半角再解析
     *    （旧行为=当普通文本渲染、标签字面漏出）。未知自创标签仍按普通文本处理（不猜测语义）。
     */
    fun parse(content: String): List<OfflineContentBlock> {
        // 先清除表情包标签（[sticker:xxx]），避免被当普通文本渲染。
        val cleaned = StickerTagParser.stripStickerTags(content)
        val trimmed = cleaned.trim()
        if (trimmed.isEmpty()) return emptyList()

        val blocks = ArrayList<OfflineContentBlock>()
        var activeTag: ActiveTag? = null
        val buffer = ArrayList<String>()

        fun flushBuffer() {
            val text = buffer.joinToString("\n").trim()
            if (text.isEmpty()) {
                buffer.clear()
                return
            }
            blocks.add(makeBlock(activeTag, text))
            buffer.clear()
        }

        // D1①：行队列替代 for 循环——同行多标签时把闭合点之后的剩余段推回队首继续处理。
        val pending = ArrayDeque<String>()
        trimmed.lines().forEach { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val rawLine = pending.removeFirst()
            val stripped = stripMarkdownPrefix(normalizeFullWidthTags(rawLine.trim()))

            if (stripped.isEmpty()) continue
            // 跳过 LLM 复读的 offline_end/offline_invite JSON 对象。
            if (isOfflineMetadataJSON(stripped)) continue

            // [场景：地点 · 时间] — 单行标签
            val scene = extractInlineTag(stripped, "场景")
            if (scene != null) {
                flushBuffer()
                activeTag = null
                val (sceneContent, sceneRest) = scene
                // filter 去空子串 = iOS split(omittingEmptySubsequences:true)，对齐畸形 [场景：·黄昏]（空地点）的归并（复核 LOW#8）。
                val parts = sceneContent.split("·", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
                val location = parts.firstOrNull() ?: sceneContent
                val time = if (parts.size > 1) parts[1] else ""
                blocks.add(OfflineContentBlock.SceneHeader(location, time))
                if (sceneRest.isNotEmpty()) pending.addFirst(sceneRest)
                continue
            }

            // [过渡] — 单行标签
            if (stripped.startsWith("[过渡]")) {
                flushBuffer()
                activeTag = null
                blocks.add(OfflineContentBlock.SceneTransition)
                val rest = stripped.removePrefix("[过渡]").trim()
                if (rest.isNotEmpty()) pending.addFirst(rest)
                continue
            }

            // [时间：xxx] — 单行简写
            val time = extractInlineTag(stripped, "时间")
            if (time != null) {
                flushBuffer()
                activeTag = null
                blocks.add(OfflineContentBlock.TimeSkip(time.first))
                if (time.second.isNotEmpty()) pending.addFirst(time.second)
                continue
            }

            // 开标签
            val open = detectOpenTag(stripped)
            if (open != null) {
                val (tag, remaining) = open
                flushBuffer()
                activeTag = tag
                val closeMarker = "[/${tag.raw}]"
                val closeIdx = remaining.indexOf(closeMarker)
                if (closeIdx >= 0) {
                    buffer.add(remaining.substring(0, closeIdx).trim())
                    flushBuffer()
                    activeTag = null
                    val rest = remaining.substring(closeIdx + closeMarker.length).trim()
                    if (rest.isNotEmpty()) pending.addFirst(rest)
                } else if (remaining.isNotEmpty()) {
                    buffer.add(remaining)
                }
                continue
            }

            // 闭标签（取位置最靠前的那个，闭合点之后的剩余段入队）
            val close = detectCloseTag(stripped)
            if (close != null) {
                val (beforeClose, closedTag, afterClose) = close
                if (beforeClose.isNotEmpty()) buffer.add(beforeClose)
                if (activeTag == closedTag || activeTag == null) {
                    flushBuffer()
                    activeTag = null
                }
                if (afterClose.isNotEmpty()) pending.addFirst(afterClose)
                continue
            }

            // 普通文本行
            buffer.add(stripped)
        }

        flushBuffer()

        // 完全没解析出块 → 整段作为叙述。
        if (blocks.isEmpty()) return listOf(OfflineContentBlock.Narration(trimmed))
        return blocks
    }

    /** 根据标签类型创建内容块；nil → 叙述兜底（1:1 iOS makeBlock）。 */
    private fun makeBlock(tag: ActiveTag?, text: String): OfflineContentBlock = when (tag) {
        ActiveTag.ENVIRONMENT -> OfflineContentBlock.Environment(text)
        ActiveTag.NARRATION -> OfflineContentBlock.Narration(text)
        ActiveTag.DIALOGUE -> OfflineContentBlock.CharacterDialogue(text)
        ActiveTag.ACTION -> OfflineContentBlock.Action(text)
        ActiveTag.MONOLOGUE -> OfflineContentBlock.InnerMonologue(text)
        ActiveTag.EMOTION -> OfflineContentBlock.Emotion(text)
        ActiveTag.USER_ACTION -> OfflineContentBlock.UserAction(text)
        ActiveTag.TIME_SKIP -> OfflineContentBlock.TimeSkip(text)
        null -> OfflineContentBlock.Narration(text)
    }

    /**
     * 提取 [前缀：内容] / [前缀:内容] 单行标签 → (内容, 首个 `]` 之后的剩余文本)。
     * D1①：改按**首个** `]` 收口（旧=要求整行以 `]` 结尾），标签后还挤了别的内容时剩余段交调用方入队。
     */
    private fun extractInlineTag(line: String, prefix: String): Pair<String, String>? {
        for (sep in listOf("：", ":")) {
            val open = "[$prefix$sep"
            if (line.startsWith(open)) {
                val end = line.indexOf(']', open.length)
                if (end <= open.length) return null
                return line.substring(open.length, end).trim() to line.substring(end + 1).trim()
            }
        }
        return null
    }

    /** 检测开标签 → (类型, 标签后剩余文本)（1:1 iOS detectOpenTag）。 */
    private fun detectOpenTag(line: String): Pair<ActiveTag, String>? {
        for ((marker, tag) in allTags) {
            if (line.startsWith(marker)) {
                val remaining = line.substring(marker.length).trim()
                return tag to remaining
            }
        }
        return null
    }

    /**
     * 检测闭标签 → (闭标签前文本, 类型, 闭标签后剩余文本)。D1①：按**位置最靠前**的闭标签收口
     * （旧=按枚举顺序取第一个命中，同行多闭标签时会切错位置），剩余文本交调用方入队。
     */
    private fun detectCloseTag(line: String): Triple<String, ActiveTag, String>? {
        var bestTag: ActiveTag? = null
        var bestIdx = Int.MAX_VALUE
        for (tag in ActiveTag.entries) {
            val idx = line.indexOf("[/${tag.raw}]")
            if (idx in 0 until bestIdx) {
                bestIdx = idx
                bestTag = tag
            }
        }
        val tag = bestTag ?: return null
        val marker = "[/${tag.raw}]"
        return Triple(
            line.substring(0, bestIdx).trim(),
            tag,
            line.substring(bestIdx + marker.length).trim(),
        )
    }

    /** D1②：已知标签的全角【】写法归一化为半角 [ ]（主流模型偶发变体；未知标签不动）。 */
    private val fullWidthTagRegex =
        Regex("""【(/?(?:环境|叙述|对话|动作|内心|你|情绪|时间|过渡)|场景[：:][^】]*|时间[：:][^】]*)】""")

    internal fun normalizeFullWidthTags(line: String): String =
        fullWidthTagRegex.replace(line) { "[${it.groupValues[1]}]" }

    /** 用户沉浸输入的 4 种标签映射（"对话"→用户行为 UserAction，1:1 iOS userTagMapping）。 */
    private val userTagMapping: List<Pair<String, (String) -> OfflineContentBlock>> = listOf(
        "环境" to { t: String -> OfflineContentBlock.Environment(t) },
        "动作" to { t: String -> OfflineContentBlock.Action(t) },
        "对话" to { t: String -> OfflineContentBlock.UserAction(t) },
        "内心" to { t: String -> OfflineContentBlock.InnerMonologue(t) },
    )

    /**
     * 解析用户沉浸模式消息中的标签（1:1 iOS `parseUserBlocks`）。缺失标签跳过（支持只填部分字段）；
     * 返回空 = 非沉浸消息（兼容普通文本）。
     *
     * 卷一 E2：先把表情包标签换成 `[表情包]`（AI 侧 [parse] 早有同款前置清洗）——见面中用户发的表情包
     * 原先会在剧场里露出 `[sticker:xxx]` 字面量。
     */
    fun parseUserBlocks(rawContent: String): List<OfflineContentBlock> {
        val content = StickerTagParser.replaceStickerTagsForDisplay(rawContent)
        val blocks = ArrayList<OfflineContentBlock>()
        for ((tag, builder) in userTagMapping) {
            val openTag = "[$tag]"
            val closeTag = "[/$tag]"
            val openIdx = content.indexOf(openTag)
            if (openIdx < 0) continue
            val closeIdx = content.indexOf(closeTag, openIdx + openTag.length)
            if (closeIdx < 0) continue
            val text = content.substring(openIdx + openTag.length, closeIdx).trim()
            if (text.isNotEmpty()) blocks.add(builder(text))
        }
        return blocks
    }

    /** 缓存正则：清理所有线下模式标签（字面中文标签名，非 \p{Han}，JVM 可用）。 */
    private val tagCleanupRegex =
        Regex("""\[/?(?:叙述|对话|内心|你|过渡|环境|动作|情绪|时间)\]|\[场景[：:][^\]]*\]|\[时间[：:][^\]]*\]""")

    /** 从文本移除所有线下标签（非线下渲染路径的兜底清理，1:1 iOS stripAllTags）。 */
    fun stripAllTags(text: String): String =
        tagCleanupRegex.replace(text, "")
            .replace("\n\n\n", "\n\n")
            .trim()

    /** 一行是否为 LLM 复读的线下 JSON 元数据（1:1 iOS isOfflineMetadataJSON）。 */
    private fun isOfflineMetadataJSON(line: String): Boolean {
        if (!line.startsWith("{") || !line.endsWith("}")) return false
        return line.contains("\"offline_end\"") || line.contains("\"offline_invite\"")
    }

    /** 去行首 Markdown 列表/引用前缀（> / - * + / 1.），保证标签检测不受干扰（1:1 iOS stripMarkdownPrefix）。 */
    private fun stripMarkdownPrefix(line: String): String {
        var s = line
        // 引用前缀 >（可能多层）
        while (s.startsWith(">")) {
            s = s.drop(1).trim()
        }
        // 无序列表 - / * / +
        if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ ")) {
            s = s.drop(2).trim()
        }
        // 有序列表 1. 2.（"." 位置须 < min(4, 长度)，前缀全数字，后跟空格）
        val dotIdx = s.indexOf('.')
        if (dotIdx in 0 until minOf(4, s.length)) {
            val numPart = s.substring(0, dotIdx)
            if (numPart.isNotEmpty() && numPart.all { it.isDigit() }) {
                val afterDot = dotIdx + 1
                if (afterDot < s.length && s[afterDot] == ' ') {
                    s = s.substring(afterDot + 1).trim()
                }
            }
        }
        return s
    }
}
