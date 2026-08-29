package com.situ.aichat.ui.world.web

import com.situ.aichat.ui.world.continent.ContinentCamSnapshot
import com.situ.aichat.ui.world.continent.ContinentSceneData
import com.situ.aichat.ui.world.continent.ContinentSite
import com.situ.aichat.ui.world.continent.RegionStyle
import com.situ.aichat.ui.world.planet.PlanetMath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 「用户在哪座城」的徽记数据（前端契约 §4.1 `presence`·三字段照抄·[cityId] 为 null = 没有落点）。 */
internal data class WorldWebPresence(
    val cityId: String?,
    val traveling: Boolean,
    val homeCityId: String?,
)

/**
 * 原生 → 网页大陆 / 星球的报文装配（图纸「网页世界二期」§2·**字段名 = 前端契约 §4.1/§5.1 逐字锁死**）。
 * 纯映射零副作用：站位坐标、十区样式表、家的球面单位向量都在原生算好（[ContinentSceneData.fromAtlas] /
 * [com.situ.aichat.ui.world.continent.ContinentStyle] / [PlanetMath] 单源），web 只拿数值画——规则不在两侧
 * 各写一遍。
 *
 * JSON 走 **kotlinx.serialization**（仓库既有依赖·纯 JVM 可 T1 直测），理由同一期 [TownWebData]：
 * 单测走 stub android.jar，`org.json` 会静默返回默认值而非报错。`buildJsonObject` 保插入序 → 同输入
 * 逐字节确定（确定性金标据此成立）。三旗报文与一期同形，直接复用 [TownWebData.flagsJson] 不另写一份。
 */
internal object WorldWebData {

    /** 大陆相机 target 的固定高（= [com.situ.aichat.ui.world.continent.ContinentCamera] 的 target y·pose 只传 x/z 故此处补回）。 */
    const val CONTINENT_TARGET_Y = 1.2f

    // ─────────────────────────── continentJson（契约 §4.1）───────────────────────────

    /** 一个大区的建造报文（坐标 = 世界单位原值·颜色 = [r,g,b] 0..1·站点卡正文 [body] 恒不外发）。 */
    fun continentJson(data: ContinentSceneData, presence: WorldWebPresence): String = buildJsonObject {
        put("regionId", data.regionId)
        put("regionName", data.regionName)
        put("isHome", data.isHome)
        put("style", styleJson(data.style))
        put("sites", buildJsonArray { data.sites.forEach { add(siteJson(it)) } })
        put("presence", presenceObject(presence))
    }.toString()

    private fun styleJson(s: RegionStyle): JsonObject = buildJsonObject {
        put("styleKey", s.styleKey)
        put("seed", s.seed); put("sea", s.sea); put("amp", s.amp); put("coast", s.coast); put("padH", s.padH)
        put("terrace", s.terrace); put("snowLine", s.snowLine)
        put("treeN", s.treeN); put("trunk", s.trunk); put("treeR", s.treeR); put("treeH", s.treeH)
        put("warm", rgb(s.warm)); put("haze", rgb(s.haze))
        put("water", rgb(s.water)); put("bed", rgb(s.bed)); put("beach", rgb(s.beach))
        put("g1", rgb(s.g1)); put("g2", rgb(s.g2))
        put("cliff", rgb(s.cliff)); put("snow", rgb(s.snow)); put("rock", rgb(s.rock)); put("earth", rgb(s.earth))
        put("leafs", buildJsonArray { s.leafs.forEach { add(rgb(it)) } })
        put("sky", buildJsonArray { s.sky.forEach { add(buildJsonObject { put("pos", it.pos); put("rgb", rgb(it.color)) }) } })
        put("glowA", s.glowA)
    }

    private fun siteJson(site: ContinentSite): JsonObject = buildJsonObject {
        put("id", site.id); put("name", site.name)
        put("isWonder", site.isWonder); put("isHome", site.isHome); put("curated", site.curated)
        put("x", site.x); put("z", site.z); put("markerTop", site.markerTop)
        put("buildingCount", site.buildingCount)
    }

    /** presence 增量推送报文（页面 `setPresence`·同一对象也内嵌在 [continentJson] 的初始建造里）。 */
    fun presenceJson(presence: WorldWebPresence): String = presenceObject(presence).toString()

    private fun presenceObject(p: WorldWebPresence): JsonObject = buildJsonObject {
        put("cityId", p.cityId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("traveling", p.traveling)
        put("homeCityId", p.homeCityId?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    // ─────────────────────────── planetJson（契约 §5.1）───────────────────────────

    /**
     * 星球建造报文。[home] = 家所在的球面单位向量，由 [PlanetMath.homeUnitVector] **单源**算出
     * （图集坐标 → 经纬 → 单位球·JS 不自算），标记必须钉在这个点上。
     */
    fun planetJson(seed: Long, seedOff: Float, homeX: Int, homeY: Int, homeCityName: String): String {
        val home = PlanetMath.homeUnitVector(homeX, homeY)
        return buildJsonObject {
            put("seed", seed)
            put("seedOff", seedOff)
            put("home", buildJsonObject { put("x", home[0]); put("y", home[1]); put("z", home[2]) })
            put("homeCityName", homeCityName)
        }.toString()
    }

    // ─────────────────────────── pose 双向（契约 §4.2 / §5.2）───────────────────────────

    /** 大陆姿态 → web（六元·[ContinentCamSnapshot.ty] 恒 [CONTINENT_TARGET_Y] 故不外发）。 */
    fun continentPoseJson(snap: ContinentCamSnapshot, tDist: Float): String = buildJsonObject {
        put("yaw", snap.yaw); put("pitch", snap.pitch); put("dist", snap.dist)
        put("tx", snap.tx); put("tz", snap.tz); put("tdist", tDist)
    }.toString()

    /** web → 大陆姿态（心跳报文·字段缺失/非法 → null，调用方保留上一份）。 */
    fun continentPoseFrom(json: String): Pair<ContinentCamSnapshot, Float>? = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        ContinentCamSnapshot(
            yaw = o.num("yaw"), pitch = o.num("pitch"), dist = o.num("dist"),
            tx = o.num("tx"), ty = CONTINENT_TARGET_Y, tz = o.num("tz"),
        ) to o.num("tdist")
    }.getOrNull()

    /** 星球姿态 → web（三元·yaw/pitch/dist）。 */
    fun planetPoseJson(pose: Triple<Float, Float, Float>): String = buildJsonObject {
        put("yaw", pose.first); put("pitch", pose.second); put("dist", pose.third)
    }.toString()

    /** web → 星球姿态（同上·非法丢弃保留上一份）。 */
    fun planetPoseFrom(json: String): Triple<Float, Float, Float>? = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        Triple(o.num("yaw"), o.num("pitch"), o.num("dist"))
    }.getOrNull()

    /** 转场镜头目标（部分字段给也行·页面按 EaseInOut 补间·契约 §6）。null 的分量不发。 */
    fun playPoseJson(
        yaw: Float? = null, pitch: Float? = null, dist: Float? = null, tx: Float? = null, tz: Float? = null,
    ): String = buildJsonObject {
        yaw?.let { put("yaw", it) }
        pitch?.let { put("pitch", it) }
        dist?.let { put("dist", it) }
        tx?.let { put("tx", it) }
        tz?.let { put("tz", it) }
    }.toString()

    // ─────────────────────────── 小工具 ───────────────────────────

    private fun JsonObject.num(key: String): Float = getValue(key).jsonPrimitive.content.toFloat()

    private fun rgb(c: DoubleArray): JsonElement = buildJsonArray { add(c[0]); add(c[1]); add(c[2]) }
    private fun rgb(c: FloatArray): JsonElement = buildJsonArray { add(c[0]); add(c[1]); add(c[2]) }
}
