package waypoint.core.gen

import waypoint.core.geometry.Vec2
import waypoint.core.grid.GridSpec
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteEvaluator
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.TerrainGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratorTest {

    /** Конус высотой 10 в центре поля 21×21 узел с шагом 1. */
    private fun cone(): NodeField {
        val s = GridSpec(0.0, 0.0, 1.0, 21, 21)
        return NodeField(s, FloatArray(s.size) { i -> (10 - hypot((i % 21) - 10.0, (i / 21) - 10.0)).toFloat() })
    }

    @Test
    fun `изолиния конуса — замкнутая окружность`() {
        val lines = MarchingSquares.isolines(cone(), 5f)
        assertEquals(1, lines.size)
        val ring = lines[0]
        assertEquals(ring.first(), ring.last())
        for (p in ring) assertEquals(5.0, hypot(p.x - 10, p.y - 10), 0.15)
        assertEquals(PI * 25, abs(MapGenerator.ringArea(ring)), 1.5)
    }

    @Test
    fun `область у края поля замыкается по краю`() {
        // Наклонная плоскость: значение = x. Область x ≥ 15 — прямоугольник 5×20 у правого края.
        val s = GridSpec(0.0, 0.0, 1.0, 21, 21)
        val f = NodeField(s, FloatArray(s.size) { (it % 21).toFloat() })
        val regions = MarchingSquares.regions(f, 15f)
        assertEquals(1, regions.size)
        assertEquals(100.0, abs(MapGenerator.ringArea(regions[0])), 1.0)
        assertEquals(1, MarchingSquares.isolines(f, 15f).size) // линия от края до края
    }

    @Test
    fun `сток стекает к краю, впадина заполняется`() {
        // Чаша: впадина в центре, край выше.
        val s = GridSpec(0.0, 0.0, 10.0, 21, 21)
        // Лёгкий наклон: выход из чаши один (у левого края).
        val bowl = NodeField(s, FloatArray(s.size) { i -> (hypot((i % 21) - 10.0, (i / 21) - 10.0) + 0.05 * (i % 21)).toFloat() })
        val h = Hydrology(bowl)
        val center = 10 * 21 + 10
        assertTrue(h.depth(center) > 5f, "центр чаши — озеро")
        // Из любого узла по получателям выходим за край.
        var cur = center
        var steps = 0
        while (cur >= 0 && steps < 1000) { cur = h.receiver[cur]; steps++ }
        assertTrue(steps < 1000)
        assertTrue(h.area.max() >= 0.9f * s.size * 100f)
    }

    @Test
    fun `генератор детерминирован и даёт проходимую карту со связками`() {
        val params = GeneratorParams(seed = 5, width = 800.0, height = 800.0)
        val a = MapGenerator(params).generate()
        val b = MapGenerator(params).generate()
        assertEquals(a.map.features.size, b.map.features.size)
        val codes = a.map.features.groupingBy { it.code.substringBefore('.') }.eachCount()
        println("объекты: $codes")
        assertTrue((codes["101"] ?: 0) + (codes["102"] ?: 0) > 5, "есть горизонтали")
        assertTrue((codes["506"] ?: 0) + (codes["504"] ?: 0) > 0, "есть тропы")
        val terrain = TerrainGrid.build(a.map, ISOM_2000, 2.0)
        val passable = terrain.speed.count { it > 0f }.toDouble() / terrain.speed.size
        assertTrue(passable > 0.8, "проходимо $passable")
        val model = MovementModel(terrain, a.elevation, maxRelativeSpeed = ISOM_2000.maxSpeed)
        val opt = assertNotNull(RouteEvaluator(model).optimal(Vec2(100.0, 100.0), Vec2(650.0, 600.0)))
        assertTrue(opt.seconds > 0)
    }
}
