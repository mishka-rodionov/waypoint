package waypoint.tools.pack

import waypoint.core.geometry.Vec2
import waypoint.core.geometry.simplifyPolyline
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackBox
import waypoint.core.pack.PackGrid
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackLegs
import waypoint.core.pack.PackMeta
import waypoint.core.pack.PackRoute
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.LegExplorer
import waypoint.core.route.LegReport
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteStats
import waypoint.core.course.ControlType
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.MapLoader
import waypoint.tools.geo.WorldImage
import waypoint.tools.legs.LegsFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    prepareOutput(out)

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
    val reports = explore(model, legs.map { it.from to it.to })
    val packLegs = legs.zip(reports).map { (l, r) ->
        l.copy(options = r?.options.orEmpty().map(::route), straight = r?.straight?.let(::route))
    }

    println("Тайлы фона из $background")
    val image = WorldImage.read(background, loaded.georef)
    val tiles = TileWriter.write(image, out, log = ::println)

    val meta = PackMeta(
        name = base,
        title = opts["title"] ?: loaded.name,
        mapBounds = PackBox.of(loaded.map.bounds),
        grid = PackGrid.of(spec),
        tiles = tiles,
        runnability = ISOM_2000.name,
        maxRelativeSpeed = ISOM_2000.maxSpeed,
    )
    PackFiles.write(out, meta, PackLegs(base, packLegs), terrain, heights)
    val size = out.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    println("Пак: $out, ${"%.1f".format(size / 1e6)} МБ, ${(System.currentTimeMillis() - started) / 1000} с")
}

/** Папку перезаписываем, только если это прежний пак или она пуста: чужое не удаляем. */
private fun prepareOutput(out: File) {
    if (!out.exists()) return
    val entries = out.list().orEmpty()
    require(entries.isEmpty() || File(out, MapPack.META).exists()) { "Папка $out не пуста и не похожа на пак, не трогаю её" }
    out.deleteRecursively()
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

private fun explore(model: MovementModel, pairs: List<Pair<Vec2, Vec2>>): List<LegReport?> {
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val pool = Executors.newFixedThreadPool(threads)
    val explorers = ThreadLocal.withInitial { LegExplorer(model) }
    val done = AtomicInteger()
    val result = pairs.map { (a, b) ->
        pool.submit<LegReport?> {
            explorers.get().explore(a, b).also {
                val n = done.incrementAndGet()
                if (n % 10 == 0) println("  $n/${pairs.size}")
            }
        }
    }.map { it.get() }
    pool.shutdown()
    return result
}

/** Путь по центрам ячеек сильно избыточен: упрощаем до полуметра. */
private fun route(s: RouteStats) = PackRoute.of(s.seconds, s.length, s.climb, simplifyPolyline(s.points, 0.5))
