package com.situ.aichat.ui.story

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons

/**
 * 章末「本章计划草稿」卡（图纸 2026-08-05 U-2·样图画面②）——AI 在上一章末随 METADATA 捎带预排的
 * 「下一章打算写什么」，摆在推进区最前，让用户**在决定怎么推进之前**先看见 AI 的打算。
 *
 * 三个动作**零新按钮、零新回调**（图纸 J8·`StoryReaderViewModel` 已越硬顶，本卷一行不加）：
 * - 照它走 = 下方既有的陶土「让故事自然发展」胶囊；
 * - 改一改 / 自己写 = 点本卡或输入卡，都开导演台（栏 A 写亲笔走向、栏 B 改草稿，分工本就完备）。
 *
 * 另立文件而不塞进 [StoryContinueZone] 的理由同 [StoryChapterEndZone]：那个文件已压 UI 软上限。
 * 卡在阅读器纸面上，颜色**一律走 [StoryReaderLayout]**（纸面与 App 主题两域正交·房规），禁用 AppTheme.colors。
 */
@Composable
internal fun StoryDraftCard(
    draftBeats: String,
    draftUserEdited: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = StoryReaderLayout.chromeScrimColor(isDark),
        border = BorderStroke(0.75.dp, StoryReaderLayout.chromeBorderColor(isDark)),
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.story_continue_draft_title),
                    color = StoryReaderLayout.ornamentColor(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(storyDraftTagRes(draftUserEdited)),
                    color = StoryReaderLayout.menuAccentColor(isDark),
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                // 小笔图标只是「可改」的暗示，不是独立按钮（整卡 clickable 已接导演台）
                Icon(
                    AppActionIcons.Edit,
                    contentDescription = null,
                    tint = StoryReaderLayout.secondaryTextColor(isDark),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                draftBeats,
                color = StoryReaderLayout.secondaryTextColor(isDark),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                maxLines = DRAFT_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 草稿正文的预览行数（图纸 U-2 锁定值）。 */
internal const val DRAFT_MAX_LINES = 4
