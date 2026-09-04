package com.situ.aichat.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 角色档案「见面回忆」全部页——「回忆长廊」（SKY-5·契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL §9）。
 * 单一竖向时间流（月相珠脊线 + 天色书签卡 + 月份刻度 + 虚珠/落款）；旧「轮播/列表双模式 + 切换钮 +
 * 发起方色条图例 + 页码指示器」已整体退役（2026-07-10 拍板·发起方语义改明话进条目脚注）。
 * 保留：空态、点卡进只读回顾、长按编辑、「简版」徽章手动重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMeetingMemoryScreen(
    onBack: () -> Unit,
    viewModel: OfflineMeetingMemoryViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val character by viewModel.character.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val reviewSession by viewModel.reviewSession.collectAsStateWithLifecycle()
    val reviewMessages by viewModel.reviewMessages.collectAsStateWithLifecycle()
    val editSession by viewModel.editSession.collectAsStateWithLifecycle()
    val retryingSessionIds by viewModel.retryingSessionIds.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "见面回忆",
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (sessions.isEmpty()) {
                EmptyMeetingState()
            } else {
                MeetingGalleryTimeline(
                    sessions = sessions,
                    retryingSessionIds = retryingSessionIds,
                    onRetry = viewModel::retryFallback,
                    onOpen = viewModel::openReview,
                    onEdit = viewModel::openEdit,
                )
            }
        }
    }

    // 只读回顾覆盖层
    reviewSession?.let { session ->
        OfflineReviewView(
            messages = reviewMessages,
            meetingInfo = "${session.location} · ${session.activity}",
            characterName = character?.name.orEmpty().ifEmpty { "角色" },
            characterAvatarPath = character?.avatarPath,
            userName = userProfile?.nickname.orEmpty().ifEmpty { "你" },
            userAvatarPath = userProfile?.avatarPath,
            themeColorHex = character?.offlineThemeColorHex,
            backgroundStyle = appSettings.offlineBackgroundStyleRaw,
            particleStyle = appSettings.offlineParticleStyleRaw,
            backgroundColor = appSettings.offlineBackgroundColor,
            onBack = viewModel::closeReview,
        )
    }

    // 编辑覆盖层（长按条目进入）
    editSession?.let { session ->
        OfflineMeetingEditSheet(
            session = session,
            onSave = { location, activity, summary -> viewModel.saveEdit(session, location, activity, summary) },
            onRegenerate = { location, activity -> viewModel.regenerateSummary(session, location, activity) },
            onDismiss = viewModel::closeEdit,
        )
    }
}

@Composable
private fun EmptyMeetingState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text("还没有一起出去过…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "在聊天中等待 ta 的邀约吧",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
