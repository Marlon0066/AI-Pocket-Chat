package com.situ.aichat.ui.world.web

import com.situ.aichat.ui.world.continent.ContinentCamSnapshot
import com.situ.aichat.ui.world.continent.ContinentSceneData
import com.situ.aichat.ui.world.continent.ContinentStrings
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldRegions
import com.situ.aichat.world.atlas.WorldWonders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldWebData] T1 金标（图纸「网页世界二期」§2/§6）。**断言全部从规格独立反推**，不照抄实现输出：
 *
 * - style 逐值 = `ContinentStyle.kt` 源表的 hex / 数值**重新打字**（willow_mist + ochre_dry 两反差区·
 *   颜色按 `hex/255` 手算，与实现的 `c(hex)` 各算一遍）；
 * - sites 坐标 = 图纸 §3.3 的 G 映射公式 `((atlas − 区中心) / radiusLi × 14)` 在测试里独立复算，
 *   markerTop = `padH + 1.6`（城）/ `padH + 6.2`（奇观）手算，buildingCount 按「家/CITY=3·其余城=2·奇观=0」规则；
 * - home 单位向量 = 契约 §5.1 的球面点，按 `lat = 45 − y/2600×84`、`lon = x/4800×360 − 180` 手算到小数第七位；
 * - pose 双向恒等 ×2 + 非法丢弃；presence 三态；同输入两次序列化字节相等（确定性）。
 */
class WorldWebDataTest {

    private val json = Json

    /** 站点卡正文由本类装配、恒不外发——此处只为构造 [ContinentSceneData]，内容与断言无关。 */
    private val strings = ContinentStrings(
        cityBodyTemplate = "%1\$s·%2\$s·%3\$s",
        tierSmall = "小",
        tierTown = "镇",
        tierCity = "城",
        wonderBodyTemplate = "%1\$s",
        curatedBodies = emptyMap(),
    )

    private val presenceHere = WorldWebPresence(cityId = "city_yunye", traveling = false, homeCityId = "city_yunye")

    private fun scene(seed: Long, regionId: String): ContinentSceneData =
        ContinentSceneData.fromAtlas(WorldAtlas.of(seed), regionId, strings)

    private fun parse(s: String): JsonObject = json.parseToJsonElement(s).jsonObject
    private fun JsonObject.f(key: String): Float = getValue(key).jsonPrimitive.float
    private fun JsonObject.rgb(key: String): Triple<Float, Float, Float> {
        val a = getValue(key).jsonArray
        return Triple(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float, a[2].jsonPrimitive.float)
    }

    /** hex → RGB 0..1（= 源表 `c(hex)` 的规格，测试侧独立实现）。 */
    private fun hex(h: Int): Triple<Float, Float, Float> =
        Triple(((h shr 16) and 255) / 255f, ((h shr 8) and 255) / 255f, (h and 255) / 255f)

    private fun assertRgb(expected: Triple<Float, Float, Float>, actual: Triple<Float, Float, Float>, what: String) {
        assertEquals("$what.r", expected.first, actual.first, 1e-6f)
        assertEquals("$what.g", expected.second, actual.second, 1e-6f)
        assertEquals("$what.b", expected.third, actual.third, 1e-6f)
    }

    // ─────────────────────────── ① style 逐值（willow_mist / 云泽大区）───────────────────────────

    @Test
    fun continentJson_yunzeStyle_matchesSourceTableValueByValue() {
        val o = parse(WorldWebData.continentJson(scene(7L, "yunze"), presenceHere))
        assertEquals("yunze", o.getValue("regionId").jsonPrimitive.content)
        assertEquals("云泽大区", o.getValue("regionName").jsonPrimitive.content)
        assertTrue(o.getValue("isHome").jsonPrimitive.boolean)

        val s = o.getValue("style").jsonObject
        assertEquals("willow_mist", s.getValue("styleKey").jsonPrimitive.content)
        assertEquals(11.7f, s.f("seed"), 1e-5f)
        assertEquals(0.46f, s.f("sea"), 1e-5f)
        assertEquals(5.2f, s.f("amp"), 1e-5f)
        assertEquals(0.60f, s.f("coast"), 1e-5f)
        assertEquals(1.5f, s.f("padH"), 1e-5f)
        assertFalse(s.getValue("terrace").jsonPrimitive.boolean)
        assertEquals(4.4f, s.f("snowLine"), 1e-5f)
        assertEquals(60, s.getValue("treeN").jsonPrimitive.int)
        assertEquals(0.7f, s.f("trunk"), 1e-5f)
        assertEquals(0.8f, s.f("treeR"), 1e-5f)
        assertEquals(1.5f, s.f("treeH"), 1e-5f)
        assertEquals(1.0f, s.f("glowA"), 1e-5f)

        assertRgb(Triple(1.0f, 0.86f, 0.70f), s.rgb("warm"), "warm")
        assertRgb(Triple(0.79f, 0.54f, 0.46f), s.rgb("haze"), "haze")
        assertRgb(hex(0x3E5C6E), s.rgb("water"), "water")
        assertRgb(hex(0x51606A), s.rgb("bed"), "bed")
        assertRgb(hex(0xD9C3A3), s.rgb("beach"), "beach")
        assertRgb(hex(0x8FA37E), s.rgb("g1"), "g1")
        assertRgb(hex(0x7E926E), s.rgb("g2"), "g2")
        assertRgb(hex(0xC4A484), s.rgb("cliff"), "cliff")
        assertRgb(hex(0xEFEDE9), s.rgb("snow"), "snow")
        assertRgb(hex(0x9A8B7C), s.rgb("rock"), "rock")
        assertRgb(hex(0x6B5A48), s.rgb("earth"), "earth")

        val leafs = s.getValue("leafs").jsonArray
        assertEquals(2, leafs.size)
        assertRgb(hex(0x7E926E), leafs[0].jsonArray.let { Triple(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float, it[2].jsonPrimitive.float) }, "leafs0")
        assertRgb(hex(0x8FA37E), leafs[1].jsonArray.let { Triple(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float, it[2].jsonPrimitive.float) }, "leafs1")

        // 天空 5 停靠（pos + hex 重新打字·契约 §4.1 锁「5 停靠」）。
        val sky = s.getValue("sky").jsonArray
        assertEquals(5, sky.size)
        val expected = listOf(0.0f to 0x16203A, 0.34f to 0x3A4874, 0.58f to 0x8A6E86, 0.74f to 0xC98A76, 1.0f to 0xE8B87E)
        expected.forEachIndexed { i, (pos, h) ->
            val stop = sky[i].jsonObject
            assertEquals("sky[$i].pos", pos, stop.f("pos"), 1e-6f)
            assertRgb(hex(h), stop.rgb("rgb"), "sky[$i].rgb")
        }
    }

    // ─────────────────────────── ② 反差区（ochre_dry / 黄砂高原·terrace + 无雪）───────────────────────────

    @Test
    fun continentJson_huangshaStyle_isTerracedAndSnowless() {
        val s = parse(WorldWebData.continentJson(scene(7L, "huangsha"), presenceHere)).getValue("style").jsonObject
        assertEquals("ochre_dry", s.getValue("styleKey").jsonPrimitive.content)
        assertEquals(41.2f, s.f("seed"), 1e-5f)
        assertEquals(0.38f, s.f("sea"), 1e-5f)
        assertEquals(6.0f, s.f("amp"), 1e-5f)
        assertEquals(0.58f, s.f("coast"), 1e-5f)
        assertEquals(2.4f, s.f("padH"), 1e-5f)
        assertTrue("ochre_dry 是梯田地貌", s.getValue("terrace").jsonPrimitive.boolean)
        assertEquals("99 = 无雪线哨兵（契约 §4.1）", 99f, s.f("snowLine"), 1e-5f)
        assertEquals(10, s.getValue("treeN").jsonPrimitive.int)
        assertRgb(hex(0x4E7080), s.rgb("water"), "water")
        assertRgb(hex(0xC9A46B), s.rgb("g1"), "g1")
        assertRgb(hex(0x8E6B4E), s.rgb("cliff"), "cliff")
        assertEquals(1, s.getValue("leafs").jsonArray.size)
        assertEquals(5, s.getValue("sky").jsonArray.size)
        // 与云泽同码不同脸：十区靠 style 数据变脸（契约 §4.1 建造要求④）。
        val yunze = parse(WorldWebData.continentJson(scene(7L, "yunze"), presenceHere)).getValue("style").jsonObject
        assertFalse(yunze.getValue("terrace").jsonPrimitive.boolean)
        assertEquals(4.4f, yunze.f("snowLine"), 1e-5f)
    }

    // ─────────────────────────── ③ sites = G 映射独立复算 ───────────────────────────

    @Test
    fun continentJson_sites_matchIndependentlyComputedGMapping() {
        val seed = 4242L
        val atlas = WorldAtlas.of(seed)
        val region = WorldRegions.ALL.first { it.id == "yunze" }
        val padH = 1.5 // willow_mist（源表重新打字）
        val sites = parse(WorldWebData.continentJson(scene(seed, "yunze"), presenceHere)).getValue("sites").jsonArray

        val cities = atlas.citiesIn("yunze")
        val wonders = WorldWonders.ALL.filter { it.regionId == "yunze" }
        assertEquals("城在前奇观在后·总数守恒", cities.size + wonders.size, sites.size)
        assertTrue("云泽至少一城一奇观", cities.isNotEmpty() && wonders.isNotEmpty())

        // G 映射：(atlas 坐标 − 区中心) / radiusLi × SPREAD(14)。
        fun boxX(v: Int) = (v - region.centerX).toFloat() / region.radiusLi * 14f
        fun boxZ(v: Int) = (v - region.centerY).toFloat() / region.radiusLi * 14f

        cities.forEachIndexed { i, city ->
            val s = sites[i].jsonObject
            assertEquals(city.id, s.getValue("id").jsonPrimitive.content)
            assertEquals(city.name, s.getValue("name").jsonPrimitive.content)
            assertFalse(s.getValue("isWonder").jsonPrimitive.boolean)
            assertEquals("x@${city.id}", boxX(city.x), s.f("x"), 1e-4f)
            assertEquals("z@${city.id}", boxZ(city.y), s.f("z"), 1e-4f)
            assertEquals("markerTop@${city.id}", (padH + 1.6).toFloat(), s.f("markerTop"), 1e-5f)
            val expectBuildings = if (city.id == "city_yunye" || city.tier.name == "CITY") 3 else 2
            assertEquals("buildingCount@${city.id}", expectBuildings, s.getValue("buildingCount").jsonPrimitive.int)
        }
        wonders.forEachIndexed { i, w ->
            val s = sites[cities.size + i].jsonObject
            assertEquals(w.id, s.getValue("id").jsonPrimitive.content)
            assertTrue(s.getValue("isWonder").jsonPrimitive.boolean)
            assertEquals("x@${w.id}", boxX(w.x), s.f("x"), 1e-4f)
            assertEquals("z@${w.id}", boxZ(w.y), s.f("z"), 1e-4f)
            assertEquals("markerTop@${w.id}", (padH + 6.2).toFloat(), s.f("markerTop"), 1e-5f)
            assertEquals("奇观无楼", 0, s.getValue("buildingCount").jsonPrimitive.int)
        }

        // 家标：云野镇 isHome=true，其余站位 false。
        sites.forEach { el ->
            val s = el.jsonObject
            assertEquals(
                "isHome@${s.getValue("id").jsonPrimitive.content}",
                s.getValue("id").jsonPrimitive.content == "city_yunye",
                s.getValue("isHome").jsonPrimitive.boolean,
            )
        }
    }

    /** 站点卡正文恒在原生 sheet 渲染 → 报文里**没有** body 字段（§2 锁定·隐私/体积双理由）。 */
    @Test
    fun continentJson_neverShipsSiteBody() {
        val out = WorldWebData.continentJson(scene(9L, "yunze"), presenceHere)
        assertFalse("报文不得含 body 键", out.contains("\"body\""))
        parse(out).getValue("sites").jsonArray.forEach { assertNull(it.jsonObject["body"]) }
    }

    // ─────────────────────────── ④ presence 三态 ───────────────────────────

    @Test
    fun presenceJson_threeStates() {
        val here = parse(WorldWebData.presenceJson(presenceHere))
        assertEquals("city_yunye", here.getValue("cityId").jsonPrimitive.content)
        assertFalse(here.getValue("traveling").jsonPrimitive.boolean)
        assertEquals("city_yunye", here.getValue("homeCityId").jsonPrimitive.content)

        val traveling = parse(WorldWebData.presenceJson(WorldWebPresence(null, true, "city_yunye")))
        assertEquals(JsonNull, traveling.getValue("cityId"))
        assertTrue(traveling.getValue("traveling").jsonPrimitive.boolean)

        val none = parse(WorldWebData.presenceJson(WorldWebPresence(null, false, null)))
        assertEquals(JsonNull, none.getValue("cityId"))
        assertFalse(none.getValue("traveling").jsonPrimitive.boolean)
        assertEquals(JsonNull, none.getValue("homeCityId"))

        // 建造报文内嵌的 presence 与增量推送同形（契约 §4.1 与 §3.1 setPresence 同一对象）。
        val embedded = parse(WorldWebData.continentJson(scene(3L, "yunze"), presenceHere)).getValue("presence").jsonObject
        assertEquals(parse(WorldWebData.presenceJson(presenceHere)), embedded)
    }

    // ─────────────────────────── ⑤ planetJson·home 单位向量手算 ───────────────────────────

    @Test
    fun planetJson_homeUnitVector_matchesHandComputedSpherePoint() {
        val o = parse(WorldWebData.planetJson(seed = 123456789L, seedOff = 0.37f, homeX = 600, homeY = 1300, homeCityName = "云野镇"))
        assertEquals(123456789L, o.getValue("seed").jsonPrimitive.content.toLong())
        assertEquals(0.37f, o.f("seedOff"), 1e-6f)
        assertEquals("云野镇", o.getValue("homeCityName").jsonPrimitive.content)

        // lat = 45 − (1300/2600)×84 = 3.0°；lon = (600/4800)×360 − 180 = −135.0°
        // → [cos3°·cos(−135°), sin3°, cos3°·sin(−135°)]（手算到小数第七位）
        val home = o.getValue("home").jsonObject
        assertEquals(-0.7061377f, home.f("x"), 1e-6f)
        assertEquals(0.0523360f, home.f("y"), 1e-6f)
        assertEquals(-0.7061377f, home.f("z"), 1e-6f)

        // 另一点（赤道东经 0°）：lat = 45 − (1392.857.../2600)×84 ≈ 0 —— 取整点 y=2600 → lat = −39°、lon = 0°。
        val south = parse(WorldWebData.planetJson(1L, 0f, homeX = 2400, homeY = 2600, homeCityName = "x")).getValue("home").jsonObject
        assertEquals(0.7771460f, south.f("x"), 1e-6f) // cos(−39°)
        assertEquals(-0.6293204f, south.f("y"), 1e-6f) // sin(−39°)
        assertEquals(0.0f, south.f("z"), 1e-6f) // cos(−39°)·sin(0°)
    }

    // ─────────────────────────── ⑥ pose 双向恒等 ───────────────────────────

    @Test
    fun continentPose_roundTripsAndKeepsTargetY() {
        val snap = ContinentCamSnapshot(yaw = 0.78f, pitch = 0.72f, dist = 34f, tx = -3.25f, ty = 1.2f, tz = 5.5f)
        val out = parse(WorldWebData.continentPoseJson(snap, tDist = 26.5f))
        assertEquals(0.78f, out.f("yaw"), 1e-6f)
        assertEquals(0.72f, out.f("pitch"), 1e-6f)
        assertEquals(34f, out.f("dist"), 1e-6f)
        assertEquals(-3.25f, out.f("tx"), 1e-6f)
        assertEquals(5.5f, out.f("tz"), 1e-6f)
        assertEquals(26.5f, out.f("tdist"), 1e-6f)
        assertNull("ty 恒 1.2 不外发", out["ty"])

        val (back, tDist) = WorldWebData.continentPoseFrom(WorldWebData.continentPoseJson(snap, 26.5f))!!
        assertEquals(snap.yaw, back.yaw, 1e-6f)
        assertEquals(snap.pitch, back.pitch, 1e-6f)
        assertEquals(snap.dist, back.dist, 1e-6f)
        assertEquals(snap.tx, back.tx, 1e-6f)
        assertEquals("ty 由常量补回", 1.2f, back.ty, 1e-6f)
        assertEquals(snap.tz, back.tz, 1e-6f)
        assertEquals(26.5f, tDist, 1e-6f)

        // 非法/缺字段 → null（调用方保留上一份）。
        assertNull(WorldWebData.continentPoseFrom("{\"yaw\":0.1}"))
        assertNull(WorldWebData.continentPoseFrom("not json"))
        assertNull(WorldWebData.continentPoseFrom("{\"yaw\":\"x\",\"pitch\":0,\"dist\":0,\"tx\":0,\"tz\":0,\"tdist\":0}"))
    }

    @Test
    fun planetPose_roundTripsThreeTuple() {
        val pose = Triple(0.6f, -0.25f, 3.1f)
        val out = parse(WorldWebData.planetPoseJson(pose))
        assertEquals(0.6f, out.f("yaw"), 1e-6f)
        assertEquals(-0.25f, out.f("pitch"), 1e-6f)
        assertEquals(3.1f, out.f("dist"), 1e-6f)
        assertNull("星球 pose 只有三元", out["tx"])

        assertEquals(pose, WorldWebData.planetPoseFrom(WorldWebData.planetPoseJson(pose)))
        assertNull(WorldWebData.planetPoseFrom("{\"yaw\":0.6,\"pitch\":-0.25}"))
        assertNull(WorldWebData.planetPoseFrom(""))
    }

    /** playPose：只发给到的分量（契约 §6「部分给也行」）。 */
    @Test
    fun playPoseJson_emitsOnlyGivenComponents() {
        val exit = parse(WorldWebData.playPoseJson(pitch = 1.12f, dist = 95f))
        assertEquals(setOf("pitch", "dist"), exit.keys)
        assertEquals(1.12f, exit.f("pitch"), 1e-6f)
        assertEquals(95f, exit.f("dist"), 1e-6f)

        val dive = parse(WorldWebData.playPoseJson(yaw = 8.63938f, pitch = 0.4f, dist = 1.45f))
        assertEquals(setOf("yaw", "pitch", "dist"), dive.keys)

        val town = parse(WorldWebData.playPoseJson(dist = 4.5f, tx = -3.2f, tz = 5.6f))
        assertEquals(setOf("dist", "tx", "tz"), town.keys)
        assertEquals(-3.2f, town.f("tx"), 1e-6f)
    }

    // ─────────────────────────── ⑦ 确定性（同输入字节相等）───────────────────────────

    @Test
    fun serialization_isDeterministicForSameInput() {
        val a = WorldWebData.continentJson(scene(20260829L, "xiyulin"), presenceHere)
        val b = WorldWebData.continentJson(scene(20260829L, "xiyulin"), presenceHere)
        assertEquals("同 seed 同区两次序列化必须字节相等", a, b)

        val p1 = WorldWebData.planetJson(20260829L, 0.37f, 600, 1300, "云野镇")
        val p2 = WorldWebData.planetJson(20260829L, 0.37f, 600, 1300, "云野镇")
        assertEquals(p1, p2)

        // 十大区逐区可装配且各自 styleKey 不空（E4 金标侧·装机另抽 3 区）。
        val keys = WorldRegions.ALL.map { r ->
            parse(WorldWebData.continentJson(scene(20260829L, r.id), presenceHere))
                .getValue("style").jsonObject.getValue("styleKey").jsonPrimitive.content
        }
        assertEquals(10, keys.size)
        assertEquals("十区十种气质（styleKey 双射）", 10, keys.toSet().size)
    }
}
