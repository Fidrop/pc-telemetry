package com.pctelemetry.dashboard

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect

@Composable
internal fun PhoneStatusLayer(paint: Paint, phone: PhoneStatus, sport: Boolean) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        design {
            if (sport) sportPhoneStatus(paint, phone) else hudPhoneStatus(paint, phone)
        }
    }
}

// Seven-segment glyphs, not a font substitution. Colons remain steady for readability.
internal fun DrawScope.segmentTime(time: String, centerX: Float, top: Float, height: Float, color: Color) {
    val digitWidth = height * 0.54f
    val thickness = height * 0.085f
    val colonWidth = height * 0.16f
    val gap = height * 0.26f
    fun glyphWidth(character: Char) = when (character) {
        ':' -> colonWidth
        '1' -> thickness
        else -> digitWidth + thickness
    }
    val total = time.sumOf { glyphWidth(it).toDouble() }.toFloat() + gap * (time.length - 1)
    var x = centerX - total / 2
    val masks = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)
    for (character in time) {
        if (character == ':') {
            for (y in listOf(0.34f, 0.74f)) drawCircle(color, height * 0.035f, Offset(x + colonWidth / 2, top + height * y))
            x += colonWidth + gap
            continue
        }
        val mask = character.digitToIntOrNull()?.let { masks[it] } ?: 0x40
        // A lit "1" occupies only its vertical strokes; do not reserve an invisible full digit box.
        withTransform({ translate(x + thickness / 2 - if (character == '1') digitWidth else 0f, top) }) {
            val w = digitWidth; val h = height; val t = thickness
            val segments = listOf(
                Offset(t, 0f) to Offset(w - t, 0f),
                Offset(w, t) to Offset(w, h / 2 - t),
                Offset(w, h / 2 + t) to Offset(w, h - t),
                Offset(t, h) to Offset(w - t, h),
                Offset(0f, h / 2 + t) to Offset(0f, h - t),
                Offset(0f, t) to Offset(0f, h / 2 - t),
                Offset(t, h / 2) to Offset(w - t, h / 2))
            for ((index, ends) in segments.withIndex()) {
                val enabled = mask and (1 shl index) != 0
                if (enabled) drawLine(color.copy(alpha = 0.08f), ends.first, ends.second, t * 2.4f, StrokeCap.Square)
                if (enabled || character != '1')
                    drawLine(if (enabled) color else color.copy(alpha = 0.055f), ends.first, ends.second, t, StrokeCap.Butt)
            }
        }
        x += glyphWidth(character) + gap
    }
}

internal fun DrawScope.sportPhoneStatus(paint: Paint, phone: PhoneStatus) {
    val percent = phone.battery.percent
    val batteryColor = when {
        percent == null -> Silver
        percent > 60 -> Color(0xFF51E596)
        percent >= 20 -> Color(0xFFFFC23A)
        else -> Red
    }
    phoneBattery(paint, phone.battery, Offset(804f, 42f), batteryColor, compact = false)
    for (index in 0..9) {
        val origin = Offset(618f + index * 55f, 92f)
        val size = Size(43f, 16f)
        drawRoundRect(Color(0xFF30383C), origin, size, CornerRadius(8f))
        val fraction = percent?.let { ((it - index * 10) / 10f).coerceIn(0f, 1f) } ?: 0f
        if (fraction > 0f) clipRect(origin.x, origin.y, origin.x + size.width * fraction, origin.y + size.height) {
            drawRoundRect(batteryColor, origin, size, CornerRadius(8f))
        }
    }
    // Recessed cluster display: silver bezel, red keyline and smoked glass.
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF667078), Color(0xFF171D23)), 610f, 679f),
        Offset(767f, 610f), Size(240f, 69f), CornerRadius(7f))
    drawRoundRect(Color(0xFF040708), Offset(770f, 613f), Size(234f, 63f), CornerRadius(5f))
    drawLine(Red.copy(alpha = 0.65f), Offset(786f, 675f), Offset(988f, 675f), 1f)
    label(paint, "LOCAL TIME", 887f, 628f, 10f, Silver, true)
    segmentTime(phone.time, 887f, 636f, 29f, White)
}

internal fun DrawScope.hudPhoneStatus(paint: Paint, phone: PhoneStatus) {
    val bezel = Path().apply { moveTo(774f, 66f); lineTo(1000f, 66f); lineTo(1012f, 78f)
        lineTo(1012f, 126f); lineTo(1000f, 138f); lineTo(774f, 138f)
        lineTo(762f, 126f); lineTo(762f, 78f); close() }
    drawPath(bezel, Color(0xF0020C14))
    drawPath(bezel, Cyan.copy(alpha = 0.4f), style = Stroke(1f))
    drawLine(Cyan, Offset(784f, 66f), Offset(825f, 66f), 2f)
    drawLine(Cyan, Offset(949f, 138f), Offset(990f, 138f), 2f)
    label(paint, "LOCAL TIME", 887f, 80f, 10f, Silver, true)
    segmentTime(phone.time, 887f, 91f, 34f, Cyan)
    phoneBattery(paint, phone.battery, Offset(1440f, 110f), Cyan, compact = false)
}

private fun DrawScope.phoneBattery(paint: Paint, battery: PhoneBattery, origin: Offset, color: Color, compact: Boolean) {
    val accent = if (battery.percent != null && battery.percent <= 15 && !battery.charging) Red else color
    drawRoundRect(accent.copy(alpha = 0.8f), origin, Size(31f, 17f), CornerRadius(2f), style = Stroke(1.5f))
    drawRect(accent, origin + Offset(32f, 5f), Size(3f, 7f))
    battery.percent?.let { percent -> drawRect(accent.copy(alpha = 0.7f), origin + Offset(3f, 3f), Size(25f * percent / 100, 11f)) }
    if (battery.charging) {
        val bolt = Path().apply { moveTo(origin.x + 20, origin.y - 2); lineTo(origin.x + 11, origin.y + 10)
            lineTo(origin.x + 17, origin.y + 10); lineTo(origin.x + 12, origin.y + 21)
            lineTo(origin.x + 24, origin.y + 6); lineTo(origin.x + 18, origin.y + 6); close() }
        drawPath(bolt, if (compact) Red else Orange)
    }
    val reading = battery.percent?.let { "$it%" } ?: "N/A"
    label(paint, "PHONE", origin.x + 45, origin.y + if (compact) 2 else 13, if (compact) 10f else 14f, Silver)
    label(paint, reading, origin.x + if (compact) 45 else 112, origin.y + if (compact) 22 else 15,
        if (compact) 21f else 22f, accent)
}
