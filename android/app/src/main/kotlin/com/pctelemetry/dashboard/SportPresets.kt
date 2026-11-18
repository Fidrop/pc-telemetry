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
        SportPreset.APEX -> SportReadingPosition(394f, 588f, 85f, 1309f, 588f)
        SportPreset.TOURING -> SportReadingPosition(450f, 456f, 122f, 522f, 523f)
    }

internal val SportPreset.gpuPosition: SportReadingPosition
    get() = when (this) {
        SportPreset.CLASSIC -> SportReadingPosition(1304f, 520f, 80f, 1304f, 605f)
        SportPreset.APEX -> SportReadingPosition(887f, 464f, 153f, 1517f, 588f)
        SportPreset.TOURING -> SportReadingPosition(1305f, 456f, 91f, 1377f, 523f)
    }

internal val SportPreset.engineTop: Float
    get() = when (this) {
        SportPreset.CLASSIC -> 495f
        SportPreset.APEX -> 606f
        SportPreset.TOURING -> 592f
    }

internal val SportPreset.clockTop: Float
    get() = when (this) {
        SportPreset.CLASSIC -> 610f
        SportPreset.APEX -> 744f
        SportPreset.TOURING -> 733f
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
            val logoTop = if (apex) 99f else 259f
            val logoWidth = if (apex) 124f else 140f
            drawEmblem(emblem, Offset((WIDTH - logoWidth) / 2f, logoTop), logoWidth, if (rogBranding) Red else accent)
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
        Offset(1687f, 523f), Offset(1663f, 746f), Offset(111f, 746f)), closed = true)
    drawPath(hood, Brush.verticalGradient(listOf(Color(0xFF17191F), Color.Black, Color(0xFF101217)), 181f, 746f))
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
    val footerRim = listOf(Offset(111f, 746f), Offset(229f, 712f), Offset(608f, 712f),
        Offset(712f, 610f), Offset(1062f, 610f), Offset(1166f, 712f), Offset(1545f, 712f), Offset(1663f, 746f))
    val footer = linePath(footerRim + listOf(Offset(1712f, 839f), Offset(62f, 839f)), closed = true)
    drawPath(footer, Brush.verticalGradient(listOf(Color(0xFF13151B), Color(0xFF050609)), 610f, 839f))
    drawPath(footer, Color(0xFF343940), style = Stroke(2f))
    drawPath(linePath(footerRim), StoWhite.copy(alpha = .72f), style = Stroke(2.5f, join = StrokeJoin.Miter))
    for (x in listOf(708f, 1066f)) {
        drawLine(StoWhite.copy(alpha = .36f), Offset(x, 750f), Offset(x, 814f), 1.5f)
    }
    label(detail, "GPU LOAD", gpu.x, 337f, 25f, StoWhite, true)
    label(detail, "CPU LOAD", cpu.x, 518f, 25f, StoWhite, true)
    drawLine(StoWhite.copy(alpha = .55f), Offset(1413f, 518f), Offset(1413f, 594f), 1.5f)
    label(detail, "CPU TEMP", cpu.temperatureX, 537f, 20f, StoWhite, true)
    label(detail, "GPU TEMP", gpu.temperatureX, 537f, 20f, StoWhite, true)
}

private fun linePath(points: List<Offset>, closed: Boolean = false) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    if (closed) close()
}

private fun wingPath(mirror: Boolean): Path {
    fun x(value: Float) = if (mirror) WIDTH - value else value
    return Path().apply {
        moveTo(x(668f), 748f)
        lineTo(x(360f), 748f)
        quadraticTo(x(303f), 748f, x(265f), 716f)
        lineTo(x(158f), 600f)
        quadraticTo(x(121f), 559f, x(121f), 508f)
        lineTo(x(121f), 399f)
        quadraticTo(x(122f), 318f, x(228f), 251f)
        lineTo(x(300f), 207f)
        lineTo(x(735f), 207f)
    }
}

private val LeftWing by lazy { PathMeasure().apply { setPath(wingPath(false), false) } }
private val RightWing by lazy { PathMeasure().apply { setPath(wingPath(true), false) } }

private fun DrawScope.wingBackdrop(paint: Paint, detail: Paint, cpu: SportReadingPosition, gpu: SportReadingPosition) {
    val outer = Path().apply {
        moveTo(88f, 355f)
        quadraticTo(105f, 183f, 336f, 175f)
        lineTo(1438f, 175f)
        quadraticTo(1669f, 183f, 1686f, 355f)
        lineTo(1686f, 610f)
        lineTo(1545f, 825f)
        lineTo(229f, 825f)
        lineTo(88f, 610f)
        close()
    }
    drawPath(outer, Brush.verticalGradient(listOf(Color(0xFF11132A), Color(0xFF181C3D), Color(0xFF0D1023)), 175f, 825f))
    drawPath(outer, WingWhite.copy(alpha = .24f), style = Stroke(1.5f, join = StrokeJoin.Miter))
    for (mirror in listOf(false, true)) {
        fun edge(x: Float, y: Float) = Offset(if (mirror) WIDTH - x else x, y)
        val facet = linePath(listOf(edge(114f, 409f), edge(181f, 281f), edge(300f, 195f),
            edge(741f, 193f), edge(657f, 295f), edge(595f, 438f), edge(635f, 579f),
            edge(714f, 753f), edge(355f, 769f), edge(155f, 606f)), closed = true)
        clipPath(outer) {
            drawPath(facet, Brush.verticalGradient(listOf(Color(0xFF2B2D49), Color(0xFF1F2447), Color(0xFF121631)), 193f, 769f))
            drawPath(facet, WingWhite.copy(alpha = .15f), style = Stroke(1f))
        }
    }
    wingScale(paint, LeftWing, false)
    wingScale(paint, RightWing, true)
    label(detail, "CPU LOAD", cpu.x, 342f, 25f, WingWhite, true)
    label(detail, "GPU LOAD", gpu.x, 360f, 24f, WingWhite, true)
    for ((name, position) in listOf("CPU TEMP" to cpu, "GPU TEMP" to gpu)) {
        for (y in listOf(489f, 546f)) {
            drawLine(WingWhite.copy(alpha = .50f), Offset(position.x - 146f, y), Offset(position.x + 146f, y), 1.5f)
        }
        label(detail, name, position.x - 65f, position.temperatureY, 20f, WingWhite, true)
    }
}

private fun DrawScope.wingScale(paint: Paint, measure: PathMeasure, mirror: Boolean) {
    drawMeasuredWing(measure, 0f, 100f, Color(0xFF101425), 29f)
    drawMeasuredWing(measure, 0f, 100f, Color(0xFF575D76), 23f)
    drawMeasuredWing(measure, 0f, 100f, Color(0xFF292F4E), 19f)
    drawMeasuredWing(measure, 0f, 100f, WingWhite.copy(alpha = .85f), 3f)
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
