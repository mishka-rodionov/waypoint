package waypoint.tools.pack

import waypoint.core.geometry.Box
import waypoint.core.geometry.simplifyPolyline
import waypoint.core.map.VectorMap
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackDiscipline
import waypoint.core.pack.PackBox
import waypoint.core.pack.PackGrid
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackLegs
import waypoint.core.pack.PackMeta
import waypoint.core.pack.PackRoute
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.LegReport
import waypoint.core.route.MovementModel
import waypoint.core.terrain.Discipline
import waypoint.core.terrain.MappedArea
import waypoint.core.route.RouteStats
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.geo.GroundImage
import waypoint.tools.legs.LegMining
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
        scale: Double,
        disciplines: List<DisciplineData>,
        heights: FloatArray,
        background: GroundImage,
        log: (String) -> Unit = ::println,
    ): PackMeta {
        prepareOutput(out)
        val tiles = TileWriter.write(background, out, log = log)
        val first = Discipline.byId(disciplines.first().info.id) ?: Discipline.FOREST
        val meta = PackMeta(
            name = name,
            title = title,
            mapBounds = PackBox.of(mapBounds),
            grid = PackGrid.of(disciplines.first().terrain.spec),
            tiles = tiles,
            runnability = first.table.name,
            maxRelativeSpeed = first.table.maxSpeed,
            scale = scale,
            disciplines = disciplines.map { it.info },
        )
        PackFiles.write(out, meta, disciplines, heights)
        val size = out.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        log("Пак: $out, ${"%.1f".format(size / 1e6)} МБ; " + disciplines.joinToString { "${it.info.title}: связок ${it.legs.legs.size}, задач ${it.score?.problems?.size ?: 0}" })
        return meta
    }

    /**
     * Данные дисциплины [d]: растр по её таблице, связки от [legs] (получает модель и растр),
     * [scoreProblems] задач «по выбору».
     */
    fun discipline(
        d: Discipline,
        map: VectorMap,
        cell: Double,
        heights: (TerrainGrid) -> FloatArray,
        mapName: String,
        scoreProblems: Int,
        seed: Long,
        scoreRadius: Double = 800.0,
        log: (String) -> Unit = ::println,
        legs: (MovementModel, TerrainGrid) -> List<PackLeg>,
    ): DisciplineData {
        log("Дисциплина «${d.title}»")
        val terrain = TerrainGrid.build(map, d.table, cell)
        val model = d.model(terrain, ElevationGrid(terrain.spec, heights(terrain)))
        val packLegs = legs(model, terrain)
        val score = if (scoreProblems > 0) {
            ScoreProblems.build(model, MappedArea.of(map), map.bounds, scoreProblems, seed, radius = scoreRadius, idPrefix = "${d.id}-score", log = log)
        } else null
        val info = PackDiscipline(
            id = d.id, title = d.title,
            terrain = MapPack.terrainFile(d.id), legs = MapPack.legsFile(d.id),
            scoreO = score?.let { MapPack.scoreFile(d.id) },
        )
        return DisciplineData(info, terrain, PackLegs(mapName, packLegs), score)
    }

    /** Отобранные интересные связки для дисциплины (имена «Связка N»). */
    fun minedLegs(
        model: MovementModel, map: VectorMap, prefix: String, candidates: Int, count: Int,
        minLen: Double, maxLen: Double, seed: Long, spacing: Double = 150.0,
    ): List<PackLeg> {
        val (chosen, _) = LegMining.mine(model, MappedArea.of(map), candidates, count, minLen, maxLen, seed, spacing)
        return chosen.mapIndexed { i, r ->
            withReport(
                PackLeg(
                    id = "$prefix-${i + 1}", title = "Связка ${i + 1}",
                    fromX = r.from.x, fromY = r.from.y, toX = r.to.x, toY = r.to.y,
                    interest = r.interest, choiceGap = r.choiceGap, straightLoss = r.straightLoss,
                ),
                r,
            )
        }
    }

    /** Список дисциплин из аргумента «forest,mtbo,ski». */
    fun parseDisciplines(arg: String?, default: List<Discipline>): List<Discipline> =
        arg?.split(",")?.map { id -> Discipline.byId(id.trim()) ?: error("Нет дисциплины $id; есть: ${Discipline.ALL.joinToString { it.id }}") } ?: default

    /** Варианты и «напрямик» из разбора связки — в связку пака. */
    fun withReport(leg: PackLeg, r: LegReport?): PackLeg =
        leg.copy(options = r?.options.orEmpty().map(::route), straight = r?.straight?.let(::route))

    /** Путь по центрам ячеек сильно избыточен: упрощаем (по умолчанию до полуметра). */
    fun route(s: RouteStats, tolerance: Double = 0.5) = PackRoute.of(s.seconds, s.length, s.climb, simplifyPolyline(s.points, tolerance))
}
