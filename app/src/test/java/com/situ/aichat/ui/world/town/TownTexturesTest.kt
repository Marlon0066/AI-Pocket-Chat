package com.situ.aichat.ui.world.town

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TownTextures] T2（台阶1 图纸 §7 T2-1·E5/E6）：**双轨兜底**的两条命脉——查无资源 → null（该桶 uTexMix=0，
 * 画面回落无贴图现状），以及非 2 的幂原图 → 兜底缩放到 512²（ES2 的 REPEAT + mipmap 只保证 2 的幂尺寸，
 * 漏缩放会整片采样失效变纯白）。缩放走真 Android Bitmap 故需 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TownTexturesTest {

    private val app = RuntimeEnvironment.getApplication()

    // ─────────────────────────── E5 资源缺失 → 该桶缺席 ───────────────────────────

    @Test
    fun missingResource_returnsNull_neverThrows() {
        assertNull(
            "查无 drawable 必须返 null（而不是抛）",
            TownTextures.decode(app.resources, app.packageName, "world_tex_definitely_not_here"),
        )
    }

    @Test
    fun emptyPackageName_stillReturnsNullNotThrow() {
        assertNull(TownTextures.decode(app.resources, "", "world_tex_grass"))
    }

    @Test
    fun decodeAll_onlyContainsBucketsThatResolved_andNeverPlain() {
        val map = TownTextures.decodeAll(app)
        assertFalse("PLAIN 桶永不贴图", map.containsKey(TownBucket.PLAIN))
        for ((bucket, bmp) in map) {
            assertTrue("$bucket 必须是可贴图的五桶之一", bucket != TownBucket.PLAIN)
            assertEquals("$bucket 宽须为 512", TownTextures.SIZE, bmp.width)
            assertEquals("$bucket 高须为 512", TownTextures.SIZE, bmp.height)
        }
    }

    // ─────────────────────────── E6 非 512 原图 → 兜底缩放 ───────────────────────────

    @Test
    fun nonPowerOfTwoBitmap_isScaledTo512Square() {
        val src = Bitmap.createBitmap(763, 512, Bitmap.Config.ARGB_8888)
        val out = TownTextures.toPowerOfTwo(src)
        assertEquals(512, out.width)
        assertEquals(512, out.height)
    }

    @Test
    fun oversizedBitmap_isAlsoBroughtDownTo512() {
        val src = Bitmap.createBitmap(2048, 1152, Bitmap.Config.ARGB_8888)
        val out = TownTextures.toPowerOfTwo(src)
        assertEquals(512, out.width)
        assertEquals(512, out.height)
    }

    @Test
    fun exact512_isPassedThroughUntouched() {
        val src = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val out = TownTextures.toPowerOfTwo(src)
        assertSame("已是 512² 不该多复制一份", src, out)
        assertFalse("原图不该被回收", out.isRecycled)
    }

    // ─────────────────────────── §4.4 锁定 uv 缩放表 ───────────────────────────

    @Test
    fun texScaleTableMatchesSpec() {
        val eps = 1e-6f
        assertEquals(1f / 7.0f, TownTextures.texScaleOf(TownBucket.GROUND), eps)
        assertEquals(1f / 4.0f, TownTextures.texScaleOf(TownBucket.STONE), eps)
        assertEquals(1f / 2.2f, TownTextures.texScaleOf(TownBucket.WALL), eps)
        assertEquals(1f / 2.6f, TownTextures.texScaleOf(TownBucket.ROOF), eps)
        assertEquals(1f / 2.4f, TownTextures.texScaleOf(TownBucket.FOLIAGE), eps)
        assertEquals("PLAIN 不贴图", 0f, TownTextures.texScaleOf(TownBucket.PLAIN), eps)
        assertEquals("贴图存在时全桶统一混合强度", 0.85f, TownTextures.TEX_MIX, eps)
    }

    @Test
    fun shippedTexturesAreAllPresent() {
        // 五张材质已入库（drawable-nodpi）→ 全表应齐；任一张丢失时上面的兜底用例保证不崩、只是该桶不贴图。
        val map = TownTextures.decodeAll(app)
        for (b in listOf(TownBucket.GROUND, TownBucket.STONE, TownBucket.WALL, TownBucket.ROOF, TownBucket.FOLIAGE)) {
            assertNotNull("$b 材质应已入库", map[b])
        }
    }
}
