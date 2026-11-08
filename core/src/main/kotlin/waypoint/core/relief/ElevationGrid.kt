package waypoint.core.relief

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.grid.GridSpec
import waypoint.core.grid.Rasterizer
import kotlin.math.floor

/** Горизонталь с известной высотой: ломаная в метрах на местности. */
class Contour(val height: Double, val points: List<Vec2>)

/** Сетка высот с билинейной интерполяцией между центрами ячеек. */
class ElevationGrid(val spec: GridSpec, val heights: FloatArray) {

    fun heightAt(p: Vec2): Double {
        val fx = (p.x - spec.originX) / spec.cellSize - 0.5
        val fy = (p.y - spec.originY) / spec.cellSize - 0.5
        val c0 = floor(fx).toInt().coerceIn(0, spec.width - 1)
        val r0 = floor(fy).toInt().coerceIn(0, spec.height - 1)
        val c1 = (c0 + 1).coerceAtMost(spec.width - 1)
        val r1 = (r0 + 1).coerceAtMost(spec.height - 1)
        val tx = (fx - c0).coerceIn(0.0, 1.0)
        val ty = (fy - r0).coerceIn(0.0, 1.0)
        val h00 = heights[spec.index(c0, r0)]
        val h10 = heights[spec.index(c1, r0)]
        val h01 = heights[spec.index(c0, r1)]
        val h11 = heights[spec.index(c1, r1)]
        val top = h00 + (h10 - h00) * tx
        val bottom = h01 + (h11 - h01) * tx
        return top + (bottom - top) * ty
    }

    /** Высоты в центрах ячеек другой сетки (например, растра проходимости). */
    fun resample(target: GridSpec): FloatArray = FloatArray(target.size) { i -> heightAt(target.cellCenter(i)).toFloat() }

    companion object {
        fun flat(bounds: Box) = ElevationGrid(GridSpec.covering(bounds, bounds.width.coerceAtLeast(bounds.height).coerceAtLeast(1.0)), FloatArray(1))

        /**
         * Восстанавливает поверхность по горизонталям: ячейки на горизонталях фиксированы,
         * остальные — решение уравнения Лапласа (гладкая поверхность между линиями), метод SOR.
         * Начальное приближение — [initial] (решение на более грубой сетке) или высота ближайшей горизонтали.
         * Ячейки дальше [fallbackDistance] метров от любой горизонтали берут высоту из [fallback]
         * (например, SRTM там, где на карте горизонталей нет).
         */
        fun fromContours(
            contours: List<Contour>,
            bounds: Box,
            cellSize: Double,
            iterations: Int = 400,
            initial: ElevationGrid? = null,
            fallback: ((Vec2) -> Double)? = null,
            fallbackDistance: Double = 150.0,
        ): ElevationGrid {
            val spec = GridSpec.covering(bounds, cellSize)
            val n = spec.size
            val h = FloatArray(n)
            val known = BooleanArray(n)
            val sum = DoubleArray(n)
            val count = IntArray(n)
            for (c in contours) {
                Rasterizer.strokePolyline(c.points, 0.0, spec) { i ->
                    sum[i] += c.height
                    count[i]++
                }
            }
            val queue = IntArray(n)
            var head = 0
            var tail = 0
            for (i in 0 until n) if (count[i] > 0) {
                h[i] = (sum[i] / count[i]).toFloat()
                known[i] = true
                queue[tail++] = i
            }
            if (tail == 0) return ElevationGrid(spec, h)

            // Волновой обход от горизонталей: расстояние в ячейках и высота ближайшей линии.
            val dist = IntArray(n) { -1 }
            for (k in 0 until tail) dist[queue[k]] = 0
            while (head < tail) {
                val i = queue[head++]
                val c = spec.col(i)
                val r = spec.row(i)
                for (d in 0 until 4) {
                    val nc = c + DC[d]
                    val nr = r + DR[d]
                    if (!spec.contains(nc, nr)) continue
                    val j = spec.index(nc, nr)
                    if (dist[j] >= 0) continue
                    dist[j] = dist[i] + 1
                    h[j] = h[i]
                    queue[tail++] = j
                }
            }
            if (initial != null) {
                for (i in 0 until n) if (!known[i]) h[i] = initial.heightAt(spec.cellCenter(i)).toFloat()
            }
            if (fallback != null) {
                val far = (fallbackDistance / cellSize).toInt()
                for (i in 0 until n) if (dist[i] > far) {
                    h[i] = fallback(spec.cellCenter(i)).toFloat()
                    known[i] = true
                }
            }

            val omega = 1.9f
            val w = spec.width
            val hh = spec.height
            repeat(iterations) {
                for (r in 0 until hh) for (c in 0 until w) {
                    val i = r * w + c
                    if (known[i]) continue
                    var s = 0f
                    var k = 0
                    if (c > 0) { s += h[i - 1]; k++ }
                    if (c < w - 1) { s += h[i + 1]; k++ }
                    if (r > 0) { s += h[i - w]; k++ }
                    if (r < hh - 1) { s += h[i + w]; k++ }
                    h[i] += omega * (s / k - h[i])
                }
            }
            return ElevationGrid(spec, h)
        }

        /** Решение от грубой сетки к точной: SOR на мелкой сетке сходится медленно без хорошего начала. */
        fun fromContoursMultiscale(
            contours: List<Contour>,
            bounds: Box,
            cellSize: Double,
            fallback: ((Vec2) -> Double)? = null,
        ): ElevationGrid {
            var grid: ElevationGrid? = null
            for (scale in intArrayOf(8, 4, 2, 1)) {
                grid = fromContours(contours, bounds, cellSize * scale, iterations = if (scale == 8) 600 else 150, initial = grid, fallback = fallback)
            }
            return grid!!
        }

        private val DC = intArrayOf(1, -1, 0, 0)
        private val DR = intArrayOf(0, 0, 1, -1)
    }
}
