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
import androidx.compose.ui.geometry.Rect
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
    val ram: MemoryMetric?, val vram: MemoryMetric?, val cpuPointer: Float? = cpu, val gpuPointer: Float? = gpu)

@Composable
fun animatedValues(state: DashboardState): InstrumentValues {
    val data = state.snapshot?.metrics
    @Composable fun smooth(value: Double?): Float? {
        val animated by animateFloatAsState((value ?: 0.0).toFloat(),
            tween(320, easing = FastOutSlowInEasing), label = "needle")
        return value?.let { animated }
    }
    return InstrumentValues(data?.cpuUsage?.value?.toFloat(), data?.gpuUsage?.value?.toFloat(),
        data?.cpuTemperature?.value?.toFloat(), data?.gpuTemperature?.value?.toFloat(), data?.ram, data?.vram,
        smooth(data?.cpuUsage?.value), smooth(data?.gpuUsage?.value))
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
internal fun polar(center: Offset, radius: Float, degrees: Float): Offset {
    val radians = degrees * PI / 180
    return Offset(center.x + radius * cos(radians).toFloat(), center.y + radius * sin(radians).toFloat())
}
private fun number(value: Float?, unit: String = "%") =
    value?.let { String.format(Locale.getDefault(), "%.1f%s", it, unit) } ?: "N/A"

internal fun DrawScope.reading(paint: Paint, value: Float?, centerX: Float, baseline: Float,
    fontSize: Float, color: Color = White, unit: String = "%") {
    if (value == null) { label(paint, "N/A", centerX, baseline, fontSize, color, true); return }
    val digits = number(value, "")
    paint.textSize = fontSize
    val digitWidth = paint.measureText(digits)
    paint.textSize = fontSize * 0.56f
    val unitWidth = paint.measureText(unit)
    val left = centerX - (digitWidth + unitWidth + 5f) / 2
    label(paint, digits, left, baseline, fontSize, color)
    label(paint, unit, left + digitWidth + 5f, baseline - 2f, fontSize * 0.56f, color)
}

@Composable
fun SportDashboard(values: InstrumentValues, state: DashboardState, status: String, age: String,
    selfTest: Float?, staleLabel: String, modifier: Modifier = Modifier, rogBranding: Boolean = true,
    phone: PhoneStatus = PhoneStatus(), onEngineStop: () -> Unit = {}, selfTestRecovery: Float = 0f,
    preset: SportPreset = SportPreset.CLASSIC) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC); fontFeatureSettings = "tnum"
    } }
    val detailPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) } }
    val presetPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); fontFeatureSettings = "tnum"
    } }
    Box(modifier) {
        if (preset == SportPreset.CLASSIC) SportBackground(paint, detailPaint, rogBranding)
        else SportPresetBackdrop(preset, presetPaint, detailPaint, rogBranding)
        Canvas(Modifier.fillMaxSize()) { design {
            fun sweep(live: Float?): Float? {
                if (selfTest == null) return live
                if (live == null && selfTestRecovery >= 1f) return null
                val peak = selfTest * 100f
                return peak + ((live ?: 0f) - peak) * selfTestRecovery
            }
            val cpu = sweep(values.cpu)
            val gpu = sweep(values.gpu)
            val cpuNeedle = sweep(values.cpuPointer)
            val gpuNeedle = sweep(values.gpuPointer)
            val instruments = if (preset == SportPreset.CLASSIC) paint else presetPaint
            val cpuPosition = preset.cpuPosition
            val gpuPosition = preset.gpuPosition
            val staleOffset = if (preset == SportPreset.CLASSIC) 36f else 29f
            if (preset == SportPreset.CLASSIC) {
                pointer(Offset(470f, 410f), cpuNeedle)
                pointer(Offset(1304f, 410f), gpuNeedle)
            } else {
                sportPresetLoad(preset, cpuNeedle, gpuNeedle)
            }
            reading(instruments, cpu, cpuPosition.x, cpuPosition.loadY, cpuPosition.loadSize, preset.readingColor)
            reading(instruments, gpu, gpuPosition.x, gpuPosition.loadY, gpuPosition.loadSize, preset.readingColor)
            if (selfTest == null && state.snapshot?.metrics?.cpuUsage?.status == "stale")
                label(paint, staleLabel, cpuPosition.x, cpuPosition.loadY + staleOffset, 23f, Orange, true)
            if (selfTest == null && state.snapshot?.metrics?.gpuUsage?.status == "stale")
                label(paint, staleLabel, gpuPosition.x, gpuPosition.loadY + staleOffset, 23f, Orange, true)
            val cpuTemp = number(sweep(values.cpuTemp), "°C") + if (selfTest == null) staleSuffix(state.snapshot?.metrics?.cpuTemperature?.status, staleLabel) else ""
            val gpuTemp = number(sweep(values.gpuTemp), "°C") + if (selfTest == null) staleSuffix(state.snapshot?.metrics?.gpuTemperature?.status, staleLabel) else ""
            if (selfTest == null && state.snapshot?.metrics?.cpuTemperature?.status == "stale")
                label(instruments, cpuTemp, cpuPosition.temperatureX, cpuPosition.temperatureY, 43f, center = true)
            else reading(instruments, sweep(values.cpuTemp), cpuPosition.temperatureX, cpuPosition.temperatureY, 43f, preset.readingColor, unit = "°C")
            if (selfTest == null && state.snapshot?.metrics?.gpuTemperature?.status == "stale")
                label(instruments, gpuTemp, gpuPosition.temperatureX, gpuPosition.temperatureY, 43f, center = true)
            else reading(instruments, sweep(values.gpuTemp), gpuPosition.temperatureX, gpuPosition.temperatureY, 43f, preset.readingColor, unit = "°C")
            label(detailPaint, status + if (selfTest != null) " · STARTUP SELF TEST" else "", 95f, 91f, 16f,
                if (selfTest != null) Orange else if (state.isOffline) Red else Silver)
            label(paint, "ENGINE STOP", 887f, 535f, 27f, White, true, 190f)
            label(detailPaint, "TAP TO SWITCH OFF", 887f, 568f, 13f, Silver, true, 190f)
            memory(paint, detailPaint, "RAM", values.ram, 90f, 752f, 690f, White, staleLabel,
                if (selfTest != null) sweep(values.ram?.percent?.toFloat()) else null)
            memory(paint, detailPaint, "VRAM", values.vram, 960f, 752f, 690f, White, staleLabel,
                if (selfTest != null) sweep(values.vram?.percent?.toFloat()) else null)
        } }
        PhoneStatusLayer(detailPaint, phone, sport = true)
        SportEngineStop(onEngineStop)
    }
}
private fun DrawScope.gaugeBackground(paint: Paint, center: Offset, radius: Float) {
    drawCircle(Color.Black.copy(alpha = 0.6f), radius + 21, center + Offset(0f, 7f))
    val steel = Brush.linearGradient(0f to Color(0xFF131A1E), 0.18f to Color(0xFF616C73),
        0.26f to Color(0xFFE5ECEF), 0.34f to Color(0xFF59656C), 0.52f to Color(0xFF161E23),
        0.76f to Color(0xFF7B868B), 0.88f to Color(0xFFB5C0C6), 1f to Color(0xFF1C262B),
        start = center - Offset(radius, radius), end = center + Offset(radius, radius))
    drawCircle(steel, radius + 15, center)
    drawCircle(Color(0xFF171F24), radius + 8, center, style = Stroke(1.5f))
    drawCircle(Color(0xFF030608), radius + 2, center)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF151D21), Color(0xFF05080A)),
        center - Offset(90f, 110f), radius * 1.6f), radius, center)
    drawCircle(Color(0xFF6E7B82), radius - 6, center, style = Stroke(1.1f))
    drawCircle(Color(0xFF1A2328), radius - 11, center, style = Stroke(1f))
    drawArc(Color.White.copy(alpha = 0.65f), 204f, 92f, false,
        center - Offset(radius + 12, radius + 12), Size((radius + 12) * 2, (radius + 12) * 2), style = Stroke(1.2f))
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
    val glass = Path().apply { addOval(Rect(center - Offset(radius - 13, radius - 13), Size((radius - 13) * 2, (radius - 13) * 2))) }
    clipPath(glass) {
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.025f), Color.Transparent,
            Color.Transparent), center - Offset(radius, radius), center + Offset(radius, radius)),
            center - Offset(radius, radius), Size(radius * 2, radius * 2))
    }
}
private fun DrawScope.pointer(center: Offset, value: Float?) {
    if (value == null) return
    val angle = 135f + 2.7f * value.coerceIn(0f, 100f)
    val side = polar(Offset.Zero, 4.5f, angle + 90f)
    val tail = polar(center, 29f, angle + 180f)
    val needle = Path().apply { moveTo(tail.x + side.x, tail.y + side.y)
        val tip = polar(center, 253f, angle); lineTo(tip.x, tip.y)
        lineTo(tail.x - side.x, tail.y - side.y); close() }
    withTransform({ translate(3f, 4f) }) { drawPath(needle, Color.Black.copy(alpha = 0.55f)) }
    drawPath(needle, Red)
    drawLine(White, polar(center, 30f, angle), polar(center, 247f, angle), 1.5f)
    drawCircle(Color(0xFF020506), 24f, center)
    drawCircle(Brush.linearGradient(listOf(Color(0xFFECF1F3), Color(0xFF4B565C), Color(0xFFC0CDD2), Color(0xFF263238)),
        center - Offset(17f, 20f), center + Offset(21f, 18f)), 21f, center)
    drawCircle(Color.White.copy(alpha = 0.45f), 20f, center, style = Stroke(0.7f))
}
private fun DrawScope.memory(paint: Paint, detailPaint: Paint, name: String, metric: MemoryMetric?,
    x: Float, y: Float, width: Float, color: Color, staleLabel: String, testPercent: Float? = null) {
    label(paint, name, x, y, 31f, color)
    val percent = testPercent?.toDouble() ?: metric?.percent
    label(paint, percent?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "N/A",
        x + 150, y, 42f, color)
    val capacity = if (testPercent != null) "SELF TEST" else metric?.usedBytes?.let { used ->
        String.format(Locale.getDefault(), "%.1f / %.1f GiB", used / 1073741824.0,
            metric.totalBytes!! / 1073741824.0)
    } ?: "N/A"
    label(detailPaint, capacity + if (testPercent == null) staleSuffix(metric?.status, staleLabel) else "", x + 395, y, 22f, color)
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
    val cpuFill = remember { Path() }
    val gpuFill = remember { Path() }
    val cpuTint = remember { Brush.verticalGradient(listOf(Cyan.copy(alpha = 0.12f), Color.Transparent), 250f, 540f) }
    val gpuTint = remember { Brush.verticalGradient(listOf(Orange.copy(alpha = 0.1f), Color.Transparent), 250f, 540f) }
    Box(modifier) {
        HudScene(paint, rogBranding)
        Canvas(Modifier.fillMaxSize()) { design {
            reading(paint, values.cpu, 226f, 300f, 68f, Cyan)
            reading(paint, values.gpu, 226f, 540f, 68f, Orange)
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
            val chartNow = nowMillis()
            val cpuEnd = curve(cpuPath, cpuFill, cpuTint, state, chartNow, true, Cyan)
            val gpuEnd = curve(gpuPath, gpuFill, gpuTint, state, chartNow, false, Orange)
            cpuEnd?.let { chartBadge(paint, it, Cyan, -32f) }
            gpuEnd?.let { chartBadge(paint, it, Orange, 8f) }
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
private data class ChartEnd(val x: Float, val y: Float, val value: Float)
private fun DrawScope.curve(path: Path, fill: Path, tint: Brush, state: DashboardState, now: Long,
    cpu: Boolean, color: Color): ChartEnd? {
    path.reset(); fill.reset()
    var previous: Long? = null
    var firstX: Float? = null
    var end: ChartEnd? = null
    fun closeSegment() {
        if (firstX != null && end != null) {
            fill.lineTo(end!!.x, 540f); fill.lineTo(firstX!!, 540f); fill.close()
        }
        firstX = null
    }
    for (point in state.history) {
        val value = if (cpu) point.cpu else point.gpu
        val age = now - point.receivedMillis
        if (value == null || age !in 0..60_000) { closeSegment(); previous = null; continue }
        val x = 1300f - age / 60_000f * 750f
        val y = 540f - value.toFloat() / 100f * 290f
        if (previous == null || point.receivedMillis - previous > 2500) {
            closeSegment(); path.moveTo(x, y); fill.moveTo(x, y); firstX = x
        } else { path.lineTo(x, y); fill.lineTo(x, y) }
        previous = point.receivedMillis
        end = ChartEnd(x, y, value.toFloat())
    }
    closeSegment()
    drawPath(fill, tint)
    drawPath(path, color.copy(alpha = 0.1f), style = Stroke(6f))
    drawPath(path, color, style = Stroke(2.2f))
    end?.let { drawCircle(Color(0xFF041018), 4.5f, Offset(it.x, it.y)); drawCircle(color, 3f, Offset(it.x, it.y)) }
    return end
}

private fun DrawScope.chartBadge(paint: Paint, end: ChartEnd, color: Color, offsetY: Float) {
    val origin = Offset((end.x + 10f).coerceAtMost(1276f), (end.y + offsetY).coerceIn(224f, 510f))
    drawRoundRect(Color(0xF507131B), origin, Size(74f, 28f), androidx.compose.ui.geometry.CornerRadius(3f))
    drawRoundRect(color.copy(alpha = 0.7f), origin, Size(74f, 28f), androidx.compose.ui.geometry.CornerRadius(3f), style = Stroke(1f))
    label(paint, number(end.value), origin.x + 37f, origin.y + 20f, 17f, color, true, 66f)
}

@Composable
private fun SportBackground(paint: Paint, detailPaint: Paint, rogBranding: Boolean) {
    val emblem = painterResource(if (rogBranding) R.drawable.brand_rog else R.drawable.brand_telemetry)
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) { design {
        drawRect(Color(0xFF070809), size = Size(WIDTH, HEIGHT))
        for (index in -90..180) {
            val x = index * 12f
            drawLine(Color(0xFF0F1214), Offset(x, HEIGHT), Offset(x + 700, 0f), 0.75f)
            drawLine(Color(0xFF0C0F11), Offset(x, 0f), Offset(x + 700, HEIGHT), 0.75f)
        }
        drawLine(Red, Offset(78f, 32f), Offset(78f, 66f), 4f)
        label(paint, "PC TELEMETRY", 95f, 63f, 36f)
        label(paint, "SPORT", 1435f, 63f, 38f, Red)
        for (centerX in listOf(470f, 1304f)) gaugeBackground(paint, Offset(centerX, 410f), 292f)
        label(paint, "CPU", 470f, 310f, 46f, center = true)
        label(paint, "GPU", 1304f, 310f, 46f, center = true)
        drawEmblem(emblem, Offset(790f, 205f), 194f, Red)
        label(detailPaint, if (rogBranding) "REPUBLIC OF GAMERS" else "PC TELEMETRY", 887f, 400f, 16f, Silver, true)
        drawLine(Red.copy(alpha = 0.5f), Offset(70f, 838f), Offset(1704f, 838f), 3f)
        drawLine(Silver.copy(alpha = 0.4f), Offset(887f, 735f), Offset(887f, 815f), 1f)
    } }
}
