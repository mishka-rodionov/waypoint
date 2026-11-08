package waypoint.core.gen

import waypoint.core.geometry.Vec2
import waypoint.core.grid.GridSpec

/**
 * Поле значений в узлах сетки: узел (c, r) стоит в точке ([GridSpec.originX] + c·cell, [GridSpec.originY] + r·cell).
 * Так удобнее для изолиний, чем центры ячеек.
 */
class NodeField(val spec: GridSpec, val values: FloatArray) {
    init {
        require(values.size == spec.size)
    }

    operator fun get(c: Int, r: Int): Float = values[r * spec.width + c]

    fun node(c: Int, r: Int) = Vec2(spec.originX + c * spec.cellSize, spec.originY + r * spec.cellSize)

    /** Значение в точке, билинейно между узлами (за краем — ближайший край). */
    fun at(p: Vec2): Double {
        val fx = ((p.x - spec.originX) / spec.cellSize).coerceIn(0.0, spec.width - 1.0)
        val fy = ((p.y - spec.originY) / spec.cellSize).coerceIn(0.0, spec.height - 1.0)
        val c0 = fx.toInt().coerceAtMost(spec.width - 2).coerceAtLeast(0)
        val r0 = fy.toInt().coerceAtMost(spec.height - 2).coerceAtLeast(0)
        val tx = fx - c0
        val ty = fy - r0
        val c1 = (c0 + 1).coerceAtMost(spec.width - 1)
        val r1 = (r0 + 1).coerceAtMost(spec.height - 1)
        val top = get(c0, r0) + (get(c1, r0) - get(c0, r0)) * tx
        val bottom = get(c0, r1) + (get(c1, r1) - get(c0, r1)) * tx
        return top + (bottom - top) * ty
    }
}

/**
 * Марширующие квадраты: изолинии поля и контуры областей «значение ≥ уровня».
 * Точки на рёбрах сетки — линейная интерполяция; седловые клетки разрешаются по среднему в центре.
 */
object MarchingSquares {

    /** Изолинии уровня [level]: замкнутые кольца (первая точка повторена в конце) и открытые линии от края до края. */
    fun isolines(field: NodeField, level: Float): List<List<Vec2>> = trace(field, level, padded = false)

    /**
     * Контуры области «значение ≥ [level]» — замкнутые кольца для заливки по правилу even-odd
     * (дырки — отдельные кольца). Область обрезается краем поля.
     */
    fun regions(field: NodeField, level: Float): List<List<Vec2>> = trace(field, level, padded = true)

    private fun trace(field: NodeField, level: Float, padded: Boolean): List<List<Vec2>> {
        val s = field.spec
        // С подкладкой вокруг поля — заведомо «снаружи»: все контуры областей замыкаются.
        val pad = if (padded) 1 else 0
        val w = s.width + 2 * pad
        val h = s.height + 2 * pad
        val outside = level - 1e6f
        fun v(c: Int, r: Int): Float {
            val cc = c - pad
            val rr = r - pad
            return if (cc < 0 || rr < 0 || cc >= s.width || rr >= s.height) outside else field[cc, rr]
        }
        fun pos(c: Int, r: Int) = Vec2(s.originX + (c - pad) * s.cellSize, s.originY + (r - pad) * s.cellSize)

        // Ребро: горизонтальное (c,r)–(c+1,r) = 2·(r·w+c), вертикальное (c,r)–(c,r+1) = 2·(r·w+c)+1.
        fun hEdge(c: Int, r: Int) = 2 * (r * w + c)
        fun vEdge(c: Int, r: Int) = 2 * (r * w + c) + 1
        val segA = IntArrayList()
        val segB = IntArrayList()
        fun seg(a: Int, b: Int) { segA.add(a); segB.add(b) }

        for (r in 0 until h - 1) for (c in 0 until w - 1) {
            val tl = v(c, r)
            val tr = v(c + 1, r)
            val br = v(c + 1, r + 1)
            val bl = v(c, r + 1)
            var idx = 0
            if (tl >= level) idx = idx or 8
            if (tr >= level) idx = idx or 4
            if (br >= level) idx = idx or 2
            if (bl >= level) idx = idx or 1
            if (idx == 0 || idx == 15) continue
            val top = hEdge(c, r)
            val bottom = hEdge(c, r + 1)
            val left = vEdge(c, r)
            val right = vEdge(c + 1, r)
            when (idx) {
                1, 14 -> seg(left, bottom)
                2, 13 -> seg(bottom, right)
                3, 12 -> seg(left, right)
                4, 11 -> seg(top, right)
                6, 9 -> seg(top, bottom)
                7, 8 -> seg(left, top)
                5 -> if ((tl + tr + br + bl) / 4 >= level) { seg(left, top); seg(bottom, right) } else { seg(top, right); seg(left, bottom) }
                10 -> if ((tl + tr + br + bl) / 4 >= level) { seg(top, right); seg(left, bottom) } else { seg(left, top); seg(bottom, right) }
            }
        }

        fun point(edge: Int): Vec2 {
            val node = edge / 2
            val c = node % w
            val r = node / w
            val c2 = if (edge % 2 == 0) c + 1 else c
            val r2 = if (edge % 2 == 0) r else r + 1
            val a = v(c, r)
            val b = v(c2, r2)
            val t = if (b == a) 0.5 else ((level - a) / (b - a)).toDouble().coerceIn(0.0, 1.0)
            val pa = pos(c, r)
            val pb = pos(c2, r2)
            return Vec2(pa.x + (pb.x - pa.x) * t, pa.y + (pb.y - pa.y) * t)
        }

        // Склейка отрезков в цепочки по общим рёбрам (у ребра не больше двух отрезков).
        val n = segA.size
        val byEdge = HashMap<Int, IntArray>(n * 2)
        fun link(edge: Int, s: Int) {
            val cur = byEdge[edge]
            if (cur == null) byEdge[edge] = intArrayOf(s, -1) else cur[1] = s
        }
        for (i in 0 until n) { link(segA[i], i); link(segB[i], i) }
        val used = BooleanArray(n)
        val out = ArrayList<List<Vec2>>()

        fun walk(startSeg: Int, startEdge: Int): List<Vec2> {
            val pts = ArrayList<Vec2>()
            var edge = startEdge
            var seg = startSeg
            pts.add(point(edge))
            while (seg >= 0 && !used[seg]) {
                used[seg] = true
                edge = if (segA[seg] == edge) segB[seg] else segA[seg]
                pts.add(point(edge))
                val pair = byEdge[edge]!!
                seg = if (pair[0] == seg) pair[1] else pair[0]
            }
            return pts
        }
        // Сначала открытые линии: начинаются на ребре с одним отрезком (край поля).
        for ((edge, pair) in byEdge) {
            if (pair[1] == -1 && !used[pair[0]]) out.add(walk(pair[0], edge))
        }
        for (i in 0 until n) if (!used[i]) out.add(walk(i, segA[i]))
        return out.filter { it.size >= 2 }
    }
}

/** Растущий массив Int без упаковки. */
internal class IntArrayList(capacity: Int = 1024) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
}
