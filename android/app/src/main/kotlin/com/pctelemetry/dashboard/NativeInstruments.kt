package com.pctelemetry.dashboard

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import com.pctelemetry.core.DashboardState
import com.pctelemetry.core.MemoryMetric
import java.util.Locale
import kotlin.math.*

enum class DashboardTheme { SPORT, HUD }
data class InstrumentValues(val cpu: Float?, val gpu: Float?, val cpuTemp: Float?, val gpuTemp: Float?,
    val ram: MemoryMetric?, val vram: MemoryMetric?)

@Composable
fun animatedValues(state: DashboardState): InstrumentValues {
    val data = state.snapshot?.metrics
    @Composable fun smooth(value: Double?): Float? {
        val animated by animateFloatAsState((value ?: 0.0).toFloat(),
            tween(500, easing = FastOutSlowInEasing), label = "reading")
        return value?.let { round(animated * 10) / 10 }
    }
    return InstrumentValues(smooth(data?.cpuUsage?.value), smooth(data?.gpuUsage?.value),
        smooth(data?.cpuTemperature?.value), smooth(data?.gpuTemperature?.value), data?.ram, data?.vram)
}

internal val White = Color(0xFFE6EBED)
internal val Silver = Color(0xFF8E969B)
internal val Red = Color(0xFFFF203B)
internal val Cyan = Color(0xFF46E3E6)
internal val Orange = Color(0xFFFFAD3D)
internal val Muted = Color(0xFF65757E)
internal const val WIDTH = 1774f
internal const val HEIGHT = 887f

internal fun DrawScope.design(block: DrawScope.() -> Unit) {
    val factor = min(size.width / WIDTH, size.height / HEIGHT)
    withTransform({
        translate((size.width - WIDTH * factor) / 2, (size.height - HEIGHT * factor) / 2)
        scale(factor, factor, Offset.Zero)
    }, block)
}
internal fun DrawScope.label(paint: Paint, text: String, x: Float, y: Float, size: Float,
    color: Color = White, center: Boolean = false, maxWidth: Float = Float.POSITIVE_INFINITY) {
    paint.color = color.toArgb()
    paint.textSize = size
    val measured = paint.measureText(text)
    if (measured > maxWidth) paint.textSize = size * maxWidth / measured
    paint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
private fun polar(center: Offset, radius: Float, degrees: Float): Offset {
    val radians = degrees * PI / 180
    return Offset(center.x + radius * cos(radians).toFloat(), center.y + radius * sin(radians).toFloat())
}
private fun number(value: Float?, unit: String = "%") =
    value?.let { String.format(Locale.getDefault(), "%.1f%s", it, unit) } ?: "N/A"

@Composable
fun SportDashboard(values: InstrumentValues, state: DashboardState, status: String, age: String,
    selfTest: Float?, staleLabel: String, modifier: Modifier = Modifier, rogBranding: Boolean = true,
    phone: PhoneStatus = PhoneStatus()) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC) } }
    Box(modifier) {
        SportBackground(paint, rogBranding)
        Canvas(Modifier.fillMaxSize()) { design {
            val cpu = selfTest?.times(100) ?: values.cpu
            val gpu = selfTest?.times(100) ?: values.gpu
            pointer(Offset(470f, 410f), cpu)
            pointer(Offset(1304f, 410f), gpu)
            label(paint, if (selfTest != null) "SELF TEST" else number(cpu), 470f, 520f, 80f, center = true)
            label(paint, if (selfTest != null) "SELF TEST" else number(gpu), 1304f, 520f, 80f, center = true)
            if (selfTest == null && state.snapshot?.metrics?.cpuUsage?.status == "stale")
                label(paint, staleLabel, 470f, 556f, 23f, Orange, true)
            if (selfTest == null && state.snapshot?.metrics?.gpuUsage?.status == "stale")
                label(paint, staleLabel, 1304f, 556f, 23f, Orange, true)
            val cpuTemp = number(values.cpuTemp, "°C") + staleSuffix(state.snapshot?.metrics?.cpuTemperature?.status, staleLabel)
            val gpuTemp = number(values.gpuTemp, "°C") + staleSuffix(state.snapshot?.metrics?.gpuTemperature?.status, staleLabel)
            label(paint, if (selfTest != null) "—" else cpuTemp, 470f, 605f, 43f, center = true)
            label(paint, if (selfTest != null) "—" else gpuTemp, 1304f, 605f, 43f, center = true)
            label(paint, status, 887f, 535f, 26f, if (state.isOffline) Red else White, true, 190f)
            label(paint, age, 887f, 573f, 22f, Muted, true, 190f)
            memory(paint, "RAM", values.ram, 90f, 752f, 690f, White, staleLabel)
            memory(paint, "VRAM", values.vram, 960f, 752f, 690f, White, staleLabel)
        } }
        PhoneStatusLayer(paint, phone, sport = true)
    }
}
private fun DrawScope.gaugeBackground(paint: Paint, center: Offset, radius: Float) {
    drawCircle(Color(0xFF33383C), radius + 15, center)
    drawCircle(Color(0xFFAAB2B6), radius + 10, center, style = Stroke(3f))
    drawCircle(Color(0xFF080A0C), radius, center)
    drawCircle(Silver, radius - 7, center, style = Stroke(2f))
    drawArc(Red.copy(alpha = 0.65f), 351f, 54f, false,
        Offset(center.x - radius + 20, center.y - radius + 20),
        Size((radius - 20) * 2, (radius - 20) * 2), style = Stroke(13f))
    for (tick in 0..100) {
        val angle = 135f + tick * 2.7f
        val major = tick % 10 == 0
        drawLine(if (tick >= 80) Red else White,
            polar(center, radius - if (major) 47 else 32, angle), polar(center, radius - 20, angle),
            if (major) 5f else 1.5f)
        if (major) {
            val position = polar(center, radius - 76, angle)
            label(paint, tick.toString(), position.x, position.y + 11, 32f, center = true)
        }
    }
}
private fun DrawScope.pointer(center: Offset, value: Float?) {
    if (value == null) return
    val angle = 135f + 2.7f * value.coerceIn(0f, 100f)
    drawLine(Red, polar(center, 26f, angle + 180), polar(center, 252f, angle), 7f)
    drawLine(White, polar(center, 30f, angle), polar(center, 247f, angle), 1.5f)
    drawCircle(Color(0xFF33383E), 22f, center)
    drawCircle(Silver, 22f, center, style = Stroke(2f))
}
private fun DrawScope.memory(paint: Paint, name: String, metric: MemoryMetric?,
    x: Float, y: Float, width: Float, color: Color, staleLabel: String) {
    label(paint, name, x, y, 31f, color)
    val percent = metric?.percent
    label(paint, percent?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "N/A",
        x + 150, y, 42f, color)
    val capacity = metric?.usedBytes?.let { used ->
        String.format(Locale.getDefault(), "%.1f / %.1f GiB", used / 1073741824.0,
            metric.totalBytes!! / 1073741824.0)
    } ?: "N/A"
    label(paint, capacity + staleSuffix(metric?.status, staleLabel), x + 395, y, 25f, color)
    for (index in 0..19) {
        drawRect(if (percent != null && percent >= (index + 1) * 5) color else Color(0xFF2B3338),
            Offset(x + index * width / 20, y + 25), Size(width / 20 - 4, 17f))
    }
}

@Composable
fun HudDashboard(values: InstrumentValues, state: DashboardState, status: String, age: String,
    nowMillis: () -> Long, staleLabel: String, modifier: Modifier = Modifier, rogBranding: Boolean = true,
    phone: PhoneStatus = PhoneStatus()) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("monospace", Typeface.BOLD) } }
    val cpuPath = remember { Path() }
    val gpuPath = remember { Path() }
    Box(modifier) {
        HudScene(paint, rogBranding)
        Canvas(Modifier.fillMaxSize()) { design {
            label(paint, number(values.cpu), 92f, 300f, 68f, Cyan)
            label(paint, number(values.gpu), 92f, 540f, 68f, Orange)
            if (state.snapshot?.metrics?.cpuUsage?.status == "stale")
                label(paint, staleLabel, 270f, 215f, 22f, Orange)
            if (state.snapshot?.metrics?.gpuUsage?.status == "stale")
                label(paint, staleLabel, 270f, 455f, 22f, Orange)
            loadBar(values.cpu, Offset(78f, 325f), Cyan)
            loadBar(values.gpu, Offset(78f, 565f), Orange)
            label(paint, number(values.cpuTemp, ""), 1580f, 255f, 43f, Cyan)
            label(paint, number(values.gpuTemp, ""), 1580f, 345f, 43f, Orange)
            if (state.snapshot?.metrics?.cpuTemperature?.status == "stale")
                label(paint, staleLabel, 1425f, 255f, 18f, Orange)
            if (state.snapshot?.metrics?.gpuTemperature?.status == "stale")
                label(paint, staleLabel, 1425f, 345f, 18f, Orange)
            compactMemory(paint, values.ram, 1425f, 446f, staleLabel)
            compactMemory(paint, values.vram, 1425f, 556f, staleLabel)
            hudMemoryBar(values.ram?.percent?.toFloat(), Offset(1425f, 465f), Cyan)
            hudMemoryBar(values.vram?.percent?.toFloat(), Offset(1425f, 575f), Cyan)
            curve(cpuPath, state, nowMillis(), true, Cyan)
            curve(gpuPath, state, nowMillis(), false, Orange)
            drawCircle(if (state.isOffline) Orange else Cyan, 6f, Offset(345f, 695f))
            label(paint, if (state.demo) "DEMO" else status, 370f, 705f, 27f,
                if (state.isOffline) Orange else Cyan, maxWidth = 260f)
            label(paint, age, 1015f, 705f, 24f, White)
        } }
        PhoneStatusLayer(paint, phone, sport = false)
    }
}
private fun DrawScope.outline(origin: Offset, size: Size, color: Color) =
    drawRect(color, origin, size, style = Stroke(1.5f))
private fun DrawScope.loadBar(value: Float?, origin: Offset, color: Color) {
    for (index in 0..14) {
        val x = origin.x + index * 20
        val path = Path().apply { moveTo(x + 7, origin.y); lineTo(x + 23, origin.y)
            lineTo(x + 16, origin.y + 17); lineTo(x, origin.y + 17); close() }
        drawPath(path, if (value != null && value >= (index + 1) * 100f / 15) color else Color(0xFF16333F))
    }
}
private fun DrawScope.hudMemoryBar(value: Float?, origin: Offset, color: Color) {
    val path = Path().apply { moveTo(origin.x + 8, origin.y); lineTo(origin.x + 275, origin.y)
        lineTo(origin.x + 267, origin.y + 12); lineTo(origin.x, origin.y + 12); close() }
    drawPath(path, Color(0xFF173641))
    if (value != null) clipPath(path) {
        drawRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color), origin.x, origin.x + 275),
            origin, Size(275 * value.coerceIn(0f, 100f) / 100, 12f))
    }
}
private fun DrawScope.compactMemory(paint: Paint, metric: MemoryMetric?, x: Float, y: Float, staleLabel: String) {
    val text = metric?.usedBytes?.let { String.format(Locale.getDefault(), "%.1f%% · %.1f/%.1f",
        metric.percent, it / 1073741824.0, metric.totalBytes!! / 1073741824.0) } ?: "N/A"
    label(paint, text, x, y, 23f, Cyan)
    label(paint, "GiB", x + 250, y, 18f, Muted)
    if (metric?.status == "stale") label(paint, staleLabel, x, y + 25, 18f, Orange)
}
private fun staleSuffix(status: String?, label: String) = if (status == "stale") " · $label" else ""
private fun DrawScope.curve(path: Path, state: DashboardState, now: Long, cpu: Boolean, color: Color) {
    path.reset()
    var previous: Long? = null
    for (point in state.history) {
        val value = if (cpu) point.cpu else point.gpu
        val age = now - point.receivedMillis
        if (value == null || age !in 0..60_000) { previous = null; continue }
        val x = 1300f - age / 60_000f * 750f
        val y = 540f - value.toFloat() / 100f * 290f
        if (previous == null || point.receivedMillis - previous > 2500) path.moveTo(x, y) else path.lineTo(x, y)
        previous = point.receivedMillis
    }
    drawPath(path, color.copy(alpha = 0.08f), style = Stroke(13f))
    drawPath(path, color.copy(alpha = 0.18f), style = Stroke(7f))
    drawPath(path, color, style = Stroke(2.5f))
}

@Composable
private fun SportBackground(paint: Paint, rogBranding: Boolean) {
    val emblem = painterResource(if (rogBranding) R.drawable.brand_rog else R.drawable.brand_telemetry)
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) { design {
        drawRect(Color(0xFF070809), size = Size(WIDTH, HEIGHT))
        for (index in -40..90) {
            val x = index * 28f
            drawLine(Color(0xFF121416), Offset(x, HEIGHT), Offset(x + 700, 0f), 3f)
        }
        drawLine(Red, Offset(78f, 32f), Offset(78f, 66f), 4f)
        label(paint, "PC TELEMETRY", 95f, 63f, 36f)
        label(paint, "SPORT", 1435f, 63f, 38f, Red)
        for (centerX in listOf(470f, 1304f)) gaugeBackground(paint, Offset(centerX, 410f), 292f)
        label(paint, "CPU", 470f, 310f, 46f, center = true)
        label(paint, "GPU", 1304f, 310f, 46f, center = true)
        drawEmblem(emblem, Offset(790f, 205f), 194f, Red)
        label(paint, if (rogBranding) "REPUBLIC OF GAMERS" else "PC TELEMETRY", 887f, 400f, 18f, Silver, true)
        drawLine(Red.copy(alpha = 0.5f), Offset(70f, 838f), Offset(1704f, 838f), 3f)
        drawLine(Silver.copy(alpha = 0.4f), Offset(887f, 735f), Offset(887f, 815f), 1f)
    } }
}
