package com.situ.aichat.ui.settings.hub

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.ui.chat.ChatMessageDispatcher
import com.situ.aichat.ui.settings.labelRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 合并等待秒数解析（与 ReplyRuleSettingsViewModel 同源，只搬不改）。 */
internal fun resolveChatSendWaitSeconds(raw: Float?): Float =
    (raw ?: ChatMessageDispatcher.DEFAULT_WAIT_SECONDS)
        .coerceIn(ChatMessageDispatcher.MIN_WAIT_SECONDS, ChatMessageDispatcher.MAX_WAIT_SECONDS)

/** 设置主页聚合快照（只读·驱动大卡与全部行尾回显）。 */
@Immutable
data class SettingsHubSnapshot(
    val activeConfigLabel: String? = null,
    val appSkin: AppSkin = AppSkin.DEFAULT,
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val chatSendWaitSeconds: Float = ChatMessageDispatcher.DEFAULT_WAIT_SECONDS,
    val replySegmentRange: IntRange = AppSettings.DEFAULT_REPLY_SEGMENT_MIN..AppSettings.DEFAULT_REPLY_SEGMENT_MAX,
    val ttsProviderName: String = TtsProviderType.SYSTEM.displayName,
    val offlineNarrativeDetailRaw: String = "plain",
    val stickersEnabled: Boolean = false,
    val shortTermMemoryLength: Int = AppSettings.DEFAULT_SHORT_TERM_MEMORY_LENGTH,
    val enabledWorldBookCount: Int = 0,
    val scheduleEnabled: Boolean = true,
    val crossCharacterLevel: Int = 1,
    val growthEnabled: Boolean = true,
    val petEnabled: Boolean = true,
    val currencyEnabled: Boolean = true,
    val worldVividnessTier: String = AppSettings.WORLD_VIVIDNESS_STANDARD,
    val diaryAutoEnabled: Boolean = false,
    val diaryAutoTime: String = "21:00",
    val momentAutoPostFrequency: Int = 2,
    val storyTemperature: Double = AppSettings.DEFAULT_STORY_CREATION_TEMPERATURE,
    val notificationsEnabled: Boolean = true,
    val activeNotifCategoryCount: Int? = null,
    val calendarEnabled: Boolean = true,
    val autoBackupEnabled: Boolean = false,
    val lastBackupAt: Long = 0L,
)

/**
 * 纯逻辑映射出的行尾回显描述：资源串或字面量 + 可选警示色标记。
 * 界面层调用 [resolve] 转成最终显示文本。
 */
@Immutable
data class SettingsHubTrailingValue(
    @param:StringRes val textRes: Int? = null,
    val literalText: String? = null,
    val formatArgs: List<Any> = emptyList(),
    val warning: Boolean = false,
) {
    @Composable
    fun resolve(): String = when {
        literalText != null -> literalText
        textRes != null && formatArgs.isEmpty() -> stringResource(textRes)
        textRes != null -> stringResource(textRes, *formatArgs.toTypedArray())
        else -> ""
    }
}

internal fun formatWaitSeconds(seconds: Float): String {
    val rounded = (seconds * 10f).toInt() / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else String.format(Locale.US, "%.1f", rounded)
}

internal fun formatBackupDate(epochMillis: Long, locale: Locale = Locale.getDefault(), zoneId: ZoneId = ZoneId.systemDefault()): String {
    val pattern = if (locale.language == "zh") "M月d日" else "MMM d"
    return Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * 纯函数：为设置主页每个入口计算行尾当前值（T1 可直接单测）。
 * 返回 null 表示该行不需要行尾值（如「高级」）。
 */
fun trailingValueFor(
    entry: SettingsHubEntry,
    snapshot: SettingsHubSnapshot,
    isBatteryExempt: Boolean,
    currentLangTag: String,
    versionName: String,
    crossLevelLabel: (Int) -> String,
    formattedBackupDate: (Long) -> String = { formatBackupDate(it) },
    skinLabel: (AppSkin) -> String = { "" },
    modeLabel: (AppearanceMode) -> String = { "" },
): SettingsHubTrailingValue? = when (entry) {
    SettingsHubEntry.AI_SERVICE -> if (snapshot.activeConfigLabel != null) {
        SettingsHubTrailingValue(literalText = snapshot.activeConfigLabel)
    } else {
        SettingsHubTrailingValue(textRes = R.string.settings_api_not_configured, warning = true)
    }
    SettingsHubEntry.APPEARANCE -> {
        val left = skinLabel(snapshot.appSkin)
        val right = modeLabel(snapshot.appearanceMode)
        if (left.isNotEmpty() && right.isNotEmpty()) {
            SettingsHubTrailingValue(literalText = "$left · $right")
        } else {
            null
        }
    }
    SettingsHubEntry.REPLY -> SettingsHubTrailingValue(
        textRes = R.string.settings_hub_val_reply,
        formatArgs = listOf(
            formatWaitSeconds(snapshot.chatSendWaitSeconds),
            snapshot.replySegmentRange.first,
            snapshot.replySegmentRange.last,
        ),
    )
    SettingsHubEntry.VOICE -> SettingsHubTrailingValue(literalText = snapshot.ttsProviderName)
    SettingsHubEntry.OFFLINE_MEETING -> SettingsHubTrailingValue(
        textRes = when (snapshot.offlineNarrativeDetailRaw) {
            "normal" -> R.string.settings_hub_val_narrative_normal
            "detailed" -> R.string.settings_hub_val_narrative_detailed
            "custom" -> R.string.settings_hub_val_narrative_custom
            else -> R.string.settings_hub_val_narrative_plain
        },
    )
    SettingsHubEntry.STICKERS -> SettingsHubTrailingValue(
        textRes = if (snapshot.stickersEnabled) R.string.settings_hub_val_stickers_on else R.string.settings_hub_val_stickers_off,
    )
    SettingsHubEntry.MEMORY -> SettingsHubTrailingValue(
        textRes = R.string.settings_hub_val_memory_turns,
        formatArgs = listOf(snapshot.shortTermMemoryLength),
    )
    SettingsHubEntry.WORLDBOOKS -> if (snapshot.enabledWorldBookCount > 0) {
        SettingsHubTrailingValue(
            textRes = R.string.settings_hub_val_worldbook_count,
            formatArgs = listOf(snapshot.enabledWorldBookCount),
        )
    } else {
        SettingsHubTrailingValue(textRes = R.string.settings_hub_val_worldbook_none)
    }
    SettingsHubEntry.SCHEDULE -> when {
        !snapshot.scheduleEnabled -> SettingsHubTrailingValue(textRes = R.string.settings_state_off)
        snapshot.crossCharacterLevel <= 0 -> SettingsHubTrailingValue(textRes = R.string.settings_state_on)
        else -> SettingsHubTrailingValue(
            textRes = R.string.settings_hub_val_schedule_with_cross,
            formatArgs = listOf(crossLevelLabel(snapshot.crossCharacterLevel)),
        )
    }
    SettingsHubEntry.GROWTH -> SettingsHubTrailingValue(
        textRes = if (snapshot.growthEnabled) R.string.settings_state_on else R.string.settings_state_off,
    )
    SettingsHubEntry.PET -> SettingsHubTrailingValue(
        textRes = if (snapshot.petEnabled) R.string.settings_state_on else R.string.settings_state_off,
    )
    SettingsHubEntry.WALLET -> SettingsHubTrailingValue(
        textRes = if (snapshot.currencyEnabled) R.string.settings_state_on else R.string.settings_state_off,
    )
    SettingsHubEntry.WORLD -> SettingsHubTrailingValue(
        textRes = when (snapshot.worldVividnessTier) {
            AppSettings.WORLD_VIVIDNESS_LITE -> R.string.world_vividness_lite_name
            AppSettings.WORLD_VIVIDNESS_RICH -> R.string.world_vividness_rich_name
            else -> R.string.world_vividness_std_name
        },
    )
    SettingsHubEntry.DIARY -> if (snapshot.diaryAutoEnabled) {
        SettingsHubTrailingValue(
            textRes = R.string.settings_hub_val_diary_daily,
            formatArgs = listOf(snapshot.diaryAutoTime),
        )
    } else {
        SettingsHubTrailingValue(textRes = R.string.settings_hub_val_diary_off)
    }
    SettingsHubEntry.MOMENTS -> if (snapshot.momentAutoPostFrequency > 0) {
        SettingsHubTrailingValue(
            textRes = R.string.settings_hub_val_moment_freq,
            formatArgs = listOf(snapshot.momentAutoPostFrequency),
        )
    } else {
        SettingsHubTrailingValue(textRes = R.string.settings_hub_val_moment_off)
    }
    SettingsHubEntry.STORY -> SettingsHubTrailingValue(
        textRes = R.string.settings_hub_val_story_temp,
        formatArgs = listOf(String.format(Locale.US, "%.1f", snapshot.storyTemperature)),
    )
    SettingsHubEntry.NOTIFICATIONS -> when {
        snapshot.activeNotifCategoryCount != null -> SettingsHubTrailingValue(
            textRes = R.string.settings_hub_val_notif_count,
            formatArgs = listOf(snapshot.activeNotifCategoryCount),
        )
        else -> SettingsHubTrailingValue(
            textRes = if (snapshot.notificationsEnabled) R.string.settings_state_on else R.string.settings_state_off,
        )
    }
    SettingsHubEntry.CALENDAR -> SettingsHubTrailingValue(
        textRes = if (snapshot.calendarEnabled) R.string.settings_state_on else R.string.settings_state_off,
    )
    SettingsHubEntry.BACKGROUND_RELIABILITY -> if (isBatteryExempt) {
        SettingsHubTrailingValue(textRes = R.string.settings_hub_val_bg_ready)
    } else {
        SettingsHubTrailingValue(textRes = R.string.settings_hub_val_bg_not_ready, warning = true)
    }
    SettingsHubEntry.BACKUP -> when {
        !snapshot.autoBackupEnabled -> null
        snapshot.lastBackupAt > 0L -> SettingsHubTrailingValue(
            textRes = R.string.settings_hub_val_backup_last,
            formatArgs = listOf(formattedBackupDate(snapshot.lastBackupAt)),
        )
        else -> SettingsHubTrailingValue(textRes = R.string.settings_hub_val_backup_pending)
    }
    SettingsHubEntry.LANGUAGE -> SettingsHubTrailingValue(
        textRes = if (currentLangTag == "en") R.string.lang_option_en else R.string.lang_option_zh,
    )
    SettingsHubEntry.ADVANCED -> null
    SettingsHubEntry.ABOUT -> SettingsHubTrailingValue(literalText = versionName)
}

@Composable
internal fun crossLevelResLabel(level: Int): String = stringResource(
    when (level) {
        2 -> R.string.cross_level_often
        3 -> R.string.cross_level_frequent
        else -> R.string.cross_level_occasional
    },
)

@Composable
internal fun resolvedTrailingValue(
    entry: SettingsHubEntry,
    snapshot: SettingsHubSnapshot,
    isBatteryExempt: Boolean,
    currentLangTag: String,
    versionName: String,
): Pair<String?, Boolean> {
    val crossOcc = stringResource(R.string.cross_level_occasional)
    val crossOften = stringResource(R.string.cross_level_often)
    val crossFreq = stringResource(R.string.cross_level_frequent)
    val skinName = stringResource(snapshot.appSkin.labelRes())
    val modeName = stringResource(snapshot.appearanceMode.labelRes())
    val tv = trailingValueFor(
        entry = entry,
        snapshot = snapshot,
        isBatteryExempt = isBatteryExempt,
        currentLangTag = currentLangTag,
        versionName = versionName,
        crossLevelLabel = { level ->
            when (level) {
                2 -> crossOften
                3 -> crossFreq
                else -> crossOcc
            }
        },
        skinLabel = { skinName },
        modeLabel = { modeName },
    ) ?: return null to false
    return tv.resolve() to tv.warning
}
