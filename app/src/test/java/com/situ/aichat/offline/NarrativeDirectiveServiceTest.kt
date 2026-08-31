package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NarrativeDirectiveService` tests (P10.2b-2): block-usage analysis (filter / suffix(5) / counts /
 * missing / threshold / underrepresented), the opening-protection target logic (前 4 轮排 timeSkip),
 * candidate-pool selection, and `generateDirective` assembly — all reverse-derived from iOS
 * `NarrativeDirectiveService`. Random picks are tested via the deterministic internal seams
 * (`effectiveTargetTypes` / `blockEmphasisCandidatePool`) + membership assertions.
 */
class NarrativeDirectiveServiceTest {

    private var seq = 0
    private fun msg(content: String, role: String = "assistant", offline: Boolean = true): MessageEntity {
        seq += 1
        return MessageEntity(
            messageUUID = "m$seq",
            conversationUuid = "c",
            roleRaw = role,
            content = content,
            timestamp = seq.toLong(),
            isOfflineMode = offline,
        )
    }

    private val degradationProne = setOf(
        BlockType.EMOTION, BlockType.NARRATION, BlockType.INNER_MONOLOGUE,
        BlockType.TIME_SKIP, BlockType.ENVIRONMENT,
    )

    // ── analyzeRecentBlockUsage ──

    @Test fun analyze_filters_role_and_offline_counts_only_last_five() {
        val messages = buildList {
            add(msg("[内心]忽略[/内心]", role = "user"))            // user → excluded
            add(msg("[内心]忽略[/内心]", offline = false))          // non-offline assistant → excluded
            repeat(6) { add(msg("[对话]d$it[/对话]")) }            // 6 assistant-offline turns
        }

        val profile = NarrativeDirectiveService.analyzeRecentBlockUsage(messages)

        // assistantTurnCount = ALL assistant-offline (6), not just the parsed suffix.
        assertEquals(6, profile.assistantTurnCount)
        // only the last 5 are parsed → 5 DIALOGUE blocks.
        assertEquals(5, profile.totalBlocks)
        assertEquals(5, profile.counts[BlockType.DIALOGUE])
        assertEquals(0, profile.counts[BlockType.EMOTION])
        // all degradation-prone types are 0 → all missing; none underrepresented.
        assertEquals(degradationProne, profile.missingTypes)
        assertTrue(profile.underrepresentedTypes.isEmpty())
    }

    @Test fun analyze_threshold_and_underrepresented_at_twenty_blocks() {
        // 20 blocks in the (single, last) offline turn → threshold = max(1, 20/10) = 2.
        val content = buildString {
            repeat(17) { append("[对话]d$it[/对话]\n") } // 17 DIALOGUE (filler, not degradation-prone)
            append("[情绪]a[/情绪]\n")                     // EMOTION ×2 → count 2 (not < threshold) → neither
            append("[情绪]b[/情绪]\n")
            append("[环境]rain[/环境]")                    // ENVIRONMENT ×1 → 0<1<2 → underrepresented
        }
        val profile = NarrativeDirectiveService.analyzeRecentBlockUsage(listOf(msg(content)))

        assertEquals(20, profile.totalBlocks)
        assertEquals(17, profile.counts[BlockType.DIALOGUE])
        assertEquals(2, profile.counts[BlockType.EMOTION])
        assertEquals(1, profile.counts[BlockType.ENVIRONMENT])
        // missing = degradation-prone with 0 count.
        assertEquals(
            setOf(BlockType.NARRATION, BlockType.INNER_MONOLOGUE, BlockType.TIME_SKIP),
            profile.missingTypes,
        )
        // underrepresented = degradation-prone with 0 < count < 2  → only ENVIRONMENT.
        assertEquals(setOf(BlockType.ENVIRONMENT), profile.underrepresentedTypes)
    }

    @Test fun analyze_empty_messages_yields_all_missing_zero_total() {
        val profile = NarrativeDirectiveService.analyzeRecentBlockUsage(emptyList())
        assertEquals(0, profile.assistantTurnCount)
        assertEquals(0, profile.totalBlocks)
        assertEquals(degradationProne, profile.missingTypes) // threshold=1 → nothing underrepresented
        assertTrue(profile.underrepresentedTypes.isEmpty())
    }

    // ── effectiveTargetTypes (开场保护：前 4 轮排 timeSkip) ──

    private fun profile(turn: Int, missing: Set<BlockType>, under: Set<BlockType> = emptySet()) =
        BlockUsageProfile(
            counts = emptyMap(),
            totalBlocks = 0,
            assistantTurnCount = turn,
            missingTypes = missing,
            underrepresentedTypes = under,
        )

    @Test fun effective_targets_drops_timeskip_before_turn_four() {
        val missing = setOf(BlockType.TIME_SKIP, BlockType.EMOTION)
        // turns 0..3 (<4) drop timeSkip.
        assertEquals(setOf(BlockType.EMOTION), NarrativeDirectiveService.effectiveTargetTypes(profile(0, missing)))
        assertEquals(setOf(BlockType.EMOTION), NarrativeDirectiveService.effectiveTargetTypes(profile(3, missing)))
        // turn 4 (>=4) keeps timeSkip.
        assertEquals(missing, NarrativeDirectiveService.effectiveTargetTypes(profile(4, missing)))
    }

    @Test fun effective_targets_unions_missing_and_underrepresented() {
        val p = profile(5, missing = setOf(BlockType.EMOTION), under = setOf(BlockType.NARRATION))
        assertEquals(setOf(BlockType.EMOTION, BlockType.NARRATION), NarrativeDirectiveService.effectiveTargetTypes(p))
    }

    // ── blockEmphasisCandidatePool ──

    @Test fun candidate_pool_returns_matches_when_two_or_more() {
        // PLAIN pool: one directive per single type (EMOTION/INNER/NARRATION/ENVIRONMENT/TIME_SKIP).
        val pool = OfflineNarrativePreset.PLAIN.blockEmphasisPool
        val p = profile(4, missing = setOf(BlockType.EMOTION, BlockType.NARRATION))
        val candidates = NarrativeDirectiveService.blockEmphasisCandidatePool(p, pool)
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.targets.any { t -> t == BlockType.EMOTION || t == BlockType.NARRATION } })
    }

    @Test fun candidate_pool_falls_back_to_whole_pool_when_single_match() {
        val pool = OfflineNarrativePreset.PLAIN.blockEmphasisPool
        // only EMOTION missing → 1 candidate → fall back to whole pool (防重复).
        val p = profile(4, missing = setOf(BlockType.EMOTION))
        assertEquals(pool, NarrativeDirectiveService.blockEmphasisCandidatePool(p, pool))
    }

    @Test fun candidate_pool_falls_back_when_no_targets() {
        val pool = OfflineNarrativePreset.PLAIN.blockEmphasisPool
        val p = profile(4, missing = emptySet())
        assertEquals(pool, NarrativeDirectiveService.blockEmphasisCandidatePool(p, pool))
    }

    @Test fun candidate_pool_timeskip_excluded_early_then_falls_back() {
        val pool = OfflineNarrativePreset.PLAIN.blockEmphasisPool
        // turn<4: timeSkip removed from {TIME_SKIP,EMOTION} → effective {EMOTION} → 1 match → whole pool.
        val p = profile(2, missing = setOf(BlockType.TIME_SKIP, BlockType.EMOTION))
        assertEquals(pool, NarrativeDirectiveService.blockEmphasisCandidatePool(p, pool))
    }

    // ── generateDirective ──

    private fun anyProfile() = NarrativeDirectiveService.analyzeRecentBlockUsage(emptyList())

    @Test fun generate_returns_null_when_all_pools_empty() {
        // custom("","","") → all three pools empty.
        val preset = OfflineNarrativePreset.custom(style = "", directive = "", emotion = "")
        assertNull(NarrativeDirectiveService.generateDirective(anyProfile(), preset))
    }

    @Test fun generate_plain_has_header_and_single_bullet_from_pool() {
        val result = NarrativeDirectiveService.generateDirective(anyProfile(), OfflineNarrativePreset.PLAIN)!!
        assertTrue(result.startsWith("【本轮叙事指令】（仅本轮有效，下轮会变化；与【节拍状态】冲突时以节拍状态为准）\n"))
        val bullets = bulletLines(result)
        assertEquals(1, bullets.size) // PLAIN: only blockEmphasisPool is non-empty
        assertTrue(bullets[0] in OfflineNarrativePreset.PLAIN.blockEmphasisPool.map { it.text })
    }

    @Test fun generate_detailed_has_two_bullets_block_and_technique() {
        // 2026-08-31 人设优先：DETAILED 情绪底色池退役 → 只剩 块偏向 + 纯写作技法 两条。
        val d = OfflineNarrativePreset.DETAILED
        val result = NarrativeDirectiveService.generateDirective(anyProfile(), d)!!
        val bullets = bulletLines(result)
        assertEquals(2, bullets.size)
        assertTrue(bullets[0] in d.blockEmphasisPool.map { it.text })
        assertTrue(bullets[1] in d.narrativeTechniquePool)
    }

    @Test fun generate_normal_has_single_block_emphasis_bullet() {
        // 2026-08-31 人设优先：NORMAL 行为指令池退役 → 只剩块偏向（纯形式补缺）一条。
        val result = NarrativeDirectiveService.generateDirective(anyProfile(), OfflineNarrativePreset.NORMAL)!!
        val bullets = bulletLines(result)
        assertEquals(1, bullets.size)
        assertTrue(bullets[0] in OfflineNarrativePreset.NORMAL.blockEmphasisPool.map { it.text })
    }

    /** Lines after the header, each stripped of the "· " prefix. */
    private fun bulletLines(directive: String): List<String> =
        directive.lines().drop(1).map { it.removePrefix("· ") }
}
