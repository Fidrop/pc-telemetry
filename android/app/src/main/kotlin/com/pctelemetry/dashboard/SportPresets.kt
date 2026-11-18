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
import kotlin.math.cos
import kotlin.math.sin

enum class SportPreset(val label: Int) {
    CLASSIC(R.string.sport_classic), APEX(R.string.sport_apex), TOURING(R.string.sport_touring)
}

internal data class SportReadingPosition(
    val x: Float, val loadY: Float, val loadSize: Float, val temperatureX: Float, val temperatureY: Float
)

internal val SportPreset.cpuPosition: SportReadingPosition
    get() = when (this) {
        SportPreset.CLASSIC -> SportReadingPosition(470f, 520f, 80f, 470f, 605f)
        SportPreset.APEX -> SportReadingPosition(394f, 572f, 72f, 1578f, 506f)
        SportPreset.TOURING -> SportReadingPosition(452f, 465f, 94f, 452f, 549f)
    }

internal val SportPreset.gpuPosition: SportReadingPosition
    get() = when (this) {
        SportPreset.CLASSIC -> SportReadingPosition(1304f, 520f, 80f, 1304f, 605f)
        SportPreset.APEX -> SportReadingPosition(887f, 439f, 142f, 1578f, 604f)
        SportPreset.TOURING -> SportReadingPosition(1322f, 465f, 94f, 1322f, 549f)
    }

private val Steel = Color(0xFFC2CED8)
private val WingBlue = Color(0xFF82ACDB)
private val Track = Color(0xFF293849)
private val Dark = Color(0xFF05080D)
private const val ApexStart = 205f
private const val ApexSweep = 130f

@Composable
internal fun SportPresetBackdrop(preset: SportPreset, paint: Paint, detail: Paint, rogBranding: Boolean) {
    val emblem = painterResource(if (rogBranding) R.drawable.brand_rog else R.drawable.brand_telemetry)
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        design {
            val apex = preset == SportPreset.APEX
            val accent = if (apex) Red else WingBlue
            drawRect(Brush.verticalGradient(listOf(Dark, if (apex) Color(0xFF10131A) else Color(0xFF101C32), Dark),
                endY = HEIGHT), size = Size(WIDTH, HEIGHT))
            if (apex) apexBackdrop(paint, detail, preset.cpuPosition, preset.gpuPosition)
            else wingBackdrop(paint, detail, preset.cpuPosition, preset.gpuPosition)
            drawLine(accent, Offset(78f, 32f), Offset(78f, 66f), 3f)
            label(paint, "PC TELEMETRY", 95f, 63f, 36f)
            label(paint, "SPORT", 1435f, 63f, 37f, accent)
            val logoTop = if (apex) 115f else 220f
            drawEmblem(emblem, Offset(834f, logoTop), 106f, if (rogBranding) Red else accent)
            drawLine(accent.copy(alpha = .7f), Offset(74f, 838f), Offset(1700f, 838f), 2f)
            drawLine(Silver.copy(alpha = .36f), Offset(887f, 731f), Offset(887f, 815f))
        }
    }
}

private fun apexPoint(degrees: Float, inset: Float = 0f): Offset {
    val angle = Math.toRadians(degrees.toDouble())
    return Offset(887f + (647f - inset) * cos(angle).toFloat(),
        617f + (398f - inset) * sin(angle).toFloat())
}

private fun DrawScope.apexArc(inset: Float, start: Float, sweep: Float, color: Color, width: Float) {
    drawArc(color, start, sweep, false, Offset(240f + inset, 219f + inset),
        Size(1294f - inset * 2, 796f - inset * 2), style = Stroke(width))
}

private fun DrawScope.apexBackdrop(paint: Paint, detail: Paint, cpu: SportReadingPosition, gpu: SportReadingPosition) {
    apexArc(0f, ApexStart, ApexSweep, Track, 27f)
    apexArc(-15f, ApexStart, ApexSweep, Steel.copy(alpha = .47f), 1.6f)
    apexArc(17f, ApexStart, ApexSweep, Steel.copy(alpha = .45f), 1.5f)
    apexArc(0f, ApexStart + ApexSweep * .8f, ApexSweep * .2f, Color(0xFF701D27), 27f)
    for (tick in 0..50) {
        val angle = ApexStart + tick * ApexSweep / 50
        val major = tick % 5 == 0
        val color = if (tick >= 40) Red else Steel
        drawLine(color.copy(alpha = if (major) .9f else .44f),
            apexPoint(angle, -14f), apexPoint(angle, if (major) 0f else -7f), if (major) 2.8f else 1.2f)
        if (major) {
            val p = apexPoint(angle, 55f)
            label(paint, (tick * 2).toString(), p.x, p.y + 8f, 26f, color, true)
        }
    }
    val lowerBand = linePath(listOf(Offset(108f, 697f), Offset(252f, 661f), Offset(688f, 661f),
        Offset(760f, 700f), Offset(1014f, 700f), Offset(1086f, 661f), Offset(1522f, 661f), Offset(1666f, 697f)))
    drawPath(lowerBand, Steel.copy(alpha = .45f), style = Stroke(2f))
    label(detail, "GPU LOAD", gpu.x, 326f, 22f, Steel, true)
    label(detail, "CPU LOAD", cpu.x, 507f, 22f, Steel, true)
    drawLine(Steel.copy(alpha = .35f), Offset(1506f, 435f), Offset(1650f, 435f))
    label(detail, "CPU TEMP", cpu.temperatureX, 463f, 18f, Steel, true)
    label(detail, "GPU TEMP", gpu.temperatureX, 560f, 18f, Steel, true)
}

private fun linePath(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

private fun wingPath(mirror: Boolean): Path {
    fun x(value: Float) = if (mirror) WIDTH - value else value
    return Path().apply {
        moveTo(x(668f), 632f)
        lineTo(x(360f), 632f)
        quadraticTo(x(308f), 632f, x(270f), 606f)
        lineTo(x(158f), 517f)
        quadraticTo(x(121f), 486f, x(121f), 428f)
        quadraticTo(x(122f), 310f, x(230f), 246f)
        quadraticTo(x(313f), 197f, x(458f), 197f)
        lineTo(x(677f), 197f)
    }
}

private val LeftWing by lazy { PathMeasure().apply { setPath(wingPath(false), false) } }
private val RightWing by lazy { PathMeasure().apply { setPath(wingPath(true), false) } }

private fun DrawScope.wingBackdrop(paint: Paint, detail: Paint, cpu: SportReadingPosition, gpu: SportReadingPosition) {
    val outer = Path().apply {
        moveTo(88f, 330f); quadraticTo(147f, 171f, 366f, 167f)
        lineTo(1408f, 167f); quadraticTo(1627f, 171f, 1686f, 330f)
        lineTo(1686f, 570f); lineTo(1574f, 694f); lineTo(200f, 694f); lineTo(88f, 570f); close()
    }
    drawPath(outer, Brush.verticalGradient(listOf(Color(0xFF18243A), Color(0xFF0A101C)), 167f, 694f))
    drawPath(outer, Steel.copy(alpha = .22f), style = Stroke(2f))
    wingScale(paint, LeftWing, false)
    wingScale(paint, RightWing, true)
    label(detail, "CPU LOAD", cpu.x, 373f, 21f, Steel, true)
    label(detail, "GPU LOAD", gpu.x, 373f, 21f, Steel, true)
    for (center in listOf(cpu.x, gpu.x)) {
        drawLine(Steel.copy(alpha = .35f), Offset(center - 108f, 496f), Offset(center + 108f, 496f))
    }
}

private fun DrawScope.wingScale(paint: Paint, measure: PathMeasure, mirror: Boolean) {
    drawMeasuredWing(measure, 0f, 100f, Track, 35f)
    drawMeasuredWing(measure, 0f, 100f, Steel.copy(alpha = .72f), 2f)
    drawMeasuredWing(measure, 80f, 100f, Red.copy(alpha = .4f), 10f)
    for (tick in 0..20) {
        val fraction = tick / 20f
        val distance = measure.length * fraction
        val pos = measure.getPosition(distance)
        val tangent = measure.getTangent(distance)
        val inward = if (mirror) Offset(tangent.y, -tangent.x) else Offset(-tangent.y, tangent.x)
        val major = tick % 4 == 0
        val color = if (tick >= 16) Red else Steel
        drawLine(color, pos + inward * 14f, pos + inward * (if (major) 30f else 22f), if (major) 2.5f else 1.4f)
        if (major) {
            val marker = pos + inward * 49f
            label(paint, (tick * 5).toString(), marker.x, marker.y + 7f, 22f, color, true)
        }
    }
}

private fun DrawScope.drawMeasuredWing(measure: PathMeasure, from: Float, to: Float, color: Color, width: Float) {
    if (to <= from) return
    val path = Path()
    measure.getSegment(measure.length * from / 100f, measure.length * to / 100f, path, true)
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Butt, join = StrokeJoin.Round))
}

internal fun DrawScope.sportPresetLoad(preset: SportPreset, cpu: Float?, gpu: Float?) {
    if (preset == SportPreset.APEX) {
        gpu?.coerceIn(0f, 100f)?.let { value ->
            val sweep = value * ApexSweep / 100f
            if (sweep > 0f) apexArc(0f, ApexStart, sweep, Color(0xFFD3DFEA).copy(alpha = .37f), 22f)
            val angle = ApexStart + sweep
            drawLine(Red, apexPoint(angle, -14f), apexPoint(angle, 23f), 6f)
        }
    } else {
        for ((measure, sample) in listOf(LeftWing to cpu, RightWing to gpu)) {
            val value = sample?.coerceIn(0f, 100f) ?: continue
            drawMeasuredWing(measure, 0f, value, WingBlue.copy(alpha = .57f), 20f)
            drawMeasuredWing(measure, 0f, value, Steel, 2f)
            val distance = measure.length * value / 100f
            val point = measure.getPosition(distance)
            val tangent = measure.getTangent(distance)
            val normal = Offset(-tangent.y, tangent.x)
            drawLine(Red, point - normal * 16f, point + normal * 29f, 5f)
        }
    }
}
