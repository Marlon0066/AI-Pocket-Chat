package com.situ.aichat.ourdays

import android.content.Context

/**
 * 「我们的日子」无 API 标记（Z-13·逐字仿 `DiaryApiMissingFlag`）：catch-up 因「未配置 API / Key 为空」静默跳过时置 true，
 * 有配置后自动清 false；SharedPreferences 服务局部 flag，不进 AppSettings / Room。卷三横幅据此显示。
 */
object OurDayApiMissingFlag {
    private const val PREFS = "our_days_state"
    private const val KEY = "api_missing"

    fun set(context: Context, missing: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, missing).apply()
    }

    fun get(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
}
