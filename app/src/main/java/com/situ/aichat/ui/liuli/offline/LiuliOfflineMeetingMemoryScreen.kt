package com.situ.aichat.ui.liuli.offline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.offline.EmptyMeetingState
import com.situ.aichat.ui.offline.MeetingGalleryTimeline
import com.situ.aichat.ui.offline.OfflineMeetingEditSheet
import com.situ.aichat.ui.offline.OfflineMeetingMemoryViewModel
import com.situ.aichat.ui.offline.OfflineReviewView

/** 见面回忆「回忆长廊」（琉璃 2.0 脸）。 */
@Composable
fun LiuliOfflineMeetingMemoryScreen(
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

    val listState = rememberLazyListState()
    val collapsed = rememberLargeTitleCollapsed(listState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(title = "见面回忆", onBack = onBack, collapsed = collapsed) {
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = LiuliPageGeometry.gutter)) {
                Column(Modifier.padding(top = LiuliPageGeometry.navRow + 4.dp)) { LiuliLargeTitle("见面回忆") }
                EmptyMeetingState()
            }
        } else {
            MeetingGalleryTimeline(
                sessions = sessions,
                retryingSessionIds = retryingSessionIds,
                onRetry = viewModel::retryFallback,
                onOpen = viewModel::openReview,
                onEdit = viewModel::openEdit,
                listState = listState,
                contentPadding = PaddingValues(
                    start = LiuliPageGeometry.gutter,
                    end = LiuliPageGeometry.gutter,
                    top = LiuliPageGeometry.navRow + 4.dp,
                    bottom = navBottom + LiuliPageGeometry.pageBottom,
                ),
                header = { LiuliLargeTitle("见面回忆", modifier = Modifier.padding(bottom = 8.dp)) },
            )
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
