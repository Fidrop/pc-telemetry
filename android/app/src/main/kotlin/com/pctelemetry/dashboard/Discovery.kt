package com.pctelemetry.dashboard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredCollector(val name: String, val endpoint: String)

class CollectorDiscovery(context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    fun discover() = callbackFlow {
        val pending = java.util.ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var active = true
        fun resolveNext() {
            if (!active || resolving || pending.isEmpty()) return
            resolving = true
            val service = pending.removeFirst()
            @Suppress("DEPRECATION")
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving = false; resolveNext()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null && serviceInfo.port in 1..65535) {
                        val authority = if (host.contains(":")) "[" + host.replace("%", "%25") + "]" else host
                        trySend(DiscoveredCollector(serviceInfo.serviceName,
                            "wss://$authority:${serviceInfo.port}/v1/stream"))
                    }
                    resolving = false; resolveNext()
                }
            })
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { close() }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (pending.size < 16 && service.serviceType.contains("_pc-telemetry._tcp")) {
                    pending.addLast(service); resolveNext()
                }
            }
        }
        manager.discoverServices("_pc-telemetry._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            active = false; pending.clear()
            try { manager.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { }
        }
    }
}
