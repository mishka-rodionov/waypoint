package waypoint.core.gen

import waypoint.core.geometry.Vec2
import waypoint.core.route.MinHeap
import kotlin.math.sqrt

/**
 * Сток воды по рельефу (узлы [NodeField]): заполнение бессточных впадин (priority-flood),
 * направление стока D8 по заполненной поверхности, площадь водосбора.
 * Впадины — будущие озёра и болота, линии большого водосбора — ручьи.
 */
class Hydrology(val heights: NodeField) {
    private val s = heights.spec
    private val w = s.width
    private val h = s.height
    private val n = s.size

    /** Поверхность с заполненными впадинами: вода из любой точки стекает к краю. */
    val filled: FloatArray = FloatArray(n)

    /** Узел, куда стекает вода (−1 — за край). */
    val receiver: IntArray = IntArray(n) { -1 }

    /** Площадь водосбора узла, м². */
    val area: FloatArray = FloatArray(n)

    init {
        fill()
        route()
    }

    /** Глубина заполнения впадины в узле, м (0 — не впадина). */
    fun depth(i: Int): Float = filled[i] - heights.values[i]

    private fun fill() {
        val closed = BooleanArray(n)
        val heap = MinHeap()
        for (i in 0 until n) {
            val c = i % w
            val r = i / w
            if (c == 0 || r == 0 || c == w - 1 || r == h - 1) {
                filled[i] = heights.values[i]
                closed[i] = true
                heap.push(i, filled[i])
            }
        }
        while (heap.size > 0) {
            val cur = heap.pop()
            val c = cur % w
            val r = cur / w
            for (d in 0 until 8) {
                val nc = c + DC[d]
                val nr = r + DR[d]
                if (nc < 0 || nr < 0 || nc >= w || nr >= h) continue
                val j = nr * w + nc
                if (closed[j]) continue
                closed[j] = true
                // Маленький подъём над соседом: в заполненной впадине вода всё равно течёт к выходу.
                filled[j] = maxOf(heights.values[j], filled[cur] + 1e-3f)
                heap.push(j, filled[j])
            }
        }
    }

    private fun route() {
        for (i in 0 until n) {
            val c = i % w
            val r = i / w
            var best = -1
            var bestSlope = 0.0
            for (d in 0 until 8) {
                val nc = c + DC[d]
                val nr = r + DR[d]
                if (nc < 0 || nr < 0 || nc >= w || nr >= h) continue
                val j = nr * w + nc
                val slope = (filled[i] - filled[j]) / DIST[d]
                if (slope > bestSlope) { bestSlope = slope; best = j }
            }
            receiver[i] = best
        }
        // Сверху вниз по заполненной поверхности: водосбор переходит к получателю.
        val order = (0 until n).sortedByDescending { filled[it] }
        val cellArea = (s.cellSize * s.cellSize).toFloat()
        for (i in 0 until n) area[i] = cellArea
        for (i in order) {
            val rcv = receiver[i]
            if (rcv >= 0) area[rcv] += area[i]
        }
    }

    /**
     * Ручьи: цепочки стока по узлам с водосбором не меньше [minArea] м².
     * Узлы, где [skip] (например, в озере), рвут линию. Каждая линия — пара (точки, большой ли ручей),
     * линия делится там, где водосбор превышает [bigArea].
     */
    fun streams(minArea: Float, bigArea: Float, skip: (Int) -> Boolean): List<Pair<List<Vec2>, Boolean>> {
        val isStream = BooleanArray(n) { area[it] >= minArea && !skip(it) }
        val hasDonor = BooleanArray(n)
        for (i in 0 until n) if (isStream[i]) receiver[i].let { if (it >= 0) hasDonor[it] = true }
        val visited = BooleanArray(n)
        val out = ArrayList<Pair<List<Vec2>, Boolean>>()
        for (src in 0 until n) {
            if (!isStream[src] || hasDonor[src]) continue
            var cur = src
            var pts = ArrayList<Vec2>()
            var big = area[cur] >= bigArea
            while (true) {
                pts.add(node(cur))
                if (visited[cur]) break
                visited[cur] = true
                val next = receiver[cur]
                if (next < 0 || !isStream[next]) break
                val nextBig = area[next] >= bigArea
                if (nextBig != big) {
                    pts.add(node(next))
                    out.add(pts to big)
                    pts = ArrayList()
                    big = nextBig
                }
                cur = next
            }
            if (pts.size >= 2) out.add(pts to big)
        }
        return out
    }

    /** Наклон в узле (тангенс), по соседям. */
    fun slope(i: Int): Double {
        val c = i % w
        val r = i / w
        val l = heights.values[r * w + maxOf(0, c - 1)]
        val rr = heights.values[r * w + minOf(w - 1, c + 1)]
        val u = heights.values[maxOf(0, r - 1) * w + c]
        val d = heights.values[minOf(h - 1, r + 1) * w + c]
        val dx = (rr - l) / (2 * s.cellSize)
        val dy = (d - u) / (2 * s.cellSize)
        return sqrt(dx * dx + dy * dy)
    }

    private fun node(i: Int) = heights.node(i % w, i / w)

    private companion object {
        val DC = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val DR = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        val DIST = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.4142, 1.4142, 1.4142, 1.4142)
    }
}
