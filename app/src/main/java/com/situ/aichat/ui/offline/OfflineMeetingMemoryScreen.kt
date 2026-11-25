package com.situ.aichat.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.grainSurface

/**
 * 角色档案「见面回忆」全部页——「回忆长廊」（暖陶外衣）。
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
    val colors = AppTheme.colors

    Scaffold(
        containerColor = colors.surface.base,
        topBar = { AppTopBar(title = "见面回忆", onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(colors.surface.base).grainSurface()) {
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
internal fun EmptyMeetingState() {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text("还没有一起出去过…", style = AppTypography.label, color = colors.text.secondary)
        Text("在聊天中等待 ta 的邀约吧", style = AppTypography.caption, color = colors.text.tertiary)
    }
}
