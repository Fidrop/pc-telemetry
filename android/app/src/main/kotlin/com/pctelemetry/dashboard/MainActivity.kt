package com.pctelemetry.dashboard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pctelemetry.core.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: DashboardViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF50DDD8), background = Color(0xFF090E12), surface = Color(0xFF121C23))) {
                Dashboard(model)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model.foreground(true)
    }
    override fun onStop() {
        model.foreground(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}

@Composable
private fun Dashboard(model: DashboardViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val profile by model.profile.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    var dismissedInitial by remember { mutableStateOf(false) }
    val showInitial = profile == null && !state.demo && !dismissedInitial
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("PC TELEMETRY", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
                val label = when {
                    state.demo -> R.string.demo_status
                    state.isOffline -> R.string.offline
                    state.phase == ConnectionPhase.CONNECTED -> R.string.online
                    state.reconnecting -> R.string.connecting
                    else -> R.string.waiting
                }
                Text(stringResource(label),
                    color = if (state.isOffline) Color(0xFFFF9F5E) else MaterialTheme.colorScheme.primary)
                state.dataAgeMillis?.let { Text(stringResource(R.string.age, it / 1000.0),
                    color = MaterialTheme.colorScheme.onBackground) }
            }
            Row {
                if (state.demo) TextButton(onClick = model::exitDemo) { Text(stringResource(R.string.exit_demo)) }
                TextButton(onClick = { settings = true }) { Text(stringResource(R.string.connection)) }
            }
        }
        val problem = error ?: when (state.message) {
            "authentication" -> R.string.authentication_error
            "certificate" -> R.string.certificate_error
            "protocol" -> R.string.protocol_error
            else -> null
        }
        problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        state.snapshot?.gpu?.let { Text(it.name, color = MaterialTheme.colorScheme.onBackground) }
        val metrics = state.snapshot?.metrics
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricColumn(Modifier.weight(1f), stringResource(R.string.cpu_usage), metric(metrics?.cpuUsage, "%"),
                stringResource(R.string.cpu_temperature), metric(metrics?.cpuTemperature, "°C"))
            MetricColumn(Modifier.weight(1f), stringResource(R.string.gpu_usage), metric(metrics?.gpuUsage, "%"),
                stringResource(R.string.gpu_temperature), metric(metrics?.gpuTemperature, "°C"))
            MetricColumn(Modifier.weight(1.2f), stringResource(R.string.ram), memory(metrics?.ram),
                stringResource(R.string.vram), memory(metrics?.vram))
        }
        Text(stringResource(R.string.history_count, state.history.size),
            color = MaterialTheme.colorScheme.onBackground)
    }
    if (settings || showInitial) ConnectionDialog(profile,
        onDismiss = { settings = false; dismissedInitial = true },
        onConnect = { model.connect(it); settings = false; dismissedInitial = true },
        onDemo = { model.startDemo(); settings = false; dismissedInitial = true },
        onDisconnect = { model.disconnect(); settings = false; dismissedInitial = true })
}

@Composable
private fun MetricColumn(modifier: Modifier, first: String, value: String, second: String, secondValue: String) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(first, color = MaterialTheme.colorScheme.primary)
        Text(value, fontSize = 30.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(second, color = MaterialTheme.colorScheme.primary)
        Text(secondValue, fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}
@Composable
private fun metric(metric: Metric?, unit: String): String {
    val value = metric?.value ?: return "N/A"
    val suffix = if (metric.status == "stale") " · " + stringResource(R.string.stale) else ""
    return String.format(Locale.getDefault(), "%.1f %s%s", value, unit, suffix)
}
@Composable
private fun memory(memory: MemoryMetric?): String {
    val used = memory?.usedBytes ?: return "N/A"
    val total = memory.totalBytes ?: return "N/A"
    val suffix = if (memory.status == "stale") " · " + stringResource(R.string.stale) else ""
    return String.format(Locale.getDefault(), "%.1f%%\n%.1f / %.1f GiB%s",
        memory.percent, used / 1073741824.0, total / 1073741824.0, suffix)
}

@Composable
private fun ConnectionDialog(initial: ConnectionProfile?, onDismiss: () -> Unit,
    onConnect: (ConnectionProfile) -> Unit, onDemo: () -> Unit, onDisconnect: () -> Unit) {
    var endpoint by remember { mutableStateOf(initial?.endpoint ?: "wss://127.0.0.1:47631/v1/stream") }
    var fingerprint by remember { mutableStateOf(initial?.fingerprint ?: "") }
    var device by remember { mutableStateOf(initial?.deviceId ?: "") }
    var credential by remember { mutableStateOf(initial?.credentialId ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.development_connection)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.development_note))
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.endpoint)) }, singleLine = true)
                OutlinedTextField(fingerprint, { fingerprint = it }, label = { Text(stringResource(R.string.fingerprint)) }, singleLine = true)
                OutlinedTextField(device, { device = it }, label = { Text(stringResource(R.string.device_id)) }, singleLine = true)
                OutlinedTextField(credential, { credential = it }, label = { Text(stringResource(R.string.credential_id)) }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.token)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (invalid) Text(stringResource(R.string.invalid_profile), color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = onDemo) { Text(stringResource(R.string.demo)) }
                    TextButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val profile = ConnectionProfile(endpoint.trim(), fingerprint.trim(), device.trim(),
                    credential.trim(), token.trim())
                try { profile.validate(); onConnect(profile) }
                catch (_: Exception) { invalid = true }
            }) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
