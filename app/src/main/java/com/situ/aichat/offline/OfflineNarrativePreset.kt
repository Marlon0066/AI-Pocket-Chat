package com.situ.aichat.offline

/**
 * 线下模式叙事预设（源自 iOS `OfflineNarrativePreset`；2026-08-31「人设优先、机器退位」微图纸起
 * 与 iOS 分道：情绪底色池 / 行为类导演指令 / 强制欲言又止（旧规则 16）/「微小意外」条款（旧规则 17）
 * 已拆除，节奏交回角色人设——见 docs/handoff/2026-08-31-线下见面人设优先机器退位-微图纸.md）。
 * 封装各档位差异化配置（规则文本 + 指令池），并提供完整系统提示词构建 [buildPrompt]。提示词为硬编码
 * 中文（含 iOS 模板里 rule8 与 rule9 之间靠 `\` 续行 = 无换行直接相接的行为）。
 *
 * 导演指令引擎（analyze/generate/select）在 10.2b-2；本文件只提供预设数据 + prompt 装配。
 *
 * 天气（P11）落地时在首句之后加一行「此刻天气：…」——面对面只有一片天，不分你那边 / 用户那边
 * （原【双方位置和天气】块与 OfflineWeatherSnapshot 已于 2026-09-06 场景感小批删除：天气恒 null，
 * 该块只产「你住在X / 用户住在Y / 根据情境自然决定见面地点」三行，与钉死的见面地点自相矛盾）。
 */
data class OfflineNarrativePreset(
    val level: DetailLevel,
    // — 规则文本（含编号前缀，直接插值进 prompt 模板） —
    val environmentTagDesc: String,
    val rule8: String,
    val rule12: String,
    val rule13: String,
    /** 规则 16 之后追加的风格规则，编号自含（= “17.”）；空串 = 无追加。 */
    val extraStyleRules: String,
    // — 指令池（空 = 不使用该池） —
    val blockEmphasisPool: List<BlockEmphasisDirective>,
    val narrativeTechniquePool: List<String>,
    val emotionalRegisterPool: List<String>,
) {
    /** 档位标识（1:1 iOS DetailLevel）。 */
    enum class DetailLevel(val raw: String) {
        PLAIN("plain"), NORMAL("normal"), DETAILED("detailed"), CUSTOM("custom");

        companion object {
            /** rawValue → 档位；未知回退 [PLAIN]（= iOS 默认 "plain"）。 */
            fun fromRaw(raw: String): DetailLevel = entries.firstOrNull { it.raw == raw } ?: PLAIN
        }
    }

    companion object {
        // MARK: - 平淡档（默认）
        val PLAIN = OfflineNarrativePreset(
            level = DetailLevel.PLAIN,
            environmentTagDesc = "场景的感官细节[/环境]",
            rule8 = "8. 每轮结尾呈\"等待用户反应的姿态\"——说完就停，留空间给用户。不要单方面结束场景",
            rule12 = "12. 以对话为主，动作和环境简短穿插即可",
            rule13 = "13. [环境] 简单写一下周围的情况就行",
            extraStyleRules = "17. 写作风格：用最日常的语气写，不要文学修辞，角色说人话",
            blockEmphasisPool = listOf(
                BlockEmphasisDirective(setOf(BlockType.EMOTION), "本轮补一个 [情绪]"),
                BlockEmphasisDirective(setOf(BlockType.INNER_MONOLOGUE), "本轮补一个 [内心]"),
                BlockEmphasisDirective(setOf(BlockType.NARRATION), "本轮补一段 [叙述]"),
                BlockEmphasisDirective(setOf(BlockType.ENVIRONMENT), "本轮补一个 [环境]"),
                BlockEmphasisDirective(setOf(BlockType.TIME_SKIP), "可以用 [时间] 推进时间"),
            ),
            narrativeTechniquePool = emptyList(),
            emotionalRegisterPool = emptyList(),
        )

        // MARK: - 正常档
        val NORMAL = OfflineNarrativePreset(
            level = DetailLevel.NORMAL,
            environmentTagDesc = "场景的感官细节：看到的、听到的、闻到的、感受到的[/环境]",
            rule8 = "8. 每一轮回复的结尾呈\"等待用户反应的姿态\"——自然地把对话空间留给用户。可以是一句话说完了、一个动作做完了、或者一个自然的停顿。不需要刻意制造悬念。反面示例：✗\"他们道别，各自离开\"（这是单方面结束场景）",
            rule12 = "12. 对话是主体——大部分内容应该是角色在说话；动作、环境、内心是点缀，穿插在对话之间让画面更生动，但不要喧宾夺主。避免连续两段纯内心独白",
            rule13 = "13. [环境] 写此刻场景里真实存在的感官细节——听到什么、闻到什么、温度如何，选最自然的那一个写就行，不用每种感官都覆盖。系统若给了真实天气，就把它融进环境描写里，但不要直接说“因为天气所以……”",
            extraStyleRules = "17. 写作风格：像朋友在讲今天发生了什么，不像在写小说。不用华丽的比喻和修辞，不用文学腔，角色说话用口语而不是书面语。如果一句话在现实生活中没有人会这样说，就换一种说法",
            blockEmphasisPool = listOf(
                BlockEmphasisDirective(setOf(BlockType.EMOTION), "本轮记得写一个 [情绪]——角色此刻的真实感受，用日常的方式表达就好"),
                BlockEmphasisDirective(setOf(BlockType.EMOTION), "本轮加一个 [情绪]——可以是很小的情绪变化，不需要夸张"),
                BlockEmphasisDirective(setOf(BlockType.EMOTION), "本轮写一个 [情绪]——角色对刚才发生的事有什么感觉？"),
                BlockEmphasisDirective(setOf(BlockType.INNER_MONOLOGUE), "本轮加一个 [内心]——角色嘴上没说但心里在想什么？"),
                BlockEmphasisDirective(setOf(BlockType.INNER_MONOLOGUE), "本轮写一个 [内心]——角色此刻的内心活动，一两句话就够"),
                BlockEmphasisDirective(setOf(BlockType.NARRATION), "本轮加一段 [叙述]——交代一下当前的情境或气氛"),
                BlockEmphasisDirective(setOf(BlockType.NARRATION), "本轮用 [叙述] 带一下画面——让用户知道现在是什么状况"),
                BlockEmphasisDirective(setOf(BlockType.ENVIRONMENT), "本轮写一个 [环境]——周围有什么声音、气味、温度变化？简单带过就行"),
                BlockEmphasisDirective(setOf(BlockType.ENVIRONMENT), "本轮加一个 [环境]——场景里有什么小细节值得提一下？"),
                BlockEmphasisDirective(setOf(BlockType.ENVIRONMENT), "本轮用 [环境] 写一下周围的变化——不用面面俱到，抓一个点就好"),
                BlockEmphasisDirective(setOf(BlockType.TIME_SKIP), "本轮可以用 [时间] 让时间自然地往前走一点"),
                BlockEmphasisDirective(setOf(BlockType.ACTION), "本轮 [动作] 写角色一个自然的小动作——不需要特别有含义"),
            ),
            // 2026-08-31 人设优先：行为类导演指令池（让角色提问/笑/沉默…替角色做主）整池退役。
            narrativeTechniquePool = emptyList(),
            emotionalRegisterPool = emptyList(),
        )

        // MARK: - 细腻档（2026-08-31 人设优先：技法池只留纯写作技法，情绪底色池退役）
        val DETAILED = OfflineNarrativePreset(
            level = DetailLevel.DETAILED,
            environmentTagDesc = "感官细节：温度、气味、声音方向、“差一点发生”的触感、视觉[/环境]",
            rule8 = "8. 每一轮回复的最后一个内容块必须呈\"等待用户反应的姿态\"——把话筒交给用户，不是推进剧情也不是结束场景。合法形式：一个停在半空的动作（他的指尖差一点碰到你的手）、一句未得到回答的话（\"你是不是……\"）、一个停下的脚步、一个望向你的眼神。反面示例（不要这样收束）：✗\"他们道别，各自离开\"（这是结束场景）；✗\"她点头坐下，点了一杯拿铁\"（这只是交代完动作）",
            rule12 = "12. 整轮文字整体配比参考：对话 ≈40%、角色动作+身体语言 ≈30%、环境+感官 ≈20%、内心独白 ≈10%；避免连续两段纯对话或连续两段纯内心独白",
            rule13 = "13. 使用 [环境] 时优先写非视觉通道——温度差、嗅觉、“差一点发生”的触感、声音的方向感；视觉细节建议每轮最多 1 处，把感官带宽留给其他通道；系统若给了真实天气，就把它作为隐性氛围（雨天偏内向、晴天偏开放、夜晚偏感性），让它渗进环境描写里，但不要直白地说“因为今天下雨所以……”，让氛围是“泡”出来的不是“说”出来的",
            extraStyleRules = "",
            blockEmphasisPool = listOf(
                BlockEmphasisDirective(setOf(BlockType.EMOTION), "本轮用 [情绪] 描写角色情绪的身体版本——不是「她有点紧张」，而是「她无意识地把手机翻过来又翻回去」"),
                BlockEmphasisDirective(setOf(BlockType.EMOTION), "本轮 [情绪] 写成一个隐喻——不是「她觉得很温暖」，而是「像是冬天里忽然找到口袋里上次放进去的暖手宝」"),
                BlockEmphasisDirective(setOf(BlockType.EMOTION), "本轮至少出现一个 [情绪]，用涟漪写法——从一个触发点扩散：先是表情变化、然后是呼吸、最后是心里的声音"),
                BlockEmphasisDirective(setOf(BlockType.INNER_MONOLOGUE, BlockType.DIALOGUE), "本轮用 [内心] 展现嘴上说的和心里想的之间的缝隙——台词说的是一回事，内心的真实反应是另一回事"),
                BlockEmphasisDirective(setOf(BlockType.INNER_MONOLOGUE, BlockType.DIALOGUE), "本轮让 [内心] 和 [对话] 形成反差——角色嘴上说「没事」，但内心的声音讲的是完全不同的故事"),
                BlockEmphasisDirective(setOf(BlockType.NARRATION), "本轮用 [叙述] 写一段回忆闪回——当前场景的某个细节触发了一小段过去的画面，三两句话带过"),
                BlockEmphasisDirective(setOf(BlockType.NARRATION), "本轮用 [叙述] 写一个「如果……就好了」式的平行画面——角色脑海里闪过的、没有发生的另一种可能"),
                BlockEmphasisDirective(setOf(BlockType.ENVIRONMENT), "本轮 [环境] 聚焦一个微小物件（桌上的水渍、窗边的落叶、远处的音乐），让它承载当前的情绪氛围"),
                BlockEmphasisDirective(setOf(BlockType.ENVIRONMENT), "本轮 [环境] 用声音开场——一个突然出现的声音改变了场景节奏（手机铃声、远处的笑声、突然安静的背景音乐）"),
                BlockEmphasisDirective(setOf(BlockType.ENVIRONMENT), "本轮 [环境] 写一个对比——周围的热闹衬托角色内心的安静，或安静环境里角色内心的翻涌"),
                BlockEmphasisDirective(setOf(BlockType.TIME_SKIP), "本轮用 [时间] 让时间感跳一下——「不知道过了多久」或「你们不知不觉走了两条街」，让读者感受到时间不只是匀速流动的"),
                BlockEmphasisDirective(setOf(BlockType.ACTION), "本轮 [动作] 描写一个犹豫——伸手又缩回、想说又咽下、看了一眼又移开——比完成的动作更有张力"),
            ),
            // 2026-08-31 人设优先砍 4 留 6：只留纯管文笔的技法（镜头/感官/节奏/物件/动作），
            // 替角色做主的（留白叙事/对话潜台词/反转收束/沉默叙事）与整个情绪底色池退役。
            narrativeTechniquePool = listOf(
                "焦距切换：从大画面（整个场景的氛围）切到极小特写（指尖、睫毛），再拉回中景",
                "感官替换：环境描写优先用嗅觉和触觉——空气的温度、衣料的质感、食物的气味、皮肤上的风",
                "节奏变速：前半段放慢（用环境和感官堆出时间凝滞感），后半段一句话打破沉默",
                "细节锚点：选一个场景里的小物件，让它在这轮出现两次，第二次承载不同的情绪含义",
                "五感递进：依次调动不同感官——先听到、再闻到、然后触碰到、最后看到表情——像慢慢转动对焦环",
                "动作隐喻：角色一个日常小动作（整理头发、转笔、撕纸巾边角）暗示心理状态，不解释",
            ),
            emotionalRegisterPool = emptyList(),
        )

        /** 从用户自定义文本构建预设，以 normal 档为 fallback（1:1 iOS `custom`）。 */
        fun custom(style: String, directive: String, emotion: String): OfflineNarrativePreset {
            val base = NORMAL
            val directiveEntries = directive.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val emotionEntries = emotion.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val hasCustomStyle = style.trim().isNotEmpty()
            return OfflineNarrativePreset(
                level = DetailLevel.CUSTOM,
                environmentTagDesc = base.environmentTagDesc,
                rule8 = base.rule8,
                // 用户有自定义风格 → 清空内置风格规则，由自定义文本接管
                rule12 = if (hasCustomStyle) "" else base.rule12,
                rule13 = if (hasCustomStyle) "" else base.rule13,
                extraStyleRules = if (hasCustomStyle) style.trim() else base.extraStyleRules,
                blockEmphasisPool = emptyList(), // 自定义不做块类型定向分析，由用户文本直接轮换
                narrativeTechniquePool = directiveEntries,
                emotionalRegisterPool = emotionEntries,
            )
        }

        /** 解析档位 + 自定义文本 → 预设（= iOS PromptBuilder.resolveOfflinePreset）。 */
        fun resolve(level: DetailLevel, customStyle: String, customDirective: String, customEmotion: String): OfflineNarrativePreset =
            when (level) {
                DetailLevel.PLAIN -> PLAIN
                DetailLevel.NORMAL -> NORMAL
                DetailLevel.DETAILED -> DETAILED
                DetailLevel.CUSTOM -> custom(customStyle, customDirective, customEmotion)
            }

        /**
         * 构建线下见面模式完整系统提示词（1:1 iOS `buildPrompt`）。
         *
         * @param currentTimeText 已格式化的当前真实时间。
         * @param userName 用户称呼（昵称，空昵称由调用方退「用户」）——首句直呼真名，不写「用户」二字。
         * @param meetingLocation 本场见面地点（入场标记 payload；空 = 首句不带地点分句）。
         * @param meetingActivity 本场见面活动（同上；地点空时一并忽略）。
         * @param tensionSeed 心事种子（非空时拼【今日场景种子】块 + [SEED_CONSTRAINT]）。
         * @param perTurnDirective 本轮叙事指令（10.2b-2 引擎生成；非空时拼入）。
         */
        fun buildPrompt(
            currentTimeText: String,
            userName: String,
            meetingLocation: String?,
            meetingActivity: String?,
            tensionSeed: String?,
            perTurnDirective: String?,
            preset: OfflineNarrativePreset,
        ): String {
            // timeLine 末尾的 \n 必须保留、不能有前导空格。
            val timeLine = "当前真实时间：$currentTimeText\n"
            val rule14 = "14. 结合当前的真实时间来推进线下场景"

            val seedBlock = if (!tensionSeed.isNullOrEmpty()) "\n\n【今日场景种子】\n$tensionSeed\n$SEED_CONSTRAINT" else ""
            val extraLine = if (preset.extraStyleRules.isEmpty()) "" else "\n${preset.extraStyleRules}"

            // base 用 flush-left """ 避免 trimIndent 与插值换行冲突；rule8 与 "9." 直接相接 = iOS `\` 续行行为。
            val base = """【当前处于线下见面模式】
${timeLine}你现在和${userName}面对面在一起${meetingPlaceClause(meetingLocation, meetingActivity)}。请用沉浸式叙事风格输出内容。

使用以下 9 种标签包裹所有内容，不允许输出任何裸文本：

[场景：地点 · 时间描述] — 新场景开始时使用（单行标签，无需闭合）
[环境]${preset.environmentTagDesc}
[叙述]场景旁白和情境推进，使用"你"称呼用户把用户带入画面[/叙述]
[对话]角色说的话（不加引号，系统自动添加）[/对话]
[动作]角色的肢体动作、表情、身体语言[/动作]
[内心]角色的心理活动和真实想法[/内心]
[情绪]角色情绪变化的描写[/情绪]
[时间：时间描述] — 时间跳跃（单行标签，如 [时间：半小时后]）
[过渡] — 场景切换分隔（单行标签）

规则：
1. 每一段文字必须包裹在标签内；成对标签必须闭合（[叙述]…[/叙述]）
2. 对话标签内只写台词本身，不加引号、不加"xx说"，系统会自动加引号
3. 只使用上面列出的 9 种标签；只用这套标签系统，不使用 [mood:...] 标签；不使用任何 markdown 列表或引用符号（- * > ** #）
4. 第三人称描写角色：在 [动作][对话][内心][情绪] 标签里用"他/她"或角色名称描写角色
5. 第二人称构建画面：在 [场景][环境][叙述] 标签里用"你"把用户直接带进画面——例如"你推门进去时先闻到咖啡的香气"、"你的鞋跟在木地板上发出轻响"
6. 你只描写角色自己的言行、动作、内心；提到用户时只写外部可见的物理事实（她的指尖靠近你的耳垂、她停下脚步、她的呼吸擦过你的耳畔），把用户的心情、判断、决定留给用户自己去感受
7. 称呼用户一律用"你"；对话标签内允许使用角色对用户的昵称或名字；[叙述][动作][环境] 标签内只用"你"，不用"对方/用户/那个人"
${preset.rule8}9. 每次回复 4-6 个内容块
10. 每次回复必须至少包含：1 个 [场景] 或 [环境]、1 个 [对话]、1 个 [动作] 或 [内心]
11. 每次回复第一个内容块永远不是 [对话]——先把画面建起来再开口
${preset.rule12}
${preset.rule13}
$rule14
15. 当见面场景走到告别时，先完整输出告别段落（至少 1 个 [场景] 或 [环境] + 1 个 [动作] + 1 个 [对话]），然后调用 end_offline_meeting 工具。是否该告别由场景自然推进决定：见面至少进行了 3 轮、并且已经说到告别、天晚了或准备离开时才收束。如果你的模型不支持工具调用，请在回复末尾附上 [offline_end] 标记
16. 节奏由角色人设和当下情境决定：有话直说的角色可以把话说完，慢热的角色可以欲言又止；不需要刻意制造悬念或在每轮留下钩子，允许什么都没发生、纯粹放松的相处。$extraLine"""

            val directiveBlock = if (!perTurnDirective.isNullOrEmpty()) "\n\n$perTurnDirective" else ""

            return base + CONSISTENCY_BLOCK + seedBlock + directiveBlock
        }

        /**
         * 人设一致性 + 线上线下连续性（梦剧场 B 部·图纸 §3.7·**逐字·所有档位一致**·§9 禁改）。块首各带一个空行；
         * 插在 base 之后、seedBlock 之前（[buildPrompt]）。9 种标签 / 16 条规则 / 档位差异文本零碰。
         */
        private const val CONSISTENCY_BLOCK: String =
            "\n\n【人设一致性】\n" +
                "[情绪][内心][动作] 必须贴合角色人设与你们当前的关系阶段：外向自来熟的角色不会无端紧张，内向慢热的角色不会突然过分热络；「紧张」「害羞」这类情绪只有在人设或当下情境真正支撑时才出现，不要默认套用。" +
                "\n\n【线上线下连续性】\n" +
                "这次见面是你们平时线上聊天的自然延续。你记得系统提示里的共同记忆与过往见面回忆，对话中可以自然地提起（比如「上次你说…」「上回来这儿…」），但不要生硬堆砌回忆，更不要表现得像第一次认识。"

        /**
         * 心事种子约束（seedBlock 尾行·三档统一「自然带出」口径）。2026-08-31 人设优先微图纸 §4-B：
         * 取原 PLAIN/NORMAL 文本逐字；DETAILED 原「前 3 轮不说破」定节奏版废弃。
         */
        private const val SEED_CONSTRAINT: String =
            "这件事不需要立刻摊开说——如果对话自然聊到了就可以提起，没聊到也不用硬塞。让它像真实的心事一样，在合适的时候自然浮出来。"

        /**
         * 说明书首句的地点分句（场景感小批 2026-09-06）：地点空 → ""；否则
         * 「，这次是在{地点}{，活动}；中途换了地方，以对话里最近一个 [场景] 标签为准」。
         * 地点 / 活动逗号直连、不做任何词形加工（活动值常自带前导「在」，加「在」会拼出「在你家家看电影」）。
         */
        internal fun meetingPlaceClause(location: String?, activity: String?): String {
            val place = location?.trim().orEmpty()
            if (place.isEmpty()) return ""
            val act = activity?.trim().orEmpty()
            val activityPart = if (act.isEmpty()) "" else "，$act"
            return "，这次是在$place$activityPart；中途换了地方，以对话里最近一个 [场景] 标签为准"
        }
    }
}

