package com.situ.aichat.ui.chat

import android.content.Context
import android.net.Uri
import com.situ.aichat.chat.image.ImageMemorySummaryService
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.prompt.memory.MeetingArchiveVectorService
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.util.ContentImageStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2：**发图链的「摘要 → 嵌入」交接**（R3 🟡-7）。
 *
 * 为什么必须补：R2 返工把「图片消息推迟嵌入」这半做对了，但另一半——摘要落库后谁来补这一嵌——
 * 全工程零测试。R3 实测「删掉 `ChatImageSender` 里那两行，6770 例全绿」。
 *
 * 断言从规格反推，不照抄实现：
 * - 「同批多图每条都嵌」= 返工图纸 W6 点名要的第三条（旧行为是「受理只跑最后一张 → 最后那张永远最差」）；
 * - 「摘要先落库再嵌」= D-2 的目的（反过来嵌的就是空摘要那版，所有图片向量撞成同一句话）；
 * - 「推迟闸只对常规受理路生效」= 两个入口口径有意不同，谁把它们「统一」了就红。
 */
class ChatImageSenderTest {

    private val appContext = mockk<Context>(relaxed = true)
    private val conversationRepo = mockk<ConversationRepository>()
    private val characterRepo = mockk<CharacterRepository>()
    private val summaryService = mockk<ImageMemorySummaryService>()

    private val conversationUuid = "conv-1"

    /** 落库的消息、以及「摘要/嵌入」两件事的真实发生顺序。 */
    private val stored = mutableListOf<MessageEntity>()
    private val trace = mutableListOf<String>()

    private val embedded get() = trace.filter { it.startsWith("embed:") }.map { it.removePrefix("embed:") }

    @After
    fun tearDown() = unmockkObject(ContentImageStore)

    private fun sender() = ChatImageSender(
        appContext = appContext,
        conversationUuid = conversationUuid,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        imageMemorySummaryService = summaryService,
        errorFlow = MutableStateFlow(null),
        storeUserMessage = { m, _, _ -> stored += m },
        acceptStoredUserMessage = { _, _ -> },
        embedImageMessage = { uuid -> trace += "embed:$uuid" },
    )

    private fun stubPickedImages() {
        coEvery { conversationRepo.get(conversationUuid) } returns ConversationEntity(
            uuid = conversationUuid, title = "t", characterUuid = "char-1", creationDate = 0L,
        )
        coEvery { characterRepo.get("char-1") } returns CharacterEntity(
            uuid = "char-1", name = "小雨", creationDate = 0L, systemPrompt = "", personalityDescription = "",
        )
        mockkObject(ContentImageStore)
        var n = 0
        coEvery { ContentImageStore.saveWithThumbnail(any(), any(), any(), any()) } answers {
            n++
            ContentImageStore.StoredImage(path = "/img/$n.jpg", thumbnailPath = "/img/${n}_t.jpg")
        }
        coEvery { summaryService.summarize(any(), any(), any()) } answers {
            trace += "summarize:${firstArg<String>()}"
            "海边的黄昏"
        }
    }

    /** 摘要链是 fire-and-forget 的子协程，不推进调度器就一个字都还没跑。 */
    private suspend fun CoroutineScope.sendAndDrain(count: Int) {
        sender().send(this, List(count) { mockk<Uri>() })
        (this as kotlinx.coroutines.test.TestScope).advanceUntilIdle()
    }

    // ── 交接本体 ──

    @Test
    fun `同批三图_每条各嵌一次_不是只嵌最后一张`() = runTest {
        stubPickedImages()
        sendAndDrain(3)

        assertEquals("三张各成一条消息", 3, stored.size)
        assertEquals("每条恰好一次、uuid 各一", stored.map { it.messageUUID }, embedded)
    }

    @Test
    fun `摘要先落库_再嵌这一条_顺序不能反`() = runTest {
        stubPickedImages()
        sendAndDrain(1)

        val uuid = stored.single().messageUUID
        assertEquals(listOf("summarize:$uuid", "embed:$uuid"), trace)
    }

    @Test
    fun `摘要抛异常也照嵌_不因一张图断掉整批`() = runTest {
        stubPickedImages()
        coEvery { summaryService.summarize(any(), any(), any()) } throws IllegalStateException("网断了")
        sendAndDrain(2)

        assertEquals("摘要挂了不该连累嵌入", stored.map { it.messageUUID }, embedded)
    }

    // ── 被交接方：VectorMemoryService 的「跳过推迟闸」入口 ──

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val embedder = mockk<TextEmbedder>(relaxed = true)

    private fun vectorService(): VectorMemoryService {
        every { embedder.isAvailable } returns true
        coEvery { embedder.embed(any()) } returns FloatArray(8) { 0.1f }
        coEvery { messageDao.updateEmbedding(any(), any()) } just Runs
        return VectorMemoryService(
            messageDao,
            mockk(relaxed = true),
            embedder,
            mockk<MeetingArchiveVectorService>(relaxed = true),
        )
    }

    private fun imageMessage(summary: String, content: String = "[图片]") = MessageEntity(
        messageUUID = "m1",
        conversationUuid = conversationUuid,
        roleRaw = "user",
        content = content,
        timestamp = 1L,
        imageRelativePath = "/img/1.jpg",
        mediaMemorySummary = summary,
    )

    @Test
    fun `摘要空但正文有话时_新入口照嵌_常规入口仍推迟`() = runTest {
        // 这一对才是「跳过推迟闸」的真凭据：同一条消息，走 embedMessageIfNeeded 被推迟闸挡住，
        // 走 embedImageMessageAfterSummary 则嵌得出来——两个入口口径不同是设计（见各自 KDoc）。
        val msg = imageMessage(summary = "", content = "今天去看海了，风很大")

        vectorService().embedMessageIfNeeded(msg)
        coVerify(exactly = 0) { messageDao.updateEmbedding(any(), any()) }

        vectorService().embedImageMessageAfterSummary(msg)
        coVerify(exactly = 1) { messageDao.updateEmbedding("m1", any()) }
    }

    @Test
    fun `摘要非空时_嵌的文本必须含摘要`() = runTest {
        vectorService().embedImageMessageAfterSummary(imageMessage(summary = "海边的黄昏，两个人的背影"))
        coVerify { embedder.embed(match { it.contains("海边的黄昏") }) }
    }

    @Test
    fun `同一条消息不会被嵌第二次`() = runTest {
        vectorService().embedImageMessageAfterSummary(imageMessage(summary = "海边").copy(embedding = ByteArray(4)))
        coVerify(exactly = 0) { messageDao.updateEmbedding(any(), any()) }
    }

    @Test
    fun `纯兜底摘要那条_两个入口都嵌不出来_因为渲染只有七个字`() = runTest {
        // ⚠️ 这条钉的是**现状与其成因**，不是「应该如此」：摘要兜底写空串时，`renderImageSemantics`
        // 产出「发送了一张图片」= 7 字，撞上全局 `MIN_CONTENT_LENGTH = 8` 这道**与图片无关的老规矩**
        // → 两个入口都写不出向量。也就是说 R3 🟡-7 说的「返工前是落库当场嵌一个弱向量」并不成立
        // ——它以前也嵌不出来，这条兜底路径上返工前后行为一致，不是回归。
        // 该不该为图片放宽这道下限（弱向量 vs 一堆同构向量互相挤占召回名额）属产品取舍，留用户/R4 裁。
        vectorService().embedImageMessageAfterSummary(imageMessage(summary = ""))
        coVerify(exactly = 0) { messageDao.updateEmbedding(any(), any()) }
    }
}
