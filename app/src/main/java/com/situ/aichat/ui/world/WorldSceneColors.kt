package com.situ.aichat.ui.world

import androidx.compose.ui.graphics.Color

/**
 * 星球屏专属色单源（W9a 图纸 §4.2·仿梦剧场 [com.situ.aichat.ui.offline.OfflineTheater] 单源模式）——
 * **不进 AppTheme**（世界屏恒深·不随明暗主题）。全部字面量 = 对版 demo `design/world/planet-3d-demo.html`
 * 的 chrome 取值（图纸 §9 禁改）。
 */
object WorldSceneColors {

    /** 就绪前纯色背景 + GL 兜底底（demo:L3 页面底 #0D1220）。 */
    val background = Color(0xFF0D1220)

    /** 玻璃 chip 背景（rgba(20,26,44,0.42)·demo:L18）。 */
    val glassChip = Color(0x6B141A2C)

    /** 玻璃 chip 顶部内高光 1dp（rgba(245,239,234,0.14)·demo:L20 inset）。 */
    val glassHighlight = Color(0x24F5EFEA)

    /** chip 文字 / 标记标签 / 兜底文案（#F5EFEA·demo:L19,32）。 */
    val onGlass = Color(0xFFF5EFEA)

    /** 家乡金点 / 光晕 / 脉冲环（#E8C57E·demo:L27-30）。 */
    val gold = Color(0xFFE8C57E)

    /** 星空徽渐变上（#141C36·demo:L7·入口行徽复用）。 */
    val spaceIndigo = Color(0xFF141C36)

    /** 星空徽渐变下（#3A3050·demo:L6·入口行徽复用）。 */
    val spaceViolet = Color(0xFF3A3050)

    // ── W9b 大陆盒景新增（§4.6/§9·全 demo 字面量）──

    /** 站点卡暖纸面（rgba(250,247,242,0.92)·demo:L36）。 */
    val sheetSurface = Color(0xEBFAF7F2)

    /** 站点卡标题 / 切换器选中文字（#2E2925·demo:L37,24）。 */
    val sheetTitle = Color(0xFF2E2925)

    /** 站点卡正文（#6B6258·demo:L41）。 */
    val sheetBody = Color(0xFF6B6258)

    /** 站点卡 ✕（#9C938A·demo:L46）。 */
    val sheetClose = Color(0xFF9C938A)

    /** 奇观标记青（#A8C5BD·demo:L31）。 */
    val wonderTeal = Color(0xFFA8C5BD)

    /** 大区切换器当前项金色填充（rgba(232,197,126,0.85)·demo:L24）。 */
    val switcherActive = Color(0xD9E8C57E)

    // ── W9c 小镇盒景新增（§4.4·demo 字面量）──

    /** 地点标签暖纸签底（rgba(250,247,242,0.88)·town demo:L21·与大陆「点+签」不同族）。 */
    val labelPaper = Color(0xE0FAF7F2)

    // ── W9d 室内 + 立绘卡片新增（§4.6/§4.7·demo 字面量）──

    /** 纸片卡纸面渐变上（#FDFAF5·interior demo:L27）。 */
    val pcardPaperTop = Color(0xFFFDFAF5)

    /** 纸片卡纸面渐变下（#F3EBDF·interior demo:L27）。 */
    val pcardPaperBottom = Color(0xFFF3EBDF)

    /** 纸片卡描边 / 小镇头像卡描边（rgba(255,255,255,.92) / rgba(250,247,242,.95)·interior demo:L28 / town demo）。 */
    val cardStroke = Color(0xEBFAF7F2)

    /** 纸片卡状态行文字（#EAD9BE·interior demo:L37）。 */
    val pcardStatus = Color(0xFFEAD9BE)

    /** 迎接光晕 halo（rgba(255,213,150,.5)·interior demo:L42）。 */
    val halo = Color(0xFFFFD596)

    /** 热气圆点（rgba(250,247,242,.5)·interior demo:L47）。 */
    val steam = Color(0x80FAF7F2)

    /** 神秘人卡底 + 虚线描边（#4A4E5E·town demo）。 */
    val mystery = Color(0xFF4A4E5E)

    /** 宠物卡圆底（#C9A06B·town demo）。 */
    val petBadge = Color(0xFFC9A06B)

    /** 幽灵胶囊描边（#BE8A76·§4.7）。 */
    val ghostStroke = Color(0xFFBE8A76)

    /** 幽灵胶囊文字（#8A5A48·§4.7）。 */
    val ghostText = Color(0xFF8A5A48)

    // ── W10 关系星图新增（星图场景 / 线型 / tag 四组 / 列表·全 demo 字面量·图纸 §4.1 禁改）──

    /** 降温虚线 stroke（#8FA3C8·demo:L238）。 */
    val smCoolLine = Color(0xFF8FA3C8)

    /** 结标记描边（#D98B6F·demo:L244）。 */
    val smKnot = Color(0xFFD98B6F)

    /** 待相识虚圈描边（#9AA1B5·demo:L279）。 */
    val smPendingStroke = Color(0xFF9AA1B5)

    /** 刻度环（rgba(245,239,234,.06)·demo:L219）。 */
    val smRing = Color(0x0FF5EFEA)

    /** 外圈虚环（rgba(154,161,181,.16)·demo:L220）。 */
    val smRim = Color(0x299AA1B5)

    /** 你→星丝线（rgba(245,239,234,.09)·demo:L226）。 */
    val smThread = Color(0x17F5EFEA)

    /** tag 基础三件（.tag·demo:L55-56）：底 / 文字 / 描边。 */
    val smTagBaseBg = Color(0x29C99A86)
    val smTagBaseText = Color(0xFF8A6A58)
    val smTagBaseBorder = Color(0x59C99A86)

    /** tag 升温三件（.tag.traj-warming·demo:L57）。 */
    val smTagWarmBg = Color(0x2ED9B36E)
    val smTagWarmText = Color(0xFF8A6E36)
    val smTagWarmBorder = Color(0x73D9B36E)

    /** tag 降温三件（.tag.traj-cooling·demo:L58）。 */
    val smTagCoolBg = Color(0x247A89B8)
    val smTagCoolText = Color(0xFF5A6890)
    val smTagCoolBorder = Color(0x667A89B8)

    /** tag 张力三件（.tag.tense·demo:L59）。 */
    val smTagTenseBg = Color(0x26D98B6F)
    val smTagTenseText = Color(0xFFA05F44)
    val smTagTenseBorder = Color(0x66D98B6F)

    /** 近事盒底（rgba(201,154,134,.10)·demo:L65）。 */
    val smRecentBg = Color(0x1AC99A86)

    /** 列表模式底（rgba(11,15,32,.94)·demo:L84）。 */
    val smListOverlay = Color(0xF00B0F20)

    /** 「你」核渐变上 / 下（#gGold·demo:L214）。 */
    val smGoldTop = Color(0xFFF2D9A0)
    val smGoldBottom = Color(0xFFD9A96B)

    // ── 收编区：原散落各文件的 file-private 色常量归拢于此（值逐项不变；同值只留一份字面量，其余为别名）──
    // 约定：新增世界屏颜色一律先进本表再使用；消费方经成员导入引用（import …WorldSceneColors.Xxx）。

    /** 主行动胶囊渐变（135°·continent demo .act·L47）。原 WorldSiteSheet / StarmapSheets 各持副本。 */
    val ActStart = Color(0xFFC99A86)
    val ActEnd = Color(0xFFBE8A76)

    // ── W12 快聊弹窗（原 WorldQuickChatSheet.kt·quickchat demo §4.2–4.5 精确值）──

    val QcHeadBg = Color(0xA9121828) // rgba(18,24,40,.66)
    val QcHandle = Color(0x47F5EFEA) // rgba(245,239,234,.28)
    val QcStatus = Color(0xC7F5EFEA) // rgba(245,239,234,.78)
    val QcClose = Color(0xA6F5EFEA) // rgba(245,239,234,.65)
    val QcBusyStart = Color(0x33D69E5A) // rgba(214,158,90,.20)
    val QcBusyEnd = Color(0x14D69E5A) // rgba(214,158,90,.08)
    val QcBusyText = Color(0xFF9A6B33)
    val QcBody = Color(0xF5FAF7F2) // rgba(250,247,242,.96)
    val QcPill = Color(0x0E2E2925) // rgba(46,41,37,.055)
    val QcUserStart = ActStart
    val QcUserEnd = ActEnd
    val QcTypingDot = Color(0xFFB4A798)
    val QcRetryBg = Color(0x122E2925) // rgba(46,41,37,.07)
    val QcInputBorder = Color(0x1F2E2925) // rgba(46,41,37,.12)
    val QcInputBg = Color(0xFFFFFDF9)
    val QcPlaceholder = Color(0xFFB4AA9E)
    val QcInputFocus = Color(0x8CBE8A76) // rgba(190,138,118,.55)
    val QcSendIcon = Color(0xFFFFF8F2)
    val QcHairline = Color(0x122E2925)

    // ── W12.5 蛋巢之约（原 EggNestSheet.kt·与快聊同值处已归并）──

    val NsHeadBg = QcHeadBg
    val NsHandle = QcHandle
    val NsTitle = onGlass
    val NsSub = QcStatus
    val NsClose = QcClose
    val NsBody = QcBody
    val NsInk = sheetTitle
    val NsStateText = Color(0xFF8A8378)
    val NsBarTrack = Color(0x142E2925)
    val NsBarStart = Color(0xFFD9B36E)
    val NsBarEnd = gold
    val NsChevron = Color(0xFFC9BFB4)
    val NsFoot = sheetClose
    val NsBodyText = sheetBody
    val NsClayStart = ActStart
    val NsClayEnd = ActEnd
    val NsBtnHighlight = Color(0x47FFFFFF)
    val NsBackBg = Color(0x142E2925)

    // ── 偷听 overlay（原 WorldEavesdropOverlay.kt·quickchat demo:L27-29/L150-176 精确值）──

    val EavesChipGlass = Color(0x75101422) // rgba(16,20,34,.46)
    val EavesWhisperGlass = Color(0x94101422) // rgba(16,20,34,.58)
    val EavesBubbleShadow = Color(0x660A0E1A) // rgba(10,14,26,.4)
    val EavesDot = pcardStatus // #EAD9BE 同字面量复用

    // ── 动态页英雄卡恒暗窗景族（原 WorldHeroCard.kt·W11 demo 逐值）──

    val CardGradTop = Color(0xFF0B0F1B) // demo:L23 底渐变 0%
    val CardGradMid = Color(0xFF141C36) // demo:L23 55%（同 spaceIndigo 字面量）
    val CardGradBottom = Color(0xFF232447) // demo:L23 100%
    val CardVioletColor = spaceViolet // demo:L27 紫雾
    val HaloColorCore = Color(0x2496B4E6) // demo:L32 rgba(150,180,230,.14)
    val HaloColorMid = Color(0x0D96B4E6) // demo:L32 .05
    val MilkyC1 = Color(0x1FC8BEE6) // demo:L29 rgba(200,190,230,.12)
    val MilkyC2 = Color(0x2EF0E1F0) // demo:L29 rgba(240,225,240,.18)
    val MilkyC3 = Color(0x1AC8BEE6) // demo:L29 rgba(200,190,230,.10)
    val CardText = onGlass // demo:L37 标题/星点色
    val CardShadow = Color(0xCC0B0F1B) // demo:L37 rgba(11,15,27,.8)
    val GoldGlow = gold.copy(alpha = 0.55f) // demo:L45 rgba(232,197,126,.55)
}
