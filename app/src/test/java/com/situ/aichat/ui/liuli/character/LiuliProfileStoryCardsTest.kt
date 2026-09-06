package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.ui.character.PromiseCardState
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.situ.aichat.offline.OfflineMeetingSession

/**
 * T2：资料页「经历」段各卡（图纸 2026-09-06 卷四 §8 C4b · §5 E17 / E18）。
 *
 * 组件级而非整段——那一段含借用的 `StarfieldEntryCard` / `ProfileOurDaysCard`，两者吃
 * `hiltViewModel()` 默认形参，Robolectric 里起不来（§11 D-17）。
 *
 * 钉：约定卡（计数徽章 / 进行中预览 / 空进行中文案 / 页脚回调）· 见面回忆（空态两行 / 有场次时卡头
 * 「全部 N 次」可点）· 共同记忆（全空一句提示 / 护栏条与「立即整理」E18 / 原文折叠 4 行与展开）·
 * 关系历程（空态两行 / 节点合成一停 a11y）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliProfileStoryCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableMapOf<String, Int>()

    private fun tap(key: String): () -> Unit = { taps[key] = (taps[key] ?: 0) + 1 }

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun promise(uuid: String, content: String, status: String = PromiseStatus.OPEN) = PromiseEntity(
        uuid = uuid,
        characterUuid = "u1",
        content = content,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        statusRaw = status,
        resolvedAtMillis = if (status == PromiseStatus.OPEN) null else 2_000L,
    )

    @Test fun 约定卡显计数与进行中预览且页脚恰回调一次() {
        host {
            LiuliProfilePromisesCard(
                state = PromiseCardState(
                    openPreview = listOf(promise("p1", "一起去看海")),
                    openCount = 2,
                    latestResolved = null,
                    totalCount = 3,
                ),
                nowMillis = 5_000L,
                onOpenAll = tap("openAll"),
            )
        }
        compose.onNodeWithText("一起去看海").assertExists()
        compose.onNodeWithText("查看全部 3 条 ›").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["openAll"])
    }

    @Test fun 约定卡进行中为零时给一句文案() {
        host {
            LiuliProfilePromisesCard(
                state = PromiseCardState(
                    openPreview = emptyList(),
                    openCount = 0,
                    latestResolved = promise("p2", "陪我过生日", PromiseStatus.FULFILLED),
                    totalCount = 1,
                ),
                nowMillis = 5_000L,
                onOpenAll = {},
            )
        }
        compose.onNodeWithText("陪我过生日").assertExists()
    }

    @Test fun 见面回忆空态两行且不显全部入口() {
        host { LiuliProfileMeetingsCard(sessions = emptyList(), onOpenAll = tap("all"), onRetryFallback = {}) }
        compose.onNodeWithText("全部 0 次 ›").assertDoesNotExist()
        assertEquals(null, taps["all"])
    }

    /** E17（复核 R1 🟡-8 补）：兜底摘要的见面卡带「简版」重试徽标 → 点它回调带会话 id。 */
    @Test fun 见面回忆兜底摘要可重试且回调带会话id() {
        val retried = mutableListOf<String>()
        host {
            LiuliProfileMeetingsCard(sessions = listOf(fallbackSession()), onOpenAll = {}, onRetryFallback = { retried += it })
        }
        compose.onNodeWithText("简版").performClick()
        compose.waitForIdle()
        assertEquals(listOf("s1"), retried)
    }

    /** E17 重试中：徽标换「生成中」、不再有「简版」可点。 */
    @Test fun 见面回忆重试中换生成中() {
        host {
            LiuliProfileMeetingsCard(
                sessions = listOf(fallbackSession()),
                onOpenAll = {},
                onRetryFallback = {},
                retryingSessionIds = setOf("s1"),
            )
        }
        compose.onNodeWithText("生成中").assertExists()
        compose.onNodeWithText("简版").assertDoesNotExist()
    }

    private fun fallbackSession() = OfflineMeetingSession(
        id = "s1",
        location = "美术馆",
        activity = "看展",
        startMillis = 1_000L,
        durationText = "1 小时",
        finalMood = "warm",
        summaryText = "简版摘要",
        conversationUuid = "c1",
        initiatedByUser = true,
        usedFallbackSummary = true,
    )

    @Test fun 共同记忆全空时一句提示() {
        host {
            LiuliProfileMemoryCard(
                stats = StructuredMemoryStats.Result(null, null, null, 0, 0),
                memory = StructuredMemory(),
                memorySummary = "",
            )
        }
        compose.onNodeWithText("还没有共同记忆，多聊聊就会有了").assertExists()
    }

    /** E18：遇阻 → 状态条 + 「立即整理」；忙碌 → 换文案且禁点。 */
    @Test fun 记忆护栏遇阻时给立即整理忙碌时禁点() {
        host {
            LiuliProfileMemoryCard(
                stats = StructuredMemoryStats.Result(null, null, null, 0, 0),
                memory = StructuredMemory(),
                memorySummary = "记住了你喜欢下雨天",
                guardBlocked = true,
                onOrganizeNow = tap("organize"),
            )
        }
        compose.onNodeWithText("立即整理").performClick()
        compose.waitForIdle()
        assertEquals(1, taps["organize"])
    }

    /** E18 忙碌半边（复核 R1 🟡-8：原例名里的「忙碌时禁点」从未渲染 organizing = true）。 */
    @Test fun 记忆护栏忙碌时换文案且不再有立即整理() {
        host {
            LiuliProfileMemoryCard(
                stats = StructuredMemoryStats.Result(null, null, null, 0, 0),
                memory = StructuredMemory(),
                memorySummary = "记住了你喜欢下雨天",
                guardBlocked = true,
                organizing = true,
                onOrganizeNow = tap("organize"),
            )
        }
        compose.onNodeWithText("正在整理…").assertExists()
        compose.onNodeWithText("立即整理").assertDoesNotExist()
        assertEquals(null, taps["organize"])
    }

    @Test fun 记忆原文折叠四行超出才给展开钮() {
        val long = (1..8).joinToString("\n") { "第 $it 条记忆" }
        host {
            LiuliProfileMemoryCard(
                stats = StructuredMemoryStats.Result(null, null, null, 0, 0),
                memory = StructuredMemory(),
                memorySummary = long,
            )
        }
        compose.onNodeWithText("第 4 条记忆").assertExists()
        compose.onNodeWithText("第 5 条记忆").assertDoesNotExist()
        compose.onNodeWithText("展开全部").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("第 8 条记忆").assertExists()
    }

    @Test fun 关系历程空态两行() {
        host { LiuliProfileTimelineCard(milestones = emptyList()) }
        compose.onNodeWithText("暂无关系记录").assertExists()
    }

    @Test fun 关系历程节点合成一停() {
        host {
            LiuliProfileTimelineCard(
                milestones = listOf(
                    MilestoneEntity(
                        uuid = "m1",
                        characterUuid = "u1",
                        relationshipName = "恋人",
                        establishedDate = 1_700_000_000_000L,
                        phase = "蜜月期",
                        triggerTypeRaw = "aiAutomatic",
                    ),
                ),
            )
        }
        // 节点是 clearAndSetSemantics 的一停：「关系名，相位，日期，来源」。
        compose.onNodeWithText("恋人").assertDoesNotExist()
    }
}
