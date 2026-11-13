package com.pctelemetry.dashboard

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pctelemetry.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureProfileStore(application)
    private val settingsStore = UiSettingsStore(application)
    private val settingsChanges = Channel<(UiSettings) -> UiSettings>(64)
    private val profileGate = Mutex()
    private val controller = ConnectionController(viewModelScope, MonotonicClock { SystemClock.elapsedRealtime() })
    val state = controller.state
    private val mutableProfile = MutableStateFlow<ConnectionProfile?>(null)
    val profile = mutableProfile.asStateFlow()
    private val mutableProfiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    val profiles = mutableProfiles.asStateFlow()
    private val mutableConnectionWanted = MutableStateFlow(false)
    val connectionWanted = mutableConnectionWanted.asStateFlow()
    private val mutableSettings = MutableStateFlow(UiSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableError = MutableStateFlow<Int?>(null)
    val error = mutableError.asStateFlow()
    private val mutableDiscovered = MutableStateFlow<List<DiscoveredCollector>>(emptyList())
    val discovered = mutableDiscovered.asStateFlow()
    private var discoveryJob: Job? = null
    private var foreground = false
    private var demo = false
    private var paused = false

    init {
        viewModelScope.launch {
            profileGate.withLock {
                try {
                    mutableProfile.value = store.load()
                    mutableProfiles.value = store.list()
                    mutableConnectionWanted.value = mutableProfile.value != null && !paused
                    if (foreground && !demo && !paused) mutableProfile.value?.let(controller::connect)
                } catch (_: Exception) { mutableError.value = R.string.storage_error }
            }
        }
        viewModelScope.launch {
            state.map { if (it.demo) null else it.deviceId?.let { id -> id to it.deviceName } }
                .distinctUntilChanged().collect { named ->
                    if (named?.second != null) profileGate.withLock {
                        val current = mutableProfile.value
                        if (current?.deviceId == named.first && current.displayName != named.second) {
                            try {
                                store.rename(named.first, named.second!!)
                                mutableProfile.value = current.copy(displayName = named.second)
                                mutableProfiles.value = store.list()
                            } catch (_: Exception) { mutableError.value = R.string.storage_error }
                        }
                    }
                }
        }
        viewModelScope.launch {
            try { mutableSettings.value = settingsStore.settings.first() }
            catch (_: Exception) { mutableError.value = R.string.storage_error }
            for (change in settingsChanges) {
                val previous = mutableSettings.value
                val next = change(previous)
                mutableSettings.value = next
                try { settingsStore.save(next) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableSettings.value = previous
                    mutableError.value = R.string.storage_error
                }
            }
        }
    }
    fun updateSettings(value: UiSettings) = changeSettings { value }
    fun changeSettings(change: (UiSettings) -> UiSettings) {
        if (!settingsChanges.trySend(change).isSuccess) mutableError.value = R.string.storage_error
    }
    fun discover() {
        discoveryJob?.cancel()
        mutableDiscovered.value = emptyList()
        discoveryJob = viewModelScope.launch {
            withTimeoutOrNull(10_000) {
                CollectorDiscovery(getApplication()).discover().collect { item ->
                    mutableDiscovered.value = (mutableDiscovered.value.filter { it.endpoint != item.endpoint } + item).take(16)
                }
            }
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (!active) { discoveryJob?.cancel(); controller.stop(keepAging = false) }
        else if (demo) controller.startDemo()
        else if (!paused) mutableProfile.value?.let(controller::connect)
    }
    fun connect(profile: ConnectionProfile) {
        try { profile.validate() }
        catch (_: Exception) { mutableError.value = R.string.invalid_profile; return }
        viewModelScope.launch {
            profileGate.withLock {
                try {
                    store.save(profile)
                    mutableProfile.value = profile
                    mutableProfiles.value = store.list()
                    mutableConnectionWanted.value = true
                    mutableError.value = null; demo = false; paused = false
                    if (foreground) controller.connect(profile)
                } catch (_: Exception) { mutableError.value = R.string.storage_error }
            }
        }
    }
    fun reconnect(deviceId: String) {
        viewModelScope.launch {
            profileGate.withLock {
                try {
                    val saved = store.select(deviceId)
                    mutableProfile.value = saved
                    mutableError.value = null; demo = false; paused = false
                    mutableConnectionWanted.value = true
                    if (foreground) controller.connect(saved)
                } catch (_: Exception) { mutableError.value = R.string.storage_error }
            }
        }
    }
    fun rePair() {
        controller.stop()
        viewModelScope.launch {
            profileGate.withLock {
                var revoked = true
                mutableProfile.value?.let { profile ->
                    try { PairingClient().unpair(profile) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { revoked = false }
                }
                mutableProfile.value?.let { store.remove(it.deviceId) }
                mutableProfile.value = null
                mutableProfiles.value = store.list()
                mutableConnectionWanted.value = false
                mutableError.value = if (revoked) null else R.string.unpair_failed
                paused = true; demo = false
            }
        }
    }
    fun selectGpu(id: String) = controller.selectGpu(id)
    fun startDemo() { demo = true; paused = false; mutableConnectionWanted.value = false; mutableError.value = null; controller.startDemo() }
    fun exitDemo() {
        demo = false
        paused = false
        mutableConnectionWanted.value = mutableProfile.value != null
        if (foreground && mutableProfile.value != null) controller.connect(mutableProfile.value!!)
        else controller.stop()
    }
    fun disconnect() {
        paused = true; demo = false; mutableConnectionWanted.value = false; controller.stop()
        viewModelScope.launch { profileGate.withLock {
            paused = true; demo = false; mutableConnectionWanted.value = false; controller.stop()
        } }
    }
}

