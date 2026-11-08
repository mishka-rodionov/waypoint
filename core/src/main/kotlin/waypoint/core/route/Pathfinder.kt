package waypoint.core.route

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import kotlin.math.max
import kotlin.math.min

/** Найденный путь: центры ячеек от старта до финиша и время в секундах. */
class GridPath(val cells: IntArray, val points: List<Vec2>, val seconds: Double)

/**
 * A* по растру с 16 направлениями. Поиск ограничен окном [window] (в метрах),
 * чтобы не выделять память под всю карту; [allowed] дополнительно сужает область (коридор),
 * [penalty] (не меньше 1) удорожает ход в ячейку — так ищутся пути, непохожие на уже найденные.
 */
class Pathfinder(private val model: MovementModel) {

    fun find(
        start: Vec2,
        goal: Vec2,
        window: Box,
        allowed: ((Int) -> Boolean)? = null,
        penalty: ((Int) -> Float)? = null,
    ): GridPath? {
        val s = model.spec
        val c0 = max(0, s.colOf(window.minX))
        val r0 = max(0, s.rowOf(window.minY))
        val c1 = min(s.width - 1, s.colOf(window.maxX))
        val r1 = min(s.height - 1, s.rowOf(window.maxY))
        if (c0 > c1 || r0 > r1) return null
        val ww = c1 - c0 + 1
        val wh = r1 - r0 + 1

        fun passable(global: Int) = model.terrain.speed[global] > 0f && (allowed == null || allowed(global))
        val startCell = nearestPassable(start, ::passable) ?: return null
        val goalCell = nearestPassable(goal, ::passable) ?: return null

        fun local(global: Int): Int {
            val c = s.col(global) - c0
            val r = s.row(global) - r0
            return if (c in 0 until ww && r in 0 until wh) r * ww + c else -1
        }
        fun global(local: Int): Int = s.index(c0 + local % ww, r0 + local / ww)

        val ls = local(startCell)
        val lg = local(goalCell)
        if (ls < 0 || lg < 0) return null

        val g = FloatArray(ww * wh) { Float.POSITIVE_INFINITY }
        val parent = IntArray(ww * wh) { -1 }
        val closed = BooleanArray(ww * wh)
        val goalPos = s.cellCenter(goalCell)
        val k = model.minSecondsPerMeter
        fun h(globalCell: Int): Float = (s.cellCenter(globalCell).distanceTo(goalPos) * k).toFloat()

        val heap = MinHeap()
        g[ls] = 0f
        heap.push(ls, h(startCell))
        val moves = Move.ALL
        while (heap.size > 0) {
            val cur = heap.pop()
            if (closed[cur]) continue
            if (cur == lg) break
            closed[cur] = true
            val gc = global(cur)
            val cc = s.col(gc)
            val cr = s.row(gc)
            for (m in moves) {
                val nc = cc + m.dc - c0
                val nr = cr + m.dr - r0
                if (nc !in 0 until ww || nr !in 0 until wh) continue
                val nl = nr * ww + nc
                if (closed[nl]) continue
                val ng = global(nl)
                if (allowed != null && !allowed(ng)) continue
                val t = model.moveTime(gc, m)
                if (t.isNaN()) continue
                val cand = g[cur] + t.toFloat() * (penalty?.invoke(ng) ?: 1f)
                if (cand < g[nl]) {
                    g[nl] = cand
                    parent[nl] = cur
                    heap.push(nl, cand + h(ng))
                }
            }
        }
        if (g[lg].isInfinite()) return null

        val path = ArrayList<Int>()
        var at = lg
        while (at != -1) {
            path.add(global(at))
            at = parent[at]
        }
        path.reverse()
        val cells = path.toIntArray()
        // Со штрафом g — не настоящее время: пересчитываем по ходам.
        val seconds = if (penalty == null) g[lg].toDouble() else model.timeOf(cells)
        return GridPath(cells, cells.map { s.cellCenter(it) }, seconds)
    }

    /** Ближайшая проходимая ячейка к точке (КП может стоять у самого объекта). */
    private fun nearestPassable(p: Vec2, passable: (Int) -> Boolean): Int? {
        val s = model.spec
        val c = s.colOf(p.x).coerceIn(0, s.width - 1)
        val r = s.rowOf(p.y).coerceIn(0, s.height - 1)
        for (radius in 0..10) {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            for (dr in -radius..radius) for (dc in -radius..radius) {
                if (max(kotlin.math.abs(dr), kotlin.math.abs(dc)) != radius) continue
                val cc = c + dc
                val rr = r + dr
                if (!s.contains(cc, rr)) continue
                val i = s.index(cc, rr)
                if (!passable(i)) continue
                val d = s.cellCenter(i).distanceTo(p)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) return best
        }
        return null
    }
}

/** Двоичная куча (узел, приоритет) с ленивым удалением устаревших записей. */
internal class MinHeap {
    private var nodes = IntArray(1024)
    private var prio = FloatArray(1024)
    var size = 0
        private set

    fun push(node: Int, priority: Float) {
        if (size == nodes.size) {
            nodes = nodes.copyOf(size * 2)
            prio = prio.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (prio[p] <= priority) break
            nodes[i] = nodes[p]
            prio[i] = prio[p]
            i = p
        }
        nodes[i] = node
        prio[i] = priority
    }

    fun pop(): Int {
        val top = nodes[0]
        val lastNode = nodes[--size]
        val lastPrio = prio[size]
        var i = 0
        while (true) {
            val l = 2 * i + 1
            if (l >= size) break
            val r = l + 1
            val c = if (r < size && prio[r] < prio[l]) r else l
            if (prio[c] >= lastPrio) break
            nodes[i] = nodes[c]
            prio[i] = prio[c]
            i = c
        }
        nodes[i] = lastNode
        prio[i] = lastPrio
        return top
    }
}
