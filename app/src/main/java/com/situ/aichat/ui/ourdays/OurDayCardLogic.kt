package com.situ.aichat.ui.ourdays

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.ourdays.OurDayFacts
import com.situ.aichat.ourdays.OurDayFactsJson
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.ourdays.OurDayPromiseEvent
import java.time.LocalDate

/**
 * 日卡 / 日页纯核（卷三图纸 §3.5 锁定·零 Compose 零 DB）：投影行 + facts → [DayCardModel]；状态判定序、chips 固定序与去重、
 * 首句截断、全部模式按角色分段（识别色序）+「你的日记」行（W-18）、页脚种类。事实条目见 [OurDayFactItems]。
 */
internal object OurDayCardLogic {

    const val FIRST_SENTENCE_MAX_CODE_POINTS = 40
    const val PROMISE_CHIP_MAX_CHARS = 12
    const val USER_DIARY_MAX_CHARS = 30
    private const val SENTENCE_ENDS = "。！？!?\n"
    private const val ELLIPSIS = "…"

    /**
     * 状态判定（§3.5·E9 今天优先：今天永不写页，无行照样显「今天还没过完」）：今天 → 无行 EMPTY → 墓碑 → 手记空白
     * （failed 与 none 同款「还没写好」）→ hidden → NORMAL。
     */
    fun status(row: OurDayCalendarRow?, isToday: Boolean): CardStatus = when {
        isToday -> CardStatus.TODAY
        row == null -> CardStatus.EMPTY
        row.deleted -> CardStatus.DELETED
        row.note.isBlank() -> CardStatus.FAILED
        row.hiddenFromMemory -> CardStatus.HIDDEN_NORMAL
        else -> CardStatus.NORMAL
    }

    /** 通话 chip 分钟：≥60 秒整除；1..59 秒 ⇒ 1。 */
    fun callMinutes(callSeconds: Int): Int = if (callSeconds >= 60) callSeconds / 60 else 1

    /** chips 固定序（零项省略·同类去重）；facts 坏 / 空 ⇒ 只出反规范化列能推的聊天 / 通话 / 见面（E14）。 */
    fun chips(row: OurDayCalendarRow, facts: OurDayFacts?): List<Chip> = buildList {
        if (row.messageCount > 0) add(Chip(ChipKind.CHAT, count = row.messageCount))
        if (row.callSeconds > 0) add(Chip(ChipKind.CALL, count = callMinutes(row.callSeconds)))
        if (row.hasMeeting) add(Chip(ChipKind.MEETING))
        if (facts != null) {
            facts.promises.forEach { p ->
                when (p.event) {
                    OurDayPromiseEvent.CREATED -> add(Chip(ChipKind.PROMISE, text = p.content.take(PROMISE_CHIP_MAX_CHARS)))
                    OurDayPromiseEvent.FULFILLED -> add(Chip(ChipKind.PROMISE_FULFILLED))
                    OurDayPromiseEvent.CANCELLED -> add(Chip(ChipKind.PROMISE_CANCELLED))
                }
            }
            if (facts.milestones.isNotEmpty()) add(Chip(ChipKind.MILESTONE))
            if (facts.gifts.isNotEmpty()) add(Chip(ChipKind.GIFT))
            if (facts.redPackets.isNotEmpty()) add(Chip(ChipKind.RED_PACKET))
            if (facts.momentPosts > 0 || facts.momentInteractions > 0) add(Chip(ChipKind.MOMENTS))
            if (facts.exchangeDiary != null) add(Chip(ChipKind.DIARY))
        }
    }.distinct()

    /** 首句：去首尾空白，按首个 `。！？!?\n` 截断（含标点·`\n` 不含），超 40 码点截 40 + `…`。 */
    fun firstSentence(note: String): String {
        val trimmed = note.trim()
        val idx = trimmed.indexOfFirst { it in SENTENCE_ENDS }
        val cut = when {
            idx < 0 -> trimmed
            trimmed[idx] == '\n' -> trimmed.substring(0, idx)
            else -> trimmed.substring(0, idx + 1)
        }.trim()
        return if (cut.codePointCount(0, cut.length) > FIRST_SENTENCE_MAX_CODE_POINTS) {
            cut.substring(0, cut.offsetByCodePoints(0, FIRST_SENTENCE_MAX_CODE_POINTS)) + ELLIPSIS
        } else {
            cut
        }
    }

    /** 单角色日卡。 */
    fun card(date: LocalDate, today: LocalDate, row: OurDayCalendarRow?, decor: DayDecor?): DayCardModel {
        val facts = row?.let { OurDayFactsJson.decodeOrNull(it.factsJson) }
        return DayCardModel(
            key = OurDayKey.keyOf(date),
            date = date,
            isToday = date == today,
            isFuture = date.isAfter(today),
            status = status(row, date == today),
            note = row?.note.orEmpty(),
            chips = row?.let { chips(it, facts) }.orEmpty(),
            scheduleLine = facts?.scheduleLine.orEmpty(),
            decor = decor,
            hasMeeting = row?.hasMeeting == true,
            generatedAt = row?.generatedAt,
        )
    }

    /** 全部模式日卡：按角色升序分段（识别色序·不在角色表的行跳过）+「你的日记」行；无编辑动作。 */
    fun allCard(
        date: LocalDate,
        today: LocalDate,
        rows: List<OurDayCalendarRow>,
        characters: List<CharacterEntity>,
        decor: DayDecor?,
        userDiary: DiaryEntryEntity?,
    ): DayCardModel {
        val segments = characters.mapIndexedNotNull { index, c ->
            rows.firstOrNull { it.characterUuid == c.uuid }?.let { row ->
                DaySegment(c.uuid, c.name, c.avatarPath, index % OurDaysCalendarLogic.IDENTITY_COLORS, card(date, today, row, decor))
            }
        }
        val diaryLine = userDiary?.let(::userDiaryLine)
        val isToday = date == today
        val status = when {
            isToday -> CardStatus.TODAY
            segments.isEmpty() && diaryLine == null -> CardStatus.EMPTY
            else -> CardStatus.NORMAL
        }
        return DayCardModel(
            key = OurDayKey.keyOf(date), date = date, isToday = isToday, isFuture = date.isAfter(today), status = status,
            note = "", chips = emptyList(), scheduleLine = "", decor = decor, hasMeeting = rows.any { it.hasMeeting }, generatedAt = null,
            segments = segments, userDiary = diaryLine,
        )
    }

    /** 「你的日记」候选：用户日记（非角色作者）∧ 非草稿 ∧ 非宠物日记，取最早一篇（W-18）。 */
    fun pickUserDiary(entries: List<DiaryEntryEntity>): DiaryEntryEntity? =
        entries.filter { it.authorCharacterUuid == null && !it.isDraft && !it.isPetDiary }.minByOrNull { it.timestamp }

    /** 正文首行截 30 字（照 `diaryPreviewText` 先例）。 */
    fun userDiaryLine(entry: DiaryEntryEntity): UserDiaryLine =
        UserDiaryLine(entry.moodEmoji?.takeIf { it.isNotEmpty() }, entry.content.take(USER_DIARY_MAX_CHARS).replace("\n", " "))

    /** 页脚（§4.6）：今天 / 未来 / 无行 / 墓碑 ⇒ NONE；hidden ⇒ HIDDEN；否则 REMEMBERS。 */
    fun footer(row: OurDayCalendarRow?, isToday: Boolean, isFuture: Boolean): FooterKind = when {
        isToday || isFuture || row == null || row.deleted -> FooterKind.NONE
        row.hiddenFromMemory -> FooterKind.HIDDEN
        else -> FooterKind.REMEMBERS
    }
}
