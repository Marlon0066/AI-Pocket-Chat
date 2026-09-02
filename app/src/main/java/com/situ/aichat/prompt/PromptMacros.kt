package com.situ.aichat.prompt

/**
 * 提示词宏目录 + 惰性解析（提示词模块编辑重设计 · Phase 0）。
 *
 * 见 [FABLE5_PROMPT_MODULE_EDIT_REDESIGN.md] §2（宏目录）/ §6（逻辑层）。
 *
 * 设计要点：
 * - **宏名 = 冻结契约**：宏会被持久化进用户编辑的模块模板字符串。新增宏可以，但改名 / 删除会让老用户模板里
 *   已写下的宏失效（变成不替换的字面量）。`{{记忆内容}}` 是现行注入模板已在用的宏（见
 *   [PromptBuilder.defaultInjectionPrompt]），尤其不可改名。
 * - **惰性求值**（契约 D8）：[resolveLazy] 只对模板中真正出现的宏求值，且每个宏至多求值一次——避免把向量检索 /
 *   DB 查询（记忆 / 礼物 / 经济等）为根本没用到该宏的模块空跑或重复跑。
 * - **纯函数、无 Android 依赖**，可直接 JVM 单测。ctx → producers 的接线在 Phase 1 落地（需 BuildContext）。
 */
object PromptMacros {

    // MARK: - 名称（scalar）

    const val CHAR = "{{char}}"
    const val USER = "{{user}}"
    const val NOW = "{{now}}"

    // MARK: - 角色资料（block）

    const val CHAR_PROFILE = "{{角色资料}}"
    const val CHAR_GROWTH = "{{角色成长}}"

    // MARK: - 用户

    const val USER_PERSONA = "{{用户人设}}"
    const val USER_CITY = "{{用户城市}}"

    /** 安卓天气尚未实现（用户自带 key 方案待排期，见 weather-geo-userkey）；该宏先注册、暂恒空。 */
    const val USER_WEATHER = "{{用户天气}}"

    // MARK: - 记忆

    const val CHAR_MEMORY = "{{角色记忆}}"

    /** 兼容现行注入模板宏（= 记忆摘要层），**勿改名**。 */
    const val MEMORY_CONTENT = "{{记忆内容}}"
    const val MEETING_MEMORY = "{{见面记忆}}"

    /** 「我们的日子」（卷二）：日期指名 + 那年今日两路的注入块；空 ⇒ 模块跳过。 */
    const val OUR_DAYS = "{{我们的日子}}"

    // MARK: - 时间 / 日程

    const val TIME_CONTEXT = "{{时间上下文}}"
    const val SCHEDULE_TODAY = "{{今日日程}}"
    const val CURRENT_MOMENT = "{{此刻状态}}"
    const val USER_CALENDAR = "{{用户日程}}"

    // MARK: - 社交 / 内容

    const val MOMENTS_CONTEXT = "{{朋友圈上下文}}"
    const val STICKER_LIBRARY = "{{表情包列表}}"
    const val PET_STATUS = "{{宠物状态}}"
    const val GIFT_HISTORY = "{{礼物记忆}}"
    const val ECONOMIC_STATE = "{{经济状况}}"

    // MARK: - 受保护（解析器强耦合，见契约 §4；删除会破坏检测 / 显示）

    const val MOOD_FORMAT = "{{情绪标注格式}}"
    const val REPLY_SEGMENTS = "{{回复条数}}"

    // MARK: - 场景（由 extraMacros 注入，仅忙碌回复场景）

    const val BUSY_ACTIVITY = "{{busy_activity}}"
    const val USER_PENDING_MESSAGES = "{{user_pending_messages}}"

    /**
     * 受保护宏：删除会让情绪显示 / 记忆识别等失效（解析器强耦合）。编辑屏底部红字警告据此列出。
     * 硬约束本身由代码在用户模板之后兜底追加，不依赖用户保留这些宏（契约 D6）。
     */
    val protectedMacros: Set<String> = setOf(MOOD_FORMAT, REPLY_SEGMENTS)

    /**
     * 惰性宏替换。[producers] 的值是 thunk：仅当 [template] 含该宏时才调用，且每个宏至多调用一次
     * （随后用其返回值替换模板中该宏的全部出现）。
     *
     * 语义对齐现行 [PromptBuilder.applyPromptMacros] 的"顺序 replace"，叠加"按需 + 至多一次"。
     * 注意（与现行一致）：若某宏展开值里又含另一个尚未处理的宏字面量，后者仍会被替换——数据宏的值来自
     * 已自行解析过 `{{char}}`/`{{user}}` 的各 builder，实践中不构成二次替换问题。
     */
    fun resolveLazy(template: String, producers: Map<String, () -> String>): String {
        if (template.isEmpty()) return template
        var result = template
        for ((macro, producer) in producers) {
            if (result.contains(macro)) {
                result = result.replace(macro, producer())
            }
        }
        return result
    }
}
