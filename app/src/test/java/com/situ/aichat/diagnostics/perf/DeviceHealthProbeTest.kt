package com.situ.aichat.diagnostics.perf

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * T2-2 的 E22 / E23 分支（图纸 2026-07-30 §5）：[DeviceHealthProbe] 取不到数时的兜底。
 *
 * 断言从规格独立反推：热节流取不到 → `-1` / `"unknown"`；电池温度取不到 → `Double.NaN`（不是 0，
 * 0 会被分析侧误读成「真的 0℃」）。取得到时如实换算（系统给的是 0.1℃ 精度的整数）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceHealthProbeTest {

    private val app = RuntimeEnvironment.getApplication()
    private val probe = DeviceHealthProbe(app)

    private fun header() = PerfHeader(PERF_SCHEMA_VERSION, 1_754_000_000_000L, PerfSampleKind.HEALTH)

    @Test
    fun `没有电池 sticky 广播时温度记 NaN 而不是 0（E23）`() {
        val sample = probe.sample(header(), scene = null)

        assertTrue("取不到必须是 NaN，0 会被读成真的 0℃", sample.batteryTempC.isNaN())
    }

    // 粘性广播无替代 API：本例要复现的正是「系统里已存在 sticky ACTION_BATTERY_CHANGED」这一前提。
    @Suppress("DEPRECATION")
    @Test
    fun `有电池 sticky 广播时按 0_1℃ 精度换算`() {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_TEMPERATURE, 351)
        app.sendStickyBroadcast(intent)

        assertEquals(35.1, probe.sample(header(), scene = null).batteryTempC, 1e-9)
    }

    @Test
    fun `热节流档位如实记录并给出档位名`() {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)

        val sample = probe.sample(header(), scene = PerfScenes.WORLD_PLANET)

        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, sample.thermalStatus)
        assertEquals("moderate", sample.thermalName)
        assertEquals(PerfScenes.WORLD_PLANET, sample.scene)
    }

    @Test
    fun `未知档位一律 unknown 不猜（E22）`() {
        assertEquals(DeviceHealthProbe.UNKNOWN_THERMAL_NAME, DeviceHealthProbe.thermalNameOf(-1))
        assertEquals(DeviceHealthProbe.UNKNOWN_THERMAL_NAME, DeviceHealthProbe.thermalNameOf(99))
    }

    @Test
    fun `七个已知档位各有名字`() {
        assertEquals(
            listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown"),
            (0..6).map { DeviceHealthProbe.thermalNameOf(it) },
        )
    }

    @Test
    fun `样本头原样带出（kind 恒为 health）`() {
        val sample = probe.sample(header(), scene = null)

        assertEquals(PerfSampleKind.HEALTH, sample.header.kind)
        assertEquals(PERF_SCHEMA_VERSION, sample.header.schemaVersion)
        assertEquals(1_754_000_000_000L, sample.header.tMillis)
    }
}
