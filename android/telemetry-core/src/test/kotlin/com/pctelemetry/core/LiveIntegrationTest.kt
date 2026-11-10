package com.pctelemetry.core

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveIntegrationTest {
    private fun profile(): ConnectionProfile {
        val text = System.getenv("PC_TELEMETRY_TEST_PROFILE")
        assumeTrue("Live Windows Collector profile is supplied by the integration script", !text.isNullOrBlank())
        return Protocol.json.decodeFromString<ConnectionProfile>(text!!).also { it.validate() }
    }
    private fun clock() = MonotonicClock { System.nanoTime() / 1_000_000 }
    @Test fun windowsCollectorStreamsRealDataOverPinnedWss() = runBlocking {
        val profile = profile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ConnectionController(scope, clock())
            controller.connect(profile)
            val frames = withTimeout(30_000) {
                controller.state.mapNotNull { it.snapshot }
                    .distinctUntilChangedBy { it.sessionId to it.sequence }.take(8).toList()
            }
            assertTrue(frames.all { it.mode == "live" && it.deviceId == profile.deviceId })
            assertTrue(frames.all { it.metrics.ram.status == "ok" })
            assertTrue(frames.any { it.metrics.cpuUsage.value != null })
            assertEquals(8, frames.map { it.sequence }.toSet().size)
            println("LIVE PASS: 8 real Windows snapshots received by Android shared core over pinned WSS.")
        } finally { scope.cancel() }
    }
    @Test fun realServerCertificateMismatchRejected() = runBlocking {
        val profile = profile().copy(fingerprint = "0".repeat(64))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ConnectionController(scope, clock())
            controller.connect(profile)
            val rejected = withTimeout(15_000) {
                controller.state.filter { it.message == "certificate" }.first()
            }
            assertNull(rejected.snapshot)
            println("LIVE PASS: wrong certificate pin rejected before telemetry.")
        } finally { scope.cancel() }
    }
    @Test fun realServerWrongCredentialRejected() = runBlocking {
        val profile = profile().copy(token = Base64.getEncoder().encodeToString(ByteArray(32)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ConnectionController(scope, clock())
            controller.connect(profile)
            val rejected = withTimeout(15_000) {
                controller.state.filter { it.message == "authentication" }.first()
            }
            assertNull(rejected.snapshot)
            println("LIVE PASS: wrong credential rejected before telemetry.")
        } finally { scope.cancel() }
    }
}
