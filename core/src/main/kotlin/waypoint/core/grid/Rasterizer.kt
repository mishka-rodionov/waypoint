package waypoint.core.grid

import waypoint.core.geometry.Vec2
import waypoint.core.geometry.distanceToSegment
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Rasterizer {

    /**
     * Заливает многоугольник с дырками по правилу even-odd.
     * Ячейка считается внутренней, если внутри её центр.
     */
    fun fillPolygon(rings: List<List<Vec2>>, spec: GridSpec, visit: (Int) -> Unit) {
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (ring in rings) for (p in ring) {
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        if (minY > maxY) return
        val rowFrom = max(0, floor((minY - spec.originY) / spec.cellSize - 0.5).toInt())
        val rowTo = min(spec.height - 1, ceil((maxY - spec.originY) / spec.cellSize - 0.5).toInt())
        var crossings = DoubleArray(64)
        for (row in rowFrom..rowTo) {
            val y = spec.originY + (row + 0.5) * spec.cellSize
            var n = 0
            for (ring in rings) {
                val size = ring.size
                if (size < 3) continue
                for (i in 0 until size) {
                    val a = ring[i]
                    val b = ring[(i + 1) % size]
                    if ((a.y <= y) != (b.y <= y)) {
                        if (n == crossings.size) crossings = crossings.copyOf(n * 2)
                        crossings[n++] = a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
                    }
                }
            }
            if (n < 2) continue
            crossings.sort(0, n)
            var k = 0
            while (k + 1 < n) {
                val c0 = max(0, ceil((crossings[k] - spec.originX) / spec.cellSize - 0.5).toInt())
                val c1 = min(spec.width - 1, floor((crossings[k + 1] - spec.originX) / spec.cellSize - 0.5).toInt())
                for (col in c0..c1) visit(spec.index(col, row))
                k += 2
            }
        }
    }

    /**
     * Отмечает ячейки, центр которых ближе [radius] к ломаной.
     * Радиус не меньше половины диагонали ячейки, поэтому любая ячейка, через которую
     * проходит линия, попадает в коридор: такая линия непрерывна в 4-связном смысле,
     * и через неё нельзя «проскочить» по диагонали.
     */
    fun strokePolyline(points: List<Vec2>, radius: Double, spec: GridSpec, visit: (Int) -> Unit) {
        val r = max(radius, spec.cellSize * SQRT_HALF + 1e-9)
        if (points.size == 1) {
            strokeSegment(points[0], points[0], r, spec, visit)
            return
        }
        for (i in 1 until points.size) strokeSegment(points[i - 1], points[i], r, spec, visit)
    }

    private fun strokeSegment(a: Vec2, b: Vec2, r: Double, spec: GridSpec, visit: (Int) -> Unit) {
        val c0 = max(0, floor((min(a.x, b.x) - r - spec.originX) / spec.cellSize).toInt())
        val c1 = min(spec.width - 1, floor((max(a.x, b.x) + r - spec.originX) / spec.cellSize).toInt())
        val r0 = max(0, floor((min(a.y, b.y) - r - spec.originY) / spec.cellSize).toInt())
        val r1 = min(spec.height - 1, floor((max(a.y, b.y) + r - spec.originY) / spec.cellSize).toInt())
        for (row in r0..r1) for (col in c0..c1) {
            if (distanceToSegment(spec.cellCenter(col, row), a, b) <= r) visit(spec.index(col, row))
        }
    }

    private val SQRT_HALF = sqrt(0.5)
}
