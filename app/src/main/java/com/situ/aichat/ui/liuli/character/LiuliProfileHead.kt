package com.situ.aichat.ui.liuli.character

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.profile.CompanionStats
import com.situ.aichat.prompt.growth.composeRelationshipDisplay
import com.situ.aichat.profile.CharacterAgeCalculator
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.liuli.page.LiuliActionItem
import com.situ.aichat.util.ZodiacCalculator

/**
 * 资料页头部的**数据整形**（图纸 2026-09-06 卷四 A-8 / A-9 / A-10 · §3）。
 *
 * 全是纯函数 / 只读 `stringResource` 的小件——头图、统计卡、动作排都只吃整形结果，便于 T1 / T2 反推。
 */

/**
 * 身份行「性别 · N岁 · 星座」（算法逐字搬暖陶 `ProfileHeaderSection.kt:42-50`）。
 *
 * @param now 当刻（岁数按它算·调用方 `remember` 一次，免得逐帧重算）
 */
@Composable
internal fun identityLine(c: CharacterEntity, now: Long): String {
    val age = CharacterAgeCalculator.currentAge(c.ageModeRaw, c.fixedAge, c.birthday, now)
    val ageText = age?.let { stringResource(R.string.profile_age_years, it) }
    val zodiac = c.birthday?.let { ZodiacCalculator.zodiacSign(it) }.orEmpty()
    return listOfNotNull(
        c.gender.takeIf { it.isNotBlank() },
        ageText,
        zodiac.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** 头图副行 = 身份行 · 职业 · 城市（三段各自可缺·A-8）。 */
@Composable
internal fun heroSubtitle(c: CharacterEntity, now: Long): String = listOfNotNull(
    identityLine(c, now).takeIf { it.isNotEmpty() },
    c.occupation.trim().takeIf { it.isNotBlank() },
    c.cityName?.trim()?.takeIf { it.isNotBlank() },
).joinToString(" · ")

/**
 * 关系胶囊文案（A-8）：取**最晚确立**的那条里程碑拼「标签 · 时期」；一条都没有 → 「初识」。
 * 取法与联系人行同源（`ContactsViewModel:92-94, :112-115`），共用 `composeRelationshipDisplay`。
 */
@Composable
internal fun relationshipPillLabel(milestones: List<MilestoneEntity>): String =
    milestones.maxByOrNull { it.establishedDate }
        ?.let { composeRelationshipDisplay(it.relationshipName, it.phase) }
        ?: stringResource(R.string.contacts_relationship_initial)

/**
 * 统计卡条目（算法逐字搬暖陶 `StatsBar`·A-10）：相识 / 消息 / 记忆恒显（未算完走兜底 1 / 0 / 0），
 * 见面与连续 > 0 才占一列（E11）。**值不带单位**，与暖陶同字。
 */
@Composable
internal fun statCardItems(character: CharacterEntity, stats: CompanionStats?): List<Pair<String, String>> = buildList {
    add(stringResource(R.string.profile_stat_days) to (stats?.companionDays ?: 1).toString())
    add(stringResource(R.string.profile_stat_messages) to (stats?.messageCount ?: 0).toString())
    add(stringResource(R.string.profile_stat_memories) to (stats?.memoryEntryCount ?: 0).toString())
    val meetings = stats?.offlineMeetingCount ?: 0
    if (meetings > 0) add(stringResource(R.string.profile_stat_meetings) to meetings.toString())
    if (character.streakCount > 0) add(stringResource(R.string.profile_stat_streak) to "🔥${character.streakCount}")
}

/**
 * 动作排四格（A-9）：日程 / 约定 / 日子 / 星空。
 *
 * 文案复用各入口卡**既有**标题键（不新增）；图标除星空外都是入口卡在用的那一枚——
 * 星空入口卡是整张 Canvas、根本没有图标，故借 M3 `AutoAwesome`（对版稿那枚星簇的最近借用件·§11 D-12）。
 * 四个回调只转手既有导航，卡内原链接不删（双入口·零新逻辑）。
 */
@Composable
internal fun profileActionItems(
    onOpenSchedule: () -> Unit,
    onOpenPromises: () -> Unit,
    onOpenOurDays: () -> Unit,
    onOpenStarfield: () -> Unit,
): List<LiuliActionItem> {
    val schedule = stringResource(R.string.schedule_card_title)
    val promises = stringResource(R.string.promise_title)
    val ourDays = stringResource(R.string.our_days_title)
    val starfield = stringResource(R.string.starfield_title)
    return listOf(
        LiuliActionItem(Icons.Filled.CalendarMonth, schedule, schedule, onOpenSchedule),
        LiuliActionItem(Icons.Filled.Handshake, promises, promises, onOpenPromises),
        LiuliActionItem(AppFeatureIcons.Days, ourDays, ourDays, onOpenOurDays),
        LiuliActionItem(Icons.Filled.AutoAwesome, starfield, starfield, onOpenStarfield),
    )
}
