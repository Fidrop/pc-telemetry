package com.pctelemetry.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

data class MicrophoneReading(val level: Float? = null, val capturing: Boolean = false, val failed: Boolean = false)

/** Relative digital loudness (dBFS), not a calibrated environmental sound-pressure meter. */
internal fun microphoneLevel(samples: ShortArray, count: Int, sensitivity: Int = 50): Float {
    if (count <= 0) return 0f
    var energy = 0.0
    for (index in 0 until count) {
        val normalized = samples[index] / 32768.0
        energy += normalized * normalized
    }
    val rms = sqrt(energy / count)
    if (rms <= 0.00001) return 0f
    val setting = sensitivity.coerceIn(0, 100) - 50
    val gainDb = setting * if (setting <= 0) 0.72 else 0.36
    return ((20 * log10(rms) + gainDb + 55) / 55).toFloat().coerceIn(0f, 1f)
}

@Composable
internal fun rememberMicrophoneMeter(enabled: Boolean, sensitivity: Int): State<MicrophoneReading> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sessionGate = remember { Mutex() }
    val currentSensitivity by rememberUpdatedState(sensitivity)
    return produceState(MicrophoneReading(), enabled, lifecycle) {
        value = MicrophoneReading()
        if (!enabled) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = MicrophoneReading()
            withContext(Dispatchers.IO) {
                sessionGate.withLock {
                    var recorder: AudioRecord? = null
                    try {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            value = MicrophoneReading(failed = true)
                            return@withLock
                        }
                        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        check(minimum > 0) { "Unsupported microphone format" }
                        recorder = AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.MIC)
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(maxOf(minimum * 2, 4096)).build()
                        check(recorder.state == AudioRecord.STATE_INITIALIZED)
                        recorder.startRecording()
                        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        val samples = ShortArray(800)
                        var smooth = 0f
                        value = MicrophoneReading(0f, capturing = true)
                        while (isActive) {
                            // Non-blocking read ensures cancellation promptly reaches stop/release.
                            val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                            check(count >= 0) { "Microphone read failed" }
                            if (count > 0) {
                                val target = microphoneLevel(samples, count, currentSensitivity)
                                smooth += (target - smooth) * if (target > smooth) 0.65f else 0.22f
                                value = MicrophoneReading(smooth, capturing = true)
                            }
                            delay(50)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        value = MicrophoneReading(failed = true)
                    } finally {
                        try {
                            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                        } catch (_: IllegalStateException) { }
                        recorder?.release()
                        // PCM is ephemeral: never retained in app state, logged, saved, or sent over the network.
                    }
                }
            }
        }
    }
}

@Composable
internal fun SportMicrophoneBars(reading: MicrophoneReading, modifier: Modifier = Modifier) {
    val description = stringResource(if (reading.failed) R.string.microphone_unavailable
        else if (reading.capturing) R.string.microphone_active else R.string.microphone_starting)
    Canvas(modifier.fillMaxSize().testTag("sport-microphone-meter").semantics { stateDescription = description }) {
        design {
            val lit = ((reading.level ?: 0f) * 32).toInt()
            val gradient = Brush.verticalGradient(listOf(Color(0xFFFF7789), Red, Color(0xFF74051A)), 180f, 660f)
            for (x in listOf(26f, 1732f)) {
                drawRoundRect(Color(0xFF170B10), Offset(x - 5, 168f), Size(26f, 508f), CornerRadius(5f))
                drawRoundRect(Red.copy(alpha = 0.3f), Offset(x - 5, 168f), Size(26f, 508f), CornerRadius(5f), style = Stroke(1f))
                for (index in 0..31) {
                    val origin = Offset(x, 650f - index * 15f)
                    if (index < lit) drawRoundRect(gradient, origin, Size(16f, 10f), CornerRadius(2f))
                    else drawRoundRect(Color(0xFF39121D), origin, Size(16f, 10f), CornerRadius(2f))
                }
            }
        }
    }
}
