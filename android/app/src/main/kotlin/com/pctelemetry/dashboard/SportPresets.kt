package com.pctelemetry.dashboard

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource

enum class SportPreset(val label: Int) {
    CLASSIC(R.string.sport_classic), APEX(R.string.sport_apex), TOURING(R.string.sport_touring)
}

private val CpuDial = Offset(470f, 442f)
private val GpuDial = Offset(1304f, 442f)
private val Gold = Color(0xFFFFBD5B)
private val Ice = Color(0xFF96D1FF)
private val Copper = Color(0xFFFF9564)
private val CpuRibbon by lazy { ribbonSegments(CpuDial.x) }
private val GpuRibbon by lazy { ribbonSegments(GpuDial.x) }

@Composable
internal fun SportPresetBackdrop(preset: SportPreset, paint: Paint, detail: Paint, rogBranding: Boolean) {
    val emblem = painterResource(if (rogBranding) R.drawable.brand_rog else R.drawable.brand_telemetry)
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        design {
            val apex = preset == SportPreset.APEX
            val accent = if (apex) Gold else Ice
            drawRect(Brush.verticalGradient(listOf(Color(0xFF070A0F), Color(0xFF131D2B), Color(0xFF06090E)),
                endY = HEIGHT), size = Size(WIDTH, HEIGHT))
            if (apex) {
                angularInstrument(paint, CpuDial.x, "CPU", Gold, mirror = false)
                angularInstrument(paint, GpuDial.x, "GPU", Gold, mirror = true)
            } else {
                ringInstrument(paint, CpuDial, "CPU", Ice)
                ringInstrument(paint, GpuDial, "GPU", Copper)
            }
            drawLine(accent, Offset(78f, 32f), Offset(78f, 66f), 3f)
            label(paint, "PC TELEMETRY", 95f, 63f, 36f)
            label(paint, "SPORT", 1435f, 63f, 37f, accent)
            drawEmblem(emblem, Offset(817f, 257f), 140f, if (rogBranding) Red else accent)
            label(detail, if (rogBranding) "REPUBLIC OF GAMERS" else "PC TELEMETRY", 887f, 410f, 14f, Silver, true)
            drawLine(accent.copy(alpha = .40f), Offset(74f, 838f), Offset(1700f, 838f), 2f)
            drawLine(Silver.copy(alpha = .3f), Offset(887f, 731f), Offset(887f, 815f))
        }
    }
}

private fun DrawScope.angularInstrument(paint: Paint, x: Float, title: String, accent: Color, mirror: Boolean) {
    val direction = if (mirror) -1f else 1f
    fun point(dx: Float, y: Float) = Offset(x + direction * dx, y)
    val shell = Path().apply {
        listOf(point(-340f, 298f), point(-228f, 186f), point(234f, 186f), point(321f, 273f),
            point(321f, 616f), point(247f, 694f), point(-279f, 694f), point(-340f, 634f))
            .forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    drawPath(shell, Brush.verticalGradient(listOf(Color(0xFF19232D), Color(0xFF070B11)), 186f, 694f))
    drawPath(shell, accent.copy(alpha = .30f), style = Stroke(2f))
    drawLine(accent, point(-336f, 305f), point(-227f, 196f), 3f)
    drawLine(accent.copy(alpha = .5f), point(244f, 685f), point(313f, 612f), 2f)
    for (step in 0..4) label(paint, (step * 25).toString(), x - 230f + step * 115f, 334f, 21f, Silver, true)
    drawLine(Silver.copy(alpha = .21f), Offset(x - 225f, 551f), Offset(x + 225f, 551f))
    label(paint, title, x, 409f, 36f, accent, true)
}

private fun DrawScope.ringInstrument(paint: Paint, center: Offset, title: String, accent: Color) {
    drawCircle(Color(0xFF05080D), 280f, center + Offset(0f, 4f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF142236), Color(0xFF0A0E16)), center, 272f), 272f, center)
    drawCircle(Color(0xFF354557), 271f, center, style = Stroke(2f))
    drawCircle(Color(0xFF1E2D3E), 207f, center, style = Stroke(1f))
    ringTrack(center, 100f, Color(0xFF243548), 10f)
    for (step in 0..20) {
        val angle = 148f + step * 12.2f
        val major = step % 4 == 0
        drawLine(if (major) White else Silver.copy(alpha = .6f), polar(center, 239f, angle),
            polar(center, if (major) 224f else 232f, angle), if (major) 3f else 1.5f)
        if (major) {
            val p = polar(center, 188f, angle)
            label(paint, (step * 5).toString(), p.x, p.y + 8f, 21f, Silver, true)
        }
    }
    label(paint, title, center.x, 381f, 37f, accent, true)
    drawLine(accent.copy(alpha = .28f), center + Offset(-80f, 82f), center + Offset(80f, 82f), 1f)
}

internal fun DrawScope.sportPresetLoad(preset: SportPreset, cpu: Float?, gpu: Float?) {
    if (preset == SportPreset.APEX) {
        loadRibbon(CpuRibbon, cpu)
        loadRibbon(GpuRibbon, gpu)
    } else {
        activeRing(CpuDial, cpu, Ice)
        activeRing(GpuDial, gpu, Copper)
    }
}

private fun ribbonSegments(x: Float) = List(20) { index ->
    val left = x - 230f + index * 23f
    val top = 255f - index * 1.4f
    Path().apply {
        moveTo(left + 8f, top); lineTo(left + 21f, top)
        lineTo(left + 13f, 300f); lineTo(left, 300f); close()
    }
}

private fun DrawScope.loadRibbon(segments: List<Path>, value: Float?) {
    val amount = (value ?: 0f).coerceIn(0f, 100f) / 5f
    segments.forEachIndexed { index, segment ->
        val color = if (index >= 16) Red else Gold
        drawPath(segment, Color(0xFF2B333D))
        val fill = (amount - index).coerceIn(0f, 1f)
        if (fill > 0f) drawPath(segment, color.copy(alpha = .30f + fill * .70f))
    }
}

private fun DrawScope.ringTrack(center: Offset, value: Float, color: Color, thickness: Float) {
    drawArc(color, 148f, value * 2.44f, false, center - Offset(251f, 251f), Size(502f, 502f),
        style = Stroke(thickness, cap = StrokeCap.Round))
}

private fun DrawScope.activeRing(center: Offset, value: Float?, accent: Color) {
    val percent = value?.coerceIn(0f, 100f) ?: return
    if (percent > 0f) {
        ringTrack(center, percent, accent.copy(alpha = .16f), 19f)
        ringTrack(center, percent, accent, 7f)
    }
    drawCircle(accent, 7f, polar(center, 251f, 148f + percent * 2.44f))
}
