package waypoint.core

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.grid.GridSpec
import waypoint.core.grid.Rasterizer
import waypoint.core.map.FeatureStyle
import waypoint.core.map.GeometryKind
import waypoint.core.map.MapFeature
import waypoint.core.map.VectorMap
import waypoint.core.relief.Contour
import waypoint.core.relief.ContourHeights
import waypoint.core.relief.MapContour
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.LegExplorer
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteEvaluator
import waypoint.core.route.SlopeModel
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.TerrainGrid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteTest {
    private val style = FeatureStyle(0)
    private val box = Box(0.0, 0.0, 200.0, 200.0)

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))

    private fun area(code: String, ring: List<Vec2>) = MapFeature(code, GeometryKind.AREA, listOf(ring), style)
    private fun line(code: String, vararg pts: Vec2) = MapFeature(code, GeometryKind.LINE, listOf(pts.toList()), style)

    /** Рамка из открытого пространства задаёт границы карты. */
    private fun mapOf(vararg features: MapFeature) =
        VectorMap(listOf(area("405", rect(0.0, 0.0, 200.0, 200.0))) + features)

    private fun evaluator(map: VectorMap, elevation: ElevationGrid? = null, slope: SlopeModel = SlopeModel.FLAT): Pair<MovementModel, RouteEvaluator> {
        val terrain = TerrainGrid.build(map, ISOM_2000, 2.0, box)
        val model = MovementModel(terrain, elevation, slope, baseSpeed = 3.0, maxRelativeSpeed = ISOM_2000.maxSpeed)
        return model to RouteEvaluator(model)
    }

    @Test
    fun `заливка учитывает дырку`() {
        val spec = GridSpec(0.0, 0.0, 1.0, 10, 10)
        var count = 0
        Rasterizer.fillPolygon(listOf(rect(0.0, 0.0, 10.0, 10.0), rect(2.0, 2.0, 8.0, 8.0)), spec) { count++ }
        assertEquals(100 - 36, count)
    }

    @Test
    fun `по ровному лесу время равно длине на скорость`() {
        val (_, ev) = evaluator(mapOf())
        val stats = ev.along(listOf(Vec2(20.0, 100.0), Vec2(180.0, 100.0)))
        assertEquals(160.0 / 3.0, stats.seconds, 1e-6)
        assertTrue(stats.violations.isEmpty())
    }

    @Test
    fun `оптимальный путь почти прямой в однородном лесу`() {
        val (_, ev) = evaluator(mapOf())
        val opt = assertNotNull(ev.optimal(Vec2(20.0, 30.0), Vec2(170.0, 160.0)))
        val straight = Vec2(20.0, 30.0).distanceTo(Vec2(170.0, 160.0))
        assertTrue(opt.length < straight * 1.04, "длина ${opt.length} против прямой $straight")
    }

    @Test
    fun `высокий забор обходится через проход`() {
        val map = mapOf(line("524", Vec2(100.0, 0.0), Vec2(100.0, 150.0)))
        val (_, ev) = evaluator(map)
        val from = Vec2(50.0, 50.0)
        val to = Vec2(150.0, 50.0)
        val opt = assertNotNull(ev.optimal(from, to))
        assertTrue(opt.points.any { it.y > 150.0 }, "путь должен обойти забор снизу")
        val drawn = ev.along(listOf(from, to))
        assertEquals(1, drawn.violations.size)
    }

    @Test
    fun `тропа в обход быстрее прямо через чащу`() {
        val map = mapOf(
            area("410", rect(0.0, 60.0, 200.0, 140.0)),
            line("506", Vec2(10.0, 20.0), Vec2(190.0, 20.0)),
            line("506", Vec2(10.0, 20.0), Vec2(10.0, 180.0)),
            line("506", Vec2(10.0, 180.0), Vec2(100.0, 180.0)),
        )
        val (_, ev) = evaluator(map)
        val opt = assertNotNull(ev.optimal(Vec2(100.0, 30.0), Vec2(100.0, 170.0)))
        assertTrue(opt.points.any { it.x < 20.0 }, "путь должен идти по тропе слева")
    }

    @Test
    fun `коридор не быстрее оптимума и не медленнее линии`() {
        val map = mapOf(area("408", rect(80.0, 0.0, 120.0, 200.0)))
        val (_, ev) = evaluator(map)
        val from = Vec2(20.0, 100.0)
        val to = Vec2(180.0, 100.0)
        val wobbly = listOf(from, Vec2(60.0, 92.0), Vec2(100.0, 108.0), Vec2(140.0, 93.0), to)
        val a = assertNotNull(ev.analyze(from, to, wobbly.subList(1, wobbly.size - 1), corridor = 15.0))
        val intended = assertNotNull(a.intended)
        assertTrue(intended.seconds >= a.optimal.seconds - 1e-6)
        assertTrue(intended.seconds <= a.drawn.seconds + 1e-6)
    }

    @Test
    fun `петля на линии остаётся петлёй`() {
        val (_, ev) = evaluator(mapOf())
        val from = Vec2(20.0, 100.0)
        val to = Vec2(180.0, 100.0)
        // Линия уходит петлёй на 60 м в сторону и возвращается: коридор 15 м не должен срезать петлю.
        val loop = listOf(Vec2(60.0, 100.0), Vec2(80.0, 40.0), Vec2(110.0, 40.0), Vec2(120.0, 100.0))
        val a = assertNotNull(ev.analyze(from, to, loop, corridor = 15.0))
        val intended = assertNotNull(a.intended)
        assertTrue(intended.length > a.optimal.length * 1.15, "петля срезана: ${intended.length} против ${a.optimal.length}")
    }

    @Test
    fun `дрожание руки сглаживается`() {
        val (_, ev) = evaluator(mapOf())
        val from = Vec2(20.0, 100.0)
        val to = Vec2(180.0, 100.0)
        val shaky = (1..15).map { i -> Vec2(20.0 + i * 10, 100.0 + if (i % 2 == 0) 5.0 else -5.0) }
        val a = assertNotNull(ev.analyze(from, to, shaky, corridor = 15.0))
        val intended = assertNotNull(a.intended)
        assertTrue(intended.seconds < a.optimal.seconds * 1.05, "${intended.seconds} против ${a.optimal.seconds}")
        assertTrue(a.drawn.length > intended.length)
    }

    @Test
    fun `обход препятствия с двух сторон даёт два варианта`() {
        // Непроходимое озеро посередине, обход сверху короче, чем снизу.
        val map = mapOf(area("301", rect(60.0, 70.0, 140.0, 160.0)))
        val (model, _) = evaluator(map)
        val report = assertNotNull(LegExplorer(model).explore(Vec2(30.0, 110.0), Vec2(170.0, 110.0)))
        assertTrue(report.options.size >= 2, "вариантов: ${report.options.size}")
        assertTrue(report.options[0].points.any { it.y < 70.0 }, "оптимум — сверху")
        assertTrue(report.options[1].points.any { it.y > 160.0 }, "второй вариант — снизу")
        val gap = assertNotNull(report.choiceGap)
        assertTrue(gap > 0.0 && gap < 0.5, "разрыв $gap")
        assertTrue(report.straightBlocked, "прямая через озеро перекрыта")
        assertTrue(report.interest > 0.3)
    }

    @Test
    fun `подъём медленнее спуска и ровного`() {
        val tobler = SlopeModel.tobler()
        assertEquals(1.0, tobler.factor(0.0), 1e-9)
        assertTrue(tobler.factor(-0.05) < 1.0)
        assertTrue(tobler.factor(0.2) > tobler.factor(-0.2))
    }

    @Test
    fun `уровни горизонталей холма восстанавливаются по грубой модели`() {
        // Холм: концентрические горизонтали через 20 м, грубая модель — гладкий конус (вершина в центре).
        val center = Vec2(500.0, 500.0)
        val circles = (1..8).map { k ->
            val r = 20.0 * k
            val ring = (0..64).map { a -> val t = a * 2 * Math.PI / 64; Vec2(center.x + r * kotlin.math.cos(t), center.y + r * kotlin.math.sin(t)) }
            MapContour(listOf(ring), index = k % 5 == 0)
        }
        val res = ContourHeights.assign(circles, { p -> 120.0 - p.distanceTo(center) * 0.125 }, 2.5)
        for (k in 1 until circles.size) {
            assertEquals(-2.5, res.heights[k] - res.heights[k - 1], 1e-9, "кольцо $k")
        }
    }

    @Test
    fun `рельеф по горизонталям монотонен между линиями`() {
        val contours = (0..4).map { k -> Contour(100.0 + 5 * k, listOf(Vec2(0.0, 40.0 * k + 20), Vec2(200.0, 40.0 * k + 20))) }
        val el = ElevationGrid.fromContours(contours, box, 4.0)
        val h1 = el.heightAt(Vec2(100.0, 40.0))
        val h2 = el.heightAt(Vec2(100.0, 80.0))
        assertTrue(abs(h1 - 102.5) < 1.0, "h1=$h1")
        assertTrue(h2 > h1)
    }
}
