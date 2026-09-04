package com.situ.aichat.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 见面回忆共享件（原 OfflineMeetingMemoryCard.kt·SKY-5b 瘦身改名）：旧「情绪渐变纪念卡」在
// 资料页（SKY-2 窗景卡）与全部页（SKY-5 回忆长廊）先后换装后零消费方，已删除；本文件留守三件
// 仍被多方消费的共享件：OfflineMoodTheme（编辑 sheet + emoji/label 单源）、卡面日期格式器、简版徽章。

/**
 * 线下见面情绪 → 视觉主题（渐变 + emoji + 中文标签），1:1 iOS `OfflineMoodTheme`。
 * 情绪键解析委托单源 [OfflineMoodKind.fromRaw]（SKY-1）；gradient 仅剩编辑 sheet 消费——
 * 资料页/全部页均走 MeetingSkyPalette（契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL）。
 */
internal data class OfflineMoodTheme(val gradient: List<Color>, val emoji: String, val label: String) {
    companion object {
        fun forMood(mood: String?): OfflineMoodTheme = when (OfflineMoodKind.fromRaw(mood)) {
            OfflineMoodKind.WARM -> OfflineMoodTheme(listOf(Color(0xFFF4845F), Color(0xFFF6D365)), "🌸", "温暖")
            OfflineMoodKind.SWEET -> OfflineMoodTheme(listOf(Color(0xFFF093FB), Color(0xFFF5576C)), "🍬", "甜蜜")
            OfflineMoodKind.MELANCHOLIC -> OfflineMoodTheme(listOf(Color(0xFF667EEA), Color(0xFFA78BFA)), "🌙", "微涩")
            OfflineMoodKind.AWKWARD -> OfflineMoodTheme(listOf(Color(0xFF89ABE3), Color(0xFF81ECEC)), "🌫️", "微妙")
            OfflineMoodKind.NEUTRAL -> OfflineMoodTheme(listOf(Color(0xFFBDC3C7), Color(0xFFECF0F1)), "☁️", "平淡")
        }
    }
}

private val cardDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)
private val cardTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

internal fun formatCardDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(cardDateFormatter)

internal fun formatCardTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(cardTimeFormatter)

/**
 * 「简版」徽章：规则兜底摘要时显示，点击触发手动重试（独立 clickable，不冒泡到整卡 onClick）。
 * 转圈态 [isRetrying] 自 OfflineSummaryRetryCoordinator.retryingSessionIds 提升传入（批2 复核修 LOW#1：
 * 原局部 remember 在 LazyColumn item 回收/pager 翻页后丢失→可叠发重试，且失败后永不复位）。
 * [tint] 默认白（历史默认）；窗景卡传 spec.textColor，长廊纸面卡传 accent 深档（SKY-5）。
 */
@Composable
internal fun FallbackBadge(isRetrying: Boolean, onRetry: () -> Unit, tint: Color = Color.White) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .clickable(enabled = !isRetrying, onClick = onRetry)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (isRetrying) {
            // TODO(图纸未覆盖): 这枚转圈的 color 跟 [tint] 走（默认白 / 窗景卡传 spec.textColor / 长廊纸面卡传
            //  accent 深档），是随卡片底色变的承重色；AppLoadingRing 无 color 槽 → 停手登记（D-13）。
            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = tint)
            Text("生成中", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.9f))
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = tint.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
            Text("简版", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.9f))
        }
    }
}
