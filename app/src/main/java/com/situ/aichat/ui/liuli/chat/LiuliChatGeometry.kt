package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 琉璃聊天屏排版几何（图纸 2026-09-05 卷二A §4.7·**全部取本表值或 token**，孤值即打回）。
 * 列表区自窗口顶起（edge-to-edge），故顶留白要含状态栏 inset——由调用方读一次 [WindowInsets.statusBars] 传入。
 */
internal object LiuliChatGeometry {
    /** 顶栏起点：状态栏底 + 6dp。 */
    val topBarInsetTop: Dp = 6.dp
    /** 顶栏三片片间距。 */
    val topBarPieceGap: Dp = 10.dp
    /** 顶栏左右（圆钮视觉边 = 12 + (48−40)/2 = 16 ≈ `AppSpacing.gutterForRoundButton`）。 */
    val topBarSide: Dp = 12.dp
    /** 名片胶囊高（两侧 40dp 圆钮在其中居中）。 */
    val topBarHeight: Dp = 44.dp
    /** 世界胶囊：名片底 + 8dp。 */
    val worldPillTop: Dp = 8.dp
    val worldPillHeight: Dp = 24.dp
    /** 日期胶囊：世界胶囊底 + 8dp（无胶囊 = 顶栏底 + 8dp）。 */
    val datePillTop: Dp = 8.dp
    /** 列表左右 gutter：12dp（尾巴伸出 9dp 后仍留 3dp 安全边）。 */
    val listHorizontal: Dp = 12.dp
    /** 首条 / 末条气泡与输入区实测顶缘之间的呼吸（复核 R1 🔴-1：底留白改跟随输入区实测高）。 */
    val listBottomGap: Dp = 12.dp
    /** 输入区 overlay 的默认实测高 = 三片行 44 + 离屏底 12（无引用条 / 日历卡 / 草稿条、单行时）。 */
    val inputOverlayDefaultHeight: Dp get() = inputPieceSize + inputBottom
    /**
     * 列表底留白 = 输入区 overlay **实测高**（含引用条 / 日历卡 / 草稿条 / 多行长高·不含导航栏）+ 12 呼吸；
     * 调用方再加导航栏 inset。默认态 = 44 + 12 + 12 = 68dp。回底钮 / snackbar 与列表共用这一个数
     * （复核 R1 🔴-1：写死 68 会让引用条 / 日历卡 / 多行输入盖住最新一条气泡与回底钮）。
     */
    fun listBottomPadding(inputOverlayHeight: Dp): Dp = inputOverlayHeight + listBottomGap
    /** 默认态底留白（68dp）——只供测试 / 首帧初值，运行时一律走实测高的重载。 */
    val listBottomPadding: Dp get() = listBottomPadding(inputOverlayDefaultHeight)
    /** 首条气泡与 chrome 的距：chrome 底 + 12dp。 */
    val listTopGap: Dp = 12.dp
    val inputSide: Dp = 10.dp
    val inputBottom: Dp = 12.dp
    val inputPieceGap: Dp = 6.dp
    val inputPieceSize: Dp = 44.dp
    /** 引用条 / 日历卡 ↔ 输入区。 */
    val stackGap: Dp = 6.dp
    val scrollFabEnd: Dp = 14.dp
    /** 回底钮底缘与输入区顶的间隙（真正的底 padding 见 [LiuliScrollToBottom] 的换算）。 */
    val scrollFabBottom: Dp = 12.dp

    /** chrome（顶栏 + 世界胶囊）底缘距窗口顶。 */
    fun chromeBottom(statusBarTop: Dp, hasWorldPill: Boolean): Dp =
        statusBarTop + topBarInsetTop + topBarHeight +
            (if (hasWorldPill) worldPillTop + worldPillHeight else 0.dp)

    /** 列表顶留白（反转列表下 = 视觉顶部的 contentPadding.top）。 */
    fun listTopPadding(statusBarTop: Dp, hasWorldPill: Boolean): Dp =
        chromeBottom(statusBarTop, hasWorldPill) + listTopGap

    /** 日期胶囊浮现位置（自列表区顶起）。 */
    fun datePillOffset(statusBarTop: Dp, hasWorldPill: Boolean): Dp =
        chromeBottom(statusBarTop, hasWorldPill) + datePillTop

    /** 48dp 触达外框（a11y）——布局脚印之外居中外溢，见 [com.situ.aichat.ui.liuli.page.liuliFootprint]。 */
    val touchTarget: Dp = 48.dp

    // ── 卷二B 增补（图纸 2026-09-05 卷二B §4.10 表·孤值即打回） ──────────────────────────────

    /** 「+」变形面板圆角（契约 §5.2）。 */
    val panelCorner: Dp = 24.dp
    /** 面板顶缘距三片行（= [stackGap]·同一坐标系）。 */
    val panelTop: Dp = stackGap
    /** 面板底缘距导航栏顶（= [inputBottom]·同一坐标系）。 */
    val panelBottom: Dp = inputBottom
    /** 面板瓦片视觉边长与圆角（契约 §5.2）。 */
    val panelTileSize: Dp = 44.dp
    val panelTileCorner: Dp = 14.dp
    /** 沉浸菜单：卡宽恒定、屏边距、泡下间隙（契约 §5.7·暖陶屏边距 6 → 琉璃 12）。 */
    val menuWidth: Dp = 200.dp
    val menuMargin: Dp = 12.dp
    val menuBubbleGap: Dp = 8.dp
    /** 双击 / 表情回应徽章直径（对版稿 26px 白圆）。 */
    val reactionBadge: Dp = 26.dp
    /** 右滑引用的玻璃圆箭头直径。 */
    val swipeArrow: Dp = 28.dp
    /** 录音声波条的红点直径（对版稿 8px）。 */
    val recordingDot: Dp = 8.dp

    // ── 卷二C 增补（图纸 2026-09-05 卷二C §2.2 表 + §3.2·孤值即打回） ─────────────────

    /** 纸白卡恒宽（礼物 / 红包 / 日程 / 通话记录·对版稿 `.card{width:236}`）。 */
    val cardWidth: Dp = 236.dp
    /** 文案重的卡恒宽（约见面 / 改期 / 线下邀约 / 结束·沿用暖陶 `widthIn(max 280)` 的上限当恒宽·A-3）。 */
    val cardWideWidth: Dp = 280.dp
    /** 卡圆角（= [com.situ.aichat.ui.liuli.designsystem.LiuliShapes.medium] 的 20dp）。 */
    val cardCorner: Dp = 20.dp
    /** 卡头图标块边长与圆角（对版稿 `.card .hd i{34/11}`）。 */
    val cardIconBlock: Dp = 34.dp
    val cardIconCorner: Dp = 11.dp
    /** 卡脚按钮高（对版稿 `.cbt{h34}`·圆角走 pill）。 */
    val cardButtonHeight: Dp = 34.dp
    /** 贴纸边长与圆角（契约 §5.5：暖陶 120 → 琉璃 110·A-7）。 */
    val stickerSize: Dp = 110.dp
    val stickerCorner: Dp = 24.dp
    /** 图片泡的宽上限（真实宽 = min(本值, bubbleMaxWidth)·A-3）。 */
    val imageMaxWidth: Dp = 200.dp
    /** 长文折叠底部渐隐带高（对版稿 `.fold::after{h36}`·A-1）。 */
    val foldFade: Dp = 36.dp
    /** 语音泡：播放圆直径 / 波形条宽 / 条间距 / 波形高（对版稿 `.voice`·A-5）。 */
    val voicePlay: Dp = 30.dp
    val voiceBarWidth: Dp = 3.dp
    val voiceBarGap: Dp = 2.dp
    val voiceBarHeight: Dp = 22.dp
}
