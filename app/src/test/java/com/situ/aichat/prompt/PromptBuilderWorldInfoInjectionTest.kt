package com.situ.aichat.prompt

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.worldbook.AtDepthInjection
import com.situ.aichat.worldbook.WorldInfoActivationResult
import com.situ.aichat.worldbook.WorldInfoDiagnostics
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WB4 世界书四锚点注入 T2（契约 §4.3）：前/后桶夹住角色身份段、后置桶在历史之后独立成段、
 * @depth 按倒数下标与 role 落位、宏解析、以及 **null/空结果 = 字节级零回归**（§5 强耦合红线的守护）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderWorldInfoInjectionTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)

    private fun world(
        before: String = "",
        after: String = "",
        suffix: String = "",
        atDepth: List<AtDepthInjection> = emptyList(),
    ) = WorldInfoActivationResult(
        before = before,
        after = after,
        suffix = suffix,
        atDepth = atDepth,
        newTimedStates = emptyList(),
        expiredTimedStates = emptyList(),
        diagnostics = WorldInfoDiagnostics(emptyList(), emptyList(), emptyList(), 0, 0),
    )

    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private fun build(worldInfo: WorldInfoActivationResult?): List<ChatMessageDto> {
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val messages = listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "第一句聊天", timestamp = 1L),
            MessageEntity(messageUUID = "a1", conversationUuid = "c1", roleRaw = "assistant", content = "第二句聊天", timestamp = 2L),
            MessageEntity(messageUUID = "u2", conversationUuid = "c1", roleRaw = "user", content = "第三句聊天", timestamp = 3L),
        )
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = messages,
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings(),
            worldInfo = worldInfo,
            now = fixedNow,
        )
    }

    @Test
    fun 前后桶_夹住角色身份段() {
        val msgs = build(world(before = "【世界观】此界魔法真实存在", after = "【补充设定】精灵一族惧怕生铁"))
        val sys = msgs.first().content.orEmpty()
        val identityLine = strings().s(R.string.pb_ident_name, "小雨")
        val iBefore = sys.indexOf("【世界观】此界魔法真实存在")
        val iIdentity = sys.indexOf(identityLine)
        val iAfter = sys.indexOf("【补充设定】精灵一族惧怕生铁")
        assertTrue("三段都必须在系统提示里", iBefore >= 0 && iIdentity >= 0 && iAfter >= 0)
        assertTrue("前桶必须在身份段之前", iBefore < iIdentity)
        assertTrue("后桶必须在身份段之后", iAfter > iIdentity)
    }

    @Test
    fun 后置桶_历史之后独立系统消息() {
        val msgs = build(world(suffix = "【当下氛围】夜风微凉"))
        val iSuffix = msgs.indexOfFirst { it.content == "【当下氛围】夜风微凉" }
        val iLastHistory = msgs.indexOfLast { it.content.orEmpty().contains("第三句聊天") }
        assertTrue("后置桶必须存在", iSuffix >= 0)
        assertEquals("system", msgs[iSuffix].role)
        assertTrue("后置桶必须在最后一条历史之后", iSuffix > iLastHistory)
    }

    @Test
    fun atDepth_倒数第一条前插入_role映射assistant() {
        val msgs = build(world(atDepth = listOf(AtDepthInjection(depth = 1, role = 2, content = "【幕后】她记得这件事"))))
        val i = msgs.indexOfFirst { it.content == "【幕后】她记得这件事" }
        val iLast = msgs.indexOfFirst { it.content.orEmpty().contains("第三句聊天") }
        assertTrue(i >= 0)
        assertEquals("assistant", msgs[i].role)
        assertEquals("depth=1 应插在最后一条历史正前方", iLast - 1, i)
    }

    @Test
    fun atDepth_深度0在历史末尾之后_默认system() {
        val msgs = build(world(atDepth = listOf(AtDepthInjection(depth = 0, role = 0, content = "【现场】就在此刻"))))
        val i = msgs.indexOfFirst { it.content == "【现场】就在此刻" }
        val iLast = msgs.indexOfFirst { it.content.orEmpty().contains("第三句聊天") }
        assertEquals("depth=0 应紧跟最后一条历史", iLast + 1, i)
        assertEquals("system", msgs[i].role)
    }

    @Test
    fun 宏解析_char宏换成角色名() {
        val msgs = build(world(before = "{{char}}的秘密档案：她怕黑"))
        assertTrue(msgs.first().content.orEmpty().contains("小雨的秘密档案：她怕黑"))
    }

    @Test
    fun 宏解析_now宏换成当前时间() {
        // 批2 2-6：契约 D6/§4.5 承诺 {{user}}/{{char}}/{{now}} 三宏；修复前只产前两个，{{now}} 原样漏出。
        val expected = com.situ.aichat.util.DateFormatters.yearMonthDayHourMinute(fixedNow.toEpochMilli())
        val msgs = build(world(before = "当前时间是{{now}}，据此判断作息"))
        val content = msgs.first().content.orEmpty()
        assertTrue("{{now}} 必须替换为格式化时间", content.contains("当前时间是$expected"))
        assertTrue("原始宏不得漏出", !content.contains("{{now}}"))
    }

    @Test
    fun 零回归_null与全空结果均与无世界书字节级一致() {
        val baseline = build(null)
        assertEquals("全空激活结果必须等价于无世界书", baseline, build(world()))
    }

    @Test
    fun 大小写无关宏解析_大写CHAR_USER_NOW全部替换() {
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val user = com.situ.aichat.data.local.entity.UserProfileEntity(nickname = "阿檀")
        val msgs = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = emptyList(),
            userProfile = user,
            appSettings = AppSettings(),
            strings = strings(),
            worldInfo = world(before = "{{CHAR}}与{{User}}在{{NOW}}相遇"),
            now = fixedNow,
        )
        val sys = msgs.first().content.orEmpty()
        val expectedNow = com.situ.aichat.util.DateFormatters.yearMonthDayHourMinute(fixedNow.toEpochMilli())
        assertTrue(sys.contains("小雨与阿檀在${expectedNow}相遇"))
    }

    @Test
    fun 上下文日志分段_前后置世界书全部显形() {
        val segments = mutableListOf<ContextSegment>()
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val messages = listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "你好", timestamp = 1L),
        )
        PromptBuilder.buildMessages(
            character = character,
            sortedMessages = messages,
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings(),
            worldInfo = world(before = "前设定", after = "后设定", suffix = "尾设定"),
            now = fixedNow,
            segmentSink = segments,
        )
        val names = segments.map { it.name }
        assertTrue(names.contains("世界书（角色前）"))
        assertTrue(names.contains("世界书（角色后）"))
        assertTrue(names.contains("世界书（后置）"))
    }
}
