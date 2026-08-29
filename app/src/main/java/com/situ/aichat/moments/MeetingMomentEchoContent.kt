package com.situ.aichat.moments

/**
 * 见面「朋友圈呼应帖」的**锁定物料**（卷二 §5④·图纸 §3.3/§4-M3、M5）：灵感段模板 + J9 内容防线。
 *
 * 单独成文件而非塞进 [MomentGenerationService]：后者是 ⛔ 存量大户，图纸 §9 只授权它新增
 * `generateEchoPost` **一个函数**、增量硬顶 +65 行（见图纸 §11 施工日志 D-4 登记）。两段都是纯函数，
 * 与生成编排无耦合，放这里也让「逐字锁定」的文本有个显眼的家。
 */
internal object MeetingMomentEchoContent {

    /** 正文上限（M5 锁定 140 字）。 */
    const val MAX_LEN = 140

    /** 昵称空白或恰为「用户」时，灵感里对用户的称呼（不让模型学会直呼「用户」）。 */
    const val FALLBACK_CALL_NAME = "TA"

    const val USER_LITERAL = "用户"

    /**
     * 灵感段（**M3 逐字锁定**——含全角括号与其中的引号，改一个字即破契约）：
     * 只给素材与分寸（含蓄程度随性格），不规定文风——文风由既有朋友圈提示词基座管。
     */
    fun inspiration(dayLabel: String, callName: String, location: String, summary: String): String {
        // 复核 R1·D-2 裁决：摘要（日记体/骨架）多以句号收尾，模板又固定补「。」——拼接前剥尾部句号防「。。」。
        // 只清洗输入，M3 模板字面一字不动。
        val cleanSummary = summary.trimEnd('。')
        return "（灵感：${dayLabel}你和${callName}在${location}见了一面——$cleanSummary。想发一条朋友圈纪念这份心情。" +
            "含蓄程度随你的性格：内敛就只写意象和氛围、一个字不提见面；外露可以提\"和重要的人\"，但不写名字。" +
            "绝不出现\"$callName\"和\"用户\"字样，不复述见面里的私密细节，不加话题标签，一到两句话。）"
    }

    /**
     * 内容防线（**J9 锁定**）：返回违规原因（拼进灵感尾部用于重写一次），null = 合格。
     * 昵称硬闸只在昵称 ≥2 字时生效（单字昵称误伤面太大）。
     */
    fun violation(content: String?, nickname: String): String? {
        val text = content?.trim().orEmpty()
        return when {
            text.isEmpty() -> "内容是空的"
            text.length > MAX_LEN -> "超过 $MAX_LEN 字"
            text.contains('[') || text.contains('【') -> "出现了标签括号"
            nickname.length >= 2 && text.contains(nickname) -> "写出了对方的名字"
            text.contains(USER_LITERAL) -> "出现了「用户」字样"
            else -> null
        }
    }
}
