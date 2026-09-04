package com.situ.aichat.ui.designsystem

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.situ.aichat.R

/**
 * Fable-5 中文排版 token：字阶 **11/13/14/16/18/22/28** × 思源黑体可变字重 **420/520/640**（见
 * [FABLE5_DESIGN_LANGUAGE.md] §2）。中文层级靠字号/字重/透明度/楷体点缀四杠杆正交，禁斜体禁下划线。
 *
 * **字体挂账**：思源黑体 CN 可变字体（Plan B·OFL）尚未入 `res/font`（[fontFamily] 暂回退系统默认）。
 * 字体落地后把 [fontFamily] 换成 `FontFamily(Font(R.font.source_han_sans_vf, variationSettings=…))`，
 * VF 420/520/640 即激活（低版本/无字体静默回退最近静态实例）。结构（字号/行高/letterSpacing/tnum/
 * includeFontPadding=false）先就位，全局 M3 Typography 槽位覆盖延后到字体落地 + 截图验证。
 */
object AppTypography {

    /** 字体族（待 res/font 落地后替换为思源黑体 VF）。 */
    val fontFamily: FontFamily = FontFamily.Default

    /**
     * 楷体点缀族（祝福/引文/旁白·设计语言 §2）= `res/font/apc_kai.ttf`（日记重设计 R1 落地·2026-07-02）：
     * 霞鹜文楷 Lite v1.522 Regular 子集（GB2312+增补 7698 字·3.4MB），OFL 1.1 合规**已改名 "APC Kai"**
     * 避开保留名（霞鹜/LXGW·许可证随包 `assets/licenses/apc_kai_OFL.txt`）。子集外生僻字由系统
     * 逐字回退（Compose 原生行为），不碎不裂。
     */
    val kaiFontFamily: FontFamily = FontFamily(Font(R.font.apc_kai))

    private val W420 = FontWeight(420) // 正文微加重
    private val W520 = FontWeight(520) // 强调
    private val W640 = FontWeight(640) // 标题

    // 中文 ascent/descent 大·钉死不依赖默认漂移
    private val platform = PlatformTextStyle(includeFontPadding = false)
    private val lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    private val base = TextStyle(
        fontFamily = fontFamily,
        fontWeight = W420,
        letterSpacing = 0.sp,
        platformStyle = platform,
        lineHeightStyle = lineHeightStyle,
    )

    /** 数字密集（金币/红包/计时/时间戳）防跳动错位。 */
    private val tnum = "tnum"

    /** 聊天正文 / 语音转写：16sp / 行高 24(1.5) / 420。 */
    val body: TextStyle = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = W420)

    /** 正文强调：16sp / 520。 */
    val bodyEmphasis: TextStyle = base.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = W520)

    /** 顶栏角色名：16sp / 行高 20 / 520（顶栏视觉锚）。 */
    val nameTopBar: TextStyle = base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = W520)

    /** 副标题 / 引用块 / 次级提示：13sp / 行高 16 / 420。 */
    val secondary: TextStyle = base.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = W420)

    /** 时间戳 / 标签 / 徽章：11sp / 420。 */
    val caption: TextStyle = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = W420)

    /** 时间戳数字：11sp / 420 / tnum。 */
    val captionNumeric: TextStyle = caption.copy(fontFeatureSettings = tnum)

    /** 卡片名 / 状态文案 / 详情标题：14sp / 行高 18 / 520。 */
    val label: TextStyle = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = W520)

    /** 聊天列表行·角色名（主标题）：16sp / 行高 20 / 520（脱 M3 titleSmall·两行制过审 2026-06-20）。 */
    val listName: TextStyle = base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = W520)

    /** 聊天列表行·消息预览（次行）：14sp / 行高 18 / 420（脱 M3 bodyMedium·两行制过审 2026-06-20）。 */
    val listPreview: TextStyle = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = W420)

    /**
     * 弹窗正文（确认弹窗多行说明·M3 清零总契约 §1）：14sp / 行高 22(1.57) / 420。
     * 与 [listPreview]（14sp/18）同字号不同行高——列表预览是单行排版，弹窗多行正文需更多呼吸感。
     */
    val dialogBody: TextStyle = base.copy(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = W420)

    /**
     * 设置行·题（[AppSettingsRow] 专用·六件套草图 §2.5 取值）：13sp / 行高 18 / **520**。
     * 与 [secondary]（同 13sp 但 420）分列两枚：设置行的题是**可点行的主标签**，需要比副标题重一档。
     * **字重归梯**：草图 CSS 写 500，设计语言 §2 字重梯只有 420/520/640 → 就近取 520（R1 🟡-2 登记为 D-17）。
     */
    val settingsRowTitle: TextStyle = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = W520)

    /** 设置行·副（[AppSettingsRow] 专用）：10.5sp / 行高 15.75（= 1.5×·对版稿 line-height:1.5）/ 420。 */
    val settingsRowSubtitle: TextStyle = base.copy(fontSize = 10.5.sp, lineHeight = 15.75.sp, fontWeight = W420)

    /** 设置行·尾值（[AppSettingsRow] 专用·「暖陶 · 跟随系统」这类右侧现值）：11.5sp / 行高 15 / 420。 */
    val settingsRowValue: TextStyle = base.copy(fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = W420)

    /**
     * 纸条正文（[AppSnackbarHost] 专用·六件套草图 §2.6 逐字取值）：12.5sp / 行高 17 / 420。
     *
     * **字重已全部归梯**（R1 复核 🟡-2·2026-09-04）：草图的 500 / 700 分别落到设计语言 §2 字重梯的
     * [W520] / [W640]——梯子（420/520/640）零改动。归梯零观感成本：字体族目前仍是 `FontFamily.Default`，
     * 640 与 700 落到同一个静态实例，肉眼无差；可变字体落地后 640 也仍是「标题档」的正确语义。
     *
     * **字号三枚已纳入字阶梯（2026-09-05 用户拍板⑦「甲案」）**：本枚的 12.5 与设置行的 10.5 / 11.5 不再是
     * 「越梯」——`FABLE5_DESIGN_LANGUAGE.md` §2 的梯子已扩为 **10.5/11/11.5/12.5/13/14/16/18/22/28**，
     * 落值一字不改（三处观感 2026-07-17 已过审，改值等于推翻已过审长相）。本文件与设计语言自此不再矛盾。
     */
    val snackbarBody: TextStyle = base.copy(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = W420)

    /** 纸条动作词（「撤销」这类）：12sp / **640**（草图 700 已归梯）/ 行高 16——见 [snackbarBody]。 */
    val snackbarAction: TextStyle = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = W640)

    /** 金额强调：14sp / 640 / tnum。 */
    val amount: TextStyle = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = W640, fontFeatureSettings = tnum)

    /** 楷体祝福/引文（红包纪念卡祝福·日记引文等点缀·楷体族·设计语言 §2）：14sp / 行高 20 / 420。 */
    val kaiQuote: TextStyle = base.copy(fontFamily = kaiFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = W420)

    /** 楷体正文（AI 代写日记全文 / 交换日记 TA 的日记·日记重设计 R1）：16sp / 行高 28（楷体笔画密，行距比 body 松半档）/ 420。 */
    val kaiBody: TextStyle = base.copy(fontFamily = kaiFontFamily, fontSize = 16.sp, lineHeight = 28.sp, fontWeight = W420)

    /** 空对话角色名 / 卡片标题：18sp / 640。 */
    val titleSmall: TextStyle = base.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = W640)

    /** 22sp / 640。 */
    val titleMedium: TextStyle = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = W640)

    /** 28sp / 640。 */
    val titleLarge: TextStyle = base.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = W640)
}
