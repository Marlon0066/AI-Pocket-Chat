package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.onboarding.agreementContent
import androidx.compose.foundation.layout.offset

/**
 * 协议正文的段间缝（逐字照暖陶 `AgreementViewScreen` 的 20）。
 *
 * 左右让位**没有**照抄暖陶的 24：琉璃页壳的 gutter 是 [LiuliPageGeometry.gutter] = 20，正文若单独用 24
 * 就会比同屏的大标题带右缩 4dp（同屏两条基准线 = 排版事故）。正文本体（[agreementContent] 的分段、字号、
 * 顺序）一个字未改（§9 ⑤「禁重写 agreementContent()」·见图纸 §11 D-17）。
 */
private val AGREEMENT_GAP = 20.dp

/**
 * 关于页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 22）。无 VM；版本号取 [BuildConfig.VERSION_NAME]。
 * 与协议复看屏 [LiuliAgreementViewScreen] 同文件（同暖陶 `AboutScreen.kt`）。
 */
@Composable
fun LiuliAboutScreen(
    onBack: () -> Unit,
    onOpenAgreement: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.about_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(
                        header = stringResource(R.string.about_section_info),
                        footer = stringResource(R.string.about_disclaimer),
                    ) {
                        // 版本行只读（暖陶也没给它 onClick）。
                        LiuliValueRow(
                            title = stringResource(R.string.about_version),
                            value = BuildConfig.VERSION_NAME,
                            divider = false,
                        )
                        LiuliNavRow(title = stringResource(R.string.about_agreement), onClick = onOpenAgreement)
                    }
                    LiuliGroup(header = stringResource(R.string.about_section_ack)) {
                        LiuliRowBase(
                            divider = false,
                            verticalPadding = LiuliPageGeometry.groupPadH,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                stringResource(R.string.about_ack_body),
                                style = AppTypography.listPreview,
                                color = colors.text.secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 用户协议复看（琉璃·图纸 2026-09-06 卷五 §4.1 屏 23·A-11「只读三屏最薄」/ D-21「气氛屏只换外壳」）。
 *
 * 正文 [agreementContent] **零改**（与首启协议共用同一份），段间 20 照搬；左右让位改随页壳 gutter 20
 * （暖陶 24·图纸 §11 D-17）——换的只有页壳。
 */
@Composable
fun LiuliAgreementViewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.about_agreement)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            // 正文让位走 contentPadding（大标题带自带左 20·再套一层列内距会双缩进到 40·复核 R1 C-1）。
            contentPadding = PaddingValues(
                start = LiuliPageGeometry.gutter,
                end = LiuliPageGeometry.gutter,
                top = LiuliPageGeometry.navRow,
                bottom = bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(AGREEMENT_GAP),
        ) {
            // 大标题带自己带 20 内距：往左抵回 contentPadding 的那 20，与其他页的标题同一条基准线。
            item(key = "large-title") { LiuliLargeTitle(title, Modifier.offset(x = -LiuliPageGeometry.gutter)) }
            agreementContent()
        }
    }
}
