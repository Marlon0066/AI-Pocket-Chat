package com.situ.aichat.prompt.diary

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1:1 校验日记角色评论的纯逻辑（对齐 iOS `generateDiaryCommentContent` 提示词 + `generateCommentsForEntry`
 * 的候选/计数 + `scheduleCharacterComments` 的延迟公式）。
 */
class DiaryCommentTest {

    private fun ch(uuid: String) = CharacterEntity(uuid = uuid, name = uuid.uppercase(), creationDate = 0L)

    private fun sentinelStrings() = DiaryCommentPromptStrings(
        intro = "intro<%1\$s|%2\$s>",
        setup = "setup<%1\$s>",
        friendWrote = "friend<%1\$s>",
        entryQuote = "quote<%1\$s>",
        write = "WRITE",
        reqHeader = "REQH",
        reqPersonality = "RP",
        reqConcise = "RC",
        reqGenuine = "RG",
        reqTone = "RT",
        reqNoAi = "RN",
        outputOnly = "OO",
        userMessage = "UM",
        replyPrevComment = "prev<%1\$s>",
        replyUserReplied = "replied<%1\$s|%2\$s>",
        replyWrite = "RWRITE",
        replyUserMessage = "RUM",
        exchangeYouWrote = "EXWROTE",
        exchangeUserCommented = "excomment<%1\$s|%2\$s>",
        exchangeReplyWrite = "EXWRITE",
        photosBlind = "photos<%1\$d>",
    )

    @Test fun `prompt with character setup — full iOS order`() {
        val out = DiaryCommentPromptBuilder.build(
            strings = sentinelStrings(),
            characterName = "Nova",
            personality = "warm",
            systemPrompt = "SP",
            userName = "U",
            entryContent = "today was good",
        )
        val expected = listOf(
            "intro<Nova|warm>",
            "setup<SP>",          // systemPrompt 非空 → 出现
            "",
            "friend<U>",
            "quote<today was good>",
            "",
            "WRITE",
            "",
            "REQH",
            "RP", "RC", "RG", "RT", "RN",
            "",
            "OO",
        ).joinToString("\n")
        assertEquals(expected, out)
    }

    @Test fun `prompt without character setup — setup line omitted`() {
        val out = DiaryCommentPromptBuilder.build(
            strings = sentinelStrings(),
            characterName = "Nova",
            personality = "warm",
            systemPrompt = "",     // 空 → 无 setup 行
            userName = "U",
            entryContent = "hi",
        )
        val lines = out.split("\n")
        assertEquals("intro<Nova|warm>", lines[0])
        assertEquals("", lines[1])               // 直接空行，没有 setup
        assertEquals("friend<U>", lines[2])
    }

    @Test fun `reply prompt — dialogue context lines, reqTone omitted (R3)`() {
        val out = DiaryCommentPromptBuilder.buildReply(
            strings = sentinelStrings(),
            characterName = "Nova",
            personality = "warm",
            systemPrompt = "SP",
            userName = "U",
            entryContent = "today was good",
            rootComment = "nice day!",
            userReply = "thx :)",
        )
        val expected = listOf(
            "intro<Nova|warm>",
            "setup<SP>",
            "",
            "friend<U>",
            "quote<today was good>",
            "",
            "prev<nice day!>",       // 角色原评论
            "replied<U|thx :)>",     // 用户回复
            "",
            "RWRITE",                // 回应指令（非 WRITE）
            "",
            "REQH",
            "RP", "RC", "RG", "RN",  // 无 RT（回应是接话，不需要开题建议）
            "",
            "OO",
        ).joinToString("\n")
        assertEquals(expected, out)
    }

    @Test fun `exchange reply prompt — author voice context, reqTone omitted (R6-1)`() {
        val out = DiaryCommentPromptBuilder.buildExchangeReply(
            strings = sentinelStrings(),
            characterName = "Nova",
            personality = "warm",
            systemPrompt = "SP",
            userName = "U",
            entryContent = "my own day",
            userComment = "loved this",
        )
        val expected = listOf(
            "intro<Nova|warm>",
            "setup<SP>",
            "",
            "EXWROTE",                    // 「你今天写了一篇日记」——角色是作者不是评论者
            "quote<my own day>",
            "",
            "excomment<U|loved this>",    // 用户读后留言
            "",
            "EXWRITE",                    // 回应留言指令
            "",
            "REQH",
            "RP", "RC", "RG", "RN",       // 与 buildReply 同：无 RT
            "",
            "OO",
        ).joinToString("\n")
        assertEquals(expected, out)
    }

    @Test fun `exchange reply prompt without character setup — setup line omitted`() {
        val out = DiaryCommentPromptBuilder.buildExchangeReply(
            strings = sentinelStrings(),
            characterName = "Nova",
            personality = "warm",
            systemPrompt = "",
            userName = "U",
            entryContent = "hi",
            userComment = "note",
        )
        val lines = out.split("\n")
        assertEquals("intro<Nova|warm>", lines[0])
        assertEquals("", lines[1])
        assertEquals("EXWROTE", lines[2])
    }

    @Test fun `commentCount = min(random 1 or 2, candidate count)`() {
        assertEquals(2, DiaryCommentService.commentCount(2, 3))
        assertEquals(1, DiaryCommentService.commentCount(2, 1))
        assertEquals(1, DiaryCommentService.commentCount(1, 5))
        assertEquals(0, DiaryCommentService.commentCount(2, 0))
    }

    @Test fun `resolveCandidates empty csv means all characters`() {
        val all = listOf(ch("a"), ch("b"), ch("c"))
        assertEquals(all, DiaryCommentService.resolveCandidates(all, ""))
        assertEquals(all, DiaryCommentService.resolveCandidates(all, "   "))
        assertEquals(all, DiaryCommentService.resolveCandidates(all, " , , "))
    }

    @Test fun `resolveCandidates filters by allowed uuid set`() {
        val all = listOf(ch("a"), ch("b"), ch("c"))
        assertEquals(listOf(ch("a"), ch("c")), DiaryCommentService.resolveCandidates(all, "a, c"))
        assertEquals(listOf(ch("b")), DiaryCommentService.resolveCandidates(all, "b"))
        // 未知 uuid 被忽略
        assertEquals(listOf(ch("a")), DiaryCommentService.resolveCandidates(all, "a, zzz"))
    }

    @Test fun `scheduleDelaySeconds = delayMinutes times 60 plus jitter`() {
        assertEquals(300L, DiaryCommentService.scheduleDelaySeconds(5, 0))    // 5min, no jitter
        assertEquals(360L, DiaryCommentService.scheduleDelaySeconds(5, 60))   // +60s jitter
        assertEquals(60L, DiaryCommentService.scheduleDelaySeconds(1, 0))
    }

    // MARK: - R3d 点赞挑选（纯函数·概率参数化确定性验证）

    @Test fun `pickLikers - commenters always like, capped at 3, commenters win the cap`() {
        val picked = DiaryCommentService.pickLikers(
            candidateUuids = listOf("x1", "x2", "c1", "c2", "c3", "c4"),
            commenterUuids = setOf("c1", "c2", "c3", "c4"),
            random = kotlin.random.Random(42),
            pOther = 1.0, // 旁观也全中 → 检验封顶与评论者优先
        )
        assertEquals(3, picked.size)
        assertEquals(true, picked.all { it in setOf("c1", "c2", "c3", "c4") }) // 评论者占满封顶
    }

    @Test fun `pickLikers - zero probabilities pick nobody`() {
        val picked = DiaryCommentService.pickLikers(
            candidateUuids = listOf("a", "b", "c"),
            commenterUuids = setOf("a"),
            random = kotlin.random.Random(1),
            pCommenter = 0.0,
            pOther = 0.0,
        )
        assertEquals(emptyList<String>(), picked)
    }

    @Test fun `pickLikers - default pCommenter is certain, result deduped and within candidates`() {
        val picked = DiaryCommentService.pickLikers(
            candidateUuids = listOf("c1", "c1", "b1"),
            commenterUuids = setOf("c1"),
            random = kotlin.random.Random(7),
        )
        assertEquals(true, "c1" in picked)                    // 评论者必点（默认 p=1.0）
        assertEquals(picked.size, picked.distinct().size)     // 去重
        assertEquals(true, picked.all { it in setOf("c1", "b1") })
    }
}
