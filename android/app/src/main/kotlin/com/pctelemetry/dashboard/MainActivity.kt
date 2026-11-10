package com.pctelemetry.dashboard

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pctelemetry.core.ConnectionPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : AppCompatActivity() {
    private val model: DashboardViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            val settings by model.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.language) {
                val locales = if (settings.language == "system") LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(settings.language)
                if (AppCompatDelegate.getApplicationLocales() != locales)
                    AppCompatDelegate.setApplicationLocales(locales)
            }
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF50DDD8),
                    background = Color(0xFF090E12), surface = Color(0xFF121C23))) {
                    Dashboard(model, settings)
                }
        }
    }
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
private fun Dashboard(model: DashboardViewModel, settings: UiSettings) {
    val state by model.state.collectAsStateWithLifecycle()
    val profile by model.profile.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(false) }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var dismissedInitial by rememberSaveable { mutableStateOf(false) }
    val theme = settings.theme
    val values = animatedValues(state)
    val startup = remember { Animatable(0f) }
    var selfTest by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (selfTest) {
            startup.animateTo(1f, tween(600)); startup.animateTo(0f, tween(600)); selfTest = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var shift by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(settings.pixelShift, lifecycle) {
        shift = Offset.Zero
        if (settings.pixelShift) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var index = 0
            val offsets = listOf(Offset(-2f, -2f), Offset(2f, -2f), Offset(2f, 2f), Offset(-2f, 2f))
            while (isActive) { delay(60_000); shift = offsets[index++ % offsets.size] }
        }
    }
    val night = settings.night == "on" || (settings.night == "system" && isSystemInDarkTheme())
    val chartOrigin = remember { android.os.SystemClock.elapsedRealtime() }
    val chartTarget = (state.history.lastOrNull()?.receivedMillis ?: chartOrigin) + (state.dataAgeMillis ?: 0L)
    val chartTime by animateFloatAsState((chartTarget - chartOrigin).toFloat(), tween(200, easing = LinearEasing),
        label = "history-time")
    val label = when {
        state.demo -> R.string.demo_status
        state.isOffline -> R.string.offline
        state.phase == ConnectionPhase.CONNECTED -> R.string.online
        state.reconnecting -> R.string.connecting
        else -> R.string.waiting
    }
    val status = stringResource(label)
    val age = state.dataAgeMillis?.let { stringResource(R.string.age, it / 1000.0) } ?: ""
    val settingsDescription = stringResource(R.string.settings)
    val staleLabel = stringResource(R.string.stale)
    val metrics = state.snapshot?.metrics
    val readingDescription = listOf(
        stringResource(R.string.cpu_usage) + ": " + (metrics?.cpuUsage?.value ?: "N/A"),
        stringResource(R.string.gpu_usage) + ": " + (metrics?.gpuUsage?.value ?: "N/A"),
        stringResource(R.string.cpu_temperature) + ": " + (metrics?.cpuTemperature?.value ?: "N/A"),
        stringResource(R.string.gpu_temperature) + ": " + (metrics?.gpuTemperature?.value ?: "N/A"),
        stringResource(R.string.ram) + ": " + (metrics?.ram?.percent ?: "N/A"),
        stringResource(R.string.vram) + ": " + (metrics?.vram?.percent ?: "N/A")
    ).joinToString(", ")
    var drag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.displayCutout.union(
            WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)))
        .pointerInput(theme) {
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (kotlin.math.abs(drag) > 80f) model.changeSettings {
                    it.copy(theme = if (it.theme == DashboardTheme.SPORT) DashboardTheme.HUD else DashboardTheme.SPORT)
                }
            }) { change, amount -> change.consume(); drag += amount }
        }
        .pointerInput(Unit) { detectTapGestures(onLongPress = { panel = true }) }) {
        val instruments = Modifier.fillMaxSize().graphicsLayer {
            alpha = if (night) 0.72f else 1f
            translationX = shift.x; translationY = shift.y
        }
        if (theme == DashboardTheme.SPORT) {
            SportDashboard(values, state, status, age, if (selfTest) startup.value else null, staleLabel,
                instruments.semantics { contentDescription = "SPORT dashboard"; stateDescription = readingDescription },
                rogBranding = settings.rogBranding)
        } else {
            HudDashboard(values, state, status, age, { chartOrigin + chartTime.toLong() }, staleLabel,
                instruments.semantics { contentDescription = "HUD dashboard"; stateDescription = readingDescription },
                rogBranding = settings.rogBranding)
        }
        TextButton(onClick = { panel = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            .semantics { contentDescription = settingsDescription }) { Text("⚙", fontSize = 25.sp) }
        val problem = error ?: when (state.message) {
            "authentication" -> R.string.authentication_error
            "certificate" -> R.string.certificate_error
            "protocol" -> R.string.protocol_error
            else -> null
        }
        problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
    }
    if (panel) SettingsPanel(model, settings, onDismiss = { panel = false }, onPair = { pairing = true })
    if (pairing || (profile == null && !state.demo && !dismissedInitial)) {
        PairingDialog(model, profile?.endpoint) { pairing = false; dismissedInitial = true }
    }
}

