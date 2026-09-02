package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.diary.toneColor
import com.situ.aichat.ui.offline.MeetingSky
import com.situ.aichat.ui.offline.MeetingSkyTextBands
import com.situ.aichat.ui.offline.OfflineMoodKind
import com.situ.aichat.ui.offline.SKY_GLOW_BANDS
import com.situ.aichat.ui.offline.SkyBucket
import com.situ.aichat.ui.offline.SkySpec
import com.situ.aichat.ui.offline.SkyWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fable-5 配色对比度看门（设计语言 §1.4·WCAG 2.2 唯一 gate·正文 4.5:1）。枚举所有「文字×底」组合，
 * 浅/深双档都断言 ≥4.5。**2026-06-13 决议背景**：白字 on 陶土玫 #BE8A76 实测仅 2.96:1（设计语言原估
 * 「≈4.6」有误），故浅档气泡/填充文字改深墨（微信式）、时间戳等功能小字用 text.secondary（tertiary 降为
 * 纯装饰）、陶土色文字用功能深档 accent.text #9A5B3E。**深陶+白字拍板（同日补充）**：深档气泡/主钮渐变
 * 换深陶 #9A5B3E→#8A4E33 配暖白 #F5EFEA（避开中间调死区）——本测把这些落值钉成回归网。
 */
class ColorContrastTest {

    private fun assertContrast(fg: Color, bg: Color, min: Double, label: String) {
        val r = ColorContrast.ratio(fg, bg)
        assertTrue("$label: 实测 ${"%.2f".format(r)}:1 < 要求 ${"%.1f".format(min)}:1", r >= min)
    }

    /** alpha 合成：fg 以 [alpha] 覆于实底 bg 上的等效实色（对齐 Compose `copy(alpha=)` over 实底的渲染）。 */
    private fun over(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    private fun checkScheme(c: AppColors, name: String) {
        val body = 4.5 // 正文/功能文字
        // 文字三级（tertiary 纯装饰不测文字对比）× 各表面
        assertContrast(c.text.primary, c.surface.base, body, "$name text.primary×base")
        assertContrast(c.text.primary, c.surface.raised, body, "$name text.primary×raised")
        assertContrast(c.text.secondary, c.surface.base, body, "$name text.secondary×base")
        assertContrast(c.text.secondary, c.surface.raised, body, "$name text.secondary×raised")
        assertContrast(c.text.secondary, c.surface.sunken, body, "$name text.secondary×sunken")
        // 卡壳呼吸白底停（appCardSurface 浅档底停 = lerp(raised, base, 0.35f)·全局卡片承托推广落点）：
        // 正文/次要两级文字都验（此前只有 economy.gold×breathe 一对·本卷卡壳内容主要是这两级）。
        assertContrast(c.text.primary, over(c.surface.base, 0.35f, c.surface.raised), body, "$name card.text.primary×breathe")
        assertContrast(c.text.secondary, over(c.surface.base, 0.35f, c.surface.raised), body, "$name card.text.secondary×breathe")
        // 气泡上的字（worst case = 渐变两 stop 都验·浅档深墨/深档暖白）
        assertContrast(c.bubble.onUser, c.bubble.userStart, body, "$name bubble.onUser×userStart")
        assertContrast(c.bubble.onUser, c.bubble.userEnd, body, "$name bubble.onUser×userEnd")
        // 主行动钮渐变（与气泡同源）上的字/图标
        assertContrast(c.text.onAccent, c.accent.gradientStart, body, "$name onAccent×gradientStart")
        assertContrast(c.text.onAccent, c.accent.gradientEnd, body, "$name onAccent×gradientEnd")
        // 陶土填充上的文字 + 陶土色文字 on 底（功能深档）
        assertContrast(c.accent.onPrimary, c.accent.primary, body, "$name accent.onPrimary×primary")
        assertContrast(c.accent.text, c.surface.base, body, "$name accent.text×base")
        assertContrast(c.accent.text, c.surface.raised, body, "$name accent.text×raised")
        // 选中态软填充容器上的字（= M3 primaryContainer↔onPrimaryContainer 对·按钮族重构 2026-06-19）
        assertContrast(c.accent.onContainer, c.accent.container, body, "$name accent.onContainer×container")
        // 白瓷药丸面上的字（材质升级「白瓷药丸」2026-09-03·[AppPorcelain] / [porcelainThumb]）：分段控件选中段
        // 与选择标签选中态的**实际底色已不再是 accent.container**，而是 surface.glaze→glazeShade 纵向渐变。
        // worst case 取「离字最近的那个 stop」——浅档字深故取更暗的 glazeShade、深档字亮故取更亮的 glaze。
        assertContrast(c.accent.onContainer, c.surface.glaze, body, "$name accent.onContainer×glaze")
        assertContrast(c.accent.onContainer, c.surface.glazeShade, body, "$name accent.onContainer×glazeShade")
        // 经济金文字（= ST8 结局档案卡「结局类型徽章」金字·徽章无填充金字直接 on base·金@0.1 填充实测仅 4.02:1<4.5 故改金边）
        assertContrast(c.economy.gold, c.surface.base, body, "$name economy.gold×base")
        // status 双档（功能深档文字 × 装饰浅档底）
        assertContrast(c.status.onWarning, c.status.warningContainer, body, "$name status.warning")
        assertContrast(c.status.onSuccess, c.status.successContainer, body, "$name status.success")
        assertContrast(c.status.onError, c.status.errorContainer, body, "$name status.error")
        assertContrast(c.status.onInfo, c.status.infoContainer, body, "$name status.info")
        // 实底警示按钮（[AppButtonStyle.Warning]·破坏性确认 CTA）：暖白字 on 深琥珀实底·深浅双档 ≥4.5（修原深色「白字 on 浅琥珀 ≈1.6:1」bug）
        assertContrast(c.status.onWarningSolid, c.status.warningSolid, body, "$name status.warningSolid")
        // 危险文字钮（[AppButton] danger=true·error 色 on 表面·非 errorContainer·按钮族 Phase 2 chunk B）
        assertContrast(c.status.onError, c.surface.base, body, "$name danger.onError×base")
        assertContrast(c.status.onError, c.surface.raised, body, "$name danger.onError×raised")
        // 圈子枢纽 Hero：恒深陶填充上的暖白字（两 stop 都验·契约 §4）
        assertContrast(c.accent.onDeep, c.accent.deepStart, body, "$name accent.onDeep×deepStart")
        assertContrast(c.accent.onDeep, c.accent.deepEnd, body, "$name accent.onDeep×deepEnd")
        // 圈子枢纽分色 IconTile：同族功能深档图标 × 浅 tile（emotion.X@alpha over raised·图标 ≥3:1）
        val icon = 3.0
        assertContrast(c.emotion.calmInk, over(c.emotion.calm, EmotionTileAlpha, c.surface.raised), icon, "$name emotion.calmInk×tile")
        assertContrast(c.emotion.sadInk, over(c.emotion.sad, EmotionTileAlpha, c.surface.raised), icon, "$name emotion.sadInk×tile")
        assertContrast(c.emotion.shyInk, over(c.emotion.shy, EmotionTileAlpha, c.surface.raised), icon, "$name emotion.shyInk×tile")
        // 步进器（[AppStepper] 凹槽药丸·按钮族 Phase 2 chunk D）：值 onContainer 文字 + +/− accent.text 图标 on sunken 凹槽
        assertContrast(c.accent.onContainer, c.surface.sunken, body, "$name stepper.value×sunken")
        assertContrast(c.accent.text, c.surface.sunken, icon, "$name stepper.icon×sunken")
        // 朋友圈评论小笺（MOMENTS_FEED §2.1/§4）：「查看全部 N 条评论」深陶**文字**直落 sunken 内衬——
        // 比上行 stepper 图标档（3.0）更严，按文字档看门（浅档实测 4.53 压线过·未来调色跌破先在此红）。
        assertContrast(c.accent.text, c.surface.sunken, body, "$name moment.viewAll×sunken")
        // 「我」页 v2 主角卡（PROFILE 契约 §9.2）：昵称 primary × 陶土 container 染底；统计数字/编辑/bio 用
        // onContainer（×container 对已在上方「选中态软填充」断言覆盖——accent.text 实测 4.06 不达标被本测打回）。
        // 呼吸染底两 stop 的 worst-case = 纯 container（另一 stop 向 raised 提亮只会拉大浅档对比、深档同理）。
        assertContrast(c.text.primary, c.accent.container, body, "$name profile.hero name×container")
        // 「我」页 v2 资产格：金数字 on 呼吸白两 stop（顶=纯 raised·底=向 base 靠 35%）都 ≥4.5。
        assertContrast(c.economy.gold, c.surface.raised, body, "$name profile.gold×raised")
        assertContrast(c.economy.gold, over(c.surface.base, 0.35f, c.surface.raised), body, "$name profile.gold×breathe")
        // 「我」页 v2 图标块（图标 ≥3:1）：陶土深档 over 陶土 tint / 金深档 over 金 tint（tile alpha 与实现单源）。
        assertContrast(c.accent.text, over(c.accent.primary, com.situ.aichat.ui.profile.ProfileTileAlphaClay, c.surface.raised), icon, "$name profile.tile.clay")
        assertContrast(c.economy.gold, over(c.economy.goldGradientStart, com.situ.aichat.ui.profile.ProfileTileAlphaGold, c.surface.raised), icon, "$name profile.tile.gold")
        // 红包纪念卡（拆开态·R 阶段）：楷体祝福/单位/日期 × 纸卡两 stop（正文 ≥4.5）
        assertContrast(c.economy.onKeepsake, c.economy.keepsakeCardStart, body, "$name keepsake.onKeepsake×cardStart")
        assertContrast(c.economy.onKeepsake, c.economy.keepsakeCardEnd, body, "$name keepsake.onKeepsake×cardEnd")
        // 节日红印上的反白字（小字 ≥4.5）
        assertContrast(c.economy.onKeepsakeStamp, c.economy.keepsakeStamp, body, "$name keepsake.stamp")
        // 金额金属渐变（大号 display·大字 3:1）：两 stop × 纸卡相近 stop 都验 worst-case
        assertContrast(c.economy.keepsakeAmountStart, c.economy.keepsakeCardEnd, icon, "$name keepsake.amountStart×card")
        assertContrast(c.economy.keepsakeAmountEnd, c.economy.keepsakeCardStart, icon, "$name keepsake.amountEnd×card")
        // 日记心情 tint（R1·心情日历格/详情洇染头/撰写选中胶囊）：text.primary × 5 情绪原型@moodTintAlpha
        // over base/raised 都验（tint 上功能文字**只许 primary**——secondary 在浅档 anger tint 上实测 <4.5）。
        val moodAlpha = com.situ.aichat.ui.diary.moodTintAlpha(c.isDark)
        listOf(
            "joy" to c.emotion.joy, "calm" to c.emotion.calm, "sad" to c.emotion.sad,
            "shy" to c.emotion.shy, "anger" to c.emotion.anger,
        ).forEach { (tone, e) ->
            assertContrast(c.text.primary, over(e, moodAlpha, c.surface.base), body, "$name diary.mood.$tone×base")
            assertContrast(c.text.primary, over(e, moodAlpha, c.surface.raised), body, "$name diary.mood.$tone×raised")
        }
        // 日记心情邮票日期小字（J4·契约 §4·实测详见图纸 §11 D-J4）：邮票底 = tint@0.22 叠在洇染 wash 之上（wash =
        // diaryMoodTint = 情绪原型色@moodTintAlpha 真实值·非图纸 0.20 乐观近似），底 surface.base。text.secondary 在此
        // 普遍跌破（图纸 0.20 模型下 12 心情已 9 枚 <4.5·真实 0.55 wash 更差）→ 邮票日期字统一用 text.primary
        //（同 [com.situ.aichat.ui.diary.DiaryMoodPalette]「tint 上功能文字一律 primary」房规）·本测按真实渲染口径断言 primary≥4.5·12 心情逐个×4 配色。
        val moodAlphaStamp = com.situ.aichat.ui.diary.moodTintAlpha(c.isDark)
        com.situ.aichat.ui.diary.DIARY_MOODS.forEach { mood ->
            val tone = com.situ.aichat.ui.diary.diaryMoodTone(mood.emoji)
            if (tone != null) {
                val e = c.emotion.toneColor(tone)
                val stampBg = over(e, 0.22f, over(e, moodAlphaStamp, c.surface.base))
                assertContrast(c.text.primary, stampBg, body, "$name diary.stamp.${mood.emoji}")
            }
        }
        // 「我们的日子」卷三（图纸 §7 T1-6·提案 D-11·R1 🟡-1 裁决后）：月格里凡落在热度填充上的文字一律深墨
        // （日数 + 强调副行都是 text.primary·设计语言 §1.4「陶土填充上的文字一律深墨」）；陶土功能深档只用在无填充格上
        // （accent.text × surface.base 已由上方通用断言覆盖）。原「accent.text × heat3」组合自 R1 起代码中不再存在。
        assertContrast(c.text.primary, c.ourDays.heat1, body, "$name ourDays.text.primary×heat1")
        assertContrast(c.text.primary, c.ourDays.heat2, body, "$name ourDays.text.primary×heat2")
        assertContrast(c.text.primary, c.ourDays.heat3, body, "$name ourDays.text.primary×heat3")
    }

    @Test fun lightScheme_meetsWcag() = checkScheme(LightAppColors, "light")

    @Test fun darkScheme_meetsWcag() = checkScheme(DarkAppColors, "dark")

    @Test fun qinghuaLightScheme_meetsWcag() = checkScheme(QinghuaLightAppColors, "qinghua-light")

    @Test fun qinghuaDarkScheme_meetsWcag() = checkScheme(QinghuaDarkAppColors, "qinghua-dark")

    /**
     * 故事阅读器**纸面** feature token（D8·契约 §6.4；2026-08-03 心情视觉层退役后只剩中性双档）：
     * 浅 / 深两档下正文文字色（[com.situ.aichat.ui.story.StoryReaderLayout.textColor]）对每个渐变 stop ≥4.5。
     * 纸面与 App 主题正交（不随暖中性 / 青花变），故只按 isDark 单轴取档。
     * 文字带 alpha（白字 0.88）先合成到 stop 上再验。
     */
    @Test fun storyMoodPalette_meetsWcag_bothModes() {
        val body = 4.5
        listOf(false, true).forEach { isDark ->
            val mode = if (isDark) "dark" else "light"
            val textColor = com.situ.aichat.ui.story.StoryReaderLayout.textColor(isDark)
            com.situ.aichat.ui.story.StoryMoodPalette.colors(isDark).forEachIndexed { i, stop ->
                // 文字实际渲染 = textColor（白字带 0.88 alpha）以自身 alpha 覆于该 stop 上。
                val onStop = over(textColor, textColor.alpha, stop)
                assertContrast(onStop, stop, body, "story.paper[$i] $mode")
            }
        }
    }

    /**
     * 故事「建议完结卡」（ST11 §4.2）：卡底 = `economy.gold` @0.08 覆于心情纸面 → 卡上的标题 / 正文 / 金图标
     * 在**四主题 × 浅深 × 每个渐变 stop**下都达标。
     *
     * 底色变体口径与 [storyMoodPalette_meetsWcag_bothModes] 一致：纸面与 App 主题正交，
     * 主题只影响 `economy.gold` 的落值。
     *
     * 〔施工登记 §11 D-6〕正文用的是心情层**正文色降到 [SUGGEST_BODY_ALPHA]**，而非心情层 secondary——
     * 后者（浅档黑 @0.4）在此卡底上实测仅 2.7:1，撑不起 §4.2 自己要求的 ≥4.5。
     */
    @Test fun storyEndingSuggestCard_meetsWcag_allThemesAndMoods() {
        // 2026-08-03 心情视觉层退役：纸面恒中性双档，原「11 心情 worst-case 集合」收缩为这两档。
        // （旧 §11 D-7 登记的 melancholy/dreamy 深 stop 金图标不达标一项，随那两套心情 token 删除自然消失。）
        listOf(false, true).forEach { isDark ->
            val gold = com.situ.aichat.ui.story.StoryReaderLayout.suggestGoldColor(isDark)
            val title = com.situ.aichat.ui.story.StoryReaderLayout.textColor(isDark)
            val body = title.copy(alpha = com.situ.aichat.ui.story.SUGGEST_BODY_ALPHA)
            com.situ.aichat.ui.story.StoryMoodPalette.colors(isDark).forEachIndexed { i, stop ->
                // 卡底 = 金以 SUGGEST_GOLD_FILL_ALPHA 覆于该纸面 stop 上的等效实色。
                val card = over(gold, com.situ.aichat.ui.story.SUGGEST_GOLD_FILL_ALPHA, stop)
                val label = "story.suggestCard.paper[$i] ${if (isDark) "dark" else "light"}"
                assertContrast(over(title, title.alpha, card), card, 4.5, "$label 标题")
                assertContrast(over(body, body.alpha, card), card, 4.5, "$label 正文")
                // 金图标/边/饰 = 非文字图形元素（WCAG 1.4.11）→ ≥3.0。
                assertContrast(gold, card, 3.0, "$label 金图标")
            }
        }
    }

    /**
     * 卷二「准备收尾」金胶囊 / 「收尾中」状态 chip（图纸 §4.4 画面②）：金字 + 金图标 + 金边 覆于
     * 金 @[com.situ.aichat.ui.story.SUGGEST_GOLD_FILL_ALPHA] 的胶囊底。
     *
     * 〔施工登记 §11 D-7〕**这里的门槛有意取 3.0 而非正文档 4.5**，理由是同排既有胶囊的等价对照：
     * 「让故事自然发展」是 `menuAccentColor` 字覆于同色 @0.15/0.20 底，浅档 worst **3.58**；本胶囊
     * 金字×金底浅档 worst **3.57**——两者**同一种「同色调字压同色调薄底」形态、同一量级**。
     * 若只把新胶囊改成墨字（可达 10.8:1），同一排会出现「一枚彩字 + 一枚墨字」的割裂，且偏离已过审样图。
     * 故本卷维持样图口径并在此把实测值钉死：**不许再劣化**；整排（含既有陶土胶囊）的对比度提升属
     * 跨组件的独立议题，已在 §11 D-7 留给复核/用户裁决。
     */
    @Test fun storyFinalePill_goldOnGoldTint_isAtParityWithExistingPill() {
        listOf(false, true).forEach { isDark ->
            val gold = com.situ.aichat.ui.story.StoryReaderLayout.suggestGoldColor(isDark)
            val accent = com.situ.aichat.ui.story.StoryReaderLayout.menuAccentColor(isDark)
            val accentAlpha = if (isDark) 0.20f else 0.15f
            com.situ.aichat.ui.story.StoryMoodPalette.colors(isDark).forEachIndexed { i, stop ->
                val pill = over(gold, com.situ.aichat.ui.story.SUGGEST_GOLD_FILL_ALPHA, stop)
                val label = "story.finalePill.paper[$i] ${if (isDark) "dark" else "light"}"
                // 金字/金图标：≥3.0（WCAG 1.4.11 非文字图形档 + 与同排既有胶囊同量级）。
                assertContrast(gold, pill, 3.0, "$label 金字与金图标")
                // 同排既有陶土胶囊的等价对照——新胶囊**不得低于**它（防「新加的比老的更糊」）。
                val existingPill = over(accent, accentAlpha, stop)
                val existingRatio = ColorContrast.ratio(accent, existingPill)
                val newRatio = ColorContrast.ratio(gold, pill)
                assertTrue(
                    "$label 金胶囊(${"%.2f".format(newRatio)}) 不得低于同排既有陶土胶囊(${"%.2f".format(existingRatio)})",
                    newRatio >= existingRatio - 0.1,
                )
            }
        }
    }

    /**
     * 卷三三档快评的**已选态胶囊**（故事二期卷三 §4.3·图纸 §7 T1-2）：
     * 标签色 [com.situ.aichat.ui.story.StoryReaderLayout.onAccentTextColor] 覆于
     * [com.situ.aichat.ui.story.StoryReaderLayout.menuAccentColor] **实底**（非半透明薄底），
     * 深浅双档一律按**正文档 4.5:1** 看门（E11·图纸明令「任一桶不达标即回报停工，不许降标准」）。
     *
     * 与同屏「准备收尾」金胶囊（3.0 档）的区别是**实底 vs 薄底**：那枚是同色调字压同色调 8% 薄底，
     * 天花板就在 3.6 一带；这枚是反白字压实底，本就该也确实做得到 4.5+（实测浅纸 4.67 / 深纸 6.42）。
     */
    /**
     * 章末「你的走向」卡（已存走向态 B·图纸 2026-08-06 §4.1/§4.0）：卡底 = `chromeScrimColor` 覆于纸面 stop
     * （与草稿卡同款），卡上 tag（陶土）与正文（正文色 @[com.situ.aichat.ui.story.DIRECTION_BODY_ALPHA]）
     * 在**浅深 × 每个渐变 stop** 下都按正文档 4.5:1 看门。
     *
     * 〔§4.0 合规偏离留痕〕已过审 mockup 原案是**陶土淡底**（accent@0.07/0.09 覆纸）+ 陶土标题——
     * 该组合浅档 worst 实测仅 **3.90:1 < 4.5**（本测的 mockup 对照例 [storyDirectionCard_mockupTerracottaBase_isWhyWeMovedToScrim]
     * 把这个事实钉住）。落地改为 scrim 卡底 + 正文色标题、陶土身份只走 1dp 边框与 tag，实测
     * 浅 worst 5.03 / 深 worst 7.92（tag）与浅 worst 8.84（正文）。
     */
    @Test
    fun storyDirectionCard_tagAndBody_meetWcag_bothModes() {
        listOf(false, true).forEach { isDark ->
            val scrim = com.situ.aichat.ui.story.StoryReaderLayout.chromeScrimColor(isDark)
            val tag = com.situ.aichat.ui.story.StoryReaderLayout.menuAccentColor(isDark)
            val text = com.situ.aichat.ui.story.StoryReaderLayout.textColor(isDark)
            val body = text.copy(alpha = com.situ.aichat.ui.story.DIRECTION_BODY_ALPHA)
            com.situ.aichat.ui.story.StoryMoodPalette.colors(isDark).forEachIndexed { i, stop ->
                // 卡底 = scrim（自带 alpha）覆于该纸面 stop 上的等效实色。
                val card = over(scrim, scrim.alpha, stop)
                val label = "story.directionCard.paper[$i] ${if (isDark) "dark" else "light"}"
                assertContrast(tag, card, 4.5, "$label tag")
                assertContrast(over(body, body.alpha, card), card, 4.5, "$label 正文")
                // 标题走正文色（同建议完结卡 D-6 先例：身份色只给饰件）——顺带钉住它也达标。
                assertContrast(over(text, text.alpha, card), card, 4.5, "$label 标题")
            }
        }
    }

    /**
     * §4.0 偏离的**依据钉**：mockup 原案（陶土 @0.08 覆纸当卡底 + 陶土标题）在浅纸上够不着 4.5——
     * 本例断言「它确实不达标」，于是日后谁想把卡底改回陶土淡底，必须先来这里翻案。
     */
    @Test
    fun storyDirectionCard_mockupTerracottaBase_isWhyWeMovedToScrim() {
        val accent = com.situ.aichat.ui.story.StoryReaderLayout.menuAccentColor(isDark = false)
        val worst = com.situ.aichat.ui.story.StoryMoodPalette.colors(isDark = false).minOf { stop ->
            ColorContrast.ratio(accent, over(accent, 0.08f, stop))
        }
        assertTrue(
            "mockup 陶土淡底方案浅档 worst 实测 ${"%.2f".format(worst)}:1——若已 ≥4.5 说明色板变了，§4.0 偏离须重新裁决",
            worst < 4.5,
        )
    }

    @Test
    fun storyChapterRating_selectedPill_meetsWcag_bothModes() {
        listOf(false, true).forEach { isDark ->
            val mode = if (isDark) "dark" else "light"
            val fill = com.situ.aichat.ui.story.StoryReaderLayout.menuAccentColor(isDark)
            val label = com.situ.aichat.ui.story.StoryReaderLayout.onAccentTextColor(isDark)
            assertContrast(label, fill, 4.5, "story.rating.selected $mode")
        }
    }

    /**
     * `onAccentTextColor` 的**选法**本身（不是落值）：它必须始终吐出「暖白 / 深墨里与底对比更高的那个」。
     *
     * 断言从 §4.3 的规格文字独立反推——这里不照抄实现的两个常量，而是**从函数自己的输出反推候选集**，
     * 再验「另一个候选不可能更好」：日后谁把两个候选调成别的色，只要选择规则没坏，本例仍成立。
     */
    @Test
    fun storyOnAccentTextColor_alwaysPicksTheHigherContrastOfTheTwoCandidates() {
        val layout = com.situ.aichat.ui.story.StoryReaderLayout
        // 两个候选 = 浅纸桶与深纸桶各自的产出（menuAccentColor 只有这两个落值，故这两轮必然把候选集取满）。
        val candidates = listOf(
            layout.onAccentTextColor(isDark = false),
            layout.onAccentTextColor(isDark = true),
        ).distinct()
        assertEquals("候选应恰为两色（暖白 / 深墨）", 2, candidates.size)
        listOf(false, true).forEach { isDark ->
            val fill = layout.menuAccentColor(isDark)
            val chosen = layout.onAccentTextColor(isDark)
            val best = candidates.maxOf { ColorContrast.ratio(it, fill) }
            assertEquals(
                "isDark=$isDark 应选中对比最高的候选",
                best,
                ColorContrast.ratio(chosen, fill),
                0.0001,
            )
        }
    }

    /**
     * 建议卡的金**仍是 `economy.gold` 的两档同源色**，只是取档依据换成纸面深浅（§11 D-7）。
     *
     * 本例把「四主题」这一轴钉死：四套主题的 economy.gold 恰好只有浅/深两个落值，且与
     * [com.situ.aichat.ui.story.StoryReaderLayout.suggestGoldColor] 的两个返回值逐一相等——
     * 故上面按纸面深浅扫一遍即等价覆盖四主题，卡片不会因换主题而漂色。
     */
    @Test fun storyEndingSuggestCard_goldStaysAnchoredToEconomyToken() {
        val lightGold = com.situ.aichat.ui.story.StoryReaderLayout.suggestGoldColor(isDark = false)
        val darkGold = com.situ.aichat.ui.story.StoryReaderLayout.suggestGoldColor(isDark = true)
        listOf(
            "warm-light" to LightAppColors, "qinghua-light" to QinghuaLightAppColors,
        ).forEach { (name, c) -> assertEquals("$name 的 economy.gold 应 = 建议卡浅纸金", lightGold, c.economy.gold) }
        listOf(
            "warm-dark" to DarkAppColors, "qinghua-dark" to QinghuaDarkAppColors,
        ).forEach { (name, c) -> assertEquals("$name 的 economy.gold 应 = 建议卡深纸金", darkGold, c.economy.gold) }
    }

    /**
     * 见面回忆「那晚的天色」停色锚点（SKY-4）：25 组合文字色对每档渐变停（底停含满纱）。
     * 〔R1 🔴-1 勘误〕文字几何上不落在这些代理点（落在停与停之间、纱只有部分强度），代理点全绿
     * ≠ 落点达标——本测**只作停色回归锚点**，达标依据 = [meetingSky_textBands_compositedContrast]。
     */
    @Test fun meetingSky_allBucketMoodCombos_textOnEveryStop() {
        for (bucket in SkyBucket.entries) {
            for (kind in OfflineMoodKind.entries) {
                val spec = MeetingSky.spec(bucket, kind)
                spec.stops.forEachIndexed { i, stop ->
                    val bg = if (i == 2 && spec.bottomHaze) over(MeetingSky.Haze, MeetingSky.HAZE_ALPHA, stop) else stop
                    assertContrast(spec.textColor, bg, 4.5, "sky $bucket×$kind stop$i")
                }
            }
        }
    }

    // ---- 天色·真实文字带扫描（R1 🔴-1 返工·契约 §2.1「meta 行落点合成色 ≥4.5」的达标依据）----

    /** 渐变在 y（0..1）处的插值色：三停位 0/0.5/1，Skia 线性渐变按 sRGB 分量插值（≠ Compose lerp 的 Oklab）。 */
    private fun gradientAt(stops: List<Color>, y: Float): Color {
        val (a, b, t) = if (y <= 0.5f) Triple(stops[0], stops[1], y / 0.5f) else Triple(stops[1], stops[2], (y - 0.5f) / 0.5f)
        return Color(
            red = a.red + (b.red - a.red) * t,
            green = a.green + (b.green - a.green) * t,
            blue = a.blue + (b.blue - a.blue) * t,
        )
    }

    /**
     * hero 卡在 y 处的落点底色变体：基础 = 渐变 + 底纱实际 α(y)（[MeetingSky.hazeAlphaAt] 单源）；
     * GLOW 桶（暖/甜）另出「霞带整带叠加」变体（层序照渲染：weather 先、纱后）——霞带 x 向只与文字部分
     * 交叠，按全叠取更严。FOG/CLOUDS（y 0.24–0.49·α≤0.10 暖白）只擦地点带前沿、离线核最差 4.8+，
     * SUN_HALO 只出现在浅天深墨字的右上角（x 与文字错位且提亮反助墨字），两者不进模型。
     */
    private fun heroBgVariants(spec: SkySpec, y: Float): List<Color> {
        val base = gradientAt(spec.stops, y)
        val glowed = if (spec.weather == SkyWeather.GLOW_BANDS) {
            SKY_GLOW_BANDS.fold(base) { acc, band ->
                val (y0, h, a) = band
                if (y >= y0 && y <= y0 + h) over(spec.weatherColor, a, acc) else acc
            }
        } else {
            base
        }
        val hazeAlpha = if (spec.bottomHaze) MeetingSky.hazeAlphaAt(y) else 0f
        return listOf(base, glowed).distinct().map { if (hazeAlpha > 0f) over(MeetingSky.Haze, hazeAlpha, it) else it }
    }

    /** 带内步进扫描：Δy ≤ 0.005（硬约束为 ≤5% 卡高，这里取 10 倍细）。 */
    private fun scanBand(band: ClosedFloatingPointRange<Float>, check: (Float) -> Unit) {
        val steps = kotlin.math.ceil((band.endInclusive - band.start) / 0.005f).toInt().coerceAtLeast(1)
        for (i in 0..steps) check(band.start + (band.endInclusive - band.start) * i / steps)
    }

    /**
     * 25 组合 × 窗景卡全部文字带 + 小天窗日期带，按**实际渲染口径**断言 ≥4.5（契约 §2.1 红线族规）：
     * 文字色先按实际 alpha 合成到落点底色（[over]·房子先例 = [storyMoodPalette_meetsWcag_bothModes]），
     * 落点底色 = 渐变按 y 插值 + 纱按实际 α(y) 折线 ramp（+GLOW 桶霞带保守叠加）。带区间与 alpha
     * 单源 = [MeetingSkyTextBands]（与 MeetingSkyCard 布局互指）。断言从契约独立反推：≥4.5 硬值无 cushion。
     */
    @Test fun meetingSky_textBands_compositedContrast() {
        val body = 4.5
        for (bucket in SkyBucket.entries) {
            for (kind in OfflineMoodKind.entries) {
                val spec = MeetingSky.spec(bucket, kind)
                listOf(
                    Triple("date", MeetingSkyTextBands.HERO_DATE, MeetingSkyTextBands.DATE_ALPHA),
                    Triple("location", MeetingSkyTextBands.HERO_LOCATION, MeetingSkyTextBands.LOCATION_ALPHA),
                    Triple("activity", MeetingSkyTextBands.HERO_ACTIVITY, MeetingSkyTextBands.ACTIVITY_ALPHA),
                    Triple("meta", MeetingSkyTextBands.HERO_META, MeetingSkyTextBands.DURATION_ALPHA),
                ).forEach { (band, range, alpha) ->
                    scanBand(range) { y ->
                        heroBgVariants(spec, y).forEach { bg ->
                            assertContrast(over(spec.textColor, alpha, bg), bg, body, "sky $bucket×$kind $band y=$y")
                        }
                    }
                }
                // meta 带上的「第 N 场·情绪」药丸：真实渲染多一层填充（textColor@fill over 落点）再落字。
                val fill = if (spec.skyIsLight) MeetingSkyTextBands.PILL_FILL_LIGHT_ALPHA else MeetingSkyTextBands.PILL_FILL_DARK_ALPHA
                scanBand(MeetingSkyTextBands.HERO_META) { y ->
                    heroBgVariants(spec, y).forEach { bg ->
                        val pillBg = over(spec.textColor, fill, bg)
                        assertContrast(over(spec.textColor, MeetingSkyTextBands.PILL_TEXT_ALPHA, pillBg), pillBg, body, "sky $bucket×$kind pill y=$y")
                    }
                }
                // 小天窗日期带：纯渐变 + MINI 纱线性 ramp（52dp 窗无星月天气·MeetingSkyMiniThumb 同构）。
                scanBand(MeetingSkyTextBands.THUMB_DATE) { y ->
                    var bg = gradientAt(spec.stops, y)
                    if (spec.bottomHaze && y > MeetingSky.MINI_HAZE_START) {
                        val a = MeetingSky.MINI_HAZE_ALPHA * (y - MeetingSky.MINI_HAZE_START) / (1f - MeetingSky.MINI_HAZE_START)
                        bg = over(MeetingSky.Haze, a, bg)
                    }
                    assertContrast(over(spec.textColor, MeetingSkyTextBands.THUMB_DATE_ALPHA, bg), bg, body, "sky $bucket×$kind thumb y=$y")
                }
            }
        }
    }

    /**
     * 记忆星空「文字×底」组合（图纸 2026-07-16-记忆星空 §7 T-C）：恒暗页不走主题 token，故独立登记
     * （同天色卡口径）。底取各文字实际落点的**最不利**实底：夜幕首停 #0A0D1E（顶栏 chrome 落此）、
     * 第三停 #232947（底部标注落此·画布最亮处）、sheet 近似底 #151A2C、入口卡主渐变首停 #0B0E1D。
     * 半透明文字先按 [over] 合成再测。
     */
    @Test fun starfield_chromeAndSheetText_contrast() {
        val body = 4.5
        val warmWhite = Color(0xFFEDE8E2)
        val skyTop = Color(0xFF0A0D1E)      // §4.1 主渐变 0%
        val skyBright = Color(0xFF232947)   // §4.1 主渐变 78%（底部标注/图例落此附近 = 最不利）
        val sheetBg = Color(0xFF151A2C)     // §4.8 深玻璃近似底（不透明分量）
        val entryBg = Color(0xFF0B0E1D)     // §4.9 入口卡主渐变 0%

        // 顶栏标题 / sheet 标题：满不透明暖白。
        assertContrast(warmWhite, skyTop, body, "starfield title×skyTop")
        assertContrast(warmWhite, sheetBg, body, "starfield sheet title×sheetBg")
        // 底部标注楷体 α.68 合成后 × 最亮夜幕停。
        assertContrast(over(warmWhite, 0.68f, skyBright), skyBright, body, "starfield footer α.68×skyBright")
        // 图例 α.7（10.5sp 走 body 档·图纸 T-C 明示）。
        assertContrast(over(warmWhite, 0.7f, skyBright), skyBright, body, "starfield legend α.7×skyBright")
        // sheet 链接金 #E8C77B（= §4.3 见面晕色）。
        assertContrast(Color(0xFFE8C77B), sheetBg, body, "starfield sheet link×sheetBg")
        // 入口卡标题（满）+ 副行 α.6 合成。
        assertContrast(warmWhite, entryBg, body, "starfield entry title×entryBg")
        assertContrast(over(warmWhite, 0.6f, entryBg), entryBg, body, "starfield entry sub α.6×entryBg")
        // sheet meta α.55 与正文 α.85。
        assertContrast(over(warmWhite, 0.85f, sheetBg), sheetBg, body, "starfield sheet body α.85×sheetBg")
    }

    /**
     * 书架长按玻璃菜单（ST10-4）：文字/危险红/陶土图标落在真实三层合成底上——
     * 页面 base → 长按 scrim（恒黑 10%·与 ShelfMenuScrim 字面互指）→ 菜单垫底 raised@94%。
     * 四主题方案全测（菜单色全走 token，随主题换肤）。
     */
    @Test fun storyShelfGlassMenu_compositedContrast() {
        val body = 4.5
        val icon = 3.0 // 前导图标 = 非文字功能图形（WCAG 1.4.11）
        listOf(
            LightAppColors to "light",
            DarkAppColors to "dark",
            QinghuaLightAppColors to "qinghua-light",
            QinghuaDarkAppColors to "qinghua-dark",
        ).forEach { (c, name) ->
            val scrimmedBase = over(Color.Black, 0.10f, c.surface.base)
            val menuSurface = over(c.surface.raised, 0.94f, scrimmedBase)
            assertContrast(c.text.primary, menuSurface, body, "$name shelfMenu 动作文字×玻璃垫底")
            assertContrast(c.status.onError, menuSurface, body, "$name shelfMenu 删除红×玻璃垫底")
            assertContrast(c.accent.text, menuSurface, icon, "$name shelfMenu 陶土图标×玻璃垫底")
        }
    }

    /** 工具自检：纯黑×纯白 = 21:1；同色 = 1:1。 */
    @Test fun contrastRatio_knownAnchors() {
        assertTrue(ColorContrast.ratio(Color.Black, Color.White) > 20.9)
        assertTrue(ColorContrast.ratio(Color.Gray, Color.Gray) < 1.01)
    }
}
