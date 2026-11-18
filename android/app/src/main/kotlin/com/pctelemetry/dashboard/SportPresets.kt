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

private val StoWhite = Color(0xFFEDF0F2)
private val StoRed = Color(0xFFFF382B)
private val WingWhite = Color(0xFFF3EEE4)
private val WingRed = Color(0xFFFF4B2F)
internal val SportPreset.readingColor: Color
    get() = when (this) {
        SportPreset.CLASSIC -> White
        SportPreset.APEX -> StoWhite
        SportPreset.TOURING -> WingWhite
    }
private const val ApexStart = 205f
private const val ApexSweep = 130f

@Composable
internal fun SportPresetBackdrop(preset: SportPreset, paint: Paint, detail: Paint, rogBranding: Boolean) {
    val emblem = painterResource(if (rogBranding) R.drawable.brand_rog else R.drawable.brand_telemetry)
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        design {
            val apex = preset == SportPreset.APEX
            val accent = if (apex) StoRed else WingWhite
            drawRect(Brush.verticalGradient(listOf(Color.Black,
                if (apex) Color(0xFF09090C) else Color(0xFF090A1A), Color.Black),
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
    apexArc(-15f, ApexStart, ApexSweep, StoWhite.copy(alpha = .44f), 1.4f)
    apexArc(17f, ApexStart, ApexSweep, StoWhite.copy(alpha = .70f), 1.8f)
    apexArc(17f, ApexStart + ApexSweep * .8f, ApexSweep * .2f, StoRed, 2.4f)
    for (index in 0 until 60) {
        val start = ApexStart + index * ApexSweep / 60f
        val redZone = index >= 48
        apexArc(0f, start, ApexSweep / 60f - .32f,
            if (redZone) Color(0xFF44110E) else Color(0xFF1C1C22), 23f)
    }
    for (tick in 0..50) {
        val angle = ApexStart + tick * ApexSweep / 50
        val major = tick % 5 == 0
        val color = if (tick >= 40) StoRed else StoWhite
        drawLine(color.copy(alpha = if (major) 1f else .58f),
            apexPoint(angle, -16f), apexPoint(angle, if (major) -5f else -10f),
            if (major) 2.8f else 1.2f, cap = StrokeCap.Butt)
        if (major) {
            val p = apexPoint(angle, 55f)
            label(paint, (tick * 2).toString(), p.x, p.y + 8f, 26f, color, true)
        }
    }
    val lowerBand = linePath(listOf(Offset(108f, 697f), Offset(252f, 661f), Offset(688f, 661f),
        Offset(760f, 700f), Offset(1014f, 700f), Offset(1086f, 661f), Offset(1522f, 661f), Offset(1666f, 697f)))
    drawPath(lowerBand, StoWhite.copy(alpha = .58f), style = Stroke(2f, join = StrokeJoin.Miter))
    label(detail, "GPU LOAD", gpu.x, 326f, 22f, StoWhite, true)
    label(detail, "CPU LOAD", cpu.x, 507f, 22f, StoWhite, true)
    drawLine(StoWhite.copy(alpha = .35f), Offset(1506f, 435f), Offset(1650f, 435f))
    label(detail, "CPU TEMP", cpu.temperatureX, 463f, 18f, StoWhite, true)
    label(detail, "GPU TEMP", gpu.temperatureX, 560f, 18f, StoWhite, true)
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
    drawPath(outer, Brush.verticalGradient(listOf(Color(0xFF15162F), Color(0xFF191D43), Color(0xFF0C1025)), 167f, 694f))
    drawPath(outer, WingWhite.copy(alpha = .24f), style = Stroke(1.5f, join = StrokeJoin.Miter))
    wingScale(paint, LeftWing, false)
    wingScale(paint, RightWing, true)
    label(detail, "CPU LOAD", cpu.x, 373f, 21f, WingWhite, true)
    label(detail, "GPU LOAD", gpu.x, 373f, 21f, WingWhite, true)
    for (center in listOf(cpu.x, gpu.x)) {
        drawLine(WingWhite.copy(alpha = .35f), Offset(center - 108f, 496f), Offset(center + 108f, 496f))
    }
}

private fun DrawScope.wingScale(paint: Paint, measure: PathMeasure, mirror: Boolean) {
    drawMeasuredWing(measure, 0f, 100f, Color(0xFF25284A), 19f)
    drawMeasuredWing(measure, 0f, 100f, WingWhite.copy(alpha = .72f), 2f)
    drawMeasuredWing(measure, 80f, 100f, WingRed, 3f)
    for (tick in 0..20) {
        val fraction = tick / 20f
        val distance = measure.length * fraction
        val pos = measure.getPosition(distance)
        val tangent = measure.getTangent(distance)
        val inward = if (mirror) Offset(tangent.y, -tangent.x) else Offset(-tangent.y, tangent.x)
        val major = tick % 4 == 0
        val color = if (tick >= 16) WingRed else WingWhite
        drawLine(color.copy(alpha = if (major) 1f else .62f), pos + inward * 10f,
            pos + inward * (if (major) 28f else 17f), if (major) 2.6f else 1.4f, cap = StrokeCap.Butt)
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
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
}

internal fun DrawScope.sportPresetLoad(preset: SportPreset, cpu: Float?, gpu: Float?) {
    if (preset == SportPreset.APEX) {
        gpu?.coerceIn(0f, 100f)?.let { value ->
            val sweep = value * ApexSweep / 100f
            for (index in 0 until 60) {
                val start = index * ApexSweep / 60f
                val length = (sweep - start).coerceIn(0f, ApexSweep / 60f - .32f)
                if (length <= 0f) break
                apexArc(0f, ApexStart + start, length,
                    if (index >= 48) StoRed.copy(alpha = .63f) else StoWhite.copy(alpha = .30f), 19f)
            }
            val angle = ApexStart + sweep
            drawLine(StoRed, apexPoint(angle, -16f), apexPoint(angle, 23f), 5f, cap = StrokeCap.Butt)
        }
    } else {
        for ((measure, sample) in listOf(LeftWing to cpu, RightWing to gpu)) {
            val value = sample?.coerceIn(0f, 100f) ?: continue
            drawMeasuredWing(measure, 0f, value, WingWhite.copy(alpha = .22f), 12f)
            drawMeasuredWing(measure, 0f, value.coerceAtMost(80f), WingWhite, 3f)
            drawMeasuredWing(measure, 80f, value, WingRed, 4f)
            val distance = measure.length * value / 100f
            val point = measure.getPosition(distance)
            val tangent = measure.getTangent(distance)
            val normal = Offset(-tangent.y, tangent.x)
            drawLine(WingRed, point - normal * 12f, point + normal * 26f, 4f, cap = StrokeCap.Butt)
        }
    }
}
