package com.situ.aichat.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.profile.CharacterAgeCalculator
import com.situ.aichat.profile.CompanionStats
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.util.ZodiacCalculator

// 资料页折叠头族（Hero + 统计条 + StatItem·从 CharacterProfileScreen.kt 按「只搬不改」逐字搬出·
// 仅可见性 private→internal 供同屏三 Tab 重构消费；渲染逐字节不变。图纸 2026-07-15-资料页三Tab重构 §2.1）。

@Composable
internal fun HeroSection(c: CharacterEntity, modifier: Modifier = Modifier) {
    val now = remember { System.currentTimeMillis() }
    val age = CharacterAgeCalculator.currentAge(c.ageModeRaw, c.fixedAge, c.birthday, now)
    val ageText = age?.let { stringResource(R.string.profile_age_years, it) }
    val zodiac = c.birthday?.let { ZodiacCalculator.zodiacSign(it) }.orEmpty()
    // 身份行：性别 · N岁 · 星座（各段独立条件拼接，1:1 iOS identityMetadataText）。
    val identityLine = listOfNotNull(
        c.gender.takeIf { it.isNotBlank() },
        ageText,
        zodiac.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val occupation = c.occupation.trim().takeIf { it.isNotBlank() }
    val city = c.cityName?.trim()?.takeIf { it.isNotBlank() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CharacterAvatar(c.name, c.avatarPath, 88.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.padding(top = 6.dp)) {
            Text(
                c.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (identityLine.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    identityLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (occupation != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    occupation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (city != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        city,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatsBar(
    character: CharacterEntity,
    stats: CompanionStats?,
    modifier: Modifier = Modifier,
) {
    // 统计未算完前用兜底值（相识 1 / 其余 0，1:1 iOS cachedStats ?? fallback）。
    val items = buildList {
        add(stringResource(R.string.profile_stat_days) to (stats?.companionDays ?: 1).toString())
        add(stringResource(R.string.profile_stat_messages) to (stats?.messageCount ?: 0).toString())
        add(stringResource(R.string.profile_stat_memories) to (stats?.memoryEntryCount ?: 0).toString())
        val meetings = stats?.offlineMeetingCount ?: 0
        if (meetings > 0) add(stringResource(R.string.profile_stat_meetings) to meetings.toString())
        if (character.streakCount > 0) add(stringResource(R.string.profile_stat_streak) to "🔥${character.streakCount}")
    }
    // 与下方 ProfileCard 同款承托（2026-07-12 用户点名并轨·上卷唯一漏网的非 ProfileCard 容器）；
    // 列间分隔随之换 0.5dp 发丝竖线（照 ProfileHeroCard.HeroStatDivider 笔法·白卡上走墨色）。
    Row(
        modifier
            .fillMaxWidth()
            .appCardSurface()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                val dividerAlpha = if (AppTheme.colors.isDark) 0.16f else 0.10f
                Box(
                    Modifier
                        .width(0.5.dp)
                        .height(28.dp)
                        .background(AppTheme.colors.text.primary.copy(alpha = dividerAlpha)),
                )
            }
            StatItem(label = label, value = value, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
