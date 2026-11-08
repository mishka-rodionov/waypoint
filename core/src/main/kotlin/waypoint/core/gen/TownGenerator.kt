package waypoint.core.gen

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.geometry.polylineLength
import waypoint.core.geometry.simplifyPolyline
import waypoint.core.grid.GridSpec
import waypoint.core.map.FeatureStyle
import waypoint.core.map.GeometryKind
import waypoint.core.map.MapFeature
import waypoint.core.map.VectorMap
import waypoint.core.relief.ElevationGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

/** Параметры городской (спринтерской) карты. Размеры в метрах. */
data class TownParams(
    val seed: Long = 1,
    val width: Double = 800.0,
    val height: Double = 800.0,
    /** Средний шаг улиц. */
    val blockSize: Double = 95.0,
    /** Доля кварталов-парков. */
    val parkShare: Double = 0.2,
)

/**
 * Процедурный город для спринта (этап 4): кварталы между улицами со сдвигами, часть соседних кварталов
 * слита (улица обрывается — объезд), в кварталах дома по периметру с проходами во двор,
 * дворы иногда за забором с калиткой, парки с травой, рощами и дорожками. Лёгкий рельеф с сечением 2 м.
 * Коды ISOM 2000: 529 — мощёная площадь, 526 — здание, 524 — непреодолимый забор, 401 — трава, 405 — лес, 506 — дорожка.
 */
class TownGenerator(private val p: TownParams) {
    private val rnd = Random(p.seed * 7919 + 13)
    private val noise = PerlinNoise(p.seed + 101)
    private val box = Box(0.0, 0.0, p.width, p.height)
    private val features = ArrayList<MapFeature>()

    fun generate(): GeneratedMap {
        // Подложка — асфальт: всё, что не дом, не трава и не забор, бегается быстро.
        features.add(MapFeature("529", GeometryKind.AREA, listOf(rect(box)), TownStyles.PAVED))
        val xs = streets(p.width)
        val ys = streets(p.height)
        val cols = xs.size - 1
        val rows = ys.size - 1
        // Слияние соседних кварталов: улица между ними обрывается.
        val mergedRight = Array(rows) { BooleanArray(cols) { rnd.nextDouble() < 0.18 } }
        val mergedDown = Array(rows) { BooleanArray(cols) { rnd.nextDouble() < 0.12 } }
        val owner = Array(rows) { IntArray(cols) { -1 } }
        val blocks = ArrayList<Box>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (owner[r][c] >= 0) continue
            var c2 = c
            var r2 = r
            if (c + 1 < cols && mergedRight[r][c] && owner[r][c + 1] < 0) c2 = c + 1
            else if (r + 1 < rows && mergedDown[r][c] && owner[r + 1][c] < 0) r2 = r + 1
            val id = blocks.size
            for (rr in r..r2) for (cc in c..c2) owner[rr][cc] = id
            val half = { i: Int, arr: List<Pair<Double, Double>> -> arr[i].second / 2 }
            blocks.add(
                Box(
                    xs[c].first + half(c, xs), ys[r].first + half(r, ys),
                    xs[c2 + 1].first - half(c2 + 1, xs), ys[r2 + 1].first - half(r2 + 1, ys),
                ),
            )
        }
        for (b in blocks) {
            if (b.width < 25 || b.height < 25) continue
            val roll = rnd.nextDouble()
            when {
                roll < p.parkShare -> park(b)
                roll < p.parkShare + 0.12 -> bigBuilding(b)
                else -> perimeterBlock(b)
            }
        }
        val elevation = relief()
        contours(elevation)
        return GeneratedMap(GeneratorParams(seed = p.seed, width = p.width, height = p.height), VectorMap(features), elevation)
    }

    /** Улицы: позиции осей (со сдвигом) и ширины; крайние «улицы» — край карты. */
    private fun streets(length: Double): List<Pair<Double, Double>> {
        val out = arrayListOf(0.0 to 0.0)
        var x = 0.0
        while (true) {
            x += p.blockSize * (0.7 + 0.6 * rnd.nextDouble())
            if (x > length - p.blockSize * 0.5) break
            out.add(x to 8.0 + rnd.nextDouble() * 8.0)
        }
        out.add(length to 0.0)
        return out
    }

    /** Дома по периметру квартала с проходами во двор; двор иногда за забором. */
    private fun perimeterBlock(b: Box) {
        val depth = 10.0 + rnd.nextDouble() * 6.0
        val sides = listOf(
            Box(b.minX, b.minY, b.maxX, b.minY + depth), // север
            Box(b.minX, b.maxY - depth, b.maxX, b.maxY), // юг
            Box(b.minX, b.minY + depth, b.minX + depth, b.maxY - depth), // запад
            Box(b.maxX - depth, b.minY + depth, b.maxX, b.maxY - depth), // восток
        )
        for ((i, s) in sides.withIndex()) {
            if (s.width <= 2 || s.height <= 2) continue
            // Сторона двора иногда открыта целиком.
            if (rnd.nextDouble() < 0.12) continue
            val horizontal = i < 2
            val len = if (horizontal) s.width else s.height
            // Дома вдоль стороны с проходами 4–8 м.
            var pos = 0.0
            while (pos < len - 6) {
                val house = min(len - pos, 18.0 + rnd.nextDouble() * 40.0)
                val gap = if (rnd.nextDouble() < 0.45) 4.0 + rnd.nextDouble() * 4.0 else 0.0
                val a = pos
                val e = pos + house - gap
                if (e - a > 5) {
                    val part = if (horizontal) Box(s.minX + a, s.minY, s.minX + e, s.maxY) else Box(s.minX, s.minY + a, s.maxX, s.minY + e)
                    building(part)
                }
                pos += house
            }
        }
        val yard = Box(b.minX + depth, b.minY + depth, b.maxX - depth, b.maxY - depth)
        if (yard.width > 12 && yard.height > 12) {
            if (rnd.nextDouble() < 0.35) features.add(MapFeature("401", GeometryKind.AREA, listOf(rect(yard.expand(-3.0))), TownStyles.GRASS))
            if (rnd.nextDouble() < 0.3) fenceAcross(yard)
        }
    }

    /** Забор поперёк двора с калиткой или без: двор делится, путь через него может закрыться. */
    private fun fenceAcross(yard: Box) {
        val vertical = rnd.nextBoolean()
        val t = 0.3 + 0.4 * rnd.nextDouble()
        val withGate = rnd.nextDouble() < 0.5
        val (a, b) = if (vertical) {
            val x = yard.minX + yard.width * t
            Vec2(x, yard.minY) to Vec2(x, yard.maxY)
        } else {
            val y = yard.minY + yard.height * t
            Vec2(yard.minX, y) to Vec2(yard.maxX, y)
        }
        if (!withGate) {
            features.add(MapFeature("524", GeometryKind.LINE, listOf(listOf(a, b)), TownStyles.FENCE))
            return
        }
        val len = a.distanceTo(b)
        val g0 = (0.3 + 0.4 * rnd.nextDouble()) * len
        val dir = (b - a) * (1 / len)
        features.add(MapFeature("524", GeometryKind.LINE, listOf(listOf(a, a + dir * g0)), TownStyles.FENCE))
        features.add(MapFeature("524", GeometryKind.LINE, listOf(listOf(a + dir * (g0 + 3.0), b)), TownStyles.FENCE))
    }

    private fun bigBuilding(b: Box) {
        val m = 3.0 + rnd.nextDouble() * 5.0
        building(b.expand(-m))
    }

    /** Парк: трава, рощи, дорожки из угла в угол. */
    private fun park(b: Box) {
        features.add(MapFeature("401", GeometryKind.AREA, listOf(rect(b.expand(-2.0))), TownStyles.GRASS))
        repeat(1 + rnd.nextInt(3)) {
            val w = b.width * (0.2 + 0.25 * rnd.nextDouble())
            val h = b.height * (0.2 + 0.25 * rnd.nextDouble())
            val x = b.minX + rnd.nextDouble() * (b.width - w)
            val y = b.minY + rnd.nextDouble() * (b.height - h)
            features.add(MapFeature("405", GeometryKind.AREA, listOf(blob(Box(x, y, x + w, y + h))), TownStyles.FOREST))
        }
        val corners = listOf(Vec2(b.minX, b.minY), Vec2(b.maxX, b.minY), Vec2(b.maxX, b.maxY), Vec2(b.minX, b.maxY))
        val center = Vec2((b.minX + b.maxX) / 2 + (rnd.nextDouble() - 0.5) * b.width * 0.3, (b.minY + b.maxY) / 2 + (rnd.nextDouble() - 0.5) * b.height * 0.3)
        for (c in corners) if (rnd.nextDouble() < 0.7) {
            features.add(MapFeature("506", GeometryKind.LINE, listOf(listOf(c, center)), Styles.PATH))
        }
    }

    private fun building(b: Box) {
        if (b.width < 4 || b.height < 4) return
        features.add(MapFeature("526", GeometryKind.AREA, listOf(rect(b)), TownStyles.BUILDING))
    }

    /** Округлое пятно, вписанное в прямоугольник. */
    private fun blob(b: Box): List<Vec2> {
        val cx = (b.minX + b.maxX) / 2
        val cy = (b.minY + b.maxY) / 2
        val n = 16
        return (0..n).map { i ->
            val a = 2 * Math.PI * (i % n) / n
            val k = 0.8 + 0.2 * noise.noise(cx / 10 + kotlin.math.cos(a), cy / 10 + kotlin.math.sin(a))
            Vec2(cx + kotlin.math.cos(a) * b.width / 2 * k, cy + kotlin.math.sin(a) * b.height / 2 * k)
        }
    }

    private fun rect(b: Box) = listOf(Vec2(b.minX, b.minY), Vec2(b.maxX, b.minY), Vec2(b.maxX, b.maxY), Vec2(b.minX, b.maxY), Vec2(b.minX, b.minY))

    /** Лёгкий уклон города: несколько метров перепада. */
    private fun relief(): ElevationGrid {
        val cell = 4.0
        val w = ceil(p.width / cell).toInt() + 1
        val h = ceil(p.height / cell).toInt() + 1
        val spec = GridSpec(-cell / 2, -cell / 2, cell, w, h)
        val v = FloatArray(w * h) { i ->
            val x = (i % w) * cell
            val y = (i / w) * cell
            (100 + 6 * noise.fbm(x / 600, y / 600, 3) + 0.03 * (x - y) / 10).toFloat()
        }
        return ElevationGrid(spec, v)
    }

    private fun contours(e: ElevationGrid) {
        val nodes = NodeField(GridSpec(0.0, 0.0, e.spec.cellSize, e.spec.width, e.spec.height), e.heights)
        val lo = floor(e.heights.min() / 2.0).toInt() + 1
        val hi = ceil(e.heights.max() / 2.0).toInt() - 1
        for (k in lo..hi) for (line in MarchingSquares.isolines(nodes, (k * 2.0).toFloat())) {
            val pts = simplifyPolyline(line, 0.4)
            if (pts.size < 2 || polylineLength(pts) < 20) continue
            features.add(MapFeature("101", GeometryKind.LINE, listOf(pts), Styles.CONTOUR))
        }
    }
}

/** Цвета городских символов: здания серые с чёрным контуром (как в ISSprOM), асфальт бежевый. */
object TownStyles {
    val PAVED = FeatureStyle(0xFFE8D2B0.toInt(), 0.0, 60)
    val GRASS = FeatureStyle(0xFFFFBA35.toInt(), 0.0, 50)
    val FOREST = FeatureStyle(0xFFFFFFFF.toInt(), 0.0, 45)
    val BUILDING = FeatureStyle(0xFF8C8C8C.toInt(), 0.0, 30)
    val FENCE = FeatureStyle(0xFF000000.toInt(), 0.0, 4)
}
