package waypoint.tools.pack

import waypoint.core.geometry.Box
import waypoint.core.geometry.simplifyPolyline
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackBox
import waypoint.core.pack.PackGrid
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackLegs
import waypoint.core.pack.PackMeta
import waypoint.core.pack.PackRoute
import waypoint.core.route.LegReport
import waypoint.core.route.RouteStats
import waypoint.core.terrain.RunnabilityTable
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.geo.GroundImage
import java.io.File

/** Общая часть сборки пака: тайлы, растры, связки, метаданные — для карты из OMAP и для сгенерированной. */
object PackAssembler {

    /** Папку перезаписываем, только если это прежний пак или она пуста: чужое не удаляем. */
    fun prepareOutput(out: File) {
        if (!out.exists()) return
        val entries = out.list().orEmpty()
        require(entries.isEmpty() || File(out, MapPack.META).exists()) { "Папка $out не пуста и не похожа на пак, не трогаю её" }
        out.deleteRecursively()
    }

    fun write(
        out: File,
        name: String,
        title: String,
        mapBounds: Box,
        terrain: TerrainGrid,
        heights: FloatArray,
        table: RunnabilityTable,
        legs: List<PackLeg>,
        background: GroundImage,
        log: (String) -> Unit = ::println,
    ): PackMeta {
        prepareOutput(out)
        val tiles = TileWriter.write(background, out, log = log)
        val meta = PackMeta(
            name = name,
            title = title,
            mapBounds = PackBox.of(mapBounds),
            grid = PackGrid.of(terrain.spec),
            tiles = tiles,
            runnability = table.name,
            maxRelativeSpeed = table.maxSpeed,
        )
        PackFiles.write(out, meta, PackLegs(name, legs), terrain, heights)
        val size = out.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        log("Пак: $out, ${"%.1f".format(size / 1e6)} МБ, связок ${legs.size}")
        return meta
    }

    /** Варианты и «напрямик» из разбора связки — в связку пака. */
    fun withReport(leg: PackLeg, r: LegReport?): PackLeg =
        leg.copy(options = r?.options.orEmpty().map(::route), straight = r?.straight?.let(::route))

    /** Путь по центрам ячеек сильно избыточен: упрощаем до полуметра. */
    fun route(s: RouteStats) = PackRoute.of(s.seconds, s.length, s.climb, simplifyPolyline(s.points, 0.5))
}
