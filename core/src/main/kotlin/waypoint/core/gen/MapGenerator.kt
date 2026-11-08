package waypoint.core.gen

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.geometry.polylineLength
import waypoint.core.geometry.simplifyPolyline
import waypoint.core.grid.GridSpec
import waypoint.core.grid.Rasterizer
import waypoint.core.map.FeatureStyle
import waypoint.core.map.GeometryKind
import waypoint.core.map.MapFeature
import waypoint.core.map.VectorMap
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.MovementModel
import waypoint.core.route.Pathfinder
import waypoint.core.route.SlopeModel
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.TerrainGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Параметры генерации лесной карты. Размеры в метрах. */
data class GeneratorParams(
    val seed: Long = 1,
    val width: Double = 2000.0,
    val height: Double = 2000.0,
    /** Шаг сетки рельефа и полей растительности. */
    val cell: Double = 4.0,
    /** Сечение рельефа. */
    val contourInterval: Double = 2.5,
    /** Перепад крупных холмов (± от средней высоты). */
    val relief: Double = 22.0,
    /** Доля зелени: сдвиг порогов растительности (0 — как задумано, больше — гуще лес). */
    val greenBias: Double = 0.0,
    /** Сколько «точек интересa» соединять тропами на квадратный километр. */
    val poiPerKm2: Double = 6.0,
)

/** Сгенерированная карта: объекты с кодами ISOM 2000 (как у импортированной OMAP) и рельеф. */
class GeneratedMap(val params: GeneratorParams, val map: VectorMap, val elevation: ElevationGrid)

/**
 * Процедурная лесная карта (этап 3):
 * 1. рельеф — фрактальный шум с искажением координат: крупные холмы, гребни, мелкие бугры;
 * 2. сток — заполнение впадин, D8, водосбор: озёра во впадинах, ручьи по большому водосбору,
 *    болота там, где воды много, а уклон мал;
 * 3. растительность — пороги полей шума: у воды гуще, на вершинах открыто;
 * 4. тропы — A* между «точками интереса» по стоимости (растительность, вода, крутизна);
 *    уже проложенные тропы дешевле, поэтому сеть получается связной и естественной;
 * 5. горизонтали — изолинии рельефа (каждая пятая утолщённая).
 * Все площади — контуры порогов полей (марширующие квадраты), поэтому края плавные.
 */
class MapGenerator(private val p: GeneratorParams) {
    private val noise = PerlinNoise(p.seed)
    private val vegNoise = PerlinNoise(p.seed * 31 + 7)
    private val rnd = Random(p.seed * 131 + 3)
    private val spec = GridSpec(0.0, 0.0, p.cell, (p.width / p.cell).toInt() + 1, (p.height / p.cell).toInt() + 1)
    private val box = Box(0.0, 0.0, (spec.width - 1) * p.cell, (spec.height - 1) * p.cell)
    private val features = ArrayList<MapFeature>()

    fun generate(): GeneratedMap {
        val heights = relief()
        val hydro = Hydrology(downsample(heights, 2))
        val wet = wetness(hydro)
        water(hydro)
        vegetation(heights, hydro, wet)
        marsh(hydro, wet)
        contours(heights)
        val elevation = ElevationGrid(
            GridSpec(-p.cell / 2, -p.cell / 2, p.cell, spec.width, spec.height),
            heights.values,
        )
        paths(elevation)
        return GeneratedMap(p, VectorMap(features), elevation)
    }

    // ---------- рельеф ----------

    private fun relief(): NodeField {
        val v = FloatArray(spec.size)
        for (r in 0 until spec.height) for (c in 0 until spec.width) {
            val x = c * p.cell
            val y = r * p.cell
            // Искажение координат делает формы холмов неправильными, как в природе.
            val wx = x + 140 * noise.fbm(x / 520 + 3.1, y / 520 - 1.7, 3)
            val wy = y + 140 * noise.fbm(x / 520 - 8.3, y / 520 + 4.2, 3)
            val base = p.relief * noise.fbm(wx / 950, wy / 950, 4)
            val ridges = p.relief * 0.32 * (noise.ridged(wx / 380 + 20, wy / 380 - 20, 3) - 0.55)
            val knolls = 1.3 * noise.fbm(x / 65 + 50, y / 65 + 50, 2)
            v[r * spec.width + c] = (150 + base + ridges + knolls).toFloat()
        }
        return NodeField(spec, v)
    }

    private fun downsample(f: NodeField, k: Int): NodeField {
        val s = GridSpec(0.0, 0.0, p.cell * k, (spec.width - 1) / k + 1, (spec.height - 1) / k + 1)
        val v = FloatArray(s.size) { i -> f[(i % s.width) * k, (i / s.width) * k] }
        return NodeField(s, v)
    }

    // ---------- вода ----------

    /** Влажность 0…1 по водосбору и уклону, на сетке гидрологии. */
    private fun wetness(hydro: Hydrology): NodeField {
        val s = hydro.heights.spec
        val raw = FloatArray(s.size) { i -> (ln(hydro.area[i].toDouble()) - 1.6 * ln(hydro.slope(i) + 0.01)).toFloat() }
        val sorted = raw.sorted()
        val lo = sorted[(sorted.size * 0.05).toInt()]
        val hi = sorted[(sorted.size * 0.98).toInt()]
        val norm = FloatArray(s.size) { ((raw[it] - lo) / (hi - lo)).coerceIn(0f, 1f) }
        return NodeField(s, blur(norm, s.width, s.height, 2))
    }

    private fun water(hydro: Hydrology) {
        val hs = hydro.heights.spec
        val depth = NodeField(hs, FloatArray(hs.size) { hydro.depth(it) })
        // Глубокие впадины — озёра, мелкие — болота (вода стоит, но неглубоко).
        val minDepth = 2.5f
        for (ring in MarchingSquares.regions(depth, minDepth)) {
            if (abs(ringArea(ring)) < 2500) continue
            features.add(MapFeature("301", GeometryKind.AREA, listOf(ring), Styles.LAKE))
        }
        for (ring in MarchingSquares.regions(depth, 0.5f)) {
            if (abs(ringArea(ring)) < 800) continue
            features.add(MapFeature("310", GeometryKind.AREA, listOf(ring), Styles.MARSH))
        }
        // Ручей начинается с водосбора 5 га, большой ручей — с 25 га.
        val streams = hydro.streams(minArea = 50_000f, bigArea = 250_000f) { depth.values[it] >= minDepth }
        for ((line, big) in streams) {
            if (polylineLength(line) < 60) continue
            val smooth = chaikin(simplifyPolyline(line, 2.0), 2)
            features.add(MapFeature(if (big) "305" else "306", GeometryKind.LINE, listOf(smooth), if (big) Styles.STREAM else Styles.SMALL_STREAM))
        }
    }

    private fun marsh(hydro: Hydrology, wet: NodeField) {
        val s = wet.spec
        val field = FloatArray(s.size) { i ->
            val x = (i % s.width) * s.cellSize
            val y = (i / s.width) * s.cellSize
            (wet.values[i] + 0.25 * vegNoise.fbm(x / 120 + 40, y / 120 - 40, 3)).toFloat()
        }
        val f = NodeField(s, field)
        for (ring in MarchingSquares.regions(f, 0.78f)) if (abs(ringArea(ring)) > 600) {
            features.add(MapFeature("310", GeometryKind.AREA, listOf(ring), Styles.MARSH))
        }
        for (ring in MarchingSquares.regions(f, 0.64f)) if (abs(ringArea(ring)) > 900) {
            features.add(MapFeature("311", GeometryKind.AREA, listOf(ring), Styles.WET))
        }
    }

    // ---------- растительность ----------

    private fun vegetation(heights: NodeField, hydro: Hydrology, wet: NodeField) {
        val vals = heights.values
        val sorted = vals.sorted()
        val hLo = sorted[(sorted.size * 0.05).toInt()]
        val hHi = sorted[(sorted.size * 0.95).toInt()]
        val green = FloatArray(spec.size)
        val open = FloatArray(spec.size)
        for (r in 0 until spec.height) for (c in 0 until spec.width) {
            val i = r * spec.width + c
            val x = c * p.cell
            val y = r * p.cell
            val hn = ((vals[i] - hLo) / (hHi - hLo)).coerceIn(0f, 1f)
            val wn = wet.at(Vec2(x, y)).toFloat()
            val o = vegNoise.fbm(x / 420 - 7, y / 420 + 3, 3) + 0.35 * hn - 0.2 * wn
            open[i] = o.toFloat()
            var g = vegNoise.fbm(x / 230 + 11, y / 230 - 5, 4) + 0.45 * wn - 0.25 * hn + p.greenBias
            g -= 0.8 * max(0.0, o - 0.3)
            green[i] = g.toFloat()
        }
        val openF = NodeField(spec, open)
        val greenF = NodeField(spec, green)
        addRegions(openF, 0.38f, "403", Styles.ROUGH_OPEN, 1500.0)
        addRegions(openF, 0.50f, "401", Styles.OPEN, 1200.0)
        addRegions(greenF, 0.16f, "406", Styles.GREEN1, 800.0)
        addRegions(greenF, 0.33f, "408", Styles.GREEN2, 500.0)
        addRegions(greenF, 0.50f, "410", Styles.GREEN3, 300.0)
    }

    private fun addRegions(f: NodeField, level: Float, code: String, style: FeatureStyle, minArea: Double) {
        // Ориентация колец у марширующих квадратов не гарантирована: внешние кольца и дырки различаем
        // по чётности вложенности. Дырка (поляна в зелени) идёт в один объект со своим внешним кольцом.
        val rings = MarchingSquares.regions(f, level).map { simplifyPolyline(it, 0.6) }.filter { it.size >= 4 }
        val areas = rings.map { abs(ringArea(it)) }
        val order = rings.indices.sortedByDescending { areas[it] }
        val depth = IntArray(rings.size)
        val owner = IntArray(rings.size) { -1 }
        for ((k, i) in order.withIndex()) {
            // Объемлющие кольца крупнее: ищем среди уже просмотренных, ближайшее — последнее подходящее.
            var d = 0
            var nearest = -1
            for (m in 0 until k) {
                val j = order[m]
                if (contains(rings[j], rings[i][0])) { d++; nearest = j }
            }
            depth[i] = d
            owner[i] = nearest
        }
        val groups = LinkedHashMap<Int, MutableList<List<Vec2>>>()
        for (i in order) if (depth[i] % 2 == 0) groups[i] = mutableListOf(rings[i])
        for (i in order) if (depth[i] % 2 == 1) groups[owner[i]]?.add(rings[i])
        for ((i, g) in groups) {
            val area = areas[i] - g.drop(1).sumOf { abs(ringArea(it)) }
            if (area < minArea) continue
            features.add(MapFeature(code, GeometryKind.AREA, g, style))
        }
    }

    // ---------- горизонтали ----------

    private fun contours(heights: NodeField) {
        val vals = heights.values
        val lo = ceil(vals.min() / p.contourInterval).toInt()
        val hi = floor(vals.max() / p.contourInterval).toInt()
        for (k in lo..hi) {
            val level = k * p.contourInterval
            val index = k % 5 == 0
            for (line in MarchingSquares.isolines(heights, level.toFloat())) {
                val pts = simplifyPolyline(line, 0.4)
                if (pts.size < 2 || polylineLength(pts) < 25) continue
                features.add(MapFeature(if (index) "102" else "101", GeometryKind.LINE, listOf(pts), if (index) Styles.INDEX_CONTOUR else Styles.CONTOUR))
            }
        }
    }

    // ---------- тропы ----------

    private fun paths(elevation: ElevationGrid) {
        // Стоимость для «строителя троп»: проходимость по уже созданной местности и крутые склоны (Тоблер ×2).
        val terrain = TerrainGrid.build(VectorMap(features), ISOM_2000, 4.0, box)
        val model = MovementModel(terrain, elevation, slope = SlopeModel.tobler(7.0), maxRelativeSpeed = ISOM_2000.maxSpeed)
        val finder = Pathfinder(model)
        val ts = terrain.spec
        val onPath = BooleanArray(ts.size)
        val penalty: (Int) -> Float = { i -> if (onPath[i]) 0.35f else 1f }

        fun build(a: Vec2, b: Vec2, code: String) {
            val path = finder.find(a, b, box, penalty = penalty) ?: return
            if (path.points.size < 2) return
            Rasterizer.strokePolyline(path.points, 3.0, ts) { onPath[it] = true }
            val smooth = chaikin(simplifyPolyline(path.points, 2.5), 2)
            features.add(MapFeature(code, GeometryKind.LINE, listOf(smooth), Styles.PATH))
        }

        fun passable(v: Vec2): Boolean = terrain.speedAt(ts.indexOf(v)) >= 0.5f
        fun randomPoint(): Vec2? {
            repeat(50) {
                val v = Vec2(box.minX + 60 + rnd.nextDouble() * (box.width - 120), box.minY + 60 + rnd.nextDouble() * (box.height - 120))
                if (passable(v)) return v
            }
            return null
        }
        // Дорога через всю карту: от одного края к противоположному.
        val horizontal = rnd.nextBoolean()
        val t1 = 0.2 + 0.6 * rnd.nextDouble()
        val t2 = 0.2 + 0.6 * rnd.nextDouble()
        val roadA = if (horizontal) Vec2(box.minX + 2, box.minY + box.height * t1) else Vec2(box.minX + box.width * t1, box.minY + 2)
        val roadB = if (horizontal) Vec2(box.maxX - 2, box.minY + box.height * t2) else Vec2(box.minX + box.width * t2, box.maxY - 2)
        build(roadA, roadB, "504")

        val count = max(4, (p.poiPerKm2 * box.width * box.height / 1e6).roundToInt())
        val pois = (0 until count).mapNotNull { randomPoint() } + listOf(roadA, roadB)
        // Остов минимальной длины (Прим): сеть связная; затем несколько петель — без них нет выбора пути.
        val inTree = BooleanArray(pois.size)
        inTree[pois.size - 1] = true
        val edges = ArrayList<Pair<Int, Int>>()
        repeat(pois.size - 1) {
            var best: Pair<Int, Int>? = null
            var bestD = Double.POSITIVE_INFINITY
            for (i in pois.indices) if (inTree[i]) for (j in pois.indices) if (!inTree[j]) {
                val d = pois[i].distanceTo(pois[j])
                if (d < bestD) { bestD = d; best = i to j }
            }
            val e = best ?: return@repeat
            inTree[e.second] = true
            edges.add(e)
        }
        for ((i, j) in edges) build(pois[i], pois[j], "506")
        for (i in pois.indices) {
            if (rnd.nextDouble() > 0.45) continue
            val near = pois.indices.filter { it != i }.sortedBy { pois[it].distanceTo(pois[i]) }
            val j = near.getOrNull(1 + rnd.nextInt(2)) ?: continue
            if (edges.any { (a, b) -> (a == i && b == j) || (a == j && b == i) }) continue
            val code = if (rnd.nextDouble() < 0.5) "507" else "508"
            build(pois[i], pois[j], code)
        }
    }

    // ---------- утилиты ----------

    private fun blur(v: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var cur = v
        repeat(radius) {
            val next = FloatArray(cur.size)
            for (r in 0 until h) for (c in 0 until w) {
                var s = 0f
                var k = 0
                for (dr in -1..1) for (dc in -1..1) {
                    val cc = c + dc
                    val rr = r + dr
                    if (cc < 0 || rr < 0 || cc >= w || rr >= h) continue
                    s += cur[rr * w + cc]
                    k++
                }
                next[r * w + c] = s / k
            }
            cur = next
        }
        return cur
    }

    companion object {
        /** Ориентированная площадь кольца (формула Гаусса). */
        fun ringArea(ring: List<Vec2>): Double {
            var s = 0.0
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                s += a.x * b.y - b.x * a.y
            }
            return s / 2
        }

        /** Точка внутри кольца (чётность пересечений луча). */
        fun contains(ring: List<Vec2>, p: Vec2): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[j]
                if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
                j = i
            }
            return inside
        }

        /** Сглаживание ломаной срезанием углов (Чайкин); концы остаются на месте. */
        fun chaikin(points: List<Vec2>, iterations: Int): List<Vec2> {
            var pts = points
            repeat(iterations) {
                if (pts.size < 3) return pts
                val out = ArrayList<Vec2>(pts.size * 2)
                out.add(pts.first())
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    out.add(a * 0.75 + b * 0.25)
                    out.add(a * 0.25 + b * 0.75)
                }
                out.add(pts.last())
                pts = out
            }
            return pts
        }
    }
}

/** Цвета и толщины символов ISOM 2000 для сгенерированной карты (толщина — метры на местности при 1:7500). */
object Styles {
    private const val MM = 7.5
    val CONTOUR = FeatureStyle(0xFFC8701E.toInt(), 0.14 * MM, 15)
    val INDEX_CONTOUR = FeatureStyle(0xFFC8701E.toInt(), 0.25 * MM, 15)
    val LAKE = FeatureStyle(0xFF7FD2FF.toInt(), 0.0, 20)
    val STREAM = FeatureStyle(0xFF0096E6.toInt(), 0.25 * MM, 10)
    val SMALL_STREAM = FeatureStyle(0xFF0096E6.toInt(), 0.18 * MM, 10)
    val MARSH = FeatureStyle(0xFF0096E6.toInt(), 0.0, 25)
    val WET = FeatureStyle(0xFF0096E6.toInt(), 0.0, 26)
    val OPEN = FeatureStyle(0xFFFFBA35.toInt(), 0.0, 50)
    val ROUGH_OPEN = FeatureStyle(0xFFFFDC9A.toInt(), 0.0, 51)
    val GREEN1 = FeatureStyle(0xFFC5FFB9.toInt(), 0.0, 42)
    val GREEN2 = FeatureStyle(0xFF8BFF74.toInt(), 0.0, 41)
    val GREEN3 = FeatureStyle(0xFF3DFF17.toInt(), 0.0, 40)
    val PATH = FeatureStyle(0xFF000000.toInt(), 0.0, 5)
}
