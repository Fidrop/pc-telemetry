package com.pctelemetry.dashboard

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pctelemetry.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureProfileStore(application)
    private val settingsStore = UiSettingsStore(application)
    private val profileGate = Mutex()
    private val controller = ConnectionController(viewModelScope, MonotonicClock { SystemClock.elapsedRealtime() })
    val state = controller.state
    private val mutableProfile = MutableStateFlow<ConnectionProfile?>(null)
    val profile = mutableProfile.asStateFlow()
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
                    if (foreground && !demo && !paused) mutableProfile.value?.let(controller::connect)
                } catch (_: Exception) { mutableError.value = R.string.storage_error }
            }
        }
        viewModelScope.launch {
            try { settingsStore.settings.collect { mutableSettings.value = it } }
            catch (_: Exception) { mutableError.value = R.string.storage_error }
        }
    }
    fun updateSettings(value: UiSettings) {
        mutableSettings.value = value
        viewModelScope.launch {
            try { settingsStore.save(value) }
            catch (_: Exception) { mutableError.value = R.string.storage_error }
        }
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
                    mutableError.value = null; demo = false; paused = false
                    if (foreground) controller.connect(profile)
                } catch (_: Exception) { mutableError.value = R.string.storage_error }
            }
        }
    }
    fun rePair() {
        controller.stop()
        viewModelScope.launch {
            profileGate.withLock {
                mutableProfile.value?.let { profile ->
                    try { PairingClient().unpair(profile) } catch (_: Exception) { }
                }
                store.clear()
                mutableProfile.value = null
                paused = true; demo = false
            }
        }
    }
    fun selectGpu(id: String) = controller.selectGpu(id)
    fun startDemo() { demo = true; paused = false; mutableError.value = null; controller.startDemo() }
    fun exitDemo() {
        demo = false
        if (foreground && mutableProfile.value != null) controller.connect(mutableProfile.value!!)
        else controller.stop()
    }
    fun disconnect() { paused = true; demo = false; controller.stop() }
}

