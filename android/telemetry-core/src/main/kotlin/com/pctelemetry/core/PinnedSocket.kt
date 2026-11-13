package com.pctelemetry.core

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface TelemetrySocket {
    suspend fun receive(): String?
    fun send(text: String)
    fun close()
}
fun interface SocketFactory { suspend fun open(profile: ConnectionProfile): TelemetrySocket }

class PinnedSocketFactory : SocketFactory {
    override suspend fun open(profile: ConnectionProfile): TelemetrySocket {
        profile.validate()
        val fingerprint = profile.fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun matches(certificate: X509Certificate): Boolean = MessageDigest.isEqual(fingerprint,
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw java.security.cert.CertificateException("Client certificates unsupported")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !matches(chain[0]))
                    throw java.security.cert.CertificateException("Certificate pin mismatch")
                chain[0].checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust)
            // The confirmed certificate fingerprint is the device identity across IP changes.
            .hostnameVerifier { _, session ->
                (session.peerCertificates.firstOrNull() as? X509Certificate)?.let(::matches) == true
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
        val messages = Channel<String>(64)
        val socket = client.newWebSocket(Request.Builder().url(profile.endpoint)
            .header("X-PC-Telemetry-Features", "device-name").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.toByteArray(Charsets.UTF_8).size > 16_384 || !messages.trySend(text).isSuccess) {
                        messages.close(IOException("Invalid or excessive server messages"))
                        webSocket.cancel()
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    messages.close(IOException("Binary messages unsupported")); webSocket.cancel()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(t); messages.close(t)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    messages.close(); webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
            })
        fun release() {
            socket.cancel()
            messages.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        try { withTimeout(10_000) { opened.await() } }
        catch (failure: Throwable) { release(); throw failure }
        return object : TelemetrySocket {
            override suspend fun receive(): String? = messages.receiveCatching().let { result ->
                result.exceptionOrNull()?.let { throw it }
                result.getOrNull()
            }
            override fun send(text: String) {
                if (!socket.send(text)) throw IOException("Socket is closed")
            }
            override fun close() = release()
        }
    }
}
