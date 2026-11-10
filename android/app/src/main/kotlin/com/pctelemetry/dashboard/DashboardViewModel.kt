package com.pctelemetry.dashboard

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pctelemetry.core.ConnectionController
import com.pctelemetry.core.ConnectionProfile
import com.pctelemetry.core.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureProfileStore(application)
    private val controller = ConnectionController(viewModelScope, MonotonicClock { SystemClock.elapsedRealtime() })
    val state = controller.state
    private val mutableProfile = MutableStateFlow<ConnectionProfile?>(null)
    val profile = mutableProfile.asStateFlow()
    private val mutableError = MutableStateFlow<Int?>(null)
    val error = mutableError.asStateFlow()
    private var foreground = false
    private var demo = false
    private var paused = false

    init {
        viewModelScope.launch {
            try {
                mutableProfile.value = store.load()
                if (foreground && !demo && !paused) mutableProfile.value?.let(controller::connect)
            } catch (_: Exception) { mutableError.value = R.string.storage_error }
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (!active) controller.stop(keepAging = false)
        else if (demo) controller.startDemo()
        else if (!paused) mutableProfile.value?.let(controller::connect)
    }
    fun connect(profile: ConnectionProfile) {
        try { profile.validate() }
        catch (_: Exception) { mutableError.value = R.string.invalid_profile; return }
        viewModelScope.launch {
            try {
                store.save(profile)
                mutableProfile.value = profile
                mutableError.value = null
                demo = false
                paused = false
                if (foreground) controller.connect(profile)
            } catch (_: Exception) { mutableError.value = R.string.storage_error }
        }
    }
    fun startDemo() { demo = true; paused = false; mutableError.value = null; controller.startDemo() }
    fun exitDemo() {
        demo = false
        if (foreground && mutableProfile.value != null) controller.connect(mutableProfile.value!!)
        else controller.stop()
    }
    fun disconnect() { paused = true; demo = false; controller.stop() }
}
