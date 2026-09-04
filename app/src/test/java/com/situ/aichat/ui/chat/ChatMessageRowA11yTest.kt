package com.situ.aichat.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 行级读屏动作面（[rememberMessageRowA11yActions]）的行为测试——钉住 2026-09-04 修掉的**陈旧快照**缺陷：
 * 动作 lambda 曾闭包捕获首帧 entity，而 remember key 有意不含 `content`（防流式逐帧重建动作面），
 * 于是 AI 边打字边被读屏触发「引用/复制/删除」时拿到的是半截文本。
 *
 * 判别力自证：把 `ChatMessageRowA11y.kt` 里的 `current` 换回 `message`（即修复前写法），
 * 下面两条「latest」用例必红——已实测（见本批施工记录）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMessageRowA11yTest {

    @get:Rule
    val compose = createComposeRule()

    private fun message(content: String) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c1",
        roleRaw = "assistant",
        content = content,
        timestamp = 1_000L,
        messageKindRaw = MessageKind.PLAIN_TEXT.raw,
    )

    /** 全 no-op 的动作面；只有点名的回调记账。**必须在 setContent 外建一次**——它是 remember 的 key。 */
    private fun actions(
        onQuote: (MessageEntity) -> Unit = {},
        onDelete: (MessageEntity) -> Unit = {},
    ) = MessageRowActions(
        onVoiceToggle = {},
        onOpenImage = {},
        onSaveImage = {},
        onQuote = onQuote,
        onDelete = onDelete,
        onOpenMenu = { _, _, _ -> },
        onFlightBubblePositioned = { _, _ -> },
        onRegenerate = {},
        loadDiyImage = { null },
        onOpenDiyDetail = {},
        observeRedPacket = { emptyFlow() },
        onRedPacketClick = {},
        onAcceptInvite = {},
        onDeclineInvite = {},
        onEndMeeting = {},
        onContinueMeeting = {},
        onReviewOffline = {},
        observeAppointment = { emptyFlow() },
        onAppointmentAccept = {},
        onAppointmentDecline = {},
        onAppointmentReschedule = {},
        onAppointmentChangeApply = {},
        onAppointmentChangeKeep = {},
        onVoiceCascadePlayed = {},
        onOpenVoiceSetup = {},
    )

    @Test
    fun `quote action reads latest content not first frame snapshot`() {
        val quoted = mutableListOf<String>()
        val rowActions = actions(onQuote = { quoted += it.content })
        var msg by mutableStateOf(message("你好我"))
        lateinit var a11y: List<CustomAccessibilityAction>
        compose.setContent {
            a11y = rememberMessageRowA11yActions(msg, isUser = false, canRegenerate = true, actions = rowActions, eligible = true)
        }
        // 流式续写：同一条消息（uuid 不变）内容长出来。
        compose.runOnUiThread { msg = msg.copy(content = "你好我在的，刚在忙别的") }
        compose.waitForIdle()

        assertTrue(a11y.first { it.label == "引用" }.action())
        assertEquals(listOf("你好我在的，刚在忙别的"), quoted)
    }

    @Test
    fun `delete action targets latest entity`() {
        val deleted = mutableListOf<String>()
        val rowActions = actions(onDelete = { deleted += it.content })
        var msg by mutableStateOf(message("首帧"))
        lateinit var a11y: List<CustomAccessibilityAction>
        compose.setContent {
            a11y = rememberMessageRowA11yActions(msg, isUser = false, canRegenerate = true, actions = rowActions, eligible = true)
        }
        compose.runOnUiThread { msg = msg.copy(content = "终态") }
        compose.waitForIdle()

        assertTrue(a11y.first { it.label == "删除" }.action())
        assertEquals(listOf("终态"), deleted)
    }

    @Test
    fun `action list is not rebuilt while content streams`() {
        // 设计意图（性能）：content 有意不进 remember key——每帧重建动作面会让读屏焦点/动作表反复失效。
        val rowActions = actions()
        var msg by mutableStateOf(message("一"))
        val captured = mutableListOf<List<CustomAccessibilityAction>>()
        compose.setContent {
            captured += rememberMessageRowA11yActions(msg, isUser = false, canRegenerate = true, actions = rowActions, eligible = true)
        }
        compose.runOnUiThread { msg = msg.copy(content = "一二") }
        compose.waitForIdle()

        assertTrue("应发生过重组（%d 次组合）".format(captured.size), captured.size >= 2)
        assertSame("动作面实例应跨内容更新复用", captured.first(), captured.last())
    }

    @Test
    fun `labels and order stay in sync with the visual menu`() {
        // 契约 §3.3：读屏动作面与沉浸菜单同源——同参数下条目与顺序必须逐字一致。
        val rowActions = actions()
        lateinit var a11y: List<CustomAccessibilityAction>
        compose.setContent {
            a11y = rememberMessageRowA11yActions(message("正文"), isUser = false, canRegenerate = true, actions = rowActions, eligible = true)
        }
        // canQuote 显式传 true（纯文字消息的真实取值）——复核 R1 🟡-2 去掉默认值后，这里不能再吃默认。
        val expected = immersiveMenuActions(isUser = false, hasImage = false, canRegenerate = true, canQuote = true)
            .map { immersiveMenuActionLabel(it) }
        assertEquals(expected, a11y.map { it.label })
    }

    @Test
    fun `older message drops regenerate from the a11y action list`() {
        val rowActions = actions()
        lateinit var a11y: List<CustomAccessibilityAction>
        compose.setContent {
            a11y = rememberMessageRowA11yActions(message("历史"), isUser = false, canRegenerate = false, actions = rowActions, eligible = true)
        }
        assertEquals(listOf("复制", "引用", "删除"), a11y.map { it.label })
    }

    @Test
    fun `ineligible row yields empty action list`() {
        // 复核 R2 🔵-4：语音/混合贴纸行不并入动作面——由 eligible **参数**决定（恒调用），
        // 而非在调用点包 if；后者会让 content 翻动时整组被丢弃、连带取消在飞的剪贴板写入。
        val rowActions = actions()
        lateinit var a11y: List<CustomAccessibilityAction>
        compose.setContent {
            a11y = rememberMessageRowA11yActions(message("语音转写"), isUser = false, canRegenerate = true, actions = rowActions, eligible = false)
        }
        assertTrue(a11y.isEmpty())
    }

    @Test
    fun `quote tracks the latest content when the same uuid morphs`() {
        // 复核 R1 🟡-1 回归钉：`canQuote` 读 content，而 remember 的 key **有意不含 content**——修复前它被冻在
        // 首帧，占位气泡原地变身成真消息后动作面仍停在首帧的答案。2026-09-04 引用一期把判据方向调过来了
        //（空正文不可引用、贴纸可引用），故场景改写成「空 → 真文字」，**钉的不变量一个字没变**。
        // 判别力自证：把 `canQuote` 挪回 remember 体内（=修复前写法），本例必红——已实测（见本批施工记录）。
        //（MessageRow 那一侧另有 isContentRevealed 闸做第二道保险；本例钉的是这个 hook 自身的不变量。）
        val rowActions = actions()
        var msg by mutableStateOf(message(""))
        lateinit var a11y: List<CustomAccessibilityAction>
        compose.setContent {
            a11y = rememberMessageRowA11yActions(msg, isUser = false, canRegenerate = false, actions = rowActions, eligible = true)
        }
        assertEquals("首帧空正文（流式占位）不该给引用", listOf("复制", "删除"), a11y.map { it.label })

        compose.runOnUiThread { msg = msg.copy(content = "刚忙完，你呢") }
        compose.waitForIdle()

        assertEquals("变身成真消息后引用必须出现", listOf("复制", "引用", "删除"), a11y.map { it.label })
    }

    @Test
    fun `non plain-text message drops quote from the a11y action list`() {
        // 2026-09-04：图片 / 各类卡片仍不可引用。这条走真组合，证明门控确实接到了读屏这一路
        //（判据本身的穷举在 ChatImmersiveMenuTest.messageCanBeQuoted 那组）。
        val rowActions = actions()
        lateinit var a11y: List<CustomAccessibilityAction>
        compose.setContent {
            a11y = rememberMessageRowA11yActions(
                message("配文").copy(imageRelativePath = "img/a.jpg"),
                isUser = true,
                canRegenerate = false,
                actions = rowActions,
                eligible = true,
            )
        }
        assertEquals(listOf("保存到相册", "删除"), a11y.map { it.label })
    }
}
