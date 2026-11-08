package waypoint.tools

import waypoint.core.grid.GridSpec
import waypoint.core.grid.Rasterizer
import waypoint.core.map.GeometryKind
import waypoint.tools.geo.Georeferencing
import waypoint.tools.omap.OmapReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Проверка привязки на настоящей карте: полевой GPX-трек должен идти вдоль троп и дорог карты.
 * Сравниваем долю точек трека рядом с тропой при верной привязке и с перевёрнутым знаком поворота.
 * Пропускается, если карты или трека нет (они не хранятся в репозитории).
 */
class GeorefCheckTest {
    @Test
    fun `GPX-трек ложится на тропы карты`() {
        val omap = File("../maps/macha.omap")
        val gpx = File("../maps/macha.gpx")
        if (!omap.exists() || !gpx.exists()) return
        val doc = OmapReader.read(omap)
        val map = doc.toVectorMap()
        val spec = GridSpec.covering(map.bounds, 2.0)
        val nearPath = BooleanArray(spec.size)
        for (f in map.features) {
            val n = f.code.substringBefore('.').toIntOrNull() ?: continue
            if (f.kind == GeometryKind.LINE && n in 502..508) {
                for (line in f.parts) Rasterizer.strokePolyline(line, 6.0, spec) { nearPath[it] = true }
            }
        }
        val track = GpxReader.read(gpx)
        fun share(g: Georeferencing): Double {
            val inside = track.map { (lat, lon) -> spec.indexOf(g.latLonToGround(lat, lon)) }.filter { it >= 0 }
            return inside.count { nearPath[it] }.toDouble() / track.size
        }
        val g = doc.georeferencing
        val good = share(g)
        val wrong = share(Georeferencing(g.scale, -g.grivationDeg, g.auxiliaryScaleFactor, g.utmZone, g.projectedRef, g.mapRefMm, g.geographicRef))
        println("точек трека в 6 м от тропы: верная привязка %.0f%%, знак поворота наоборот %.0f%%".format(good * 100, wrong * 100))
        assertTrue(good > wrong)
    }
}
