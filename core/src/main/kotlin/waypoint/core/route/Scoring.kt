package waypoint.core.route

import kotlin.math.roundToInt

/** Очки за связку. Формула временная: 100 минус удвоенный процент потери, не меньше нуля. */
object Scoring {
    fun score(loss: Double): Int = (100 * (1 - loss.coerceAtLeast(0.0) * 2)).coerceAtLeast(0.0).roundToInt()
}

val LegAnalysis.score: Int get() = Scoring.score(loss)
