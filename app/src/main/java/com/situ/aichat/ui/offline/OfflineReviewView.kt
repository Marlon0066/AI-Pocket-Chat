package com.situ.aichat.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.offline.OfflineContentBlock
import com.situ.aichat.offline.OfflineContentParser
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTopBarIcons

/**
 * 线下见面只读回顾（1:1 iOS `OfflineReviewView`）：背景同沉浸剧场，把该次见面的全部消息预解析成内容块
 * 静态渲染（无逐块淡入），顶部 ✦ 装饰 + 见面信息、底部「见面结束」。供见面回忆卡点击进入。
 */
@Composable
fun OfflineReviewView(
    messages: List<MessageEntity>,
    meetingInfo: String,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    themeColorHex: String?,
    backgroundStyle: String,
    particleStyle: String,
    backgroundColor: String,
    chatWallpaperPath: String? = null,
    onBack: () -> Unit,
) {
    val themeColor = parseOfflineThemeColor(themeColorHex)
    // 见面回顾真·全屏沉浸：背景 fillMaxSize 自然铺到状态栏/导航栏后；顶栏走透明剧场横梁。
    Box(Modifier.fillMaxSize()) {
        OfflineBackgroundView(
            backgroundStyle = backgroundStyle,
            particleStyle = particleStyle,
            backgroundColor = backgroundColor,
            themeColorHex = themeColorHex,
            chatWallpaperPath = chatWallpaperPath,
            modifier = Modifier.fillMaxSize(),
        )
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp),
                ) {
                    AppIconButton(
                        icon = AppTopBarIcons.Back,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = onBack,
                        tint = OfflineTheater.textBright,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = AppSpacing.gutterForRoundButton),
                    )
                    Text(
                        "见面回顾",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = OfflineTheater.textBright,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .semantics { heading() },
                    )
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                item(key = "review_header") { ReviewDecoration(themeColor, meetingInfo, isFooter = false) }
                items(messages, key = { it.messageUUID }) { message ->
                    ReviewMessage(message, characterName, characterAvatarPath, userName, userAvatarPath, themeColor)
                }
                item(key = "review_footer") { ReviewDecoration(themeColor, "", isFooter = true) }
            }
        }
    }
}

@Composable
private fun ReviewMessage(
    message: MessageEntity,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    themeColor: Color,
) {
    val isUser = message.roleRaw == "user"
    val blocks = remember(message.content, isUser) {
        if (isUser) {
            OfflineContentParser.parseUserBlocks(message.content).ifEmpty {
                if (message.content.isEmpty()) emptyList() else listOf(OfflineContentBlock.UserAction(message.content))
            }
        } else {
            OfflineContentParser.parse(message.content)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            OfflineContentBlockView(
                block = block,
                characterName = characterName,
                characterAvatarPath = characterAvatarPath,
                userName = userName,
                userAvatarPath = userAvatarPath,
                themeColor = themeColor,
            )
        }
    }
}

/** 顶部/底部 ✦ 虚线装饰 + 「线下见面」信息 / 「见面结束」（1:1 iOS headerView/footerView）。 */
@Composable
private fun ReviewDecoration(themeColor: Color, meetingInfo: String, isFooter: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = if (isFooter) 24.dp else 20.dp, bottom = if (isFooter) 40.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StarDashedRow(themeColor.copy(alpha = if (isFooter) 0.4f else 0.6f))
        if (isFooter) {
            Text("见面结束", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, tint = themeColor, modifier = Modifier.width(16.dp))
                Text("线下见面", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = themeColor)
            }
            if (meetingInfo.isNotEmpty()) {
                Text(meetingInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StarDashedRow(themeColor.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun StarDashedRow(starColor: Color) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(lineColor))
        Text("✦", style = MaterialTheme.typography.labelSmall, color = starColor)
        Box(Modifier.weight(1f).height(0.5.dp).background(lineColor))
    }
}
