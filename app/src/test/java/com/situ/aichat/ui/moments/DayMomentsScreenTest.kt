package com.situ.aichat.ui.moments

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * T2（补偿覆盖·图纸 §11 D-2）：`DayMomentsScreen` 的**真组合**——标题按 `moment_day_title` 拼出
 * 「{日期} · 朋友圈」、两条分节标签按组是否为空**发射 / 不发射**、空态只在已加载且两组皆空时出现。
 *
 * 为什么需要它：图纸 §7 的装机档在本机模拟器上够不着（无有效 API key ⇒ 造不出角色动态），这条把
 * 「屏能否渲染 / 标题与标签文案是否上屏」从「编译过」升到真组合断言。屏尺寸钉真机档防「元素落可视区外」
 * 的假绿（记忆 `reference-robolectric-screen-size-fake-green`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class DayMomentsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val db: AppDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val momentRepo = MomentRepository(db.momentDao())
    private val characterRepo = mockk<CharacterRepository>()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2026, 9, 1)
    private val dayKey = OurDayKey.keyOf(day)
    private val start = OurDayKey.dayBounds(dayKey, zone).first
    private val vms = mutableListOf<DayMomentsViewModel>()

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private fun seedPost(uuid: String, timestamp: Long) = runBlocking {
        db.momentDao().insertPost(
            MomentPostEntity(uuid = uuid, content = "内容 $uuid", timestamp = timestamp, authorTypeRaw = "character", characterUuid = "c1")
        )
    }

    private fun seedUserLike(postUuid: String, timestamp: Long) = runBlocking {
        db.momentDao().insertLike(MomentLikeEntity(timestamp = timestamp, authorTypeRaw = "user", characterUuid = null, postUuid = postUuid))
    }

    private fun show(char: String = "c1", key: String = dayKey) {
        every { characterRepo.observeAll() } returns flowOf(listOf(CharacterEntity(uuid = "c1", name = "林晚", creationDate = 1)))
        val vm = DayMomentsViewModel(
            SavedStateHandle(mapOf(DayMomentsViewModel.ARG_CHARACTER_UUID to char, DayMomentsViewModel.ARG_DAY_KEY to key)),
            momentRepo, characterRepo, db.userProfileDao(),
        )
        vms += vm
        // 真 App 由 AppRoot 注入触感；测试环境自备，否则 MomentPostCard 的互动条取 CompositionLocal 会抛。
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                DayMomentsScreen(onBack = {}, onOpenPost = {}, viewModel = vm)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `标题按日期拼出 两组都有内容时两条标签都上屏`() {
        seedPost("p-that-day", start + 3_600_000L)
        seedPost("p-earlier", start - 86_400_000L)
        seedUserLike("p-earlier", start + 7_200_000L)
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这一天发的").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("9 月 1 日 · 朋友圈").assertIsDisplayed()
        compose.onNodeWithText("这一天发的").assertIsDisplayed()
        compose.onNodeWithText("更早发的 · 这一天有来往").assertIsDisplayed()
    }

    @Test
    fun `只有当天发的 更早那组标签不发射`() {
        seedPost("p-that-day", start + 3_600_000L)
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这一天发的").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("这一天发的").assertIsDisplayed()
        compose.onNodeWithText("更早发的 · 这一天有来往").assertDoesNotExist()
    }

    @Test
    fun `两组皆空且已加载 出空态两句 不出标签`() {
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这一天的动态不在了").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("这一天的动态不在了").assertIsDisplayed()
        compose.onNodeWithText("可能后来被删掉了。").assertIsDisplayed()
        compose.onNodeWithText("这一天发的").assertDoesNotExist()
        compose.onNodeWithText("更早发的 · 这一天有来往").assertDoesNotExist()
    }

    @Test
    fun `非法日键 标题退回动态 且出空态`() {
        show(key = "2026-9-1")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这一天的动态不在了").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("动态").assertIsDisplayed()
        compose.onNodeWithText("这一天的动态不在了").assertIsDisplayed()
    }
}
