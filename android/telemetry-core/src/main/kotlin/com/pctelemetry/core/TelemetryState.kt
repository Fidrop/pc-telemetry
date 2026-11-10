package com.pctelemetry.core

enum class ConnectionPhase { DISCOVERING, PAIRING, CONNECTED, OFFLINE, RECONNECTING }
data class HistoryPoint(val receivedMillis: Long, val cpu: Double?, val gpu: Double?)

/** Mutable only on the connection controller's serial coroutine. */
class HistoryBuffer {
    private val points = arrayOfNulls<HistoryPoint>(60)
    private var next = 0
    private var count = 0
    fun append(point: HistoryPoint) {
        points[next] = point
        next = (next + 1) % points.size
        count = minOf(count + 1, points.size)
    }
    fun samples(now: Long): List<HistoryPoint> = (0 until count)
        .mapNotNull { points[(next - count + it + points.size) % points.size] }
        .filter { now - it.receivedMillis in 0 until 60_000 }
    fun clearGpu() {
        for (index in points.indices) points[index] = points[index]?.copy(gpu = null)
    }
    fun clear() { points.fill(null); next = 0; count = 0 }
}

data class DashboardState(
    val phase: ConnectionPhase = ConnectionPhase.DISCOVERING,
    val snapshot: Snapshot? = null,
    val dataAgeMillis: Long? = null,
    val history: List<HistoryPoint> = emptyList(),
    val gpus: List<GpuInfo> = emptyList(),
    val demo: Boolean = false,
    val reconnecting: Boolean = false,
    val message: String? = null
) {
    val isOffline: Boolean get() = !demo && snapshot != null &&
        (phase != ConnectionPhase.CONNECTED || (dataAgeMillis ?: Long.MAX_VALUE) >= 5_000)
}

fun interface MonotonicClock { fun nowMillis(): Long }
class SnapshotState(private val clock: MonotonicClock) {
    private val history = HistoryBuffer()
    private var session: String? = null
    private var sequence = -1L
    private var gpu: String? = null
    private var receivedAt: Long? = null
    var state = DashboardState()
        private set

    fun reset(demo: Boolean = false) {
        history.clear(); session = null; sequence = -1; gpu = null; receivedAt = null
        state = DashboardState(demo = demo)
    }
    fun setPhase(phase: ConnectionPhase, reconnecting: Boolean = false, message: String? = null) {
        state = state.copy(phase = phase, reconnecting = reconnecting, message = message)
        tick()
    }
    fun hello(hello: Hello, expected: ConnectionProfile) {
        require(hello.type == "hello" && hello.version == 1 &&
            1 in hello.supportedVersions && hello.deviceId == expected.deviceId && hello.sessionId.isNotBlank())
        hello.gpuList.forEach(Protocol::validateGpu)
        if (session != hello.sessionId) {
            session = hello.sessionId
            sequence = -1
        }
        state = state.copy(gpus = hello.gpuList)
    }
    fun accept(snapshot: Snapshot, expectedDevice: String, demo: Boolean = false): Boolean {
        Protocol.validate(snapshot)
        if (snapshot.deviceId != expectedDevice || snapshot.mode != (if (demo) "demo" else "live") ||
            (!demo && snapshot.sessionId != session) || snapshot.sequence <= sequence ||
            !snapshot.metrics.hasFreshValue()) return false
        if (gpu != snapshot.gpu?.id) history.clearGpu()
        gpu = snapshot.gpu?.id
        sequence = snapshot.sequence
        receivedAt = clock.nowMillis()
        val cpu = snapshot.metrics.cpuUsage.takeIf { it.status == "ok" }?.value
        val gpuValue = snapshot.metrics.gpuUsage.takeIf { it.status == "ok" }?.value
        history.append(HistoryPoint(receivedAt!!, cpu, gpuValue))
        state = state.copy(phase = ConnectionPhase.CONNECTED, snapshot = snapshot,
            demo = demo, reconnecting = false, message = null)
        tick()
        return true
    }
    fun tick() {
        val now = clock.nowMillis()
        val age = receivedAt?.let { (now - it).coerceAtLeast(0) }
        val phase = if (!state.demo && age != null && age >= 5_000)
            ConnectionPhase.OFFLINE else state.phase
        state = state.copy(phase = phase, dataAgeMillis = age, history = history.samples(now))
    }
}
