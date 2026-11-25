package com.situ.aichat.ui.settings.hub

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone

/**
 * 设置主页五组（单源·暖陶与琉璃共用·总规划 §2）。
 * 大卡（AI 服务 / 外观）与页底两张独立卡（高级 / 关于）不属于这五组，单独渲染。
 */
enum class SettingsHubGroup(@param:StringRes val titleRes: Int) {
    CHAT(R.string.settings_hub_group_chat),
    MEMORY(R.string.settings_hub_group_memory),
    LIFE(R.string.settings_hub_group_life),
    WRITING(R.string.settings_hub_group_writing),
    SYSTEM(R.string.settings_hub_group_system),
}

/**
 * 设置主页全部 23 个入口的唯一真相源（顺序即草图顺序）。
 * 两张脸都由此渲染分组、图标、砖色与搜索关键词，彻底杜绝两张脸漂移。
 */
enum class SettingsHubEntry(
    val group: SettingsHubGroup?,
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int? = null,
    val icon: ImageVector,
    val tileTone: LiuliTileTone,
    @param:ArrayRes val keywordsRes: Int,
) {
    // ── 顶部两张大卡 ──
    AI_SERVICE(
        group = null,
        titleRes = R.string.settings_hub_ai_service,
        icon = Icons.Filled.AutoAwesome,
        tileTone = LiuliTileTone.Lilac,
        keywordsRes = R.array.settings_kw_ai_service,
    ),
    APPEARANCE(
        group = null,
        titleRes = R.string.appearance_title,
        icon = Icons.Filled.Palette,
        tileTone = LiuliTileTone.Peach,
        keywordsRes = R.array.settings_kw_appearance,
    ),

    // ── ① 聊天 ──
    REPLY(
        group = SettingsHubGroup.CHAT,
        titleRes = R.string.reply_rule_title,
        icon = Icons.Filled.Forum,
        tileTone = LiuliTileTone.Sky,
        keywordsRes = R.array.settings_kw_reply,
    ),
    VOICE(
        group = SettingsHubGroup.CHAT,
        titleRes = R.string.settings_hub_voice,
        icon = Icons.Filled.GraphicEq,
        tileTone = LiuliTileTone.Sky,
        keywordsRes = R.array.settings_kw_voice,
    ),
    OFFLINE_MEETING(
        group = SettingsHubGroup.CHAT,
        titleRes = R.string.immersive_settings_title,
        icon = Icons.Filled.Bedtime,
        tileTone = LiuliTileTone.Sky,
        keywordsRes = R.array.settings_kw_offline,
    ),
    STICKERS(
        group = SettingsHubGroup.CHAT,
        titleRes = R.string.settings_sticker_title,
        icon = Icons.Filled.Mood,
        tileTone = LiuliTileTone.Sky,
        keywordsRes = R.array.settings_kw_stickers,
    ),

    // ── ② 记忆 ──
    MEMORY(
        group = SettingsHubGroup.MEMORY,
        titleRes = R.string.mem_settings_title,
        icon = Icons.Filled.Memory,
        tileTone = LiuliTileTone.Lilac,
        keywordsRes = R.array.settings_kw_memory,
    ),
    WORLDBOOKS(
        group = SettingsHubGroup.MEMORY,
        titleRes = R.string.wb_hub_title,
        icon = AppFeatureIcons.Worldbook,
        tileTone = LiuliTileTone.Lilac,
        keywordsRes = R.array.settings_kw_worldbooks,
    ),

    // ── ③ TA 们的生活 ──
    SCHEDULE(
        group = SettingsHubGroup.LIFE,
        titleRes = R.string.settings_hub_schedule,
        icon = Icons.Filled.Schedule,
        tileTone = LiuliTileTone.Mint,
        keywordsRes = R.array.settings_kw_schedule,
    ),
    GROWTH(
        group = SettingsHubGroup.LIFE,
        titleRes = R.string.growth_settings_title,
        icon = Icons.Filled.Insights,
        tileTone = LiuliTileTone.Mint,
        keywordsRes = R.array.settings_kw_growth,
    ),
    PET(
        group = SettingsHubGroup.LIFE,
        titleRes = R.string.settings_hub_pet,
        icon = AppFeatureIcons.Pet,
        tileTone = LiuliTileTone.Mint,
        keywordsRes = R.array.settings_kw_pet,
    ),
    WALLET(
        group = SettingsHubGroup.LIFE,
        titleRes = R.string.settings_hub_wallet,
        icon = Icons.Filled.AccountBalanceWallet,
        tileTone = LiuliTileTone.Mint,
        keywordsRes = R.array.settings_kw_wallet,
    ),
    WORLD(
        group = SettingsHubGroup.LIFE,
        titleRes = R.string.world_settings_title,
        icon = Icons.Filled.Public,
        tileTone = LiuliTileTone.Mint,
        keywordsRes = R.array.settings_kw_world,
    ),

    // ── ④ TA 写的东西 ──
    DIARY(
        group = SettingsHubGroup.WRITING,
        titleRes = R.string.diary_settings_title,
        icon = Icons.Filled.Book,
        tileTone = LiuliTileTone.Rose,
        keywordsRes = R.array.settings_kw_diary,
    ),
    MOMENTS(
        group = SettingsHubGroup.WRITING,
        titleRes = R.string.moment_settings_title,
        icon = Icons.Filled.Groups,
        tileTone = LiuliTileTone.Rose,
        keywordsRes = R.array.settings_kw_moments,
    ),
    STORY(
        group = SettingsHubGroup.WRITING,
        titleRes = R.string.story_global_settings_title,
        icon = AppFeatureIcons.Story,
        tileTone = LiuliTileTone.Rose,
        keywordsRes = R.array.settings_kw_story,
    ),

    // ── ⑤ 系统 ──
    NOTIFICATIONS(
        group = SettingsHubGroup.SYSTEM,
        titleRes = R.string.notif_settings_title,
        icon = Icons.Filled.Notifications,
        tileTone = LiuliTileTone.Gold,
        keywordsRes = R.array.settings_kw_notifications,
    ),
    CALENDAR(
        group = SettingsHubGroup.SYSTEM,
        titleRes = R.string.cal_title,
        icon = AppActionIcons.Calendar,
        tileTone = LiuliTileTone.Gold,
        keywordsRes = R.array.settings_kw_calendar,
    ),
    BACKGROUND_RELIABILITY(
        group = SettingsHubGroup.SYSTEM,
        titleRes = R.string.bg_title,
        icon = Icons.Filled.Bolt,
        tileTone = LiuliTileTone.Gold,
        keywordsRes = R.array.settings_kw_background,
    ),
    BACKUP(
        group = SettingsHubGroup.SYSTEM,
        titleRes = R.string.backup_title,
        icon = Icons.Filled.Backup,
        tileTone = LiuliTileTone.Gold,
        keywordsRes = R.array.settings_kw_backup,
    ),
    LANGUAGE(
        group = SettingsHubGroup.SYSTEM,
        titleRes = R.string.settings_language,
        icon = Icons.Filled.Language,
        tileTone = LiuliTileTone.Gold,
        keywordsRes = R.array.settings_kw_language,
    ),

    // ── 底部独立卡片 ──
    ADVANCED(
        group = null,
        titleRes = R.string.settings_hub_advanced,
        subtitleRes = R.string.settings_hub_advanced_subtitle,
        icon = Icons.Filled.Tune,
        tileTone = LiuliTileTone.Lilac,
        keywordsRes = R.array.settings_kw_advanced,
    ),
    ABOUT(
        group = null,
        titleRes = R.string.about_title,
        icon = Icons.Filled.Info,
        tileTone = LiuliTileTone.Lilac,
        keywordsRes = R.array.settings_kw_about,
    ),
    ;

    companion object {
        val heroEntries: List<SettingsHubEntry> = listOf(AI_SERVICE, APPEARANCE)
        val footerEntries: List<SettingsHubEntry> = listOf(ADVANCED, ABOUT)
        fun entriesIn(group: SettingsHubGroup): List<SettingsHubEntry> =
            entries.filter { it.group == group }
    }
}
