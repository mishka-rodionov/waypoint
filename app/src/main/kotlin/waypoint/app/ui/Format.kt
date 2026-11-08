package waypoint.app.ui

import java.util.Locale
import kotlin.math.roundToInt

fun formatTime(seconds: Double): String {
    val s = seconds.roundToInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatPct(x: Double): String = (if (x >= 0) "+" else "−") + String.format(Locale.ROOT, "%.1f%%", kotlin.math.abs(x) * 100).replace('.', ',')

fun formatMeters(m: Double): String = "${m.roundToInt()} м"
