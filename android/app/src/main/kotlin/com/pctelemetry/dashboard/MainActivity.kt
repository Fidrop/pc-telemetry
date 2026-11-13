package com.pctelemetry.dashboard

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
    var sportEngineOff by rememberSaveable { mutableStateOf(false) }
    val showEngineOff = theme == DashboardTheme.SPORT && sportEngineOff
    val context = LocalContext.current
    var microphoneDenied by remember { mutableStateOf(false) }
    var microphoneSensitivityPreview by remember { mutableStateOf<Int?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneDenied = !granted
        model.changeSettings { it.copy(microphoneMeter = granted) }
    }
    val microphone by rememberMicrophoneMeter(settings.microphoneMeter && theme == DashboardTheme.SPORT && !showEngineOff,
        microphoneSensitivityPreview ?: settings.microphoneSensitivity)
    val values = animatedValues(state)
    val phone by rememberPhoneStatus()
    val phoneDescription = stringResource(R.string.phone_status, phone.time,
        phone.battery.percent?.let { "$it%" } ?: "N/A",
        if (phone.battery.charging) stringResource(R.string.charging) else "")
    val startup = remember { Animatable(0f) }
    val startupRecovery = remember { Animatable(0f) }
    var selfTest by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(selfTest) {
        if (selfTest) {
            startup.snapTo(0f); startupRecovery.snapTo(0f)
            startup.animateTo(0.8f, tween(650, easing = FastOutSlowInEasing))
            delay(120)
            startupRecovery.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
            selfTest = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                model.changeSettings { it.copy(microphoneMeter = false) }
        }
    }
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
    ).joinToString(", ") + ", " + status + ", " + age + ", " + phoneDescription
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
        .pointerInput(showEngineOff) {
            if (!showEngineOff) detectTapGestures(onLongPress = { panel = true })
        }) {
        val instruments = Modifier.fillMaxSize().graphicsLayer {
            alpha = if (night) 0.72f else 1f
            translationX = shift.x; translationY = shift.y
        }
        if (theme == DashboardTheme.SPORT) {
            AnimatedContent(targetState = showEngineOff, modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.97f)) togetherWith fadeOut(tween(250))
                }, label = "ignition-page") { off ->
                if (off) {
                    EngineOffScreen(onStarted = { sportEngineOff = false; selfTest = true }, modifier = instruments)
                } else {
                    SportDashboard(values, state, status, age, if (selfTest) startup.value else null, staleLabel,
                        instruments.semantics {
                            contentDescription = "SPORT dashboard"
                            stateDescription = readingDescription + if (selfTest) ", STARTUP SELF TEST" else ""
                        }, rogBranding = settings.rogBranding, phone = phone,
                        onEngineStop = { sportEngineOff = true; selfTest = false; panel = false; pairing = false },
                        selfTestRecovery = startupRecovery.value)
                    if (settings.microphoneMeter) SportMicrophoneBars(microphone, instruments)
                }
            }
        } else {
            HudDashboard(values, state, status, age, { chartOrigin + chartTime.toLong() }, staleLabel,
                instruments.semantics { contentDescription = "HUD dashboard"; stateDescription = readingDescription },
                rogBranding = settings.rogBranding, phone = phone)
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
        if (theme == DashboardTheme.SPORT && (microphoneDenied || (settings.microphoneMeter && microphone.failed))) {
            Text(stringResource(if (microphoneDenied) R.string.microphone_permission_denied else R.string.microphone_unavailable),
                color = White, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
    if (panel) SettingsPanel(model, settings, onDismiss = { panel = false }, onPair = { pairing = true },
        onMicrophoneSensitivityPreview = { microphoneSensitivityPreview = it },
        onMicrophoneChange = { enabled ->
            microphoneDenied = false
            if (!enabled) model.changeSettings { it.copy(microphoneMeter = false) }
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                model.changeSettings { it.copy(microphoneMeter = true) }
            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        })
    if (pairing || (profile == null && !state.demo && !dismissedInitial)) {
        PairingDialog(model, if (pairing) null else profile?.endpoint) { pairing = false; dismissedInitial = true }
    }
}

