package com.situ.aichat.ui.world.town

import com.situ.aichat.world.WorldClock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 小镇昼夜天色氛围（台阶0 图纸 §3.1·美术方向稿「温暖手绘绘本风」·黄昏燃灯第一优先）。
 *
 * **相位单源 = [WorldClock.phaseAt]**（世界物理常数·锁死）：DAWN [05:00,07:00) / DAY [07:00,17:00) /
 * DUSK [17:00,19:30) / NIGHT 其余。旧版自造「19:00 入夜 + 夜心 20:15±63min」三角权重，21:18 之后与
 * 00:00-05:00 会回落白天档（本次修复的 bug）——氛围侧只保留「边界 ±27min 叠化」这层化妆逻辑。
 *
 * 叠化（§3.1 锁定）：两侧都有色板（DUSK↔NIGHT）时 7 停靠逐停 lerp + tint/fog/glow 同步 lerp；白天侧无色板
 * （DAY↔DUSK / NIGHT↔DAWN）时只 lerp glow/tint/fog，天空取有色侧。太阳方向不参与叠化（按相位取预设）。
 *
 * 白天不接管天空（[Snapshot.skyColors] = null → 沿用各城 [TownData.sky] 对版值）；夜晚不再「压暗」而是
 * 「调色温」（[Snapshot.sceneTint] 取代旧 uNightDim 标量·§4.1）。reduceMotion / 静帧时渲染器冻结当前帧快照。
 */
internal object TownAmbience {

    /**
     * 单帧氛围快照（§3.1 锁定字段）：[skyColors] = null 表示沿用城市天空（7×rgb 展平 21）；[paintedPhase]
     * 0=无画层(DAWN/DAY) · 1=黄昏画层 · 2=深夜画层；[sceneTint]/[fog] = vec3；[lampT] 灯火主控（0=全灭(白天) ·
     * (0,1)=黎明渐灭段 · 1=点亮期）；[duskSec] = 距 17:00 的秒数（≥0·错峰哈希用·NIGHT 恒 3600f·DAWN/DAY 恒 0f）。
     */
    class Snapshot(
        val skyColors: FloatArray?,
        val glowA: Float,
        val sun: FloatArray,
        val paintedPhase: Int,
        val sceneTint: FloatArray,
        val fog: FloatArray,
        val lampT: Float,
        val duskSec: Float,
    )

    private class Preset(
        val sky: FloatArray?,   // 7×rgb 0..1 展平（21）·null = 白天沿用城市天空
        val glowA: Float,
        val sun: FloatArray,    // vec3
        val tint: FloatArray,   // vec3·场景色温（§4.1·取代旧 uNightDim 标量）
        val fog: FloatArray,    // vec3·距离雾色（值 = 旧 TownRenderer 硬编码三档·零变）
    )

    // 深夜蓝金基调：大面积沉靛蓝托极少量暖光；太阳压低成冷侧光（磨出体积感）。
    // tint 取旧暗度 0.50 的等亮蓝移（(0.40+0.47+0.72)/3 ≈ 0.53·§4.1）。
    private val NIGHT = Preset(
        sky = flat(
            arr(0x07, 0x0D, 0x1E), arr(0x0B, 0x12, 0x26), arr(0x11, 0x1A, 0x33),
            arr(0x19, 0x23, 0x40), arr(0x24, 0x2C, 0x4A), arr(0x2C, 0x33, 0x50),
            arr(0x1E, 0x24, 0x3A),
        ),
        glowA = 0.16f,
        sun = arr3(-0.30f, 0.22f, 0.66f),
        tint = arr3(0.40f, 0.47f, 0.72f),
        fog = arr3(0.15f, 0.18f, 0.30f),
    )

    // 黄昏霞橙：西天染暖、辉光最足（满镇燃灯的主背景）。tint 同理对旧暗度 0.80 暖移。
    private val DUSK = Preset(
        sky = flat(
            arr(0x3A, 0x30, 0x50), arr(0x58, 0x42, 0x55), arr(0x84, 0x59, 0x51),
            arr(0xC8, 0x87, 0x5A), arr(0xE8, 0xA8, 0x7C), arr(0xD9, 0x92, 0x66),
            arr(0x6E, 0x4A, 0x3C),
        ),
        glowA = 0.85f,
        sun = arr3(-0.72f, 0.24f, 0.48f),
        tint = arr3(0.86f, 0.74f, 0.70f),
        fog = arr3(0.44f, 0.31f, 0.31f),
    )

    // 白天/黎明（黎明走白天档·专属画层素材未出）：不接管天空，色温中性。
    private val DAYISH = Preset(
        sky = null,
        glowA = 0.45f,
        sun = arr3(-0.55f, 0.5f, 0.42f),
        tint = arr3(1.0f, 1.0f, 1.0f),
        fog = arr3(0.79f, 0.54f, 0.46f),
    )

    /** 边界叠化半宽（分钟·§3.1 锁定：每个边界 ±27min 线性）。 */
    private const val FADE_MIN = 27

    private const val DAWN_MIN = 5 * 60
    private const val DUSK_MIN = 17 * 60

    /** 一个相位边界的叠化定义（[atMin] 为边界分钟·[before]/[after] 为两侧预设）。 */
    private class Boundary(val atMin: Int, val before: Preset, val after: Preset)

    // 四个边界 = WorldClock 锁死值（07:00 两侧同为 DAYISH → 叠化恒等·列出以对齐相位表）。
    private val BOUNDARIES = arrayOf(
        Boundary(DAWN_MIN, NIGHT, DAYISH),
        Boundary(7 * 60, DAYISH, DAYISH),
        Boundary(DUSK_MIN, DAYISH, DUSK),
        Boundary(19 * 60 + 30, DUSK, NIGHT),
    )

    /**
     * 取当前时刻快照（渲染器每帧调·纯计算）。[now] = 设备本地墙钟（J1·世界时区锚回退系统时区，两者事实等价）；
     * [reduceMotion] 时错峰取消（[Snapshot.duskSec] 直接给 3600f → 按相位瞬时亮/灭·§4.2）。
     */
    fun current(now: LocalTime = LocalTime.now(), reduceMotion: Boolean = false): Snapshot {
        val zone = ZoneId.systemDefault()
        val nowMs = LocalDate.now(zone).atTime(now).atZone(zone).toInstant().toEpochMilli()
        val phase = WorldClock.phaseAt(nowMs, zone)
        val base = presetOf(phase)
        val m = now.hour * 60 + now.minute
        val b = BOUNDARIES.firstOrNull { m >= it.atMin - FADE_MIN && m <= it.atMin + FADE_MIN }
        val t = if (b == null) 0f else (m - (b.atMin - FADE_MIN)).toFloat() / (2f * FADE_MIN)
        return Snapshot(
            skyColors = if (b == null) base.sky else blendSky(b.before.sky, b.after.sky, t),
            glowA = if (b == null) base.glowA else lerp(b.before.glowA, b.after.glowA, t),
            sun = base.sun,
            paintedPhase = when (phase) {
                WorldClock.DayPhase.DUSK -> 1
                WorldClock.DayPhase.NIGHT -> 2
                else -> 0
            },
            sceneTint = if (b == null) base.tint else lerp3(b.before.tint, b.after.tint, t),
            fog = if (b == null) base.fog else lerp3(b.before.fog, b.after.fog, t),
            lampT = lampTOf(phase, m),
            duskSec = duskSecOf(phase, now, reduceMotion),
        )
    }

    private fun presetOf(phase: WorldClock.DayPhase): Preset = when (phase) {
        WorldClock.DayPhase.DUSK -> DUSK
        WorldClock.DayPhase.NIGHT -> NIGHT
        WorldClock.DayPhase.DAWN, WorldClock.DayPhase.DAY -> DAYISH
    }

    /**
     * 灯火主控（§3.1 锁定）：DUSK / NIGHT 恒 1f；跨 NIGHT→DAWN 边界 04:33-05:27 从 1 线性降 0；其余（DAWN 末段
     * 与整个白天）恒 0f。黄昏侧不做渐升——17:00 起的点亮由 [Snapshot.duskSec] 错峰哈希独立驱动（§4.2）。
     */
    private fun lampTOf(phase: WorldClock.DayPhase, m: Int): Float = when {
        m >= DAWN_MIN - FADE_MIN && m <= DAWN_MIN + FADE_MIN ->
            1f - (m - (DAWN_MIN - FADE_MIN)).toFloat() / (2f * FADE_MIN)
        phase == WorldClock.DayPhase.DUSK || phase == WorldClock.DayPhase.NIGHT -> 1f
        else -> 0f
    }

    /**
     * 距 17:00 的秒数（§3.1 锁定·R1 修订 D-5）：DUSK 内实算（≥0）·NIGHT 恒 3600f·DAWN/DAY 恒 0f，
     * **黎明渐熄窗（04:33–05:27）的 DAWN 侧例外仍给 3600f**——否则 05:00 相位翻转把着色器 clamp 项瞬间归零，
     * `on = clamp(…)×lampT` 的整体渐熄会在半程被硬切（D-5·复核 R1 采纳施工方建议修法）。[reduceMotion] 时恒 3600f。
     */
    private fun duskSecOf(phase: WorldClock.DayPhase, now: LocalTime, reduceMotion: Boolean): Float = when {
        phase == WorldClock.DayPhase.DAWN && now.hour * 60 + now.minute <= DAWN_MIN + FADE_MIN -> 3600f
        phase == WorldClock.DayPhase.DAWN || phase == WorldClock.DayPhase.DAY -> 0f
        reduceMotion || phase == WorldClock.DayPhase.NIGHT -> 3600f
        else -> (now.toSecondOfDay() - DUSK_MIN * 60).toFloat().coerceAtLeast(0f)
    }

    /** 天空叠化：两侧都有色板 → 7 停靠逐停 lerp；只一侧有 → 取有色侧；都无 → null（§3.1）。 */
    private fun blendSky(before: FloatArray?, after: FloatArray?, t: Float): FloatArray? = when {
        before != null && after != null -> FloatArray(21) { before[it] + (after[it] - before[it]) * t }
        else -> before ?: after
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun lerp3(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }
    private fun flat(vararg stops: FloatArray) = FloatArray(21) { stops[it / 3][it % 3] }
    private fun arr(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)
    private fun arr3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
}
