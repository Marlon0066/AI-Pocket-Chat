package com.situ.aichat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * 系统时钟绑定：让「取当前时刻 / 时区」的类以 [Clock] 注入，而非在方法体内裸取
 * `System.currentTimeMillis()` / `ZoneId.systemDefault()`——生产恒为系统钟（行为不变），
 * 测试可给 `Clock.fixed` 钉死时刻，时段类断言才不随「测试在几点 / 哪天跑」漂移。
 * 首个用户：[com.situ.aichat.notification.NotificationScheduler]（根治日程支睡眠闸用例的按日 flaky）。
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
