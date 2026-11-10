package com.pctelemetry.dashboard

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun SportEngineStop(onStop: () -> Unit) {
    val description = stringResource(R.string.engine_stop_description)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = minOf(maxWidth / WIDTH, maxHeight / HEIGHT)
        Box(Modifier.offset((maxWidth - unit * WIDTH) / 2 + unit * 777f,
            (maxHeight - unit * HEIGHT) / 2 + unit * 495f)
            .size(unit * 220f, unit * 95f)
            .testTag("engine-stop-control")
            .semantics { contentDescription = description; role = Role.Button }
            .clickable(onClick = onStop))
    }
}

@Composable
internal fun EngineOffScreen(onStarted: () -> Unit, modifier: Modifier = Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val currentStarted by rememberUpdatedState(onStarted)
    val view = LocalView.current
    val holdDescription = stringResource(R.string.engine_hold_description)
    val holdHint = stringResource(R.string.engine_hold_hint)
    var pressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "engine-off")
        val opacity by transition.animateFloat(1f, 0.28f,
            infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse), label = "off-text")
        opacity
    } else 1f

    BoxWithConstraints(modifier.testTag("engine-off-screen").semantics { contentDescription = "ENGINE OFF" }) {
        val unit = minOf(maxWidth / WIDTH, maxHeight / HEIGHT)
        Canvas(Modifier.fillMaxSize()) { design {
            drawRect(Color(0xFF040506), size = Size(WIDTH, HEIGHT))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF20242B), Color.Transparent),
                Offset(887f, 555f), 440f), 440f, Offset(887f, 555f))
            label(paint, "ENGINE OFF", 887f, 252f, 87f, White.copy(alpha = alpha), true)
            label(paint, holdHint, 887f, 805f, 22f, Silver, true, 800f)
        } }
        Canvas(Modifier.offset((maxWidth - unit * WIDTH) / 2 + unit * 717f,
            (maxHeight - unit * HEIGHT) / 2 + unit * 395f)
            .size(unit * 340f)
            .clip(CircleShape)
            .testTag("engine-start-button")
            .semantics {
                role = Role.Button
                contentDescription = holdDescription
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }
            .pointerInput(active) {
                if (!active) return@pointerInput
                detectTapGestures(onPress = onPress@{ position ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    if ((position - center).getDistance() > size.width / 2f) return@onPress
                    coroutineScope {
                        val began = SystemClock.uptimeMillis()
                        var completed = false
                        fun complete() {
                            if (!completed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                completed = true
                                currentStarted()
                            }
                        }
                        pressed = true
                        val timer = launch {
                            var nextTick = began
                            while (isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                val now = SystemClock.uptimeMillis()
                                progress = ((now - began) / 1000f).coerceIn(0f, 1f)
                                if (now - began >= 1000L) { complete(); break }
                                if (now >= nextTick) {
                                    // Brief system ticks respect the phone's haptic settings; no sustained vibration.
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    nextTick = now + 140L
                                }
                                delay(16)
                            }
                        }
                        try {
                            val released = tryAwaitRelease()
                            if (released && SystemClock.uptimeMillis() - began >= 1000L) complete()
                        } finally {
                            timer.cancelAndJoin()
                            pressed = false
                            progress = 0f
                        }
                    }
                })
            }) {
            val factor = size.minDimension / 340f
            withTransform({ scale(factor, factor, Offset.Zero) }) {
                drawEngineButton(paint, pressed, progress)
            }
        }
    }
}

private fun DrawScope.drawEngineButton(paint: Paint, pressed: Boolean, progress: Float) {
    val center = Offset(170f, 170f)
    drawCircle(Color.Black.copy(alpha = 0.7f), 157f, center + Offset(0f, 11f))
    drawCircle(Brush.linearGradient(listOf(Color(0xFF91989F), Color(0xFF252B32), Color(0xFF030405)),
        Offset(70f, 20f), Offset(235f, 320f)), 155f, center)
    drawCircle(Brush.sweepGradient(listOf(Color(0xFF434951), Color(0xFFE0E3E6),
        Color(0xFF5A6066), Color(0xFF13171B), Color(0xFF858D93), Color(0xFF434951)), center), 144f, center)
    drawCircle(Color(0xFF050607), 133f, center)
    val face = center + Offset(0f, if (pressed) 4f else 0f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF34383E), Color(0xFF0A0D10)),
        face - Offset(35f, 55f), 235f), 127f, face)
    drawCircle(Red.copy(alpha = if (pressed) 0.9f else 0.45f), 128f, face, style = Stroke(1.5f))
    drawArc(White.copy(alpha = 0.65f), 208f, 104f, false,
        face - Offset(143f, 143f), Size(286f, 286f), style = Stroke(1.8f))
    drawArc(White.copy(alpha = 0.15f), 34f, 90f, false,
        face - Offset(137f, 137f), Size(274f, 274f), style = Stroke(2f))
    drawArc(White.copy(alpha = 0.035f), 190f, 160f, true,
        face - Offset(119f, 119f), Size(238f, 150f))
    drawRoundRect(Red, face + Offset(-20f, -77f), Size(40f, 5f), androidx.compose.ui.geometry.CornerRadius(2f))
    label(paint, "ENGINE", face.x, face.y - 17f, 29f, Silver, true)
    label(paint, "START", face.x, face.y + 34f, 44f, White, true)
    drawLine(Red.copy(alpha = 0.7f), face + Offset(-34f, 56f), face + Offset(34f, 56f), 2f)
    drawCircle(White.copy(alpha = 0.08f), 164f, center, style = Stroke(3f))
    if (progress > 0f) drawArc(Red, -90f, 360f * progress, false,
        Offset(6f, 6f), Size(328f, 328f), style = Stroke(4f, cap = StrokeCap.Round))
}
