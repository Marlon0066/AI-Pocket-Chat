package com.situ.aichat.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Fable-5 自研设计语言 · **第二层：semantic + feature 色 token**（主题切换枢纽）。
 *
 * 组件经 [AppTheme].colors 读取（如 `AppTheme.colors.text.primary` / `.bubble.userStart` / `.economy.gold`），
 * **禁直引 [Palette]**。深浅（+ 未来 AMOLED）= 不同的 semantic→primitive 映射，feature 引用关系不动。
 * 见 [FABLE5_DESIGN_LANGUAGE.md] §1 与 [FABLE5_CHAT_REDESIGN_PROPOSAL.md] §2。
 */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val text: AppTextColors,
    val surface: AppSurfaceColors,
    val accent: AppAccentColors,
    val bubble: AppBubbleColors,
    val status: AppStatusColors,
    val economy: AppEconomyColors,
    val emotion: AppEmotionColors,
    val pet: AppPetColors,
    val ourDays: AppOurDaysColors,
)

/**
 * 文字三级 + 强调上文字（87%/60%/38% 透明度层级靠 token 落值而非运行时 alpha）。
 * [onAccent]=陶土渐变填充（用户气泡/输入栏主钮·同源）上的字/图标：浅档深墨、深档暖白
 * （深陶+白字拍板 2026-06-13·与 [AppBubbleColors.onUser] 同值同向）。
 */
@Immutable
data class AppTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val onAccent: Color,
)

/**
 * 表面层级：底 / raised（纸·靠极浅投影或 1px 描边立体）/ sunken（凹）/ stroke（1px 明度分隔）/ scrim
 * / glaze+glazeShade（白瓷药丸面·浮在 sunken 凹槽之上）。
 */
@Immutable
data class AppSurfaceColors(
    val base: Color,
    val raised: Color,
    val sunken: Color,
    val stroke: Color,
    val scrim: Color,
    // 白瓷药丸面纵向渐变两 stop（顶 [glaze] → 底 [glazeShade]）——分段控件 / 选择标签选中态浮起的那枚瓷片
    // （「白瓷药丸」2026-09-03·消费方 = `Modifier.porcelainThumb`）。**不是** raised：它专为「凹槽里浮起
    // 一块」而生，浅档比 raised 更亮更暖、深档比 sunken 亮一阶，两者不可互换。
    val glaze: Color,
    val glazeShade: Color,
)

/**
 * 陶土玫主强调 + 用户气泡 / 输入栏主钮共享的双 stop 渐变（135° 对角·D4 同源同向）。
 * [primary]=**装饰性**陶强调（光标/引用条/波形/越阈染等细件·非文字非按钮底·两档都取中间调保深底可见）；
 * [gradientStart]/[gradientEnd]=主行动钮填充（浅档浅陶配深墨字、深档深陶配暖白字·深陶+白字拍板
 * 2026-06-13）；其上文字走 [AppTextColors.onAccent]。[onPrimary]=若以 [primary] 作整面填充时其上的字
 * （两档=深墨·中间调配深墨 4.85:1）；[text]=陶土色**文字/小图标** on 表面的功能档（≥4.5:1）。
 */
@Immutable
data class AppAccentColors(
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    // 选中态软填充（= M3 primaryContainer 对）：[container]=极浅陶土容器（分段控件滑动药丸 / 选择标签选中 /
    // 次要按钮 Tonal）·[onContainer]=容器上的字/图标（与 container 预配平衡 ≥4.5:1·按钮族重构 2026-06-19）。
    val container: Color,
    val onContainer: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    // 恒深陶档（不随主题翻浅）：大面积深陶填充（圈子枢纽 Hero 等）用，配 [onDeep] 暖白字（两档同值·§1.4）。
    val deepStart: Color,
    val deepEnd: Color,
    val onDeep: Color,
)

/** 气泡族：用户陶土玫渐变 + AI raised 暖白纸（D1）+ AI 深档描边。 */
@Immutable
data class AppBubbleColors(
    val userStart: Color,
    val userEnd: Color,
    val onUser: Color,
    val ai: Color,
    val aiStroke: Color,
)

/**
 * status 家族（低饱和·瞬态）：每色双档=container（装饰浅档底）+ on（功能深档文字 ≥4.5:1）。
 * warning 另加 **solid 实底档**（[warningSolid] 深琥珀填充 + [onWarningSolid] 暖白字·破坏性确认 CTA 用·
 * 见 [AppButtonStyle.Warning]·深浅双档 ≥4.5:1 进 ColorContrastTest；注意 [onWarning] 语义是「warningContainer/
 * 浅底上的琥珀文字/图标」≠ 实底填充，不可互换）。
 */
@Immutable
data class AppStatusColors(
    val warningContainer: Color,
    val onWarning: Color,
    val warningSolid: Color,
    val onWarningSolid: Color,
    val successContainer: Color,
    val onSuccess: Color,
    val errorContainer: Color,
    val onError: Color,
    val infoContainer: Color,
    val onInfo: Color,
)

/**
 * 经济金贵金属族 + 红包哑光暗红/烫金（去糖果化·去赌场化）。
 * [redPacketStroke]=红包外缘烫金细描边（D12·两档单色 #D4B96A——描边走单色不走渐变，深档大面积渐变易振动出血）。
 */
@Immutable
data class AppEconomyColors(
    val gold: Color,
    val goldGradientStart: Color,
    val goldGradientEnd: Color,
    val redPacketStart: Color,
    val redPacketEnd: Color,
    val redPacketSeal: Color,
    val redPacketStroke: Color,
    val sealGoldStart: Color,
    val sealGoldEnd: Color,
    // 纪念卡（拆开态·去赌场化纪念卡范式·R 阶段）：纸卡渐变 + 卡上字 + 金额金属渐变 + 节日红印 + 开盒径向背景。
    val keepsakeCardStart: Color,
    val keepsakeCardEnd: Color,
    val onKeepsake: Color,
    val keepsakeAmountStart: Color,
    val keepsakeAmountEnd: Color,
    val keepsakeStamp: Color,
    val onKeepsakeStamp: Color,
    val openedBackdropStart: Color,
    val openedBackdropEnd: Color,
)

/**
 * 情绪氛围色（莫兰迪低饱和·装饰浅档·只承载氛围不承载语义）。本期 5 个 valence-arousal 原型，
 * 完整 12 情绪映射在组件 ④ 细化；[forValence] 给 12 情绪一个临时归并入口。
 */
@Immutable
data class AppEmotionColors(
    val joy: Color,
    val calm: Color,
    val sad: Color,
    val shy: Color,
    val anger: Color,
    // 功能深档图标（圈子枢纽分色 IconTile·on 浅 tile ≥3:1）——§1.4「装饰浅档 + 功能深档」双档之深档。
    val calmInk: Color,
    val sadInk: Color,
    val shyInk: Color,
)

/**
 * 宠物状态四色（莫兰迪暖档·替 iOS systemColor 四环）：饱食/清洁/心情/健康。
 * **纯装饰填充**（心情环弧 + 状态条·非文字底）；数值文字走 [AppTextColors]，不在本色上 → 不入 ColorContrastTest 文字网。
 */
@Immutable
data class AppPetColors(
    val satiety: Color,
    val cleanliness: Color,
    val mood: Color,
    val health: Color,
)

/** 「我们的日子」feature 色（卷三图纸 §4.1·提案 D-11）：热度三档底 + 三家族点 + 六识别色（识别色只作识别圈 / 头像描边·不作文字）。 */
@Immutable
data class AppOurDaysColors(
    val heat1: Color,
    val heat2: Color,
    val heat3: Color,
    val dotMeeting: Color,
    val dotRelation: Color,
    val dotLife: Color,
    val identity: List<Color>,
) {
    companion object {
        val IDENTITY: List<Color> = listOf(Color(0xFFBE8A76), Color(0xFF8FA085), Color(0xFF8A9BAA), Color(0xFFC49A94), Color(0xFFC4AC7A), Color(0xFFA093A8))

        /** 非陶土主题（青花）派生：heat1 = lerp(base, container, 0.5)；heat2 = container；heat3 = lerp(container, primary, 0.2)。 */
        fun derive(base: Color, container: Color, primary: Color, dotMeeting: Color, dotRelation: Color, dotLife: Color) = AppOurDaysColors(
            heat1 = lerp(base, container, 0.5f),
            heat2 = container,
            heat3 = lerp(container, primary, 0.2f),
            dotMeeting = dotMeeting,
            dotRelation = dotRelation,
            dotLife = dotLife,
            identity = IDENTITY,
        )
    }
}

/**
 * 圈子枢纽分色 IconTile 浅 tile 的不透明度（`emotion.X.copy(alpha = EmotionTileAlpha)` over surface.raised）。
 * 与同族 `*Ink` 图标的对比度由 `ColorContrastTest` 按此值看门——调此值会同步影响渲染与测试，单一事实源（契约 §4）。
 */
internal const val EmotionTileAlpha = 0.30f

/** 浅色档（暖中性默认）。 */
val LightAppColors: AppColors = AppColors(
    isDark = false,
    text = AppTextColors(
        primary = Palette.Ink,
        secondary = Palette.InkSoft,
        // tertiary 降级为纯装饰（分隔/disabled/非文字）——功能性小字（时间戳等）改用 secondary（WCAG 决议 2026-06-13）。
        tertiary = Palette.InkFaint,
        onAccent = Palette.OnClayInk,
    ),
    surface = AppSurfaceColors(
        base = Palette.Porcelain,
        raised = Palette.White,
        sunken = Palette.Linen,
        stroke = Palette.Linen,
        scrim = Palette.Scrim,
        glaze = Palette.Glaze,
        glazeShade = Palette.GlazeShade,
    ),
    accent = AppAccentColors(
        primary = Palette.Clay,
        onPrimary = Palette.OnClayInk,
        text = Palette.ClayDeep,
        container = Palette.ClayWhisper,
        onContainer = Palette.ClayInk,
        gradientStart = Palette.ClayLight,
        gradientEnd = Palette.Clay,
        deepStart = Palette.ClayDeep,
        deepEnd = Palette.ClayEmber,
        onDeep = Palette.OnClayDark,
    ),
    bubble = AppBubbleColors(
        userStart = Palette.ClayLight,
        userEnd = Palette.Clay,
        onUser = Palette.OnClayInk,
        ai = Palette.White,
        aiStroke = Palette.Linen,
    ),
    status = AppStatusColors(
        warningContainer = Palette.WarnContainer,
        onWarning = Palette.OnWarn,
        warningSolid = Palette.OnWarn,
        onWarningSolid = Palette.OnClayDark,
        successContainer = Palette.SuccessContainer,
        onSuccess = Palette.OnSuccess,
        errorContainer = Palette.ErrorContainer,
        onError = Palette.OnError,
        infoContainer = Palette.InfoContainer,
        onInfo = Palette.OnInfo,
    ),
    economy = AppEconomyColors(
        gold = Palette.Gold,
        goldGradientStart = Palette.GoldGradStart,
        goldGradientEnd = Palette.GoldGradEnd,
        redPacketStart = Palette.RedPacketStart,
        redPacketEnd = Palette.RedPacketEnd,
        redPacketSeal = Palette.RedPacketSeal,
        redPacketStroke = Palette.GoldDark,
        sealGoldStart = Palette.SealGoldStart,
        sealGoldEnd = Palette.SealGoldEnd,
        keepsakeCardStart = Palette.KeepsakeCardStart,
        keepsakeCardEnd = Palette.KeepsakeCardEnd,
        onKeepsake = Palette.OnKeepsake,
        keepsakeAmountStart = Palette.KeepsakeAmountStart,
        keepsakeAmountEnd = Palette.KeepsakeAmountEnd,
        keepsakeStamp = Palette.SealStamp,
        onKeepsakeStamp = Palette.OnSealStamp,
        openedBackdropStart = Palette.OpenedBackdropStart,
        openedBackdropEnd = Palette.OpenedBackdropEnd,
    ),
    emotion = AppEmotionColors(
        joy = Palette.EmotionJoy,
        calm = Palette.EmotionCalm,
        sad = Palette.EmotionSad,
        shy = Palette.EmotionShy,
        anger = Palette.EmotionAnger,
        calmInk = Palette.CalmInk,
        sadInk = Palette.SadInk,
        shyInk = Palette.ShyInk,
    ),
    pet = AppPetColors(
        satiety = Palette.PetSatiety,
        cleanliness = Palette.PetClean,
        mood = Palette.PetMood,
        health = Palette.PetHealth,
    ),
    ourDays = AppOurDaysColors(
        heat1 = Color(0xFFF6EBE4),
        heat2 = Palette.ClayWhisper,
        heat3 = Color(0xFFE8CDBF),
        dotMeeting = Color(0xFFA88E4E),
        dotRelation = Palette.CalmInk,
        dotLife = Palette.SadInk,
        identity = AppOurDaysColors.IDENTITY,
    ),
)

/** 深色档（深暖灰默认·点缀降饱和 20–30%·AI 气泡靠 1px 描边替投影）。AMOLED 纯黑档后续只替 surface 映射。 */
val DarkAppColors: AppColors = AppColors(
    isDark = true,
    text = AppTextColors(
        primary = Palette.Cream,
        secondary = Palette.Sand,
        tertiary = Palette.Taupe,
        onAccent = Palette.OnClayDark,
    ),
    surface = AppSurfaceColors(
        base = Palette.Espresso,
        raised = Palette.Coffee,
        sunken = Palette.Bark,
        stroke = Palette.BarkLine,
        scrim = Palette.Scrim,
        glaze = Palette.GlazeDark,
        glazeShade = Palette.GlazeDarkShade,
    ),
    // 深陶+白字拍板（2026-06-13·微信深色式）：深档气泡/主钮渐变换深陶 #9A5B3E→#8A4E33 配暖白
    // #F5EFEA（4.67–5.72:1·避开中间调死区 #A8765F）——夜里大面积浅陶有眩光感，深陶贴「同一房间的
    // 夜晚」。装饰细件 accent.primary 仍取中间调 Clay 保深底可见（对 base ≈6.4:1·非文字）；
    // accent.text 用 #BE8A76 作深表面上的陶土色文字（对 raised #1C1916 ≈5.8:1）。
    accent = AppAccentColors(
        primary = Palette.Clay,
        onPrimary = Palette.OnClayInk,
        text = Palette.Clay,
        container = Palette.ClayWhisperDark,
        onContainer = Palette.ClayCream,
        gradientStart = Palette.ClayDeep,
        gradientEnd = Palette.ClayEmber,
        deepStart = Palette.ClayDeep,
        deepEnd = Palette.ClayEmber,
        onDeep = Palette.OnClayDark,
    ),
    bubble = AppBubbleColors(
        userStart = Palette.ClayDeep,
        userEnd = Palette.ClayEmber,
        onUser = Palette.OnClayDark,
        ai = Palette.Bark,
        aiStroke = Palette.BarkLine,
    ),
    status = AppStatusColors(
        warningContainer = Palette.WarnContainerDark,
        onWarning = Palette.OnWarnDark,
        warningSolid = Palette.WarnSolidDark,
        onWarningSolid = Palette.OnClayDark,
        successContainer = Palette.SuccessContainerDark,
        onSuccess = Palette.OnSuccessDark,
        errorContainer = Palette.ErrorContainerDark,
        onError = Palette.OnErrorDark,
        infoContainer = Palette.InfoContainerDark,
        onInfo = Palette.OnInfoDark,
    ),
    economy = AppEconomyColors(
        gold = Palette.GoldDark,
        goldGradientStart = Palette.GoldDark,
        goldGradientEnd = Palette.GoldDark,
        redPacketStart = Palette.RedPacketStart,
        redPacketEnd = Palette.RedPacketEnd,
        redPacketSeal = Palette.RedPacketSeal,
        redPacketStroke = Palette.GoldDark,
        sealGoldStart = Palette.SealGoldStart,
        sealGoldEnd = Palette.SealGoldEnd,
        keepsakeCardStart = Palette.KeepsakeCardDarkStart,
        keepsakeCardEnd = Palette.KeepsakeCardDarkEnd,
        onKeepsake = Palette.OnKeepsakeDark,
        keepsakeAmountStart = Palette.KeepsakeAmountDarkStart,
        keepsakeAmountEnd = Palette.KeepsakeAmountDarkEnd,
        keepsakeStamp = Palette.SealStampDark,
        onKeepsakeStamp = Palette.OnSealStamp,
        openedBackdropStart = Palette.OpenedBackdropDarkStart,
        openedBackdropEnd = Palette.OpenedBackdropDarkEnd,
    ),
    emotion = AppEmotionColors(
        joy = Palette.EmotionJoyDark,
        calm = Palette.EmotionCalmDark,
        sad = Palette.EmotionSadDark,
        shy = Palette.EmotionShyDark,
        anger = Palette.EmotionAngerDark,
        // 深档：深 tile 上需亮档图标 → 复用浅档 morandi（calm/sad/shy 浅档原色）。
        calmInk = Palette.EmotionCalm,
        sadInk = Palette.EmotionSad,
        shyInk = Palette.EmotionShy,
    ),
    pet = AppPetColors(
        satiety = Palette.PetSatietyDark,
        cleanliness = Palette.PetCleanDark,
        mood = Palette.PetMoodDark,
        health = Palette.PetHealthDark,
    ),
    ourDays = AppOurDaysColors(
        heat1 = Color(0xFF221B17),
        heat2 = Palette.ClayWhisperDark,
        heat3 = Color(0xFF4A362B),
        dotMeeting = Palette.EmotionJoy,
        dotRelation = Palette.EmotionCalm,
        dotLife = Palette.EmotionSad,
        identity = AppOurDaysColors.IDENTITY,
    ),
)

/**
 * 青花主题 · 浅色档（瓷白底 + 钴蓝点睛·见 FABLE5_THEME_QINGHUA_PROPOSAL.md §3.1）。
 * 只换 text/surface/accent/bubble；status/economy/emotion/pet **沿用暖中性现值**（§1 铁律 2·复用 [LightAppColors] 子对象）。
 */
val QinghuaLightAppColors: AppColors = AppColors(
    isDark = false,
    text = AppTextColors(
        primary = Palette.InkBlue,
        secondary = Palette.InkBlueSoft,
        tertiary = Palette.InkBlueFaint,
        onAccent = Palette.OnCobalt,
    ),
    surface = AppSurfaceColors(
        base = Palette.PorcelainBlue,
        raised = Palette.White,
        sunken = Palette.MistBlue,
        stroke = Palette.MistBlue,
        scrim = Palette.Scrim,
        glaze = Palette.GlazeBlue,
        glazeShade = Palette.GlazeBlueShade,
    ),
    accent = AppAccentColors(
        primary = Palette.Cobalt,
        onPrimary = Palette.OnCobalt,
        text = Palette.CobaltText,
        container = Palette.CobaltContainer,
        onContainer = Palette.CobaltOnContainer,
        gradientStart = Palette.CobaltGradStart,
        gradientEnd = Palette.CobaltGradEnd,
        deepStart = Palette.CobaltGradEnd,
        deepEnd = Palette.CobaltDeepEnd,
        onDeep = Palette.OnCobaltDeep,
    ),
    bubble = AppBubbleColors(
        userStart = Palette.CobaltGradStart,
        userEnd = Palette.CobaltGradEnd,
        onUser = Palette.OnCobalt,
        ai = Palette.White,
        aiStroke = Palette.MistBlue,
    ),
    status = LightAppColors.status,
    economy = LightAppColors.economy,
    emotion = LightAppColors.emotion,
    pet = LightAppColors.pet,
    ourDays = AppOurDaysColors.derive(
        base = Palette.PorcelainBlue,
        container = Palette.CobaltContainer,
        primary = Palette.Cobalt,
        dotMeeting = Color(0xFFA88E4E),
        dotRelation = Palette.CalmInk,
        dotLife = Palette.SadInk,
    ),
)

/** 青花主题 · 深色档（青花·夜墨青底 + 略提亮钴蓝·见 §3.2）。沿用 [DarkAppColors] 的 status/economy/emotion/pet。 */
val QinghuaDarkAppColors: AppColors = AppColors(
    isDark = true,
    text = AppTextColors(
        primary = Palette.MoonCream,
        secondary = Palette.MoonCreamSoft,
        tertiary = Palette.MoonCreamFaint,
        onAccent = Palette.OnCobalt,
    ),
    surface = AppSurfaceColors(
        base = Palette.NightInk,
        raised = Palette.NightRaised,
        sunken = Palette.NightSunken,
        stroke = Palette.NightStroke,
        scrim = Palette.Scrim,
        glaze = Palette.GlazeBlueDark,
        glazeShade = Palette.GlazeBlueDarkShade,
    ),
    accent = AppAccentColors(
        primary = Palette.CobaltBright,
        onPrimary = Palette.NightInk, // 亮档钴蓝 #6E93C8 配深字（白字仅 2.95·避中间调死区）
        text = Palette.CobaltTextDark,
        container = Palette.CobaltContainerDark,
        onContainer = Palette.CobaltOnContainerDark,
        gradientStart = Palette.CobaltGradStartDark,
        gradientEnd = Palette.CobaltGradEndDark,
        deepStart = Palette.CobaltDeepStartDark,
        deepEnd = Palette.CobaltDeepEndDark,
        onDeep = Palette.OnCobaltDeep,
    ),
    bubble = AppBubbleColors(
        userStart = Palette.CobaltGradStartDark,
        userEnd = Palette.CobaltGradEndDark,
        onUser = Palette.OnCobalt,
        ai = Palette.NightRaised,
        aiStroke = Palette.NightStroke,
    ),
    status = DarkAppColors.status,
    economy = DarkAppColors.economy,
    emotion = DarkAppColors.emotion,
    pet = DarkAppColors.pet,
    ourDays = AppOurDaysColors.derive(
        base = Palette.NightInk,
        container = Palette.CobaltContainerDark,
        primary = Palette.CobaltBright,
        dotMeeting = Palette.EmotionJoy,
        dotRelation = Palette.EmotionCalm,
        dotLife = Palette.EmotionSad,
    ),
)

/** semantic 色的 CompositionLocal（[AIPocketChatTheme] 按解析后的深浅 provide·默认浅档）。 */
val LocalAppColors = staticCompositionLocalOf { LightAppColors }
