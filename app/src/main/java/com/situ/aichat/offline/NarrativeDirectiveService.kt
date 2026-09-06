package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.MessageEntity

/**
 * 线下模式叙事指令轮换服务（纯引擎，1:1 iOS `NarrativeDirectiveService`）。
 *
 * 分析最近几轮 AI 线下回复的内容块分布，从 [OfflineNarrativePreset] 提供的指令池里抽取「导演指令」。
 * 本对象只负责**分析 + 选择**；池内容由预设提供。选择带随机（[List.random]/[List.randomOrNull]），
 * 确定性部分（块统计 / 目标类型 / 候选池）抽成 internal 纯函数便于单测反推 iOS。
 */
object NarrativeDirectiveService {

    private const val ROLE_ASSISTANT = "assistant"

    /** 容易退化的块类型——指令偏向选择的重点目标（1:1 iOS degradationProneTypes）。 */
    private val degradationProneTypes: Set<BlockType> = setOf(
        BlockType.EMOTION,
        BlockType.NARRATION,
        BlockType.INNER_MONOLOGUE,
        BlockType.TIME_SKIP,
        BlockType.ENVIRONMENT,
    )

    // MARK: - 分析最近块类型使用

    /**
     * 分析最近 N 轮 AI 线下回复的内容块分布（1:1 iOS `analyzeRecentBlockUsage`）。
     *
     * 取 `assistant` 且 `isOfflineMode` 的消息：全部条数 = [BlockUsageProfile.assistantTurnCount]（判断第几轮），
     * 末尾 5 条用 [OfflineContentParser.parse] 拆块统计。missing = 易退化类型中 0 次的；threshold = max(1, total/10)；
     * underrepresented = 易退化类型中 0<count<threshold 的。
     */
    fun analyzeRecentBlockUsage(messages: List<MessageEntity>): BlockUsageProfile {
        val allAssistant = messages.filter { it.roleRaw == ROLE_ASSISTANT && it.isOfflineMode }
        val assistantTurnCount = allAssistant.size
        val recentAssistant = allAssistant.takeLast(5)

        val counts = BlockType.entries.associateWith { 0 }.toMutableMap()
        var totalBlocks = 0

        for (message in recentAssistant) {
            val blocks = OfflineContentParser.parse(message.content)
            for (block in blocks) {
                val blockType = classifyBlock(block)
                counts[blockType] = (counts[blockType] ?: 0) + 1
                totalBlocks += 1
            }
        }

        val missingTypes = degradationProneTypes.filter { (counts[it] ?: 0) == 0 }.toSet()
        val threshold = maxOf(1, totalBlocks / 10)
        val underrepresentedTypes = degradationProneTypes.filter {
            val count = counts[it] ?: 0
            count in 1 until threshold
        }.toSet()

        return BlockUsageProfile(
            counts = counts,
            totalBlocks = totalBlocks,
            assistantTurnCount = assistantTurnCount,
            missingTypes = missingTypes,
            underrepresentedTypes = underrepresentedTypes,
        )
    }

    // MARK: - 生成本轮指令

    /**
     * 根据块使用分析和预设指令池，组装本轮叙事指令（1:1 iOS `generateDirective`）。
     * 返回 null 表示所有池都为空（不注入指令块）。三池各取一条：块偏向（定向选择）+ 叙事手法（随机）+ 情绪底色（随机）。
     */
    fun generateDirective(profile: BlockUsageProfile, preset: OfflineNarrativePreset): String? {
        val bullets = ArrayList<String>()

        // Pool 1: 块偏向指令（优先覆盖缺失/低频块类型）
        if (preset.blockEmphasisPool.isNotEmpty()) {
            bullets.add(selectBlockEmphasis(profile, preset.blockEmphasisPool))
        }
        // Pool 2: 叙事手法 / 交互行为
        if (preset.narrativeTechniquePool.isNotEmpty()) {
            bullets.add(preset.narrativeTechniquePool.random())
        }
        // Pool 3: 情绪底色
        if (preset.emotionalRegisterPool.isNotEmpty()) {
            bullets.add(preset.emotionalRegisterPool.random())
        }

        if (bullets.isEmpty()) return null

        val joined = bullets.joinToString("\n") { "· $it" }
        return "【本轮叙事指令】（仅本轮有效，下轮会变化）\n$joined"
    }

    // MARK: - 内部辅助

    /** 将 [OfflineContentBlock] 映射到 [BlockType]（1:1 iOS classifyBlock）。 */
    private fun classifyBlock(block: OfflineContentBlock): BlockType = when (block) {
        is OfflineContentBlock.SceneHeader -> BlockType.SCENE_HEADER
        is OfflineContentBlock.Environment -> BlockType.ENVIRONMENT
        is OfflineContentBlock.Narration -> BlockType.NARRATION
        is OfflineContentBlock.CharacterDialogue -> BlockType.DIALOGUE
        is OfflineContentBlock.Action -> BlockType.ACTION
        is OfflineContentBlock.InnerMonologue -> BlockType.INNER_MONOLOGUE
        is OfflineContentBlock.Emotion -> BlockType.EMOTION
        is OfflineContentBlock.UserAction -> BlockType.USER_ACTION
        is OfflineContentBlock.TimeSkip -> BlockType.TIME_SKIP
        OfflineContentBlock.SceneTransition -> BlockType.SCENE_TRANSITION
    }

    /**
     * 本轮块偏向指令的「有效目标类型」（1:1 iOS selectBlockEmphasis 内 effectiveTargets）。
     * = 缺失 ∪ 低频；**开场保护**：前 4 轮（assistantTurnCount<4）从目标里剔除 [BlockType.TIME_SKIP]。
     */
    internal fun effectiveTargetTypes(profile: BlockUsageProfile): Set<BlockType> {
        val targetTypes = profile.missingTypes union profile.underrepresentedTypes
        return if (profile.assistantTurnCount < 4) targetTypes - BlockType.TIME_SKIP else targetTypes
    }

    /**
     * 块偏向指令的候选池（1:1 iOS selectBlockEmphasis 的选择前半段，抽出便于确定性单测）。
     * 有效目标非空且命中候选 ≥2 → 返回命中候选（防重复）；否则回退全池随机。
     */
    internal fun blockEmphasisCandidatePool(
        profile: BlockUsageProfile,
        pool: List<BlockEmphasisDirective>,
    ): List<BlockEmphasisDirective> {
        val effectiveTargets = effectiveTargetTypes(profile)
        if (effectiveTargets.isNotEmpty()) {
            val candidates = pool.filter { directive -> directive.targets.any { it in effectiveTargets } }
            if (candidates.size >= 2) return candidates
        }
        return pool
    }

    /** Pool 1 选择：从 [blockEmphasisCandidatePool] 随机取一条文案（1:1 iOS selectBlockEmphasis）。 */
    private fun selectBlockEmphasis(profile: BlockUsageProfile, pool: List<BlockEmphasisDirective>): String =
        blockEmphasisCandidatePool(profile, pool).randomOrNull()?.text ?: ""
}
