package com.situ.aichat.ui.liuli.gift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.gift.GiftImage
import com.situ.aichat.ui.gift.ReceivedGiftDetailViewModel
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.util.DateFormatters

/** 收礼详情页（琉璃 2.0 脸 · 纯只读，与 `ReceivedGiftDetailScreen` 共用 [ReceivedGiftDetailViewModel]）。 */
@Composable
fun LiuliReceivedGiftDetailScreen(
    onBack: () -> Unit,
    viewModel: ReceivedGiftDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val dark = LocalIsDarkTheme.current
    val scrollState = rememberScrollState()
    val collapsed = rememberScrollCollapsed(scrollState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(title = "礼物详情", onBack = onBack, collapsed = collapsed) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = LiuliPageGeometry.navRow, bottom = navBottom + LiuliPageGeometry.pageBottom),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LiuliLargeTitle("礼物详情")
            Column(
                modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.item?.let { GiftImage(item = it, size = 200.dp, cornerRadius = 24.dp) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("来自", style = typography.caption, color = colors.text.secondary)
                    Text(state.giftName, style = typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.text.primary)
                    Text(state.characterName, style = typography.secondary, color = colors.text.secondary)
                }
            }
            if (state.senderMessage.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CharacterAvatar(name = state.characterName, avatarPath = state.avatarPath, size = 44.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        Text(state.characterName, style = typography.label, color = colors.text.primary)
                        Box(Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark)) {
                            Text(
                                state.senderMessage,
                                style = typography.body,
                                color = colors.text.primary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppActionIcons.Clock, contentDescription = null, tint = colors.text.secondary, modifier = Modifier.size(14.dp))
                Text(DateFormatters.mediumDateShortTime(state.timestamp), style = typography.caption, color = colors.text.secondary)
            }
        }
    }
}
