package com.situ.aichat.ui.world.web

import android.graphics.Bitmap
import android.util.Base64
import com.situ.aichat.ui.world.CastCardKind
import com.situ.aichat.ui.world.CastPlacement
import com.situ.aichat.ui.world.id
import com.situ.aichat.ui.world.town.GrammarPart
import com.situ.aichat.ui.world.town.TownAmbience
import com.situ.aichat.ui.world.town.TownCamSnapshot
import com.situ.aichat.ui.world.town.TownData
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.util.ImageScaler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * 原生 → 网页小镇的报文装配（图纸「网页世界一期」§3.2/§3.3·**字段名 §9 锁死照抄**）。纯映射零副作用：
 * 布局/摆位/氛围都在原生算好（[com.situ.aichat.ui.world.WorldCastAnchors] / [TownAmbience] 单源），
 * web 只拿坐标与色值画——规则不在两侧各写一遍。
 *
 * JSON 走 **kotlinx.serialization**（仓库既有依赖·纯 JVM 可 T1 直测）而非 org.json：后者在单测里走 stub
 * android.jar，本模块 `isReturnDefaultValues = true` 会让它**静默返回 null** 而非报错（见图纸 §11 D-2）。
 * `buildJsonObject` 保插入序 → 同输入逐字节确定（E4 确定性金标据此成立）。
 */
internal object TownWebData {

    /** 相机 target 的固定高（[com.situ.aichat.ui.world.town.TownCamera] 的 target y·pose 只传 x/z 故此处补回）。 */
    const val TARGET_Y = 0.8f

    /** 头像编码档（§2.1）：长边 96px + JPEG q80 —— 卡面直径 37px，96 足够 3x 屏，报文又小。 */
    private const val AVATAR_EDGE = 96
    private const val AVATAR_QUALITY = 80

    // ─────────────────────────── townJson（§3.2）───────────────────────────

    /** 整座镇的建造报文（坐标/尺寸 = 世界单位原值·颜色 = [r,g,b] 0..1）。 */
    fun townJson(data: TownData): String = buildJsonObject {
        put("cityId", data.cityId)
        put("cityName", data.cityName)
        put("curated", data.curated)
        put("glowA", data.glowA)
        put("sky", buildJsonArray { data.sky.forEach { add(buildJsonObject { put("pos", it.pos); put("rgb", rgb(it.color)) }) } })
        put("ground", rgb(data.layout.ground))
        put("water", data.layout.water.name)
        put("buildings", buildJsonArray {
            data.layout.buildings.forEach {
                add(
                    buildJsonObject {
                        put("cx", it.cx); put("cz", it.cz); put("sx", it.sx); put("h", it.h); put("sz", it.sz)
                        put("wall", rgb(it.wall)); put("roof", rgb(it.roof)); put("windows", it.windows)
                    },
                )
            }
        })
        put("fillers", buildJsonArray {
            data.layout.fillers.forEach { add(buildJsonObject { put("cx", it.cx); put("cz", it.cz); put("wall", rgb(it.wall)) }) }
        })
        put("lanterns", buildJsonArray {
            data.layout.lanterns.forEach { add(buildJsonObject { put("cx", it.cx); put("cz", it.cz); put("baseY", it.baseY) }) }
        })
        put("trees", buildJsonArray {
            data.layout.trees.forEach {
                add(
                    buildJsonObject {
                        put("cx", it.cx); put("cz", it.cz); put("s", it.s)
                        put("leaf", rgb(it.leaf)); put("trunkH", it.trunkH); put("coneH", it.coneH)
                    },
                )
            }
        })
        put("litBoxes", boxes(data.layout.litBoxes))
        put("emisBoxes", boxes(data.layout.emisBoxes))
        put("cones", buildJsonArray {
            data.layout.cones.forEach {
                add(
                    buildJsonObject {
                        put("cx", it.cx); put("y", it.y); put("cz", it.cz)
                        put("r", it.r); put("h", it.h); put("col", rgb(it.col))
                    },
                )
            }
        })
        put("grammar", buildJsonArray { data.layout.grammar.forEach { add(grammarPart(it)) } })
        put("places", buildJsonArray {
            // body 不外发：站点卡正文恒在原生 sheet 渲染（§2.3.1）。
            data.places.forEach {
                add(buildJsonObject { put("id", it.id); put("name", it.name); put("x", it.x); put("z", it.z); put("top", it.top) })
            }
        })
    }.toString()

    private fun boxes(list: List<com.situ.aichat.ui.world.town.TownBox>): JsonArray = buildJsonArray {
        list.forEach {
            add(
                buildJsonObject {
                    put("cx", it.cx); put("y0", it.y0); put("cz", it.cz)
                    put("sx", it.sx); put("h", it.h); put("sz", it.sz); put("col", rgb(it.col))
                },
            )
        }
    }

    /** 语法件（角锚定 x/z·`t` 三值 = JS 建造分支键·[GrammarPart.Roof.style] 三式逐字同 [com.situ.aichat.ui.world.town.RoofStyle]）。 */
    private fun grammarPart(p: GrammarPart): JsonObject = when (p) {
        is GrammarPart.LitBox -> buildJsonObject { put("t", "lit"); box6(p.x, p.y, p.z, p.sx, p.h, p.sz); put("col", rgb(p.col)) }
        is GrammarPart.EmisBox -> buildJsonObject { put("t", "emis"); box6(p.x, p.y, p.z, p.sx, p.h, p.sz); put("col", rgb(p.col)) }
        is GrammarPart.Roof -> buildJsonObject {
            put("t", "roof"); put("style", p.style.name); box6(p.x, p.y, p.z, p.sx, p.h, p.sz); put("col", rgb(p.col))
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.box6(
        x: Double, y: Double, z: Double, sx: Double, h: Double, sz: Double,
    ) {
        put("x", x); put("y", y); put("z", z); put("sx", sx); put("h", h); put("sz", sz)
    }

    // ─────────────────────────── castJson（§3.3）───────────────────────────

    /**
     * 演员卡报文。[avatars] = 卡 id → base64 头像（缺失/无头像给 null → JS 画首字圆），由调用方在 IO 线程
     * 经 [avatarBase64] 预备；本函数保持纯映射可 T1。神秘卡不外发真名（JS 也不渲染名签）。
     */
    fun castJson(placement: CastPlacement, avatars: Map<String, String?>): String = buildJsonObject {
        put("cards", buildJsonArray {
            placement.cards.forEach { c ->
                val id = c.kind.id()
                add(
                    buildJsonObject {
                        put("id", id)
                        put("kind", kindOf(c.kind))
                        put("name", nameOf(c.kind))
                        put("x", c.x); put("y", c.y); put("z", c.z)
                        put("avatar", avatars[id]?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                        put("present", presentOf(c.kind))
                        // W-1（契约 v1.2）：状态下标位两态（页面渲染「睡着了」月牙 / 「在家」·非 Character 恒 false）。
                        val member = c.kind as? CastCardKind.Character
                        put("sleeping", member?.sleeping == true)
                        put("atHome", member?.atHome == true)
                        // 三期卷一（契约 v1.3）：true = 环位闲逛客·页面开走动（x/z 退作静止锚）。
                        put("walking", c.walking)
                    },
                )
            }
        })
        put("overflows", buildJsonArray {
            placement.overflows.forEach { add(buildJsonObject { put("x", it.x); put("y", it.y); put("z", it.z); put("count", it.count) }) }
        })
    }.toString()

    private fun kindOf(kind: CastCardKind): String = when (kind) {
        is CastCardKind.Character -> "member"
        is CastCardKind.Native -> if (kind.staged.discovered) "native" else "mystery"
        is CastCardKind.Pet -> "pet"
    }

    private fun nameOf(kind: CastCardKind): String = when (kind) {
        is CastCardKind.Character -> kind.staged.name
        is CastCardKind.Native -> if (kind.staged.discovered) kind.staged.name else "" // 未发现不外发真名
        is CastCardKind.Pet -> kind.staged.name
    }

    /** `present=false` → JS 卡片蒙灰。一期取「睡着了」= 现版月牙卡的最近等义（图纸 §11 D-3）。 */
    private fun presentOf(kind: CastCardKind): Boolean =
        !(kind is CastCardKind.Character && kind.sleeping)

    /** 头像 → base64（长边 96px JPEG q80）。无路径/解码失败 → null（JS 画首字圆·E6）。**IO 线程调**。 */
    fun avatarBase64(path: String?): String? {
        val src = AvatarStore.loadBlocking(path) ?: return null
        return runCatching {
            val scaled = ImageScaler.scaleToMaxEdge(src, AVATAR_EDGE)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, out)
            if (scaled !== src) scaled.recycle() // src 归 AvatarStore 缓存所有·绝不回收
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    // ─────────────────────────── ambJson / flags / pose（§3.3）───────────────────────────

    /** 氛围快照（= [TownAmbience.Snapshot] 字段直搬·web 不自算时刻表·J4）。 */
    fun ambienceJson(snap: TownAmbience.Snapshot): String = buildJsonObject {
        put("phase", snap.paintedPhase)
        put("lampT", snap.lampT)
        put("duskSec", snap.duskSec)
        put("tint", rgb(snap.sceneTint))
        put("fog", rgb(snap.fog))
        put("glowA", snap.glowA)
    }.toString()

    fun flagsJson(reduceMotion: Boolean, staticMode: Boolean, interactive: Boolean): String = buildJsonObject {
        put("reduceMotion", reduceMotion); put("staticMode", staticMode); put("interactive", interactive)
    }.toString()

    /** 相机姿态 → web（[TownCamSnapshot.ty] 恒 [TARGET_Y] 故不外发）。 */
    fun poseJson(snap: TownCamSnapshot): String = buildJsonObject {
        put("yaw", snap.yaw); put("pitch", snap.pitch); put("dist", snap.dist); put("tx", snap.tx); put("tz", snap.tz)
    }.toString()

    /** web → 相机姿态（桥心跳报文·字段缺失/非法 → null，调用方保留上一份·§3.5）。 */
    fun poseFrom(json: String): TownCamSnapshot? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        TownCamSnapshot(
            yaw = o.getValue("yaw").jsonPrimitive.content.toFloat(),
            pitch = o.getValue("pitch").jsonPrimitive.content.toFloat(),
            dist = o.getValue("dist").jsonPrimitive.content.toFloat(),
            tx = o.getValue("tx").jsonPrimitive.content.toFloat(),
            ty = TARGET_Y,
            tz = o.getValue("tz").jsonPrimitive.content.toFloat(),
        )
    }.getOrNull()

    // ─────────────────────────── 色值 ───────────────────────────

    private fun rgb(c: DoubleArray): JsonElement = buildJsonArray { add(c[0]); add(c[1]); add(c[2]) }
    private fun rgb(c: FloatArray): JsonElement = buildJsonArray { add(c[0]); add(c[1]); add(c[2]) }
}
