package waypoint.tools

import waypoint.core.geometry.Vec2
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteEvaluator
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.geo.Georeferencing
import waypoint.tools.geo.Utm
import waypoint.tools.omap.OmapReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolsTest {

    @Test
    fun `перевод мм-UTM обратим`() {
        val g = Georeferencing(7500.0, 8.0, 1.000199, 38, Vec2(372000.0, 5716000.0))
        val mm = Vec2(-120.5, 33.25)
        val back = g.projectedToMm(g.mmToProjected(mm))
        assertEquals(mm.x, back.x, 1e-9)
        assertEquals(mm.y, back.y, 1e-9)
    }

    @Test
    fun `UTM совпадает с опорной точкой карты Мача`() {
        // В карте: ref_point 372000, 5716000 ↔ 51.58050996, 43.15262886.
        val p = Utm.forward(51.58050996, 43.15262886, 38)
        assertEquals(372000.0, p.x, 0.5)
        assertEquals(5716000.0, p.y, 0.5)
    }

    @Test
    fun `кривая Безье разворачивается в ломаную до конечной точки`() {
        val parts = OmapReader.parseCoords("0 0 1;1000 0;2000 1000;2000 2000;0 2000 18;")
        assertEquals(1, parts.size)
        val ring = parts[0]
        assertTrue(ring.size > 4)
        assertEquals(Vec2(2.0, 2.0), ring[ring.size - 2])
        assertEquals(Vec2(0.0, 2.0), ring.last())
    }

    @Test
    fun `файл отобранных связок читается обратно`() {
        val legs = listOf(
            waypoint.tools.legs.MinedLeg(Vec2(-12.5, 30.25), Vec2(400.0, -7.0), 0.83, 0.064, 0.3, listOf(200.0, 212.8)),
            waypoint.tools.legs.MinedLeg(Vec2(1.0, 2.0), Vec2(3.0, 4.0), 0.1, null, null, listOf(50.0)),
        )
        val f = File.createTempFile("legs", ".json")
        try {
            waypoint.tools.legs.LegsFile.write(f, "тест", legs)
            val back = waypoint.tools.legs.LegsFile.read(f)
            assertEquals(2, back.size)
            assertEquals(legs[0].from, back[0].from)
            assertEquals(legs[0].choiceGap!!, back[0].choiceGap!!, 1e-4)
            assertEquals(null, back[1].choiceGap)
            assertEquals(legs[0].optionSeconds, back[0].optionSeconds)
        } finally {
            f.delete()
        }
    }

    /** Дымовой тест на настоящей карте; пропускается, если карты нет (она не хранится в репозитории). */
    @Test
    fun `настоящая карта загружается и считается связка`() {
        val omap = File("../maps/macha.omap")
        if (!omap.exists()) return
        val loaded = MapLoader.load(omap, File("../maps/macha-srtm25.shp"), log = ::println)
        println("bounds=${loaded.map.bounds} courses=${loaded.courses.map { it.name to it.controls.size }}")
        val elevation = assertNotNull(loaded.elevation)
        println("heights: ${elevation.heights.min()}..${elevation.heights.max()}")

        var t = System.currentTimeMillis()
        val terrain = TerrainGrid.build(loaded.map, ISOM_2000, 2.0)
        println("terrain ${terrain.spec.width}x${terrain.spec.height}: ${System.currentTimeMillis() - t} мс")
        val model = MovementModel(terrain, elevation, maxRelativeSpeed = ISOM_2000.maxSpeed)
        val ev = RouteEvaluator(model)
        val course = loaded.courses.first { !it.freeOrder }
        for (leg in course.legs.take(4)) {
            t = System.currentTimeMillis()
            val opt = assertNotNull(ev.optimal(leg.from.position, leg.to.position), leg.title)
            val straight = ev.along(listOf(leg.from.position, leg.to.position))
            println("${leg.title}: прямая ${leg.from.position.distanceTo(leg.to.position).toInt()} м; " +
                "оптимум ${opt.seconds.toInt()} с / ${opt.length.toInt()} м, по прямой ${straight.seconds.toInt()} с, " +
                "запретов на прямой ${straight.violations.size}; ${System.currentTimeMillis() - t} мс")
            assertTrue(opt.seconds <= straight.seconds + 1e-6)
        }
    }
}
