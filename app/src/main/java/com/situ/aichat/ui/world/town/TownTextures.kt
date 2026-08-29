package com.situ.aichat.ui.world.town

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 小镇材质贴图加载（台阶1 图纸 §3.5·**双轨兜底**）：把五张手绘材质（草地/石板/墙面/陶瓦/叶团）解码成位图交
 * 渲染器在 GL 线程懒上传。纯逻辑 + BitmapFactory，零 GL。
 *
 * 双轨兜底铁律：**任一张查无资源 id / 解码失败，该桶直接缺席** → 渲染器把它的 `uTexMix` 置 0，
 * `mix(vec3(1.0), tex*2.0, 0.0)` 数学恒等 ⇒ 该桶画面与「无贴图」字节级同源；五张全缺 = 整场景回落现状。
 *
 * ⚠ 解码是阻塞 IO，**调用方负责放 [kotlinx.coroutines.Dispatchers.IO]**（见 TownSceneView 的 LaunchedEffect）。
 */
internal object TownTextures {

    /** ES2 的 REPEAT wrap + mipmap 仅保证 **2 的幂**尺寸可用（平台事实）→ 材质贴图强制 512×512。 */
    const val SIZE = 512

    /** 贴图存在时的混合强度（§4.4 锁定·全桶统一；该桶缺图恒 0）。 */
    const val TEX_MIX = 0.85f

    /** 桶 → drawable 资源名（§3.5 锁定表·drawable-nodpi 下的 webp；[TownBucket.PLAIN] 不贴图故不在表内）。 */
    private val RES_NAMES: Map<TownBucket, String> = linkedMapOf(
        TownBucket.GROUND to "world_tex_grass",
        TownBucket.STONE to "world_tex_stone",
        TownBucket.WALL to "world_tex_wall",
        TownBucket.ROOF to "world_tex_roof",
        TownBucket.FOLIAGE to "world_tex_leaf",
    )

    /**
     * 世界坐标 uv 缩放（§4.4 锁定）= 1 / 贴图在世界里的平铺边长（米）。片元着色器按世界坐标平面采样，
     * 故无需 UV 顶点属性——几何格式与 [com.situ.aichat.ui.world.continent.TriStream] 零改。
     */
    fun texScaleOf(bucket: TownBucket): Float = when (bucket) {
        TownBucket.GROUND -> 1f / 7.0f
        TownBucket.STONE -> 1f / 4.0f
        TownBucket.WALL -> 1f / 2.2f
        TownBucket.ROOF -> 1f / 2.6f
        TownBucket.FOLIAGE -> 1f / 2.4f
        TownBucket.PLAIN -> 0f   // 不贴图（水体/树干/灯柱/环境小件）
    }

    /** 全表解码：缺席的桶直接不出现在结果里（= 该桶 uTexMix 归 0）。 */
    fun decodeAll(context: Context): Map<TownBucket, Bitmap> {
        val res = context.resources
        val pkg = context.packageName
        val out = LinkedHashMap<TownBucket, Bitmap>(RES_NAMES.size)
        for ((bucket, name) in RES_NAMES) decode(res, pkg, name)?.let { out[bucket] = it }
        return out
    }

    /** 单张解码 + 2 的幂兜底。资源不存在 / 解码失败 / OOM 一律返 null，**绝不抛**（E5/E6）。 */
    internal fun decode(res: Resources, pkg: String, name: String): Bitmap? {
        val id = try {
            @Suppress("DiscouragedApi") // 按名查 id 是双轨兜底的前提：素材未入库时必须查得出「没有」。
            res.getIdentifier(name, "drawable", pkg)
        } catch (e: Exception) {
            0
        }
        if (id == 0) return null
        val bmp = try {
            BitmapFactory.decodeResource(res, id)
        } catch (e: Throwable) {
            null
        } ?: return null
        return toPowerOfTwo(bmp)
    }

    /** 非 [SIZE]² → 缩放到 [SIZE]²（ES2 pow2 约束·原图非 2 的幂时 REPEAT + mipmap 会整片失效变纯白）。 */
    internal fun toPowerOfTwo(bmp: Bitmap): Bitmap {
        if (bmp.width == SIZE && bmp.height == SIZE) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}
