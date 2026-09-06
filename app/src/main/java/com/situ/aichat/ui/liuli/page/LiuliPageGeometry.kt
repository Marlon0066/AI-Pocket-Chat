package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 琉璃**二级屏**排版几何（图纸 2026-09-06 卷四 §4.1·**全部取本表值或 token**，孤值即打回）。
 *
 * 与 `LiuliHomeGeometry`（主页四 Tab）/ `LiuliChatGeometry`（聊天屏）平级：这张管「有返回钮的二级屏」
 * ——页壳、内嵌圆角分组、详情页头 / 动作排 / 统计卡 / 分段条。
 *
 * 与主页同名的量（[titleTop] / [titleHeight] / [titleGap] / [compactBar] / [gutter]）是**有意同值复制**：
 * 两张表各自独立可改，`LiuliPageGeometryTest` 钉住「今天必须同值」防止哪天一边漂了没人发现。
 */
object LiuliPageGeometry {

    // ── 页壳：导航行 / 大标题带 / 收起顶栏（§4.1 · A-3） ────────────────────────────
    /** 二级页比主页多的那一行：状态栏之下的 44dp 导航行（返回圆钮 + 尾随动作·纸面无玻璃）。 */
    val navRow: Dp = 44.dp
    /** 大标题带：顶 = 导航行底 + 2dp、高 40（主页是「状态栏底 + 2」，二级页多让过一整条导航行）。 */
    val titleTop: Dp = 2.dp
    val titleHeight: Dp = 40.dp
    /** 大标题带 ↔ 搜索槽 / 首组的呼吸。 */
    val titleGap: Dp = 12.dp
    /** 收起后的玻璃顶栏在状态栏之下的那一段高（总高 = 状态栏 + 44）。 */
    val compactBar: Dp = 44.dp
    /** 返回 / 尾随圆钮视觉直径（触达 48 由 `LiuliCircleButton` 自保·两态同位不跳）。 */
    val backButton: Dp = 40.dp
    /** 尾随动作之间的缝。 */
    val actionButtonGap: Dp = 12.dp
    /** 返回 / 尾随圆钮里的图标尺寸（对版稿 `.cbtn svg{20px}`·两态、各页同值）。 */
    val chromeIcon: Dp = 20.dp
    /** 大标题右侧留给尾随件的宽 = 钮 + 缝（与主页 `titleEndReserve` 同算式同值）。 */
    val titleEndReserve: Dp = backButton + titleGap
    /** 屏左右 gutter。 */
    val gutter: Dp = 20.dp
    /** 48dp 触达外框（a11y）——布局脚印之外居中外溢，见 [liuliFootprint] / [liuliTouchHeight]。 */
    val touchTarget: Dp = 48.dp

    /** 内容顶内距（`LazyColumn` 的 `contentPadding.top` / `Column` 的 `padding(top)`）= 状态栏 + 导航行。 */
    fun contentTopInset(statusBarTop: Dp): Dp = statusBarTop + navRow

    // ── 内嵌圆角分组与行族（§4.1 · 对版稿 A 甲） ─────────────────────────────────
    val groupCorner: Dp = 16.dp
    val groupPadH: Dp = 16.dp
    /** 单行行高下限 / 两行行高下限。 */
    val rowMin: Dp = 52.dp
    val rowTwoLine: Dp = 64.dp
    /** 两行行的上下内距（对版稿 `.row.two{padding:10px 16px}`）。 */
    val rowTwoLinePad: Dp = 10.dp
    /** 图标砖：28 见方、圆角 7、砖 ↔ 文字 12。 */
    val tile: Dp = 28.dp
    val tileCorner: Dp = 7.dp
    val tileGap: Dp = 12.dp
    /** 组内分隔发丝起点：有砖 = 16 + 28 + 12 = 56；无砖 = 16。 */
    val dividerInsetTile: Dp = groupPadH + tile + tileGap
    val dividerInsetPlain: Dp = groupPadH
    /**
     * 页底留白（列表 `contentPadding.bottom` 里导航栏之外的那一段）。卷四三屏各自写了一份私有
     * `PAGE_BOTTOM = 24.dp`；卷五起三十屏一律取本表这一枚，别再复制（§9 ⑥「孤值即打回」）。
     */
    val pageBottom: Dp = 24.dp

    /** 组标题 ↔ 组体 / 组体 ↔ 组脚注 / 组 ↔ 组。 */
    val groupHeaderBottom: Dp = 8.dp
    val groupFooterTop: Dp = 6.dp
    val groupGap: Dp = 24.dp

    // ── 详情页（T3·§4.1 · A-8–A-11） ────────────────────────────────────────────
    /** 头图高（满宽）与底部遮罩带高。 */
    val hero: Dp = 280.dp
    val heroScrim: Dp = 130.dp
    /**
     * 头图收起判据的尾巴**名义值**：契约 §6.5 写的「图底 − 88」= 44 状态栏 + 44 收起顶栏。
     * 实际判据用**真状态栏**（`WindowInsets.statusBars` + [compactBar]·复核 R1 🔴-1）：状态栏矮于 44 的机型
     * 若照 88 算会早收、切段落位也漏掉状态栏那一段。本值只留作契约对表与测试钉。
     */
    val heroCollapseTail: Dp = 88.dp
    /** 动作排：圆钮视觉 56 · 版位 68 · 缝 16（4×68 + 3×16 = 320 = 360 − 2×20·窄屏不换行）。 */
    val action: Dp = 56.dp
    val actionSlot: Dp = 68.dp
    val actionGap: Dp = 16.dp
    /** 分段条：纸面态 36 高 · 收起后住进玻璃顶栏的那枚 40 高玻璃 pill。 */
    val stripPaper: Dp = 36.dp
    val stripGlass: Dp = 40.dp
    /** 玻璃顶栏之下再铺的 subBar 槽高（8 + 40 + 8）。 */
    val subBar: Dp = 56.dp

    /** 收起态覆盖区总高 = 状态栏 + 收起顶栏 + subBar（有 subBar 时；无则不含末项·调用方判）。 */
    fun cover(statusBarTop: Dp, hasSubBar: Boolean): Dp =
        statusBarTop + compactBar + (if (hasSubBar) subBar else 0.dp)

    /** 统计卡字号（Q-S6 甲）。 */
    val statValue: TextUnit = 18.sp
    val statLabel: TextUnit = 12.sp

    // ── 设置族新增件（图纸 2026-09-06 卷五 A-4·§8 C0「加表并钉」·由 `LiuliPageGeometryTest` 钉） ──
    /** 步进器行右端两枚圆钮的视觉直径（Button 档·48 触达由外套的 28 盒让它居中外溢·同 [LiuliPageCircleAction] 的做法）。 */
    val stepperButton: Dp = 28.dp
    /** 步进器圆钮里的 ± 图标尺寸。 */
    val stepperIcon: Dp = 16.dp
    /** 步进器值槽最小宽：数字换位数时两枚钮不许左右挪（`tnum` 等宽 + 定宽槽双保险）。 */
    val stepperValueMin: Dp = 44.dp

    /** 列表页悬浮主行动钮（FAB·A-4 ⑦）：视觉 56 · 图标 24 · 右缘 = [gutter] · 距导航栏 24。 */
    val fab: Dp = 56.dp
    val fabIcon: Dp = 24.dp
    val fabBottom: Dp = 24.dp

    /** Snackbar 玻璃 pill（A-4 ④）：左右 = [gutter] · 距导航栏 12 · 最小高 44 · 内距 14（上下）/ 16（左右）。 */
    val snackbarBottom: Dp = 12.dp
    val snackbarMinHeight: Dp = 44.dp
    val snackbarPadV: Dp = 14.dp
    val snackbarPadH: Dp = 16.dp

    /**
     * 底部保存栏（契约 §6.5「底部保存栏（T2）」·图纸 A-9）：栏高 56（导航栏另加）· 内钮 44 高满宽 ·
     * 左右 = [gutter] · 两钮并排时的缝 12。
     */
    val saveBar: Dp = 56.dp
    /** 保存栏内容的上下内距（56 − 44 钮）/ 2 = 6：栏长高时上下各留这么多。 */
    val saveBarPadV: Dp = 6.dp
    val saveBarButton: Dp = 44.dp
    val saveBarGap: Dp = 12.dp

    /** 输入行（契约 §6.5「输入行（T2）」）：标签固定 96 宽 · 标签 ↔ 输入格的缝 12（= [tileGap]）。 */
    val inputLabelWidth: Dp = 96.dp

    /**
     * 进度条（A-4 ③）：轨 4 高 · 圆角 2。**与 `LiuliSlider` 的轨同源落值**（契约 §3.2「轨 4 高」）——
     * 那一枚的 `TRACK_HEIGHT` 是文件私有常量，本表这一行是两件共同遵守的那个数，改一侧必须同步另一侧。
     */
    val progressTrack: Dp = 4.dp
    val progressCorner: Dp = 2.dp
}

/**
 * 「触达框不占版」（卷二A 复核 R1 🔴-2）：布局脚印 = [visual]（圆钮视觉直径 / 胶囊高），48dp 触达框以 `requiredSize`
 * 居中外溢——否则 `minimumInteractiveComponentSize` 会把 40 / 44dp 的圆钮撑成 48dp 版位：顶栏 Row 被撑到 48
 * 令 chrome 算式差 2dp、世界胶囊被推低 14dp 且日期胶囊压进它的框；输入排 Bottom 对齐时圆钮比
 * 输入胶囊高出 2dp。Compose 对超出约束的子项自动居中放置，触达仍是完整 48dp。
 *
 * （卷四 A-2 从 `ui/liuli/chat/LiuliChatGeometry.kt` **只搬不改**到 page 包并公有化——聊天屏 / 主页 / 二级屏都用它。）
 */
fun Modifier.liuliFootprint(visual: Dp): Modifier =
    this.size(visual).requiredSize(LiuliPageGeometry.touchTarget)

/**
 * 「触达高不占版」的文字链 / 卡内钮版（卷二C 复核 R1 🔴-1·REDLINES「a11y 48dp」）：与 [liuliFootprint] 同思路，
 * 但宽随父级（`weight` / 文字本宽）走、版位高由**外层约束或内容本高**决定——内层被强制量成 48dp 高，本节点
 * 只上报 `constrainHeight(内容固有高)`，多出的部分上下居中外溢（Compose 对超出约束的子项自动居中放置）。
 *
 * 用法：`Modifier.height(34.dp).liuliTouchHeight().clickable(...)`（版位 34 · 触达 48）或
 * `Modifier.liuliTouchHeight().clickable(...)` 直接套在一行文字外（版位 = 字高 · 触达 48）。
 * 点击面在内层，所以 `clickable` **必须排在本修饰符之后**；内层再放一个 `Box(contentAlignment = Center)`
 * 让视觉在 48 里居中，否则内容会被顶到 48 框的上沿。
 *
 * **只给「四周是非点击内容」的件用**（卷二C R2 🔴-1）：紧贴堆叠的兄弟点击行各自外溢 48 会互压。
 */
fun Modifier.liuliTouchHeight(): Modifier = this.layout { measurable, constraints ->
    val touch = LiuliPageGeometry.touchTarget.roundToPx()
    val natural = measurable.minIntrinsicHeight(constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minHeight = touch, maxHeight = touch))
    val height = constraints.constrainHeight(natural)
    layout(placeable.width, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
}
