package com.situ.aichat.ui.liuli.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 琉璃主页四 Tab 的排版几何（图纸 2026-09-06 卷三 §3.2 / §4.7·**全部取本表值或 token**，孤值即打回）。
 *
 * 与聊天屏的 [com.situ.aichat.ui.liuli.chat.LiuliChatGeometry] 平级：那张管聊天屏，这张管主页四页 + 玻璃底栏。
 * 派生量（[listBottomInset] / [dividerInset] / [listTop]）写成算式而非落值，让「改一处、派生跟着走」，
 * 金标由 `LiuliHomeGeometryTest` 独立反推。
 */
internal object LiuliHomeGeometry {

    // ── 大标题带 / 搜索槽 / 收起顶栏（§3.2「大标题带」·§4.2） ──────────────────────────
    /** 大标题带：顶 = 状态栏底 + 2dp、高 40。 */
    val titleTop: Dp = 2.dp
    val titleHeight: Dp = 40.dp
    /** 大标题带 ↔ 搜索槽 / 首行的呼吸。 */
    val titleGap: Dp = 12.dp
    /** 搜索槽高（pill）。 */
    val searchHeight: Dp = 38.dp
    /** 搜索槽顶 = 状态栏底 + 54（= 2 + 40 + 12）。 */
    val searchTop: Dp = titleTop + titleHeight + titleGap
    /** 有搜索槽的页：列表首行顶 = 状态栏底 + 104。 */
    val listTop: Dp = searchTop + searchHeight + titleGap
    /** 收起后的玻璃顶栏在状态栏之下的那一段高（总高 = 状态栏 + 44）。 */
    val compactBar: Dp = 44.dp
    /** 「+」圆钮视觉直径（触达 48 由 `LiuliCircleButton` 自保）；大标题右侧留 = 钮 + 缝。 */
    val plusButton: Dp = 40.dp
    val titleEndReserve: Dp = plusButton + titleGap

    // ── 屏 gutter / 列表行（§3.2「列表行」） ───────────────────────────────────────
    val gutter: Dp = 20.dp
    val rowAvatar: Dp = 54.dp
    val rowPadV: Dp = 12.dp
    /** 头像 ↔ 文字 / 通用行内小缝。 */
    val rowGap: Dp = 12.dp
    /** 分隔发丝起点 = 20 + 54 + 12 = 86。 */
    val dividerInset: Dp = gutter + rowAvatar + rowGap
    /** 未读丸：高 20（min 宽同值）、左右 7。 */
    val unreadHeight: Dp = 20.dp
    val unreadSidePadding: Dp = 7.dp

    // ── 玻璃底栏（§4.1·E 表） ────────────────────────────────────────────────────
    val tabBar: Dp = 66.dp
    val tabBarSide: Dp = 12.dp
    val tabBarBottom: Dp = 12.dp
    /** 当前 Tab 的玻璃透镜丸：72×46 扁透镜（用户 2026-09-06 拍板「丸甲 + 形状 B」·原 64×52 平染）。 */
    val tabPillWidth: Dp = 72.dp
    val tabPillHeight: Dp = 46.dp
    /** 丸在 66 高栏内的顶距（(66 − 46) / 2 = 10）。 */
    val tabPillTop: Dp = (tabBar - tabPillHeight) / 2
    /** Tab 槽高 52（≥ 48 触达·丸只是视觉，槽不跟丸变矮）。 */
    val tabSlot: Dp = 52.dp
    /** 槽在 66 高栏内的上下留白（(66 − 52) / 2 = 7）。 */
    val tabBarVPad: Dp = (tabBar - tabSlot) / 2
    val tabMini: Dp = 44.dp
    val tabIcon: Dp = 24.dp
    val tabMiniIcon: Dp = 20.dp
    val tabMiniStart: Dp = 12.dp
    val tabMiniEnd: Dp = 16.dp
    val tabMiniGap: Dp = 6.dp
    val tabLabelGap: Dp = 3.dp
    /** 徽章：16 高 min 16 宽，贴图标右上偏移 +6 / −4。 */
    val badge: Dp = 16.dp
    val badgeOffsetX: Dp = 6.dp
    val badgeOffsetY: Dp = (-4).dp
    /** 缩丸 / 展开的滚动累计阈值。 */
    val collapseThreshold: Dp = 24.dp
    /** 列表底留白 = 12 + 66 + 12（不含导航栏 inset·调用方另加）。 */
    val listBottomInset: Dp = tabBarBottom + tabBar + tabBarBottom

    // ── 卡片流（§3.2「卡片」） ───────────────────────────────────────────────────
    val cardGap: Dp = 12.dp
    val cardPad: Dp = 16.dp
    /** 方卡最小高（日记 / 故事两列）。 */
    val gridCardMinHeight: Dp = 148.dp
    /** 图标块（IconTile）：40 见方、圆角 12。 */
    val tile: Dp = 40.dp
    val tileCorner: Dp = 12.dp
    /** 卡内 chevron 尺寸。 */
    val chevron: Dp = 16.dp
}
