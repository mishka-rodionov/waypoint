package waypoint.core.relief

import waypoint.core.geometry.Vec2
import waypoint.core.route.MinHeap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Горизонталь карты без высоты. [index] — утолщённая (каждая пятая). */
class MapContour(val parts: List<List<Vec2>>, val index: Boolean)

/**
 * Восстанавливает высоты горизонталей карты: у горизонталей OMAP высот нет, есть только форма.
 *
 * 1. **Сторона подъёма.** Вдоль одной горизонтали подъём всегда с одной и той же стороны.
 *    Для каждой горизонтали (каждой её части) сторона определяется голосованием грубой модели
 *    [reference] (SRTM) по всей длине: её уклон на базе 2·gradientBase м виден на длинных участках
 *    вдоль общего склона, и ответ переносится на изгибы, где SRTM не видит мелких форм (овраг, отрог).
 * 2. **Соседи.** Из точек горизонтали по нормали пускаются лучи до первой другой горизонтали.
 *    Зная, с какой стороны мы ушли от одной и с какой пришли к другой, получаем разницу уровней:
 *    +1/−1 сечение или 0 (между ними гребень или ложбина).
 * 3. **Согласование.** Короткие горизонтали, где SRTM неуверен, подстраивают сторону под соседей:
 *    соседи по лучу обычно отличаются на сечение, а не стоят на одном уровне.
 * 4. **Уровни** — целые числа сечений: распространяются по самым надёжным связям соседей
 *    (максимальное остовное дерево), общий уровень каждой связной группы — по SRTM,
 *    затем локальная донастройка: каждая горизонталь ±1 сечение, если так лучше соседям
 *    и общей кратности утолщённых горизонталей (каждая пятая).
 */
object ContourHeights {

    class Result(
        /** Высота каждой горизонтали в метрах; NaN, если у горизонтали нет точек. */
        val heights: DoubleArray,
        /** Средняя высота [reference] вдоль горизонтали: для сдвига грубой модели к уровням карты. */
        val referenceMeans: DoubleArray,
        /** Доля (по весу) пар соседей, которым соответствует итоговая разница уровней. */
        val agreement: Double,
        /** Для отладки: соседи каждой горизонтали — (сосед, шаг k_сосед − k_i, вес). */
        val neighbors: List<List<Triple<Int, Double, Double>>> = emptyList(),
    )

    private const val SAMPLE_STEP = 8.0
    private const val MAX_RAY = 80.0
    private const val CELL = 20.0

    /** Цена «соседи на одном уровне» в согласовании сторон, в голосах SRTM. */
    private const val SAME_LEVEL_PENALTY = 1.0

    /** Привязка к SRTM: вес на точку выборки и мёртвая зона в сечениях. */
    private const val ANCHOR = 0.05
    private const val DEAD_ZONE = 1.5

    /** Штраф утолщённой горизонтали не на «своём» остатке, в долях суммарного веса её связей. */
    private const val INDEX_PENALTY = 0.5

    /** Луч от линии [from] (ушёл в сторону [leave] её нормали) до линии [to] (пришёл со стороны [arrive]). */
    private class Obs(val from: Int, val leave: Int, val to: Int, val arrive: Int)

    fun assign(
        contours: List<MapContour>,
        reference: (Vec2) -> Double,
        interval: Double,
        gradientBase: Double = 40.0,
    ): Result {
        val n = contours.size
        // Части горизонталей — отдельные линии со своей стороной подъёма; высота общая для объекта.
        val owner = ArrayList<Int>()
        val lines = ArrayList<List<Vec2>>()
        for ((i, c) in contours.withIndex()) for (part in c.parts) if (part.size >= 2) {
            owner.add(i)
            lines.add(part)
        }
        val u = lines.size
        val index = SegmentIndex(lines, CELL)

        val refSum = DoubleArray(n)
        val refCount = IntArray(n)
        val sideVote = DoubleArray(u) // > 0: подъём со стороны +нормали
        val obs = ArrayList<Obs>()

        for (line in 0 until u) {
            val i = owner[line]
            forEachSample(lines[line], SAMPLE_STEP) { p, normal ->
                refSum[i] += reference(p)
                refCount[i]++
                val b = gradientBase
                val gx = (reference(Vec2(p.x + b, p.y)) - reference(Vec2(p.x - b, p.y))) / (2 * b)
                val gy = (reference(Vec2(p.x, p.y + b)) - reference(Vec2(p.x, p.y - b))) / (2 * b)
                val g = sqrt(gx * gx + gy * gy)
                if (g > 1e-4) {
                    val along = (gx * normal.x + gy * normal.y) / g
                    val confidence = min(1.0, g * 2 * b / interval)
                    // Голос сильнее, когда нормаль смотрит вдоль склона (линия поперёк склона).
                    sideVote[line] += along * abs(along) * confidence
                }
                for (leave in intArrayOf(1, -1)) {
                    val hit = index.firstHit(p, normal * leave.toDouble(), MAX_RAY, exclude = line) ?: continue
                    if (owner[hit.line] == i) continue
                    // Пришли к линии со стороны, противоположной направлению луча.
                    val arrive = if (-(normal.x * hit.normal.x + normal.y * hit.normal.y) * leave > 0) 1 else -1
                    obs.add(Obs(line, leave, hit.line, arrive))
                }
            }
        }

        // Согласование сторон: SRTM против «соседи обычно на разных уровнях».
        val side = IntArray(u) { if (sideVote[it] >= 0) 1 else -1 }
        val byLine = Array(u) { ArrayList<Obs>() }
        for (o in obs) {
            byLine[o.from].add(o)
            if (o.to != o.from) byLine[o.to].add(o)
        }
        for (pass in 0 until 50) {
            var changed = false
            for (line in 0 until u) {
                fun cost(s: Int): Double {
                    var c = if (s * sideVote[line] < 0) abs(sideVote[line]) else 0.0
                    for (o in byLine[line]) {
                        val sf = if (o.from == line) s else side[o.from]
                        val st = if (o.to == line) s else side[o.to]
                        if (relation(sf, o.leave, st, o.arrive) == 0) c += SAME_LEVEL_PENALTY
                    }
                    return c
                }
                val flipped = -side[line]
                if (cost(flipped) < cost(side[line]) - 1e-9) {
                    side[line] = flipped
                    changed = true
                }
            }
            if (!changed) break
        }

        // Разницы уровней между объектами по всем лучам: голоса за −1, 0, +1.
        val pairVotes = HashMap<Long, IntArray>()
        for (o in obs) {
            val a = owner[o.from]
            val b = owner[o.to]
            if (a == b) continue
            val r = relation(side[o.from], o.leave, side[o.to], o.arrive)
            val (lo, hi, st) = if (a < b) Triple(a, b, r) else Triple(b, a, -r)
            pairVotes.getOrPut((lo.toLong() shl 32) or hi.toLong()) { IntArray(3) }[st + 1]++
        }
        val adj = Array(n) { ArrayList<Edge>() }
        for ((key, votes) in pairVotes) {
            val lo = (key shr 32).toInt()
            val hi = (key and 0xFFFFFFFFL).toInt()
            val best = (0..2).maxBy { votes[it] }
            // Вес — перевес большинства: спорные пары почти не влияют.
            val weight = (2 * votes[best] - votes.sum()).toDouble()
            if (weight <= 0.0) continue
            val steps = (best - 1).toDouble()
            adj[lo].add(Edge(hi, steps, weight))
            adj[hi].add(Edge(lo, -steps, weight))
        }

        val refMean = DoubleArray(n) { if (refCount[it] > 0) refSum[it] / refCount[it] else Double.NaN }
        val k = IntArray(n)
        val assigned = BooleanArray(n)

        // Распространение по максимальному остовному дереву (Прим): сначала самые надёжные связи.
        val order = (0 until n).filter { refCount[it] > 0 }.sortedByDescending { i -> adj[i].sumOf { it.weight } }
        val heap = MinHeap() // ребро (от, номер) закодировано в edgeRefs; приоритет — минус вес
        val edgeRefs = ArrayList<Long>()
        fun push(from: Int, ei: Int) {
            edgeRefs.add((from.toLong() shl 32) or ei.toLong())
            heap.push(edgeRefs.size - 1, -adj[from][ei].weight.toFloat())
        }
        val component = IntArray(n) { -1 }
        var components = 0
        for (root in order) {
            if (assigned[root]) continue
            assigned[root] = true
            component[root] = components
            k[root] = 0
            for (ei in adj[root].indices) push(root, ei)
            while (heap.size > 0) {
                val ref = edgeRefs[heap.pop()]
                val from = (ref shr 32).toInt()
                val ei = (ref and 0xFFFFFFFFL).toInt()
                val e = adj[from][ei]
                if (assigned[e.to]) continue
                assigned[e.to] = true
                component[e.to] = components
                k[e.to] = k[from] + e.steps.toInt()
                for ((ej, next) in adj[e.to].withIndex()) if (!assigned[next.to]) push(e.to, ej)
            }
            components++
        }
        // Общий уровень каждой группы — по средней высоте SRTM (медиана сдвига).
        val shifts = Array(components) { ArrayList<Double>() }
        for (i in 0 until n) if (component[i] >= 0 && !refMean[i].isNaN()) shifts[component[i]].add(refMean[i] / interval - k[i])
        val offset = IntArray(components) { c -> shifts[c].sorted().let { if (it.isEmpty()) 0 else it[it.size / 2].roundToInt() } }
        for (i in 0 until n) if (component[i] >= 0) k[i] += offset[component[i]]

        // Против дрейфа (ошибки ±1 копятся вдоль длинных цепочек): мягкая привязка к SRTM с мёртвой зоной.
        // Внутри ±DEAD_ZONE сечений SRTM не влияет (мелкие формы он не видит), снаружи тянет к краю зоны.
        val target = DoubleArray(n) { if (refMean[it].isNaN()) Double.NaN else refMean[it] / interval }
        val anchor = DoubleArray(n) { ANCHOR * refCount[it] }
        fun anchorPull(i: Int, v: Double): Double? {
            val t = target[i]
            if (t.isNaN()) return null
            return when {
                v > t + DEAD_ZONE -> t + DEAD_ZONE
                v < t - DEAD_ZONE -> t - DEAD_ZONE
                else -> null
            }
        }
        val kc = DoubleArray(n) { k[it].toDouble() }
        for (iter in 0 until 3000) {
            var change = 0.0
            for (i in 0 until n) {
                if (component[i] < 0) continue
                var num = 0.0
                var den = 0.0
                for (e in adj[i]) {
                    num += e.weight * (kc[e.to] - e.steps)
                    den += e.weight
                }
                anchorPull(i, kc[i])?.let { num += anchor[i] * it; den += anchor[i] }
                if (den == 0.0) continue
                val v = num / den
                change = max(change, abs(v - kc[i]))
                kc[i] = v
            }
            if (change < 1e-4) break
        }
        for (i in 0 until n) if (component[i] >= 0) k[i] = kc[i].roundToInt()

        // Утолщённые горизонтали: общий остаток по модулю 5 (большинство).
        val indexIds = contours.indices.filter { contours[it].index && component[it] >= 0 }
        val rho = (0 until 5).maxBy { r -> indexIds.count { k[it].mod(5) == r } }

        // Донастройка: ±1 сечение, если так лучше соседям и кратности утолщённых.
        for (pass in 0 until 30) {
            var changed = false
            for (i in 0 until n) {
                if (component[i] < 0 || adj[i].isEmpty()) continue
                fun cost(v: Int): Double {
                    var c = 0.0
                    for (e in adj[i]) {
                        val d = k[e.to] - v - e.steps
                        c += e.weight * d * d
                    }
                    if (contours[i].index && v.mod(5) != rho) c += INDEX_PENALTY * adj[i].sumOf { it.weight }
                    val t = target[i]
                    if (!t.isNaN()) {
                        val out = abs(v - t) - DEAD_ZONE
                        if (out > 0) c += anchor[i] * out * out
                    }
                    return c
                }
                var best = k[i]
                var bestCost = cost(best)
                for (v in intArrayOf(k[i] - 1, k[i] + 1)) {
                    val c = cost(v)
                    if (c < bestCost - 1e-9) { best = v; bestCost = c }
                }
                if (best != k[i]) { k[i] = best; changed = true }
            }
            if (!changed) break
        }

        var agree = 0.0
        var total = 0.0
        for (i in 0 until n) for (e in adj[i]) if (i < e.to) {
            total += e.weight
            if (abs(k[e.to] - k[i] - e.steps) < 0.5) agree += e.weight
        }
        val heights = DoubleArray(n) { if (refCount[it] == 0) Double.NaN else k[it] * interval }
        return Result(heights, refMean, if (total > 0) agree / total else 1.0, adj.map { l -> l.map { Triple(it.to, it.steps, it.weight) } })
    }

    /**
     * Разница уровней (k_to − k_from): ушли от линии в сторону подъёма и пришли к соседу со стороны спуска — +1;
     * наоборот — −1; иначе между ними вершина или дно, уровни равны.
     */
    private fun relation(sideFrom: Int, leave: Int, sideTo: Int, arrive: Int): Int {
        val leftUphill = leave == sideFrom
        val arrivedFromUphill = arrive == sideTo
        return when {
            leftUphill && !arrivedFromUphill -> 1
            !leftUphill && arrivedFromUphill -> -1
            else -> 0
        }
    }

    private class Edge(val to: Int, val steps: Double, val weight: Double)

    /** Точки через [step] метров вдоль ломаной и единичная нормаль в них (одна и та же сторона по всей линии). */
    private inline fun forEachSample(line: List<Vec2>, step: Double, action: (Vec2, Vec2) -> Unit) {
        var toNext = step / 2
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val len = a.distanceTo(b)
            if (len == 0.0) continue
            val t = (b - a) * (1.0 / len)
            val normal = Vec2(-t.y, t.x)
            var s = toNext
            while (s < len) {
                action(a + t * s, normal)
                s += step
            }
            toNext = s - len
        }
    }

    /** Пересечение луча с линией [line]; [normal] — нормаль этой линии в точке пересечения. */
    private class Hit(val line: Int, val distance: Double, val normal: Vec2)

    /** Сетка корзин с отрезками горизонталей: быстрый поиск первого пересечения луча. */
    private class SegmentIndex(lines: List<List<Vec2>>, val cell: Double) {
        private val buckets = HashMap<Long, MutableList<Seg>>()

        class Seg(val line: Int, val a: Vec2, val b: Vec2)

        init {
            for ((i, part) in lines.withIndex()) for (k in 1 until part.size) {
                val seg = Seg(i, part[k - 1], part[k])
                val c0 = floor(min(seg.a.x, seg.b.x) / cell).toInt()
                val c1 = floor(max(seg.a.x, seg.b.x) / cell).toInt()
                val r0 = floor(min(seg.a.y, seg.b.y) / cell).toInt()
                val r1 = floor(max(seg.a.y, seg.b.y) / cell).toInt()
                for (r in r0..r1) for (cc in c0..c1) buckets.getOrPut(key(cc, r)) { ArrayList() }.add(seg)
            }
        }

        private fun key(c: Int, r: Int) = (c.toLong() shl 32) xor (r.toLong() and 0xFFFFFFFFL)

        fun firstHit(origin: Vec2, dir: Vec2, maxLen: Double, exclude: Int): Hit? {
            val end = origin + dir * maxLen
            val c0 = floor(min(origin.x, end.x) / cell).toInt()
            val c1 = floor(max(origin.x, end.x) / cell).toInt()
            val r0 = floor(min(origin.y, end.y) / cell).toInt()
            val r1 = floor(max(origin.y, end.y) / cell).toInt()
            var best: Seg? = null
            var bestT = maxLen
            var selfT = maxLen
            for (r in r0..r1) for (c in c0..c1) {
                val list = buckets[key(c, r)] ?: continue
                for (s in list) {
                    val t = rayHit(origin, dir, s.a, s.b) ?: continue
                    if (t < 0.05) continue
                    if (s.line == exclude) {
                        if (t < selfT) selfT = t
                    } else if (t < bestT) {
                        bestT = t
                        best = s
                    }
                }
            }
            val seg = best ?: return null
            // Луч сначала вернулся к своей же линии (V-образная ложбина или отрог): соседа за ней не считаем.
            if (selfT < bestT) return null
            val d = seg.b - seg.a
            val len = d.length()
            return Hit(seg.line, bestT, Vec2(-d.y / len, d.x / len))
        }

        /** Расстояние вдоль луча до пересечения с отрезком или null. */
        private fun rayHit(o: Vec2, d: Vec2, a: Vec2, b: Vec2): Double? {
            val sx = b.x - a.x
            val sy = b.y - a.y
            val den = d.x * sy - d.y * sx
            if (abs(den) < 1e-12) return null
            val qx = a.x - o.x
            val qy = a.y - o.y
            val t = (qx * sy - qy * sx) / den
            val u = (qx * d.y - qy * d.x) / den
            return if (t >= 0 && u in 0.0..1.0) t else null
        }
    }
}
