package com.pctelemetry.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class PhoneBattery(val percent: Int? = null, val charging: Boolean = false) {
    companion object {
        fun from(intent: Intent?): PhoneBattery {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (scale <= 0 || level !in 0..scale) return PhoneBattery()
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            return PhoneBattery((level * 100L / scale).toInt(),
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    (status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0))
        }
    }
}
data class PhoneStatus(val time: String = "--:--:--", val battery: PhoneBattery = PhoneBattery())

internal fun formatPhoneTime(time: LocalTime): String = time.format(phoneTimeFormat)
private val phoneTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

/** Wall-clock display is independent of the monotonic telemetry/offline clock. */
@Composable
fun rememberPhoneStatus(): State<PhoneStatus> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(PhoneStatus(), context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    value = value.copy(battery = PhoneBattery.from(intent))
                }
            }
            val sticky = ContextCompat.registerReceiver(context, receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            value = PhoneStatus(formatPhoneTime(LocalTime.now()), PhoneBattery.from(sticky))
            try {
                while (isActive) {
                    // Resolve the phone's current time zone on every tick, including after a settings change.
                    value = value.copy(time = formatPhoneTime(LocalTime.now()))
                    delay(1000L - Math.floorMod(System.currentTimeMillis(), 1000L))
                }
            } finally { context.unregisterReceiver(receiver) }
        }
    }
}
