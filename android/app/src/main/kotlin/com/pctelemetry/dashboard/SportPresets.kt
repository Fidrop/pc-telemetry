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
import androidx.compose.ui.graphics.drawscope.clipPath
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
    val hood = linePath(listOf(Offset(87f, 523f), Offset(140f, 383f), Offset(324f, 245f),
        Offset(555f, 181f), Offset(1219f, 181f), Offset(1450f, 245f), Offset(1634f, 383f),
        Offset(1687f, 523f), Offset(1640f, 701f), Offset(134f, 701f)), closed = true)
    drawPath(hood, Brush.verticalGradient(listOf(Color(0xFF17191F), Color.Black, Color(0xFF101217)), 181f, 701f))
    drawPath(hood, Color(0xFF282C33), style = Stroke(2f))
    apexArc(6f, ApexStart, ApexSweep, Color(0xFF111318), 46f)
    apexArc(6f, ApexStart + ApexSweep * .8f, ApexSweep * .2f, Color(0xFF250B0A), 46f)
    apexArc(-18f, ApexStart, ApexSweep, StoWhite.copy(alpha = .50f), 1.5f)
    // The inner keyline drops under each major numeral, separate from the outer scale ticks.
    val innerEdge = (0..100).map { index ->
        val distanceToTick = minOf(index % 10, 10 - index % 10)
        val notch = when (distanceToTick) { 0, 1 -> 10f; 2 -> 4f; else -> 0f }
        apexPoint(ApexStart + ApexSweep * index / 100f, 40f + notch)
    }
    drawPath(linePath(innerEdge), StoWhite.copy(alpha = .77f), style = Stroke(1.7f))
    drawPath(linePath(innerEdge.drop(80)), StoRed, style = Stroke(1.9f))
    for (row in 0..6) for (index in 1..179) {
        val fraction = (index + (row % 2) * .5f) / 180f
        val color = if (fraction >= .8f) StoRed else StoWhite
        drawCircle(color.copy(alpha = if (fraction >= .8f) .32f else .18f),
            1.05f, apexPoint(ApexStart + ApexSweep * fraction, -9f + row * 5.5f))
    }
    for (tick in 0..50) {
        val angle = ApexStart + tick * ApexSweep / 50
        val major = tick % 5 == 0
        val color = if (tick >= 40) StoRed else StoWhite
        drawLine(color.copy(alpha = if (major) 1f else .58f),
            apexPoint(angle, -20f), apexPoint(angle, if (major) -9f else -15f),
            if (major) 2.8f else 1.2f, cap = StrokeCap.Butt)
        if (major) {
            val p = apexPoint(angle, 32f)
            drawPath(linePath(listOf(p + Offset(-21f, -14f), p + Offset(21f, -14f),
                p + Offset(24f, 12f), p + Offset(-24f, 12f)), closed = true), Color(0xFF08090C))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.25f
            label(paint, (tick * 2).toString(), p.x, p.y + 8f, 26f, color, true)
            paint.style = Paint.Style.FILL
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

private fun linePath(points: List<Offset>, closed: Boolean = false) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    if (closed) close()
}

private fun wingPath(mirror: Boolean): Path {
    fun x(value: Float) = if (mirror) WIDTH - value else value
    return Path().apply {
        moveTo(x(668f), 632f)
        lineTo(x(360f), 632f)
        quadraticTo(x(308f), 632f, x(270f), 606f)
        lineTo(x(158f), 517f)
        quadraticTo(x(121f), 486f, x(121f), 428f)
        quadraticTo(x(122f), 310f, x(228f), 249f)
        lineTo(x(316f), 203f)
        lineTo(x(458f), 197f)
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
    for (mirror in listOf(false, true)) {
        fun edge(x: Float, y: Float) = Offset(if (mirror) WIDTH - x else x, y)
        val facet = linePath(listOf(edge(114f, 412f), edge(184f, 279f), edge(318f, 198f),
            edge(673f, 185f), edge(592f, 252f), edge(535f, 327f), edge(504f, 403f),
            edge(513f, 503f), edge(564f, 584f), edge(674f, 644f), edge(363f, 650f), edge(151f, 535f)), closed = true)
        clipPath(outer) {
            drawPath(facet, Brush.verticalGradient(listOf(Color(0xFF343854), Color(0xFF222847), Color(0xFF141833)), 185f, 650f))
            drawPath(facet, WingWhite.copy(alpha = .15f), style = Stroke(1f))
        }
    }
    wingScale(paint, LeftWing, false)
    wingScale(paint, RightWing, true)
    label(detail, "CPU LOAD", cpu.x, 373f, 21f, WingWhite, true)
    label(detail, "GPU LOAD", gpu.x, 373f, 21f, WingWhite, true)
    for (center in listOf(cpu.x, gpu.x)) {
        drawLine(WingWhite.copy(alpha = .35f), Offset(center - 108f, 496f), Offset(center + 108f, 496f))
    }
}

private fun DrawScope.wingScale(paint: Paint, measure: PathMeasure, mirror: Boolean) {
    drawMeasuredWing(measure, 0f, 100f, Color(0xFF101425), 29f)
    drawMeasuredWing(measure, 0f, 100f, Color(0xFF575D76), 23f)
    drawMeasuredWing(measure, 0f, 100f, Color(0xFF292F4E), 19f)
    drawMeasuredWing(measure, 0f, 100f, WingWhite.copy(alpha = .72f), 2f)
    drawMeasuredWing(measure, 80f, 100f, WingRed, 3f)
    for (tick in 0..40) {
        val fraction = tick / 40f
        val distance = measure.length * fraction
        val pos = measure.getPosition(distance)
        val tangent = measure.getTangent(distance)
        val inward = if (mirror) Offset(tangent.y, -tangent.x) else Offset(-tangent.y, tangent.x)
        val major = tick % 4 == 0
        val color = if (tick >= 32) WingRed else WingWhite
        drawLine(color.copy(alpha = if (major) 1f else .62f), pos + inward * 10f,
            pos + inward * (if (major) 28f else 17f), if (major) 2.6f else 1.4f, cap = StrokeCap.Butt)
        if (major) {
            val marker = pos + inward * 49f
            label(paint, (tick * 5 / 2).toString(), marker.x, marker.y + 7f, 21f, color, true)
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
            val normal = minOf(sweep, ApexSweep * .8f)
            if (normal > 0f) apexArc(5f, ApexStart, normal, StoWhite.copy(alpha = .15f), 39f)
            if (sweep > normal) apexArc(5f, ApexStart + normal, sweep - normal, StoRed.copy(alpha = .30f), 39f)
            val angle = ApexStart + sweep
            val cursor = linePath(listOf(apexPoint(angle - .32f, -19f), apexPoint(angle + .32f, -19f),
                apexPoint(angle + .16f, 12f), apexPoint(angle, 18f), apexPoint(angle - .16f, 12f)), closed = true)
            drawPath(cursor, StoRed)
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
            val inward = if (measure === LeftWing) normal else -normal
            drawPath(linePath(listOf(point - inward * 12f - tangent * 3f, point - inward * 12f + tangent * 3f,
                point + inward * 24f + tangent * 11f, point + inward * 15f - tangent * 5f), closed = true), WingRed)
        }
    }
}
