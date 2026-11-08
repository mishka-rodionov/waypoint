package waypoint.core.route

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.grid.GridSpec
import waypoint.core.grid.Rasterizer
import kotlin.math.max
import kotlin.math.min

/**
 * Лучший путь, который проходит нарисованную линию по порядку.
 *
 * Вдоль линии через каждые [radius] метров стоят «ворота» — круги радиусом [radius].
 * Путь обязан пройти все ворота по очереди, а между соседними воротами — оставаться
 * в коридоре вокруг своего участка линии. Так мелкое дрожание руки сглаживается,
 * а петли и зигзаги остаются: каракули не накрывают оптимальный путь целиком.
 */
class CorridorRouter(private val model: MovementModel) {

    fun route(line: List<Vec2>, radius: Double): GridPath? {
        if (line.size < 2) return null
        val s = model.spec
        val gates = gatePoints(line, radius)
        val start = nearestPassable(line.first()) ?: return null
        val goal = nearestPassable(line.last()) ?: return null

        val stages = ArrayList<Stage>()
        // Стоимость входа в текущий этап: ячейка → время; на первом этапе только старт.
        var seeds: Map<Int, Float> = mapOf(start to 0f)
        for (k in 1 until gates.size) {
            val piece = gates[k - 1].second..gates[k].second
            val sub = buildList {
                add(gates[k - 1].first)
                for (i in piece.first + 1..piece.last) add(line[i])
                add(gates[k].first)
            }
            val window = Box.of(sub).expand(radius + 2 * s.cellSize)
            val stage = Stage(s, window)
            Rasterizer.strokePolyline(sub, radius, stage.spec) { stage.mask[it] = true }
            stage.run(seeds)
            stages.add(stage)
            seeds = if (k == gates.size - 1) {
                val local = stage.local(goal)
                if (local < 0 || stage.dist[local].isInfinite()) return null
                mapOf(goal to stage.dist[local])
            } else {
                val next = HashMap<Int, Float>()
                val gate = gates[k].first
                Rasterizer.strokePolyline(listOf(gate), radius, stage.spec) { local ->
                    val d = stage.dist[local]
                    if (d.isFinite()) next[stage.global(local)] = d
                }
                if (next.isEmpty()) return null
                next
            }
        }

        // Обратный проход: внутри этапа по родителям, на «семени» переходим в предыдущий этап.
        val cells = ArrayList<Int>()
        var cell = goal
        for (k in stages.indices.reversed()) {
            val st = stages[k]
            var local = st.local(cell)
            while (true) {
                cells.add(st.global(local))
                val p = st.parent[local]
                if (p < 0) break
                local = p
            }
            cell = st.global(local)
            // Ячейка-семя уже добавлена: в предыдущем этапе начнём с неё же, убираем дубль.
            if (k > 0) cells.removeAt(cells.size - 1)
        }
        cells.reverse()
        val arr = cells.toIntArray()
        return GridPath(arr, arr.map { s.cellCenter(it) }, (seeds.values.firstOrNull() ?: 0f).toDouble())
    }

    /** Точки ворот через каждые [step] метров по линии и индекс отрезка линии, на котором они лежат. */
    private fun gatePoints(line: List<Vec2>, step: Double): List<Pair<Vec2, Int>> {
        val out = ArrayList<Pair<Vec2, Int>>()
        out.add(line.first() to 0)
        var carried = 0.0
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val len = a.distanceTo(b)
            var t = step - carried
            while (t < len) {
                out.add(a + (b - a) * (t / len) to i - 1)
                t += step
            }
            carried = len - (t - step)
        }
        if (out.last().first != line.last()) out.add(line.last() to line.size - 2)
        return out
    }

    private fun nearestPassable(p: Vec2): Int? {
        val s = model.spec
        val c = s.colOf(p.x).coerceIn(0, s.width - 1)
        val r = s.rowOf(p.y).coerceIn(0, s.height - 1)
        for (radius in 0..10) {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            for (dr in -radius..radius) for (dc in -radius..radius) {
                if (max(kotlin.math.abs(dr), kotlin.math.abs(dc)) != radius) continue
                if (!s.contains(c + dc, r + dr)) continue
                val i = s.index(c + dc, r + dr)
                if (model.terrain.speed[i] <= 0f) continue
                val d = s.cellCenter(i).distanceTo(p)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) return best
        }
        return null
    }

    /** Один участок между воротами: Дейкстра в маленьком окне глобальной сетки. */
    private inner class Stage(global: GridSpec, window: Box) {
        val c0 = max(0, global.colOf(window.minX))
        val r0 = max(0, global.rowOf(window.minY))
        val c1 = min(global.width - 1, global.colOf(window.maxX))
        val r1 = min(global.height - 1, global.rowOf(window.maxY))
        val spec = GridSpec(
            global.originX + c0 * global.cellSize, global.originY + r0 * global.cellSize,
            global.cellSize, max(1, c1 - c0 + 1), max(1, r1 - r0 + 1),
        )
        val mask = BooleanArray(spec.size)
        val dist = FloatArray(spec.size) { Float.POSITIVE_INFINITY }
        val parent = IntArray(spec.size) { -1 }

        fun local(globalCell: Int): Int {
            val g = model.spec
            val c = g.col(globalCell) - c0
            val r = g.row(globalCell) - r0
            return if (c in 0 until spec.width && r in 0 until spec.height) r * spec.width + c else -1
        }

        fun global(local: Int): Int = model.spec.index(c0 + local % spec.width, r0 + local / spec.width)

        fun run(seeds: Map<Int, Float>) {
            val heap = MinHeap()
            for ((cell, d) in seeds) {
                val l = local(cell)
                if (l < 0) continue
                mask[l] = true
                if (d < dist[l]) {
                    dist[l] = d
                    heap.push(l, d)
                }
            }
            val closed = BooleanArray(spec.size)
            while (heap.size > 0) {
                val cur = heap.pop()
                if (closed[cur]) continue
                closed[cur] = true
                val gc = global(cur)
                val cc = cur % spec.width
                val cr = cur / spec.width
                for (m in Move.ALL) {
                    val nc = cc + m.dc
                    val nr = cr + m.dr
                    if (nc !in 0 until spec.width || nr !in 0 until spec.height) continue
                    val nl = nr * spec.width + nc
                    if (closed[nl] || !mask[nl]) continue
                    val t = model.moveTime(gc, m)
                    if (t.isNaN()) continue
                    val cand = dist[cur] + t.toFloat()
                    if (cand < dist[nl]) {
                        dist[nl] = cand
                        parent[nl] = cur
                        heap.push(nl, cand)
                    }
                }
            }
        }
    }
}
