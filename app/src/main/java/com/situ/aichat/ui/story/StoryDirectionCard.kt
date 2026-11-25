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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons

/**
 * 章末「你的走向」卡（图纸 2026-08-06「已存走向推进区状态化」§4.1·mockup story_direction_resume_mockup）。
 *
 * 治的是「走向已落库、屏上却哪儿都看不见」：用户在导演台写下走向 → 保存 → 点「稍后」，走向就静静躺在末章
 * `userChoice` 里（章末选项默认关 ⇒ 选择区根本不渲染），只剩一个说反话的「让故事自然发展」按钮。这张卡把那条
 * 走向摆回台面，并且**整卡即编辑入口**——点它开导演台（编辑模式预填原文）。纯展示零逻辑（模式判定在
 * [StoryReaderEndgameLogic.continueZoneMode]，写库在 `StoryDirectionEditor`）。
 *
 * 结构照 [StoryDraftCard] 镜像，差别只在身份色与两处对比度合规落点（图纸 §4.0）：**卡底取
 * [StoryReaderLayout.chromeScrimColor]、标题取 [StoryReaderLayout.textColor]**，陶土身份只走 1dp 边框与 tag——
 * mockup 原案的陶土淡底（accent@0.08 覆纸）上陶土标题浅档实测仅 3.90:1 < 4.5，落值由 `ColorContrastTest` 看门。
 * 颜色**一律走 [StoryReaderLayout]**（纸面与 App 主题两域正交·房规），禁裸 `Color(0x…)`。
 */
@Composable
internal fun StoryDirectionCard(
    directionText: String,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = StoryReaderLayout.chromeScrimColor(isDark),
        border = BorderStroke(1.dp, StoryReaderLayout.menuAccentColor(isDark).copy(alpha = 0.40f)),
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.story_continue_direction_title),
                    color = StoryReaderLayout.textColor(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.story_continue_direction_tag),
                    color = StoryReaderLayout.menuAccentColor(isDark),
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                // 小笔图标只是「可改」的暗示，不是独立按钮（整卡 clickable 已接导演台·同草稿卡）
                Icon(
                    AppActionIcons.Edit,
                    contentDescription = null,
                    tint = StoryReaderLayout.secondaryTextColor(isDark),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                directionText,
                color = StoryReaderLayout.textColor(isDark).copy(alpha = DIRECTION_BODY_ALPHA),
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                fontFamily = FontFamily.Serif,
                maxLines = DIRECTION_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 走向正文的预览行数（图纸 §9 锁定值·超出省略号截断，全文在导演台里可编辑）。 */
internal const val DIRECTION_MAX_LINES = 4

/** 走向正文透明度（图纸 §4.1 锁定值·与 [com.situ.aichat.ui.designsystem.ColorContrastTest] 互指）。 */
internal const val DIRECTION_BODY_ALPHA = 0.80f
