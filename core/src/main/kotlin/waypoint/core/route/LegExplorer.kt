package waypoint.core.route

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.grid.Rasterizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Разбор связки «для составителя»: оптимум, существенно разные альтернативы и «бег напрямик».
 *
 * Связка интересна, когда выбор есть и он не очевиден: второй по времени непохожий путь
 * проигрывает оптимуму немного (несколько процентов), и/или бег по прямой заметно хуже оптимума.
 */
data class LegReport(
    val from: Vec2,
    val to: Vec2,
    /** Непохожие пути, по возрастанию времени; первый — оптимум. */
    val options: List<RouteStats>,
    /** Лучший путь вдоль прямой (коридор 15 м): что получит бегущий «по азимуту»; null — прямая перекрыта. */
    val straight: RouteStats?,
) {
    /** Вдоль прямой не пройти (озеро, забор): самая сильная ловушка. */
    val straightBlocked: Boolean get() = straight == null

    val optimum: RouteStats get() = options.first()

    /** Насколько второй вариант медленнее оптимума (0.06 = на 6%); null — альтернатив нет. */
    val choiceGap: Double? get() = options.getOrNull(1)?.let { it.seconds / optimum.seconds - 1 }

    /** Насколько бег напрямик медленнее оптимума. */
    val straightLoss: Double? get() = straight?.let { it.seconds / optimum.seconds - 1 }

    /**
     * Интересность 0…1. Выбор: лучше всего, когда второй вариант медленнее на 3–12%
     * (меньше — всё равно как бежать, больше — выбор очевиден). Ловушка: прямая хуже оптимума на 25% и больше.
     */
    val interest: Double
        get() {
            val choice = choiceGap?.let { g ->
                when {
                    g < 0.01 -> 0.0
                    g < 0.03 -> (g - 0.01) / 0.02
                    g <= 0.12 -> 1.0
                    g < 0.25 -> (0.25 - g) / 0.13
                    else -> 0.0
                }
            } ?: 0.0
            val trap = straightLoss?.let { (it / 0.25).coerceIn(0.0, 1.0) } ?: 1.0
            return 0.65 * choice + 0.35 * trap
        }
}

class LegExplorer(private val model: MovementModel) {
    private val pathfinder = Pathfinder(model)
    private val corridor = CorridorRouter(model)
    private val spec = model.spec

    /** Сколько коридоров уже найденных путей накрывает ячейку (переиспользуется между вызовами). */
    private val penaltyCount = ByteArray(spec.size)

    fun explore(from: Vec2, to: Vec2, maxOptions: Int = 3): LegReport? {
        val length = from.distanceTo(to)
        val window = Box.of(listOf(from, to)).expand(max(300.0, length * 0.75))
        val first = pathfinder.find(from, to, window) ?: return null
        val options = arrayListOf(first)

        // Ширина «занятого» коридора и порог непохожести растут с длиной связки.
        val width = max(20.0, length * 0.07)
        val distinct = max(30.0, length * 0.08)
        val touched = ArrayList<Int>()
        for (attempt in 1 until maxOptions) {
            // Штрафуем коридор последнего найденного пути (предыдущие уже отмечены).
            val marked = HashSet<Int>() // отрезки ломаной перекрываются: одна ячейка — один раз за путь
            Rasterizer.strokePolyline(options.last().points, width, spec) { i ->
                // Возле старта и финиша пути неизбежно совпадают: там не штрафуем.
                val c = spec.cellCenter(i)
                if (marked.add(i) && c.distanceTo(from) > width * 1.5 && c.distanceTo(to) > width * 1.5) {
                    if (penaltyCount[i] == 0.toByte()) touched.add(i)
                    if (penaltyCount[i] < 100) penaltyCount[i]++
                }
            }
            val alt = pathfinder.find(from, to, window, penalty = { i -> 1f + 2f * penaltyCount[i] })
            if (alt == null || alt.seconds > first.seconds * 1.5 || options.any { farShare(alt.points, it.points, distinct) < 0.25 }) break
            options.add(alt)
        }
        for (i in touched) penaltyCount[i] = 0

        val straight = corridor.route(listOf(from, to), 15.0)
        return LegReport(
            from, to,
            options.sortedBy { it.seconds }.map { model.statsOf(it) },
            straight?.let { model.statsOf(it) },
        )
    }

    /** Доля точек пути [a], удалённых от пути [b] дальше [dist]. */
    private fun farShare(a: List<Vec2>, b: List<Vec2>, dist: Double): Double {
        var far = 0
        for (p in a) {
            var near = false
            for (q in b) if (abs(p.x - q.x) <= dist && abs(p.y - q.y) <= dist && p.distanceTo(q) <= dist) { near = true; break }
            if (!near) far++
        }
        return far.toDouble() / a.size
    }
}
