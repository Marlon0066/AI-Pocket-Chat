package com.situ.aichat.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentNotificationEntity
import com.situ.aichat.data.model.MomentNotificationType
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppElevation
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.rememberTimeTick
import kotlinx.coroutines.launch

/**
 * 朋友圈互动通知列表（M06 7.2.8，对齐 iOS `MomentNotificationListView`）：未读通知行（角色头像 + 描述 + 内容
 * 预览 + 相对时间）+ 滑动已读 + 全部已读 + 点进帖子详情（帖已删 → snackbar 提示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentNotificationListScreen(
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    viewModel: MomentNotificationViewModel = hiltViewModel(),
) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.moment_detail_deleted)

    val aiLabel = stringResource(R.string.moment_author_ai)
    val relStrings = DateFormatters.RelativeTimeStrings(
        justNow = stringResource(R.string.relative_time_just_now),
        minutesAgo = stringResource(R.string.relative_time_minutes_ago),
        hoursAgo = stringResource(R.string.relative_time_hours_ago),
        yesterday = stringResource(R.string.relative_time_yesterday),
    )
    val nowMillis = rememberTimeTick() // moments-ui-10：通知列表相对时间每 60s 自动刷新（= iOS MomentNotificationListView 读 TimeTick）

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.moment_notif_list_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    if (notifications.isNotEmpty()) {
                        AppButton(onClick = viewModel::markAllRead, style = AppButtonStyle.Text) {
                            Text(stringResource(R.string.moment_notif_mark_all_read))
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (notifications.isEmpty()) {
            val colors = AppTheme.colors
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).background(colors.surface.base).grainSurface().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 空态去 M3 内置图标（契约 §2.6）：自绘铃铛，tertiary 装饰档。
                Icon(AppMomentIcons.Bell, contentDescription = null, modifier = Modifier.size(48.dp), tint = colors.text.tertiary)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.moment_notif_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.text.primary)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.moment_notif_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text.secondary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // 页底 = surface.base + 纸感 grain（契约 §2.6·行自身画 base 保滑动揭示不透底）。
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).background(AppTheme.colors.surface.base).grainSurface()) {
                items(notifications, key = { it.id }) { notification ->
                    // confirmValueChange 在 material3（compose BOM 2026.06）被弃用且官方未给替代 API。
                    // 此处沿用以保持「左滑标记已读」的现有手感字节级不变（铁律：本次升级不改行为）；
                    // 迁移到新 anchor 模式留待专门的 UI 走查再议。
                    @Suppress("DEPRECATION")
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.markRead(notification.id)
                                true
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = { MarkReadBackground() },
                    ) {
                        NotificationRow(
                            notification = notification,
                            character = characters[notification.characterUuid],
                            aiLabel = aiLabel,
                            timeText = DateFormatters.relativeTimeString(notification.timestamp, nowMillis, relStrings),
                            onClick = {
                                viewModel.openNotification(notification) { uuid ->
                                    if (uuid != null) {
                                        onOpenPost(uuid)
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar(deletedText) }
                                    }
                                }
                            },
                        )
                    }
                    // 行间发丝分隔（契约 §2.6·D6 拍板：透明行不卡片化）：inset 68 = 行首距 16 + 头像 40 + 间距 12；
                    // 放在 SwipeToDismissBox 之外，滑动时分隔线不跟行位移。
                    AppListDivider(modifier = Modifier.padding(start = 68.dp), startInset = 0.dp)
                }
            }
        }
    }
}

@Composable
private fun MarkReadBackground() {
    // 滑动已读揭示底（契约 §2.6）：陶土软容器 + 同族深字（完成语义走品牌而非 M3 primaryContainer）。
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.accent.container)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent.onContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.moment_notif_mark_read), style = MaterialTheme.typography.labelLarge, color = colors.accent.onContainer)
    }
}

@Composable
private fun NotificationRow(
    notification: MomentNotificationEntity,
    character: CharacterEntity?,
    aiLabel: String,
    timeText: String,
    onClick: () -> Unit,
) {
    val name = character?.name ?: aiLabel
    val colors = AppTheme.colors
    Row(
        // 「透明行」（D6 拍板）：视觉上与页底一体——但必须画不透明 base 底，否则滑动揭示底会从行身透出。
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface.base)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterAvatar(name = name, avatarPath = character?.avatarPath, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                notificationTitle(MomentNotificationType.fromRaw(notification.typeRaw), name),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text.primary,
                maxLines = 2,
            )
            if (notification.contentPreview.isNotEmpty()) {
                Text(
                    notification.contentPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                    maxLines = 2,
                )
            }
            Text(timeText, style = MaterialTheme.typography.labelSmall, color = colors.text.secondary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.text.tertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 通知描述（对齐 iOS `notificationTitle` 4 类）。 */
@Composable
private fun notificationTitle(type: MomentNotificationType, name: String): String = when (type) {
    MomentNotificationType.COMMENT_ON_USER_POST -> stringResource(R.string.moment_notif_title_comment, name)
    MomentNotificationType.REPLY_TO_USER_COMMENT -> stringResource(R.string.moment_notif_title_reply, name)
    MomentNotificationType.LIKE_ON_USER_POST -> stringResource(R.string.moment_notif_title_like, name)
    MomentNotificationType.CO_LIKE -> stringResource(R.string.moment_notif_title_colike, name)
}
