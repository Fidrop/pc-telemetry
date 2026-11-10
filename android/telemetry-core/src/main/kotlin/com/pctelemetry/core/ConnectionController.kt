package com.pctelemetry.core

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AuthenticationFailure : IOException()
class ProtocolFailure : IOException()

class ConnectionController(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val factory: SocketFactory = PinnedSocketFactory(),
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble(0.8, 1.2) }
) {
    private sealed interface Command {
        data class Connect(val profile: ConnectionProfile) : Command
        data object Demo : Command
        data class Stop(val keepAging: Boolean) : Command
        data class SelectGpu(val id: String) : Command
    }
    private val commands = Channel<Command>(64)
    private val gate = Mutex()
    private val model = SnapshotState(clock)
    private val mutableState = MutableStateFlow(model.state)
    val state = mutableState.asStateFlow()
    private var previousProfile: ConnectionProfile? = null
    private var activeSocket: TelemetrySocket? = null

    init {
        scope.launch {
            var active: Job? = null
            for (command in commands) {
                if (command is Command.SelectGpu) {
                    gate.withLock {
                        try {
                            activeSocket?.send(buildJsonObject {
                                put("type", "gpu.select"); put("version", 1)
                                put("requestId", java.util.UUID.randomUUID().toString()); put("gpuId", command.id)
                            }.toString())
                        } catch (_: IOException) { activeSocket?.close() }
                    }
                    continue
                }
                active?.cancelAndJoin()
                when (command) {
                    is Command.Connect -> {
                        gate.withLock {
                            if (previousProfile != command.profile || model.state.demo) model.reset()
                            previousProfile = command.profile
                        }
                        active = launch { runWithTicker { connectLoop(command.profile) } }
                    }
                    Command.Demo -> {
                        gate.withLock { model.reset(demo = true); previousProfile = null }
                        active = launch {
                            runWithTicker {
                                var sequence = 0L
                                while (isActive) {
                                    update { accept(DemoSource.snapshot(++sequence), "DEMO", demo = true) }
                                    delay(1_000)
                                }
                            }
                        }
                    }
                    is Command.Stop -> {
                        update {
                            if (state.demo) reset()
                            setPhase(if (state.snapshot == null) ConnectionPhase.PAIRING else ConnectionPhase.OFFLINE)
                        }
                        if (command.keepAging) active = launch {
                            while (isActive) { update { tick() }; delay(250) }
                        }
                    }
                    is Command.SelectGpu -> Unit
                }
            }
        }
    }

    fun connect(profile: ConnectionProfile) {
        profile.validate()
        commands.trySend(Command.Connect(profile))
    }
    fun startDemo() { commands.trySend(Command.Demo) }
    fun stop(keepAging: Boolean = true) { commands.trySend(Command.Stop(keepAging)) }
    fun selectGpu(id: String) { if (id.isNotBlank()) commands.trySend(Command.SelectGpu(id)) }

    private suspend fun update(change: SnapshotState.() -> Unit) {
        gate.withLock {
            model.change()
            mutableState.value = model.state
        }
    }

    private suspend fun runWithTicker(block: suspend CoroutineScope.() -> Unit) = coroutineScope {
        val ticker = launch { while (isActive) { update { tick() }; delay(250) } }
        try { block() } finally { ticker.cancelAndJoin() }
    }

    private suspend fun connectLoop(profile: ConnectionProfile) {
        var attempt = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            update { setPhase(if (state.snapshot == null) ConnectionPhase.RECONNECTING else ConnectionPhase.OFFLINE, true) }
            var socket: TelemetrySocket? = null
            try {
                socket = factory.open(profile)
                gate.withLock { activeSocket = socket }
                socket.send(buildJsonObject {
                    put("type", "auth"); put("version", 1)
                    put("credentialId", profile.credentialId); put("token", profile.token)
                }.toString())
                withTimeout(10_000) {
                    val auth = Protocol.json.decodeFromString<AuthResult>(socket.receive() ?: throw IOException())
                    if (auth.type != "auth.result" || auth.version != 1) throw ProtocolFailure()
                    if (!auth.success) throw AuthenticationFailure()
                    val hello = Protocol.json.decodeFromString<Hello>(socket.receive() ?: throw IOException())
                    update { hello(hello, profile) }
                }
                var lastFresh = clock.nowMillis()
                while (kotlin.coroutines.coroutineContext.isActive) {
                    val remaining = (5_000 - (clock.nowMillis() - lastFresh)).coerceAtLeast(1)
                    val text = withTimeout(remaining) { socket.receive() } ?: throw IOException()
                    val snapshot = try {
                        Protocol.decode(text)
                    } catch (_: Exception) { null }
                    if (snapshot != null) {
                        val accepted = gate.withLock {
                            model.accept(snapshot, profile.deviceId).also { mutableState.value = model.state }
                        }
                        if (accepted) { lastFresh = clock.nowMillis(); attempt = 0 }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                update { setPhase(if (state.snapshot == null) ConnectionPhase.RECONNECTING else ConnectionPhase.OFFLINE, true, "network") }
            } catch (failure: Exception) {
                val reason = when (failure) {
                    is AuthenticationFailure -> "authentication"
                    is SSLException -> "certificate"
                    is ProtocolFailure, is IllegalArgumentException,
                    is kotlinx.serialization.SerializationException -> "protocol"
                    else -> "network"
                }
                if (reason != "network") {
                    update { setPhase(ConnectionPhase.PAIRING, message = reason) }
                    kotlinx.coroutines.awaitCancellation()
                }
                update { setPhase(if (state.snapshot == null) ConnectionPhase.RECONNECTING else ConnectionPhase.OFFLINE, true, reason) }
            } finally {
                socket?.close()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    gate.withLock { if (activeSocket === socket) activeSocket = null }
                }
            }
            delay(reconnectDelay(attempt++, jitter()))
        }
    }

    companion object {
        fun reconnectDelay(attempt: Int, jitter: Double): Long =
            (1_000L * (1L shl attempt.coerceIn(0, 5)) * jitter.coerceIn(0.8, 1.2)).toLong()
                .coerceIn(800, 30_000)
    }
}
