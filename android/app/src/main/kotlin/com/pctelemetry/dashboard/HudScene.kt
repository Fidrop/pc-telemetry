package com.pctelemetry.dashboard

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import kotlin.math.*
import kotlin.random.Random

internal fun DrawScope.drawEmblem(painter: Painter, position: Offset, width: Float, color: Color) {
    withTransform({ translate(position.x, position.y) }) {
        with(painter) { draw(Size(width, width), colorFilter = ColorFilter.tint(color)) }
    }
}

/** Static avionics artwork is recorded on its own layer, separate from 1 Hz readings. */
@Composable
internal fun HudScene(paint: Paint, rogBranding: Boolean) {
    val emblem = painterResource(if (rogBranding) R.drawable.brand_rog else R.drawable.brand_telemetry)
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) { design {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF02070D), Color(0xFF061B29), Color(0xFF02080E))),
            size = Size(WIDTH, HEIGHT))
        val stars = Random(71)
        repeat(150) {
            val point = Offset(stars.nextFloat() * WIDTH, stars.nextFloat() * HEIGHT)
            drawCircle(Cyan.copy(alpha = stars.nextFloat() * 0.33f + 0.08f), if (it % 9 == 0) 1.3f else 0.6f, point)
        }
        for (x in 20..1754 step 42) drawLine(Cyan.copy(alpha = 0.025f), Offset(x.toFloat(), 110f), Offset(x.toFloat(), 845f))
        for (y in 110..845 step 42) drawLine(Cyan.copy(alpha = 0.025f), Offset(20f, y.toFloat()), Offset(1754f, y.toFloat()))
        orbitalNavigation()
        earthHorizon()
        spacecraft(Offset(1285f, 47f))

        // Header: the brand is a replaceable vector resource, not baked into the scene.
        drawEmblem(emblem, Offset(48f, -2f), 103f, Red)
        label(paint, "PC TELEMETRY", 182f, 52f, 35f)
        label(paint, "HARDWARE  /  FLIGHT DECK", 184f, 78f, 13f, Cyan.copy(alpha = 0.7f))
        drawLine(Cyan.copy(alpha = 0.6f), Offset(175f, 92f), Offset(590f, 92f), 1f)
        for (index in 0..2) slash(Offset(600f + index * 12, 37f), Cyan.copy(alpha = 0.5f))
        hudPanel(Offset(1510f, 20f), Size(190f, 60f), Cyan)
        label(paint, "HUD MODE", 1534f, 59f, 24f, Cyan)

        // Asymmetric, double-edged cut panels with physical-looking mounting details.
        hudPanel(Offset(40f, 150f), Size(370f, 218f), Cyan)
        hudPanel(Offset(40f, 390f), Size(370f, 220f), Cyan)
        hudPanel(Offset(450f, 150f), Size(910f, 460f), Cyan)
        hudPanel(Offset(1400f, 150f), Size(334f, 212f), Cyan)
        hudPanel(Offset(1400f, 382f), Size(334f, 228f), Cyan)
        // Thin topological lines and restrained hatch tabs add depth without invented metrics.
        for (index in 0..7) slash(Offset(260f + index * 13, 133f), Cyan.copy(alpha = 0.55f))
        for (index in 0..5) slash(Offset(1615f + index * 13, 801f), Cyan.copy(alpha = 0.55f))
        for (x in listOf(26f, 1748f)) {
            drawLine(Cyan.copy(alpha = 0.5f), Offset(x, 665f), Offset(x, 797f))
            for (y in listOf(680f, 782f)) crosshair(Offset(x, y), 5f, Cyan)
        }

        chipIcon(Offset(79f, 185f), Cyan)
        chipIcon(Offset(79f, 425f), Orange)
        label(paint, "CPU", 125f, 215f, 31f, White)
        label(paint, "GPU", 125f, 455f, 31f, White)
        for (start in listOf(220f, 460f)) for (index in 0..7) {
            val y = start + index * 18f
            drawLine(Cyan.copy(alpha = 0.55f), Offset(49f, y), Offset(if (index % 5 == 0) 60f else 55f, y), 1f)
        }
        label(paint, "PROCESSOR", 92f, 357f, 11f, Cyan.copy(alpha = 0.6f))
        label(paint, "GRAPHICS", 92f, 597f, 11f, Orange.copy(alpha = 0.7f))

        label(paint, "CPU / GPU USAGE", 485f, 199f, 25f)
        label(paint, "60s", 870f, 199f, 18f, Muted)
        drawRoundRect(Cyan, Offset(1110f, 181f), Size(25f, 7f), androidx.compose.ui.geometry.CornerRadius(2f))
        label(paint, "CPU", 1146f, 198f, 17f, Cyan)
        drawRoundRect(Orange, Offset(1230f, 181f), Size(25f, 7f), androidx.compose.ui.geometry.CornerRadius(2f))
        label(paint, "GPU", 1266f, 198f, 17f, Orange)
        drawLine(Cyan.copy(alpha = 0.3f), Offset(475f, 216f), Offset(1335f, 216f))
        for (index in 0..4) {
            val y = 540f - index * 72.5f
            drawLine(Cyan.copy(alpha = 0.23f), Offset(550f, y), Offset(1300f, y), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
            label(paint, "${index * 25}%", 477f, y + 7, 19f, Silver)
            drawLine(Cyan, Offset(543f, y), Offset(550f, y), 1.5f)
        }
        for (index in 0..6) {
            val x = 550f + index * 125
            drawLine(Cyan.copy(alpha = 0.18f), Offset(x, 250f), Offset(x, 540f), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
            label(paint, if (index == 6) "Now" else "-${60 - index * 10}s", x, 578f, 18f, Silver, true)
            drawLine(Cyan, Offset(x, 540f), Offset(x, 547f), 1.5f)
        }
        drawLine(Cyan.copy(alpha = 0.8f), Offset(550f, 250f), Offset(550f, 540f))
        drawLine(Cyan.copy(alpha = 0.8f), Offset(550f, 540f), Offset(1300f, 540f))

        label(paint, "CPU °C", 1425f, 203f, 20f, Silver)
        label(paint, "GPU °C", 1425f, 295f, 20f, Silver)
        drawLine(Cyan.copy(alpha = 0.24f), Offset(1425f, 270f), Offset(1709f, 270f))
        label(paint, "RAM", 1425f, 415f, 21f, White)
        label(paint, "VRAM", 1425f, 525f, 21f, White)
        drawLine(Cyan.copy(alpha = 0.24f), Offset(1425f, 492f), Offset(1709f, 492f))
        // The status strip contains only connection status, sample cadence and real data age.
        hudPanel(Offset(300f, 655f), Size(1174f, 75f), Cyan)
        drawLine(Cyan.copy(alpha = 0.3f), Offset(650f, 674f), Offset(650f, 714f))
        drawLine(Cyan.copy(alpha = 0.3f), Offset(970f, 674f), Offset(970f, 714f))
        label(paint, "REFRESH  1 Hz", 700f, 705f, 23f, Silver)
        for (x in listOf(295f, 1480f)) crosshair(Offset(x, 745f), 7f, Cyan)
        // Decorative registration marks intentionally omit fabricated heading/latency values.
        drawLine(Cyan.copy(alpha = 0.25f), Offset(887f, 744f), Offset(887f, 866f))
        crosshair(Offset(887f, 810f), 11f, Cyan)
        for (index in -8..8) {
            val x = 887f + index * 28
            val y = 777f + index * index * 0.42f
            drawLine(Cyan.copy(alpha = 0.5f), Offset(x, y), Offset(x, y + if (index % 4 == 0) 10 else 5), 1f)
        }
    } }
}

private fun DrawScope.hudPanel(origin: Offset, size: Size, color: Color) {
    val (x, y) = origin
    val w = size.width; val h = size.height; val cut = 22f
    val panel = Path().apply {
        moveTo(x + cut, y); lineTo(x + w - cut, y); lineTo(x + w, y + cut)
        lineTo(x + w, y + h - cut); lineTo(x + w - cut, y + h)
        lineTo(x + cut, y + h); lineTo(x, y + h - cut); lineTo(x, y + cut); close()
    }
    drawPath(panel, Brush.verticalGradient(listOf(Color(0xEF081D29), Color(0xF0020C14)), y, y + h))
    drawPath(panel, color.copy(alpha = 0.05f), style = Stroke(9f))
    drawPath(panel, color.copy(alpha = 0.56f), style = Stroke(1.2f))
    val edge = Path().apply { moveTo(x, y + 35); lineTo(x, y + cut); lineTo(x + cut, y); lineTo(x + 96, y)
        moveTo(x + w - 65, y + h); lineTo(x + w - cut, y + h); lineTo(x + w, y + h - cut); lineTo(x + w, y + h - 40) }
    drawPath(edge, color, style = Stroke(2.3f))
    drawLine(color.copy(alpha = 0.17f), Offset(x + cut + 8, y + 7), Offset(x + w - cut - 8, y + 7))
    for (point in listOf(Offset(x + 12, y + h - 25), Offset(x + w - 12, y + 25)))
        drawCircle(color.copy(alpha = 0.6f), 1.5f, point)
}
private fun DrawScope.slash(origin: Offset, color: Color) {
    val path = Path().apply { moveTo(origin.x + 7, origin.y); lineTo(origin.x + 13, origin.y)
        lineTo(origin.x + 3, origin.y + 9); lineTo(origin.x - 3, origin.y + 9); close() }
    drawPath(path, color)
}
private fun DrawScope.crosshair(center: Offset, radius: Float, color: Color) {
    drawLine(color.copy(alpha = 0.7f), center - Offset(radius, 0f), center + Offset(radius, 0f))
    drawLine(color.copy(alpha = 0.7f), center - Offset(0f, radius), center + Offset(0f, radius))
}
private fun DrawScope.chipIcon(origin: Offset, color: Color) {
    drawRect(color.copy(alpha = 0.7f), origin, Size(27f, 27f), style = Stroke(1.4f))
    drawRect(color.copy(alpha = 0.18f), origin + Offset(5f, 5f), Size(17f, 17f))
    for (index in 0..3) {
        val offset = 4f + index * 6
        for (direction in listOf(-5f, 27f)) {
            drawLine(color, origin + Offset(offset, direction), origin + Offset(offset, direction + 5))
            drawLine(color, origin + Offset(direction, offset), origin + Offset(direction + 5, offset))
        }
    }
}
private fun DrawScope.orbitalNavigation() {
    val center = Offset(887f, 430f)
    for (radius in listOf(345f, 372f, 405f)) {
        drawArc(Cyan.copy(alpha = if (radius == 372f) 0.6f else 0.16f), 214f, 112f, false,
            center - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(if (radius == 372f) 1.5f else 1f))
    }
    for (index in 0..36) {
        val angle = (218 + index * 2.9) * PI / 180
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(Cyan.copy(alpha = if (index % 4 == 0) 0.7f else 0.3f), center + direction * 368f,
            center + direction * (if (index % 4 == 0) 381f else 374f), 1f)
    }
    crosshair(Offset(887f, 58f), 11f, Cyan)
    drawCircle(Cyan, 2f, Offset(887f, 58f))
    drawLine(Cyan.copy(alpha = 0.2f), Offset(887f, 25f), Offset(887f, 139f))
}
private fun DrawScope.earthHorizon() {
    val surface = Path().apply { moveTo(55f, 962f); quadraticBezierTo(887f, 532f, 1719f, 962f)
        lineTo(1774f, 887f); lineTo(0f, 887f); close() }
    val horizon = Path().apply { moveTo(55f, 962f); quadraticBezierTo(887f, 532f, 1719f, 962f) }
    drawPath(horizon, Color(0xFF29A7D9).copy(alpha = 0.05f), style = Stroke(60f))
    drawPath(horizon, Cyan.copy(alpha = 0.07f), style = Stroke(26f))
    drawPath(surface, Brush.verticalGradient(listOf(Color(0xFF135176), Color(0xFF041421)), 750f, 887f))
    clipPath(surface) {
        // Stylized land masses and fixed city lights are decorative scene artwork.
        val land = Path().apply {
            moveTo(360f, 820f); lineTo(445f, 786f); lineTo(525f, 795f); lineTo(550f, 818f)
            lineTo(620f, 815f); lineTo(685f, 852f); lineTo(625f, 878f); lineTo(524f, 864f); close()
            moveTo(795f, 771f); lineTo(866f, 760f); lineTo(930f, 784f); lineTo(1020f, 774f)
            lineTo(1110f, 802f); lineTo(1190f, 807f); lineTo(1265f, 848f); lineTo(1180f, 862f)
            lineTo(1090f, 835f); lineTo(1010f, 857f); lineTo(912f, 812f); lineTo(846f, 825f); close()
        }
        drawPath(land, Color(0xFF183B49))
        val random = Random(85)
        clipPath(land) {
            repeat(190) {
                val point = Offset(350f + random.nextFloat() * 940, 755f + random.nextFloat() * 125)
                drawCircle(Color(0xFFFFCF8E).copy(alpha = random.nextFloat() * 0.6f), 0.6f + random.nextFloat(), point)
            }
        }
        for (offset in listOf(45f, 95f, 155f)) {
            val latitude = Path().apply { moveTo(100f, 962f + offset); quadraticBezierTo(887f, 532f + offset, 1674f, 962f + offset) }
            drawPath(latitude, Cyan.copy(alpha = 0.12f), style = Stroke(1f))
        }
        for (index in -5..5) {
            val meridian = Path().apply { moveTo(887f + index * 85, 736f)
                quadraticBezierTo(887f + index * 150, 835f, 887f + index * 180, 900f) }
            drawPath(meridian, Cyan.copy(alpha = 0.12f), style = Stroke(1f))
        }
    }
    drawPath(horizon, Color(0xFF55D6FC).copy(alpha = 0.35f), style = Stroke(7f))
    drawPath(horizon, Color(0xFFB3F3FF), style = Stroke(1.4f))
}
private fun DrawScope.spacecraft(origin: Offset) {
    withTransform({ translate(origin.x, origin.y); rotate(-16f, Offset.Zero) }) {
        val hull = Path().apply { moveTo(-44f, 7f); lineTo(4f, -4f); lineTo(14f, -25f)
            lineTo(19f, -2f); lineTo(69f, 5f); lineTo(20f, 13f); lineTo(10f, 34f)
            lineTo(4f, 14f); close() }
        drawPath(hull, Color(0xFF102B3B))
        drawPath(hull, Cyan.copy(alpha = 0.3f), style = Stroke(1f))
        for (side in listOf(-1f, 1f)) {
            val wing = Path().apply { moveTo(2f, side * 10); lineTo(-25f, side * 21)
                lineTo(-37f, side * 12); lineTo(-1f, side * 4); close() }
            drawPath(wing, Color(0xFF164054))
            drawPath(wing, Cyan.copy(alpha = 0.4f), style = Stroke(0.7f))
        }
        drawLine(Cyan.copy(alpha = 0.65f), Offset(-20f, 7f), Offset(48f, 5f), 1f)
    }
}
