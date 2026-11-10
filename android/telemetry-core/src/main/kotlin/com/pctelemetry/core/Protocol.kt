package com.pctelemetry.core

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Metric(val value: Double?, val status: String, val sampledAt: String?, val source: String?)
@Serializable
data class MemoryMetric(
    val usedBytes: Long?, val totalBytes: Long?, val percent: Double?,
    val status: String, val sampledAt: String?, val source: String?
)
@Serializable
data class GpuInfo(val id: String, val name: String, val kind: String)
@Serializable
data class Metrics(
    val cpuUsage: Metric, val gpuUsage: Metric, val ram: MemoryMetric,
    val vram: MemoryMetric, val cpuTemperature: Metric, val gpuTemperature: Metric
) {
    fun hasFreshValue() = listOf(cpuUsage.status, gpuUsage.status, ram.status, vram.status,
        cpuTemperature.status, gpuTemperature.status).any { it == "ok" }
}
@Serializable
data class Snapshot(
    val type: String, val version: Int, val deviceId: String, val sessionId: String,
    val sequence: Long, val sampledAt: String, val mode: String, val gpu: GpuInfo?, val metrics: Metrics
)
@Serializable
data class AuthResult(val type: String, val version: Int, val success: Boolean)
@Serializable
data class Hello(
    val type: String, val version: Int, val deviceId: String, val sessionId: String,
    val supportedVersions: List<Int>, val gpuList: List<GpuInfo>
)
@Serializable
data class ConnectionProfile(
    val endpoint: String, val fingerprint: String, val deviceId: String,
    val credentialId: String, val token: String
) {
    fun validate() {
        val uri = java.net.URI(endpoint)
        require(uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path == "/v1/stream" && (uri.port == -1 || uri.port in 1..65535))
        require(fingerprint.matches(Regex("[a-fA-F0-9]{64}")))
        require(deviceId.isNotBlank() && credentialId.isNotBlank())
        require(Base64.getDecoder().decode(token).size == 32)
    }
    override fun toString() = "ConnectionProfile(credentials redacted)"
}

object Protocol {
    const val MAX_INTEGER = 9_007_199_254_740_991L
    val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
    }

    fun decode(text: String): Snapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= 16_384)
        return json.decodeFromString<Snapshot>(text).also(::validate)
    }

    fun validate(snapshot: Snapshot) {
        require(snapshot.type == "telemetry" && snapshot.version == 1)
        require(snapshot.deviceId.isNotBlank() && snapshot.sessionId.isNotBlank())
        require(snapshot.sequence in 0..MAX_INTEGER && snapshot.mode in setOf("live", "demo"))
        val envelopeTime = timestamp(snapshot.sampledAt)
        snapshot.gpu?.let(::validateGpu)
        with(snapshot.metrics) {
            validateMetric(cpuUsage, true, envelopeTime)
            validateMetric(gpuUsage, true, envelopeTime)
            validateMetric(cpuTemperature, false, envelopeTime)
            validateMetric(gpuTemperature, false, envelopeTime)
            validateMemory(ram, envelopeTime)
            validateMemory(vram, envelopeTime)
        }
    }

    fun validateGpu(gpu: GpuInfo) {
        require(gpu.id.isNotBlank() && gpu.name.isNotBlank())
        require(gpu.kind in setOf("discrete", "integrated", "unknown"))
    }

    private fun validateTime(status: String, timestamp: String?, source: String?, envelope: Instant) {
        require(status in setOf("ok", "stale"))
        require(!source.isNullOrBlank())
        val time = timestamp(requireNotNull(timestamp))
        require(!time.isAfter(envelope))
        // Compare within the sender's clock domain. Local wall-clock skew does not cause false offline.
        if (status == "ok") require(!time.isBefore(envelope.minusSeconds(5)))
    }

    private fun validateMetric(metric: Metric, percent: Boolean, envelope: Instant) {
        if (metric.status == "unavailable") {
            require(metric.value == null && metric.sampledAt == null)
            return
        }
        validateTime(metric.status, metric.sampledAt, metric.source, envelope)
        val number = requireNotNull(metric.value)
        require(number.isFinite() && (!percent || number in 0.0..100.0))
    }

    private fun validateMemory(metric: MemoryMetric, envelope: Instant) {
        if (metric.status == "unavailable") {
            require(metric.usedBytes == null && metric.totalBytes == null &&
                metric.percent == null && metric.sampledAt == null)
            return
        }
        validateTime(metric.status, metric.sampledAt, metric.source, envelope)
        val used = requireNotNull(metric.usedBytes)
        val total = requireNotNull(metric.totalBytes)
        val percent = requireNotNull(metric.percent)
        require(total in 1..MAX_INTEGER && used in 0..total)
        require(percent.isFinite() && percent in 0.0..100.0 &&
            kotlin.math.abs(100.0 * used / total - percent) <= 0.05)
    }
    // Android 10's ISO_INSTANT parser is less permissive than newer desktop JDKs.
    private fun timestamp(value: String): Instant = java.time.OffsetDateTime.parse(value).toInstant()
}
