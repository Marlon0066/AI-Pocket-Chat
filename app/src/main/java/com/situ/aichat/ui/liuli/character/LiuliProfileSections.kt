package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GiftImpressionTag
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.profile.CharacterWalletActivity
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.ui.character.PromiseCardState
import com.situ.aichat.ui.character.ScheduleCardState
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.ourdays.ProfileOurDaysCard
import com.situ.aichat.ui.starfield.StarfieldEntryCard

/**
 * 资料页三段的 item 骨架（琉璃·图纸 2026-09-06 卷四 §4.3 T3）。
 *
 * 每段的**卡序与显隐条件逐字继承暖陶** `CharacterProfileScreen`（图纸 F5）；这里只管「哪一段发哪些 item」，
 * 卡本身各在自己的文件里。抽成独立文件是为让 `LiuliCharacterProfileScreen` 落回 300 行档（§11 D-16）。
 */

/** 各段卡的左右 gutter（20·同暖陶 cardPadding）。 */
private val gutter = Modifier.padding(horizontal = LiuliPageGeometry.gutter)

/** 「经历」段：星空入口 → 日子入口 → 约定卡（有才显）→ 见面回忆 → 共同记忆 → 关系历程。 */
internal fun LazyListScope.storySection(
    promiseCard: PromiseCardState,
    offlineSessions: List<OfflineMeetingSession>,
    retryingOfflineSessions: Set<String>,
    memoryStats: StructuredMemoryStats.Result,
    structuredMemory: StructuredMemory,
    memorySummary: String,
    memoryGuardBlocked: Boolean,
    organizingMemory: Boolean,
    milestones: List<MilestoneEntity>,
    nowMillis: Long,
    onOpenStarfield: () -> Unit,
    onOpenOurDays: () -> Unit,
    onOpenPromises: () -> Unit,
    onOpenOfflineMeetings: () -> Unit,
    onRetryOfflineFallback: (String) -> Unit,
    onOrganizeMemoryNow: () -> Unit,
    onEditMemory: () -> Unit,
) {
    // 星空 / 日子两枚入口卡是 Canvas / 复合件，按图纸 F6 永久借用，只补组间距。
    item(key = "starfield_entry", contentType = "starfield_entry") {
        Box(gutter.fillMaxWidth().padding(bottom = LiuliPageGeometry.groupGap)) {
            StarfieldEntryCard(onOpen = onOpenStarfield)
        }
    }
    item(key = "our_days", contentType = "our_days") {
        Box(gutter.fillMaxWidth().padding(bottom = LiuliPageGeometry.groupGap)) {
            ProfileOurDaysCard(onOpen = onOpenOurDays)
        }
    }
    if (promiseCard.hasAny) {
        item(key = "promises", contentType = "promises") {
            LiuliProfilePromisesCard(
                state = promiseCard,
                nowMillis = nowMillis,
                onOpenAll = onOpenPromises,
                modifier = gutter,
            )
        }
    }
    item(key = "meetings", contentType = "meetings") {
        LiuliProfileMeetingsCard(
            sessions = offlineSessions,
            onOpenAll = onOpenOfflineMeetings,
            onRetryFallback = onRetryOfflineFallback,
            modifier = gutter,
            retryingSessionIds = retryingOfflineSessions,
        )
    }
    item(key = "memory", contentType = "memory") {
        LiuliProfileMemoryCard(
            stats = memoryStats,
            memory = structuredMemory,
            memorySummary = memorySummary,
            modifier = gutter,
            guardBlocked = memoryGuardBlocked,
            organizing = organizingMemory,
            onOrganizeNow = onOrganizeMemoryNow,
            onEditMemory = onEditMemory,
            editInProgressBlocked = organizingMemory,
        )
    }
    item(key = "timeline", contentType = "timeline") {
        LiuliProfileTimelineCard(milestones = milestones, modifier = gutter)
    }
}

/** 「资料」段：兴趣热度 → 钱包（永不隐藏）→ 双雷达 → 角色信息。 */
internal fun LazyListScope.aboutSection(
    character: CharacterEntity,
    dynamicInterests: List<DynamicInterest>,
    personalitySpectrum: PersonalitySpectrum,
    relationshipQuality: RelationshipQuality,
    wallet: CharacterWalletEntity?,
    walletActivity: CharacterWalletActivity.Summary,
    walletHasNews: Boolean,
    nowMillis: Long,
    onEditCharacter: () -> Unit,
    onEditWallet: () -> Unit,
) {
    item(key = "interest", contentType = "interest") {
        LiuliProfileInterestCard(interests = dynamicInterests, modifier = gutter)
    }
    item(key = "wallet", contentType = "wallet") {
        LiuliProfileWalletCard(
            characterName = character.name,
            wallet = wallet,
            activity = walletActivity,
            nowMillis = nowMillis,
            modifier = gutter,
            showNewBadge = walletHasNews,
            onEdit = onEditWallet,
        )
    }
    item(key = "personality_radar", contentType = "radar") {
        LiuliProfilePersonalityRadarCard(spectrum = personalitySpectrum, onEdit = onEditCharacter, modifier = gutter)
    }
    item(key = "relationship_radar", contentType = "radar") {
        LiuliProfileRelationshipRadarCard(quality = relationshipQuality, onEdit = onEditCharacter, modifier = gutter)
    }
    item(key = "charinfo", contentType = "charinfo") {
        LiuliProfileCharacterInfoCard(character = character, modifier = gutter)
    }
}

/** 「近况」段：倒数条 → 亲友账卡 → 日程卡 → 成长日志（顺序 / 条件逐字继承暖陶）。 */
internal fun LazyListScope.nearSection(
    character: CharacterEntity,
    impressionTags: List<GiftImpressionTag>,
    receivedGifts: List<GiftRecordEntity>,
    growthLog: List<GrowthLogEntry>,
    scheduleEnabled: Boolean,
    scheduleCard: ScheduleCardState,
    nowMillis: Long,
    nextMeetingChip: (@Composable () -> Unit)?,
    onRetrySchedule: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
    if (nextMeetingChip != null) {
        item(key = "meeting_countdown", contentType = "meeting_countdown") {
            Box(
                modifier = gutter.fillMaxWidth().padding(bottom = LiuliPageGeometry.groupGap),
                contentAlignment = Alignment.Center,
            ) {
                nextMeetingChip()
            }
        }
    }
    if (impressionTags.isNotEmpty() || receivedGifts.isNotEmpty()) {
        item(key = "account", contentType = "account") {
            LiuliRelationshipAccountCard(
                characterName = character.name,
                tags = impressionTags,
                gifts = receivedGifts,
                nowMillis = nowMillis,
                modifier = gutter,
            )
        }
    }
    if (scheduleEnabled && scheduleCard !is ScheduleCardState.Hidden) {
        item(key = "schedule", contentType = "schedule") {
            LiuliProfileScheduleCard(
                state = scheduleCard,
                onRetry = onRetrySchedule,
                onOpenFullDay = onOpenSchedule,
                modifier = gutter,
            )
        }
    }
    item(key = "growthlog", contentType = "growthlog") {
        LiuliGrowthLogCard(log = growthLog, modifier = gutter)
    }
}
