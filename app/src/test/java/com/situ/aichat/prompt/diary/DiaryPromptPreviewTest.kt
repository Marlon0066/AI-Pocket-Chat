package com.situ.aichat.prompt.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * T1-6（图纸 §7·E18/E19）：只读预览的行模型。断言从图纸 §3.7 分类规则独立反推——
 * 占位行标 SLOT、用户改过的那几行标 CUSTOM（**字数行只有 ≠1000 时才算**）、其余 PLAIN；
 * 预览纯函数，不查库不联网。
 */
class DiaryPromptPreviewTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun slots() = DiaryPreviewSlots(
        persona = "〈你的个人简介，设置里填的〉",
        chat = "〈今天和角色们聊的内容〉",
        schedule = "〈今天的日程〉",
        time = "〈生成时的时间〉",
        card = "〈角色卡〉",
        personality = "〈性格〉",
        setup = "〈角色设定〉",
        aboutUser = "〈关于你〉",
        relationship = "〈你们的关系〉",
        memory = "〈TA 记得的事〉",
        mood = "〈TA 此刻的心情〉",
        exchangeChat = "〈今天你们聊的内容〉",
        exchangeSchedule = "〈TA 今天的日程〉",
        characterFallback = "TA",
    )

    private fun mineStrings() = DiaryPromptStrings(
        intro = "你就是「%1\$s」。", requirementsHeader = "## 要求",
        firstPerson = "- 用第一人称（我）书写", styleDefault = "- 诚实、松弛",
        wordCount = "- 全文约 %1\$s 字", emoji = "- 少用 emoji", events = "- 挑两三个瞬间",
        chatMention = "- 提到聊天", innerVoice = "- 多写心里想的", noAi = "- 别暴露 AI",
        shortOk = "- 信息少就短一点", personaHeader = "## 关于我", personaCity = "住在 %1\$s",
        chatSummaryHeader = "## 今日聊天记录摘要", chatGroupHeader = "### 和 %1\$s 聊了",
        scheduleHeader = "## 今日日程安排", currentTime = "当前时间：%1\$s",
        outputOnly = "只输出日记内容本身。", moodHeader = "## 今日心情",
        photosBlind = "附了 %1\$s 张照片", moodOutputRule = "MOOD: <emoji>", userMessage = "写吧",
        guideHeader = "## 我今天最想写的", guideLead = "（回答）", guideEvent = "事：%1\$s",
        guideFeeling = "感：%1\$s", guideUnsaid = "未说：%1\$s", roleMe = "我", roleOther = "对方",
        chatLine = "[%1\$s] %2\$s：%3\$s", calendarLine = "%1\$s %2\$s", eventUntitled = "未命名",
        userFallback = "我",
    )

    private fun exchangeStrings() = DiaryExchangePromptStrings(
        intro = "你是「%1\$s」，性格：%2\$s", setup = "你的角色设定：%1\$s", task = "和 %1\$s 交换日记",
        reqHeader = "## 要求", reqSelf = "- 用第一人称写你自己", reqStyle = "- 写对 %1\$s 的在意",
        reqWords = "- 约 %1\$s 字", reqMoment = "- 挑具体瞬间", reqNotSocial = "- 不是社交动态",
        reqNoPeek = "- 别偷看", reqNoAi = "- 别暴露 AI", moodHeader = "## 你此刻的心情",
        chatHeader = "## 今天你们的聊天摘要", scheduleHeader = "## 你今天的日程",
        personaFrame = "（保留你的声音）", aboutUserHeader = "## 关于 %1\$s",
        relationshipHeader = "## 你和 %1\$s 的关系", phaseLine = "现在你们处在%1\$s。",
        phaseNames = "蜜月期|磨合期|稳定期|倦怠期|突破期", milestoneLine = "%1\$s 起成为%2\$s。",
        memoryHeader = "## 你还记得的", currentTime = "当前时间：%1\$s",
        outputOnly = "只输出日记内容本身。", moodOutputRule = "MOOD: <emoji>",
        userMessage = "写吧", userFallback = "我",
    )

    private fun kindOf(lines: List<PreviewLine>, text: String): PreviewLineKind? =
        lines.firstOrNull { it.text == text }?.kind

    @Test fun `占位行标 SLOT，普通提示词行标 PLAIN`() {
        val lines = DiaryPromptPreview.buildMine(
            mineStrings(), slots(), "小明", DiaryRuleValues(1000, "", "", ""), zone,
        )
        assertEquals(PreviewLineKind.SLOT, kindOf(lines, "〈你的个人简介，设置里填的〉"))
        assertEquals(PreviewLineKind.SLOT, kindOf(lines, "〈今天和角色们聊的内容〉"))
        assertEquals(PreviewLineKind.SLOT, kindOf(lines, "〈今天的日程〉"))
        assertEquals(PreviewLineKind.PLAIN, kindOf(lines, "## 要求"))
        assertEquals(PreviewLineKind.PLAIN, kindOf(lines, "- 用第一人称（我）书写"))
        // 全默认 ⇒ 一行 CUSTOM 都没有。
        assertFalse("未自定义时不该有高亮行", lines.any { it.kind == PreviewLineKind.CUSTOM })
    }

    @Test fun `当前时间行渲染成时间占位，不出现真日期`() {
        val lines = DiaryPromptPreview.buildMine(
            mineStrings(), slots(), "小明", DiaryRuleValues(1000, "", "", ""), zone,
        )
        val text = lines.joinToString("\n") { it.text }
        assertTrue("时间换成占位", text.contains("当前时间：〈生成时的时间〉"))
        assertFalse("绝不出现真年份", text.contains("2023"))
    }

    @Test fun `改过的人称 文风 补充规则标 CUSTOM`() {
        val lines = DiaryPromptPreview.buildMine(
            mineStrings(), slots(), "小明",
            DiaryRuleValues(1000, "用「我」写", "克制一点", "别写天气\n\n多写手上的动作"), zone,
        )
        assertEquals(PreviewLineKind.CUSTOM, kindOf(lines, "- 用「我」写"))
        assertEquals(PreviewLineKind.CUSTOM, kindOf(lines, "- 克制一点"))
        assertEquals(PreviewLineKind.CUSTOM, kindOf(lines, "- 别写天气"))
        assertEquals(PreviewLineKind.CUSTOM, kindOf(lines, "- 多写手上的动作"))
        // 没改的要求行仍是 PLAIN。
        assertEquals(PreviewLineKind.PLAIN, kindOf(lines, "- 少用 emoji"))
    }

    @Test fun `字数行 - 1000 时不标 CUSTOM，改过才标`() {
        val plain = DiaryPromptPreview.buildMine(
            mineStrings(), slots(), "小明", DiaryRuleValues(1000, "", "", ""), zone,
        )
        assertEquals(PreviewLineKind.PLAIN, kindOf(plain, "- 全文约 1000 字"))

        val changed = DiaryPromptPreview.buildMine(
            mineStrings(), slots(), "小明", DiaryRuleValues(1500, "", "", ""), zone,
        )
        assertEquals(PreviewLineKind.CUSTOM, kindOf(changed, "- 全文约 1500 字"))
    }

    @Test fun `TA 的信预览 - 角色卡与各段占位就位，角色名进段头`() {
        val lines = DiaryPromptPreview.buildExchange(
            exchangeStrings(), slots(), "小明", "小满", DiaryRuleValues(1000, "", "", ""), zone,
        )
        assertEquals(PreviewLineKind.SLOT, kindOf(lines, "〈角色卡〉"))
        assertEquals(PreviewLineKind.SLOT, kindOf(lines, "〈TA 记得的事〉"))
        assertEquals(PreviewLineKind.SLOT, kindOf(lines, "〈今天你们聊的内容〉"))
        assertEquals(PreviewLineKind.SLOT, kindOf(lines, "〈TA 今天的日程〉"))
        val text = lines.joinToString("\n") { it.text }
        assertTrue("身份行带角色名", text.contains("你是「小满」"))
        assertTrue("段头带用户昵称", text.contains("## 关于 小明"))
    }

    @Test fun `TA 的信预览 - 无角色时角色名走兜底，不崩不显示空名`() {
        val lines = DiaryPromptPreview.buildExchange(
            exchangeStrings(), slots(), "小明", slots().characterFallback,
            DiaryRuleValues(1000, "", "", ""), zone,
        )
        val text = lines.joinToString("\n") { it.text }
        assertTrue("兜底名上屏", text.contains("你是「TA」"))
        assertFalse("绝不出现空名", text.contains("你是「」"))
    }

    @Test fun `TA 的信预览 - 占位替换后的文风行标 CUSTOM`() {
        val lines = DiaryPromptPreview.buildExchange(
            exchangeStrings(), slots(), "小明", "小满",
            DiaryRuleValues(1200, "", "多写对{用户名}的在意", ""), zone,
        )
        assertEquals(PreviewLineKind.CUSTOM, kindOf(lines, "- 多写对小明的在意"))
        assertEquals(PreviewLineKind.CUSTOM, kindOf(lines, "- 约 1200 字"))
    }

    @Test fun `TA 的信预览 - 身份行带性格占位，角色设定段不缺席（R1 复核修）`() {
        // 🟡-1 回归钉：原实现给 personality/systemPrompt 传空串 ⇒ 身份行渲染成「你是「Yun」，性格：」
        // 尾巴空着，且「你的角色设定：」整段被 isNotEmpty 判空省掉。预览的用途正是「看清自己写的设定
        // 落在哪」，缺这两样等于把最该看的藏了。断言从修订后的 §3.7 规格反推，不照抄实现。
        val lines = DiaryPromptPreview.buildExchange(
            strings = exchangeStrings(), slots = slots(), userName = "小满",
            characterName = "Yun", values = DiaryRuleValues(1000, "", "", ""), zone = zone,
        )
        val texts = lines.map { it.text }
        assertEquals("你是「Yun」，性格：〈性格〉", texts.first())
        assertFalse("身份行不许尾巴空着", texts.first().endsWith("性格："))
        assertTrue("角色设定段必须在场", texts.contains("你的角色设定：〈角色设定〉"))
    }

}
