package com.situ.aichat.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.situ.aichat.R
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.notification.Notifier
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.ui.chat.ChatListScheduleStatus
import com.situ.aichat.util.DateFormatters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 角色「此刻」状态桌面小组件（13.9a · C1，安卓超越 iOS——iOS 仅有宠物桌面小组件，无角色状态小组件）。
 *
 * 展示**主对话**角色当下在做什么：头像 + 名字 + 当天进行中日程的「活动 心情emoji」。点整块 → 直达该会话。
 *
 * **安卓地道做法 / 数据流**：与 App 同进程，经 Hilt EntryPoint 直读 Room（仿 [PetGlanceWidget]）；状态串每次
 * 渲染按当前时间**现算**（复用聊天列表同款 [ChatListScheduleStatus.currentStatus]）→ 即便被 ROM 杀后台、再被
 * [com.situ.aichat.work.WidgetRefreshWorker] 30 分定期 nudge 一次，渲染出的「此刻」永远对得上当前时间。
 * 跳转复用既有会话深链通道（[Notifier.conversationShortcutIntent] → MainActivity → NotificationNavigator →
 * `chat/{uuid}`，纯导航不物化），无需新增路由。
 */
class CharacterStatusGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE))

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun conversationRepository(): ConversationRepository
        fun characterRepository(): CharacterRepository
        fun scheduleDao(): ScheduleDao
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
        val state = buildState(deps, System.currentTimeMillis())
        // P1-32：按两布局最大显示尺寸密度感知降采样（Responsive 下 SMALL/MEDIUM 共享同一 Bitmap，必须按大的取）。
        val density = context.resources.displayMetrics.density
        val avatar = state?.avatarPath?.let {
            decodeAvatarForWidget(it, widgetTargetPx(STATUS_AVATAR_MEDIUM.value, density))
        }
        provideContent {
            GlanceTheme {
                when {
                    state == null -> NoConversationContent(context)
                    LocalSize.current.width >= MEDIUM_THRESHOLD -> MediumContent(context, state, avatar)
                    else -> SmallContent(context, state, avatar)
                }
            }
        }
    }

    /** 组装快照：选主对话 → 取角色名/头像 → 现算日程状态串。无任何有消息会话 → null。 */
    private suspend fun buildState(deps: Deps, now: Long): CharacterStatusWidgetState? {
        val conv = CharacterStatusWidgetData.pickConversation(deps.conversationRepository().activeSnapshot())
            ?: return null
        val character = deps.characterRepository().get(conv.characterUuid)
        val name = character?.name?.takeIf { it.isNotBlank() } ?: conv.title
        return CharacterStatusWidgetState(
            conversationUuid = conv.uuid,
            characterName = name,
            avatarPath = character?.avatarPath,
            // 卷一 F5：会话正在线下见面 → 状态行隐藏（既不泄见面地点，也不显早已过时的线上日程）。
            statusLine = if (OfflineMeetingGate.inMeeting(conv)) null else computeStatus(deps, conv.characterUuid, now),
        )
    }

    /** 现算「活动 心情emoji」（1:1 复用聊天列表 [ChatListScheduleStatus]）：日程系统关 / 无当天日程 / 无进行中事件 → null。 */
    private suspend fun computeStatus(deps: Deps, characterUuid: String, now: Long): String? {
        if (!deps.settingsRepository().getAppSettings().scheduleSystemEnabled) return null
        val today = DateFormatters.startOfDayMillis(now)
        val schedule = deps.scheduleDao().scheduleFor(characterUuid, today) ?: return null
        val events = deps.scheduleDao().eventsForSchedule(schedule.uuid)
        return ChatListScheduleStatus.currentStatus(events, now)
    }

    private companion object {
        val SMALL_SIZE = DpSize(110.dp, 110.dp)
        val MEDIUM_SIZE = DpSize(250.dp, 110.dp)
        val MEDIUM_THRESHOLD = 200.dp
    }
}

// P1-32：头像显示尺寸=解码目标（强耦合防未来改版式时脱钩重新发糊）。布局是顶级函数，常量随之放顶级。
private val STATUS_AVATAR_SMALL = 56.dp
private val STATUS_AVATAR_MEDIUM = 60.dp

// MARK: - 小号布局（头像 + 名字 + 此刻状态）

@Composable
private fun SmallContent(context: Context, state: CharacterStatusWidgetState, avatar: Bitmap?) {
    WidgetSurface(onBodyClick = actionStartActivity(openConversationIntent(context, state.conversationUuid))) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(state.characterName, avatar, STATUS_AVATAR_SMALL)
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = state.characterName,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            )
            Text(
                text = statusText(context, state),
                maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center),
            )
        }
    }
}

// MARK: - 中号布局（头像左 + 名字/此刻状态）

@Composable
private fun MediumContent(context: Context, state: CharacterStatusWidgetState, avatar: Bitmap?) {
    WidgetSurface(onBodyClick = actionStartActivity(openConversationIntent(context, state.conversationUuid))) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(state.characterName, avatar, STATUS_AVATAR_MEDIUM)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = state.characterName,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    text = statusText(context, state),
                    maxLines = 2,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
        }
    }
}

// MARK: - 无会话

@Composable
private fun NoConversationContent(context: Context) {
    Box(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("💬", style = TextStyle(fontSize = 26.sp, textAlign = TextAlign.Center))
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.character_status_widget_no_chat),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center),
            )
        }
    }
}

// MARK: - 复用片段（WidgetSurface / Avatar 抽到 WidgetUi.kt 与最新动态小组件共用）

/** 当下日程状态串；无进行中事件 / 日程关时回退邀约文案（不留空白行）。 */
private fun statusText(context: Context, state: CharacterStatusWidgetState): String =
    state.statusLine ?: context.getString(R.string.character_status_widget_idle)

/**
 * 点击 → 打开该会话。复用既有会话深链 [Notifier.conversationShortcutIntent]（纯导航不物化），
 * 追加唯一 data Uri 保证 PendingIntent 隔离 + NEW_TASK/SINGLE_TOP 标志（对齐 [PetWidgetIntents] 做法）。
 */
private fun openConversationIntent(context: Context, conversationUuid: String): Intent =
    Notifier.conversationShortcutIntent(context, conversationUuid).apply {
        data = Uri.parse("aichat://status/$conversationUuid")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
