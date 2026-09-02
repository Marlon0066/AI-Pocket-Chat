package com.situ.aichat.prompt.growth

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 可拨动的测试时钟（修缮卷 T2-1 / T2-SIM 夹具）：给注入了 [Clock] 的协调器用——`Clock.fixed` 钉一个时刻，
 * 本类还能在一次调用**中途**拨动（E38 跨午夜：LLM 桩里 [advance] 两分钟 ⇒ 协调器的 `now` 与 `writeNow` 落在两天）。
 */
class MutableClock(
    private var nowMs: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(nowMs, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

    fun set(ms: Long) { nowMs = ms }
    fun advance(ms: Long) { nowMs += ms }
}
