package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.moments.MomentsHubState
import com.situ.aichat.ui.ourdays.CellModel
import com.situ.aichat.ui.ourdays.OurDaysStripState
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * T2-7：动态枢纽各卡（图纸 2026-09-06 卷三 §7 T2-7 · §4.5 · E11）。
 *
 * 卡与条各自可测（都是无 VM 的纯呈现件）：API 横幅显隐、圈子条未读丸 / 空预览 / 两条「作者：正文」、
 * 日记与故事卡的徽标与副文三态、宠物条尾句三态、日子条七格与今日标。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliMomentsHubCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private var taps = 0

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun post(uuid: String, content: String, characterUuid: String?) = MomentPostEntity(
        uuid = uuid,
        authorTypeRaw = if (characterUuid == null) "user" else "character",
        characterUuid = characterUuid,
        content = content,
        timestamp = now,
    )

    private fun character(uuid: String, name: String) =
        CharacterEntity(uuid = uuid, name = name, creationDate = now)

    @Test fun API横幅只在未配置时出现() {
        show { LiuliApiMissingBanner() }
        compose.onNodeWithText("未配置 API，自动发布动态已暂停。").assertIsDisplayed()
    }

    @Test fun 圈子条空预览与未读丸() {
        show { LiuliCircleStrip(state = MomentsHubState(unreadCount = 5), onClick = { taps++ }) }
        compose.onNodeWithText("看看大家的新动态").assertIsDisplayed()
        compose.onNodeWithText("还没有动态，快来发第一条吧").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithText("圈子").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test fun 圈子条显两条作者加正文的预览() {
        val yun = character("c1", "云野")
        show {
            LiuliCircleStrip(
                state = MomentsHubState(
                    previewPosts = listOf(post("p1", "今天天气真好", "c1"), post("p2", "我也出门了", null)),
                    charactersByUuid = mapOf("c1" to yun),
                ),
                onClick = {},
            )
        }
        compose.onNodeWithText("云野：今天天气真好").assertIsDisplayed()
        compose.onNodeWithText("我：我也出门了").assertIsDisplayed()
        compose.onNodeWithText("还没有动态，快来发第一条吧").assertDoesNotExist()
    }

    @Test fun 日记卡副文与未读徽标() {
        val entry = DiaryEntryEntity(uuid = "d1", content = "今天去了海边", timestamp = now, moodEmoji = "🌊")
        show { LiuliDiaryCard(state = MomentsHubState(latestDiary = entry, diaryUnreadCount = 2), onClick = { taps++ }) }
        compose.onNodeWithText("🌊 今天去了海边").assertIsDisplayed()
        compose.onNodeWithText("2 new").assertIsDisplayed()
        compose.onNodeWithText("日记").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test fun 日记卡无日记走默认描述() {
        show { LiuliDiaryCard(state = MomentsHubState(), onClick = {}) }
        compose.onNodeWithText("记录每一天的故事").assertIsDisplayed()
    }

    @Test fun 故事卡三态各走各的() {
        show {
            Column {
                LiuliStoryCard(state = MomentsHubState(), onClick = {})
                LiuliStoryCard(
                    state = MomentsHubState(latestStory = StoryEntity(id = "s1", title = "夏日", createdAt = now, updatedAt = now)),
                    onClick = {},
                )
            }
        }
        // 无故事 → 默认描述；有故事但零章 → 「尚未生成章节」。
        compose.onNodeWithText("你的 AI 连载小说").assertIsDisplayed()
        compose.onNodeWithText("尚未生成章节").assertIsDisplayed()
    }

    @Test fun 宠物条尾句三态() {
        // 「都好着呢」/「{宠名} {状态}」两态都要一只真宠物：饥饿 0、清洁 100 = 舒坦；饥饿 90 = 「饿了」。
        val happy = CharacterPetEntity(uuid = "p1", name = "团子", hunger = 0, cleanliness = 100, happiness = 90, health = 100)
        val hungry = happy.copy(uuid = "p2", name = "圆圆", hunger = 95, happiness = 20)
        show {
            Column {
                LiuliPetHubStrip(state = MomentsHubState(), onClick = { taps++ })
                LiuliPetHubStrip(state = MomentsHubState(petCount = 2, petAllWell = true, petGlance = happy), onClick = {})
                LiuliPetHubStrip(state = MomentsHubState(petCount = 3, petAllWell = false, petGlance = hungry), onClick = {})
            }
        }
        compose.onNodeWithContentDescription("宠物，还没有，一起养一只宠物吧").assertIsDisplayed()
        compose.onNodeWithContentDescription("宠物，2 只小家伙，都好着呢").assertIsDisplayed()
        // 第三态：非「都好」时报「{宠名} {状态}」——状态串由 `petStatusRes` 决定，这里只钉宠名确实进了尾句。
        compose.onNodeWithText("圆圆", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("宠物，还没有，一起养一只宠物吧").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test fun 日子条七格加今日标与空态尾句() {
        val today = LocalDate.now()
        val week = (0..6).map { i ->
            val date = today.minusDays(today.dayOfWeek.value.toLong() - 1).plusDays(i.toLong())
            CellModel(
                date = date,
                key = date.toString(),
                inPeriod = true,
                isToday = date == today,
                isFuture = date.isAfter(today),
                heatLevel = 0,
            )
        }
        show { LiuliOurDaysStripContent(state = OurDaysStripState(week = week), onClick = { taps++ }) }
        week.forEach { compose.onNodeWithText("${it.date.dayOfMonth}").assertIsDisplayed() }
        compose.onNodeWithText("还没有一起的日子").assertIsDisplayed()
        compose.onNodeWithText("和 TA 聊过之后，每一天都会记在这里").assertIsDisplayed()
    }
}
