package waypoint.core.route

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.geometry.polylineLength
import kotlin.math.max

/** Итог прохождения маршрута. [violations] — точки, где маршрут пересёк непреодолимое. */
data class RouteStats(
    val seconds: Double,
    val length: Double,
    val climb: Double,
    val points: List<Vec2>,
    val violations: List<Vec2> = emptyList(),
)

/** Разбор связки: путь игрока против оптимального. */
data class LegAnalysis(
    val optimal: RouteStats,
    /** Время строго по нарисованной линии. */
    val drawn: RouteStats,
    /** Лучший путь внутри коридора вокруг линии: «что игрок имел в виду», без погрешности пальца. */
    val intended: RouteStats?,
) {
    /** Потеря времени относительно оптимума, в долях (0.1 = на 10% медленнее). */
    val loss: Double get() = (intended ?: drawn).seconds / optimal.seconds - 1.0
}

class RouteEvaluator(private val model: MovementModel) {
    private val pathfinder = Pathfinder(model)
    private val corridorRouter = CorridorRouter(model)

    /** Оптимальный путь между двумя точками. Окно поиска — прямоугольник связки с запасом. */
    fun optimal(from: Vec2, to: Vec2): RouteStats? {
        val margin = max(300.0, from.distanceTo(to) * 0.75)
        val path = pathfinder.find(from, to, Box.of(listOf(from, to)).expand(margin)) ?: return null
        return model.statsOf(path)
    }

    /**
     * Лучший путь, который проходит ломаную [route] по порядку, не отходя от неё дальше [radius].
     * Так оценивается выбор пути, а не точность рисования пальцем (см. [CorridorRouter]).
     */
    fun withinCorridor(route: List<Vec2>, radius: Double): RouteStats? =
        corridorRouter.route(route, radius)?.let { model.statsOf(it) }

    /** Время строго по ломаной: шаг по половине ячейки, скорость в каждой точке, уклон по высотам. */
    fun along(route: List<Vec2>): RouteStats {
        val s = model.spec
        val step = s.cellSize / 2
        var seconds = 0.0
        var climb = 0.0
        val violations = ArrayList<Vec2>()
        var inViolation = false
        val elevation = model.elevation
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val len = a.distanceTo(b)
            if (len == 0.0) continue
            val steps = max(1, (len / step).toInt())
            var prev = a
            var prevH = elevation?.heightAt(a) ?: 0.0
            for (k in 1..steps) {
                val p = a + (b - a) * (k.toDouble() / steps)
                val mid = (prev + p) * 0.5
                val d = prev.distanceTo(p)
                var v = model.terrain.speedAt(s.indexOf(mid)).toDouble()
                if (v <= 0.0) {
                    if (!inViolation) violations.add(mid)
                    inViolation = true
                    v = IMPASSABLE_PENALTY_SPEED
                } else {
                    inViolation = false
                }
                val h = elevation?.heightAt(p) ?: 0.0
                if (h > prevH) climb += h - prevH
                seconds += d / (model.baseSpeed * v) * model.gradeFactor(h - prevH, d)
                prev = p
                prevH = h
            }
        }
        return RouteStats(seconds, polylineLength(route), climb, route, violations)
    }

    fun analyze(from: Vec2, to: Vec2, drawn: List<Vec2>, corridor: Double): LegAnalysis? {
        val opt = optimal(from, to) ?: return null
        val route = buildList {
            add(from)
            addAll(drawn)
            add(to)
        }
        val raw = along(route)
        val intended = withinCorridor(route, corridor)
        // Игрок мог уйти за окно поиска оптимума и найти путь лучше: тогда эталоном считаем его.
        val best = if (intended != null && intended.seconds < opt.seconds) intended else opt
        return LegAnalysis(best, raw, intended)
    }

    companion object {
        /** Скорость, с которой считаем «проход сквозь» непреодолимое при нарисованной линии. */
        const val IMPASSABLE_PENALTY_SPEED = 0.1
    }
}

/** Длина, набор и время пути по сетке. */
internal fun MovementModel.statsOf(path: GridPath): RouteStats {
    var climb = 0.0
    val h = heights
    if (h != null) for (i in 1 until path.cells.size) {
        val dh = h[path.cells[i]] - h[path.cells[i - 1]]
        if (dh > 0) climb += dh
    }
    return RouteStats(path.seconds, polylineLength(path.points), climb, path.points)
}
