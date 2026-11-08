package waypoint.core.geometry

import kotlin.math.hypot
import kotlin.math.sqrt

/** Точка или вектор на местности, в метрах. Ось x направлена вправо, ось y вниз, как на карте. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)

    fun length(): Double = hypot(x, y)
    fun distanceTo(o: Vec2): Double = hypot(x - o.x, y - o.y)
    fun dot(o: Vec2): Double = x * o.x + y * o.y
}

/** Расстояние от точки [p] до отрезка [a]–[b]. */
fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val len2 = abx * abx + aby * aby
    if (len2 == 0.0) return p.distanceTo(a)
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / len2).coerceIn(0.0, 1.0)
    val dx = p.x - (a.x + abx * t)
    val dy = p.y - (a.y + aby * t)
    return sqrt(dx * dx + dy * dy)
}

fun polylineLength(points: List<Vec2>): Double {
    var sum = 0.0
    for (i in 1 until points.size) sum += points[i - 1].distanceTo(points[i])
    return sum
}

/** Прямоугольник в метрах. */
data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY

    fun expand(margin: Double) = Box(minX - margin, minY - margin, maxX + margin, maxY + margin)

    companion object {
        fun of(points: Iterable<Vec2>): Box {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return Box(minX, minY, maxX, maxY)
        }
    }
}
