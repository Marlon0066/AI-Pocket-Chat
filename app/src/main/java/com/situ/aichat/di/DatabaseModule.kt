package com.situ.aichat.di

import android.content.Context
import androidx.room.Room
import com.situ.aichat.data.local.ALL_MIGRATIONS
import com.situ.aichat.data.local.AppDatabase
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
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.dao.OpenLoopDao
import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.dao.PromiseDao
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
import com.situ.aichat.data.local.dao.WorldUserResidentDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DB_NAME)
            // P12.2「数据保命」：显式迁移链 v1→v15（[com.situ.aichat.data.local.ALL_MIGRATIONS]，由导出快照确定性
            // 生成、MigrationTest 校验）。**不再用 fallbackToDestructiveMigration**——缺迁移宁可启动崩溃报错，也绝不
            // 静默清库（聊天/角色/记忆/故事/钱包）。新增 DB 版本时务必同步追加迁移 + 测试用例。
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun provideCharacterDao(db: AppDatabase): CharacterDao = db.characterDao()
    @Provides fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideApiConfigDao(db: AppDatabase): ApiConfigDao = db.apiConfigDao()
    @Provides fun provideUserProfileDao(db: AppDatabase): UserProfileDao = db.userProfileDao()
    @Provides fun provideMilestoneDao(db: AppDatabase): MilestoneDao = db.milestoneDao()
    @Provides fun provideScheduleDao(db: AppDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun provideNotificationTemplateDao(db: AppDatabase): NotificationTemplateDao =
        db.notificationTemplateDao()
    @Provides fun provideNotificationDeliveryDao(db: AppDatabase): NotificationDeliveryDao =
        db.notificationDeliveryDao()
    @Provides fun provideNotificationWindowStatsDao(db: AppDatabase): NotificationWindowStatsDao =
        db.notificationWindowStatsDao()
    @Provides fun provideDiaryDao(db: AppDatabase): DiaryDao = db.diaryDao()
    @Provides fun provideMomentDao(db: AppDatabase): MomentDao = db.momentDao()
    @Provides fun provideCustomStickerDao(db: AppDatabase): CustomStickerDao = db.customStickerDao()
    @Provides fun providePetDao(db: AppDatabase): PetDao = db.petDao()
    @Provides fun provideCurrencyDao(db: AppDatabase): CurrencyDao = db.currencyDao()
    @Provides fun provideGiftDao(db: AppDatabase): GiftDao = db.giftDao()
    @Provides fun provideRedPacketDao(db: AppDatabase): RedPacketDao = db.redPacketDao()
    @Provides fun provideRedeemCodeUsageDao(db: AppDatabase): RedeemCodeUsageDao = db.redeemCodeUsageDao()
    @Provides fun provideStoryDao(db: AppDatabase): StoryDao = db.storyDao()
    @Provides fun provideLogDao(db: AppDatabase): LogDao = db.logDao()
    @Provides fun provideMeetingAppointmentDao(db: AppDatabase): MeetingAppointmentDao =
        db.meetingAppointmentDao()
    @Provides fun provideWorldBookDao(db: AppDatabase): WorldBookDao = db.worldBookDao()
    @Provides fun provideWorldDao(db: AppDatabase): WorldDao = db.worldDao()
    @Provides fun provideWorldSocialDao(db: AppDatabase): WorldSocialDao = db.worldSocialDao()
    @Provides fun provideWorldNativeDao(db: AppDatabase): WorldNativeDao = db.worldNativeDao()
    @Provides fun provideWorldUserResidentDao(db: AppDatabase): WorldUserResidentDao = db.worldUserResidentDao()
    @Provides fun provideWorldMemoryDao(db: AppDatabase): WorldMemoryDao = db.worldMemoryDao()
    @Provides fun provideWorldBulletinDao(db: AppDatabase): WorldBulletinDao = db.worldBulletinDao()
    @Provides fun provideOfflineMeetingMemoryDao(db: AppDatabase): OfflineMeetingMemoryDao = db.offlineMeetingMemoryDao()
    @Provides fun provideOpenLoopDao(db: AppDatabase): OpenLoopDao = db.openLoopDao()
    @Provides fun providePromiseDao(db: AppDatabase): PromiseDao = db.promiseDao()
    @Provides fun provideOurDayDao(db: AppDatabase): OurDayDao = db.ourDayDao()
    @Provides fun provideUserStoryTemplateDao(db: AppDatabase): UserStoryTemplateDao = db.userStoryTemplateDao()
}
