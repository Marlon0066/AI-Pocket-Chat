package com.situ.aichat.data.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.situ.aichat.util.ContentImageStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * T2 端到端：**备份还原后，落在磁盘上的图到底是多少像素**（R3 🔴-1 + 🟡-9「备份往返」下沉自动化）。
 *
 * 为什么必须端到端量像素、而不是只测那个纯函数：
 * - R2 抓到的 🔴 是「还原把聊天图砍到 1024」——静默、有损、每导一次再导一次再降一档，不可逆累积。
 * - R3 抓到的是**补的那把锁没上**：产线 `resaveMedia` 自己写了一份 `when`，单测测的是另一份纯函数
 *   `maxEdgeForKey`。把产线那支整条删掉，5 例全绿。所以这里从 zip 字节一路跑到落盘文件，
 *   断言解出来的长边——`resaveMedia` 的档位一旦回退，这条**必红**。
 * - 旧真机项「备份往返」的话术是「图片能回来且能点开」，压根测不出像素被砍，故那条已下沉到这里。
 *
 * Robolectric NATIVE 图形模式 = 真 Skia，JPEG 编解码与缩放都是真实现（同 `ImageOrientationTest` 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupMediaRestoreRoutingTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** 一张**比两个档位都大**的图：只有这样，两条路由才会落在不同的长边上（都小于档位则恒等，测不出差别）。 */
    private fun bigJpegBytes(width: Int = 2000, height: Int = 1200): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 跑一遍真还原，返回「zip 键 → 落盘图的长边像素」。 */
    private suspend fun restoredLongestEdges(vararg keys: String): Map<String, Int> {
        val bytes = bigJpegBytes()
        val zip = zipOf(*keys.map { it to bytes }.toTypedArray())
        val newPathByKey = mutableMapOf<String, String>()
        val newMediaFiles = mutableListOf<String>()

        val failed = BackupMediaRestorer(context).restoreMedia(
            source = { zip.inputStream() },
            skipKeys = emptySet(),
            mediaTotal = keys.size,
            newPathByKey = newPathByKey,
            newMediaFiles = newMediaFiles,
            onProgress = null,
        )
        assertEquals("没有任何一条该还原失败", 0, failed)

        return keys.associateWith { key ->
            val path = newPathByKey[key]
            assertNotNull("『$key』应落盘并登记新路径", path)
            val decoded = BitmapFactory.decodeFile(path!!)
            assertNotNull("『$key』落盘的应是一张能解出来的图", decoded)
            max(decoded.width, decoded.height)
        }
    }

    @Test
    fun `聊天图还原后长边仍是 1568 而不是被 else 捞走砍到 1024`() = runTest {
        val edges = restoredLongestEdges("media/images/abc.jpg")
        assertEquals(
            "聊天图被降档 = R2 抓到的那个静默有损 bug 复发",
            1568,
            edges.getValue("media/images/abc.jpg"),
        )
    }

    @Test
    fun `朋友圈 日记 礼物图还原后长边仍是 1024 不被聊天档带跑`() = runTest {
        val keys = arrayOf("media/moment/a.jpg", "media/diary/b.jpg", "media/gift/c.jpg")
        val edges = restoredLongestEdges(*keys)
        keys.forEach { assertEquals("『$it』不该被聊天档带跑", 1024, edges.getValue(it)) }
    }

    @Test
    fun `同一包里两条路由各走各的档`() = runTest {
        // 一次还原里两家并存才是真实场景；也堵住「全局改成一个档」这种改法
        val edges = restoredLongestEdges("media/images/chat.jpg", "media/moment/post.jpg")
        assertEquals(1568, edges.getValue("media/images/chat.jpg"))
        assertEquals(1024, edges.getValue("media/moment/post.jpg"))
    }

    @Test
    fun `落盘文件确实进了内容图目录且被登记进清理清单`() = runTest {
        val key = "media/images/x.jpg"
        val zip = zipOf(key to bigJpegBytes())
        val newPathByKey = mutableMapOf<String, String>()
        val newMediaFiles = mutableListOf<String>()
        BackupMediaRestorer(context).restoreMedia(
            source = { zip.inputStream() },
            skipKeys = emptySet(),
            mediaTotal = 1,
            newPathByKey = newPathByKey,
            newMediaFiles = newMediaFiles,
            onProgress = null,
        )
        val path = newPathByKey.getValue(key)
        // 中途抛出/取消时调用方靠 newMediaFiles 清孤儿——漏登记 = 失败的导入会在私有目录里留垃圾
        assertEquals(listOf(path), newMediaFiles)
        assertEquals(true, File(path).exists())
        assertEquals(File(context.filesDir, "content_images").absolutePath, File(path).parentFile?.absolutePath)
    }

    @Test
    fun `跳过策略的键连字节都不读 也不落盘`() = runTest {
        val key = "media/images/skipped.jpg"
        val zip = zipOf(key to bigJpegBytes())
        val newPathByKey = mutableMapOf<String, String>()
        val newMediaFiles = mutableListOf<String>()
        val failed = BackupMediaRestorer(context).restoreMedia(
            source = { zip.inputStream() },
            skipKeys = setOf(key),
            mediaTotal = 1,
            newPathByKey = newPathByKey,
            newMediaFiles = newMediaFiles,
            onProgress = null,
        )
        assertEquals("跳过不算失败", 0, failed)
        assertEquals(emptyMap<String, String>(), newPathByKey)
        assertEquals(emptyList<String>(), newMediaFiles)
    }

    @Test
    fun `两个档位常量确实不同 否则上面几条全是空转`() {
        assertEquals(1568, ContentImageStore.CHAT_MAX_EDGE)
        assertEquals(1024, ContentImageStore.MOMENT_DIARY_MAX_EDGE)
    }
}
