package com.situ.aichat.seam

import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.Notifier
import com.situ.aichat.relationship.MilestoneCelebrationNotifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卷一 C8「里程碑庆祝」行为测试（图纸 §7 T2-C6/J7）：见面中不庆祝（Toast 盖不住恒暗剧场、通知同样穿帮），
 * **数据照记**（本测只管庆祝出口）；非见面照常走前台/后台出口（N1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MilestoneMeetingGateTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var conversationDao: ConversationDao
    private lateinit var settingsRepo: SettingsRepository

    private fun convo(inMeeting: Boolean) = ConversationEntity(
        uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = inMeeting, currentOfflineSessionId = if (inMeeting) "sess-1" else null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        mockkObject(Notifier)
        every { Notifier.postMilestone(any(), any(), any(), any()) } returns true
        conversationDao = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(milestoneNotificationEnabled = true)
    }

    @After
    fun tearDown() {
        unmockkObject(Notifier)
        Dispatchers.resetMain()
    }

    private suspend fun achieve() = MilestoneCelebrationNotifier(context, settingsRepo, conversationDao)
        .onMilestoneAchieved(
            characterUuid = "c1", characterName = "小雨",
            historyNames = listOf("普通朋友"), newName = "好朋友", triggerTypeRaw = "aiAutomatic",
        )

    @Test
    fun 见面中_不庆祝() = runTest {
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = true)
        achieve()
        verify(exactly = 0) { Notifier.postMilestone(any(), any(), any(), any()) }
    }

    /** N1 对照：非见面 + App 非前台（Robolectric 默认）→ 照常发系统通知。 */
    @Test
    fun 非见面_照常庆祝() = runTest {
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = false)
        achieve()
        verify(exactly = 1) { Notifier.postMilestone(any(), "c1", "小雨", any()) }
    }
}
