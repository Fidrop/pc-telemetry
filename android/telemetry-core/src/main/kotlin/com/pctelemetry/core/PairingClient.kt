package com.pctelemetry.core

import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class PairResult(val type: String, val version: Int, val requestId: String?,
    val success: Boolean, val deviceId: String, val credentialId: String?, val token: String?)
@Serializable
private data class UnpairResult(val type: String, val version: Int, val requestId: String, val success: Boolean)

class PairingClient(private val factory: SocketFactory = PinnedSocketFactory()) {
    /** Observe public certificate metadata, aborting TLS before sending application data. */
    suspend fun fingerprint(endpoint: String): String = withContext(Dispatchers.IO) {
        val uri = URI(endpoint)
        val placeholder = ConnectionProfile(endpoint, "0".repeat(64), "pending", "pending",
            Base64.getEncoder().encodeToString(ByteArray(32)))
        placeholder.validate()
        var observed: String? = null
        val observer = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Unsupported")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isNotEmpty()) observed = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02X".format(it.toInt() and 255) }
                throw CertificateException("User fingerprint confirmation required")
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(observer), null) }
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(uri.host, if (uri.port == -1) 443 else uri.port), 5000)
            try { socket.startHandshake() }
            catch (failure: javax.net.ssl.SSLException) { if (observed == null) throw failure }
        }
        requireNotNull(observed) { "Certificate unavailable" }
    }

    suspend fun pair(endpoint: String, fingerprint: String, code: String): ConnectionProfile {
        require(code.matches(Regex("[0-9]{8}")))
        val placeholder = ConnectionProfile(endpoint, fingerprint, "pending", "pending",
            Base64.getEncoder().encodeToString(ByteArray(32)))
        val socket = factory.open(placeholder)
        try {
            return withTimeout(10_000) {
                val request = UUID.randomUUID().toString()
                socket.send(buildJsonObject {
                    put("type", "pair.request"); put("version", 1); put("requestId", request); put("code", code)
                }.toString())
                val result = Protocol.json.decodeFromString<PairResult>(socket.receive() ?: throw java.io.IOException())
                require(result.type == "pair.result" && result.version == 1 && result.requestId == request && result.success)
                ConnectionProfile(endpoint, fingerprint, result.deviceId,
                    requireNotNull(result.credentialId), requireNotNull(result.token)).also { it.validate() }
            }
        } finally { socket.close() }
    }

    suspend fun unpair(profile: ConnectionProfile) {
        val socket = factory.open(profile)
        try {
            withTimeout(5000) {
                socket.send(buildJsonObject {
                    put("type", "auth"); put("version", 1)
                    put("credentialId", profile.credentialId); put("token", profile.token)
                }.toString())
                require(Protocol.json.decodeFromString<AuthResult>(socket.receive()!!).success)
                Protocol.json.decodeFromString<Hello>(socket.receive()!!)
                val request = UUID.randomUUID().toString()
                socket.send(buildJsonObject {
                    put("type", "unpair"); put("version", 1); put("requestId", request)
                }.toString())
                val reply = Protocol.json.decodeFromString<UnpairResult>(socket.receive() ?: throw java.io.IOException())
                require(reply.type == "unpair.result" && reply.version == 1 && reply.requestId == request && reply.success)
            }
        } finally { socket.close() }
    }
}
