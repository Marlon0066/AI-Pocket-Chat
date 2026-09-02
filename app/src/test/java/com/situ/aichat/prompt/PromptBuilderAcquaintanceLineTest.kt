package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

/**
 * 相识天数图纸 §6.2 · T2（E11/E13/E14 + 装配接线）：真 [PromptBuilder.buildMessages] 里的相识行——
 * 昵称/无昵称两种人称、字段空即整行缺席、线下见面不出；且相识行必须落在 `<time_context>` 块内、「现在：」行之后。
 * 断言从图纸 §4.2 锁定文案独立反推（不照抄实现）。时区钉死 Asia/Shanghai 保证日期确定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderAcquaintanceLineTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000) // 沪 2025-06-15
    private val ninetyThreeDaysAgo = 1_750_000_000_000L - 93 * 86_400_000L // 沪 2025-03-14
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimeZone() = TimeZone.setDefault(originalTz)

    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private fun character(firstMessageDate: Long? = ninetyThreeDaysAgo, streak: Int = 12) = CharacterEntity(
        uuid = "c1", name = "小雨", creationDate = 0L, firstMessageDate = firstMessageDate, streakCount = streak,
    )

    private fun history(): List<MessageEntity> = listOf(
        MessageEntity(messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user", content = "在干嘛", timestamp = fixedNow.toEpochMilli() - 60_000),
        MessageEntity(messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = "刚忙完~", timestamp = fixedNow.toEpochMilli() - 30_000),
    )

    private fun build(character: CharacterEntity, userProfile: UserProfileEntity?): List<String> =
        PromptBuilder.buildMessages(
            character = character, sortedMessages = history(), userProfile = userProfile,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
        ).map { it.content.orEmpty() }

    @Test
    fun 有昵称_相识行在现在卡的时间锚内且紧随现在行() {
        val contents = build(character(), UserProfileEntity(nickname = "小明"))
        val last = contents.last()
        val line = "你和小明是 2025-03-14 第一次聊天认识的，到今天相识 93 天，最近连续 12 天每天都聊。"
        assertTrue("最后一条 system 含相识行", last.contains(line))
        assertTrue("相识行在 <time_context> 块内", last.indexOf("<time_context>") < last.indexOf(line))
        assertTrue("相识行在 </time_context> 之前", last.indexOf(line) < last.indexOf("</time_context>"))
        assertTrue("相识行在「现在：」行之后", last.indexOf("现在：") < last.indexOf(line))
    }

    @Test
    fun 无用户资料_人称退到对方() {
        val last = build(character(), null).last()
        assertTrue(last.contains("你和对方是 2025-03-14 第一次聊天认识的，到今天相识 93 天，最近连续 12 天每天都聊。"))
    }

    /**
     * R1 🔵-4：**有资料行但昵称为空**（`UserProfileEntity.nickname` 默认就是 `""`，是最常见的真实状态）——
     * 单独钉 `nickname.takeIf { it.isNotEmpty() } ?: USER_LABEL_FALLBACK` 这段映射；上一例走的是 `userProfile == null`
     * 的另一条分支，把 `takeIf` 删掉它仍绿。
     */
    @Test
    fun 有资料行但昵称为空_人称同样退到对方() {
        val last = build(character(), UserProfileEntity(nickname = "", bio = "喜欢夜跑")).last()
        assertTrue(last.contains("你和对方是 2025-03-14 第一次聊天认识的，到今天相识 93 天，最近连续 12 天每天都聊。"))
        assertFalse("绝不出现系统词「用户」", last.contains("你和用户是"))
    }

    /**
     * 图纸 §13（用户拍板 2026-09-03）：真管线里相识行与间隔行**共用同一个称呼**——
     * 有昵称两行都叫昵称、整块不再出现「对方」；这条走 3 小时前的角色消息才有间隔行（<10 分钟不出）。
     */
    @Test
    fun 有昵称_相识行与间隔行同一个称呼() {
        val threeHoursAgo = listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user", content = "在干嘛", timestamp = fixedNow.toEpochMilli() - 3 * 3_600_000L - 60_000),
            MessageEntity(messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = "刚忙完~", timestamp = fixedNow.toEpochMilli() - 3 * 3_600_000L),
        )
        val last = PromptBuilder.buildMessages(
            character = character(), sortedMessages = threeHoursAgo, userProfile = UserProfileEntity(nickname = "小明"),
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
        ).map { it.content.orEmpty() }.last()
        val block = last.substringAfter("<time_context>\n").substringBefore("\n</time_context>").split("\n")
        assertEquals("你和小明是 2025-03-14 第一次聊天认识的，到今天相识 93 天，最近连续 12 天每天都聊。", block[1])
        assertEquals("小明隔了约 3 小时才回你", block[2])
        assertFalse("同一块里不再出现第二种叫法", block.joinToString("\n").contains("对方"))
    }

    /** 昵称为空时两行都退回「对方」（旧文案逐字回归钉）。 */
    @Test
    fun 无昵称_间隔行仍是对方() {
        val threeHoursAgo = listOf(
            MessageEntity(messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = "刚忙完~", timestamp = fixedNow.toEpochMilli() - 3 * 3_600_000L),
        )
        val last = PromptBuilder.buildMessages(
            character = character(), sortedMessages = threeHoursAgo, userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
        ).map { it.content.orEmpty() }.last()
        assertTrue(last.contains("你和对方是 2025-03-14 第一次聊天认识的"))
        assertTrue(last.contains("对方隔了约 3 小时才回你"))
    }

    @Test
    fun 首聊时间为空_全部消息都不含相识行() {
        val all = build(character(firstMessageDate = null), UserProfileEntity(nickname = "小明")).joinToString("\n\n")
        assertFalse(all.contains("第一次聊天认识的"))
    }

    @Test
    fun 线下见面场景_不出相识行() {
        val conv = ConversationEntity(
            uuid = "conv1", title = "测试会话", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = true, currentOfflineSessionId = "sess1",
        )
        val offlineHistory = history().map { it.copy(isOfflineMode = true, offlineSessionId = "sess1") }
        val all = PromptBuilder.buildMessages(
            character = character(), conversation = conv, sortedMessages = offlineHistory,
            userProfile = UserProfileEntity(nickname = "小明"),
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
            scene = PromptScene.OFFLINE_MEETING,
        ).joinToString("\n\n") { it.content.orEmpty() }
        assertTrue("线下仍有时间锚", all.contains("<time_context>"))
        assertFalse("线下不接相识行", all.contains("第一次聊天认识的"))
    }

    @Test
    fun 语音通话场景_相识行照出() {
        // D-5：只有线下见面走 factsOnly 早退分支；语音通话与线上聊天同一条装配路径，相识行照出。
        val all = PromptBuilder.buildMessages(
            character = character(), sortedMessages = history(), userProfile = UserProfileEntity(nickname = "小明"),
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
            scene = PromptScene.VOICE_CALL,
        ).joinToString("\n\n") { it.content.orEmpty() }
        assertTrue(all.contains("你和小明是 2025-03-14 第一次聊天认识的，到今天相识 93 天，最近连续 12 天每天都聊。"))
    }

    /**
     * 装机样张（§6.3）：把整张现在卡打进测试输出，供施工日志贴样。
     * R1 🔵-1：原例只 `println` 无断言 ⇒ 恒绿、零判别力；补上「样张必须是一张完整现在卡」的三条骨架断言
     * （块头尾 + 现在行 + 相识行紧随其后），删掉任一要素即红。
     */
    @Test
    fun 样张_打印整张现在卡() {
        val card = build(character(), UserProfileEntity(nickname = "小明")).last()
        println("──── 现在卡样张（有昵称·相识 93 天·连续 12 天） ────")
        println(card)
        println("──── 样张结束 ────")

        val block = card.substringAfter("<time_context>\n").substringBefore("\n</time_context>").split("\n")
        assertTrue("样张须是完整的现在卡（含时间锚块与尾注）", card.contains("<time_context>") && card.contains("↑ 以上是此刻的真实时间，以它为准。"))
        assertTrue("块内第 1 行 = 现在行", block[0].startsWith("现在：2025-06-15 周日"))
        assertEquals(
            "块内第 2 行 = 相识行",
            "你和小明是 2025-03-14 第一次聊天认识的，到今天相识 93 天，最近连续 12 天每天都聊。",
            block[1],
        )
    }
}
