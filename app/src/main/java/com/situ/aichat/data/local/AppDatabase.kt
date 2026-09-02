package com.situ.aichat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.situ.aichat.data.local.dao.ApiConfigDao
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.CustomStickerDao
import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.dao.OpenLoopDao
import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.dao.PromiseDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.NotificationWindowStatsDao
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.dao.RedeemCodeUsageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.StoryDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.dao.WorldBulletinDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.dao.WorldUserResidentDao
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.DiaryCommentEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.DiaryReactionEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.LogEntryEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentNotificationEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity
import com.situ.aichat.data.local.entity.NotificationTemplateEntity
import com.situ.aichat.data.local.entity.NotificationWindowStatsEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.local.entity.RedeemCodeUsageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import com.situ.aichat.data.local.entity.UserWalletEntity
import com.situ.aichat.data.local.entity.WorldBookBindingEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.local.entity.WorldDiscoveryEntity
import com.situ.aichat.data.local.entity.WorldBulletinEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldLlmSpendEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.local.entity.WorldUserResidentEntity

@Database(
    entities = [
        CharacterEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ApiConfigEntity::class,
        UserProfileEntity::class,
        MilestoneEntity::class,
        CharacterDailyScheduleEntity::class,
        ScheduleEventEntity::class,
        NotificationTemplateEntity::class,
        NotificationDeliveryRecordEntity::class,
        NotificationWindowStatsEntity::class,
        DiaryEntryEntity::class,
        DiaryCommentEntity::class,
        DiaryReactionEntity::class,
        MonthlyReviewEntity::class,
        MomentPostEntity::class,
        MomentCommentEntity::class,
        MomentLikeEntity::class,
        MomentNotificationEntity::class,
        CustomStickerEntity::class,
        CharacterPetEntity::class,
        UserWalletEntity::class,
        CharacterWalletEntity::class,
        CurrencyTransactionEntity::class,
        GiftRecordEntity::class,
        RedPacketRecordEntity::class,
        StoryEntity::class,
        StoryChapterEntity::class,
        StoryCharacterRoleEntity::class,
        RedeemCodeUsageEntity::class,
        LogEntryEntity::class,
        MeetingAppointmentEntity::class,
        WorldBookEntity::class,
        WorldBookEntryEntity::class,
        WorldBookBindingEntity::class,
        WorldBookTimedStateEntity::class,
        // 世界系统 W1 数据底座（契约 FABLE5_WORLD_SYSTEM_PROPOSAL.md §5 / W1 图纸）：8 张新表。
        WorldStateEntity::class,
        WorldTravelEntity::class,
        WorldNativeStateEntity::class,
        WorldRelationshipEntity::class,
        WorldRelationshipEventEntity::class,
        WorldEventEntity::class,
        WorldCityLoreEntity::class,
        WorldDiscoveryEntity::class,
        // 世界系统 W5 联动闭环（契约 §9 / W5 图纸 §3.1）：双视角世界记忆 + 开机小报 + LLM 预算台账。
        WorldMemoryEntity::class,
        WorldBulletinEntity::class,
        WorldLlmSpendEntity::class,
        // 线下见面「梦剧场」B 部（契约 §B2 决议 B-1 / 图纸 §3.2）：每次见面一行的结构化回忆表。
        OfflineMeetingMemoryEntity::class,
        // 活人感一期 P2 承诺回连（图纸 §3.2）：「心里惦记的事」结构化行（无 FK·手动级联清）。
        OpenLoopEntity::class,
        // 世界二期战役 B（契约 FABLE5_WORLD_ART_RESIDENTS_PROPOSAL.md §3 / 图纸 §3.1）：用户自建居民静态人设。
        WorldUserResidentEntity::class,
        // 记忆改造一期·部件① 承诺账本（图纸 §3.1）：「我们的约定」结构化行（无 FK·手动级联清·会话删不连坐）。
        PromiseEntity::class,
        // 故事「我的模板」（图纸四 §3.2）：整套创作设定存单列 JSON，与故事表零关联（删模板不影响已开的书）。
        UserStoryTemplateEntity::class,
        // 「我们的日子」卷一《沉淀》（总图纸 docs/handoff/2026-09-02-我们的日子-总图纸.md §3.1）：一天 × 一角色的事实快照 + 手记（无 FK·手动级联清）。
        OurDayEntity::class,
    ],
    version = 48,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun notificationTemplateDao(): NotificationTemplateDao
    abstract fun notificationDeliveryDao(): NotificationDeliveryDao
    abstract fun notificationWindowStatsDao(): NotificationWindowStatsDao
    abstract fun diaryDao(): DiaryDao
    abstract fun momentDao(): MomentDao
    abstract fun customStickerDao(): CustomStickerDao
    abstract fun petDao(): PetDao
    abstract fun currencyDao(): CurrencyDao
    abstract fun giftDao(): GiftDao
    abstract fun redPacketDao(): RedPacketDao
    abstract fun redeemCodeUsageDao(): RedeemCodeUsageDao
    abstract fun storyDao(): StoryDao
    abstract fun logDao(): LogDao
    abstract fun meetingAppointmentDao(): MeetingAppointmentDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun worldDao(): WorldDao
    abstract fun worldSocialDao(): WorldSocialDao
    abstract fun worldNativeDao(): WorldNativeDao
    abstract fun worldUserResidentDao(): WorldUserResidentDao
    abstract fun worldMemoryDao(): WorldMemoryDao
    abstract fun worldBulletinDao(): WorldBulletinDao
    abstract fun offlineMeetingMemoryDao(): OfflineMeetingMemoryDao
    abstract fun openLoopDao(): OpenLoopDao
    abstract fun promiseDao(): PromiseDao
    abstract fun ourDayDao(): OurDayDao
    abstract fun userStoryTemplateDao(): UserStoryTemplateDao

    companion object {
        const val DB_NAME = "aichat.db"
    }
}
