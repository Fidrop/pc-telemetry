package com.pctelemetry.core

import java.io.File
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryTests {
    private val profile = ConnectionProfile("wss://127.0.0.1:47631/v1/stream", "a".repeat(64),
        "fixture-device", "fixture-credential", Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
    private fun snapshot(sequence: Long = 1) = DemoSource.snapshot(sequence).copy(
        deviceId = profile.deviceId, sessionId = "fixture-session", mode = "live")
    private fun hello(session: String = "fixture-session") = Hello("hello", 1, profile.deviceId,
        session, listOf(1), listOf(GpuInfo("DEMO-GPU", "Fixture GPU", "discrete")))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid input rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun optionalDeviceNamesKeepOldPayloadsReadable() {
        val legacyProfile = Protocol.json.encodeToString(profile)
        assertNull(Protocol.json.decodeFromString<ConnectionProfile>(legacyProfile).displayName)
        val oldHello = Protocol.json.encodeToString(hello())
        assertNull(Protocol.json.decodeFromString<Hello>(oldHello).deviceName)
        val named = profile.copy(displayName = "FIDROP-MECH")
        named.validate()
        assertEquals(named, Protocol.json.decodeFromString<ConnectionProfile>(Protocol.json.encodeToString(named)))
        rejected { named.copy(displayName = "line\nbreak").validate() }
        val model = SnapshotState(MonotonicClock { 0 })
        model.hello(hello().copy(deviceName = "FIDROP-MECH"), profile)
        assertEquals("FIDROP-MECH", model.state.deviceName)
        assertEquals(profile.deviceId, model.state.deviceId)
        rejected { model.hello(hello().copy(deviceId = "impostor", deviceName = "FIDROP-MECH"), profile) }
    }

    @Test fun sharedFixturesRoundTrip() {
        File(System.getProperty("protocolRoot"), "examples").listFiles()!!
            .filter { it.extension == "json" }.forEach { file ->
                val fixture = Protocol.decode(file.readText())
                assertEquals(fixture, Protocol.decode(Protocol.json.encodeToString(fixture)))
            }
    }
    @Test fun windowsOffsetTimestampFixture() {
        val captured = File(System.getProperty("protocolRoot")).parentFile
            .resolve("docs/stage-1-live-snapshots.jsonl").useLines { it.first() }
        assertEquals("live", Protocol.decode(captured).mode)
    }
    @Test fun requiredAndUnknownFieldsRejected() {
        val encoded = Protocol.json.encodeToString(snapshot())
        rejected { Protocol.decode(encoded.replace("\"sequence\":1,", "")) }
        rejected { Protocol.decode(encoded.replace("\"version\":1", "\"version\":1,\"extra\":0")) }
    }
    @Test fun malformedValuesDoNotBecomeZero() {
        val frame = snapshot()
        rejected { Protocol.validate(frame.copy(metrics = frame.metrics.copy(
            cpuUsage = frame.metrics.cpuUsage.copy(value = 101.0)))) }
        rejected { Protocol.validate(frame.copy(metrics = frame.metrics.copy(
            cpuTemperature = frame.metrics.cpuTemperature.copy(status = "unavailable")))) }
        val missing = frame.copy(metrics = frame.metrics.copy(
            cpuTemperature = Metric(null, "unavailable", null, null)))
        Protocol.validate(missing)
        assertNull(missing.metrics.cpuTemperature.value)
    }
    @Test fun memoryCapacitySemantics() {
        val frame = snapshot()
        rejected { Protocol.validate(frame.copy(metrics = frame.metrics.copy(
            ram = frame.metrics.ram.copy(percent = 1.0)))) }
        rejected { Protocol.validate(frame.copy(metrics = frame.metrics.copy(
            vram = frame.metrics.vram.copy(totalBytes = 0)))) }
        Protocol.validate(frame.copy(metrics = frame.metrics.copy(
            ram = frame.metrics.ram.copy(usedBytes = 0, percent = 0.0))))
    }
    @Test fun futureAndFalselyFreshMetricsRejected() {
        val frame = snapshot()
        rejected { Protocol.validate(frame.copy(metrics = frame.metrics.copy(
            cpuUsage = frame.metrics.cpuUsage.copy(sampledAt = "2999-01-01T00:00:00Z")))) }
        rejected { Protocol.validate(frame.copy(metrics = frame.metrics.copy(
            cpuUsage = frame.metrics.cpuUsage.copy(sampledAt = "2000-01-01T00:00:00Z")))) }
    }
    @Test fun profileOnlyAllowsPinnedWss() {
        profile.validate()
        rejected { profile.copy(endpoint = "ws://127.0.0.1:47631/v1/stream").validate() }
        rejected { profile.copy(fingerprint = "bad").validate() }
        rejected { profile.copy(token = "bad").validate() }
        rejected { profile.copy(endpoint = "wss://user:pass@127.0.0.1/v1/stream").validate() }
        assertFalse(profile.toString().contains(profile.token))
    }
    @Test fun offlineBoundaryUsesMonotonicTime() {
        var now = 0L
        val state = SnapshotState { now }
        state.hello(hello(), profile)
        val frame = snapshot()
        assertTrue(state.accept(frame, profile.deviceId))
        now = 4999; state.tick()
        assertEquals(ConnectionPhase.CONNECTED, state.state.phase)
        assertFalse(state.accept(frame, profile.deviceId))
        now = 5000; state.tick()
        assertEquals(ConnectionPhase.OFFLINE, state.state.phase)
        assertEquals(frame, state.state.snapshot)
        now = 9000; state.tick()
        assertEquals(9000L, state.state.dataAgeMillis)
    }
    @Test fun oldSessionWrongDeviceAndDemoCannotRefreshLive() {
        val state = SnapshotState { 0L }
        state.hello(hello(), profile)
        assertFalse(state.accept(snapshot().copy(sessionId = "old"), profile.deviceId))
        assertFalse(state.accept(snapshot().copy(deviceId = "wrong"), profile.deviceId))
        assertFalse(state.accept(snapshot().copy(mode = "demo"), profile.deviceId))
        assertNull(state.state.snapshot)
    }
    @Test fun reconnectSameSessionStillRejectsReplay() {
        val state = SnapshotState { 0L }
        state.hello(hello(), profile)
        assertTrue(state.accept(snapshot(10), profile.deviceId))
        state.hello(hello(), profile)
        assertFalse(state.accept(snapshot(9), profile.deviceId))
        state.hello(hello("new-session"), profile)
        assertTrue(state.accept(snapshot().copy(sessionId = "new-session"), profile.deviceId))
    }
    @Test fun allUnavailableDoesNotResetAge() {
        var now = 0L
        val state = SnapshotState { now }
        state.hello(hello(), profile)
        state.accept(snapshot(), profile.deviceId)
        now = 5000
        val frame = snapshot(2)
        fun old(metric: Metric) = metric.copy(status = "stale")
        val stale = frame.copy(metrics = with(frame.metrics) {
            Metrics(old(cpuUsage), old(gpuUsage), ram.copy(status = "stale"),
                vram.copy(status = "stale"), old(cpuTemperature), old(gpuTemperature))
        })
        assertFalse(state.accept(stale, profile.deviceId))
        state.tick()
        assertEquals(ConnectionPhase.OFFLINE, state.state.phase)
    }
    @Test fun historyBoundedAndGapPreserved() {
        val history = HistoryBuffer()
        repeat(70) { history.append(HistoryPoint(it * 1000L, it.toDouble(), null)) }
        val retained = history.samples(69_000)
        assertEquals(60, retained.size)
        assertEquals(10.0, retained.first().cpu!!, 0.0)
        assertEquals(0, history.samples(130_000).size)
        history.clear()
        history.append(HistoryPoint(0, 1.0, 2.0))
        history.append(HistoryPoint(10_000, 3.0, 4.0))
        assertEquals(listOf(0L, 10_000L), history.samples(10_000).map { it.receivedMillis })
    }
    @Test fun gpuSwitchClearsOnlyGpuHistory() {
        val state = SnapshotState { 0L }
        state.hello(hello(), profile)
        state.accept(snapshot(), profile.deviceId)
        state.accept(snapshot(2).copy(gpu = GpuInfo("other", "Other GPU", "integrated")), profile.deviceId)
        assertNull(state.state.history.first().gpu)
        assertNotNull(state.state.history.first().cpu)
    }
    @Test fun backoffBounded() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L),
            (0..5).map { ConnectionController.reconnectDelay(it, 1.0) })
        assertEquals(30_000L, ConnectionController.reconnectDelay(1000, 1.2))
    }
    @Test fun unpairAllowsQueuedTelemetryBeforeAcknowledgement() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        var closed = false
        val socket = object : TelemetrySocket {
            override suspend fun receive() = incoming.receive()
            override fun close() { closed = true; incoming.close() }
            override fun send(text: String) {
                val command = Protocol.json.parseToJsonElement(text).jsonObject
                when (command.getValue("type").jsonPrimitive.content) {
                    "auth" -> handshake(snapshot()).forEach { incoming.trySend(it) }
                    "unpair" -> incoming.trySend(kotlinx.serialization.json.buildJsonObject {
                        put("type", "unpair.result"); put("version", 1)
                        put("requestId", command.getValue("requestId")); put("success", true)
                    }.toString())
                }
            }
        }
        PairingClient { socket }.unpair(profile)
        assertTrue(closed)
    }

    private class FakeSocket(initial: List<String>) : TelemetrySocket {
        val incoming = Channel<String>(Channel.UNLIMITED)
        var closed = false
        init { initial.forEach { incoming.trySend(it) } }
        override suspend fun receive() = incoming.receiveCatching().getOrNull()
        override fun send(text: String) {}
        override fun close() { closed = true; incoming.close() }
    }
    private fun handshake(frame: Snapshot? = null) = buildList {
        add(Protocol.json.encodeToString(AuthResult("auth.result", 1, true)))
        add(Protocol.json.encodeToString(hello()))
        frame?.let { add(Protocol.json.encodeToString(it)) }
    }
    @Test fun controllerTimesOutInvalidStreamAndReconnects() = runTest {
        val first = FakeSocket(handshake(snapshot()))
        val second = FakeSocket(handshake(snapshot(2)))
        var opens = 0
        val controller = ConnectionController(backgroundScope, { testScheduler.currentTime },
            { if (opens++ == 0) first else second }, { 1.0 })
        controller.connect(profile); runCurrent()
        assertEquals(ConnectionPhase.CONNECTED, controller.state.value.phase)
        advanceTimeBy(4999); runCurrent()
        first.incoming.trySend("{}"); runCurrent()
        assertEquals(ConnectionPhase.CONNECTED, controller.state.value.phase)
        advanceTimeBy(1); runCurrent()
        assertEquals(ConnectionPhase.OFFLINE, controller.state.value.phase)
        assertTrue(first.closed)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, opens)
        assertEquals(2L, controller.state.value.snapshot!!.sequence)
    }
    @Test fun demoExplicitAndClearedBeforeRealConnect() = runTest {
        val socket = FakeSocket(handshake(snapshot()))
        val controller = ConnectionController(backgroundScope, { testScheduler.currentTime }, { socket })
        controller.startDemo(); runCurrent()
        assertTrue(controller.state.value.demo)
        assertEquals("demo", controller.state.value.snapshot!!.mode)
        advanceTimeBy(2000); runCurrent()
        controller.connect(profile); runCurrent()
        assertFalse(controller.state.value.demo)
        assertEquals(1, controller.state.value.history.size)
        assertEquals("live", controller.state.value.snapshot!!.mode)
    }
    @Test fun authenticationFailureStopsRetries() = runTest {
        var opens = 0
        val socket = FakeSocket(listOf(Protocol.json.encodeToString(AuthResult("auth.result", 1, false))))
        val controller = ConnectionController(backgroundScope, { testScheduler.currentTime }, { opens++; socket })
        controller.connect(profile); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, opens)
        assertEquals("authentication", controller.state.value.message)
        assertEquals(ConnectionPhase.PAIRING, controller.state.value.phase)
    }
}
