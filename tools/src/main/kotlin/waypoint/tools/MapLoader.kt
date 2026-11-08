package waypoint.tools

import waypoint.core.course.Course
import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.map.VectorMap
import waypoint.core.map.GeometryKind
import waypoint.core.relief.Contour
import waypoint.core.relief.ContourHeights
import waypoint.core.relief.ElevationGrid
import waypoint.core.relief.MapContour
import waypoint.tools.geo.Georeferencing
import waypoint.tools.omap.CoursesReader
import waypoint.tools.omap.OmapReader
import waypoint.tools.relief.ShapefileContours
import java.io.File

/** Карта, готовая для песочницы: векторные объекты, дистанции и (если есть) рельеф. */
class LoadedMap(
    val name: String,
    val map: VectorMap,
    val georef: Georeferencing,
    val courses: List<Course>,
    /** Рельеф по горизонталям карты (уровни подобраны с помощью SRTM) или грубый SRTM, если горизонталей нет. */
    val elevation: ElevationGrid?,
    /** Грубая модель SRTM: для сравнения. */
    val srtm: ElevationGrid? = null,
)

object MapLoader {

    /**
     * Загружает OMAP, рядом лежащий `<карта>.courses` и, если указан, шейпфайл горизонталей.
     * Подложки (templates) из OMAP не читаются. [contourInterval] — сечение рельефа карты в метрах.
     */
    fun load(
        omap: File,
        contoursShp: File?,
        contourInterval: Double = 2.5,
        log: (String) -> Unit = {},
    ): LoadedMap {
        var t = System.currentTimeMillis()
        fun lap(what: String) {
            val now = System.currentTimeMillis()
            log("$what: ${now - t} мс")
            t = now
        }
        val doc = OmapReader.read(omap)
        val map = doc.toVectorMap()
        lap("OMAP, ${map.features.size} объектов")

        val coursesFile = File(omap.path + ".courses")
        val courses = if (coursesFile.exists()) CoursesReader.read(coursesFile, doc.georeferencing) else emptyList()

        val srtm = contoursShp?.takeIf { it.exists() }?.let { shp ->
            elevationFor(shp, doc.georeferencing, map.bounds, 10.0).also { lap("рельеф SRTM") }
        }
        val elevation = srtm?.let { reference ->
            mapRelief(map, reference, contourInterval, log)?.also { lap("рельеф по горизонталям карты") } ?: reference
        }
        return LoadedMap(omap.nameWithoutExtension, map, doc.georeferencing, courses, elevation, srtm)
    }

    /**
     * Рельеф по горизонталям карты: уровни горизонталей подбираются по соседству и подсказке SRTM,
     * поверхность между ними — гладкая интерполяция. Вдали от горизонталей карты — SRTM, сдвинутый к уровням карты.
     */
    fun mapRelief(map: VectorMap, reference: ElevationGrid, interval: Double, log: (String) -> Unit = {}): ElevationGrid? {
        val contours = map.features.filter { f ->
            f.kind == GeometryKind.LINE && f.code.substringBefore('.').let { it == "101" || it == "102" }
        }.map { MapContour(it.parts, index = it.code.startsWith("102")) }
        if (contours.size < 10) return null
        val result = ContourHeights.assign(contours, reference::heightAt, interval)
        val valid = result.heights.filter { !it.isNaN() }
        log("горизонтали карты: ${contours.size}, высоты ${valid.min()}..${valid.max()} м, " +
            "согласованность соседей ${"%.0f".format(result.agreement * 100)}%")
        val offsets = result.heights.indices.filter { !result.heights[it].isNaN() }
            .map { result.heights[it] - result.referenceMeans[it] }.sorted()
        val offset = offsets[offsets.size / 2]
        val withHeights = contours.flatMapIndexed { i, c ->
            if (result.heights[i].isNaN()) emptyList() else c.parts.map { Contour(result.heights[i], it) }
        }
        return ElevationGrid.fromContoursMultiscale(withHeights, map.bounds.expand(200.0), 2.0) { p -> reference.heightAt(p) + offset }
    }

    fun elevationFor(shp: File, georef: Georeferencing, bounds: Box, cell: Double): ElevationGrid {
        val margin = bounds.expand(200.0)
        val corners = listOf(
            Vec2(margin.minX, margin.minY), Vec2(margin.maxX, margin.minY),
            Vec2(margin.minX, margin.maxY), Vec2(margin.maxX, margin.maxY),
        )
        // Рамка карты в градусах с запасом, чтобы не читать весь лист SRTM.
        val lonLat = corners.map { groundToLonLat(georef, it) }
        val raw = ShapefileContours.read(
            shp,
            minLon = lonLat.minOf { it.x } - 0.01, minLat = lonLat.minOf { it.y } - 0.01,
            maxLon = lonLat.maxOf { it.x } + 0.01, maxLat = lonLat.maxOf { it.y } + 0.01,
        )
        val contours = raw.filter { !it.height.isNaN() }.map { c ->
            Contour(c.height, c.lon.indices.map { i -> georef.latLonToGround(c.lat[i], c.lon[i]) })
        }
        return ElevationGrid.fromContours(contours, margin, cell)
    }

    /** Обратный перевод нужен только для рамки: решаем методом Ньютона через прямую функцию. */
    private fun groundToLonLat(georef: Georeferencing, target: Vec2): Vec2 {
        val ref = requireNotNull(georef.geographicRef) { "В карте нет географической опорной точки" }
        var lat = ref.y
        var lon = ref.x
        // Начальное приближение — опорная точка; дальше несколько итераций по численному якобиану.
        repeat(8) {
            val p = georef.latLonToGround(lat, lon)
            val d = 1e-5
            val pLat = georef.latLonToGround(lat + d, lon)
            val pLon = georef.latLonToGround(lat, lon + d)
            val a = (pLat.x - p.x) / d
            val b = (pLon.x - p.x) / d
            val c = (pLat.y - p.y) / d
            val e = (pLon.y - p.y) / d
            val det = a * e - b * c
            val rx = target.x - p.x
            val ry = target.y - p.y
            lat += (e * rx - b * ry) / det
            lon += (-c * rx + a * ry) / det
        }
        return Vec2(lon, lat)
    }
}
