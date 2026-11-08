package waypoint.tools.pack

import waypoint.core.pack.PackLeg
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.MovementModel
import waypoint.core.course.ControlType
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.MapLoader
import waypoint.tools.geo.WorldImage
import waypoint.tools.legs.LegMining
import waypoint.tools.legs.LegsFile
import java.io.File

/**
 * Сборка пака карты для приложения ([MapPack]): тайлы фона, растры проходимости и высот, связки с вариантами.
 *
 * Запуск: `./gradlew :tools:buildPack` (берёт первую .omap и .shp из maps/) или
 * `./gradlew :tools:buildPack -Pargs="--map maps/macha.omap --title Мача"`.
 * Параметры: --map, --contours, --interval (сечение, м), --background (PNG с world-файлом),
 * --legs (отобранные связки), --title, --cell (размер ячейки, м), --out (папка пака).
 * По умолчанию пак кладётся в app/src/main/assets/packs/<карта>/ (папка в .gitignore).
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    val mapFile = opts["map"]?.let(::File) ?: File("maps").listFiles { f -> f.extension == "omap" }?.minOrNull()
        ?: error("Нет карты: --map путь.omap или положите её в maps/")
    val base = mapFile.nameWithoutExtension
    val contours = opts["contours"]?.let(::File) ?: File("maps").listFiles { f -> f.extension == "shp" }?.minOrNull()
    val background = opts["background"]?.let(::File) ?: File(mapFile.parentFile, "$base.png")
    val legsFile = opts["legs"]?.let(::File) ?: File(mapFile.parentFile, "$base.legs.json")
    val cell = opts["cell"]?.toDouble() ?: 2.0
    val out = opts["out"]?.let(::File) ?: File("app/src/main/assets/packs/$base")
    require(background.exists() && WorldImage.worldFileFor(background) != null) {
        "Нет фона карты: экспортируйте карту из Mapper в $background с world-файлом (.pgw) или передайте --background"
    }
    PackAssembler.prepareOutput(out)

    val started = System.currentTimeMillis()
    val loaded = MapLoader.load(mapFile, contours, opts["interval"]?.toDouble() ?: 2.5, log = ::println)
    val terrain = TerrainGrid.build(loaded.map, ISOM_2000, cell)
    val spec = terrain.spec
    println("Проходимость ${spec.width}×${spec.height}")
    val heights = loaded.elevation?.resample(spec) ?: FloatArray(spec.size)
    // Варианты считаем на той же сетке высот, что получит приложение.
    val model = MovementModel(terrain, ElevationGrid(spec, heights), maxRelativeSpeed = ISOM_2000.maxSpeed)

    val legs = collectLegs(loaded, legsFile)
    println("Связок: ${legs.size}, ищу варианты…")
    val reports = LegMining.explore(model, legs.map { it.from to it.to })
    val packLegs = legs.zip(reports).map { (l, r) -> PackAssembler.withReport(l, r) }

    println("Тайлы фона из $background")
    val image = WorldImage.read(background, loaded.georef)
    PackAssembler.write(out, base, opts["title"] ?: loaded.name, loaded.map.bounds, terrain, heights, ISOM_2000, packLegs, image)
    println("Готово за ${(System.currentTimeMillis() - started) / 1000} с")
}

/** Отобранные связки (по убыванию интересности), затем связки дистанций карты. */
private fun collectLegs(loaded: waypoint.tools.LoadedMap, legsFile: File): List<PackLeg> {
    val mined = if (legsFile.exists()) LegsFile.read(legsFile) else emptyList()
    val out = ArrayList<PackLeg>()
    mined.forEachIndexed { i, m ->
        out.add(
            PackLeg(
                id = "mined-${i + 1}", title = "Связка ${i + 1}",
                fromX = m.from.x, fromY = m.from.y, toX = m.to.x, toY = m.to.y,
                interest = m.interest, choiceGap = m.choiceGap, straightLoss = m.straightLoss,
            ),
        )
    }
    for (course in loaded.courses.filter { !it.freeOrder }) {
        course.legs.forEachIndexed { i, leg ->
            out.add(
                PackLeg(
                    id = "course-${course.name}-${i + 1}", title = "${course.name}: ${leg.from.id} → ${leg.to.id}",
                    fromX = leg.from.position.x, fromY = leg.from.position.y,
                    toX = leg.to.position.x, toY = leg.to.position.y,
                    fromStart = leg.from.type == ControlType.START,
                ),
            )
        }
    }
    return out
}

