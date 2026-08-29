package com.situ.aichat.ui.world

import com.situ.aichat.world.cast.WorldAffinityStage
import com.situ.aichat.world.stage.StageMode
import com.situ.aichat.world.stage.StagedCharacter
import com.situ.aichat.world.stage.StagedNative
import com.situ.aichat.world.stage.StagedPet
import com.situ.aichat.world.stage.WorldTownCast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * [WorldCastAnchors] T1（W9d 图纸 §7·E5 民居认领确定性 / fillers 空退环位·E12 卡片上限 3 + 溢出·§4.6A 锚规则）。
 */
class WorldCastAnchorsTest {

    private val yunye = "city_yunye"
    private val places = mapOf(
        "yunye_cafe" to Triple(2f, 3.3f, -1f),  // 建筑（hasInterior→+2.0）
        "yunye_square" to Triple(-2f, 3.6f, 1f), // 环境（+1.6）
        "yunye_home" to Triple(3f, 3.5f, 2f),
    )
    private val fillers = listOf(8.6f to 3.2f, -6.8f to -3.8f, 6.2f to 7.4f, -2.2f to 7.8f, 9.2f to -4.2f)
    private fun hasInterior(id: String) = id == "yunye_cafe" || id == "yunye_home"

    private fun ch(uuid: String, mode: StageMode, placeId: String? = null) =
        StagedCharacter(uuid, "角色$uuid", null, mode, placeId, "上午·活动", visiting = false)

    private fun cast(chars: List<StagedCharacter> = emptyList(), natives: List<StagedNative> = emptyList(), pets: List<StagedPet> = emptyList()) =
        WorldTownCast(yunye, chars, natives, pets)

    private fun placement(c: WorldTownCast, fs: List<Pair<Float, Float>> = fillers) =
        WorldCastAnchors.place(c, places, fs, homePlaceId = "yunye_home", hasInterior = ::hasInterior)

    // ---- E5 民居认领 ----

    @Test
    fun `E5 同uuid恒同民居`() {
        val a = placement(cast(listOf(ch("u1", StageMode.SLEEPING)))).cards.single()
        val b = placement(cast(listOf(ch("u1", StageMode.SLEEPING)))).cards.single()
        assertEquals(a.x, b.x, 1e-4f); assertEquals(a.z, b.z, 1e-4f)
        assertEquals("睡眠 y = 4.3", 4.3f, a.y, 1e-4f)
        assertTrue("落在某民居", fillers.any { hypot((it.first - a.x).toDouble(), (it.second - a.z).toDouble()) < 1e-3 })
    }

    @Test
    fun `E5 fillers空_睡眠退环位`() {
        val a = placement(cast(listOf(ch("u1", StageMode.SLEEPING))), fs = emptyList()).cards.single()
        assertEquals("退环位 y2.2", 2.2f, a.y, 1e-4f) // 非 4.3 民居
    }

    @Test
    fun `E5 AT_HOME与SLEEPING同民居锚_呼吸卡无月牙（R1返工）`() {
        val sleep = placement(cast(listOf(ch("u1", StageMode.SLEEPING)))).cards.single()
        val home = placement(cast(listOf(ch("u1", StageMode.AT_HOME)))).cards.single()
        // 两态共用同一民居锚（同 uuid 恒同民居·§4.6A）。
        assertEquals("同民居 x", sleep.x, home.x, 1e-4f)
        assertEquals("同民居 z", sleep.z, home.z, 1e-4f)
        assertEquals("民居 y4.3", 4.3f, home.y, 1e-4f)
        // 呈现差：SLEEPING = 月牙不呼吸；AT_HOME = 呼吸无月牙。
        val homeKind = home.kind as CastCardKind.Character
        assertFalse("AT_HOME 不睡", homeKind.sleeping)
        assertTrue("AT_HOME 标记", homeKind.atHome)
        val sleepKind = sleep.kind as CastCardKind.Character
        assertTrue("SLEEPING 睡", sleepKind.sleeping)
        assertFalse("SLEEPING 非在家", sleepKind.atHome)
    }

    // ---- E12 卡片上限 ----

    @Test
    fun `E12 同地点5卡_取3+溢出2`() {
        val chars = (1..5).map { ch("u$it", StageMode.AT_PLACE, "yunye_cafe") }
        val p = placement(cast(chars))
        assertEquals(3, p.cards.size)
        assertEquals(1, p.overflows.size)
        assertEquals(2, p.overflows.single().count)
        // 扇形偏移 = (k-1)*1.1（n=3·中心居中）
        val xs = p.cards.map { it.x }.sorted()
        assertEquals(1.1f, xs[1] - xs[0], 1e-3f); assertEquals(1.1f, xs[2] - xs[1], 1e-3f)
    }

    // ---- AT_PLACE 抬升 ----

    @Test
    fun `AT_PLACE 建筑加2_环境加1_6`() {
        val bldg = placement(cast(listOf(ch("u1", StageMode.AT_PLACE, "yunye_cafe")))).cards.single()
        assertEquals(3.3f + 2.0f, bldg.y, 1e-4f)
        val env = placement(cast(listOf(ch("u2", StageMode.AT_PLACE, "yunye_square")))).cards.single()
        assertEquals(3.6f + 1.6f, env.y, 1e-4f)
    }

    // ---- IN_TOWN 环位 ----

    @Test
    fun `IN_TOWN 环位_半径2_6绕城中锚`() {
        val chars = (1..4).map { ch("u$it", StageMode.IN_TOWN) }
        val p = placement(cast(chars))
        assertEquals(4, p.cards.size)
        val cx = -1.8f; val cz = 1.8f // yunye 城中锚
        p.cards.forEach {
            assertEquals("y2.2", 2.2f, it.y, 1e-4f)
            assertEquals("半径 2.6", 2.6f, hypot((it.x - cx).toDouble(), (it.z - cz).toDouble()).toFloat(), 1e-2f)
        }
    }

    // ---- 三期卷一 walking ----

    @Test
    fun `walking 环位客true_其余钉卡false`() {
        val ringMystery = StagedNative("native:x", "x", "小X", discovered = false, placeId = null, oneLiner = "", stage = WorldAffinityStage.STRANGER)
        val placeNat = StagedNative("native:y", "y", "小Y", discovered = true, placeId = "yunye_cafe", oneLiner = "", stage = WorldAffinityStage.STRANGER)
        val p = placement(
            cast(
                chars = listOf(ch("u1", StageMode.IN_TOWN), ch("u2", StageMode.AT_PLACE, "yunye_cafe"), ch("u3", StageMode.SLEEPING)),
                natives = listOf(ringMystery, placeNat),
                pets = listOf(StagedPet("p1", "u1", "团子")),
            ),
        )
        fun card(id: String) = p.cards.single { it.kind.id() == id }
        assertTrue("IN_TOWN 角色走动", card("u1").walking)
        assertTrue("环位神秘人也走动", card("native:x").walking)
        assertFalse("AT_PLACE 角色钉卡", card("u2").walking)
        assertFalse("睡着钉民居", card("u3").walking)
        assertFalse("在地点原住民钉卡", card("native:y").walking)
        assertFalse("宠物钉家", card("p1").walking)
    }

    @Test
    fun `walking E5退环位的睡着卡不走`() {
        val a = placement(cast(listOf(ch("u1", StageMode.SLEEPING))), fs = emptyList()).cards.single()
        assertFalse("E5 退环位仍不走（月牙不上街）", a.walking)
    }

    @Test
    fun `原住民程序城落环位_角色后接续`() {
        val nat = StagedNative("native:x", "x", "小X", discovered = true, placeId = null, oneLiner = "", stage = WorldAffinityStage.STRANGER)
        val p = placement(cast(chars = listOf(ch("u1", StageMode.IN_TOWN)), natives = listOf(nat)))
        assertEquals(2, p.cards.size) // 1 角色 + 1 原住民同环
        p.cards.forEach { assertEquals(2.2f, it.y, 1e-4f) }
    }
}
