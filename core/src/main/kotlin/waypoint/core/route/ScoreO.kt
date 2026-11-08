package waypoint.core.route

import kotlin.math.ceil
import kotlin.math.max

/** Итог плана «по выбору»: порядок КП (номера 1…n), очки, время с возвращением на финиш, штраф. */
data class ScoreResult(val order: List<Int>, val points: Int, val seconds: Double, val penalty: Int) {
    /** Очки за вычетом штрафа за опоздание. */
    val net: Int get() = points - penalty
}

/**
 * Ориентирование по выбору: взять КП с очками и вернуться на финиш (он же старт) в пределах лимита.
 * Опоздание штрафуется: каждые начатые [PENALTY_SECONDS] секунд — минус очко (12 очков в минуту, КП стоят 10–30).
 * Точное решение — динамика по подмножествам: для n ≤ 16 КП это доли секунды.
 */
object ScoreO {
    const val PENALTY_SECONDS = 5.0
    const val MAX_CONTROLS = 16

    fun penalty(seconds: Double, limit: Double): Int = if (seconds <= limit) 0 else ceil((seconds - limit) / PENALTY_SECONDS).toInt()

    /** Оценка порядка [order] (номера КП 1…n; 0 — старт). [time] (откуда, куда) — время между точками. */
    fun evaluate(order: List<Int>, points: IntArray, limit: Double, time: (Int, Int) -> Double): ScoreResult {
        var t = 0.0
        var prev = 0
        var sum = 0
        for (c in order) {
            t += time(prev, c)
            sum += points[c]
            prev = c
        }
        t += time(prev, 0)
        return ScoreResult(order, sum, t, penalty(t, limit))
    }

    /**
     * Лучший план: максимум очков за вычетом штрафа, при равенстве — быстрее.
     * [points] — очки по индексам 1…n (индекс 0 не используется).
     */
    fun solve(n: Int, points: IntArray, limit: Double, time: (Int, Int) -> Double): ScoreResult {
        require(n in 0..MAX_CONTROLS) { "Слишком много КП: $n" }
        if (n == 0) return ScoreResult(emptyList(), 0, 0.0, 0)
        val states = 1 shl n
        // dp[mask·n + j] — наименьшее время от старта, взяв КП из mask и стоя на КП j (0-based).
        val dp = DoubleArray(states * n) { Double.POSITIVE_INFINITY }
        val parent = IntArray(states * n) { -1 }
        for (j in 0 until n) dp[(1 shl j) * n + j] = time(0, j + 1)
        for (mask in 1 until states) for (j in 0 until n) {
            val cur = dp[mask * n + j]
            if (cur.isInfinite()) continue
            // Дальше лимита с запасом идти бессмысленно: штраф съест больше, чем даст любой КП.
            if (cur > limit + PENALTY_SECONDS * (points.max() + 1)) continue
            for (k in 0 until n) {
                if (mask and (1 shl k) != 0) continue
                val nm = mask or (1 shl k)
                val t = cur + time(j + 1, k + 1)
                if (t < dp[nm * n + k]) {
                    dp[nm * n + k] = t
                    parent[nm * n + k] = j
                }
            }
        }
        var best = ScoreResult(emptyList(), 0, 0.0, 0)
        for (mask in 1 until states) {
            var sum = 0
            for (j in 0 until n) if (mask and (1 shl j) != 0) sum += points[j + 1]
            for (j in 0 until n) {
                val t = dp[mask * n + j]
                if (t.isInfinite()) continue
                val total = t + time(j + 1, 0)
                val net = sum - penalty(total, limit)
                if (net > best.net || (net == best.net && total < best.seconds)) {
                    best = ScoreResult(restore(mask, j, n, parent), sum, total, penalty(total, limit))
                }
            }
        }
        return best
    }

    private fun restore(mask: Int, last: Int, n: Int, parent: IntArray): List<Int> {
        val out = ArrayList<Int>()
        var m = mask
        var j = last
        while (j >= 0) {
            out.add(j + 1)
            val p = parent[m * n + j]
            m = m and (1 shl j).inv()
            j = p
        }
        return out.reversed()
    }

    /** Нижняя оценка времени, чтобы взять все КП (для подбора лимита задачи). */
    fun allControlsTime(n: Int, time: (Int, Int) -> Double): Double {
        val all = IntArray(n + 1) { if (it == 0) 0 else 1 }
        // Лимит огромный: решатель берёт все КП самым быстрым порядком.
        return solve(n, all, 1e12, time).seconds.let { max(it, 0.0) }
    }
}
