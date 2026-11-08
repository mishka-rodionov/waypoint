package waypoint.tools.pack

import waypoint.core.pack.PackLeg
import waypoint.core.course.ControlType
import waypoint.core.terrain.Discipline
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
 * --legs (отобранные связки), --title, --cell (размер ячейки, м), --out (папка пака), --scale (масштаб карты),
 * --disciplines (через запятую: forest, mtbo, ski; по умолчанию все три), --score (задач «по выбору» на дисциплину).
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
    val disciplines = PackAssembler.parseDisciplines(opts["disciplines"], listOf(Discipline.FOREST, Discipline.MTBO, Discipline.SKI))
    val scoreCount = opts["score"]?.toInt() ?: 3
    // Высоты на сетке растра: одна на все дисциплины (сетка у всех одна — рамка карты и шаг).
    var heights: FloatArray? = null
    val heightsFor = { t: TerrainGrid -> heights ?: (loaded.elevation?.resample(t.spec) ?: FloatArray(t.spec.size)).also { heights = it } }
    val data = disciplines.map { d ->
        PackAssembler.discipline(d, loaded.map, cell, heightsFor, base, scoreCount, seed = 11) { model, _ ->
            if (d == Discipline.FOREST) {
                // Кросс: отобранные заранее связки (mineLegs) и связки дистанций карты.
                val legs = collectLegs(loaded, legsFile)
                println("Связок: ${legs.size}, ищу варианты…")
                val reports = LegMining.explore(model, legs.map { it.from to it.to })
                legs.zip(reports).map { (l, r) -> PackAssembler.withReport(l, r) }
            } else {
                PackAssembler.minedLegs(model, loaded.map, "$base-${d.id}", candidates = 400, count = 30, minLen = 400.0, maxLen = 1500.0, seed = 5)
            }
        }
    }

    println("Тайлы фона из $background")
    val image = WorldImage.read(background, loaded.georef)
    PackAssembler.write(out, base, opts["title"] ?: loaded.name, loaded.map.bounds, opts["scale"]?.toDouble() ?: 7500.0, data, heights!!, image)
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

