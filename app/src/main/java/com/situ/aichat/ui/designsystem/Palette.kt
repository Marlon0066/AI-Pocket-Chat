package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Fable-5 自研设计语言 · **第一层：primitive 色阶**（无语义·烘焙好的 sRGB 常量）。
 *
 * 三层 token 结构（见 [FABLE5_DESIGN_LANGUAGE.md] §1.1）：
 *   primitive（本文件，无语义）→ semantic/feature（[AppColors]，主题切换枢纽）→ 组件只引 [AppTheme].colors。
 *
 * 工序：OKLCH 锁 L/C 只转 H 生成色阶与功能色家族 → 烘焙成 sRGB hex（无运行时成本）。
 * **组件禁止直引本对象**——只能经 semantic/feature 层（[AppColors]）。深浅/AMOLED 三主题 = 三份
 * semantic→primitive 映射，primitive 与 feature 引用关系不动。
 */
internal object Palette {

    // ── 暖中性基底（同一暖 hue ≈ 75–85°·「同一房间的白天与夜晚」） ──
    val Porcelain = Color(0xFFFAF7F2)   // 浅 surface 底
    val White = Color(0xFFFFFFFF)       // 浅 surface raised
    val Linen = Color(0xFFF1ECE4)       // 浅 surface sunken / 1px 明度分隔
    val LinenDeep = Color(0xFFE3DAD0)   // 浅 outlineVariant（更浅分隔）
    val Ink = Color(0xFF2E2925)         // 浅 text primary
    val InkSoft = Color(0xFF6B6258)     // 浅 text secondary
    val InkFaint = Color(0xFF9C938A)    // 浅 text tertiary / outline

    val Espresso = Color(0xFF14110E)    // 深 surface 底（深暖灰默认）
    val Coffee = Color(0xFF1C1916)      // 深 surface raised
    val Bark = Color(0xFF242019)        // 深 surface sunken / 抬升卡
    val BarkLine = Color(0xFF2C2822)    // 深 1px 暖灰描边（替投影）
    val Cream = Color(0xFFEDE8E2)       // 深 text primary
    val Sand = Color(0xFFB5ACA1)        // 深 text secondary
    val Taupe = Color(0xFF7E766C)       // 深 text tertiary / outline

    // ── 主强调：陶土玫（locked #BE8A76·暖柔手作陶器质感） ──
    val Clay = Color(0xFFBE8A76)        // accent 填充/大元素（装饰档·中间调·非文字）
    val ClayLight = Color(0xFFC99A86)   // 浅档气泡渐变起点（135° 左上·配深墨字）
    val ClayDark = Color(0xFFA8765F)    // 深档 accent 描边/图标（对比死区·不作白/深文字底）
    val ClayDarkStart = Color(0xFFB5826B) // 备用深陶（中间调·闲置）
    val ClayDeep = Color(0xFF9A5B3E)    // 陶土功能深档：浅底上的陶土字/小图标（对暖白 ≥4.5:1）；兼深档气泡渐变起点（对 #F5EFEA 4.67:1）
    val ClayEmber = Color(0xFF8A4E33)   // 深档气泡渐变终点（135°·对 #F5EFEA 5.72:1·深陶+白字拍板 2026-06-13）
    val OnClayInk = Color(0xFF2E2925)   // 浅档陶土填充上的深墨字（微信式·WCAG 决议 2026-06-13·= Ink）
    val ClayWhisper = Color(0xFFF0DDD3) // 浅 primaryContainer（极浅陶土）
    val ClayWhisperDark = Color(0xFF3A2A22) // 深 primaryContainer
    val ClayInk = Color(0xFF5A3A2A)     // 浅 onPrimaryContainer
    val ClayCream = Color(0xFFE8CDBF)   // 深 onPrimaryContainer
    val OnClay = Color(0xFFFFFFFF)      // 浅 气泡内白字
    val OnClayDark = Color(0xFFF5EFEA)  // 深档深陶填充上的暖白字（降纯白光晕）

    // ── 白瓷药丸面（分段控件 / 选择标签选中态·「白瓷药丸」对版稿烘焙值 2026-09-03）──
    // 浅档 = 纯白起、底 stop 通道值各降 ~1.6%（#FBF5F1）带一丝陶土暖意——暖白非死白，接住顶光的釉面。
    // 深档 = surface.sunken 提亮到相对亮度 ≈2.94×（暖灰抬升一阶·**非陶土色底**·D-4 拍板）。
    // 注：倍数只描述本组暖档；青花档另有一组（见下方 GlazeBlue*），其比值 ≈2.58×，不套用此数。
    val Glaze = Color(0xFFFFFFFF)          // 浅 药丸面顶
    val GlazeShade = Color(0xFFFBF5F1)     // 浅 药丸面底
    val GlazeDark = Color(0xFF403A32)      // 深 药丸面顶
    val GlazeDarkShade = Color(0xFF352F29) // 深 药丸面底

    // ── 经济金：贵金属族（与 status.warning 物理隔离） ──
    val Gold = Color(0xFF8C6D1F)        // 浅 金文字/图标（对暖白 ≥4.5:1）
    val GoldDark = Color(0xFFD4B96A)    // 深 金文字 / 深档大元素单色
    val GoldGradStart = Color(0xFFE3C77B) // 大元素金属渐变起点（浅）
    val GoldGradEnd = Color(0xFFA8842F)   // 大元素金属渐变终点（浅）

    // ── 红包：哑光暗红 + 烫金（去赌场化） ──
    val RedPacketStart = Color(0xFFA8323D)
    val RedPacketEnd = Color(0xFF8A2230)
    val RedPacketSeal = Color(0xFF8A2230)   // 福字
    val SealGoldStart = Color(0xFFF0DCA8)   // 福徽径向金起点
    val SealGoldEnd = Color(0xFFC9A458)     // 福徽径向金终点

    // ── 红包纪念卡（拆开态·去赌场化纪念卡范式·浅奶油纸 / 深暖卡） ──
    val KeepsakeCardStart = Color(0xFFFAF0DC)       // 浅 纸卡渐变起点
    val KeepsakeCardEnd = Color(0xFFEFDFC1)         // 浅 纸卡渐变终点
    val KeepsakeCardDarkStart = Color(0xFF2C261E)   // 深 暖卡渐变起点
    val KeepsakeCardDarkEnd = Color(0xFF221C15)     // 深 暖卡渐变终点
    val OnKeepsake = Color(0xFF6B3F0F)              // 浅 卡上楷体祝福/单位/日期（对纸卡 ≥4.5）
    val OnKeepsakeDark = Color(0xFFEDE8E2)          // 深 卡上暖白字（楷体祝福·与金额金色分层）
    val KeepsakeAmountStart = Color(0xFF9A7728)     // 浅 金额金属渐变起（深档金·可读 on 纸卡）
    val KeepsakeAmountEnd = Color(0xFF7A5C16)       // 浅 金额金属渐变终
    val KeepsakeAmountDarkStart = Color(0xFFF0D98C) // 深 金额金属渐变起（亮金 on 深卡）
    val KeepsakeAmountDarkEnd = Color(0xFFC99A3A)   // 深 金额金属渐变终
    val SealStamp = Color(0xFF9A2630)               // 浅 节日红印底
    val SealStampDark = Color(0xFFA83038)           // 深 节日红印底（深红·反白字 ≥4.5·留余量）
    val OnSealStamp = Color(0xFFF3E3C9)             // 红印上反白字（两档同·端午等节日名）
    val OpenedBackdropStart = Color(0xFFECC98D)     // 浅 开盒暖金背景径向中心
    val OpenedBackdropEnd = Color(0xFF8A5A2A)       // 浅 开盒暖金背景径向边缘
    val OpenedBackdropDarkStart = Color(0xFF5C4427) // 深 开盒背景径向中心（暖金微光）
    val OpenedBackdropDarkEnd = Color(0xFF160F08)   // 深 开盒背景径向边缘

    // ── 宠物状态莫兰迪四色（饱食/清洁/心情/健康·替 iOS systemColor 四环·装饰填充·心情环+状态条·非文字底） ──
    val PetSatiety = Color(0xFFD9B27A)      // 浅 饱食=暖金
    val PetClean = Color(0xFFA7B89A)        // 浅 清洁=灰绿
    val PetMood = Color(0xFFD2A0A0)         // 浅 心情=暖玫
    val PetHealth = Color(0xFFB3A57E)       // 浅 健康=橄榄陶
    val PetSatietyDark = Color(0xFFC2A06E)  // 深 降饱和 20–30%
    val PetCleanDark = Color(0xFF94A289)
    val PetMoodDark = Color(0xFFBC8F8F)
    val PetHealthDark = Color(0xFFA09372)

    // ── status 家族（低饱和·瞬态非常驻·warning ≠ economy.gold） ──
    val WarnContainer = Color(0xFFF3E7D2)
    val OnWarn = Color(0xFF7A5A12)
    val WarnContainerDark = Color(0xFF3A2E1A)
    val OnWarnDark = Color(0xFFE0C68A)
    val WarnSolidDark = Color(0xFF8A6516)   // 深档实底警示按钮填充（更挺括·替不可读的浅琥珀底·对暖白 #F5EFEA 4.66:1）
    val SuccessContainer = Color(0xFFE2EBE2)
    val OnSuccess = Color(0xFF3A5A40)
    val SuccessContainerDark = Color(0xFF1E2A22)
    val OnSuccessDark = Color(0xFF9FC4A8)
    val ErrorContainer = Color(0xFFFCEBEB)
    val OnError = Color(0xFFA32D2D)
    val ErrorContainerDark = Color(0xFF2E1818)
    val OnErrorDark = Color(0xFFE89B9B)
    val InfoContainer = Color(0xFFE4ECF2)
    val OnInfo = Color(0xFF3A5266)
    val InfoContainerDark = Color(0xFF1E2630)
    val OnInfoDark = Color(0xFFA8B9C8)

    // ── 12 情绪色：莫兰迪低饱和（C≈0.05–0.08·只承载氛围·辨识靠 emoji+文案冗余） ──
    // 本期 Phase 0 先落 5 个 valence-arousal 原型（装饰浅档）；完整 12 色 OKLCH 派生在组件 ④（分段递送+情绪入场）细化。
    val EmotionJoy = Color(0xFFD9C9A8)    // 喜悦/兴奋=暖金
    val EmotionCalm = Color(0xFFAEB8A6)   // 平静/思考=灰绿
    val EmotionSad = Color(0xFFA8B4BE)    // 悲伤/叹气=雾蓝
    val EmotionShy = Color(0xFFD6B3AC)    // 害羞/爱=暖玫
    val EmotionAnger = Color(0xFFC4A89E)  // 怒/惊/恐（负向）=同 hue 降 chroma 暖灰玫
    val EmotionJoyDark = Color(0xFF8A7E63)
    val EmotionCalmDark = Color(0xFF6E7768)
    val EmotionSadDark = Color(0xFF6B7681)
    val EmotionShyDark = Color(0xFF8A6F69)
    val EmotionAngerDark = Color(0xFF7C6A62)

    // 莫兰迪「功能深档」：圈子枢纽分色 IconTile 图标（同族深色·on 浅 tile ≥3:1·设计语言 §1.4 双档之深档）。
    // 深色档（深 tile）上需亮档图标 → 深档直接复用上面的 EmotionCalm/Sad/Shy（见 DarkAppColors.emotion）。
    val CalmInk = Color(0xFF52614E)   // 灰绿功能深档（日记图标·浅档）
    val SadInk = Color(0xFF4E5C68)    // 雾蓝功能深档（故事图标·浅档）
    val ShyInk = Color(0xFF7E5258)    // 暖玫功能深档（宠物图标·浅档）

    // ── 琉璃主题（第二张脸配色·见 FABLE5_THEME_LIULI_PROPOSAL.md §4.2/§4.3）：冷灰瓷白 / 近黑 + 钴蓝。 ──
    // 昼（琉璃·瓷白）
    val GlassMist = Color(0xFFF6F7FB)            // 昼 surface 底（≥4.54 托住 economy.gold·作者修订值）
    val GlassSunken = Color(0xFFE8EAF0)          // 昼 surface sunken（搜索槽 / 凹陷）
    val GlassStroke = Color(0xFFDDE0E8)          // 昼 surface stroke（≈ 墨 10% over 底·实色）
    val GlassBubbleStroke = Color(0xFFE9ECF3)    // 昼 AI 气泡发丝（≈ 墨 6% over 白）
    val InkCool = Color(0xFF111318)              // 昼 text primary
    val InkCoolSoft = Color(0xFF5F6470)          // 昼 text secondary（5.9 on 白）
    val InkCoolFaint = Color(0xFF9A9FAB)         // 昼 text tertiary（纯装饰）
    val Cobalt26 = Color(0xFF2570E8)             // 昼 accent 装饰 / 大元素（白字 4.6）
    val Cobalt26Text = Color(0xFF0A5FCB)         // 昼 钴蓝文字 on 白 6.0 / on sunken 5.0（作者修订值）
    val Cobalt26Container = Color(0xFFE3EEFD)    // 昼 选中浅染
    val Cobalt26OnContainer = Color(0xFF0B4FB0)  // 昼 container 上字（7.0）
    val Cobalt26GradStart = Color(0xFF2570E8)    // 渐变起点（两档同·白字 4.6）
    val Cobalt26GradEnd = Color(0xFF1557CC)      // 渐变终点（两档同·白字 6.4）
    val Cobalt26DeepEnd = Color(0xFF0F44A3)      // 恒深档终点（两档同）
    val GlazeGlass = Color(0xFFFFFFFF)           // 昼 药丸面顶（D-15 甲：白瓷药丸仍在未迁屏出现）
    val GlazeGlassShade = Color(0xFFF4F6FA)      // 昼 药丸面底
    // 夜（琉璃·夜·近黑非纯黑 D-9）
    val NightGlass = Color(0xFF0B0D12)           // 夜 surface 底
    val NightGlassRaised = Color(0xFF16191F)     // 夜 surface raised / 纸面
    val NightGlassSunken = Color(0xFF1F232B)     // 夜 surface sunken
    val NightGlassStroke = Color(0xFF2A2F39)     // 夜 stroke / AI 气泡发丝
    val NightGlassBubble = Color(0xFF1C2028)     // 夜 AI 气泡（深石板）
    val MoonWhite = Color(0xFFF2F4F8)            // 夜 text primary
    val MoonWhiteSoft = Color(0xFFA3A9B5)        // 夜 text secondary（7.5 on raised）
    val MoonWhiteFaint = Color(0xFF6C7280)       // 夜 text tertiary（纯装饰）
    val Cobalt26Bright = Color(0xFF3B86FF)       // 夜 accent 装饰 / tint（配 NightGlass 墨字 5.6·白字仅 3.5 禁）
    val Cobalt26TextDark = Color(0xFF6FA8FF)     // 夜 钴蓝文字 on 近黑 8.1
    val Cobalt26ContainerDark = Color(0xFF17304F) // 夜 选中浅染
    val Cobalt26OnContainerDark = Color(0xFFB9D3FF) // 夜 container 上字（8.8）
    val GlazeGlassDark = Color(0xFF343B48)       // 夜 药丸面顶（冷灰抬升一阶）
    val GlazeGlassDarkShade = Color(0xFF2A303C)  // 夜 药丸面底

    // ── 通用 ──
    val Scrim = Color(0xFF000000)
}
