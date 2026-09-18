package com.situ.aichat.notification

import android.content.Context
import com.situ.aichat.R

/**
 * 通知保底文案（P6.1c）。1:1 移植 iOS `SmartNotificationScheduler.fallbackText(for:)`：当某角色**连一条
 * 通知模板都没有**（[com.situ.aichat.data.local.dao.NotificationTemplateDao.pickUnused] 返回 null）时的
 * 最后兜底——按分类随机取一条本地化静态文案。
 *
 * 正常情况下建角色会生成模板（LLM 或默认），故这是极少触发的最后一道防线。文案存
 * `R.array.notif_fallback_*`（en 逐字对齐 iOS，zh 为译文）。
 */
object NotificationFallbackText {

    /**
     * 取一条该分类的保底文案；未知分类回退 streak_remind（对齐 iOS default 分支）。
     * 宠物饿/病两类单列（2026-09-18）：落 else 会给宠物提醒配上「今天还没聊天」类文案，答非所问——
     * 直接用默认文案池的同一份宠物短句，不另立资源。
     */
    fun pick(context: Context, category: String): String {
        val arrayRes = when (category) {
            "streak_remind" -> R.array.notif_fallback_streak_remind
            "streak_urgent" -> R.array.notif_fallback_streak_urgent
            "streak_broken" -> R.array.notif_fallback_streak_broken
            "morning" -> R.array.notif_fallback_morning
            "evening" -> R.array.notif_fallback_evening
            "random" -> R.array.notif_fallback_random
            "pet_hungry" -> R.array.notif_default_pet_hungry
            "pet_sick" -> R.array.notif_default_pet_sick
            else -> R.array.notif_fallback_streak_remind
        }
        return context.resources.getStringArray(arrayRes).randomOrNull() ?: ""
    }
}
