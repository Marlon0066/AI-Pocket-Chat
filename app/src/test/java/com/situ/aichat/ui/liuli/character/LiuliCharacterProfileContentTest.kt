package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.profile.CharacterWalletActivity
import com.situ.aichat.profile.CompanionStats
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.ui.character.PromiseCardState
import com.situ.aichat.ui.character.ScheduleCardState
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：资料页内容层·上半（图纸 2026-09-06 卷四 §8 C4a · §4.3 T3）。
 *
 * 钉：头图名 / 关系胶囊（E12 空里程碑 → 「初识」）/ 统计卡列数（E11）/ 动作排四个回调各恰一次 /
 * 分段条默认落「近况」/ 近况段各卡的条件（亲友账卡两空不渲染、日程卡三态与 Hidden、成长日志恒在）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliCharacterProfileContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableMapOf<String, Int>()
    private val milestones = mutableStateOf(emptyList<MilestoneEntity>())
    private val scheduleEnabled = mutableStateOf(true)
    private val scheduleCard = mutableStateOf<ScheduleCardState>(ScheduleCardState.Loading)
    private val withCountdown = mutableStateOf(false)

    private fun tap(key: String): () -> Unit = { taps[key] = (taps[key] ?: 0) + 1 }

    /** 组标题（挂了 heading()）——动作排的字与卡的组标题会重名（「今日行程」）。 */
    private fun header(text: String) = compose.onNode(hasText(text) and isHeading())

    private val character = CharacterEntity(
        uuid = "u1",
        name = "林晚",
        creationDate = 0L,
        gender = "女",
        occupation = "插画师",
        streakCount = 12,
    )

    private fun show(
        warningShown: Boolean = true,
        statusBarTop: Dp = 0.dp,
        interests: List<DynamicInterest> = emptyList(),
        listState: LazyListState? = null,
    ) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliCharacterProfileContent(
                        character = character,
                        stats = CompanionStats(
                            companionDays = 128,
                            messageCount = 3412,
                            characterCount = 1,
                            memoryEntryCount = 86,
                            offlineMeetingCount = 3,
                        ),
                        milestones = milestones.value,
                        impressionTags = emptyList(),
                        receivedGifts = emptyList(),
                        growthLog = emptyList(),
                        scheduleEnabled = scheduleEnabled.value,
                        scheduleCard = scheduleCard.value,
                        nextMeetingChip = if (withCountdown.value) {
                            { Text("倒数条") }
                        } else {
                            null
                        },
                        promiseCard = PromiseCardState.EMPTY,
                        offlineSessions = emptyList(),
                        retryingOfflineSessions = emptySet(),
                        memoryStats = StructuredMemoryStats.Result(null, null, null, 0, 0),
                        structuredMemory = StructuredMemory(),
                        memorySummary = "",
                        memoryGuardBlocked = false,
                        organizingMemory = false,
                        dynamicInterests = interests,
                        personalitySpectrum = PersonalitySpectrum.NEUTRAL,
                        relationshipQuality = RelationshipQuality.INITIAL,
                        wallet = null,
                        walletActivity = CharacterWalletActivity.EMPTY,
                        walletHasNews = false,
                        onBack = tap("back"),
                        onEditCharacter = tap("edit"),
                        onOpenSchedule = tap("schedule"),
                        onOpenPromises = tap("promises"),
                        onOpenOurDays = tap("ourDays"),
                        onOpenStarfield = tap("starfield"),
                        onOpenOfflineMeetings = tap("offlineMeetings"),
                        onEditMemory = tap("editMemory"),
                        onRetrySchedule = tap("retry"),
                        onRetryOfflineFallback = { },
                        onOrganizeMemoryNow = tap("organize"),
                        onSaveSalary = { _, _ -> },
                        walletWarningShown = { warningShown },
                        onMarkWalletWarningShown = tap("markShown"),
                        listState = listState ?: androidx.compose.foundation.lazy.rememberLazyListState(),
                        statusBarTop = statusBarTop,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 头图显名与副行且里程碑为空时胶囊落初识() {
        show()
        compose.onNodeWithText("林晚").assertExists()
        compose.onNodeWithText("初识").assertExists()
        // 副行 = 身份行 · 职业（本例没生日 → 只有性别）。
        compose.onNodeWithText("女 · 插画师").assertExists()
    }

    @Test fun 有里程碑时胶囊显最晚那条的标签与时期() {
        milestones.value = listOf(
            MilestoneEntity(uuid = "m1", characterUuid = "u1", relationshipName = "朋友", establishedDate = 100L),
            MilestoneEntity(uuid = "m2", characterUuid = "u1", relationshipName = "恋人", establishedDate = 900L, phase = "蜜月期"),
        )
        show()
        compose.onNodeWithText("恋人 · 蜜月期").assertExists()
        compose.onNodeWithText("初识").assertDoesNotExist()
    }

    @Test fun 统计卡五列齐全且值不带单位() {
        show()
        listOf("相识", "消息", "记忆", "见面", "连续").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertExists()
        }
        compose.onNodeWithText("128", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("🔥12", useUnmergedTree = true).assertExists()
    }

    @Test fun 动作排四个出口各恰一次() {
        show()
        listOf("今日行程" to "schedule", "我们的约定" to "promises", "我们的日子" to "ourDays", "记忆星空" to "starfield")
            .forEach { (cd, _) ->
                compose.onNodeWithContentDescription(cd).performScrollTo().performClick()
                compose.waitForIdle()
            }
        assertEquals(1, taps["schedule"])
        assertEquals(1, taps["promises"])
        assertEquals(1, taps["ourDays"])
        assertEquals(1, taps["starfield"])
        assertEquals(null, taps["back"])
    }

    @Test fun 编辑圆钮在顶栏且恰回调一次() {
        show()
        compose.onNodeWithContentDescription("编辑角色").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["edit"])
    }

    @Test fun 默认落近况段且成长日志恒在() {
        show()
        compose.onNodeWithText("近况").assertIsSelected()
        header("成长日志").performScrollTo().assertExists()
    }

    @Test fun 亲友账卡两样都空时整卡不渲染() {
        show()
        header("林晚 眼里的你").assertDoesNotExist()
    }

    @Test fun 日程卡三态与关掉时不渲染() {
        show()
        // Loading 态：卡在、文案是「日程正在整理中」。
        header("今日行程").performScrollTo().assertExists()

        compose.runOnIdle { scheduleCard.value = ScheduleCardState.Failed }
        compose.waitForIdle()
        compose.onNodeWithText("重试").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, taps["retry"])

        compose.runOnIdle { scheduleCard.value = ScheduleCardState.Hidden }
        compose.waitForIdle()
        header("今日行程").assertDoesNotExist()

        compose.runOnIdle {
            scheduleCard.value = ScheduleCardState.Loading
            scheduleEnabled.value = false
        }
        compose.waitForIdle()
        header("今日行程").assertDoesNotExist()
    }

    @Test fun 倒数条只在有下一场约定时渲染() {
        show()
        compose.onNodeWithText("倒数条").assertDoesNotExist()
        compose.runOnIdle { withCountdown.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("倒数条").performScrollTo().assertExists()
    }

    /**
     * 切段 = 节显隐（同暖陶 D-A）。这里走「近况 → 资料」：**「经历」段渲染不了**——它含借用的
     * `StarfieldEntryCard` / `ProfileOurDaysCard`，两者都吃 `hiltViewModel()` 默认形参
     * （记忆 `reference-robolectric-hiltviewmodel-blocks-fullscreen`）；那一段的卡各自在
     * `LiuliProfileStoryCardsTest` 里组件级测，整段接线留装机批（§11 D-17）。
     */
    @Test fun 切段时上一段的卡让位() {
        show()
        header("成长日志").performScrollTo().assertExists()
        compose.onNodeWithText("资料").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("资料").assertIsSelected()
        header("成长日志").assertDoesNotExist()
        // 资料段的卡按暖陶同序在位。
        header("兴趣热度").performScrollTo().assertExists()
        header("林晚 的钱包").performScrollTo().assertExists()
    }

    /** E15 首次半边（复核 R1 🟡-8）：没提示过 → 先弹警告、面板不开；点「继续」→ 记一次已提示 + 面板开。 */
    @Test fun 资料段钱包首次编辑先弹警告点继续才开面板() {
        show(warningShown = false)
        compose.onNodeWithText("资料").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("编辑钱包").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("手动设置月薪").assertExists()
        compose.onNodeWithText("保存").assertDoesNotExist()
        compose.onNodeWithText("继续").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["markShown"])
        compose.onNodeWithText("保存").assertExists()
    }

    @Test fun 资料段钱包卡编辑钮走首次警告闩() {
        show()
        compose.onNodeWithText("资料").performClick()
        compose.waitForIdle()
        // 本例 walletWarningShown = true（已提示过）→ 直接开面板，不弹警告。
        compose.onNodeWithContentDescription("编辑钱包").performScrollTo().performClick()
        compose.waitForIdle()
        // 面板真开了：只有面板里才有「保存」。
        compose.onNodeWithText("保存").assertExists()
    }

    /**
     * E13 / A-11（复核 R1 🔴-1·装机 `v4_12` 坐实）：收起态切段，新段首项顶边必须落在覆盖区
     * = **真状态栏** + 44 收起顶栏 + 56 subBar 之下——施工版拿 0 顶替状态栏，首项被玻璃盖住一截。
     * 本例注入 24dp 状态栏、500dp 高视口（资料段给 12 条兴趣撑高，切段后列表才不回弹）；
     * 断言值 124 从规格反推（24 + 44 + 56），不回读实现。
     */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h500dp")
    fun 收起态切段后新段首项落在覆盖区之下() {
        val listState = LazyListState()
        show(
            statusBarTop = 24.dp,
            interests = (1..12).map { DynamicInterest(name = "兴趣$it", heat = 90 - it) },
            listState = listState,
        )
        // 先把头图滚出去（收起）：分段条住进玻璃顶栏，纸面那枚已滚出视口。
        compose.runOnIdle { runBlocking { listState.scrollToItem(1) } }
        compose.waitForIdle()
        // 500dp 视口里纸面分段条还没滚出去 → 屏上有两枚「资料」，点玻璃顶栏里那枚（靠顶的）。
        compose.onNode(hasText("资料") and SemanticsMatcher("在玻璃顶栏里") { it.boundsInRoot.top < 200f }).performClick()
        compose.waitForIdle()
        val top = header("兴趣热度").getUnclippedBoundsInRoot().top
        val cover = 24.dp + LiuliPageGeometry.compactBar + LiuliPageGeometry.subBar
        assertEquals(124.dp, cover)
        assertTrue("新段首项顶边 $top 须 ≥ 覆盖区 $cover", top >= cover - 0.5.dp)
    }
}
