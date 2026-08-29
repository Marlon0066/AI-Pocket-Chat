package com.situ.aichat.data.backup

import com.situ.aichat.util.ContentImageStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 备份还原按归档 key 前缀路由到的**重存长边**。
 *
 * 为什么值得单独一条：契约 §B2 把 `ContentImageStore` 的 `maxEdge` 参数化，正是为了让聊天图片走 1568、
 * 其余维持 1024；但备份还原这个调用方当初被漏掉，聊天图被 `else` 一把捞走按 1024 重存——
 * **静默、有损、且每导一次再导一次就再降一档、不可逆地累积**。
 *
 * 更要命的是它当时藏在真机批「备份往返」那条底下，而那条的验收话术是「图片能回来且能点开」，
 * 压根测不出像素被砍。故这里用纯函数把路由钉死，不靠人眼。
 */
class BackupMediaMaxEdgeTest {

    private fun maxEdge(key: String) = BackupMediaRestorer.maxEdgeForKey(key)

    @Test
    fun `聊天图片走 1568 档`() {
        assertEquals(ContentImageStore.CHAT_MAX_EDGE, maxEdge("media/images/abc.jpg"))
    }

    @Test
    fun `聊天缩略图同走 1568 档`() {
        // 同前缀；缩略图本身只有 1024，`scaleToMaxEdge` 从不放大，故按 1568 重存是无害的恒等
        assertEquals(ContentImageStore.CHAT_MAX_EDGE, maxEdge("media/images/abc_thumb.jpg"))
    }

    @Test
    fun `朋友圈 日记 礼物维持 1024 档`() {
        listOf("media/moment/a.jpg", "media/diary/b.jpg", "media/gift/c.jpg").forEach { key ->
            assertEquals("『$key』应维持 1024，不该被聊天档带跑", ContentImageStore.MOMENT_DIARY_MAX_EDGE, maxEdge(key))
        }
    }

    @Test
    fun `两个档位确实不同 否则这条测试没有意义`() {
        // 防呆：若哪天有人把两个常量调成一样，上面三条会全绿却什么都没保住
        assertEquals(1568, ContentImageStore.CHAT_MAX_EDGE)
        assertEquals(1024, ContentImageStore.MOMENT_DIARY_MAX_EDGE)
    }

    @Test
    fun `前缀相近但不同的 key 不误判`() {
        // `media/images_old/` 这种不该命中聊天档
        assertEquals(ContentImageStore.MOMENT_DIARY_MAX_EDGE, maxEdge("media/imagesold/a.jpg"))
    }
}
