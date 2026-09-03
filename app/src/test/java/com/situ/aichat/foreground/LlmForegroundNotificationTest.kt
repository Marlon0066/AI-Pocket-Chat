package com.situ.aichat.foreground

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.situ.aichat.story.StoryGenPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 灵动岛卷一 T2-2 / T2-3：[LlmForegroundNotification] 三分支的**非 API36 可见面**（Robolectric·sdk 34）。
 *
 * 为什么只断言到 34：`NotificationCompat.ProgressStyle` / `setRequestPromotedOngoing` 是 API 36(BAKLAVA) 专属，
 * Robolectric 够不到——那一档归 `LlmForegroundNotificationDeviceTest`（模拟器真跑）。这里锁的是三分支各自的
 * 标题/正文/可见性/小图标/点击意图/经典进度条这些**跨版本恒成立**的输入。
 *
 * 为什么钉 `qualifiers = "zh-rCN"`：Robolectric 默认取 `values/`（en），而本 app 的目标用户是国行中文。
 * 不钉就会拿 en 值去比 zh 字面量——那不是测出 bug，是测错了区域。en 侧另有一例专门钉（E11 双语成对）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class LlmForegroundNotificationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun storyProgress(overall: Double = 0.45, chapterNumber: Int = 3) = ForegroundActivity.StoryProgress(
        storyId = "story-a",
        overall = overall,
        genPhase = StoryGenPhase.WRITING,
        phaseLabel = "正在撰写正文…",
        shortLabel = "撰写",
        title = "小镇奇谭",
        chapterNumber = chapterNumber,
    )

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    // ── null 分支：备份导出等纯保活（E14）──

    @Test
    fun E14_无槽主_静默常驻通知_不在锁屏暴露() {
        val n = LlmForegroundNotification.build(context, null)
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
        assertTrue("须常驻", (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test
    fun E14_静默态_点击可开app_不再是死通知() {
        assertNotNull("此前两态都没有 contentIntent，点了没反应", LlmForegroundNotification.build(context, null).contentIntent)
    }

    @Test
    fun 静默态_用故事剪影小图标_不是彩色启动图标() {
        assertEquals(com.situ.aichat.R.drawable.ic_notif_story, LlmForegroundNotification.build(context, null).smallIcon.resId)
    }

    // ── StoryProgress 分支 ──

    @Test
    fun 故事态_标题为书名号加章号_正文为阶段词() {
        val n = LlmForegroundNotification.build(context, storyProgress())
        assertEquals("《小镇奇谭》 第 3 章", n.title())
        assertEquals("正在撰写正文…", n.text())
    }

    @Test
    fun 故事态_进度可见_点击带故事深链() {
        val n = LlmForegroundNotification.build(context, storyProgress())
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertNotNull(n.contentIntent)
        assertEquals(com.situ.aichat.R.drawable.ic_notif_story, n.smallIcon.resId)
    }

    @Test
    fun 故事态_绝不显示百分比数字() {
        val n = LlmForegroundNotification.build(context, storyProgress(overall = 0.45))
        assertFalse("四段条+阶段词已达意，数字只暴露「进度是估的」", listOfNotNull(n.title(), n.text()).any { it.contains("%") })
    }

    @Test
    fun E10_API34_故事态退化为经典确定性进度条() {
        val n = LlmForegroundNotification.build(context, storyProgress(overall = 0.45))
        assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(45, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse("确定性进度不该是 indeterminate", n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun E10_API34_进度百分比按总fraction换算并钳位() {
        assertEquals(0, LlmForegroundNotification.build(context, storyProgress(overall = 0.0)).extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(75, LlmForegroundNotification.build(context, storyProgress(overall = 0.75)).extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, LlmForegroundNotification.build(context, storyProgress(overall = 1.0)).extras.getInt(Notification.EXTRA_PROGRESS))
    }

    // ── Typing 分支 ──

    @Test
    fun typing态_标题为角色名_正文为正在输入() {
        val n = LlmForegroundNotification.build(context, ForegroundActivity.Typing("小夏", null, "c1"))
        assertEquals("小夏", n.title())
        assertEquals("正在输入…", n.text())
        assertEquals(com.situ.aichat.R.drawable.ic_notif_typing, n.smallIcon.resId)
    }

    @Test
    fun typing态_锁屏不暴露角色名_走公开版占位() {
        val n = LlmForegroundNotification.build(context, ForegroundActivity.Typing("小夏", null, "c1"))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val pub = assertNotNull("须有锁屏公开版", n.publicVersion).let { n.publicVersion!! }
        assertEquals(Notification.VISIBILITY_PUBLIC, pub.visibility)
        assertFalse("公开版绝不能带角色名", pub.title() == "小夏")
        assertEquals("正在输入…", pub.text())
    }

    @Test
    fun E7_角色无头像_不设largeIcon且不崩() {
        val n = LlmForegroundNotification.build(context, ForegroundActivity.Typing("小夏", avatarPath = null, conversationUuid = "c1"))
        assertNull("无头像不设 largeIcon", n.getLargeIcon())
        assertEquals("小夏", n.title())
    }

    @Test
    fun E7_头像文件丢失_优雅降级不崩() {
        val n = LlmForegroundNotification.build(
            context,
            ForegroundActivity.Typing("小夏", avatarPath = "/not/exist/avatar.png", conversationUuid = "c1"),
        )
        assertEquals("小夏", n.title())
    }

    @Test
    fun typing态_点击回该会话() {
        assertNotNull(LlmForegroundNotification.build(context, ForegroundActivity.Typing("小夏", null, "c1")).contentIntent)
    }

    @Test
    fun typing态_无会话id时退回开app() {
        assertNotNull(
            LlmForegroundNotification.build(context, ForegroundActivity.Typing("小夏", null, conversationUuid = null)).contentIntent,
        )
    }

    @Test
    fun E10_API34_typing态退化为经典不确定进度条() {
        val n = LlmForegroundNotification.build(context, ForegroundActivity.Typing("小夏", null, "c1"))
        assertTrue("等多久由模型说了算，估不出来就不该假估", n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun E11_英文档_typing文案成对落值() {
        val n = LlmForegroundNotification.build(context, ForegroundActivity.Typing("Summer", null, "c1"))
        assertEquals("Typing…", n.text())
        assertEquals("Typing…", n.publicVersion!!.text())
    }

    // ── 三态公共底座 ──

    @Test
    fun 三态_一律常驻且只响一次() {
        listOf(null, storyProgress(), ForegroundActivity.Typing("小夏", null, "c1")).forEach { a ->
            val n = LlmForegroundNotification.build(context, a)
            assertTrue("$a 须常驻", (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
            assertTrue("$a 须只响一次（常驻通知反复刷新不该反复出声）", (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        }
    }

    @Test
    fun 三态_一律走静音的进行中渠道() {
        listOf(null, storyProgress(), ForegroundActivity.Typing("小夏", null, "c1")).forEach { a ->
            assertEquals(
                com.situ.aichat.notification.NotificationChannels.STORY_GENERATING,
                NotificationCompat.getChannelId(LlmForegroundNotification.build(context, a)),
            )
        }
    }
}
