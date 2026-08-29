package com.situ.aichat.ui.world.web

import com.situ.aichat.ui.world.CastCard
import com.situ.aichat.ui.world.CastCardKind
import com.situ.aichat.ui.world.CastOverflow
import com.situ.aichat.ui.world.CastPlacement
import com.situ.aichat.ui.world.town.TownAmbience
import com.situ.aichat.ui.world.town.TownCamSnapshot
import com.situ.aichat.ui.world.town.TownData
import com.situ.aichat.ui.world.town.TownSceneData
import com.situ.aichat.ui.world.town.TownStrings
import com.situ.aichat.world.atlas.CityTier
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.cast.WorldAffinityStage
import com.situ.aichat.world.stage.StageMode
import com.situ.aichat.world.stage.StagedCharacter
import com.situ.aichat.world.stage.StagedNative
import com.situ.aichat.world.stage.StagedPet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * [TownWebData] T1 金标（图纸「网页世界一期」§7 T1-1）。**断言全部从规格独立反推**，不照抄实现输出：
 * 地点坐标按 §3.3 的 G 映射 `((g − 中心)×3.6)` 手算、锚高按 [com.situ.aichat.ui.world.town.TownLayout]
 * 手写表的 `h + 1.1` 手算、天空 7 停靠按 demo 原 hex 手打、氛围三档按 [TownAmbience] 规格（相位表 +
 * 预设值 + lampT/duskSec 规则）反推。
 */
class TownWebDataTest {

    private val json = Json

    // §4.5 全串（正文不外发·此处只为构造 TownData·内容与断言无关）。
    private val strings = TownStrings(subtitleTemplate = "%1\$s · %2\$s", placeBodies = emptyMap())

    private fun town(seed: Long, cityId: String): TownData =
        TownSceneData.of(WorldAtlas.of(seed), cityId, strings)

    private fun parse(s: String): JsonObject = json.parseToJsonElement(s).jsonObject

    private fun JsonObject.f(key: String): Float = getValue(key).jsonPrimitive.float
    private fun JsonObject.rgb(key: String): Triple<Float, Float, Float> {
        val a = getValue(key).jsonArray
        return Triple(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float, a[2].jsonPrimitive.float)
    }

    // ─────────────────────────── 云野镇金标（§7 T1-1 ①）───────────────────────────

    /** demo 原 7 停靠（TownSceneData 的 YUNYE_SKY·此处按 hex 重新打字，双保险 pin）。 */
    private val yunyeSkyHex = listOf(
        0.0f to 0x233054, 0.26f to 0x3A4874, 0.44f to 0x6A6490, 0.56f to 0xA57F8C,
        0.66f to 0xC98A76, 0.78f to 0xE8B87E, 1.0f to 0xEFC98F,
    )

    @Test
    fun `云野镇 sky 7 停靠逐值`() {
        val sky = parse(TownWebData.townJson(town(42L, "city_yunye"))).getValue("sky").jsonArray
        assertEquals("7 停靠", 7, sky.size)
        sky.forEachIndexed { i, e ->
            val o = e.jsonObject
            val (pos, hex) = yunyeSkyHex[i]
            assertEquals("停靠$i pos", pos, o.f("pos"), 1e-6f)
            val (r, g, b) = o.rgb("rgb")
            assertEquals("停靠$i r", ((hex shr 16) and 255) / 255f, r, 1e-6f)
            assertEquals("停靠$i g", ((hex shr 8) and 255) / 255f, g, 1e-6f)
            assertEquals("停靠$i b", (hex and 255) / 255f, b, 1e-6f)
        }
    }

    @Test
    fun `云野镇 places 七处坐标 = G 映射手算值`() {
        // G(gx,gy) = ((gx − 5.5)×3.6, (gy − 6.5)×3.6)；top = 手写表 h + 1.1（环境地点为表内直给值）。
        val expected = mapOf(
            "yunye_home" to Triple(1.8f, 1.8f, 3.5f),
            "yunye_cafe" to Triple(-1.8f, -1.8f, 3.3f),
            "yunye_book" to Triple(5.4f, -1.8f, 3.7f),
            "yunye_park" to Triple(-5.4f, 5.4f, 2.6f),
            "yunye_dock" to Triple(-9.0f, -5.4f, 1.2f),
            "yunye_eat" to Triple(1.8f, -5.4f, 3.1f),
            "yunye_square" to Triple(-1.8f, 1.8f, 3.6f),
        )
        val places = parse(TownWebData.townJson(town(42L, "city_yunye"))).getValue("places").jsonArray
        assertEquals("地点件数", expected.size, places.size)
        places.forEach { e ->
            val o = e.jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            val (x, z, top) = requireNotNull(expected[id]) { "多出地点 $id" }
            assertEquals("$id x", x, o.f("x"), 1e-4f)
            assertEquals("$id z", z, o.f("z"), 1e-4f)
            assertEquals("$id top", top, o.f("top"), 1e-4f)
            assertFalse("正文不外发", o.containsKey("body"))
        }
    }

    @Test
    fun `云野镇 buildings 与手写表件数一致且尺寸色值直传`() {
        val data = town(42L, "city_yunye")
        val o = parse(TownWebData.townJson(data))
        val arr = o.getValue("buildings").jsonArray
        assertEquals("手写表四栋可点建筑", 4, arr.size)
        assertEquals(data.layout.buildings.size, arr.size)
        // 逐栋对布局表原值（序不变 + 八字段直传）。
        data.layout.buildings.forEachIndexed { i, b ->
            val j = arr[i].jsonObject
            assertEquals(b.cx.toFloat(), j.f("cx"), 1e-4f); assertEquals(b.cz.toFloat(), j.f("cz"), 1e-4f)
            assertEquals(b.sx.toFloat(), j.f("sx"), 1e-4f); assertEquals(b.h.toFloat(), j.f("h"), 1e-4f)
            assertEquals(b.sz.toFloat(), j.f("sz"), 1e-4f)
            assertEquals(b.windows, j.getValue("windows").jsonPrimitive.int)
            assertEquals(b.wall[0].toFloat(), j.rgb("wall").first, 1e-4f)
            assertEquals(b.roof[2].toFloat(), j.rgb("roof").third, 1e-4f)
        }
        assertEquals("云野镇 = 西河", "WEST_RIVER", o.getValue("water").jsonPrimitive.content)
        assertEquals("地面色 = 0xC7A987 的 r", 0xC7 / 255f, o.rgb("ground").first, 1e-4f)
        assertTrue("精修城", o.getValue("curated").jsonPrimitive.content.toBoolean())
    }

    // ─────────────────────────── 程序城金标（§7 T1-1 ②）───────────────────────────

    private fun genCity(seed: Long): String =
        WorldAtlas.of(seed).cities.first { !it.curated && it.tier == CityTier.TOWN }.id

    @Test
    fun `程序城 同 seed 两次序列化字节相等`() {
        val id = genCity(42L)
        assertEquals(TownWebData.townJson(town(42L, id)), TownWebData.townJson(town(42L, id)))
    }

    @Test
    fun `程序城 grammar 条目全数出报文且 t_style 值域合法`() {
        val data = town(42L, genCity(42L))
        val o = parse(TownWebData.townJson(data))
        val g = o.getValue("grammar").jsonArray
        assertTrue("程序城靠 grammar 成镇", g.isNotEmpty())
        assertEquals("一件不丢", data.layout.grammar.size, g.size)
        // 分型件数对源：lit / emis / roof 三桶各自守恒（防路由把某型漏发或错发）。
        val srcLit = data.layout.grammar.count { it is com.situ.aichat.ui.world.town.GrammarPart.LitBox }
        val srcEmis = data.layout.grammar.count { it is com.situ.aichat.ui.world.town.GrammarPart.EmisBox }
        val srcRoof = data.layout.grammar.count { it is com.situ.aichat.ui.world.town.GrammarPart.Roof }
        assertEquals(srcLit, g.count { it.jsonObject.getValue("t").jsonPrimitive.content == "lit" })
        assertEquals(srcEmis, g.count { it.jsonObject.getValue("t").jsonPrimitive.content == "emis" })
        assertEquals(srcRoof, g.count { it.jsonObject.getValue("t").jsonPrimitive.content == "roof" })
        g.forEach { e ->
            val j = e.jsonObject
            assertTrue("t 值域", j.getValue("t").jsonPrimitive.content in setOf("lit", "emis", "roof"))
            if (j.getValue("t").jsonPrimitive.content == "roof") {
                assertTrue("三式", j.getValue("style").jsonPrimitive.content in setOf("GABLE", "PYRAMID", "FLAT"))
            }
            // 角锚定六元齐全（JS 侧靠 x+sx/2 换中心·缺一即错位）。
            listOf("x", "y", "z", "sx", "h", "sz").forEach { k -> assertTrue("缺 $k", j.containsKey(k)) }
        }
        assertTrue("程序城无手写建筑", o.getValue("buildings").jsonArray.isEmpty())
        assertTrue("程序城无地点", o.getValue("places").jsonArray.isEmpty())
    }

    // ─────────────────────────── ambJson 三档金标（§7 T1-1 ③）───────────────────────────

    @Test
    fun `ambJson 白天档 12点`() {
        val o = parse(TownWebData.ambienceJson(TownAmbience.current(LocalTime.of(12, 0))))
        // WorldClock：07:00-17:00 = DAY → paintedPhase 0；无边界叠化（最近边界 17:00±27min）。
        assertEquals(0, o.getValue("phase").jsonPrimitive.int)
        assertEquals("白天灯全灭", 0f, o.f("lampT"), 1e-6f)
        assertEquals("白天不错峰", 0f, o.f("duskSec"), 1e-6f)
        assertEquals("DAYISH glowA", 0.45f, o.f("glowA"), 1e-6f)
        assertEquals(Triple(1f, 1f, 1f), o.rgb("tint"))
        assertEquals("DAYISH fog", Triple(0.79f, 0.54f, 0.46f), o.rgb("fog"))
    }

    @Test
    fun `ambJson 黄昏档 18点`() {
        val o = parse(TownWebData.ambienceJson(TownAmbience.current(LocalTime.of(18, 0))))
        assertEquals(1, o.getValue("phase").jsonPrimitive.int)
        assertEquals("DUSK 灯火满开", 1f, o.f("lampT"), 1e-6f)
        assertEquals("距 17:00 一小时", 3600f, o.f("duskSec"), 1e-6f)
        assertEquals("DUSK glowA", 0.85f, o.f("glowA"), 1e-6f)
        assertEquals("DUSK tint", Triple(0.86f, 0.74f, 0.70f), o.rgb("tint"))
        assertEquals("DUSK fog", Triple(0.44f, 0.31f, 0.31f), o.rgb("fog"))
    }

    @Test
    fun `ambJson 深夜档 22点`() {
        val o = parse(TownWebData.ambienceJson(TownAmbience.current(LocalTime.of(22, 0))))
        assertEquals(2, o.getValue("phase").jsonPrimitive.int)
        assertEquals(1f, o.f("lampT"), 1e-6f)
        assertEquals("NIGHT 恒 3600", 3600f, o.f("duskSec"), 1e-6f)
        assertEquals("NIGHT glowA", 0.16f, o.f("glowA"), 1e-6f)
        assertEquals("NIGHT tint", Triple(0.40f, 0.47f, 0.72f), o.rgb("tint"))
        assertEquals("NIGHT fog", Triple(0.15f, 0.18f, 0.30f), o.rgb("fog"))
    }

    // ─────────────────────────── castJson（§7 T1-1 ④）───────────────────────────

    private fun ch(uuid: String, sleeping: Boolean = false, atHome: Boolean = false, name: String = "小音") =
        CastCardKind.Character(
            StagedCharacter(uuid, name, null, if (sleeping) StageMode.SLEEPING else StageMode.IN_TOWN, null, "", visiting = false),
            sleeping = sleeping,
            atHome = atHome,
        )

    private fun native(id: String, discovered: Boolean, name: String = "灰炉师傅") =
        CastCardKind.Native(StagedNative(id, "slug_$id", name, discovered, null, "一句话", WorldAffinityStage.STRANGER))

    @Test
    fun `castJson 五卡 kind_name_avatar_present 映射`() {
        val cards = listOf(
            CastCard(ch("u1", atHome = true), 1f, 2f, 3f),
            CastCard(ch("u2", sleeping = true, name = "阿岸"), 4f, 5f, 6f),
            CastCard(native("n1", discovered = true), 7f, 8f, 9f),
            CastCard(native("n2", discovered = false, name = "不该外发的名字"), 10f, 11f, 12f),
            CastCard(CastCardKind.Pet(StagedPet("p1", "u1", "团子")), 13f, 14f, 15f),
            CastCard(ch("u3", name = "走动客"), 16f, 2.2f, 17f, walking = true),
        )
        val placement = CastPlacement(cards, listOf(CastOverflow(20f, 21f, 22f, 3)))
        val s = TownWebData.castJson(placement, mapOf("u1" to "QUJD"))
        val o = parse(s)
        val arr = o.getValue("cards").jsonArray
        assertEquals(6, arr.size)

        val u1 = arr[0].jsonObject
        assertEquals("member", u1.getValue("kind").jsonPrimitive.content)
        assertEquals("小音", u1.getValue("name").jsonPrimitive.content)
        assertEquals("QUJD", u1.getValue("avatar").jsonPrimitive.content)
        assertTrue(u1.getValue("present").jsonPrimitive.content.toBoolean())
        assertEquals(1f, u1.f("x"), 1e-6f); assertEquals(2f, u1.f("y"), 1e-6f); assertEquals(3f, u1.f("z"), 1e-6f)

        val u2 = arr[1].jsonObject
        assertEquals("睡着 → present=false（JS 蒙灰）", "false", u2.getValue("present").jsonPrimitive.content)
        assertSame("无头像 → null 非空串（JS 靠此画首字圆）", JsonNull, u2.getValue("avatar"))
        // W-1（契约 v1.2）：下标位两态——睡着卡 sleeping=true、在家卡 atHome=true、非 Character 恒双 false。
        assertEquals("睡着卡 sleeping", "true", u2.getValue("sleeping").jsonPrimitive.content)
        assertEquals("睡着卡非在家", "false", u2.getValue("atHome").jsonPrimitive.content)
        assertEquals("在家卡 atHome", "true", u1.getValue("atHome").jsonPrimitive.content)
        assertEquals("在家卡非睡着", "false", u1.getValue("sleeping").jsonPrimitive.content)
        assertEquals("原住民恒无下标", "false", arr[2].jsonObject.getValue("sleeping").jsonPrimitive.content)
        assertEquals("宠物恒无下标", "false", arr[4].jsonObject.getValue("atHome").jsonPrimitive.content)
        // 三期卷一（契约 v1.3）：walking 外发——走动客 true·钉卡 false。
        assertEquals("走动客 walking", "true", arr[5].jsonObject.getValue("walking").jsonPrimitive.content)
        assertEquals("钉卡 walking=false", "false", u1.getValue("walking").jsonPrimitive.content)
        assertEquals("睡着卡恒不走", "false", u2.getValue("walking").jsonPrimitive.content)

        assertEquals("native", arr[2].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("灰炉师傅", arr[2].jsonObject.getValue("name").jsonPrimitive.content)

        val myst = arr[3].jsonObject
        assertEquals("mystery", myst.getValue("kind").jsonPrimitive.content)
        assertEquals("未发现原住民真名不外发", "", myst.getValue("name").jsonPrimitive.content)
        assertFalse("报文里也不许出现真名", s.contains("不该外发的名字"))

        assertEquals("pet", arr[4].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("团子", arr[4].jsonObject.getValue("name").jsonPrimitive.content)

        val of = o.getValue("overflows").jsonArray.single().jsonObject
        assertEquals(3, of.getValue("count").jsonPrimitive.int)
        assertEquals(20f, of.f("x"), 1e-6f)
    }

    @Test
    fun `castJson 空表 = 空两组`() {
        val o = parse(TownWebData.castJson(CastPlacement(emptyList(), emptyList()), emptyMap()))
        assertTrue(o.getValue("cards").jsonArray.isEmpty())
        assertTrue(o.getValue("overflows").jsonArray.isEmpty())
    }

    // ─────────────────────────── pose / flags（§7 T1-1 ⑤）───────────────────────────

    @Test
    fun `pose 双向换算恒等 且 ty 恒补 0_8`() {
        val src = TownCamSnapshot(yaw = 0.7f, pitch = 0.36f, dist = 30f, tx = -1.5f, ty = 0.8f, tz = -1.0f)
        val back = requireNotNull(TownWebData.poseFrom(TownWebData.poseJson(src)))
        assertEquals(src.yaw, back.yaw, 1e-6f)
        assertEquals(src.pitch, back.pitch, 1e-6f)
        assertEquals(src.dist, back.dist, 1e-6f)
        assertEquals(src.tx, back.tx, 1e-6f)
        assertEquals(src.tz, back.tz, 1e-6f)
        assertEquals("target y 单源补回", 0.8f, back.ty, 1e-6f)
        assertFalse("ty 不外发", TownWebData.poseJson(src).contains("\"ty\""))
    }

    @Test
    fun `poseFrom 非法报文给 null`() {
        assertEquals(null, TownWebData.poseFrom("not json"))
        assertEquals(null, TownWebData.poseFrom("{}"))
        assertEquals(null, TownWebData.poseFrom("""{"yaw":0.1,"pitch":0.2,"dist":30,"tx":0}"""))
        assertEquals(null, TownWebData.poseFrom("""{"yaw":"x","pitch":0.2,"dist":30,"tx":0,"tz":0}"""))
    }

    @Test
    fun `flagsJson 三旗直传`() {
        val o = parse(TownWebData.flagsJson(reduceMotion = true, staticMode = false, interactive = true))
        assertEquals("true", o.getValue("reduceMotion").jsonPrimitive.content)
        assertEquals("false", o.getValue("staticMode").jsonPrimitive.content)
        assertEquals("true", o.getValue("interactive").jsonPrimitive.content)
    }
}
