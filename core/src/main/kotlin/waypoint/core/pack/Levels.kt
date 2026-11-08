package waypoint.core.pack

import kotlin.math.roundToInt

/**
 * Сложность связки 1…5 по той же метрике, что отбор связок (этап 3):
 * близкие по времени варианты — выбор труднее; ловушка на прямой (озеро, забор) — тоже;
 * длинная связка труднее короткой (больше вариантов и дольше держать план).
 */
object Difficulty {
    fun of(leg: PackLeg): Int {
        val gap = leg.choiceGap ?: leg.options.takeIf { it.size > 1 }?.let { it[1].seconds / it[0].seconds - 1 }
        val trap = leg.options.isNotEmpty() && (leg.straight == null || (leg.straightLoss ?: 0.0) > 0.3)
        val length = leg.from.distanceTo(leg.to)
        var d = 1.0
        d += when {
            gap == null -> 0.0
            gap < 0.04 -> 2.0
            gap < 0.08 -> 1.5
            gap < 0.15 -> 1.0
            else -> 0.5
        }
        if (trap) d += 0.7
        d += (length / 600.0).coerceAtMost(1.5)
        return d.roundToInt().coerceIn(1, 5)
    }
}

/** Уровень: связки одной ступени сложности. */
data class Level(val number: Int, val legs: List<PackLeg>) {
    val difficulty: Double get() = legs.map { Difficulty.of(it) }.average()
}

/**
 * Уровни: связки по возрастанию сложности, группами по [size].
 * Уровень открыт, если на предыдущем пройдено не меньше [toUnlock] связок с результатом от [passScore] очков.
 */
object Levels {
    const val SIZE = 8
    const val PASS_SCORE = 60

    fun group(legs: List<PackLeg>, size: Int = SIZE): List<Level> =
        legs.sortedWith(compareBy<PackLeg> { Difficulty.of(it) }.thenBy { it.from.distanceTo(it.to) })
            .chunked(size)
            .mapIndexed { i, chunk -> Level(i + 1, chunk) }

    fun toUnlock(level: Level): Int = (level.legs.size * 3 + 3) / 4

    /** Сколько связок уровня пройдено; [best] — лучшие очки по id связки. */
    fun passed(level: Level, best: (String) -> Int?): Int = level.legs.count { (best(it.id) ?: 0) >= PASS_SCORE }

    /** Открытые уровни: первый всегда, следующий — когда пройден порог предыдущего. */
    fun unlocked(levels: List<Level>, best: (String) -> Int?): Int {
        var open = if (levels.isEmpty()) 0 else 1
        for (i in 0 until levels.size - 1) {
            if (passed(levels[i], best) >= toUnlock(levels[i])) open = i + 2 else break
        }
        return open
    }
}
