package com.pctelemetry.core

import java.time.Instant
import kotlin.math.sin

object DemoSource {
    fun snapshot(sequence: Long): Snapshot {
        val time = Instant.now().toString()
        fun metric(value: Double) = Metric(value, "ok", time, "DEMO")
        fun memory(percent: Double, total: Long) = MemoryMetric(
            (total * percent / 100).toLong(), total, percent, "ok", time, "DEMO")
        return Snapshot("telemetry", 1, "DEMO", "DEMO", sequence, time, "demo",
            GpuInfo("DEMO-GPU", "DEMO GPU", "discrete"),
            Metrics(metric(45 + 30 * sin(sequence / 7.0)), metric(55 + 25 * sin(sequence / 9.0)),
                memory(52.0, 16L * 1024 * 1024 * 1024), memory(40.0, 8L * 1024 * 1024 * 1024),
                metric(55 + 8 * sin(sequence / 7.0)), metric(62 + 5 * sin(sequence / 9.0))))
    }
}
