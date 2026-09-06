package com.situ.aichat.ui.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fable-5 间距 token（4dp 网格·**设计语言 §2.5**）。
 *
 * **出处**：设计语言 v2 提案 §2「间距节奏」（2026-07-03 用户拍板「深化不推翻」）→ 2026-09-06 用户复拍
 * **甲′ 案「尊重现有军规、不改军规本身」**，同日并入 [FABLE5_DESIGN_LANGUAGE.md] §2.5 成为事实源。
 * 本 object 是那张表的代码化——**八枚 token 与三条军规落值一字不改地搬**，改值须走「语言进化」过审。
 *
 * **硬规**：任何 margin/padding 必须取本表 token（4 的倍数）——出现 5/10/14 之类孤值即打回；
 * mockup 与实现同守。
 *
 * ## 视觉边缘（2026-09-06 甲′ 拍板增补·军规原文没说清的那一层）
 *
 * 军规只说「屏 gutter 恒 20dp」，没说 **20 量到哪**——同一个 padding 数字放不同组件会得到不同的视觉结果
 * （踩坑实例：`AppFormBar` 左右槽都写 4dp，左槽文字钮自带横 12dp 内边距故文字落在 16dp、右槽实心钮无补偿
 * 故钮边就在 4dp，肉眼可见地不齐）。故补三条原则 + 一张换算表：
 *
 * **原则三条**
 * 1. **视觉边缘 = 用户眼睛看到的那条边**，不是布局盒子的边。
 * 2. **有底色的**（实心钮 / 卡片 / 输入框 / 有填充的容器）→ 量**底的边**；
 *    **无底色的**（裸文字 / 文字钮 / 图标）→ 量**字形或图形的边**。
 * 3. **不算进视觉边缘的三样**：触达区外溢、投影 / 外发光、ripple 波纹。
 *
 * **换算表**（要让 gutter 视觉边缘 = 20dp，布局 padding 该给多少）
 *
 * | 放什么 | 内部补偿 | 布局 padding |
 * |---|---|---|
 * | 实心钮（[AppButton] Primary / Tonal / Warning） | 0 | **20dp** = [gutterForSolid] |
 * | 文字钮（[AppButton] Text 档·自带横 12dp） | +12 | **8dp** = [gutterForTextButton] |
 * | 白瓷圆钮（[AppTopBarAction]·触达 48 / 视觉 40） | +4 | **16dp** = [gutterForRoundButton] |
 * | 裸文字 / 裸图标 | 0 | **20dp** = [gutterForSolid] |
 * | 卡片（`appCardSurface`）/ 输入框（[AppTextField]） | 0 | **20dp** = [gutterForSolid] |
 * | 带外影的元素（釉烧钮 / 卡片软影） | 影不计入 | 同其底色形态 |
 *
 * **⚠️ 补偿失效的边界（2026-09-06 R1 复核补）**：`AppButton` 与 [AppTopBarAction] 都挂了
 * `minimumInteractiveComponentSize()`，它**不只扩触达、连布局宽高一起撑到 48dp 并把内容居中**
 * （库源 `InteractiveComponentSize.kt`：`width = maxOf(placeable.width, 48dp)`）。故上表两条钮的补偿
 * 其实是同一条公式：**补偿 = 自身横内边距 + max(0, 48dp − 测得宽) / 2**。
 * - 白瓷圆钮：0 + (48 − 40)/2 = **+4** ✅
 * - 文字钮：12 + (48 − 钮宽)/2 —— **钮宽 ≥ 48dp 时才等于 +12**。两字标签（「取消」「保存」）宽约 52dp，
 *   落在安全区（装机像素实测 16.3dp 对上 +12 模型）；**单字 / 纯图标的 Text 档钮宽不足 48dp，会被撑开居中，
 *   +12 不再成立**——这类钮请按公式现算，别直接套 [gutterForTextButton]。
 *
 * 贴屏幕边的元素**一律引这三枚具名常量**（或 [gutterPadding]），不写字面量。
 *
 * 主题无关（深浅不变），经 [AppTheme].spacing 取用。
 */
object AppSpacing {

    // ────────────────────────── 4dp 网格 token（V2 §2 军规原表·一字不改）──────────────────────────

    /** 4dp：正文段间。 */
    val xs: Dp = 4.dp
    /** 8dp：标题↔正文。 */
    val s: Dp = 8.dp
    /** 12dp：同组卡间。 */
    val m: Dp = 12.dp
    /** 16dp：卡片内边距 / 列表行水平起点。 */
    val l: Dp = 16.dp
    /** 20dp：屏幕水平 gutter / hero 卡内边距。 */
    val xl: Dp = 20.dp
    /** 24dp：组间 / section 标题上方。 */
    val xxl: Dp = 24.dp
    /** 32dp。 */
    val hero: Dp = 32.dp
    /** 48dp。 */
    val section: Dp = 48.dp

    // ────────────────────────── 军规落值（三条·V2 §2）──────────────────────────

    /**
     * 屏幕水平 gutter：**恒 20dp**。所有贴屏幕边元素的**视觉边缘**落在此线——
     * 布局 padding 按内部补偿换算，见 [gutterPadding] 与本类 KDoc 的换算表。
     */
    val screenGutter: Dp = xl

    /** 卡片内边距 16dp。 */
    val cardInset: Dp = l

    /** hero 卡内边距 20dp。 */
    val heroCardInset: Dp = xl

    /** 列表行水平起点 16dp。 */
    val rowInset: Dp = l

    /** 同组卡间 12dp。 */
    val cardGapInGroup: Dp = m
    /** 组间 24dp。 */
    val cardGapBetweenGroups: Dp = xxl
    /** section 标题上 24dp。 */
    val sectionTitleTop: Dp = xxl
    /** section 标题下 8dp。 */
    val sectionTitleBottom: Dp = s
    /** 标题↔正文 8dp。 */
    val titleToBody: Dp = s
    /** 正文段间 4dp。 */
    val bodyParagraph: Dp = xs

    // ────────────────────────── 视觉边缘换算（2026-09-06 甲′ 增补）──────────────────────────

    /**
     * 由「内部补偿」反算贴边元素该给的**布局 padding**，使其**视觉边缘**恰落在 [screenGutter] 线上。
     *
     * [compensation] = 该元素自身把内容往里推的量（文字钮的横内边距、圆钮触达区比视觉区的外溢…）。
     * 三种常见形态已备具名常量：[gutterForSolid] / [gutterForTextButton] / [gutterForRoundButton]。
     * 补偿本身的算法（含 48dp 触达撑开这条坑）见本类 KDoc 的「补偿失效的边界」。
     *
     * @throws IllegalArgumentException 当 [compensation] > [screenGutter]（算出负 padding 必崩在 `Modifier.padding`）。
     */
    fun gutterPadding(compensation: Dp = 0.dp): Dp {
        require(compensation <= screenGutter) {
            "内部补偿 $compensation 超过 gutter $screenGutter —— 该元素无法用非负 padding 把视觉边缘拉到 " +
                "gutter 线上（负 padding 会让 Modifier.padding 抛异常）。请改小组件自身的内边距，" +
                "或按设计语言 §2.5.3 为它单独定一条落值。"
        }
        return screenGutter - compensation
    }

    /** 无内部补偿者（实心钮 / 裸文字 / 裸图标 / 卡片 / 输入框）贴边 padding = **20dp**。 */
    val gutterForSolid: Dp = gutterPadding()

    /** 文字钮（[AppButton] Text 档·自带横 12dp 内边距）贴边 padding = **8dp**。 */
    val gutterForTextButton: Dp = gutterPadding(12.dp)

    /** 白瓷圆钮（[AppTopBarAction]·触达 48 / 视觉 40 → 每边溢 4dp）贴边 padding = **16dp**。 */
    val gutterForRoundButton: Dp = gutterPadding(4.dp)
}
