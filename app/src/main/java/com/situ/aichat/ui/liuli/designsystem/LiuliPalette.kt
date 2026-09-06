package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.ui.graphics.Color

/**
 * 琉璃专属字面量的**唯一出口**（图纸 2026-09-05 卷二C A-10 · §9 ⑤「禁裸 `Color(0x…)`」）。
 *
 * 为什么这些色不进 [com.situ.aichat.ui.designsystem.AppColors]：它们是琉璃这张脸自己的长相
 * ——红包哑光红 / 恒暗舞台卡 / 图片戳压底 —— 暖陶那张脸没有对应槽位（暖陶红包是陶红
 * `economy.redPacketStart/End`，两者**不可互换**）。`ui/designsystem/Palette.kt` 零碰。
 *
 * 深浅两档：本表全部**双档同值**——红包 / 恒暗卡 / 图片压底本身就是「自带底色的面」，
 * 不随主题翻浅（同暖陶红包卡与见面剧场的既有判例）。
 */
internal object LiuliPalette {

    // ── AI 泡时间戳（卷二A 的唯二字面量·A-10 搬入·契约 §4.2 `bubble.aiTime`） ────────────
    val aiStampLight = Color(0xFF8A8F9A)
    val aiStampDark = Color(0xFF7C8390)

    // ── 用户泡渐变窗口的末 stop（C7·用户 2026-09-05 选「乙 · 钴 → 紫」） ────────────────
    /**
     * 渐变窗口底端色。原值走 `Palette.Cobalt26GradEnd`（`#1557CC`·同色相略暗，白字对比 4.6 → 6.4），
     * 换成偏紫的 `#3B3FC6` 后底端 7.7、区间内逐 0.01 仍 ≥ 4.5（`LiuliBubbleGradientTest` 钉）。
     * `Palette.Cobalt26GradEnd` **不动**——它还喂着 `accent.gradientEnd`（钮）与暖陶 `bubble.userEnd`，
     * 与本项无关；换色只发生在琉璃用户泡这一处。
     */
    val bubbleGradientEnd = Color(0xFF3B3FC6)

    // ── 红包卡（对版稿 `.card.red`·哑光红 160°·**非**暖陶陶红） ───────────────────────
    val packetRedTop = Color(0xFFC8443A)
    val packetRedBottom = Color(0xFFA93A31)

    /** 哑光红上的暖白字（对版稿 `color:#FFF3E6`）。 */
    val packetText = Color(0xFFFFF3E6)

    /** 红包卡上的金（图标 / 状态胶囊字·对版稿 `#F3D48B`）。 */
    val packetGold = Color(0xFFF3D48B)

    /** 烫金封印上的「福」字（对版稿 `color:#5A3A08`）。 */
    val sealInk = Color(0xFF5A3A08)

    // ── 线下卡恒暗舞台（对版稿 `.card.dark`·与见面剧场同源口径） ─────────────────────
    val stageInk = Color(0xFF111418)
    val stageText = Color(0xFFF2F4F8)

    /** 卡顶钴蓝微光的**基色**（用处见 `LiuliOfflineCards`：径向 60%×40% at (70%, 0)·0.22 透明度在用点施加）。 */
    val stageGlow = Color(0xFF6FA8FF)

    /** 恒暗卡图标块上的图标色（对版稿 `.card.dark .hd i svg{stroke:#9FC2FF}`）。 */
    val stageIcon = Color(0xFF9FC2FF)

    // ── 图片泡右下时间戳 ────────────────────────────────────────────────────────
    /**
     * 图片戳的压底（A-6）：**不走** [com.situ.aichat.ui.liuli.glass.liuliGlass]——内容层拿不到
     * `LocalBackdrop` 会退成浅色染色，压在照片上白字读不出来；故走对版稿的 `rgba(0,0,0,.35)` 实底。
     */
    val imageStampScrim = Color(0x59000000)

    // ── 二级屏图标砖十色（契约 §6.5「图标砖色板」·一组一色·白图标 16·**夜档同色**） ───────────
    /**
     * 为什么是字面量：这十色是**分类标识**（一组一色，像 iOS 设置里的彩砖），不是语义色——
     * `AppColors` 里没有「第 N 类」这种槽位，暖陶那张脸也不用彩砖。白 16 图标压在这十色上的对比
     * 由 `ColorContrastTest` 钉（≥ 4.5:1）。红 `#C8443A` 只给危险行、**不做砖**（契约 §6.5）。
     */
    val tilePersonalize = Color(0xFF2570E8)   // 个性化 · 钴蓝
    val tileApi = Color(0xFF3B3FC6)           // API 与模型 · 靛
    val tileChat = Color(0xFF1F8A7A)          // 聊天行为 · 青
    val tileMemory = Color(0xFF2F7A4F)        // 记忆与设定 · 绿
    val tileVoice = Color(0xFFB7791F)         // 语音 · 琥珀
    val tileCreation = Color(0xFFD2691E)      // AI 自动创作 · 橙
    val tileStory = Color(0xFFC0397B)         // 故事 · 玫
    val tileWorld = Color(0xFF3A8DDE)         // 世界 · 天蓝
    val tileSystem = Color(0xFF4B5563)        // 系统与通知 / 功能开关 · 石墨
    val tileData = Color(0xFF6B7280)          // 数据与诊断 / 关于 · 灰

    /**
     * 详情页头图底部遮罩的墨（契约 §6.5「底 130 遮罩 ink@0→55%」）：与玻璃上主文字同一枚墨
     * （`#111318`），但语义是**压在照片上的幕**、昼夜同值——照片本身不随主题翻浅。
     */
    val heroScrimInk = Color(0xFF111318)

    /** 十砖色全表（`ColorContrastTest` 逐色核白图标对比用）。 */
    val tileColors: List<Color> = listOf(
        tilePersonalize, tileApi, tileChat, tileMemory, tileVoice,
        tileCreation, tileStory, tileWorld, tileSystem, tileData,
    )
}
