package com.situ.aichat.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 内容过滤规则（移植 iOS `ContentFilterService.ContentFilterRule`）。预设规则 isPreset=true、不可删除/不可改正则，
 * 仅可开关；自定义规则用户自填正则 + 模式。`useMultilineAnchors` 仅预设规则指定（控制 `^`/`$` 是否多行匹配），
 * 自定义规则默认 null（= 不加多行）。`@Serializable`：整份规则列表以 JSON 存进 `AppSettings.contentFilterRulesJSON`。
 */
@Serializable
data class ContentFilterRule(
    val id: String,
    val name: String,
    val pattern: String,
    val isEnabled: Boolean,
    val isPreset: Boolean,
    val mode: FilterMode,
    val replacement: String = "",
    val useMultilineAnchors: Boolean? = null,
)

/** 过滤模式（1:1 iOS `FilterMode`）：remove=删除匹配内容 / replace=替换为指定文字。JSON raw 对齐 iOS（小写）。 */
@Serializable
enum class FilterMode {
    @SerialName("remove")
    REMOVE,

    @SerialName("replace")
    REPLACE;

    val displayName: String
        get() = when (this) {
            REMOVE -> "删除"
            REPLACE -> "替换"
        }
}

/**
 * 内容过滤服务（移植 iOS `Services/ContentFilterService.swift`）：对 AI 回复正文按用户规则做删除/替换净化。
 * 5 条预设（默认全关）+ 任意自定义正则规则；在「sanitize 之后、表情包归一 / 分条之前」对每条 AI 正文调用
 * [applyFilters]（接线于 [com.situ.aichat.ui.chat.ChatViewModel] 主聊天路径 + [com.situ.aichat.busyreply.BusyReplyService]）。
 *
 * **平台等价（移植取舍）**：iOS 用 `NSRegularExpression`（ICU 引擎，支持 `\p&#123;Emoji_Presentation&#125;` 等 Unicode
 * Emoji 二进制属性）。安卓 **运行时** `java.util.regex` 虽也是 ICU 后端、支持该属性，但 **单元测试 JVM**
 * （OpenJDK/JBR）不支持裸 `\p&#123;Emoji_Presentation&#125;`（仅认 `\p&#123;IsEmoji_Presentation&#125;`）。为保证「测试路径 ==
 * 生产路径」且跨安卓版本稳定，**连续 Emoji 预设特判为纯 Kotlin 码点扫描** [removeConsecutiveEmoji]（与 iOS 正则
 * 行为等价：连续 ≥3 个「Emoji 展示码点(+可选肤色修饰符)」单元整体删除）——同 iOS 自身对 Markdown 预设的特判思路。
 * 码点表 [EMOJI_PRESENTATION_RANGES] 由 JVM Unicode 数据机器生成（与 ICU 同源），单测断言其与 `\p&#123;IsEmoji_Presentation&#125;`
 * 全平面等价。其余预设 / 自定义正则仍走真实正则引擎（`NSRegularExpression` → Kotlin [Regex]；`.anchorsMatchLines`
 * → [RegexOption.MULTILINE]；`withTemplate` 模板语义 `$1`/`\` 两端一致）。
 */
object ContentFilterService {

    // MARK: - 预设规则 ID（固定，对齐 iOS；注意缺 002=iOS 已删的「括号旁白」）

    const val PRESET_ACTION_ID = "00000001-0000-0000-0000-000000000001"
    const val PRESET_THINKING_ID = "00000001-0000-0000-0000-000000000003"
    const val PRESET_EMOJI_ID = "00000001-0000-0000-0000-000000000004"
    const val PRESET_SEPARATOR_ID = "00000001-0000-0000-0000-000000000005"
    const val PRESET_MARKDOWN_ID = "00000001-0000-0000-0000-000000000006"

    /** 默认预设规则列表（全部默认关闭 isEnabled=false，用户按需开启；1:1 iOS defaultPresetRules）。 */
    fun defaultPresetRules(): List<ContentFilterRule> = listOf(
        ContentFilterRule(
            id = PRESET_ACTION_ID,
            name = "星号动作描述",
            pattern = """\*[^*\n]+\*""",
            isEnabled = false,
            isPreset = true,
            mode = FilterMode.REMOVE,
            replacement = "",
        ),
        ContentFilterRule(
            id = PRESET_THINKING_ID,
            name = "思考标签",
            pattern = """<(?:\|)?(?:think(?:ing)?|thought|reasoning)(?:\|)?>[\s\S]*?(?:<[/|]*(?:think(?:ing)?|thought|reasoning)(?:\|)?>|\z)""",
            isEnabled = false,
            isPreset = true,
            mode = FilterMode.REMOVE,
            replacement = "",
        ),
        ContentFilterRule(
            id = PRESET_EMOJI_ID,
            name = "连续 Emoji",
            // 存储 iOS 原文（用于规则身份 / loadRules 版本同步）；实际过滤特判走 [removeConsecutiveEmoji]，不编译此 pattern。
            pattern = """(\p{Emoji_Presentation}\p{Emoji_Modifier}*){3,}""",
            isEnabled = false,
            isPreset = true,
            mode = FilterMode.REMOVE,
            replacement = "",
        ),
        ContentFilterRule(
            id = PRESET_SEPARATOR_ID,
            name = "分隔线",
            pattern = """^-{3,}$""",
            isEnabled = false,
            isPreset = true,
            mode = FilterMode.REMOVE,
            replacement = "",
            useMultilineAnchors = true,
        ),
        ContentFilterRule(
            id = PRESET_MARKDOWN_ID,
            name = "Markdown 格式",
            pattern = """(?:\*\*(.+?)\*\*|__(.+?)__|(?:^|\n)(#{1,6})\s)""",
            isEnabled = false,
            isPreset = true,
            mode = FilterMode.REMOVE,
            replacement = "",
        ),
    )

    /** 预设规则简短说明（设置页展示；逐字对齐 iOS presetDescription）。 */
    fun presetDescription(ruleId: String): String = when (ruleId) {
        PRESET_ACTION_ID -> "过滤 *叹了口气* 等星号包裹的动作描述"
        PRESET_THINKING_ID -> "过滤 <think>/<thinking>/<thought>/<reasoning> 等思考过程标签"
        PRESET_EMOJI_ID -> "过滤连续 3 个以上 emoji 的堆砌"
        PRESET_SEPARATOR_ID -> "过滤 --- 等分隔线"
        PRESET_MARKDOWN_ID -> "去除 **加粗**、# 标题 等格式符号"
        else -> ""
    }

    // MARK: - 缓存（避免每条消息重复编译正则 / 重复 JSON 解码；iOS unfair lock → 安卓并发安全容器）

    /** 已编译正则缓存，key = pattern + "|" + multiline。编译失败不缓存（与 iOS 一致：每次返回 null）。 */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /** 规则列表缓存（最近一次 json → 解码结果）。lock-free best-effort，并发只会重复解码、不会损坏。 */
    private val rulesCache = AtomicReference<Pair<String, List<ContentFilterRule>>?>(null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rulesSerializer = ListSerializer(ContentFilterRule.serializer())

    private fun cachedRegex(pattern: String, multiline: Boolean): Regex? {
        val key = "$pattern|$multiline"
        regexCache[key]?.let { return it }
        return try {
            val regex = if (multiline) Regex(pattern, RegexOption.MULTILINE) else Regex(pattern)
            regexCache[key] = regex
            regex
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - 规则存取

    /**
     * 从 JSON 解码规则列表（1:1 iOS loadRules 的解码 + 迁移逻辑）。带缓存，JSON 不变直接返回。
     *
     * **安卓地道分叉**：iOS loadRules 在 JSON 空时会 **写回** 默认预设到 AppSettings（惰性初始化副作用）。安卓
     * `AppSettings` 是不可变读模型快照，本函数保持 **纯**（空 → 返回默认、不持久化）；首次写回默认由设置页 VM
     * 显式落库。聊天热路径只需有效规则做过滤——预设默认全关，applyFilters 本就是 no-op，无需持久化即等价。
     */
    fun loadRules(rulesJson: String): List<ContentFilterRule> {
        if (rulesJson.isEmpty()) return defaultPresetRules()

        rulesCache.get()?.let { if (it.first == rulesJson && it.second.isNotEmpty()) return it.second }

        val decoded = runCatching { json.decodeFromString(rulesSerializer, rulesJson) }.getOrNull()
        if (decoded == null) {
            val presets = defaultPresetRules()
            rulesCache.set(rulesJson to presets)
            return presets
        }

        val rules = decoded.toMutableList()
        // 移除已被删除的预设规则（如 002「括号旁白」），避免旧数据残留。
        val validPresetIds = defaultPresetRules().mapTo(HashSet()) { it.id }
        rules.removeAll { it.isPreset && it.id !in validPresetIds }

        // 补充新增的预设规则（插在当前预设数量位 = 自定义规则之前；existingIds 取迭代前快照，与 iOS 一致）。
        val existingIds = rules.mapTo(HashSet()) { it.id }
        for (preset in defaultPresetRules()) {
            if (preset.id !in existingIds) {
                rules.add(rules.count { it.isPreset }, preset)
            }
        }

        // 预设正则可能因版本迭代更新，同步最新 pattern。
        val presetDefaults = defaultPresetRules().associateBy { it.id }
        for (i in rules.indices) {
            if (rules[i].isPreset) {
                val latest = presetDefaults[rules[i].id]
                if (latest != null && rules[i].pattern != latest.pattern) {
                    rules[i] = rules[i].copy(pattern = latest.pattern)
                }
            }
        }

        rulesCache.set(rulesJson to rules)
        return rules
    }

    /** 规则列表编码为 JSON（设置页保存用；对应 iOS saveRules 的 JSONEncoder 部分）。 */
    fun encodeRules(rules: List<ContentFilterRule>): String = json.encodeToString(rulesSerializer, rules)

    // MARK: - 过滤执行

    /** 对内容应用所有启用的过滤规则（1:1 iOS applyFilters）。空 / 无启用规则快返回；末了清理多余空行 + trim。 */
    fun applyFilters(content: String, rules: List<ContentFilterRule>): String {
        if (content.isEmpty()) return content
        if (rules.none { it.isEnabled }) return content

        var result = content
        for (rule in rules) {
            if (rule.isEnabled) result = applyRule(result, rule)
        }

        return result
            .replace("\n\n\n", "\n\n")
            .trim()
    }

    /** 应用单条规则（1:1 iOS applyRule）。Markdown / 连续 Emoji 预设特判，其余走正则引擎。 */
    private fun applyRule(content: String, rule: ContentFilterRule): String {
        // Markdown 格式特殊处理：保留内容文字，只去掉格式符号。
        if (rule.id == PRESET_MARKDOWN_ID && rule.mode == FilterMode.REMOVE) {
            return removeMarkdownFormatting(content)
        }
        // 连续 Emoji 特殊处理：码点扫描（等价 iOS 正则，避免跨引擎 `\p{Emoji_Presentation}` 差异，见类注释）。
        if (rule.id == PRESET_EMOJI_ID && rule.mode == FilterMode.REMOVE) {
            return removeConsecutiveEmoji(content)
        }

        val regex = cachedRegex(rule.pattern, rule.useMultilineAnchors == true) ?: return content
        return when (rule.mode) {
            FilterMode.REMOVE -> regex.replace(content, "")
            FilterMode.REPLACE -> safeTemplateReplace(regex, content, rule.replacement)
        }
    }

    /**
     * 模板替换（对齐 iOS stringByReplacingMatches(withTemplate:)：`$1` 组引用 / `\` 转义两端语义一致）。包 try/catch
     * 兜底非法模板（如用户替换文本里裸 `$`），失败时返回原文不崩溃——比 iOS 多一层防御，行为不变。
     */
    private fun safeTemplateReplace(regex: Regex, content: String, replacement: String): String =
        runCatching { regex.replace(content, replacement) }.getOrDefault(content)

    /** 去除 Markdown 格式符号但保留内容文字（1:1 iOS removeMarkdownFormatting）。 */
    private fun removeMarkdownFormatting(content: String): String {
        var result = content
        // **加粗** 或 __加粗__ → 保留内容（$1/$2 非参与组在 Kotlin/iOS 均替空，等价）。
        cachedRegex("""\*\*(.+?)\*\*|__(.+?)__""", multiline = false)?.let {
            result = it.replace(result, "\$1\$2")
        }
        // # 标题 → 去掉 # 号（保留前导换行）。**`\s` → `[\s\p{Z}]`**：iOS NSRegularExpression(ICU) 的 `\s` 含
        // `\p{Z}`（匹配全角空格 U+3000 / NBSP 等），但安卓 `java.util.regex` 的 `\s` 恒为 ASCII-only（无法开
        // UNICODE_CHARACTER_CLASS）。中文 LLM 在 `#` 后常跟全角空格，故并入 `\p{Z}`（两端引擎均支持）对齐 ICU。
        cachedRegex("""(?:^|\n)(#{1,6})[\s\p{Z}]+""", multiline = true)?.let {
            result = it.replace(result, "\n")
        }
        return result
    }

    /**
     * 删除连续 ≥3 个 emoji 的堆砌（等价 iOS 正则 `(\p{Emoji_Presentation}\p{Emoji_Modifier}*){3,}` + remove）。
     *
     * **关键：复刻回溯语义**。正则的 `{3,}` 贪婪 + `\p{Emoji_Modifier}*` 贪婪会 **回溯** 以凑足 ≥3 个单元——而肤色
     * 修饰符（U+1F3FB..1F3FF）本身又同属 `Emoji_Presentation`，故 "🏻🏻🏻" 被引擎拆成 3 个「基础」单元而非「1 基础 +
     * 2 修饰符」。因此正确判据是：一段「Emoji 展示符 或 修饰符」的极大连续运行段中，**从首个展示符到段尾、含 ≥3 个
     * `Emoji_Presentation` 码点** → 整段（含尾随修饰符）删除；首个展示符之前的纯修饰符保留。<3 则整段原样保留。
     */
    internal fun removeConsecutiveEmoji(content: String): String {
        val sb = StringBuilder(content.length)
        var i = 0
        val n = content.length
        while (i < n) {
            val cp0 = content.codePointAt(i)
            if (!isEmojiPresentation(cp0) && !isEmojiModifier(cp0)) {
                sb.appendCodePoint(cp0)
                i += Character.charCount(cp0)
                continue
            }
            // 极大「展示符 或 修饰符」运行段 [runStart, runEnd)；统计首个展示符位置 + 展示符总数。
            val runStart = i
            var j = i
            var firstBase = -1
            var baseCount = 0
            while (j < n) {
                val cp = content.codePointAt(j)
                val ep = isEmojiPresentation(cp)
                val em = isEmojiModifier(cp)
                if (!ep && !em) break
                if (ep) {
                    if (firstBase < 0) firstBase = j
                    baseCount++
                }
                j += Character.charCount(cp)
            }
            if (firstBase >= 0 && baseCount >= 3) {
                sb.append(content, runStart, firstBase) // 保留首个展示符前的纯修饰符；删 [firstBase, runEnd)
            } else {
                sb.append(content, runStart, j)          // <3 展示符或无展示符 → 整段保留
            }
            i = j
        }
        return sb.toString()
    }

    // MARK: - 测试功能（设置页规则编辑屏用）

    /** 测试单条规则对输入的过滤效果（1:1 iOS testFilter）。正则非法 → null；remove → 替空 + trim / replace → 替换。 */
    fun testFilter(content: String, pattern: String, mode: FilterMode, replacement: String): String? {
        val regex = cachedRegex(pattern, multiline = false) ?: return null
        return when (mode) {
            FilterMode.REMOVE -> regex.replace(content, "").trim()
            FilterMode.REPLACE -> safeTemplateReplace(regex, content, replacement)
        }
    }

    /** 验证正则是否有效（1:1 iOS isValidRegex）：非空 + 可编译。 */
    fun isValidRegex(pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        return cachedRegex(pattern, multiline = false) != null
    }

    // MARK: - Emoji 码点判定（表由 JVM Unicode 数据机器生成，等价 ICU `Emoji_Presentation` / `Emoji_Modifier`）

    private fun isEmojiModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    private fun isEmojiPresentation(cp: Int): Boolean {
        val arr = EMOJI_PRESENTATION_RANGES
        var lo = 0
        var hi = arr.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = arr[mid * 2]
            val end = arr[mid * 2 + 1]
            when {
                cp < start -> hi = mid - 1
                cp > end -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * `Emoji_Presentation=Yes` 码点区间（成对 start,end；升序）。机器生成自 JVM Unicode（与 iOS/安卓 ICU 同源），
     * 单测断言其在 0..0x1FFFF 全平面与 `\p{IsEmoji_Presentation}` 完全一致。80 个区间。
     * 基准 = Unicode 16.0（JDK 25 内置）。JDK 大版本升级若带来新 Unicode 版本，本表须同步扩表，
     * 否则 `emoji_codepointTableMatchesUnicodeProperty` 转红（2026-09-04 由 JDK 21→25 实际命中一次）。
     */
    private val EMOJI_PRESENTATION_RANGES = intArrayOf(
        0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3,
        0x25FD, 0x25FE, 0x2614, 0x2615, 0x2648, 0x2653, 0x267F, 0x267F,
        0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE,
        0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA,
        0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD,
        0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C,
        0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
        0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50,
        0x2B55, 0x2B55, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A, 0x1F1E6, 0x1F1FF, 0x1F201, 0x1F201, 0x1F21A, 0x1F21A,
        0x1F22F, 0x1F22F, 0x1F232, 0x1F236, 0x1F238, 0x1F23A, 0x1F250, 0x1F251,
        0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393,
        0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4,
        0x1F3F8, 0x1F43E, 0x1F440, 0x1F440, 0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D,
        0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596,
        0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC,
        0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D7, 0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC,
        0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FA89,
        0x1FA8F, 0x1FAC6, 0x1FACE, 0x1FADC, 0x1FADF, 0x1FAE9, 0x1FAF0, 0x1FAF8,
    )
}
