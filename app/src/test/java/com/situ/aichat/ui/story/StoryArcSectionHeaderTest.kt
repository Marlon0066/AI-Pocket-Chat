package com.situ.aichat.ui.story

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.theme.AIPocketChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 按弧分组小节头（卷三 C4·图纸 §4.4 画面⑤）：头行文案三形态渲染。
 * 「哪一章之前插头」的挂点算法由 [StoryArcSectionAnchorTest] 单独钉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryArcSectionHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    private fun header(section: com.situ.aichat.story.StoryArcPlanning.ArcSection) {
        compose.setContent { AIPocketChatTheme { ArcSectionHeader(section) } }
    }

    @Test fun 完结弧_区间加主题() {
        header(com.situ.aichat.story.StoryArcPlanning.ArcSection(1, 12, "雾港来信", ongoing = false))
        compose.onNodeWithText(app.getString(R.string.story_arc_section_format, 1, 12, "雾港来信")).assertIsDisplayed()
    }

    @Test fun 完结弧_无主题时不留尾巴分隔符() {
        header(com.situ.aichat.story.StoryArcPlanning.ArcSection(3, 9, null, ongoing = false))
        // 主题为空 → 文案末尾的「 · 」被裁掉，不留「第 3–9 章 ·」这种断尾。
        compose.onNodeWithText(app.getString(R.string.story_arc_section_format, 3, 9, "").trimEnd(' ', '·'))
            .assertIsDisplayed()
    }

    @Test fun 进行中弧_带主题() {
        header(com.situ.aichat.story.StoryArcPlanning.ArcSection(13, null, "码头档案室的秘密", ongoing = true))
        compose.onNodeWithText(app.getString(R.string.story_arc_section_ongoing_format, 13, "码头档案室的秘密"))
            .assertIsDisplayed()
    }

    @Test fun 进行中弧_无主题走退化文案_不重复进行中() {
        header(com.situ.aichat.story.StoryArcPlanning.ArcSection(13, null, null, ongoing = true))
        val expected = app.getString(R.string.story_arc_section_ongoing_plain, 13)
        compose.onNodeWithText(expected).assertIsDisplayed()
    }
}

/**
 * 小节头挂点算法（[arcHeadAnchors]）：列表最新在上，每段弧的头挂在该段**实际存在的最大章号**那一章上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryArcSectionAnchorTest {

    private fun chapters(vararg numbers: Int) = numbers.map {
        StoryChapterEntity(id = "ch$it", storyId = "s1", chapterNumber = it, content = "正文")
    }

    @Test fun 无简史无进行中弧_零挂点() {
        val map = arcHeadAnchors(chapters(1, 2, 3), StoryEntity(id = "s1"))
        org.junit.Assert.assertTrue("与分组前完全一致", map.isEmpty())
    }

    @Test fun story为null或空章节_零挂点() {
        org.junit.Assert.assertTrue(arcHeadAnchors(chapters(1), null).isEmpty())
        org.junit.Assert.assertTrue(arcHeadAnchors(emptyList(), StoryEntity(id = "s1", arcHistory = "第1–8章·甲")).isEmpty())
    }

    @Test fun 两段简史_各挂在区间最大章上() {
        val story = StoryEntity(id = "s1", arcHistory = "第1–4章·甲\n第5–8章·乙")
        val map = arcHeadAnchors(chapters(1, 2, 3, 4, 5, 6, 7, 8), story)
        org.junit.Assert.assertEquals(2, map.size)
        org.junit.Assert.assertEquals("甲", map["ch4"]?.theme)
        org.junit.Assert.assertEquals("乙", map["ch8"]?.theme)
    }

    @Test fun 进行中弧_挂在最新章上且无末章() {
        val story = StoryEntity(
            id = "s1",
            arcHistory = "第1–4章·甲",
            currentArcStartChapter = 5,
            currentArc = "新一段旅程",
        )
        val map = arcHeadAnchors(chapters(1, 2, 3, 4, 5, 6), story)
        org.junit.Assert.assertEquals(2, map.size)
        val ongoing = map["ch6"]!!
        org.junit.Assert.assertTrue(ongoing.ongoing)
        org.junit.Assert.assertEquals(5, ongoing.start)
        org.junit.Assert.assertNull(ongoing.endInclusive)
    }

    @Test fun 区间内一章都不存在_不挂空头() {
        // 简史说第 20–30 章，但书里只有 1–4 章（重写/取消收尾后的错峰）→ 不渲染一个底下空无一物的头。
        val story = StoryEntity(id = "s1", arcHistory = "第20–30章·错峰")
        org.junit.Assert.assertTrue(arcHeadAnchors(chapters(1, 2, 3, 4), story).isEmpty())
    }

    @Test fun 区间部分存在_挂在存在的最大那章() {
        val story = StoryEntity(id = "s1", arcHistory = "第1–99章·超长区间")
        val map = arcHeadAnchors(chapters(1, 2, 3), story)
        org.junit.Assert.assertEquals(1, map.size)
        org.junit.Assert.assertEquals("超长区间", map["ch3"]?.theme)
    }
}
